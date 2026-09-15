package com.creatorcam.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampSynchronizerTest {

    @Test
    fun `simultaneous start needs no shift`() {
        val plan = TimestampSynchronizer.plan(10_000L, 10_000L)
        assertEquals(0L, plan.rearOffsetUs)
        assertEquals(0L, plan.frontOffsetUs)
        assertTrue(plan.inSync)
    }

    @Test
    fun `late front stream is shifted earlier`() {
        // Front started 60ms after rear: shift its samples -60ms.
        val plan = TimestampSynchronizer.plan(10_000L, 10_060L)
        assertEquals(0L, plan.rearOffsetUs)
        assertEquals(-60_000L, plan.frontOffsetUs)
        assertFalse(plan.inSync)
    }

    @Test
    fun `small skew is considered in sync`() {
        val plan = TimestampSynchronizer.plan(10_000L, 10_020L)
        assertEquals(-20_000L, plan.frontOffsetUs)
        assertTrue(plan.inSync)
    }

    @Test
    fun `early front stream is shifted later`() {
        val plan = TimestampSynchronizer.plan(10_000L, 9_990L)
        assertEquals(10_000L, plan.frontOffsetUs)
        assertTrue(plan.inSync)
    }

    @Test
    fun `aligned durations pass validation`() {
        assertTrue(TimestampSynchronizer.durationsAligned(10_000_000L, 10_500_000L))
    }

    @Test
    fun `drifted durations fail validation`() {
        assertFalse(TimestampSynchronizer.durationsAligned(10_000_000L, 12_000_000L))
    }

    @Test
    fun `missing durations do not fail validation`() {
        assertTrue(TimestampSynchronizer.durationsAligned(0L, 10_000_000L))
    }
}
