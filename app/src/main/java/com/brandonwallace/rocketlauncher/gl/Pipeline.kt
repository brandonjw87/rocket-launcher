package com.brandonwallace.rocketlauncher.gl

import android.opengl.GLES30
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** Graphics quality presets (Settings → Quality). Medium is the default and targets 60 fps on a Pixel. */
data class Quality(
    val name: String, val scale: Float, val msaa: Int, val shadowSize: Int, val shadowTaps: Int,
    val bloomLevels: Int, val shimmer: Boolean, val particles: Int, val lutSteps: Int, val segs: Int, val cloudPuffs: Int,
) {
    companion object {
        val LOW = Quality("Low", 0.7f, 0, 1024, 1, 0, false, 1500, 10, 24, 3)
        val MEDIUM = Quality("Medium", 0.85f, 2, 1536, 9, 4, true, 2600, 16, 40, 5)
        val HIGH = Quality("High", 1.0f, 4, 2048, 9, 5, true, 3600, 24, 56, 7)
        fun of(i: Int) = when (i) { 0 -> LOW; 2 -> HIGH; else -> MEDIUM }
    }
}

/**
 * Render targets: HDR scene (optionally MSAA, resolved with a blit), bloom mip chain, shadow map, sky LUT.
 * Everything is (re)built from onSurfaceChanged or when the quality changes — never mid-frame — and all
 * sizes come from the real surface size, so rotation/resume can't leave a stretched or stale buffer.
 */
class Pipeline {
    var floatTargets = false; private set
    var outScale = 1f; private set
    var width = 0; private set
    var height = 0; private set
    var sceneW = 0; private set
    var sceneH = 0; private set
    var samples = 0; private set
    var quality = Quality.MEDIUM; private set

    // scene
    private var msFbo = 0; private var msColor = 0; private var msDepth = 0
    var sceneFbo = 0; private set          // single-sample FBO (render target when no MSAA, resolve target otherwise)
    var sceneTex = 0; private set
    private var sceneDepth = 0
    // bloom
    val bloomFbo = IntArray(6); val bloomTex = IntArray(6); val bloomW = IntArray(6); val bloomH = IntArray(6)
    var bloomLevels = 0; private set
    // shadow
    var shadowFbo = 0; private set
    var shadowTex = 0; private set
    var shadowSize = 0; private set
    // sky LUT
    var lutFbo = 0; private set
    var lutTex = 0; private set
    val lutW = 128; val lutH = 64

    private var quadVao = 0; private var quadVbo = 0
    private var created = false

