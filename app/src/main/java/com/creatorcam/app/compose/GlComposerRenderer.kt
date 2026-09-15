package com.creatorcam.app.compose

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 2.0 frame compositor. Draws each decoded camera frame (OES
 * external texture) into its layout region with center-crop fill — the same
 * framing as the FILL_CENTER live preview — then draws overlay pills.
 *
 * All calls must happen on the single EGL-owned compose thread.
 */
class GlComposerRenderer {

    private var oesProgram = 0
    private var overlayProgram = 0
    private lateinit var quadBuffer: FloatBuffer
    private lateinit var uvBuffer: FloatBuffer

    // OES uniforms / attribs
    private var oesPos = 0
    private var oesUv = 0
    private var oesSTMatrix = 0
    private var oesCropScale = 0
    private var oesCropOffset = 0
    private var oesRotation = 0
    private var oesMirror = 0
    private var oesShape = 0
    private var oesRadius = 0
    private var oesSize = 0

    // Overlay uniforms / attribs
    private var ovPos = 0
    private var ovUv = 0
    private var ovAlpha = 0

    fun init() {
        val quad = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f,
            -1f, 1f, 1f, -1f, 1f, 1f,
        )
        val uv = floatArrayOf(
            0f, 0f, 1f, 0f, 0f, 1f,
            0f, 1f, 1f, 0f, 1f, 1f,
        )
        quadBuffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quad); position(0)
            }
        uvBuffer = ByteBuffer.allocateDirect(uv.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(uv); position(0)
            }
        oesProgram = buildProgram(OES_VERTEX, OES_FRAGMENT)
        oesPos = GLES20.glGetAttribLocation(oesProgram, "aPosition")
        oesUv = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
        oesSTMatrix = GLES20.glGetUniformLocation(oesProgram, "uSTMatrix")
        oesCropScale = GLES20.glGetUniformLocation(oesProgram, "uCropScale")
        oesCropOffset = GLES20.glGetUniformLocation(oesProgram, "uCropOffset")
        oesRotation = GLES20.glGetUniformLocation(oesProgram, "uRotation")
        oesMirror = GLES20.glGetUniformLocation(oesProgram, "uMirror")
        oesShape = GLES20.glGetUniformLocation(oesProgram, "uShape")
        oesRadius = GLES20.glGetUniformLocation(oesProgram, "uRadiusPx")
        oesSize = GLES20.glGetUniformLocation(oesProgram, "uSizePx")

        overlayProgram = buildProgram(OVERLAY_VERTEX, OVERLAY_FRAGMENT)
        ovPos = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        ovUv = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        ovAlpha = GLES20.glGetUniformLocation(overlayProgram, "uAlpha")

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        return tex[0]
    }

    fun clearFrame() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * @param displayAspect displayed frame aspect (w/h after container rotation).
     * @param viewportPx intArrayOf(x, y, w, h) in GL coords (origin bottom-left).
     */
    fun drawVideoFrame(
        texId: Int,
        stMatrix: FloatArray,
        rotation: Int,
        mirror: Boolean,
        displayAspect: Float,
        viewportPx: IntArray,
        shape: RegionShape,
        cornerRadiusFraction: Float,
    ) {
        GLES20.glViewport(viewportPx[0], viewportPx[1], viewportPx[2], viewportPx[3])
        GLES20.glUseProgram(oesProgram)

        val regionAspect = viewportPx[2].toFloat() / viewportPx[3].coerceAtLeast(1).toFloat()
        val (su, sv) = if (displayAspect > regionAspect) {
            (regionAspect / displayAspect) to 1f // crop sides
        } else {
            1f to (displayAspect / regionAspect) // crop top/bottom
        }
        GLES20.glUniform2f(oesCropScale, su, sv)
        GLES20.glUniform2f(oesCropOffset, (1f - su) / 2f, (1f - sv) / 2f)
        GLES20.glUniformMatrix4fv(oesSTMatrix, 1, false, stMatrix, 0)
        GLES20.glUniform1i(oesRotation, rotation)
        GLES20.glUniform1i(oesMirror, if (mirror) 1 else 0)
        GLES20.glUniform1i(
            oesShape, when (shape) {
                RegionShape.RECT -> 0
                RegionShape.ROUNDED_RECT -> 1
                RegionShape.CIRCLE -> 2
            }
        )
        val radiusPx = cornerRadiusFraction * minOf(viewportPx[2], viewportPx[3])
        GLES20.glUniform1f(oesRadius, radiusPx)
        GLES20.glUniform2f(viewportPx[2].toFloat(), viewportPx[3].toFloat())

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        bindQuad(oesPos, oesUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    fun uploadOverlay(bitmap: Bitmap): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return tex[0]
    }

    fun drawOverlay(texId: Int, alpha: Float, viewportPx: IntArray) {
        GLES20.glViewport(viewportPx[0], viewportPx[1], viewportPx[2], viewportPx[3])
        GLES20.glUseProgram(overlayProgram)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "sTexture"), 0)
        GLES20.glUniform1f(ovAlpha, alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        bindQuad(ovPos, ovUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    fun deleteTexture(id: Int, oes: Boolean) {
        val target = if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glBindTexture(target, 0)
        GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun release() {
        if (oesProgram != 0) GLES20.glDeleteProgram(oesProgram)
        if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram)
        oesProgram = 0
        overlayProgram = 0
    }

    private fun bindQuad(posHandle: Int, uvHandle: Int) {
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        uvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val link = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
        check(link[0] == GLES20.GL_TRUE) { "Program link failed" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) {
            "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        private const val OES_VERTEX = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            uniform vec2 uCropScale;
            uniform vec2 uCropOffset;
            uniform int uRotation;
            uniform int uMirror;
            varying vec2 vTexCoord;
            varying vec2 vQuad;
            void main() {
                gl_Position = aPosition;
                vQuad = aTexCoord;
                vec2 uv = aTexCoord * uCropScale + uCropOffset;
                if (uMirror == 1) { uv.x = 1.0 - uv.x; }
                vec2 ruv = uv;
                if (uRotation == 90) { ruv = vec2(uv.y, 1.0 - uv.x); }
                else if (uRotation == 180) { ruv = vec2(1.0 - uv.x, 1.0 - uv.y); }
                else if (uRotation == 270) { ruv = vec2(1.0 - uv.y, uv.x); }
                vTexCoord = (uSTMatrix * vec4(ruv, 0.0, 1.0)).xy;
            }
        """
        private const val OES_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            varying vec2 vQuad;
            uniform samplerExternalOES sTexture;
            uniform int uShape;
            uniform float uRadiusPx;
            uniform vec2 uSizePx;
            void main() {
                vec4 c = texture2D(sTexture, vTexCoord);
                float alpha = 1.0;
                if (uShape == 2) {
                    vec2 p = vQuad * uSizePx;
                    float d = distance(p, uSizePx * 0.5) - min(uSizePx.x, uSizePx.y) * 0.5;
                    alpha = 1.0 - smoothstep(-1.0, 1.0, d);
                } else if (uShape == 1) {
                    vec2 p = vQuad * uSizePx;
                    vec2 b = uSizePx * 0.5 - vec2(uRadiusPx);
                    vec2 q = abs(p - uSizePx * 0.5) - b;
                    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadiusPx;
                    alpha = 1.0 - smoothstep(-1.0, 1.0, d);
                }
                if (alpha <= 0.001) { discard; }
                gl_FragColor = vec4(c.rgb, c.a * alpha);
            }
        """
        private const val OVERLAY_VERTEX = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """
        private const val OVERLAY_FRAGMENT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            uniform float uAlpha;
            void main() {
                vec4 c = texture2D(sTexture, vTexCoord);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """
    }
}
