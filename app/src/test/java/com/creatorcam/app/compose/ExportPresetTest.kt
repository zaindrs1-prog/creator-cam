package com.creatorcam.app.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPresetTest {

    @Test
    fun `shorts produces vertical 9-16`() {
        val (w, h) = ExportPreset.SHORTS.outputSize(1920, 1920, 1080)
        assertEquals(1080, w)
        assertEquals(1920, h)
    }

    @Test
    fun `landscape produces horizontal 16-9`() {
        val (w, h) = ExportPreset.LANDSCAPE.outputSize(1920, 1080, 1920)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `square produces 1-1`() {
        val (w, h) = ExportPreset.SQUARE.outputSize(1080, 1920, 1080)
        assertEquals(1080, w)
        assertEquals(1080, h)
    }

    @Test
    fun `original keeps source aspect`() {
        val (w, h) = ExportPreset.ORIGINAL.outputSize(1920, 1920, 1080)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `all outputs have even dimensions for AVC`() {
        for (preset in ExportPreset.SOCIAL) {
            val (w, h) = preset.outputSize(1921, 1000, 700)
            assertTrue("$preset: $w x $h", w % 2 == 0 && h % 2 == 0)
            assertTrue("$preset: $w x $h", w >= 2 && h >= 2)
        }
    }
}
