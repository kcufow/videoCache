package com.darwin.videocache.api

import java.io.File

/**
 * 预加载优先级，数值越大越先执行。
 */
enum class PreloadPriority(val weight: Int) {
    LOW(0),
    NORMAL(50),
    HIGH(100),
    PLAYBACK(200),
}

enum class PreloadState {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
}

enum class ResourceType {
    PROGRESSIVE,
    HLS,
}

data class VideoCacheConfig(
    /** 自定义缓存根目录；null 则使用 context.cacheDir/video_cache */
    val cacheDir: File? = null,
    /** 总缓存上限（字节） */
    val maxCacheBytes: Long = 512L * 1024 * 1024,
    /** 同时预加载任务上限，超出排队 */
    val maxConcurrentPreloads: Int = 2,
    /** 本地 Proxy 端口，0 表示系统自动分配 */
    val proxyPort: Int = 0,
    /** 上游默认 Header */
    val defaultHeaders: Map<String, String> = emptyMap(),
    /** 默认预加载策略 */
    val preloadStrategy: PreloadStrategy = BytesPreloadStrategy(DEFAULT_PRELOAD_BYTES),
    /** 清理策略 */
    val evictionPolicy: EvictionPolicy = LruEvictionPolicy(),
    /** 自定义 cacheKey；默认对 URL 做 SHA-256 */
    val cacheKeyFactory: CacheKeyFactory = DefaultCacheKeyFactory,
    /** OkHttp 读超时（秒） */
    val readTimeoutSeconds: Long = 30,
    /** OkHttp 连接超时（秒） */
    val connectTimeoutSeconds: Long = 15,
) {
    companion object {
        const val DEFAULT_PRELOAD_BYTES: Long = 2L * 1024 * 1024
        const val DEFAULT_HLS_SEGMENTS: Int = 3
    }
}

data class PreloadRequest(
    val url: String,
    val cacheKey: String? = null,
    val strategy: PreloadStrategy? = null,
    val priority: PreloadPriority = PreloadPriority.NORMAL,
    val headers: Map<String, String> = emptyMap(),
)

data class ProxyUrlOptions(
    val cacheKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** 强制资源类型；null 则按 URL 推断 */
    val resourceType: ResourceType? = null,
)

data class CacheSnapshot(
    val key: String,
    val originUrl: String,
    val type: ResourceType,
    val contentLength: Long,
    val cachedBytes: Long,
    val lastAccessTimeMs: Long,
    val fullyCached: Boolean,
)

interface CacheKeyFactory {
    fun create(url: String): String
}

object DefaultCacheKeyFactory : CacheKeyFactory {
    override fun create(url: String): String = CacheKeys.sha256(url)
}

interface VideoCacheListener {
    fun onPreloadState(cacheKey: String, url: String, state: PreloadState, error: Throwable? = null) {}
    fun onPreloadProgress(cacheKey: String, url: String, cachedBytes: Long, totalBytes: Long) {}
    fun onCacheEvicted(cacheKey: String, freedBytes: Long) {}
}

/**
 * 预加载策略：根据资源元信息规划要拉取的区间/分片。
 */
fun interface PreloadStrategy {
    fun plan(meta: ResourceMeta): List<FetchPlan>
}

data class ResourceMeta(
    val url: String,
    val cacheKey: String,
    val type: ResourceType,
    val contentLength: Long,
    val contentType: String?,
    /** HLS 时为已解析的绝对分片 URL 列表（按播放顺序） */
    val hlsSegmentUrls: List<String> = emptyList(),
)

sealed class FetchPlan {
    data class ByteRange(
        val url: String,
        val cacheKey: String,
        val start: Long,
        val endExclusive: Long,
    ) : FetchPlan()

    data class FullResource(
        val url: String,
        val cacheKey: String,
    ) : FetchPlan()
}

/**
 * 淘汰策略：在需要腾出 [bytesToFree] 时选出受害者。
 * [protectedKeys] 为正在被 Proxy 读取的 key，默认实现应尽量跳过。
 */
fun interface EvictionPolicy {
    fun selectVictims(
        entries: List<CacheSnapshot>,
        bytesToFree: Long,
        protectedKeys: Set<String>,
    ): List<String>
}

/**
 * 按大小预加载：渐进式资源拉取前 [bytes] 字节；HLS 时回退为前 [hlsSegments] 个分片。
 */
class BytesPreloadStrategy(
    private val bytes: Long = VideoCacheConfig.DEFAULT_PRELOAD_BYTES,
    private val hlsSegments: Int = VideoCacheConfig.DEFAULT_HLS_SEGMENTS,
) : PreloadStrategy {
    override fun plan(meta: ResourceMeta): List<FetchPlan> {
        return when (meta.type) {
            ResourceType.PROGRESSIVE -> {
                val end = if (meta.contentLength > 0) {
                    minOf(bytes, meta.contentLength)
                } else {
                    bytes
                }
                listOf(FetchPlan.ByteRange(meta.url, meta.cacheKey, 0L, end))
            }
            ResourceType.HLS -> {
                meta.hlsSegmentUrls.take(hlsSegments).map { segUrl ->
                    FetchPlan.FullResource(segUrl, CacheKeys.sha256(segUrl))
                }
            }
        }
    }
}

/**
 * HLS 按分片个数预加载；渐进式则按 [fallbackBytes]。
 */
class HlsSegmentPreloadStrategy(
    private val segmentCount: Int = VideoCacheConfig.DEFAULT_HLS_SEGMENTS,
    private val fallbackBytes: Long = VideoCacheConfig.DEFAULT_PRELOAD_BYTES,
) : PreloadStrategy {
    override fun plan(meta: ResourceMeta): List<FetchPlan> {
        return when (meta.type) {
            ResourceType.HLS -> {
                meta.hlsSegmentUrls.take(segmentCount).map { segUrl ->
                    FetchPlan.FullResource(segUrl, CacheKeys.sha256(segUrl))
                }
            }
            ResourceType.PROGRESSIVE -> {
                BytesPreloadStrategy(fallbackBytes).plan(meta)
            }
        }
    }
}

/**
 * LRU 淘汰；优先跳过 [protectedKeys]。
 */
class LruEvictionPolicy : EvictionPolicy {
    override fun selectVictims(
        entries: List<CacheSnapshot>,
        bytesToFree: Long,
        protectedKeys: Set<String>,
    ): List<String> {
        if (bytesToFree <= 0) return emptyList()
        val ordered = entries
            .sortedBy { it.lastAccessTimeMs }
            .partition { it.key in protectedKeys }
            .let { (protected, others) -> others + protected }

        val victims = ArrayList<String>()
        var freed = 0L
        for (entry in ordered) {
            if (freed >= bytesToFree) break
            victims += entry.key
            freed += entry.cachedBytes
        }
        return victims
    }
}

/** 供自定义 [PreloadStrategy] / cacheKey 复用的哈希工具。 */
object CacheKeys {
    fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
