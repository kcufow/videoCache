package com.darwin.videocache.cache

import com.darwin.videocache.api.CacheSnapshot
import com.darwin.videocache.api.EvictionPolicy
import com.darwin.videocache.api.ResourceType
import com.darwin.videocache.api.VideoCacheListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal class CacheStore(
    private val rootDir: File,
    private var maxCacheBytes: Long,
    private var evictionPolicy: EvictionPolicy,
) {
    private val entries = ConcurrentHashMap<String, CacheEntry>()
    private val protectedKeys = CopyOnWriteArraySet<String>()
    private val listeners = CopyOnWriteArraySet<VideoCacheListener>()

    init {
        rootDir.mkdirs()
        loadExisting()
    }

    fun addListener(listener: VideoCacheListener) {
        listeners += listener
    }

    fun removeListener(listener: VideoCacheListener) {
        listeners -= listener
    }

    fun setMaxCacheBytes(value: Long) {
        maxCacheBytes = value
        trimToSize(value)
    }

    fun setEvictionPolicy(policy: EvictionPolicy) {
        evictionPolicy = policy
    }

    fun getUsedBytes(): Long = entries.values.sumOf { it.cachedBytes() }

    fun getCacheDir(): File = rootDir

    fun getEntry(key: String): CacheEntry? = entries[key]

    fun getOrCreate(
        key: String,
        originUrl: String,
        type: ResourceType,
    ): CacheEntry {
        entries[key]?.let { existing ->
            existing.touch()
            if (existing.originUrl != originUrl) {
                existing.originUrl = originUrl
                existing.persistMeta()
            }
            return existing
        }
        val dir = File(rootDir, key).also { it.mkdirs() }
        val entry = CacheEntry(dir, key, originUrl, type)
        entries[key] = entry
        entry.persistMeta()
        return entry
    }

    fun snapshot(): List<CacheSnapshot> = entries.values.map { it.toSnapshot() }

    fun isFullyCached(key: String): Boolean = entries[key]?.isFullyCached() == true

    fun getCachedBytes(key: String): Long = entries[key]?.cachedBytes() ?: 0L

    fun protect(key: String) {
        protectedKeys += key
    }

    fun unprotect(key: String) {
        protectedKeys -= key
    }

    fun protectedKeys(): Set<String> = protectedKeys.toSet()

    fun remove(key: String): Boolean {
        val entry = entries.remove(key) ?: return false
        val freed = entry.cachedBytes()
        entry.delete()
        listeners.forEach { it.onCacheEvicted(key, freed) }
        return true
    }

    fun clearAll() {
        entries.keys.toList().forEach { remove(it) }
    }

    fun ensureSpace(additionalBytes: Long) {
        if (additionalBytes <= 0) return
        val need = getUsedBytes() + additionalBytes - maxCacheBytes
        if (need <= 0) return
        trimBy(need)
    }

    fun trimToSize(maxBytes: Long = maxCacheBytes) {
        val overflow = getUsedBytes() - maxBytes
        if (overflow > 0) trimBy(overflow)
    }

    private fun trimBy(bytesToFree: Long) {
        val victims = evictionPolicy.selectVictims(snapshot(), bytesToFree, protectedKeys())
        victims.forEach { remove(it) }
    }

    private fun loadExisting() {
        val dirs = rootDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val metaFile = File(dir, META_FILE)
            if (!metaFile.exists()) continue
            runCatching {
                val entry = CacheEntry.load(dir) ?: return@runCatching
                entries[entry.key] = entry
            }
        }
    }

    companion object {
        const val META_FILE = "meta.json"
        const val DATA_FILE = "data.bin"
    }
}

