package com.creatorcam.app.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSpecTest {

    private fun assertRegion(
        region: Region,
        x: Float, y: Float, w: Float, h: Float,
    ) {
        assertEquals(x, region.x, 0.001f)
        assertEquals(y, region.y, 0.001f)
        assertEquals(w, region.w, 0.001f)
        assertEquals(h, region.h, 0.001f)
    }

    @Test
    fun `split puts creator on top`() {
        val rects = LayoutSpec.rects(DualLayout.SPLIT_50_50, 1080, 1920)
        assertRegion(rects.rear, 0f, 0.5f, 1f, 0.5f)
        assertNotNull(rects.front)
        assertRegion(rects.front!!, 0f, 0f, 1f, 0.5f)
    }

    @Test
    fun `swapped split puts environment on top`() {
        val rects = LayoutSpec.rects(DualLayout.SPLIT_SWAPPED, 1080, 1920)
        assertRegion(rects.rear, 0f, 0f, 1f, 0.5f)
        assertRegion(rects.front!!, 0f, 0.5f, 1f, 0.5f)
    }

    @Test
    fun `side by side puts front left`() {
        val rects = LayoutSpec.rects(DualLayout.SIDE_BY_SIDE, 1920, 1080)
        assertRegion(rects.rear, 0.5f, 0f, 0.5f, 1f)
        assertRegion(rects.front!!, 0f, 0f, 0.5f, 1f)
    }

    @Test
    fun `pip window keeps output aspect and sits bottom-end`() {
        val rects = LayoutSpec.rects(DualLayout.REAR_MAIN_FRONT_PIP, 1080, 1920)
        assertRegion(rects.rear, 0f, 0f, 1f, 1f)
        val pip = rects.front!!
        // Width 0.36 of output; height preserves the 9:16 output aspect.
        assertEquals(0.36f, pip.w, 0.001f)
        assertEquals(0.36f * 16f / 9f, pip.h, 0.001f)
        assertEquals(1f - 0.035f - pip.w, pip.x, 0.001f)
        assertTrue(pip.y + pip.h <= 1.001f)
    }

    @Test
    fun `circle pip is square in pixels`() {
        val outW = 1080
        val outH = 1920
        val rects = LayoutSpec.rects(DualLayout.CIRCLE_PIP, outW, outH)
        val pip = rects.front!!
        assertEquals(RegionShape.CIRCLE, pip.shape)
        assertEquals(pip.w * outW, pip.h * outH, 0.5f)
    }

    @Test
    fun `rounded pip carries a corner radius`() {
        val rects = LayoutSpec.rects(DualLayout.ROUNDED_PIP, 1080, 1920)
        assertEquals(RegionShape.ROUNDED_RECT, rects.front!!.shape)
        assertTrue(rects.front.cornerRadius > 0f)
    }

    @Test
    fun `single has no front region`() {
        val rects = LayoutSpec.rects(DualLayout.FULLSCREEN_SINGLE, 1080, 1920)
        assertRegion(rects.rear, 0f, 0f, 1f, 1f)
        assertNull(rects.front)
    }

    @Test
    fun `every layout stays inside the frame`() {
        val sizes = listOf(1080 to 1920, 1920 to 1080, 1080 to 1080)
        for (layout in DualLayout.entries) {
            for ((w, h) in sizes) {
                val rects = LayoutSpec.rects(layout, w, h)
                (listOf(rects.rear) + listOfNotNull(rects.front)).forEach { r ->
                    assertTrue("$layout $w x $h: $r", r.x >= 0f && r.y >= 0f)
                    assertTrue("$layout $w x $h: $r", r.x + r.w <= 1.001f)
                    assertTrue("$layout $w x $h: $r", r.y + r.h <= 1.001f)
                }
            }
        }
    }
}
