package com.brandonwallace.rocketlauncher.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer

/**
 * Procedural decal atlas drawn with Canvas at startup (no image assets). 1024x512, four 1024x128 rows:
 *  0: "ROCKET LAUNCHER" vertical lettering, 1: mission emblem + "BW MARS PROGRAM",
 *  2: "RL" + stripes, 3: hazard stripes. Mipmapped + clamped so the text never shimmers or bleeds rows.
 */
object Decals {
    fun create(): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        val bmp = try { draw() } catch (_: Throwable) { null }
        if (bmp != null) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        } else {
            // fallback: fully transparent 1x1 texture (decals simply don't show)
            val px = ByteBuffer.allocateDirect(4)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun draw(): Bitmap? {
        val w = 1024; val h = 512; val row = 128f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) ?: return null
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        // keep a 6px transparent margin around every row so mip levels never bleed between rows
        // row 0: lettering (dark with a light outline: readable on white paint, foam, steel and carbon)
        p.textSize = 84f; p.textAlign = Paint.Align.CENTER; p.letterSpacing = 0.06f
        p.style = Paint.Style.STROKE; p.strokeWidth = 9f; p.color = Color.argb(235, 245, 245, 245)
        c.drawText("ROCKET LAUNCHER", w / 2f, row * 0.5f + 33f, p)
        p.style = Paint.Style.FILL; p.color = Color.rgb(18, 26, 48)
        c.drawText("ROCKET LAUNCHER", w / 2f, row * 0.5f + 33f, p)
        // row 1: emblem + program name
        val y1 = row
        p.color = Color.rgb(16, 36, 92); c.drawCircle(80f, y1 + 64f, 54f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = Color.rgb(240, 190, 40); c.drawCircle(80f, y1 + 64f, 54f, p)
        p.strokeWidth = 3f; c.drawOval(RectF(36f, y1 + 46f, 124f, y1 + 82f), p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        val rocket = Path().apply { moveTo(80f, y1 + 22f); lineTo(92f, y1 + 70f); lineTo(80f, y1 + 98f); lineTo(68f, y1 + 70f); close() }
        c.drawPath(rocket, p)
        p.color = Color.rgb(255, 120, 40); c.drawCircle(80f, y1 + 104f, 6f, p)
        p.textSize = 64f; p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0.05f
        p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.color = Color.argb(230, 245, 245, 245)
        c.drawText("BW MARS PROGRAM", 160f, y1 + 86f, p)
        p.style = Paint.Style.FILL; p.color = Color.rgb(18, 26, 48)
        c.drawText("BW MARS PROGRAM", 160f, y1 + 86f, p)
        // row 2: V2 mark with racing stripes
        val y2 = row * 2
        p.color = Color.rgb(200, 30, 30); c.drawRect(8f, y2 + 30f, 1016f, y2 + 50f, p)
        p.color = Color.rgb(20, 40, 110); c.drawRect(8f, y2 + 78f, 1016f, y2 + 98f, p)
        p.textAlign = Paint.Align.CENTER; p.textSize = 110f; p.color = Color.rgb(18, 26, 48)
        c.drawText("RL", w / 2f, y2 + 104f, p)
        // row 3: hazard stripes
        val y3 = row * 3
        p.color = Color.rgb(250, 200, 20); c.drawRect(8f, y3 + 8f, 1016f, y3 + 120f, p)
        p.color = Color.rgb(20, 20, 20)
        var x = -100f
        while (x < w + 100) {
            val s = Path().apply { moveTo(x, y3 + 120f); lineTo(x + 50f, y3 + 120f); lineTo(x + 150f, y3 + 8f); lineTo(x + 100f, y3 + 8f); close() }
            c.drawPath(s, p); x += 100f
        }
        return bmp
    }
}
