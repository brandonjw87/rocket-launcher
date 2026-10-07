package com.brandonwallace.rocketlauncher.gl

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Column-major 4x4 matrix helpers (same layout as OpenGL), pure Kotlin so the whole renderer's math
 * also runs in JVM unit tests. All functions write into caller-provided arrays (no per-frame garbage).
 */
object Mat {
    fun identity(m: FloatArray): FloatArray { m.fill(0f); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f; return m }
    fun new(): FloatArray = identity(FloatArray(16))

    /** out = a * b (out may alias a or b). */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
        val t = tmpMul
        for (c in 0 until 4) for (r in 0 until 4) {
            t[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
        }
        System.arraycopy(t, 0, out, 0, 16)
        return out
    }
    private val tmpMul = FloatArray(16)

    /**
     * out = pre * T(tx,ty,tz) * Ry(ry) * Rz(rz) * Rx(rx) * S(sx,sy,sz); angles in degrees.
     */
    fun trs(
        out: FloatArray, tx: Float, ty: Float, tz: Float, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f,
        ry: Float = 0f, rz: Float = 0f, rx: Float = 0f, pre: FloatArray? = null,
    ): FloatArray {
        val a = Math.toRadians(ry.toDouble()); val b = Math.toRadians(rz.toDouble()); val c = Math.toRadians(rx.toDouble())
        val cy = cos(a).toFloat(); val sy_ = sin(a).toFloat()
        val cz = cos(b).toFloat(); val sz_ = sin(b).toFloat()
        val cx = cos(c).toFloat(); val sx_ = sin(c).toFloat()
        // R = Ry * Rz * Rx
        val r00 = cy * cz; val r01 = -cy * sz_ * cx + sy_ * sx_; val r02 = cy * sz_ * sx_ + sy_ * cx
        val r10 = sz_; val r11 = cz * cx; val r12 = -cz * sx_
        val r20 = -sy_ * cz; val r21 = sy_ * sz_ * cx + cy * sx_; val r22 = -sy_ * sz_ * sx_ + cy * cx
        val m = if (pre == null) out else tmpTrs
        m[0] = r00 * sx; m[1] = r10 * sx; m[2] = r20 * sx; m[3] = 0f
        m[4] = r01 * sy; m[5] = r11 * sy; m[6] = r21 * sy; m[7] = 0f
        m[8] = r02 * sz; m[9] = r12 * sz; m[10] = r22 * sz; m[11] = 0f
        m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
        if (pre != null) mul(out, pre, m)
        return out
    }
    private val tmpTrs = FloatArray(16)

    fun perspective(m: FloatArray, fovyDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovyDeg / 2.0)).toFloat()
        m.fill(0f)
        m[0] = f / aspect; m[5] = f
        m[10] = (far + near) / (near - far); m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    fun ortho(m: FloatArray, l: Float, r: Float, b: Float, t: Float, n: Float, f: Float): FloatArray {
        m.fill(0f)
        m[0] = 2f / (r - l); m[5] = 2f / (t - b); m[10] = -2f / (f - n)
        m[12] = -(r + l) / (r - l); m[13] = -(t + b) / (t - b); m[14] = -(f + n) / (f - n); m[15] = 1f
        return m
    }

    fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float): FloatArray {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        var l = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-9f); fx /= l; fy /= l; fz /= l
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        l = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-9f); sx /= l; sy /= l; sz /= l
        val ux2 = sy * fz - sz * fy; val uy2 = sz * fx - sx * fz; val uz2 = sx * fy - sy * fx
        m[0] = sx; m[1] = ux2; m[2] = -fx; m[3] = 0f
        m[4] = sy; m[5] = uy2; m[6] = -fy; m[7] = 0f
        m[8] = sz; m[9] = uz2; m[10] = -fz; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez); m[13] = -(ux2 * ex + uy2 * ey + uz2 * ez); m[14] = fx * ex + fy * ey + fz * ez; m[15] = 1f
        return m
    }

    /** General inverse. Returns false (and leaves out = identity) if singular. */
    fun invert(out: FloatArray, m: FloatArray): Boolean {
        val inv = tmpInv
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (det == 0f || det.isNaN()) { identity(out); return false }
        val id = 1f / det
        for (i in 0 until 16) out[i] = inv[i] * id
        return true
    }
    private val tmpInv = FloatArray(16)

    /** 3x3 inverse-transpose of the upper-left of [m] (column-major 9 floats) for transforming normals. */
    fun normalMatrix(out9: FloatArray, m: FloatArray): FloatArray {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        val id = if (det == 0f) 0f else 1f / det
        // inverse-transpose = cofactor / det
        out9[0] = A * id; out9[1] = B * id; out9[2] = C * id
        out9[3] = -(b * i - c * h) * id; out9[4] = (a * i - c * g) * id; out9[5] = -(a * h - b * g) * id
        out9[6] = (b * f - c * e) * id; out9[7] = -(a * f - c * d) * id; out9[8] = (a * e - b * d) * id
        return out9
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    fun transformDir(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z
        out[1] = m[1] * x + m[5] * y + m[9] * z
        out[2] = m[2] * x + m[6] * y + m[10] * z
    }

    /** Projects a camera-relative point; returns false if behind the camera. out = (ndcX, ndcY, w). */
    fun project(vp: FloatArray, x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw <= 1e-4f) return false
        out[0] = cx / cw; out[1] = cy / cw; out[2] = cw
        return true
    }
}
