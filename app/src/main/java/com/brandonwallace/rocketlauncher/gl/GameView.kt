package com.brandonwallace.rocketlauncher.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer
import com.brandonwallace.rocketlauncher.audio.SoundEngine
import com.brandonwallace.rocketlauncher.game.GameEngine
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * GLSurfaceView driven by Choreographer: each vsync posts one render request carrying the vsync timestamp,
 * so simulation time advances in exact display-frame increments (smooth pacing, no double-step judder).
 * The surface is never resized with setFixedSize (v1 did that, which caused a rescale flicker); the renderer
 * scales internally and upsamples in its own composite pass instead.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, engine: GameEngine, sound: SoundEngine) : GLSurfaceView(context), Choreographer.FrameCallback {
    private val renderer = GameRenderer(engine, sound)
    private var running = false

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(Es3ConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        renderer.vsyncNanos = frameTimeNanos
        requestRender()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onResume() {
        super.onResume()
        if (!running) { running = true; Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }
}

/** RGB888 ES3 config without depth/MSAA on the window surface (all 3D work happens in our own FBOs). */
class Es3ConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val es3 = 0x40
        val prefs = listOf(
            intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 0, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
            intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
            intArrayOf(EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
        )
        for (attribs in prefs) {
            val num = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0 && configs[0] != null) return configs[0]!!
        }
        throw IllegalStateException("No OpenGL ES 3 config available")
    }
}
