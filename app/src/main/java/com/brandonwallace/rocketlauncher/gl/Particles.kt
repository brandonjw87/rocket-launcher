package com.brandonwallace.rocketlauncher.gl

import kotlin.math.max
import kotlin.math.min

/**
 * Structure-of-arrays particle pool (no per-particle objects -> no GC hitches).
 *
 * Two coordinate frames avoid float precision problems and "sliding" smoke:
 *  - GROUND particles store pad-frame world Y (launch steam stays glued to the pad);
 *  - FLIGHT particles store Y relative to the rocket base and scroll with the world, like hazards.
 */
class Particles(val capacity: Int) {
    companion object {
        const val SMOKE = 0; const val FLAME = 1; const val CLOUD = 2; const val SPARK = 3
        const val GROUND = 0; const val FLIGHT = 1
    }

    var count = 0; private set
    val x = FloatArray(capacity); val y = FloatArray(capacity); val z = FloatArray(capacity)
    val vx = FloatArray(capacity); val vy = FloatArray(capacity); val vz = FloatArray(capacity)
    val life = FloatArray(capacity); val maxLife = FloatArray(capacity)
    val size0 = FloatArray(capacity); val size1 = FloatArray(capacity)
    val r = FloatArray(capacity); val g = FloatArray(capacity); val b = FloatArray(capacity); val a = FloatArray(capacity)
    val kind = IntArray(capacity); val frame = IntArray(capacity)
    val drag = FloatArray(capacity); val buoy = FloatArray(capacity)
    val rot = FloatArray(capacity); val spin = FloatArray(capacity); val seed = FloatArray(capacity)

    /** Soft budget (quality setting); emitters stop when reached instead of overwriting live particles. */
    var budget = capacity

    private var rng = 0x2545F491
    fun rnd(): Float { rng = rng xor (rng shl 13); rng = rng xor (rng ushr 17); rng = rng xor (rng shl 5); return (rng ushr 8) / 16777216f }
    fun rnd(a: Float, b: Float) = a + (b - a) * rnd()

    fun emit(
        k: Int, fr: Int, px: Float, py: Float, pz: Float, svx: Float, svy: Float, svz: Float,
        lifeS: Float, s0: Float, s1: Float, cr: Float, cg: Float, cb: Float, ca: Float,
        dragK: Float = 0.5f, buoyancy: Float = 0f,
    ): Boolean {
        if (count >= min(budget, capacity)) return false
        val i = count++
        x[i] = px; y[i] = py; z[i] = pz; vx[i] = svx; vy[i] = svy; vz[i] = svz
        life[i] = 0f; maxLife[i] = lifeS; size0[i] = s0; size1[i] = s1
        r[i] = cr; g[i] = cg; b[i] = cb; a[i] = ca
        kind[i] = k; frame[i] = fr; drag[i] = dragK; buoy[i] = buoyancy
        rot[i] = rnd() * 6.2832f; spin[i] = rnd(-0.6f, 0.6f); seed[i] = rnd()
        return true
    }

    /** [scroll]: world scroll speed (m/s, downwards) applied to FLIGHT particles. [wind]: lateral drift. */
    fun update(dt: Float, scroll: Float, wind: Float) {
        var i = 0
        while (i < count) {
            life[i] += dt
            if (life[i] >= maxLife[i]) { kill(i); continue }
            val d = max(0f, 1f - drag[i] * dt)
            vx[i] = vx[i] * d + wind * drag[i] * dt * 0.5f
            vy[i] = vy[i] * d + buoy[i] * dt
            vz[i] *= d
            rot[i] += spin[i] * dt
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt
            if (frame[i] == FLIGHT) y[i] -= scroll * dt
            // ground steam never sinks below the pad
            if (frame[i] == GROUND && y[i] < -1.5f) { y[i] = -1.5f; vy[i] = max(vy[i], 0f) }
            i++
        }
    }

    private fun kill(i: Int) {
        val j = --count
        if (i == j) return
        x[i] = x[j]; y[i] = y[j]; z[i] = z[j]; vx[i] = vx[j]; vy[i] = vy[j]; vz[i] = vz[j]
        life[i] = life[j]; maxLife[i] = maxLife[j]; size0[i] = size0[j]; size1[i] = size1[j]
        r[i] = r[j]; g[i] = g[j]; b[i] = b[j]; a[i] = a[j]; kind[i] = kind[j]; frame[i] = frame[j]
        drag[i] = drag[j]; buoy[i] = buoy[j]; rot[i] = rot[j]; spin[i] = spin[j]; seed[i] = seed[j]
    }

    fun clear() { count = 0 }

    /** Normalised age 0..1. */
    fun t(i: Int) = (life[i] / maxLife[i]).coerceIn(0f, 1f)
    fun size(i: Int): Float { val t = t(i); return size0[i] + (size1[i] - size0[i]) * (1f - (1f - t) * (1f - t)) }
    /** Alpha fades out smoothly over the whole life (fade-in is done in the shader) -> no popping. */
    fun alpha(i: Int): Float {
        val t = t(i)
        return a[i] * when (kind[i]) {
            FLAME -> (1f - t) * (1f - t)
            SPARK -> 1f - t
            else -> (1f - t) * (1f - t * 0.5f)
        }
    }
}

/**
 * Builds the per-frame instance stream: alpha-blended (smoke + cloud puffs) sorted back-to-front with a
 * stable packed-key sort, and additive (flame + sparks) unsorted (order-independent).
 * Layout per instance: posSize(4) color(4) params(4).
 */
class BillboardBatch(val capacity: Int) {
    val data = FloatArray(capacity * 12)
    private val keys = LongArray(capacity)
    private val tmp = FloatArray(capacity * 12)
    var alphaCount = 0; private set
    var addCount = 0; private set
    private var n = 0

    fun begin() { n = 0; alphaCount = 0; addCount = 0 }

    /** Add an instance into the staging area. [depth] = view-space distance along the camera forward. */
    fun stage(px: Float, py: Float, pz: Float, size: Float, cr: Float, cg: Float, cb: Float, ca: Float, kind: Int, rot: Float, life: Float, seed: Float, depth: Float) {
        if (n >= capacity || depth < 0.05f) return
        val o = n * 12
        tmp[o] = px; tmp[o + 1] = py; tmp[o + 2] = pz; tmp[o + 3] = size
        tmp[o + 4] = cr; tmp[o + 5] = cg; tmp[o + 6] = cb; tmp[o + 7] = ca
        tmp[o + 8] = kind.toFloat(); tmp[o + 9] = rot; tmp[o + 10] = life; tmp[o + 11] = seed
        val additive = kind == Particles.FLAME || kind == Particles.SPARK
        // sort key: additive after all alpha; alpha far-to-near (descending depth); index as a stable tie-break
        val dBits = java.lang.Float.floatToRawIntBits(depth.coerceIn(0.05f, 1e7f)).toLong()
        val group = if (additive) 1L else 0L
        val order = if (additive) 0L else (0x7FFFFFFFL - dBits)
        keys[n] = (group shl 62) or (order shl 20) or n.toLong()
        if (additive) addCount++ else alphaCount++
        n++
    }

    /** Sorts and writes the final ordered stream into [data]; returns the instance count. */
    fun finish(): Int {
        java.util.Arrays.sort(keys, 0, n)
        for (k in 0 until n) {
            val src = (keys[k] and 0xFFFFF).toInt() * 12
            System.arraycopy(tmp, src, data, k * 12, 12)
        }
        return n
    }
}