    fun init() {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        floatTargets = ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")
        outScale = if (floatTargets) 1f else 0.25f
        val a = IntArray(1); val b = IntArray(1)
        GLES30.glGenVertexArrays(1, a, 0); GLES30.glGenBuffers(1, b, 0)
        quadVao = a[0]; quadVbo = b[0]
        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        val fb = floatBuffer(8).put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); fb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
        createLut()
        created = false
    }

    fun drawQuad() { GLES30.glBindVertexArray(quadVao); GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4) }

    private fun colorFormat(): Triple<Int, Int, Int> =
        if (floatTargets) Triple(GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT)
        else Triple(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)

    private fun tex2d(w: Int, h: Int, fmt: Triple<Int, Int, Int>, filter: Int = GLES30.GL_LINEAR, wrapS: Int = GLES30.GL_CLAMP_TO_EDGE): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, fmt.first, w, h, 0, fmt.second, fmt.third, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrapS)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun fboFor(tex: Int): Int {
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        return f[0]
    }

    private fun complete(name: String): Boolean {
        val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) { Log.w("RocketGL", "$name incomplete: 0x${Integer.toHexString(st)}"); return false }
        return true
    }

    private fun createLut() {
        // U wraps (azimuth is periodic) so there is no seam behind the camera
        lutTex = tex2d(lutW, lutH, colorFormat(), GLES30.GL_LINEAR, GLES30.GL_REPEAT)
        lutFbo = fboFor(lutTex)
        if (!complete("lut") && floatTargets) {
            // extremely defensive: fall back to RGBA8 everywhere
            floatTargets = false; outScale = 0.25f
            GLES30.glDeleteFramebuffers(1, intArrayOf(lutFbo), 0); GLES30.glDeleteTextures(1, intArrayOf(lutTex), 0)
            lutTex = tex2d(lutW, lutH, colorFormat(), GLES30.GL_LINEAR, GLES30.GL_REPEAT)
            lutFbo = fboFor(lutTex)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Build/rebuild size-dependent targets. Safe to call repeatedly; no-op if nothing changed. */
    fun configure(w: Int, h: Int, q: Quality) {
        if (w <= 0 || h <= 0) return
        if (created && w == width && h == height && q == quality) return
        releaseSized()
        width = w; height = h; quality = q
        sceneW = max(1, (w * q.scale).toInt()); sceneH = max(1, (h * q.scale).toInt())
        val fmt = colorFormat()

        // single-sample scene target (+ depth so it can be rendered into directly)
        sceneTex = tex2d(sceneW, sceneH, fmt)
        sceneFbo = fboFor(sceneTex)
        sceneDepth = renderbuffer(GLES30.GL_DEPTH_COMPONENT24, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, sceneDepth)
        complete("scene")

        // MSAA target, clamped to what the driver supports for this exact format
        samples = 0
        if (q.msaa > 0) {
            val n = IntArray(1)
            GLES30.glGetInternalformativ(GLES30.GL_RENDERBUFFER, fmt.first, GLES30.GL_SAMPLES, 1, n, 0)
            val maxS = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, maxS, 0)
            val s = min(q.msaa, min(if (n[0] > 0) n[0] else 0, maxS[0]))
            if (s >= 2) {
                val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); msFbo = f[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFbo)
                msColor = renderbuffer(fmt.first, s)
                GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msColor)
                msDepth = renderbuffer(GLES30.GL_DEPTH_COMPONENT24, s)
                GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, msDepth)
                if (complete("msaa")) samples = s else releaseMsaa()
            }
        }

        // bloom chain (half, quarter, ...)
        bloomLevels = q.bloomLevels
        var bw = sceneW; var bh = sceneH
        for (i in 0 until bloomLevels) {
            bw = max(1, bw / 2); bh = max(1, bh / 2)
            bloomW[i] = bw; bloomH[i] = bh
            bloomTex[i] = tex2d(bw, bh, fmt)
            bloomFbo[i] = fboFor(bloomTex[i])
        }

        // shadow map: 24-bit depth texture with hardware comparison (bilinear PCF for free)
        shadowSize = q.shadowSize
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0); shadowTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, shadowSize, shadowSize, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); shadowFbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        if (!complete("shadow")) shadowSize = 0

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        created = true
    }

    private fun renderbuffer(fmt: Int, s: Int): Int {
        val r = IntArray(1)
        GLES30.glGenRenderbuffers(1, r, 0)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, r[0])
        if (s > 0) GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, s, fmt, sceneW, sceneH)
        else GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, fmt, sceneW, sceneH)
        return r[0]
    }

    /** Bind the target the 3D scene is drawn into. */
    fun bindScene() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (samples > 0) msFbo else sceneFbo)
        GLES30.glViewport(0, 0, sceneW, sceneH)
    }

    /** MSAA resolve (blit) into the single-sample scene texture; depth is discarded (saves bandwidth on tilers). */
    fun resolve() {
        if (samples > 0) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, sceneFbo)
            GLES30.glBlitFramebuffer(0, 0, sceneW, sceneH, 0, 0, sceneW, sceneH, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msFbo)
            GLES30.glInvalidateFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_DEPTH_ATTACHMENT), 0)
        } else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
            GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_DEPTH_ATTACHMENT), 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun releaseMsaa() {
        if (msFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(msFbo), 0)
        if (msColor != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(msColor), 0)
        if (msDepth != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(msDepth), 0)
        msFbo = 0; msColor = 0; msDepth = 0; samples = 0
    }

    private fun releaseSized() {
        if (!created) return
        releaseMsaa()
        GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(sceneTex), 0)
        GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
        for (i in 0 until bloomLevels) { GLES30.glDeleteFramebuffers(1, intArrayOf(bloomFbo[i]), 0); GLES30.glDeleteTextures(1, intArrayOf(bloomTex[i]), 0) }
        GLES30.glDeleteFramebuffers(1, intArrayOf(shadowFbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(shadowTex), 0)
        created = false
    }
}
