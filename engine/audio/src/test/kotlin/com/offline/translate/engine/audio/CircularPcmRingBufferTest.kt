package com.offline.translate.engine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CircularPcmRingBufferTest {

    @Test
    fun `ring buffer retains latest pre-roll samples on wraparound`() {
        val ring = CircularPcmRingBuffer(capacitySamples = 5)
        ring.write(shortArrayOf(10, 20, 30))
        assertEquals(3, ring.size())
        assertArrayEquals(shortArrayOf(10, 20, 30), ring.snapshot())

        // Write 4 more samples -> total 7 written, latest 5 should remain in order
        ring.write(shortArrayOf(40, 50, 60, 70))
        assertEquals(5, ring.size())
        assertArrayEquals(shortArrayOf(30, 40, 50, 60, 70), ring.snapshot())

        // Drain returns snapshot and clears buffer
        val drained = ring.drain()
        assertArrayEquals(shortArrayOf(30, 40, 50, 60, 70), drained)
        assertEquals(0, ring.size())
    }
}
