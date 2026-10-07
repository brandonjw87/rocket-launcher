package com.brandonwallace.rocketlauncher.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun floatBuffer(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
fun intBuffer(n: Int): IntBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

class ShaderProgram(val name: String, vs: String, fs: String) {
    val id: Int
    private val locs = HashMap<String, Int>()
    var ok = true; private set

    init {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, v); GLES30.glAttachShader(id, f)
        GLES30.glLinkProgram(id)
        val st = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) { ok = false; Log.e("RocketGL", "$name link: " + GLES30.glGetProgramInfoLog(id)) }
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val st = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) { ok = false; Log.e("RocketGL", "$name ${if (type == GLES30.GL_VERTEX_SHADER) "VS" else "FS"}: " + GLES30.glGetShaderInfoLog(s)) }
        return s
    }

    fun use() = GLES30.glUseProgram(id)
    fun loc(n: String): Int = locs.getOrPut(n) { GLES30.glGetUniformLocation(id, n) }
    fun i(n: String, v: Int) = GLES30.glUniform1i(loc(n), v)
    fun f(n: String, v: Float) = GLES30.glUniform1f(loc(n), v)
    fun f2(n: String, a: Float, b: Float) = GLES30.glUniform2f(loc(n), a, b)
    fun f3(n: String, a: Float, b: Float, c: Float) = GLES30.glUniform3f(loc(n), a, b, c)
    fun f3(n: String, v: FloatArray) = GLES30.glUniform3f(loc(n), v[0], v[1], v[2])
    fun f4(n: String, a: Float, b: Float, c: Float, d: Float) = GLES30.glUniform4f(loc(n), a, b, c, d)
    fun m4(n: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(n), 1, false, m, 0)
    fun m3(n: String, m: FloatArray) = GLES30.glUniformMatrix3fv(loc(n), 1, false, m, 0)
    fun release() = GLES30.glDeleteProgram(id)
}

/** Interleaved mesh: pos(3) normal(3) color(3) material(2: roughness, metallic) = 11 floats; 32-bit indices. */
class Mesh(verts: FloatArray, idx: IntArray) {
    private val vao = IntArray(1); private val bufs = IntArray(2)
    val count = idx.size
    val vertexCount = verts.size / STRIDE
    /** Cheap fingerprint of the geometry and colours (tests use it to prove every upgrade changes the rocket). */
    val checksum: Double = run { var s = 0.0; for (i in verts.indices) s += verts[i] * (1 + i % 7); s }

    companion object {
        const val STRIDE = 11
        fun bindLayout() {
            val s = STRIDE * 4
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, s, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, s, 12)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, s, 24)
            GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, s, 36)
        }
    }

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, bufs, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[0])
        val fb = floatBuffer(verts.size).put(verts); fb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, bufs[1])
        val ib = intBuffer(idx.size).put(idx); ib.position(0)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GLES30.GL_STATIC_DRAW)
        bindLayout()
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        if (count == 0) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0)
    }

    fun release() { GLES30.glDeleteBuffers(2, bufs, 0); GLES30.glDeleteVertexArrays(1, vao, 0) }
}

/**
 * CPU mesh builder with a current transform, colour and material. Lathe surfaces get analytic smooth
 * normals (creases above ~35° are kept sharp) and a duplicated seam column so there is no normal seam.
 */
class MeshBuilder {
    private var v = FloatArray(4096)
    private var vn = 0
    private var ix = IntArray(4096)
    private var ixn = 0
    private val xf = Mat.new()
    private val nm = FloatArray(9)
    private var hasXf = false
    var r = 0.8f; var g = 0.8f; var b = 0.8f
    var rough = 0.5f; var metal = 0f

    val vertexCount get() = vn / Mesh.STRIDE
    val isEmpty get() = ixn == 0

