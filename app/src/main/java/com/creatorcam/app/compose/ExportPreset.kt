package com.creatorcam.app.compose

/**
 * Social export presets. Each preset is pure geometry: the editor re-encodes
 * through [VideoComposer] (single input, center-crop fill) into the target
 * aspect ratio. No fake "4K" — output long edge is capped by the source.
 */
enum class ExportPreset(
    val title: String,
    val aspectW: Int,
    val aspectH: Int,
    val hint: String,
) {
    SHORTS("YouTube Shorts", 9, 16, "Vertical 9:16"),
    REELS("Instagram Reels", 9, 16, "Vertical 9:16"),
    TIKTOK("TikTok", 9, 16, "Vertical 9:16"),
    FB_REELS("Facebook Reels", 9, 16, "Vertical 9:16"),
    LANDSCAPE("YouTube Landscape", 16, 9, "Landscape 16:9"),
    SQUARE("Square", 1, 1, "Square 1:1"),
    ORIGINAL("Original", 0, 0, "Keep aspect"),
    ;

    fun outputSize(longEdge: Int, srcW: Int, srcH: Int): Pair<Int, Int> {
        val long = (longEdge / 2) * 2
        if (this == ORIGINAL || aspectW == 0) {
            // Scale source to fit long edge, keep aspect, stay even.
            val scale = long.toFloat() / maxOf(srcW, srcH).coerceAtLeast(1)
            val w = ((srcW * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val h = ((srcH * scale).toInt() / 2 * 2).coerceAtLeast(2)
            return w to h
        }
        return if (aspectW >= aspectH) {
            val w = long
            val h = (long * aspectH / aspectW / 2 * 2).coerceAtLeast(2)
            w to h
        } else {
            val h = long
            val w = (long * aspectW / aspectH / 2 * 2).coerceAtLeast(2)
            w to h
        }
    }

    companion object {
        val SOCIAL: List<ExportPreset> =
            listOf(SHORTS, REELS, TIKTOK, FB_REELS, LANDSCAPE, SQUARE, ORIGINAL)
    }
}
