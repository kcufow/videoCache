package com.darwin.videocache.core

import com.darwin.videocache.api.ResourceType
import java.util.concurrent.ConcurrentHashMap

internal data class RegisteredResource(
    val cacheKey: String,
    val originUrl: String,
    val type: ResourceType,
    val headers: Map<String, String>,
)

/**
 * 原始 URL / cacheKey → 资源描述；Proxy 路径只暴露 cacheKey。
 */
internal class ResourceRegistry {
    private val byKey = ConcurrentHashMap<String, RegisteredResource>()
    private val segmentParent = ConcurrentHashMap<String, String>()

    fun register(
        cacheKey: String,
        originUrl: String,
        type: ResourceType,
        headers: Map<String, String>,
    ): RegisteredResource {
        val resource = RegisteredResource(cacheKey, originUrl, type, headers)
        byKey[cacheKey] = resource
        return resource
    }

    fun registerSegment(parentKey: String, segmentKey: String, segmentUrl: String, headers: Map<String, String>) {
        byKey[segmentKey] = RegisteredResource(
            cacheKey = segmentKey,
            originUrl = segmentUrl,
            type = ResourceType.PROGRESSIVE,
            headers = headers,
        )
        segmentParent[segmentKey] = parentKey
    }

    fun get(cacheKey: String): RegisteredResource? = byKey[cacheKey]

    fun remove(cacheKey: String) {
        byKey.remove(cacheKey)
        segmentParent.entries.removeIf { it.key == cacheKey || it.value == cacheKey }
    }

    fun clear() {
        byKey.clear()
        segmentParent.clear()
    }
}