    fun color(r: Float, g: Float, b: Float): MeshBuilder { this.r = r; this.g = g; this.b = b; return this }
    fun material(rough: Float, metal: Float): MeshBuilder { this.rough = rough; this.metal = metal; return this }
    fun transform(m: FloatArray?): MeshBuilder {
        if (m == null) { Mat.identity(xf); hasXf = false } else { System.arraycopy(m, 0, xf, 0, 16); Mat.normalMatrix(nm, xf); hasXf = true }
        return this
    }

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
        if (vn + Mesh.STRIDE > v.size) v = v.copyOf(v.size * 2)
        var px = x; var py = y; var pz = z; var qx = nx; var qy = ny; var qz = nz
        if (hasXf) {
            px = xf[0] * x + xf[4] * y + xf[8] * z + xf[12]
            py = xf[1] * x + xf[5] * y + xf[9] * z + xf[13]
            pz = xf[2] * x + xf[6] * y + xf[10] * z + xf[14]
            qx = nm[0] * nx + nm[3] * ny + nm[6] * nz
            qy = nm[1] * nx + nm[4] * ny + nm[7] * nz
            qz = nm[2] * nx + nm[5] * ny + nm[8] * nz
            val l = sqrt(qx * qx + qy * qy + qz * qz).coerceAtLeast(1e-9f); qx /= l; qy /= l; qz /= l
        }
        v[vn++] = px; v[vn++] = py; v[vn++] = pz; v[vn++] = qx; v[vn++] = qy; v[vn++] = qz
        v[vn++] = r; v[vn++] = g; v[vn++] = b; v[vn++] = rough; v[vn++] = metal
        return vn / Mesh.STRIDE - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (ixn + 3 > ix.size) ix = ix.copyOf(ix.size * 2)
        ix[ixn++] = a; ix[ixn++] = b; ix[ixn++] = c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    /**
     * Surface of revolution around +Y. [profile] = flat list r0,y0, r1,y1, ... ordered bottom to top.
     * Normals point outward (or inward when [inside]).
     */
    fun lathe(profile: FloatArray, segs: Int, inside: Boolean = false, a0: Float = 0f, a1: Float = (2 * PI).toFloat()): MeshBuilder {
        val n = profile.size / 2
        if (n < 2) return this
        // per-segment normals in (r, y)
        val snr = FloatArray(n - 1); val sny = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            val dr = profile[2 * i + 2] - profile[2 * i]; val dy = profile[2 * i + 3] - profile[2 * i + 1]
            val l = sqrt(dr * dr + dy * dy).coerceAtLeast(1e-9f)
            snr[i] = dy / l; sny[i] = -dr / l
        }
        val sign = if (inside) -1f else 1f
        for (i in 0 until n - 1) {
            val ends = intArrayOf(i, i + 1)
            val rowStart = IntArray(2)
            for ((k, p) in ends.withIndex()) {
                // normal at this profile point for segment i: smooth with the neighbour unless creased
                var nr = snr[i]; var ny = sny[i]
                val nb = if (k == 0) i - 1 else i + 1
                if (nb in 0 until n - 1) {
                    val dot = snr[i] * snr[nb] + sny[i] * sny[nb]
                    if (dot > 0.82f) { nr = snr[i] + snr[nb]; ny = sny[i] + sny[nb]; val l = sqrt(nr * nr + ny * ny); nr /= l; ny /= l }
                }
                val pr = profile[2 * p]; val py = profile[2 * p + 1]
                rowStart[k] = vertexCount
                for (s in 0..segs) {
                    val a = a0 + (a1 - a0) * s / segs
                    val ca = cos(a); val sa = sin(a)
                    vertex(pr * ca, py, pr * sa, sign * nr * ca, sign * ny, sign * nr * sa)
                }
            }
            for (s in 0 until segs) {
                val a = rowStart[0] + s; val b = rowStart[0] + s + 1; val c = rowStart[1] + s + 1; val d = rowStart[1] + s
                if (inside) quad(a, b, c, d) else quad(a, d, c, b)
            }
        }
        return this
    }

    fun disc(r: Float, y: Float, segs: Int, up: Boolean): MeshBuilder {
        val c = vertex(0f, y, 0f, 0f, if (up) 1f else -1f, 0f)
        val start = vertexCount
        for (s in 0..segs) {
            val a = (2 * PI * s / segs).toFloat()
            vertex(r * cos(a), y, r * sin(a), 0f, if (up) 1f else -1f, 0f)
        }
        for (s in 0 until segs) if (up) tri(c, start + s + 1, start + s) else tri(c, start + s, start + s + 1)
        return this
    }

    fun cylinder(r: Float, y0: Float, y1: Float, segs: Int, caps: Boolean = true): MeshBuilder {
        lathe(floatArrayOf(r, y0, r, y1), segs)
        if (caps) { disc(r, y1, segs, true); disc(r, y0, segs, false) }
        return this
    }

    fun sphere(rad: Float, segs: Int, rings: Int, bump: ((Float, Float) -> Float)? = null): MeshBuilder {
        val start = vertexCount
        for (j in 0..rings) {
            val th = (PI * j / rings).toFloat()
            for (s in 0..segs) {
                val ph = (2 * PI * s / segs).toFloat()
                val nx = sin(th) * cos(ph); val ny = -cos(th); val nz = sin(th) * sin(ph)
                val rr = rad * (bump?.invoke(th, if (s == segs) 0f else ph) ?: 1f)
                vertex(nx * rr, ny * rr, nz * rr, nx, ny, nz)
            }
        }
        for (j in 0 until rings) for (s in 0 until segs) {
            val a = start + j * (segs + 1) + s; val b = a + 1; val c = a + segs + 2; val d = a + segs + 1
            quad(a, d, c, b)
        }
        return this
    }

    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float): MeshBuilder {
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        fun face(nx: Float, ny: Float, nz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float, d: Float, hu: Float, hv: Float) {
            val ox = cx + nx * d; val oy = cy + ny * d; val oz = cz + nz * d
            val a = vertex(ox - ux * hu - vx * hv, oy - uy * hu - vy * hv, oz - uz * hu - vz * hv, nx, ny, nz)
            val b = vertex(ox + ux * hu - vx * hv, oy + uy * hu - vy * hv, oz + uz * hu - vz * hv, nx, ny, nz)
            val c = vertex(ox + ux * hu + vx * hv, oy + uy * hu + vy * hv, oz + uz * hu + vz * hv, nx, ny, nz)
            val dd = vertex(ox - ux * hu + vx * hv, oy - uy * hu + vy * hv, oz - uz * hu + vz * hv, nx, ny, nz)
            quad(a, b, c, dd)
        }
        face(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f, hx, hz, hy)
        face(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, hx, hz, hy)
        face(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f, hy, hx, hz)
        face(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, hy, hx, hz)
        face(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f, hz, hx, hy)
        face(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f, hz, hx, hy)
        return this
    }

    /** A beam between two points with a square cross-section (for lattices, struts, wires). */
    fun beam(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, w: Float): MeshBuilder {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-5f) return this
        // orientation: rotate +Y onto d
        val ux = dx / len; val uy = dy / len; val uz = dz / len
        val m = FloatArray(16)
        // build orthonormal basis (a, u, c)
        var ax = 1f; var ay = 0f; var az = 0f
        if (abs(ux) > 0.9f) { ax = 0f; az = 1f }
        // c = a x u, a = u x c
        var cx = ay * uz - az * uy; var cy = az * ux - ax * uz; var cz = ax * uy - ay * ux
        val cl = sqrt(cx * cx + cy * cy + cz * cz); cx /= cl; cy /= cl; cz /= cl
        ax = uy * cz - uz * cy; ay = uz * cx - ux * cz; az = ux * cy - uy * cx
        m[0] = ax * w; m[1] = ay * w; m[2] = az * w
        m[4] = dx; m[5] = dy; m[6] = dz
        m[8] = cx * w; m[9] = cy * w; m[10] = cz * w
        m[12] = x0; m[13] = y0; m[14] = z0; m[15] = 1f
        val saved = xf.copyOf(); val had = hasXf
        val comb = if (had) Mat.mul(FloatArray(16), saved, m) else m
        transform(comb)
        box(0f, 0.5f, 0f, 1f, 1f, 1f)
        if (had) transform(saved) else transform(null)
        return this
    }

    /** Flat polygon (in the XY plane, CCW) extruded along Z by [thick], with bevel-free sides. */
    fun extrude(poly: FloatArray, thick: Float): MeshBuilder {
        val n = poly.size / 2
        val hz = thick / 2
        for (side in listOf(1f, -1f)) {
            val c = vertexCount
            for (i in 0 until n) vertex(poly[2 * i], poly[2 * i + 1], side * hz, 0f, 0f, side)
            for (i in 1 until n - 1) if (side > 0) tri(c, c + i, c + i + 1) else tri(c, c + i + 1, c + i)
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = poly[2 * i]; val y0 = poly[2 * i + 1]; val x1 = poly[2 * j]; val y1 = poly[2 * j + 1]
            var nx = y1 - y0; var ny = -(x1 - x0); val l = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-9f); nx /= l; ny /= l
            val a = vertex(x0, y0, hz, nx, ny, 0f); val b = vertex(x1, y1, hz, nx, ny, 0f)
            val c = vertex(x1, y1, -hz, nx, ny, 0f); val d = vertex(x0, y0, -hz, nx, ny, 0f)
            quad(a, d, c, b)
        }
        return this
    }

    fun buildArrays(): Pair<FloatArray, IntArray> = v.copyOf(vn) to ix.copyOf(ixn)
    fun build(): Mesh { val (a, b) = buildArrays(); return Mesh(a, b) }

    companion object {
        /** angle helper for tests */
        fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float) = acos((ax * bx + ay * by).coerceIn(-1f, 1f))
    }
}
