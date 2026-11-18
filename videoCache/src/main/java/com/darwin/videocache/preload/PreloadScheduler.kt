package com.darwin.videocache.preload

import com.darwin.videocache.api.FetchPlan
import com.darwin.videocache.api.PreloadPriority
import com.darwin.videocache.api.PreloadRequest
import com.darwin.videocache.api.PreloadState
import com.darwin.videocache.api.PreloadStrategy
import com.darwin.videocache.api.ResourceMeta
import com.darwin.videocache.api.ResourceType
import com.darwin.videocache.api.VideoCacheListener
import com.darwin.videocache.cache.CacheFiller
import com.darwin.videocache.cache.CacheStore
import com.darwin.videocache.core.ResourceRegistry
import com.darwin.videocache.hls.HlsPlaylistParser
import com.darwin.videocache.upstream.UpstreamClient
import com.darwin.videocache.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

internal class PreloadScheduler(
    private val store: CacheStore,
    private val registry: ResourceRegistry,
    private val filler: CacheFiller,
    private val upstream: UpstreamClient,
    private val defaultStrategy: PreloadStrategy,
    private val maxConcurrent: Int,
) {
    private val seq = AtomicLong(0)
    private val runningCount = AtomicInteger(0)
    private val queueMutex = Mutex()
    private val queue = PriorityBlockingQueue<PreloadTask>()
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val listeners = CopyOnWriteArraySet<VideoCacheListener>()

    private val dispatcher = Executors.newFixedThreadPool(
        maxOf(1, maxConcurrent),
    ) { r -> Thread(r, "video-cache-preload").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun addListener(listener: VideoCacheListener) {
        listeners += listener
    }

    fun removeListener(listener: VideoCacheListener) {
        listeners -= listener
    }

    fun submit(request: PreloadRequest, resolvedKey: String, type: ResourceType) {
        val existing = activeJobs[resolvedKey]
        if (existing?.isActive == true) return

        val task = PreloadTask(
            id = seq.incrementAndGet(),
            request = request,
            cacheKey = resolvedKey,
            type = type,
            priority = request.priority,
        )
        queue.offer(task)
        notifyState(resolvedKey, request.url, PreloadState.QUEUED)
        pump()
    }

    fun cancel(cacheKey: String) {
        queue.removeIf { it.cacheKey == cacheKey }
        activeJobs.remove(cacheKey)?.cancel()
    }

    fun cancelAll() {
        queue.clear()
        activeJobs.keys.toList().forEach { cancel(it) }
    }

    fun shutdown() {
        cancelAll()
        scope.cancel()
        dispatcher.close()
    }

    private fun pump() {
        scope.launch {
            queueMutex.withLock {
                while (runningCount.get() < maxConcurrent) {
                    val task = queue.poll() ?: break
                    if (activeJobs[task.cacheKey]?.isActive == true) continue
                    runningCount.incrementAndGet()
                    val job = scope.launch {
                        try {
                            execute(task)
                        } finally {
                            activeJobs.remove(task.cacheKey)
                            runningCount.decrementAndGet()
                            pump()
                        }
                    }
                    activeJobs[task.cacheKey] = job
                }
            }
        }
    }

    private suspend fun execute(task: PreloadTask) {
        val url = task.request.url
        val key = task.cacheKey
        notifyState(key, url, PreloadState.RUNNING)
        try {
            registry.register(key, url, task.type, task.request.headers)
            val strategy = task.request.strategy ?: defaultStrategy
            val meta = buildMeta(url, key, task.type, task.request.headers)
            val plans = strategy.plan(meta)
            var produced = 0L
            for (plan in plans) {
                if (!coroutineContext.isActive) break
                when (plan) {
                    is FetchPlan.ByteRange -> {
                        val entry = store.getOrCreate(plan.cacheKey, plan.url, ResourceType.PROGRESSIVE)
                        filler.ensureRange(
                            entry = entry,
                            start = plan.start,
                            endExclusive = plan.endExclusive,
                            headers = task.request.headers,
                            sink = null,
                        ) { bytes ->
                            produced = bytes
                            notifyProgress(key, url, produced, meta.contentLength)
                        }
                        produced = maxOf(produced, store.getCachedBytes(plan.cacheKey))
                        notifyProgress(key, url, produced, meta.contentLength)
                    }
                    is FetchPlan.FullResource -> {
                        registry.register(
                            plan.cacheKey,
                            plan.url,
                            ResourceType.PROGRESSIVE,
                            task.request.headers,
                        )
                        val entry = store.getOrCreate(plan.cacheKey, plan.url, ResourceType.PROGRESSIVE)
                        filler.fillFully(entry, task.request.headers) { bytes ->
                            // HLS 分片各自独立 key，进度用累计分片字节更准确
                            notifyProgress(
                                key,
                                url,
                                produced + bytes,
                                if (meta.type == ResourceType.HLS) -1L else meta.contentLength,
                            )
                        }
                        produced += store.getCachedBytes(plan.cacheKey)
                        notifyProgress(
                            key,
                            url,
                            produced,
                            if (meta.type == ResourceType.HLS) -1L else meta.contentLength,
                        )
                    }
                }
            }
            if (coroutineContext.isActive) {
                notifyProgress(
                    key,
                    url,
                    if (meta.type == ResourceType.HLS) produced else store.getCachedBytes(key),
                    meta.contentLength,
                )
                notifyState(key, url, PreloadState.SUCCESS)
            } else {
                notifyState(key, url, PreloadState.CANCELLED)
            }
        } catch (t: Throwable) {
            if (!coroutineContext.isActive) {
                notifyState(key, url, PreloadState.CANCELLED)
            } else {
                notifyState(key, url, PreloadState.FAILED, t)
            }
        }
    }

    private fun buildMeta(
        url: String,
        key: String,
        type: ResourceType,
        headers: Map<String, String>,
    ): ResourceMeta {
        return when (type) {
            ResourceType.PROGRESSIVE -> {
                val entry = store.getOrCreate(key, url, ResourceType.PROGRESSIVE)
                if (entry.contentLength <= 0) {
                    runCatching {
                        filler.ensureRange(entry, 0, 1, headers, sink = null)
                    }
                }
                ResourceMeta(
                    url = url,
                    cacheKey = key,
                    type = type,
                    contentLength = entry.contentLength,
                    contentType = entry.contentType,
                )
            }
            ResourceType.HLS -> {
                val (text, contentType) = fetchAndCachePlaylist(url, key, headers)
                val parsed = HlsPlaylistParser.parseAndRewrite(url, text) { it }
                val mediaSegments = parsed.mediaUrls.filterNot {
                    it.substringBefore('?').endsWith(".m3u8", true)
                }
                // master：取第一个子 playlist 的分片做预加载规划，并缓存子清单
                val segmentUrls = if (parsed.isMaster && parsed.mediaUrls.isNotEmpty()) {
                    val nested = parsed.mediaUrls.first()
                    val nestedKey = com.darwin.videocache.api.CacheKeys.sha256(nested)
                    registry.register(nestedKey, nested, ResourceType.HLS, headers)
                    val (nestedText, _) = fetchAndCachePlaylist(nested, nestedKey, headers)
                    HlsPlaylistParser.parseAndRewrite(nested, nestedText) { it }
                        .mediaUrls
                        .filterNot { it.substringBefore('?').endsWith(".m3u8", true) }
                } else {
                    mediaSegments
                }
                ResourceMeta(
                    url = url,
                    cacheKey = key,
                    type = type,
                    contentLength = -1L,
                    contentType = contentType,
                    hlsSegmentUrls = segmentUrls,
                )
            }
        }
    }

    private fun fetchAndCachePlaylist(
        playlistUrl: String,
        cacheKey: String,
        headers: Map<String, String>,
    ): Pair<String, String?> {
        val entry = store.getOrCreate(cacheKey, playlistUrl, ResourceType.HLS)
        entry.readFullyOrNull()?.let { cached ->
            return String(cached, Charsets.UTF_8) to entry.contentType
        }
        val (text, contentType) = upstream.fetchText(playlistUrl, headers)
        val bytes = text.toByteArray(Charsets.UTF_8)
        store.ensureSpace(bytes.size.toLong())
        entry.writeFully(bytes, contentType ?: "application/vnd.apple.mpegurl")
        return text to contentType
    }

    private fun notifyState(key: String, url: String, state: PreloadState, error: Throwable? = null) {
        listeners.forEach { it.onPreloadState(key, url, state, error) }
    }

    private fun notifyProgress(key: String, url: String, cached: Long, total: Long) {
        listeners.forEach { it.onPreloadProgress(key, url, cached, total) }
    }

    private data class PreloadTask(
        val id: Long,
        val request: PreloadRequest,
        val cacheKey: String,
        val type: ResourceType,
        val priority: PreloadPriority,
    ) : Comparable<PreloadTask> {
        override fun compareTo(other: PreloadTask): Int {
            val byPriority = other.priority.weight.compareTo(priority.weight)
            if (byPriority != 0) return byPriority
            return id.compareTo(other.id)
        }
    }
}
