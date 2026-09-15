package com.creatorcam.app.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.creatorcam.app.util.TimeFormat

/** A bitmap composited over the final frame (normalized origin-top-left rect). */
data class OverlayLayer(
    val bitmap: Bitmap,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val alpha: Float = 0.92f,
)

/**
 * Renders watermark / date-time pills with Canvas. Bitmaps are uploaded to GL
 * once per compose run (date/time uses the take's start timestamp — a static
 * burn-in, never a mid-encode clock that could drift).
 */
object OverlayFactory {

    fun build(
        context: Context,
        outW: Int,
        outH: Int,
        watermarkText: String?,
        dateTimeMs: Long?,
    ): List<OverlayLayer> {
        val layers = mutableListOf<OverlayLayer>()
        val pillH = (outH * 0.042f).coerceAtLeast(28f)
        val marginX = outW * 0.035f
        val marginTop = outH * 0.028f
        if (!watermarkText.isNullOrBlank()) {
            val bmp = renderPill(watermarkText.take(32), pillH)
            val w = bmp.width.toFloat() / outW
            val h = bmp.height.toFloat() / outH
            layers += OverlayLayer(bmp, marginX / outW, marginTop / outH, w, h)
        }
        if (dateTimeMs != null) {
            val text = "${TimeFormat.overlayDate(dateTimeMs)}  ${TimeFormat.overlayTime(dateTimeMs)}"
            val bmp = renderPill(text, pillH)
            val w = bmp.width.toFloat() / outW
            val h = bmp.height.toFloat() / outH
            // Date sits top-end; if a watermark exists it takes the row below.
            val y = if (layers.isEmpty()) marginTop / outH
            else (marginTop + pillH * 1.35f) / outH
            layers += OverlayLayer(bmp, 1f - marginX / outW - w, y, w, h)
        }
        return layers
    }

    /** Single-line text burn-in for the editor (title / caption card). */
    fun textCard(
        text: String,
        outW: Int,
        outH: Int,
    ): OverlayLayer {
        val pillH = (outH * 0.055f).coerceAtLeast(36f)
        val bmp = renderPill(text.take(64), pillH)
        val w = bmp.width.toFloat() / outW
        val h = bmp.height.toFloat() / outH
        return OverlayLayer(bmp, (1f - w) / 2f, 1f - h - 0.06f, w, h)
    }

    private fun renderPill(text: String, heightPx: Float): Bitmap {
        val textSize = heightPx * 0.52f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textW = paint.measureText(text)
        val padX = heightPx * 0.55f
        val w = (textW + padX * 2).toInt().coerceAtLeast(2)
        val h = heightPx.toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, bg)
        val fm = paint.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, padX, baseline, paint)
        return bmp
    }
}