internal class CacheEntry(
    val dir: File,
    val key: String,
    @Volatile var originUrl: String,
    @Volatile var type: ResourceType,
) {
    private val dataFile = File(dir, CacheStore.DATA_FILE)
    private val metaFile = File(dir, CacheStore.META_FILE)
    private val ranges = ByteRanges()
    private val lock = Any()

    @Volatile
    var contentLength: Long = -1L
        private set

    @Volatile
    var contentType: String? = null
        private set

    @Volatile
    var lastAccessTimeMs: Long = System.currentTimeMillis()
        private set

    init {
        if (!dataFile.exists()) {
            dataFile.createNewFile()
        }
    }

    fun touch() {
        lastAccessTimeMs = System.currentTimeMillis()
        persistMeta()
    }

    fun updateMeta(contentLength: Long? = null, contentType: String? = null) {
        synchronized(lock) {
            if (contentLength != null && contentLength >= 0) {
                this.contentLength = contentLength
            }
            if (!contentType.isNullOrBlank()) {
                this.contentType = contentType
            }
            persistMeta()
        }
    }

    fun cachedBytes(): Long = synchronized(lock) { ranges.cachedBytes() }

    fun isFullyCached(): Boolean = synchronized(lock) {
        contentLength > 0 && ranges.contains(0L, contentLength)
    }

    fun hasRange(start: Long, endExclusive: Long): Boolean = synchronized(lock) {
        ranges.contains(start, endExclusive)
    }

    fun firstMissing(start: Long, endExclusive: Long): LongRange? = synchronized(lock) {
        ranges.firstMissing(start, endExclusive)
    }

    fun read(start: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            touchQuiet()
            RandomAccessFile(dataFile, "r").use { raf ->
                if (start >= raf.length()) return -1
                raf.seek(start)
                return raf.read(buffer, offset, length)
            }
        }
    }

    /** 若整文件已缓存则读出；否则返回 null。 */
    fun readFullyOrNull(): ByteArray? {
        synchronized(lock) {
            if (contentLength <= 0 || !ranges.contains(0L, contentLength)) return null
            val size = contentLength.toInt()
            val out = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = readUnlocked(offset.toLong(), out, offset, size - offset)
                if (read <= 0) return null
                offset += read
            }
            touchQuiet()
            return out
        }
    }

    fun writeFully(bytes: ByteArray, contentType: String? = null) {
        synchronized(lock) {
            RandomAccessFile(dataFile, "rw").use { raf ->
                raf.setLength(0)
                raf.write(bytes)
            }
            ranges.clear()
            ranges.add(0L, bytes.size.toLong())
            this.contentLength = bytes.size.toLong()
            if (!contentType.isNullOrBlank()) {
                this.contentType = contentType
            }
            touchQuiet()
            persistMeta()
        }
    }

    fun write(start: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            RandomAccessFile(dataFile, "rw").use { raf ->
                raf.seek(start)
                raf.write(buffer, offset, length)
            }
            ranges.add(start, start + length)
            touchQuiet()
            persistMeta()
        }
    }

    private fun readUnlocked(start: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        RandomAccessFile(dataFile, "r").use { raf ->
            if (start >= raf.length()) return -1
            raf.seek(start)
            return raf.read(buffer, offset, length)
        }
    }

    fun toSnapshot(): CacheSnapshot = synchronized(lock) {
        CacheSnapshot(
            key = key,
            originUrl = originUrl,
            type = type,
            contentLength = contentLength,
            cachedBytes = ranges.cachedBytes(),
            lastAccessTimeMs = lastAccessTimeMs,
            fullyCached = isFullyCachedUnlocked(),
        )
    }

    fun persistMeta() {
        val json = JSONObject()
            .put("key", key)
            .put("originUrl", originUrl)
            .put("type", type.name)
            .put("contentLength", contentLength)
            .put("contentType", contentType)
            .put("lastAccessTimeMs", lastAccessTimeMs)
        val array = JSONArray()
        ranges.snapshot().forEach { range ->
            array.put(
                JSONObject()
                    .put("start", range.first)
                    .put("endExclusive", range.last + 1),
            )
        }
        json.put("ranges", array)
        metaFile.writeText(json.toString())
    }

    fun delete() {
        synchronized(lock) {
            dir.deleteRecursively()
        }
    }

    private fun touchQuiet() {
        lastAccessTimeMs = System.currentTimeMillis()
    }

    private fun isFullyCachedUnlocked(): Boolean =
        contentLength > 0 && ranges.contains(0L, contentLength)

    companion object {
        fun load(dir: File): CacheEntry? {
            val metaFile = File(dir, CacheStore.META_FILE)
            if (!metaFile.exists()) return null
            val json = JSONObject(metaFile.readText())
            val key = json.getString("key")
            val originUrl = json.getString("originUrl")
            val type = ResourceType.valueOf(json.optString("type", ResourceType.PROGRESSIVE.name))
            val entry = CacheEntry(dir, key, originUrl, type)
            entry.contentLength = json.optLong("contentLength", -1L)
            entry.contentType = if (json.isNull("contentType")) {
                null
            } else {
                json.optString("contentType")
            }
            entry.lastAccessTimeMs = json.optLong("lastAccessTimeMs", System.currentTimeMillis())
            val ranges = json.optJSONArray("ranges") ?: JSONArray()
            for (i in 0 until ranges.length()) {
                val item = ranges.getJSONObject(i)
                entry.ranges.add(item.getLong("start"), item.getLong("endExclusive"))
            }
            return entry
        }
    }
}
