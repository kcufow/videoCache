package com.darwin.videocache.cache

/**
 * 已缓存字节区间集合（半开区间 [start, end)）。
 */
internal class ByteRanges {
    private val ranges = ArrayList<LongRange>()

    @Synchronized
    fun clear() {
        ranges.clear()
    }

    @Synchronized
    fun snapshot(): List<LongRange> = ranges.map { it }

    @Synchronized
    fun cachedBytes(): Long = ranges.sumOf { it.last - it.first + 1 }

    @Synchronized
    fun contains(start: Long, endExclusive: Long): Boolean {
        if (endExclusive <= start) return true
        var cursor = start
        for (range in ranges) {
            val from = range.first
            val toExclusive = range.last + 1
            if (toExclusive <= cursor) continue
            if (from > cursor) return false
            cursor = maxOf(cursor, toExclusive)
            if (cursor >= endExclusive) return true
        }
        return cursor >= endExclusive
    }

    @Synchronized
    fun add(start: Long, endExclusive: Long) {
        if (endExclusive <= start) return
        val incoming = LongRange(start, endExclusive - 1)
        ranges += incoming
        normalize()
    }

    @Synchronized
    fun firstMissing(start: Long, endExclusive: Long): LongRange? {
        if (endExclusive <= start) return null
        var cursor = start
        for (range in ranges) {
            val from = range.first
            val toExclusive = range.last + 1
            if (toExclusive <= cursor) continue
            if (from > cursor) {
                return LongRange(cursor, minOf(from, endExclusive) - 1)
            }
            cursor = maxOf(cursor, toExclusive)
            if (cursor >= endExclusive) return null
        }
        return if (cursor < endExclusive) LongRange(cursor, endExclusive - 1) else null
    }

    private fun normalize() {
        if (ranges.isEmpty()) return
        ranges.sortBy { it.first }
        val merged = ArrayList<LongRange>()
        var current = ranges[0]
        for (i in 1 until ranges.size) {
            val next = ranges[i]
            if (next.first <= current.last + 1) {
                current = LongRange(current.first, maxOf(current.last, next.last))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        ranges.clear()
        ranges.addAll(merged)
    }

    companion object {
        fun fromPairs(pairs: List<Pair<Long, Long>>): ByteRanges {
            val result = ByteRanges()
            pairs.forEach { (start, endExclusive) -> result.add(start, endExclusive) }
            return result
        }
    }
}
