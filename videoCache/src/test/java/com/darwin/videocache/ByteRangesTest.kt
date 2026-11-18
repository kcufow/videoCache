package com.darwin.videocache

import com.darwin.videocache.cache.ByteRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteRangesTest {
    @Test
    fun mergeAdjacentRanges() {
        val ranges = ByteRanges()
        ranges.add(0, 100)
        ranges.add(100, 200)
        assertTrue(ranges.contains(0, 200))
        assertEquals(200, ranges.cachedBytes())
    }

    @Test
    fun firstMissingInsideGap() {
        val ranges = ByteRanges()
        ranges.add(0, 50)
        ranges.add(100, 150)
        val missing = ranges.firstMissing(0, 150)
        assertEquals(50L, missing!!.first)
        assertEquals(99L, missing.last)
        assertFalse(ranges.contains(0, 150))
        assertNull(ranges.firstMissing(0, 50))
    }
}
