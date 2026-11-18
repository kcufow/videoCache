package com.darwin.videocache

import android.content.Context
import com.darwin.videocache.api.EvictionPolicy
import com.darwin.videocache.api.PreloadRequest
import com.darwin.videocache.api.ProxyUrlOptions
import com.darwin.videocache.api.ResourceType
import com.darwin.videocache.api.VideoCacheConfig
import com.darwin.videocache.api.VideoCacheListener
import com.darwin.videocache.cache.CacheFiller
import com.darwin.videocache.cache.CacheStore
import com.darwin.videocache.core.ResourceRegistry
import com.darwin.videocache.preload.PreloadScheduler
import com.darwin.videocache.proxy.ProxyRouter
import com.darwin.videocache.proxy.ProxyServer
import com.darwin.videocache.upstream.UpstreamClient
import com.darwin.videocache.util.UrlUtils
import java.io.File
import kotlin.LazyThreadSafetyMode

/** 顶层快捷入口 */
val videoCacheManager by lazy { VideoCacheManager.instance }

/**
 * 视频预加载 + 边下边播门面。播放器无关：通过 [getProxyUrl] 拿到本地地址即可播放。
 */
class VideoCacheManager private constructor() {

    companion object {
        val instance: VideoCacheManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            VideoCacheManager()
        }
    }

    @Volatile
    private var initialized = false

    private lateinit var config: VideoCacheConfig
    private lateinit var store: CacheStore
    private lateinit var registry: ResourceRegistry
    private lateinit var upstream: UpstreamClient
    private lateinit var filler: CacheFiller
    private lateinit var scheduler: PreloadScheduler
    private lateinit var proxyServer: ProxyServer

    fun init(context: Context, config: VideoCacheConfig = VideoCacheConfig()) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            this.config = config
            val root = config.cacheDir ?: File(context.applicationContext.cacheDir, "video_cache")
            root.mkdirs()
            store = CacheStore(root, config.maxCacheBytes, config.evictionPolicy)
            registry = ResourceRegistry()
            upstream = UpstreamClient(
                connectTimeoutSeconds = config.connectTimeoutSeconds,
                readTimeoutSeconds = config.readTimeoutSeconds,
                defaultHeaders = config.defaultHeaders,
            )
            filler = CacheFiller(store, upstream)
            scheduler = PreloadScheduler(
                store = store,
                registry = registry,
                filler = filler,
                upstream = upstream,
                defaultStrategy = config.preloadStrategy,
                maxConcurrent = config.maxConcurrentPreloads,
            )
            val router = ProxyRouter(
                registry = registry,
                store = store,
                filler = filler,
                upstream = upstream,
                baseUrlProvider = { proxyServer.baseUrl },
            )
            proxyServer = ProxyServer(config.proxyPort, router)
            proxyServer.start()
            initialized = true
        }
    }

    fun isInitialized(): Boolean = initialized

    fun getProxyUrl(url: String, options: ProxyUrlOptions = ProxyUrlOptions()): String {
        ensureInit()
        val type = UrlUtils.detectType(url, options.resourceType)
        val key = options.cacheKey ?: config.cacheKeyFactory.create(url)
        val headers = config.defaultHeaders + options.headers
        registry.register(key, url, type, headers)
        store.getOrCreate(key, url, type)
        return when (type) {
            ResourceType.PROGRESSIVE ->
                "${proxyServer.baseUrl}/p/${UrlUtils.encodePathSegment(key)}"
            ResourceType.HLS ->
                "${proxyServer.baseUrl}/h/${UrlUtils.encodePathSegment(key)}/index.m3u8"
        }
    }

    fun preload(request: PreloadRequest) {
        ensureInit()
        val type = UrlUtils.detectType(request.url)
        val key = request.cacheKey ?: config.cacheKeyFactory.create(request.url)
        registry.register(key, request.url, type, config.defaultHeaders + request.headers)
        scheduler.submit(request, key, type)
    }

    fun preload(
        url: String,
        priority: com.darwin.videocache.api.PreloadPriority =
            com.darwin.videocache.api.PreloadPriority.NORMAL,
    ) {
        preload(PreloadRequest(url = url, priority = priority))
    }

    fun cancelPreload(urlOrKey: String) {
        ensureInit()
        val key = store.getEntry(urlOrKey)?.key
            ?: config.cacheKeyFactory.create(urlOrKey).takeIf { store.getEntry(it) != null }
            ?: urlOrKey
        scheduler.cancel(key)
    }

    fun cancelAllPreloads() {
        ensureInit()
        scheduler.cancelAll()
    }

    fun addListener(listener: VideoCacheListener) {
        ensureInit()
        store.addListener(listener)
        scheduler.addListener(listener)
    }

    fun removeListener(listener: VideoCacheListener) {
        if (!initialized) return
        store.removeListener(listener)
        scheduler.removeListener(listener)
    }

    fun isCached(urlOrKey: String): Boolean {
        ensureInit()
        return resolveKey(urlOrKey)?.let { store.isFullyCached(it) } == true
    }

    fun getCachedBytes(urlOrKey: String): Long {
        ensureInit()
        return resolveKey(urlOrKey)?.let { store.getCachedBytes(it) } ?: 0L
    }

    fun getUsedBytes(): Long {
        ensureInit()
        return store.getUsedBytes()
    }

    fun getCacheDir(): File {
        ensureInit()
        return store.getCacheDir()
    }

    fun remove(urlOrKey: String) {
        ensureInit()
        val key = resolveKey(urlOrKey) ?: return
        scheduler.cancel(key)
        store.remove(key)
        registry.remove(key)
    }

    fun clearAll() {
        ensureInit()
        scheduler.cancelAll()
        store.clearAll()
        registry.clear()
    }

    fun trimToSize(maxBytes: Long) {
        ensureInit()
        store.trimToSize(maxBytes)
    }

    fun setEvictionPolicy(policy: EvictionPolicy) {
        ensureInit()
        store.setEvictionPolicy(policy)
    }

    fun setMaxCacheBytes(maxBytes: Long) {
        ensureInit()
        store.setMaxCacheBytes(maxBytes)
    }

    fun shutdown() {
        if (!initialized) return
        synchronized(this) {
            if (!initialized) return
            scheduler.shutdown()
            proxyServer.stop()
            initialized = false
        }
    }

    private fun resolveKey(urlOrKey: String): String? {
        store.getEntry(urlOrKey)?.let { return it.key }
        val hashed = config.cacheKeyFactory.create(urlOrKey)
        return if (store.getEntry(hashed) != null) hashed else urlOrKey.takeIf { store.getEntry(it) != null }
    }

    private fun ensureInit() {
        check(initialized) {
            "VideoCacheManager 未初始化，请先调用 videoCacheManager.init(context, config)"
        }
    }
}
