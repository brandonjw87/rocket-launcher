package com.brandonwallace.rocketlauncher.gl

import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.Stat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object MatType { const val PLAIN = 0; const val BODY = 1; const val CARBON = 2; const val FOAM = 3; const val STEEL = 4; const val TILES = 5; const val TERRAIN = 6; const val ENGINE = 7; const val METAL = 8; const val PAD = 9; const val FOIL = 10; const val NANO = 11 }

/** One drawable piece of the rocket. Mesh vertices are in metres in the part's local frame. */
class Part(
    val group: Int, val mesh: Mesh, val local: FloatArray, val mat: Int,
    val panel: FloatArray = floatArrayOf(1.2f, 0f, 0f, 0f),
    val decal: FloatArray? = null, val decalRow: Int = 0,
    val roughMul: Float = 1f,
    /** Engine bell: glows when that stage burns. */
    val engine: Boolean = false,
    /** Self-lit parts (AI sensor dome, laser lens, ion thrusters, graphene bands). */
    val emissive: FloatArray? = null,
    /** Solar wing / sail quadrant / tether: folded against the body on the pad, swings out once in space. */
    val hinge: Boolean = false,
)

class Nozzle(val x: Float, val z: Float, val radius: Float)

/**
 * Procedural rocket. Everything visible comes from the researched tech: body material (paint → aluminium →
 * carbon → cryo foam → stainless → graphene), engine type and count (1 → 3 → 9 → 13 → 20 → 33), gimbal
 * actuators, vacuum bells and plasma coils, nose profile and spike, fins, grid fins, RCS/ion thrusters,
 * avionics hardware (antennas, dishes, AI dome, laser terminal, solar wings, RTGs), thermal and radiation
 * protection (gold foil vault), Whipple shields, the Europa lander and sky-crane, boosters/side cores and the
 * number of stages. 1.3 adds the Saturn program: detonation and aerospike engines (42/60), fusion and antimatter
 * upper stages, six side cores, eight stages, sails / tethers / magnetic loop / lightsail that open in space,
 * ring shields, the Titan probe and Dragonfly rotorcraft, reactors and a Cassini-style dish, and the
 * nanotube / diamond / nanocomposite hulls. (The exhaust itself changes too: see [Exhaust].)
 */
class RocketModel(val spec: RocketSpec, private val segs: Int) {
    companion object {
        const val PAYLOAD = 100
        /** Hinged solar wings, sail quadrants and tethers (attached to the payload, swing out in space). */
        const val ARRAYS = 101
        /** Parts that open out in space by growing sideways (magnetic loop, lightsail). */
        const val SPREAD = 102
        private val GLOW_BLUE = floatArrayOf(0.35f, 1.1f, 3.2f)
        private val GLOW_GREEN = floatArrayOf(0.3f, 3.0f, 0.8f)
        private val GLOW_VIOLET = floatArrayOf(1.6f, 0.45f, 3.4f)
        private val GLOW_CYAN = floatArrayOf(0.3f, 2.4f, 2.8f)
        private val GLOW_ORANGE = floatArrayOf(3.2f, 1.1f, 0.25f)
        private val GLOW_RED = floatArrayOf(3.4f, 0.25f, 0.2f)
        private val GLOW_WHITE = floatArrayOf(3.0f, 3.0f, 3.4f)
        private val GLOW_PINK = floatArrayOf(3.0f, 0.9f, 2.0f)
    }

    val parts = ArrayList<Part>()
    val stageBase = FloatArray(spec.stages.size)
    val stageTop = FloatArray(spec.stages.size)
    val nozzles = ArrayList<List<Nozzle>>()
    val boosterOffsets = ArrayList<FloatArray>()
    var boosterNozzleR = 0.3f; private set
    var totalLength = 0f; private set
    var maxRadius = 0f; private set
    /** Gas-generator turbine exhaust outlet on stage 1 (stage-local), or null. */
    var ggExhaust: FloatArray? = null; private set

    private fun has(id: String) = spec.has(id)

    // ---- material choices from tech
    private val graphene = has("S_GRAPHENE")
    /** 1.3 hulls: filament-wound nanotube tanks, then the midnight-blue nanocomposite monocoque. */
    private val nano = has("S_NANO") || has("S_NANOMONO")
    private val bodyMat = when { nano -> MatType.NANO; graphene -> MatType.CARBON; has("S_BALLOON") -> MatType.STEEL; has("S_CRYO") -> MatType.FOAM; has("S_COMP") -> MatType.CARBON; else -> MatType.BODY }
    private val alu = has("S_ALU")
    private val bodyCol = when {
        has("S_NANOMONO") -> floatArrayOf(0.05f, 0.07f, 0.17f)
        nano -> floatArrayOf(0.11f, 0.11f, 0.13f)
        alu && bodyMat == MatType.BODY -> floatArrayOf(0.80f, 0.81f, 0.83f)
        else -> floatArrayOf(0.92f, 0.92f, 0.9f)
    }
    private val bodyRough = if (alu) 0.36f else 0.42f
    private val bodyMetal = if (alu && bodyMat == MatType.BODY) 0.9f else 0f
    private val paintPattern = if (!alu && bodyMat == MatType.BODY) 1f else 0f
    /** Diamond film: a glassy, sparkling clear coat over everything. */
    private val roughMul = when { has("S_DIAMOND") -> 0.18f; has("A_WAVE") -> 0.35f; has("A_SMOOTH") -> 0.55f; else -> 1f }
    private val accent = when (bodyMat) {
        MatType.NANO -> floatArrayOf(0.25f, 0.75f, 0.85f)
        MatType.CARBON -> floatArrayOf(0.85f, 0.85f, 0.88f)
        MatType.FOAM -> floatArrayOf(0.06f, 0.06f, 0.07f)
        MatType.STEEL -> floatArrayOf(0.15f, 0.15f, 0.16f)
        else -> if (alu) floatArrayOf(0.12f, 0.2f, 0.45f) else floatArrayOf(0.75f, 0.12f, 0.08f)
    }

    init { build() }

    private fun mb() = MeshBuilder()
    private fun add(group: Int, b: MeshBuilder, mat: Int, panel: FloatArray = floatArrayOf(1.2f, 0f, 0f, 0f), decal: FloatArray? = null, decalRow: Int = 0, engine: Boolean = false, local: FloatArray = Mat.new(), rm: Float = roughMul,
                    emissive: FloatArray? = null, hinge: Boolean = false) {
        if (b.isEmpty) return
        parts += Part(group, b.build(), local, mat, panel, decal, decalRow, rm, engine, emissive, hinge)
    }

    private fun ring(n: Int, rad: Float, phase: Float) = (0 until n).map { k -> val a = k * 2f * PI.toFloat() / n + phase; rad * cos(a) to rad * sin(a) }

    /** Bell nozzle (outer + glowing inner surface) with exit at y0. Returns its height. */
    private fun bell(b: MeshBuilder, x: Float, z: Float, y0: Float, re: Float, len: Float, solid: Boolean) {
        val rt = re * (if (solid) 0.55f else 0.36f)
        val prof = ArrayList<Float>()
        val n = 9
        val yT = len * (if (solid) 0.6f else 0.8f)
        for (k in 0..n) {
            val s = k / n.toFloat()
            val r = rt + (re - rt) * (1 - s).pow(if (solid) 1.0f else 1.8f)
            prof += r; prof += y0 + s * yT
        }
        prof += rt * 1.55f; prof += y0 + len * 0.9f
        prof += rt * 1.55f; prof += y0 + len
        prof += rt * 0.6f; prof += y0 + len * 1.08f
        val p = prof.toFloatArray()
        b.transform(Mat.trs(FloatArray(16), x, 0f, z))
        b.lathe(p, segs)
        // inner wall (slightly smaller) facing inward — this is what glows
        val inner = FloatArray((n + 1) * 2)
        for (k in 0..n) { inner[2 * k] = p[2 * k] * 0.95f; inner[2 * k + 1] = p[2 * k + 1] + 0.01f }
        b.lathe(inner, segs, inside = true)
        b.transform(null)
    }

    /** Ring of round cross-section (radius [rc]) around the Y axis at height [y]. */
    private fun torus(b: MeshBuilder, rr: Float, y: Float, rc: Float, n: Int = 10) {
        val prof = FloatArray((n + 1) * 2)
        for (k in 0..n) {
            val t = (-PI / 2 + 2 * PI * k / n).toFloat()   // counter-clockwise from the bottom: normals point outward
            prof[2 * k] = rr + rc * cos(t); prof[2 * k + 1] = y + rc * sin(t)
        }
        b.lathe(prof, segs)
    }

    /** Rotating detonation engine: an annular chamber (glowing channel) around a central plug, exit at y0. */
    private fun ringEngine(b: MeshBuilder, x: Float, z: Float, y0: Float, re: Float, len: Float) {
        b.transform(Mat.trs(FloatArray(16), x, 0f, z))
        b.lathe(floatArrayOf(re, y0, re * 1.03f, y0 + len * 0.15f, re, y0 + len, re * 0.55f, y0 + len * 1.1f), segs)
        b.lathe(floatArrayOf(re * 0.93f, y0 + 0.01f, re * 0.93f, y0 + len * 0.95f), segs, inside = true)
        b.lathe(floatArrayOf(0.001f, y0 - len * 0.35f, re * 0.6f, y0 + len * 0.15f, re * 0.6f, y0 + len * 0.98f), segs)
        b.transform(null)
    }

    private fun build() {
        val nSt = spec.stages.size
        val engineLv = spec.level(Stat.ENGINE)
        val solid = !has("P_LIQUID")
        val vac = spec.level(Stat.VACUUM)
        var base = 0f
        // pre-compute upper-stage bell sizes (they sit inside the interstage below)
        val ubl = FloatArray(nSt); val ure = FloatArray(nSt)
        for (i in 1 until nSt) {
            val r = spec.stages[i].radius
            // deep-space nozzles (level 3) widen the bell a little more but never past the interstage below
            ure[i] = r * min(0.5f + 0.17f * vac, 0.9f); ubl[i] = r * (0.75f + 0.5f * min(vac, 2) + 0.2f * max(vac - 2, 0))
        }
        for (i in 0 until nSt) {
            val st = spec.stages[i]
            val r = st.radius
            maxRadius = max(maxRadius, r)
            stageBase[i] = base
            val len = st.length
            val engines = mb().color(0.22f, 0.21f, 0.2f).material(0.35f, 0.9f)
            val noz = ArrayList<Nozzle>()
            val tankStart: Float
            if (i == 0) {
                val count = spec.engineCount
                val spike = has("P_SPIKE")
                val rde = has("P_RDE")
                val bl = r * (if (solid) 0.5f else 0.8f + 0.05f * engineLv.coerceAtMost(10))
                val positions = when (count) {
                    60 -> ring(22, r * 0.52f, 0f) + ring(38, r * 0.85f, 0.08f)
                    42 -> if (spike) ring(18, r * 0.52f, 0f) + ring(24, r * 0.84f, 0.13f)
                        else ring(4, r * 0.16f, 0f) + ring(14, r * 0.5f, 0.2f) + ring(24, r * 0.84f, 0.13f)
                    33 -> ring(3, r * 0.18f, 0f) + ring(10, r * 0.48f, 0.3f) + ring(20, r * 0.82f, 0.1f)
                    20 -> ring(6, r * 0.33f, 0f) + ring(14, r * 0.76f, 0.2f)
                    13 -> (0 until 3).map { k -> val a = k * 2f * PI.toFloat() / 3f; r * 0.2f * cos(a) to r * 0.2f * sin(a) } +
                        (0 until 10).map { k -> val a = k * PI.toFloat() / 5f + 0.3f; r * 0.7f * cos(a) to r * 0.7f * sin(a) }
                    9 -> listOf(0f to 0f) + (0 until 8).map { k -> val a = k * PI.toFloat() / 4f; r * 0.62f * cos(a) to r * 0.62f * sin(a) }
                    3 -> (0 until 3).map { k -> val a = k * 2f * PI.toFloat() / 3f + PI.toFloat() / 2f; r * 0.45f * cos(a) to r * 0.45f * sin(a) }
                    else -> listOf(0f to 0f)
                }
                val re = when (count) {
                    60 -> r * 0.075f; 42 -> r * 0.095f; 33 -> r * (if (has("P_MK4")) 0.124f else 0.115f); 20 -> r * 0.15f; 13 -> r * 0.165f; 9 -> r * 0.21f; 3 -> r * 0.36f
                    else -> r * (if (solid) 0.5f else 0.64f)
                }
                when {
                    solid -> engines.color(0.16f, 0.16f, 0.17f).material(0.7f, 0.1f)
                    rde -> engines.color(0.62f, 0.6f, 0.66f).material(0.2f, 1f)              // machined detonation rings
                    has("P_MK4") -> engines.color(0.46f, 0.45f, 0.5f).material(0.16f, 1f)     // Mk IV: brighter, polished
                    has("P_METHALOX") -> engines.color(0.34f, 0.33f, 0.35f).material(0.24f, 1f)   // polished methalox bells
                }
                // transpiration cooling: porous, darker chamber walls
                if (has("TH_TRANSP")) engines.color(engines.r * 0.62f, engines.g * 0.58f, engines.b * 0.56f)
                for ((x, z) in positions) {
                    if (rde) ringEngine(engines, x, z, 0f, re, bl * 0.55f) else bell(engines, x, z, 0f, re, bl, solid)
                    noz += Nozzle(x, z, re)
                }
                // aerospike core: a long plug in the middle of the cluster (cooling channels with Regenerative Spike Cooling)
                if (spike) {
                    val sp = mb().color(0.5f, 0.48f, 0.52f).material(0.25f, 1f)
                    sp.lathe(floatArrayOf(0.02f, -bl * 0.75f, r * 0.12f, -bl * 0.2f, r * 0.24f, bl * 0.5f, r * 0.3f, bl * 0.95f), segs)
                    add(0, sp, MatType.ENGINE, floatArrayOf(bl * 0.6f, -bl * 0.75f, if (has("TH_REGEN3")) 1f else 0f, 0f), engine = true)
                }
                // tripropellant: hydrogen manifold ring + feed lines
                if (has("P_TRIPROP")) {
                    val hm = mb().color(0.88f, 0.9f, 0.94f).material(0.3f, 0.6f)
                    torus(hm, r * 0.9f, bl * 0.95f, 0.055f)
                    for (k in 0 until 8) { val a = k * PI.toFloat() / 4f + 0.2f; hm.beam(cos(a) * r * 0.9f, bl * 0.95f, sin(a) * r * 0.9f, cos(a) * r * 0.96f, bl + 0.22f, sin(a) * r * 0.96f, 0.035f) }
                    add(0, hm, MatType.METAL)
                }
                // gimbal actuators (thrust-vector control: red electric struts; differential throttling: gold)
                if (spec.level(Stat.GIMBAL) >= 1) {
                    val tvc = spec.level(Stat.GIMBAL) >= 2
                    val gold = spec.level(Stat.GIMBAL) >= 3
                    val gb = when {
                        gold -> mb().color(0.9f, 0.68f, 0.22f).material(0.25f, 1f)
                        tvc -> mb().color(0.78f, 0.12f, 0.08f).material(0.45f, 0.6f)
                        else -> mb().color(0.32f, 0.32f, 0.34f).material(0.45f, 0.6f)
                    }
                    for ((x, z) in positions.take(13)) for (k in 0 until 2) {
                        val a = k * PI.toFloat() / 2f + 0.4f
                        gb.beam(x + cos(a) * re * 0.55f, bl * 0.95f, z + sin(a) * re * 0.55f, x + cos(a) * re * 0.62f, bl * 0.45f, z + sin(a) * re * 0.62f, re * 0.06f + 0.015f)
                    }
                    add(0, gb, MatType.METAL)
                }
                if (!solid && count == 1 && has("P_GG")) ggExhaust = floatArrayOf(r * 0.55f, bl * 0.05f, r * 0.1f)
                // turbopump / gas-generator plumbing on single-engine liquid stages
                if (!solid && count == 1) {
                    val pb = mb().color(0.45f, 0.43f, 0.4f).material(0.4f, 0.85f)
                    pb.transform(Mat.trs(FloatArray(16), r * 0.38f, bl * 0.86f, 0f)); pb.cylinder(r * 0.12f, -bl * 0.12f, bl * 0.12f, 12); pb.transform(null)
                    pb.beam(r * 0.38f, bl * 0.95f, 0f, r * 0.12f, bl * 0.98f, 0f, r * 0.05f)
                    if (has("P_GG")) pb.beam(r * 0.42f, bl * 0.75f, r * 0.05f, r * 0.55f, bl * 0.05f, r * 0.1f, r * 0.07f)
                    add(0, pb, MatType.METAL)
                }
                add(0, engines, MatType.ENGINE, floatArrayOf(bl * 0.85f, 0f, if (has("TH_REGEN")) 1f else 0f, 0f), engine = true)
                // beamed-energy boost: gold laser-receiver ring around the base of the first stage
                if (has("P_BEAMED")) {
                    val lr = mb().color(0.95f, 0.76f, 0.3f).material(0.12f, 1f)
                    val yb = bl + 0.85f
                    lr.lathe(floatArrayOf(r * 1.004f, yb, r * 1.2f, yb + 0.06f, r * 1.2f, yb + 0.16f, r * 1.004f, yb + 0.26f), segs)
                    add(0, lr, MatType.METAL)
                }
                // boat-tail skirt
                val sk = mb().color(accent[0] * 0.4f + 0.1f, accent[1] * 0.4f + 0.1f, accent[2] * 0.4f + 0.1f).material(0.5f, 0.3f)
                sk.lathe(floatArrayOf(r * 0.9f, bl * 0.55f, r, bl + 0.25f), segs)
                add(0, sk, MatType.BODY, floatArrayOf(0.35f, 16f, 0f, 0f))
                tankStart = bl + 0.25f
            } else {
                val re = ure[i]; val bl = ubl[i]
                val ext = vac >= 2
                val nuc = spec.level(Stat.NUCLEAR)
                val plasma = nuc >= 3
                when {
                    nuc >= 7 -> engines.color(0.82f, 0.84f, 0.9f).material(0.15f, 1f)     // antimatter: polished white chamber
                    nuc >= 5 -> engines.color(0.7f, 0.64f, 0.72f).material(0.18f, 1f)     // direct fusion: pinkish alloy
                    plasma -> engines.color(0.55f, 0.56f, 0.6f).material(0.22f, 1f)        // polished plasma chamber
                    has("P_NTR") -> engines.color(0.62f, 0.48f, 0.18f).material(0.28f, 1f) // gold reactor shielding
                    else -> engines.color(0.25f, 0.24f, 0.23f).material(0.32f, 0.9f)
                }
                if (has("TH_TRANSP")) engines.color(engines.r * 0.7f, engines.g * 0.66f, engines.b * 0.64f)
                bell(engines, 0f, 0f, 0f, re, bl, false)
                var exitR = re
                if (ext) { // dark niobium extension ring (carbon-carbon skirts make it much longer)
                    val skirt = if (vac >= 4) 0.55f else 0.25f
                    exitR = re * (if (vac >= 4) 1.22f else 1.12f)
                    val ex = mb().color(if (vac >= 4) 0.07f else 0.16f, if (vac >= 4) 0.07f else 0.13f, if (vac >= 4) 0.08f else 0.17f).material(0.4f, 0.8f)
                    ex.lathe(floatArrayOf(exitR, -bl * skirt, re, 0f), segs)
                    ex.lathe(floatArrayOf(exitR * 0.95f, -bl * skirt, re * 0.95f, 0f), segs, inside = true)
                    add(i, ex, MatType.ENGINE, floatArrayOf(0f, 0f, 0f, 0f), engine = true)
                    val yExit = -bl * skirt
                    // dual-bell nozzle: a stepped second contour below the skirt
                    if (has("P_DUALBELL")) {
                        val db = mb().color(0.1f, 0.1f, 0.11f).material(0.4f, 0.8f)
                        db.lathe(floatArrayOf(exitR * 1.2f, yExit - bl * 0.18f, exitR * 1.04f, yExit - 0.02f, exitR, yExit + 0.04f), segs)
                        db.lathe(floatArrayOf(exitR * 1.15f, yExit - bl * 0.18f, exitR * 0.97f, yExit), segs, inside = true)
                        add(i, db, MatType.ENGINE, floatArrayOf(0f, 0f, 0f, 0f), engine = true)
                        exitR *= 1.2f
                    }
                    // folding petal nozzle: dark petals flare out from the exit
                    if (has("P_PETAL")) {
                        val pt = mb().color(0.08f, 0.08f, 0.09f).material(0.45f, 0.7f)
                        val y1 = yExit - bl * (if (has("P_DUALBELL")) 0.18f else 0f)
                        for (k in 0 until 12) {
                            pt.transform(Mat.mul(FloatArray(16), Mat.trs(FloatArray(16), 0f, y1, 0f, ry = k * 30f), Mat.trs(FloatArray(16), exitR * 0.98f, 0f, 0f, rz = -24f)))
                            pt.box(0f, -bl * 0.22f, 0f, 0.025f, bl * 0.44f, exitR * 0.5f)
                            pt.transform(null)
                        }
                        add(i, pt, MatType.METAL)
                        exitR *= 1.25f
                    }
                    // superconducting magnetic nozzle: glowing blue magnet rings at the exit
                    if (has("P_MAGNOZ")) {
                        val mg = mb().color(0.3f, 0.55f, 1f).material(0.2f, 0f)
                        torus(mg, re * 1.1f, yExit * 0.35f, 0.05f); torus(mg, re * 1.18f, yExit * 0.75f, 0.05f)
                        add(i, mg, MatType.PLAIN, emissive = GLOW_BLUE)
                    }
                }
                // direct fusion drive: reactor rings around the chamber (twin-reactor: a second pair)
                if (nuc >= 5) {
                    val rx = mb().color(0.78f, 0.78f, 0.82f).material(0.25f, 1f)
                    val nr = if (nuc >= 6) 4 else 2
                    for (k in 0 until nr) torus(rx, re * (1.18f - 0.1f * k), bl * (0.5f + 0.13f * k), 0.07f)
                    add(i, rx, MatType.METAL)
                    val core = mb().color(1f, 0.5f, 0.8f).material(0.2f, 0f)
                    torus(core, re * 0.98f, bl * 0.5f, 0.03f)
                    add(i, core, MatType.PLAIN, emissive = GLOW_PINK)
                }
                // antimatter: glowing magnetic trap pods on struts
                if (nuc >= 7) {
                    val pods = mb().color(0.7f, 0.85f, 1f).material(0.15f, 0f)
                    val struts = mb().color(0.7f, 0.7f, 0.74f).material(0.35f, 0.9f)
                    for (k in 0 until 4) {
                        val a = (PI / 4 + k * PI / 2).toFloat()
                        val px = cos(a) * re * 1.45f; val pz = sin(a) * re * 1.45f
                        struts.beam(cos(a) * re * 0.9f, bl * 0.35f, sin(a) * re * 0.9f, px, bl * 0.42f, pz, 0.03f)
                        pods.transform(Mat.trs(FloatArray(16), px, bl * 0.42f, pz)); pods.sphere(0.11f, 10, 6); pods.transform(null)
                    }
                    add(i, struts, MatType.METAL); add(i, pods, MatType.PLAIN, emissive = GLOW_WHITE)
                }
                // antimatter beam core: a long thin beam nozzle with a white-hot tip ring
                if (nuc >= 8) {
                    val bn = mb().color(0.85f, 0.86f, 0.9f).material(0.15f, 1f)
                    bn.lathe(floatArrayOf(re * 0.18f, -bl * 1.25f, re * 0.24f, -bl * 0.2f, re * 0.5f, bl * 0.15f), segs)
                    add(i, bn, MatType.METAL)
                    val tip = mb().color(1f, 1f, 1f).material(0.2f, 0f)
                    torus(tip, re * 0.22f, -bl * 1.25f, 0.035f)
                    add(i, tip, MatType.PLAIN, emissive = GLOW_WHITE)
                }
                add(i, engines, MatType.ENGINE, floatArrayOf(bl * 0.85f, 0f, if (has("TH_REGEN")) 1f else 0f, 0f), engine = true)
                if (plasma) {   // copper superconducting magnet coils around the magnetic nozzle
                    val coils = mb().color(0.78f, 0.42f, 0.2f).material(0.3f, 1f)
                    val n = if (spec.level(Stat.NUCLEAR) >= 4) 5 else 3
                    for (k in 0 until n) {
                        val yy = bl * (0.12f + 0.62f * k / (n - 1).coerceAtLeast(1))
                        val rr = re * (1.02f - 0.45f * k / n) + 0.04f
                        coils.lathe(floatArrayOf(rr, yy - 0.05f, rr + 0.07f, yy, rr, yy + 0.05f), segs)
                    }
                    add(i, coils, MatType.METAL)
                }
                noz += Nozzle(0f, 0f, exitR)
                tankStart = bl + 0.1f
            }
            nozzles += noz

            // tank body
            val top = len.coerceAtLeast(tankStart + 0.8f)
            val tank = mb().color(bodyCol[0], bodyCol[1], bodyCol[2]).material(bodyRough, bodyMetal)
            tank.lathe(floatArrayOf(r * 0.97f, tankStart, r, tankStart + 0.06f, r, top - 0.06f, r * 0.985f, top), segs)
            val ringSp = when { has("S_ALLI") && bodyMat == MatType.BODY -> 0.6f; bodyMat == MatType.FOAM -> 2.0f; else -> 1.2f }
            val seams = when (bodyMat) { MatType.BODY -> if (alu) 8f else 0f; MatType.STEEL -> 0f; else -> 4f }
            val decal = when (i) {
                0 -> { val ly = min(4.6f, (top - tankStart) * 0.75f); floatArrayOf(tankStart + 0.35f, tankStart + 0.35f + ly, (PI / 2).toFloat(), ly / (16f * r)) }
                1 -> { val ly = min(3.2f, (top - tankStart) * 0.7f); floatArrayOf(tankStart + 0.3f, tankStart + 0.3f + ly, (PI / 2).toFloat(), ly / (16f * r)) }
                else -> null
            }
            val foilTop = i == nSt - 1 && i > 0 && spec.level(Stat.RADIATION) >= 1
            if (foilTop) {   // radiation vault program: the top stage wears crinkled gold multi-layer insulation
                val gold = mb().color(0.86f, 0.63f, 0.2f).material(0.32f, 1f)
                gold.lathe(floatArrayOf(r * 0.97f, tankStart, r, tankStart + 0.06f, r, top - 0.06f, r * 0.985f, top), segs)
                add(i, gold, MatType.FOIL)
            } else add(i, tank, bodyMat, floatArrayOf(ringSp, seams, 0f, paintPattern), decal, if (i == 0) 0 else 1)
            // Whipple shield: quilted stand-off bumper panels around the upper stages (stuffed: thicker, finer quilting)
            val stuffed = has("S_STUFFED")
            val wOut = if (stuffed) 1.055f else 1.035f
            if (i > 0 && !foilTop && has("S_WHIPPLE")) {
                val wb = mb().color(0.93f, 0.93f, 0.9f).material(0.75f, 0f)
                val y0w = tankStart + 0.25f; val y1w = top - 0.3f
                if (y1w > y0w + 0.3f) {
                    wb.lathe(floatArrayOf(r * 1.012f, y0w, r * wOut, y0w + 0.05f, r * wOut, y1w - 0.05f, r * 1.012f, y1w), segs)
                    add(i, wb, MatType.BODY, floatArrayOf(if (stuffed) 0.3f else 0.45f, if (stuffed) 20f else 12f, 0f, 0f))
                }
            }
            buildSaturnStageDecor(i, r, tankStart, top, foilTop, wOut)
            // graphene frame: glowing blue bands
            if (graphene) {
                val gl = mb().color(0.2f, 0.5f, 1f).material(0.3f, 0f)
                for (yy in listOf(tankStart + 0.35f, top - 0.35f)) gl.lathe(floatArrayOf(r * 1.004f, yy - 0.05f, r * 1.008f, yy, r * 1.004f, yy + 0.05f), segs)
                add(i, gl, MatType.PLAIN, emissive = GLOW_BLUE)
            }
            // zero-boil-off cryocooler radiators near the top of the first stage
            if (i == 0 && has("S_ZBO")) {
                val zr = mb().color(0.92f, 0.93f, 0.95f).material(0.35f, 0.2f)
                for (k in 0 until 4) {
                    zr.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = 22.5f + k * 90f))
                    zr.box(r * 1.07f, top - 1.3f, 0f, 0.04f, 1.5f, r * 0.7f)
                    zr.beam(r * 0.99f, top - 1.3f, 0f, r * 1.07f, top - 1.3f, 0f, 0.05f)
                    zr.transform(null)
                }
                add(0, zr, MatType.METAL)
            }
            // slush-densified propellant: frost bands on the cold lower tanks
            if (i <= 1 && has("S_SLUSH")) {
                // triple-point propellants: the frost climbs much higher and whiter
                val tp = has("S_TRIPLE")
                val fr = if (tp) mb().color(0.96f, 0.98f, 1f).material(0.97f, 0f) else mb().color(0.9f, 0.93f, 0.97f).material(0.95f, 0f)
                val f0 = if (tp) 0.6f else 0.35f
                fr.lathe(floatArrayOf(r * 1.002f, tankStart + 0.1f, r * 1.006f, tankStart + 0.2f, r * 1.006f, tankStart + (top - tankStart) * f0, r * 1.002f, tankStart + (top - tankStart) * (f0 + 0.03f)), segs)
                add(i, fr, MatType.PLAIN)
            }
            // RCS thruster quads near the top of the first stage
            if (i == 0 && has("A_RCS")) buildRcs(0, r, top - 0.45f)
            // reinforcement bands (monocoque) / weld rings
            if (has("S_MONO") || has("S_TITAN") || bodyMat == MatType.STEEL) {
                val bands = mb().color(accent[0], accent[1], accent[2]).material(0.4f, 0.5f)
                bands.lathe(floatArrayOf(r * 1.004f, tankStart + 0.25f, r * 1.012f, tankStart + 0.3f, r * 1.012f, tankStart + 0.55f, r * 1.004f, tankStart + 0.6f), segs)
                bands.lathe(floatArrayOf(r * 1.004f, top - 0.6f, r * 1.012f, top - 0.55f, r * 1.012f, top - 0.3f, r * 1.004f, top - 0.25f), segs)
                add(i, bands, MatType.METAL)
            }
            // bimodal nuclear core: radiator panels on the upper stages
            if (i > 0 && has("P_NTR2")) {
                val rad = mb().color(0.82f, 0.84f, 0.86f).material(0.3f, 0.7f)
                for (s in 0..1) { val sx = if (s == 0) -1f else 1f; rad.box(sx * (r + 0.42f), tankStart + (top - tankStart) * 0.5f, 0f, 0.7f, (top - tankStart) * 0.6f, 0.04f) }
                add(i, rad, MatType.METAL)
            }
            // supersonic retropropulsion: landing legs folded against the first stage
            if (i == 0 && has("EDL_SRP")) {
                val legs = mb().color(0.16f, 0.16f, 0.17f).material(0.45f, 0.6f)
                for (k in 0 until 4) {
                    val a = (PI / 4 + k * PI / 2).toFloat()
                    legs.beam(cos(a) * r * 1.02f, tankStart + 1.6f, sin(a) * r * 1.02f, cos(a) * r * 1.5f, 0.05f, sin(a) * r * 1.5f, 0.07f)
                    legs.transform(Mat.trs(FloatArray(16), cos(a) * r * 1.5f, 0.05f, sin(a) * r * 1.5f)); legs.sphere(0.14f, 8, 5); legs.transform(null)
                }
                add(0, legs, MatType.METAL)
            }
            var stageEnd = top
            // interstage (belongs to the lower stage, hides the next bell)
            if (i < nSt - 1) {
                val rn = spec.stages[i + 1].radius
                val isLen = ubl[i + 1] + 0.25f
                val isg = mb().color(0.10f, 0.10f, 0.11f).material(0.55f, 0.2f)
                isg.lathe(floatArrayOf(r * 0.985f, top, r, top + 0.05f, max(rn, r * 0.93f), top + isLen), segs)
                add(i, isg, MatType.BODY, floatArrayOf(0.22f, 24f, 0f, 0f))
                if (has("ST_PYRO")) {
                    val sep = mb().color(0.85f, 0.65f, 0.1f).material(0.5f, 0.6f)
                    sep.lathe(floatArrayOf(max(rn, r * 0.93f) * 1.006f, top + isLen - 0.12f, max(rn, r * 0.93f) * 1.012f, top + isLen - 0.06f, max(rn, r * 0.93f) * 1.006f, top + isLen), segs)
                    add(i, sep, MatType.METAL)
                }
                val ri = max(rn, r * 0.93f)
                // metamaterial frame: diagonal lattice struts over the interstage
                if (has("S_METAMAT")) {
                    val lt = mb().color(0.62f, 0.64f, 0.68f).material(0.3f, 0.9f)
                    for (k in 0 until 12) {
                        val a0 = k * PI.toFloat() / 6f; val a1 = a0 + PI.toFloat() / 6f
                        val r0 = r * 1.012f; val r1 = ri * 1.012f
                        lt.beam(cos(a0) * r0, top + 0.05f, sin(a0) * r0, cos(a1) * r1, top + isLen - 0.05f, sin(a1) * r1, 0.035f)
                        lt.beam(cos(a1) * r0, top + 0.05f, sin(a1) * r0, cos(a0) * r1, top + isLen - 0.05f, sin(a0) * r1, 0.035f)
                    }
                    add(i, lt, MatType.METAL)
                }
                // zero-gap staging: blue magnetic latch ring; laser-cut separation: a glowing seam
                if (has("ST_ZERO")) {
                    val zl = mb().color(0.25f, 0.45f, 0.95f).material(0.3f, 0.6f)
                    zl.lathe(floatArrayOf(ri * 1.004f, top + isLen - 0.3f, ri * 1.02f, top + isLen - 0.26f, ri * 1.02f, top + isLen - 0.18f, ri * 1.004f, top + isLen - 0.14f), segs)
                    add(i, zl, MatType.METAL)
                }
                if (has("ST_LASERSEP")) {
                    val ls = mb().color(1f, 0.45f, 0.2f).material(0.3f, 0f)
                    ls.lathe(floatArrayOf(ri * 1.003f, top + isLen - 0.035f, ri * 1.012f, top + isLen - 0.015f, ri * 1.003f, top + isLen + 0.005f), segs)
                    add(i, ls, MatType.PLAIN, emissive = GLOW_ORANGE)
                }
                if (has("ST_HOT")) {   // hot staging: vented ring so the next engine can light before separation
                    val vent = mb().color(0.04f, 0.04f, 0.045f).material(0.7f, 0.3f)
                    val vr = (r + max(rn, r * 0.93f)) * 0.5f
                    for (k in 0 until 12) {
                        vent.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = k * 30f))
                        vent.box(vr * 1.01f, top + isLen * 0.55f, 0f, 0.05f, isLen * 0.32f, vr * 0.22f)
                        vent.transform(null)
                    }
                    add(i, vent, MatType.METAL)
                }
                stageEnd = top + isLen
                base = stageBase[i] + top + 0.15f
            }
            stageTop[i] = stageBase[i] + stageEnd

            // fins + grid fins on the first stage
            if (i == 0) buildFins(r, tankStart)
            if (i == 0 && has("A_GRID")) buildGridFins(r, top)
        }
        // payload + nose on top of the last stage
        val last = nSt - 1
        val rTop = spec.stages[last].radius
        totalLength = buildNose(stageTop[last], rTop)
        buildBoosters()
    }

    /** Saturn-program tank and hull hardware on one stage (bands, ribs, blankets, armour, docking rings, thrusters). */
    private fun buildSaturnStageDecor(i: Int, r: Float, tankStart: Float, top: Float, foilTop: Boolean, wOut: Float) {
        val L = top - tankStart
        if (L < 1.2f) return
        fun at(f: Float) = tankStart + L * f
        fun band(b: MeshBuilder, y0: Float, y1: Float, rr: Float) =
            b.lathe(floatArrayOf(r * 1.002f, y0, r * rr, y0 + 0.02f, r * rr, y1 - 0.02f, r * 1.002f, y1), segs)
        val upper = i > 0
        // self-healing skin: glossy amber bands
        if (has("S_SELFHEAL")) {
            val am = mb().color(0.95f, 0.58f, 0.12f).material(0.1f, 0f)
            if (upper) band(am, tankStart + 0.06f, tankStart + 0.18f, 1.016f) else { band(am, at(0.3f) - 0.05f, at(0.3f) + 0.05f, 1.016f); band(am, at(0.52f) - 0.05f, at(0.52f) + 0.05f, 1.016f) }
            add(i, am, MatType.PLAIN, rm = 1f)
        }
        // spider-silk composite: a pearly wrap
        if (has("S_SPIDER") && !upper) {
            val sp = mb().color(0.93f, 0.91f, 0.97f).material(0.22f, 0.3f)
            band(sp, at(0.42f), at(0.46f), 1.015f)
            add(i, sp, MatType.PLAIN, rm = 1f)
        }
        // wrap-around tanks: ribs along the stage
        if (has("S_ANNULAR") && !(upper && has("S_WHIPPLE"))) {
            val rb = mb().color(0.55f, 0.57f, 0.6f).material(0.35f, 0.8f)
            var y = tankStart + 1.0f
            while (y < top - 1.0f) { band(rb, y, y + 0.07f, 1.022f); y += 1.3f }
            add(i, rb, MatType.METAL)
        }
        if (!upper) {
            // aerogel blankets, metallic-hydrogen bands, tanker docking ring
            if (has("S_AEROGEL")) { val ag = mb().color(0.72f, 0.84f, 0.97f).material(0.95f, 0f); band(ag, at(0.72f), at(0.82f), 1.012f); add(i, ag, MatType.PLAIN) }
            if (has("S_METAH")) {
                val mh = mb().color(0.6f, 0.72f, 0.92f).material(0.18f, 1f)
                band(mh, at(0.85f) - 0.04f, at(0.85f) + 0.04f, 1.018f); band(mh, at(0.88f) - 0.04f, at(0.88f) + 0.04f, 1.018f)
                add(i, mh, MatType.METAL)
            }
            if (has("S_TANKERS")) {
                val dk = mb().color(0.15f, 0.15f, 0.16f).material(0.45f, 0.7f)
                band(dk, at(0.93f) - 0.06f, at(0.93f) + 0.06f, 1.02f)
                for (k in 0 until 2) { val a = (k * PI).toFloat() + 0.6f; dk.box(cos(a) * r * 1.03f, at(0.93f), sin(a) * r * 1.03f, 0.28f, 0.28f, 0.28f) }
                add(i, dk, MatType.METAL)
            }
            // Hall-effect thrusters near the top; the micro-thruster skin's dotted band near the bottom
            if (has("A_HALL")) {
                val ht = mb().color(0.6f, 0.4f, 1f).material(0.2f, 0f)
                for (k in 0 until 4) {
                    val a = (k * PI / 2).toFloat()
                    ht.transform(Mat.trs(FloatArray(16), cos(a) * r * 1.02f, at(0.97f), sin(a) * r * 1.02f, 0.5f, 1f, 1f, ry = -Math.toDegrees(a.toDouble()).toFloat()))
                    ht.sphere(0.13f, 10, 6); ht.transform(null)
                }
                add(i, ht, MatType.PLAIN, emissive = GLOW_VIOLET)
            }
            if (has("A_MICRO")) {
                val mb0 = mb().color(0.12f, 0.12f, 0.13f).material(0.5f, 0.5f); band(mb0, at(0.1f), at(0.13f), 1.012f); add(i, mb0, MatType.METAL)
                val dots = mb().color(0.4f, 0.95f, 1f).material(0.2f, 0f)
                for (k in 0 until 24) { val a = k * PI.toFloat() / 12f; dots.box(cos(a) * r * 1.014f, at(0.115f), sin(a) * r * 1.014f, 0.05f, 0.05f, 0.05f) }
                add(i, dots, MatType.PLAIN, emissive = GLOW_CYAN)
            }
        } else if (!foilTop) {
            // boron-nitride armour plates and layered ice armour outside the Whipple shield
            if (has("S_BNNT") && L > 2f) {
                val pl = mb().color(0.88f, 0.86f, 0.8f).material(0.6f, 0.1f)
                for (k in 0 until 8) {
                    pl.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = 22.5f + k * 45f))
                    pl.box(r * (wOut + 0.012f), at(0.5f), 0f, 0.05f, L * 0.4f, r * 0.62f)
                    pl.transform(null)
                }
                add(i, pl, MatType.BODY, floatArrayOf(0.4f, 0f, 0f, 0f))
            }
            if (has("S_ICEARMOR")) {
                val ia = mb().color(0.95f, 0.96f, 0.97f).material(0.7f, 0f)
                band(ia, tankStart + 0.3f, tankStart + 0.5f, wOut + 0.035f); band(ia, top - 0.55f, top - 0.35f, wOut + 0.035f)
                add(i, ia, MatType.PLAIN)
            }
        }
        // ultra-light domes: white dome caps at the top of every tank
        if (has("S_DOMES")) { val dm = mb().color(0.95f, 0.95f, 0.95f).material(0.4f, 0f); band(dm, top - 0.28f, top - 0.08f, 1.015f); add(i, dm, MatType.PLAIN) }
        // nanocomposite monocoque: glowing cyan seams
        if (has("S_NANOMONO")) {
            val sm = mb().color(0.3f, 0.95f, 1f).material(0.3f, 0f)
            if (upper) { band(sm, tankStart + 0.02f, tankStart + 0.05f, 1.009f); band(sm, top - 0.07f, top - 0.04f, 1.009f) }
            else for (f in listOf(0.2f, 0.4f, 0.6f, 0.8f)) band(sm, at(f) - 0.02f, at(f) + 0.02f, 1.009f)
            add(i, sm, MatType.PLAIN, emissive = GLOW_CYAN)
        }
        // drop tanks: two slim jettisonable tanks on the second stage
        if (i == 1 && has("ST_DROP")) {
            val dt = mb().color(bodyCol[0], bodyCol[1], bodyCol[2]).material(bodyRough, bodyMetal)
            val st = mb().color(0.3f, 0.3f, 0.32f).material(0.5f, 0.7f)
            val tr = 0.24f; val y0 = tankStart + 0.3f; val y1 = tankStart + L * 0.8f
            val dist = r + tr + 0.08f
            // at 45° and 225°: clear of the bimodal-nuclear radiator plates (0°/180°), the decal and the armour plates
            for (k in 0..1) {
                val a = Math.toRadians(45.0 + 180.0 * k)
                val cx = cos(a).toFloat(); val cz = sin(a).toFloat()
                dt.transform(Mat.trs(FloatArray(16), cx * dist, 0f, cz * dist))
                dt.lathe(floatArrayOf(tr * 0.7f, y0 - 0.2f, tr, y0, tr, y1), segs)
                val tip = noseProfile(tr, tr * 2.4f, 1); for (j in 0 until tip.size / 2) tip[2 * j + 1] += y1
                dt.lathe(tip, segs); dt.transform(null)
                for (yy in listOf(y0 + 0.3f, y1 - 0.3f)) st.beam(cx * r * 0.99f, yy, cz * r * 0.99f, cx * (dist - tr * 0.9f), yy, cz * (dist - tr * 0.9f), 0.05f)
            }
            add(i, dt, if (bodyMat == MatType.NANO) MatType.NANO else MatType.BODY, floatArrayOf(1.2f, 0f, 0f, 0f))
            add(i, st, MatType.METAL)
        }
    }

    private fun buildFins(r: Float, tankStart: Float) {
        val lv = spec.level(Stat.FINS)
        if (lv == 0) return
        val boosters = spec.boosterCount
        val count = if (lv >= 2) 4 else 3
        val start = when { boosters == 4 -> 0f; boosters == 2 || count == 4 -> 45f; else -> 90f }
        val poly = when {
            has("A_STRAKE") -> floatArrayOf(0f, 0f, 1.45f * r, 0.4f * r, 1.45f * r, 1.25f * r, 0f, 3.0f * r)
            has("A_ACTIVE") -> floatArrayOf(0f, 0f, 1.25f * r, 0.35f * r, 1.25f * r, 1.0f * r, 0f, 2.1f * r)
            has("A_SWEPT") -> floatArrayOf(0f, 0f, 1.2f * r, -0.25f * r, 1.2f * r, 0.35f * r, 0f, 2.3f * r)
            else -> floatArrayOf(0f, 0f, 0.95f * r, 0.15f * r, 0.95f * r, 0.95f * r, 0f, 1.65f * r)
        }
        val tiles = has("TH_TILE")
        val b = mb().color(accent[0], accent[1], accent[2]).material(0.45f, if (bodyMat == MatType.STEEL) 0.9f else 0.2f)
        for (k in 0 until count) {
            val ang = start + k * 360f / count
            val m = Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = -ang)
            Mat.mul(m, m, Mat.trs(FloatArray(16), r * 0.98f, tankStart * 0.2f + 0.1f, 0f))
            b.transform(m)
            b.extrude(poly, 0.07f * (1f + 0.3f * lv / 4f))
            if (has("A_ACTIVE")) b.box(0.15f * r, 1.0f * r, 0f, 0.3f * r, 0.9f * r, 0.18f)
            b.transform(null)
        }
        add(0, b, if (tiles) MatType.TILES else MatType.PLAIN)
    }

    private fun buildGridFins(r: Float, top: Float) {
        val b = mb().color(0.18f, 0.18f, 0.19f).material(0.4f, 0.9f)
        val vg = has("A_VGRID")   // variable-geometry grid fins: bigger, finer lattice
        val w = r * (if (vg) 1.2f else 0.9f); val h = r * (if (vg) 0.95f else 0.7f)
        for (k in 0 until 4) {
            val ang = 45f + k * 90f
            b.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = -ang))
            val x0 = r * 1.02f; val y0 = top - 0.9f - h
            // frame
            b.beam(x0, y0, -w / 2, x0 + h, y0, -w / 2, 0.04f); b.beam(x0, y0, w / 2, x0 + h, y0, w / 2, 0.04f)
            b.beam(x0 + h, y0, -w / 2, x0 + h, y0, w / 2, 0.04f); b.beam(x0, y0, -w / 2, x0, y0, w / 2, 0.05f)
            // lattice
            val nl = if (vg) 7 else 5
            for (j in 1 until nl) { val t = j / nl.toFloat(); b.beam(x0 + h * t, y0, -w / 2, x0 + h * t, y0, w / 2, 0.015f); b.beam(x0, y0, -w / 2 + w * t, x0 + h, y0, -w / 2 + w * t, 0.015f) }
            b.transform(null)
        }
        add(0, b, MatType.METAL)
    }

    private fun noseProfile(R: Float, L: Float, kind: Int): FloatArray {
        val n = 14
        val p = FloatArray((n + 1) * 2)
        for (k in 0..n) {
            val s = k / n.toFloat()         // 0 = base, 1 = tip
            val x = s * L
            val r = when (kind) {
                0 -> R * (1 - s) * 0.92f + R * 0.08f * (1 - s * s)   // blunt cone
                1 -> { val rho = (R * R + L * L) / (2 * R); sqrt(max(0f, rho * rho - x * x)) - (rho - R) }   // tangent ogive
                else -> { val th = acos(1 - 2 * (L - x) / L); (R / sqrt(PI.toFloat())) * sqrt(max(0f, th - sin(2 * th) / 2)) } // Von Kármán
            }
            p[2 * k] = max(r, 0f); p[2 * k + 1] = x
        }
        p[2 * n] = 0f
        return p
    }

    /** RCS thruster quads: four little blocks with nozzles pointing four ways. */
    private fun buildRcs(group: Int, r: Float, y: Float) {
        val q = mb().color(0.78f, 0.78f, 0.8f).material(0.4f, 0.7f)
        for (k in 0 until 4) {
            q.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = 45f + k * 90f))
            q.box(r * 1.04f, y, 0f, 0.12f, 0.2f, 0.2f)
            q.beam(r * 1.08f, y, 0f, r * 1.08f, y + 0.16f, 0f, 0.05f); q.beam(r * 1.08f, y, 0f, r * 1.08f, y - 0.16f, 0f, 0.05f)
            q.beam(r * 1.08f, y, 0f, r * 1.08f, y, 0.16f, 0.05f); q.beam(r * 1.08f, y, 0f, r * 1.08f, y, -0.16f, 0.05f)
            q.transform(null)
        }
        add(group, q, MatType.METAL)
    }

    /** Payload bay (radiation vault, Europa lander + sky-crane), solar wings and RTGs. Returns the new top. */
    private fun buildPayloadBay(y0: Float, r: Float): Float {
        var y = y0
        // aerocapture heat shield: a wide grey brim; ring-plane dust shield: a white collar above it
        if (spec.level(Stat.AEROCAP) >= 1) {
            val hs = mb().color(0.42f, 0.42f, 0.4f).material(0.9f, 0f)
            hs.lathe(floatArrayOf(r * 0.99f, y + 0.02f, r * 1.42f, y + 0.12f, r * 1.42f, y + 0.18f, r * 0.99f, y + 0.32f), segs)
            add(PAYLOAD, hs, MatType.PLAIN)
        }
        if (spec.level(Stat.RINGS) >= 1) {
            val dc = mb().color(0.94f, 0.94f, 0.92f).material(0.8f, 0f)
            dc.lathe(floatArrayOf(r * 0.99f, y + 0.38f, r * 1.3f, y + 0.58f, r * 1.3f, y + 0.64f, r * 0.99f, y + 0.84f), segs)
            add(PAYLOAD, dc, MatType.BODY, floatArrayOf(0.1f, 24f, 0f, 0f))
        }
        val rad = spec.level(Stat.RADIATION)
        if (rad >= 1) {   // gold-foil radiation vault section with the titanium vault box
            val hb = 0.9f
            val foil = mb().color(0.86f, 0.63f, 0.2f).material(0.32f, 1f)
            foil.lathe(floatArrayOf(r * 0.985f, y, r, y + 0.04f, r, y + hb - 0.04f, r * 0.985f, y + hb), segs)
            add(PAYLOAD, foil, MatType.FOIL)
            val vault = mb().color(0.56f, 0.57f, 0.6f).material(0.35f, 0.9f)
            vault.box(0f, y + hb / 2, r * 0.97f, r * 0.8f, hb * 0.8f, r * 0.3f)
            if (rad >= 2) {   // radiation-hardened electronics: dark tungsten shielding plates
                vault.color(0.1f, 0.1f, 0.11f).material(0.55f, 0.7f)
                for (k in 0 until 4) {
                    vault.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = 45f + k * 90f))
                    vault.box(r * 1.012f, y + hb / 2, 0f, 0.04f, hb * 0.7f, r * 0.55f)
                    vault.transform(null)
                }
            }
            add(PAYLOAD, vault, MatType.METAL)
            y += hb
        }
        val lander = spec.level(Stat.LANDER)
        if (lander >= 1) {   // octagonal gold Europa lander with folded legs
            val h = 1.3f
            val body = mb().color(0.86f, 0.63f, 0.2f).material(0.32f, 1f)
            body.lathe(floatArrayOf(r * 0.94f, y, r * 0.96f, y + 0.05f, r * 0.96f, y + h - 0.05f, r * 0.94f, y + h), 8)
            add(PAYLOAD, body, MatType.FOIL)
            val legs = mb().color(0.72f, 0.72f, 0.74f).material(0.4f, 0.9f)
            for (k in 0 until 4) {
                val a = (PI / 4 + k * PI / 2).toFloat()
                legs.beam(cos(a) * r * 0.96f, y + h * 0.85f, sin(a) * r * 0.96f, cos(a) * r * 1.1f, y + 0.08f, sin(a) * r * 1.1f, 0.06f)
                legs.transform(Mat.trs(FloatArray(16), cos(a) * r * 1.1f, y + 0.04f, sin(a) * r * 1.1f)); legs.cylinder(0.14f, -0.03f, 0.03f, 10); legs.transform(null)
            }
            add(PAYLOAD, legs, MatType.METAL)
            y += h
            if (lander >= 2) {   // sky-crane: red frame with four down-firing thruster pods
                val hc = 0.7f
                val crane = mb().color(0.8f, 0.12f, 0.08f).material(0.45f, 0.5f)
                for (k in 0 until 4) {
                    val a = (k * PI / 2).toFloat()
                    val cx = cos(a) * r * 0.85f; val cz = sin(a) * r * 0.85f
                    crane.beam(cx, y, cz, cx * 0.6f, y + hc, cz * 0.6f, 0.06f)
                    crane.box(cx * 1.12f, y + hc * 0.35f, cz * 1.12f, 0.22f, 0.22f, 0.22f)
                }
                crane.lathe(floatArrayOf(r * 0.55f, y + hc - 0.06f, r * 0.62f, y + hc, r * 0.55f, y + hc + 0.06f), segs)
                add(PAYLOAD, crane, MatType.METAL)
                val pods = mb().color(0.22f, 0.21f, 0.2f).material(0.35f, 0.9f)
                for (k in 0 until 4) { val a = (k * PI / 2).toFloat(); bell(pods, cos(a) * r * 0.95f, sin(a) * r * 0.95f, y + hc * 0.35f - 0.32f, 0.07f, 0.2f, false) }
                add(PAYLOAD, pods, MatType.METAL)
                y += hc
            }
        }
        // Titan descent stage (orange frame, four thrusters) and the Dragonfly rotorcraft on top of it
        val titan = spec.level(Stat.TITAN)
        if (titan >= 4) {
            val hd = 0.5f
            val fr = mb().color(0.95f, 0.5f, 0.1f).material(0.45f, 0.4f)
            for (k in 0 until 4) {
                val a = (PI / 4 + k * PI / 2).toFloat()
                fr.beam(cos(a) * r * 0.9f, y, sin(a) * r * 0.9f, cos(a) * r * 0.55f, y + hd, sin(a) * r * 0.55f, 0.06f)
                fr.box(cos(a) * r * 0.98f, y + hd * 0.4f, sin(a) * r * 0.98f, 0.2f, 0.2f, 0.2f)
            }
            fr.lathe(floatArrayOf(r * 0.5f, y + hd - 0.05f, r * 0.58f, y + hd, r * 0.5f, y + hd + 0.05f), segs)
            add(PAYLOAD, fr, MatType.METAL)
            val th = mb().color(0.22f, 0.21f, 0.2f).material(0.35f, 0.9f)
            for (k in 0 until 4) { val a = (PI / 4 + k * PI / 2).toFloat(); bell(th, cos(a) * r * 0.98f, sin(a) * r * 0.98f, y + hd * 0.4f - 0.3f, 0.07f, 0.2f, false) }
            add(PAYLOAD, th, MatType.METAL)
            y += hd
        }
        if (titan >= 3) {   // Dragonfly: a boxy rotorcraft with four arms and eight (stacked) rotors
            val hr = 0.9f
            val body = mb().color(0.93f, 0.93f, 0.9f).material(0.5f, 0f)
            body.box(0f, y + 0.35f, 0f, r * 1.0f, 0.5f, r * 0.8f)
            body.color(0.9f, 0.5f, 0.12f); body.box(0f, y + 0.62f, 0f, r * 0.5f, 0.06f, r * 0.5f)
            add(PAYLOAD, body, MatType.PLAIN)
            val rot = mb().color(0.2f, 0.2f, 0.22f).material(0.4f, 0.6f)
            for (k in 0 until 4) {
                val a = (PI / 4 + k * PI / 2).toFloat()
                val ax = cos(a) * r * 1.05f; val az = sin(a) * r * 1.05f
                rot.beam(cos(a) * r * 0.4f, y + 0.35f, sin(a) * r * 0.4f, ax, y + 0.35f, az, 0.045f)
                for (yy in listOf(y + 0.25f, y + 0.5f)) { rot.transform(Mat.trs(FloatArray(16), ax, yy, az)); rot.cylinder(r * 0.38f, -0.01f, 0.01f, 16); rot.transform(null) }
            }
            add(PAYLOAD, rot, MatType.METAL)
            y += hr
        }
        buildSaturnPayloadSide(y0, y, r)
        // Juno-class solar wings: three long panels, hinged just above the top stage (they hang folded on the pad)
        if (spec.level(Stat.POWER) >= 1) {
            val w = 2.6f * r + 0.6f; val d = 0.8f * r + 0.15f
            for (k in 0 until 3) {
                val a = (k * 2.0 * PI / 3.0).toFloat()
                val hingeR = r * 1.06f
                val local = Mat.trs(FloatArray(16), cos(a) * hingeR, y0 + 0.25f, -sin(a) * hingeR, ry = Math.toDegrees(a.toDouble()).toFloat())
                val wing = mb().color(0.7f, 0.7f, 0.72f).material(0.4f, 0.9f)
                wing.box(0.08f, 0f, 0f, 0.16f, 0.06f, 0.1f)                 // hinge yoke
                wing.color(0.05f, 0.09f, 0.28f).material(0.22f, 0.6f)
                wing.box(0.16f + w / 2, 0f, 0f, w, 0.035f, d)              // cells
                wing.color(0.7f, 0.7f, 0.72f).material(0.4f, 0.9f)
                for (j in 0..4) { val x = 0.16f + w * j / 4f; wing.beam(x, 0.02f, -d / 2, x, 0.02f, d / 2, 0.02f) }   // panel frames
                wing.beam(0.16f, 0.02f, d / 2, 0.16f + w, 0.02f, d / 2, 0.02f); wing.beam(0.16f, 0.02f, -d / 2, 0.16f + w, 0.02f, -d / 2, 0.02f)
                add(ARRAYS, wing, MatType.METAL, local = local, hinge = true)
            }
        }
        // radioisotope generators: two finned cylinders on booms
        if (spec.level(Stat.POWER) >= 2) {
            val rtg = mb().color(0.2f, 0.2f, 0.21f).material(0.5f, 0.8f)
            for (deg in if (spec.level(Stat.POWER) >= 3) listOf(60f, 150f, 210f, 300f) else listOf(60f, 180f)) {
                rtg.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = deg))
                val ry0 = y0 + 0.5f
                rtg.beam(r * 0.98f, ry0, 0f, r + 0.9f, ry0 - 0.35f, 0f, 0.05f)
                rtg.transform(Mat.mul(FloatArray(16), Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = deg), Mat.trs(FloatArray(16), r + 0.95f, ry0 - 0.35f, 0f)))
                rtg.cylinder(0.15f, -0.45f, 0.45f, 12); rtg.transform(null)
                for (f in 0 until 6) {
                    rtg.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = deg))
                    val fa = f * PI.toFloat() / 3f
                    rtg.beam(r + 0.95f + cos(fa) * 0.15f, ry0 - 0.78f, sin(fa) * 0.15f, r + 0.95f + cos(fa) * 0.27f, ry0 - 0.78f + 0.85f, sin(fa) * 0.27f, 0.02f)
                    rtg.transform(null)
                }
            }
            add(PAYLOAD, rtg, MatType.METAL)
        }
        return y
    }

    /** Local frame on the body surface at angle [deg] (degrees around the axis), height [y], pushed out to [rad]. */
    private fun side(deg: Float, y: Float, rad: Float, rz: Float = 0f): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        return Mat.trs(FloatArray(16), (cos(a) * rad).toFloat(), y, (sin(a) * rad).toFloat(), ry = -deg, rz = rz)
    }

    /**
     * Saturn-program hardware around the payload bay ([y0] = bay bottom, [yTop] = bay top): the Titan probe,
     * plume spectrometer, reactor boom, Cassini-style dish, ion pods, ballutes, dust-field emitters, and the
     * deployables that open in space (sail, electric-sail tethers, magnetic loop, lightsail).
     */
    private fun buildSaturnPayloadSide(y0: Float, yTop: Float, r: Float) {
        val titan = spec.level(Stat.TITAN)
        if (titan >= 1) {   // Huygens-style: the orange-tan heat shield faces out; the probe adds a gold back cover
            val rp = r * 0.6f
            val shield = mb().color(0.78f, 0.52f, 0.28f).material(0.85f, 0f)
            shield.transform(side(90f, y0 + 1.6f, r + 0.38f, rz = -90f))
            shield.lathe(floatArrayOf(rp, 0f, rp * 0.93f, 0.08f, rp * 0.5f, 0.22f, 0.001f, 0.3f), segs)
            shield.transform(null)
            add(PAYLOAD, shield, MatType.PLAIN)
            if (titan == 1) {   // adapter cone to the hull (the probe's back cover takes its place at TITAN 2)
                val ad = mb().color(0.55f, 0.56f, 0.6f).material(0.4f, 0.8f)
                ad.transform(side(90f, y0 + 1.6f, r + 0.38f, rz = -90f))
                ad.lathe(floatArrayOf(rp * 0.35f, -0.38f - 0.15f * r, rp * 0.5f, 0f), segs)
                ad.transform(null)
                add(PAYLOAD, ad, MatType.METAL)
            }
            if (titan >= 2) {
                val back = mb().color(0.86f, 0.63f, 0.2f).material(0.32f, 1f)
                back.transform(side(90f, y0 + 1.6f, r + 0.38f, rz = -90f))
                back.lathe(floatArrayOf(0.001f, -0.36f, rp * 0.55f, -0.3f, rp * 0.98f, -0.02f, rp, 0f), segs)
                back.transform(null)
                add(PAYLOAD, back, MatType.FOIL)
            }
        }
        if (spec.level(Stat.PLUME) >= 1) {   // gold instrument box with an intake funnel
            val pi = mb().color(0.86f, 0.63f, 0.2f).material(0.32f, 1f)
            pi.transform(side(270f, y0 + 1.6f, r + 0.2f)); pi.box(0f, 0f, 0f, 0.36f, 0.36f, 0.36f); pi.transform(null)
            add(PAYLOAD, pi, MatType.FOIL)
            val fn = mb().color(0.8f, 0.8f, 0.82f).material(0.25f, 1f)
            fn.transform(side(270f, y0 + 1.78f, r + 0.2f)); fn.lathe(floatArrayOf(0.06f, 0f, 0.08f, 0.1f, 0.2f, 0.32f), segs); fn.transform(null)
            add(PAYLOAD, fn, MatType.METAL)
        }
        val power = spec.level(Stat.POWER)
        if (power >= 4) {   // Kilopower reactor on a boom with a radiator cone (Megawatt: big radiator panels)
            val by = y0 + 2.4f
            val rk = mb().color(0.62f, 0.63f, 0.66f).material(0.35f, 0.9f)
            rk.transform(side(330f, 0f, 0f))
            rk.beam(r * 0.95f, by, 0f, r + 1.6f, by + 0.2f, 0f, 0.05f)
            rk.transform(Mat.mul(FloatArray(16), side(330f, 0f, 0f), Mat.trs(FloatArray(16), r + 1.7f, by + 0.2f, 0f, rz = -90f)))
            rk.cylinder(0.17f, -0.25f, 0.25f, 14)
            rk.lathe(floatArrayOf(0.17f, 0.25f, 0.55f, 1.15f), segs)
            rk.transform(null)
            add(PAYLOAD, rk, MatType.METAL)
            if (power >= 5) {
                val rp = mb().color(0.88f, 0.89f, 0.9f).material(0.4f, 0.3f)
                rp.transform(side(330f, 0f, 0f))
                for (sz in listOf(-1f, 1f)) rp.box(r + 0.9f, by + 0.1f, sz * 0.55f, 1.3f, 0.03f, 0.9f)
                rp.transform(null)
                add(PAYLOAD, rp, MatType.METAL)
            }
        }
        if (power >= 6) {   // compact fusion power: a glowing ring around the bay
            val fr = mb().color(1f, 0.55f, 0.6f).material(0.2f, 0f)
            torus(fr, r * 1.22f, y0 + 2.0f, 0.06f)
            add(PAYLOAD, fr, MatType.PLAIN, emissive = GLOW_PINK)
        }
        if (spec.level(Stat.SLING) >= 3) {   // big white Cassini-style high-gain dish
            val hg = mb().color(0.93f, 0.93f, 0.92f).material(0.45f, 0.1f)
            val rd = r * 1.15f
            hg.transform(side(150f, y0 + 3.0f, r + 0.35f, rz = -90f))
            hg.lathe(floatArrayOf(0.05f, 0f, rd * 0.6f, 0.08f, rd, 0.24f), segs)
            hg.lathe(floatArrayOf(rd, 0.24f, rd * 0.6f, 0.06f, 0.05f, -0.02f), segs)
            hg.cylinder(0.05f, 0f, rd * 0.55f, 8)
            hg.cylinder(0.05f, -0.35f - 0.3f * r, 0f, 8)   // boom into the bay
            hg.transform(null)
            add(PAYLOAD, hg, MatType.PLAIN)
        }
        if (spec.level(Stat.CRUISE) >= 2) {   // ion cruise engines: two pods on short booms, glowing grids underneath
            val pod = mb().color(0.55f, 0.56f, 0.6f).material(0.35f, 0.9f)
            val grid = mb().color(0.4f, 0.65f, 1f).material(0.2f, 0f)
            for (deg in listOf(30f, 210f)) {
                pod.transform(side(deg, 0f, 0f)); pod.beam(r * 0.95f, y0 + 0.8f, 0f, r + 0.75f, y0 + 0.8f, 0f, 0.05f); pod.transform(null)
                pod.transform(side(deg, y0 + 0.8f, r + 0.8f)); pod.cylinder(0.2f, -0.25f, 0.25f, 14); pod.transform(null)
                grid.transform(side(deg, y0 + 0.8f, r + 0.8f)); grid.disc(0.17f, -0.26f, 14, false); grid.transform(null)
            }
            add(PAYLOAD, pod, MatType.METAL); add(PAYLOAD, grid, MatType.PLAIN, emissive = GLOW_BLUE)
        }
        val aerocap = spec.level(Stat.AEROCAP)
        if (aerocap >= 2) {   // folded orange ballute ring(s)
            val bl = mb().color(0.95f, 0.45f, 0.1f).material(0.8f, 0f)
            torus(bl, r * 1.1f, y0 + 1.0f, 0.12f)
            if (aerocap >= 3) torus(bl, r * 1.1f, y0 + 1.3f, 0.1f)
            add(PAYLOAD, bl, MatType.PLAIN)
        }
        if (spec.level(Stat.RINGS) >= 3) {   // electrostatic dust field: four emitter spikes with violet tips
            val em = mb().color(0.75f, 0.76f, 0.8f).material(0.3f, 1f)
            val tips = mb().color(0.7f, 0.4f, 1f).material(0.2f, 0f)
            for (k in 0 until 4) {
                val a = (PI / 4 + k * PI / 2).toFloat()
                val ty = yTop - 0.2f
                em.beam(cos(a) * r * 0.98f, ty - 0.4f, sin(a) * r * 0.98f, cos(a) * (r + 0.55f), ty, sin(a) * (r + 0.55f), 0.03f)
                tips.transform(Mat.trs(FloatArray(16), cos(a) * (r + 0.57f), ty + 0.02f, sin(a) * (r + 0.57f))); tips.sphere(0.07f, 8, 5); tips.transform(null)
            }
            add(PAYLOAD, em, MatType.METAL); add(PAYLOAD, tips, MatType.PLAIN, emissive = GLOW_VIOLET)
        }
        // ---- deployables: tucked inside the body on the pad, they open out once in space
        val cruise = spec.level(Stat.CRUISE)
        if (cruise >= 1 && !has("P_LASERSAIL")) {   // square mirror solar sail on four booms
            val L = r * 4.5f + 2f; val rIn = r * 1.15f
            val sail = mb().color(0.86f, 0.87f, 0.92f).material(0.08f, 1f)
            val poly = floatArrayOf(rIn, -rIn, L, -L, L, L, rIn, rIn)
            for (k in 0 until 4) {
                sail.transform(Mat.trs(FloatArray(16), 0f, yTop - 0.15f, 0f, ry = 90f * k + 45f, rx = 90f)); sail.extrude(poly, 0.015f); sail.transform(null)
            }
            add(SPREAD, sail, MatType.METAL)
            val booms = mb().color(0.3f, 0.3f, 0.32f).material(0.4f, 0.8f)
            for (k in 0 until 4) { val a = (k * PI / 2).toFloat(); booms.beam(cos(a) * rIn, yTop - 0.13f, sin(a) * rIn, cos(a) * L * 1.414f, yTop - 0.13f, sin(a) * L * 1.414f, 0.04f) }
            add(SPREAD, booms, MatType.METAL)
        }
        if (cruise >= 3) {   // electric sail: long charged tethers with end masses
            val tz = mb().color(0.35f, 0.65f, 1f).material(0.3f, 0f)
            for (k in 0 until 8) {
                val a = k * PI.toFloat() / 4f + 0.2f
                tz.beam(cos(a) * r * 0.9f, yTop - 0.6f, sin(a) * r * 0.9f, cos(a) * 15f, yTop - 0.6f, sin(a) * 15f, 0.025f)
                tz.box(cos(a) * 15f, yTop - 0.6f, sin(a) * 15f, 0.12f, 0.12f, 0.12f)
            }
            add(SPREAD, tz, MatType.PLAIN, emissive = floatArrayOf(0.15f, 0.35f, 0.9f))
        }
        if (cruise >= 4) {   // magnetic sail: a huge superconducting loop
            val ml = mb().color(0.35f, 0.75f, 1f).material(0.2f, 0f)
            torus(ml, 10f, yTop + 0.05f, 0.07f, 8)
            for (k in 0 until 4) {   // spokes that hold the loop out
                val a = (PI / 4 + k * PI / 2).toFloat()
                ml.beam(cos(a) * r * 0.95f, yTop + 0.05f, sin(a) * r * 0.95f, cos(a) * 10f, yTop + 0.05f, sin(a) * 10f, 0.02f)
            }
            add(SPREAD, ml, MatType.PLAIN, emissive = GLOW_CYAN)
        }
        if (cruise >= 5) {   // laser-boosted lightsail: a round shining disc
            val ls = mb().color(0.96f, 0.9f, 0.78f).material(0.05f, 1f)
            ls.lathe(floatArrayOf(7.5f, yTop + 0.3f, r * 1.3f, yTop + 0.32f), segs)
            add(SPREAD, ls, MatType.METAL)
        }
    }

    /** Payload adaptor, avionics, nose/fairing, thermal protection, antennas. Returns the overall top. */
    private fun buildNose(y0: Float, r: Float): Float {
        val aero = spec.level(Stat.AERO)
        var y = buildPayloadBay(y0, r)
        // avionics ring
        if (spec.level(Stat.AVIONICS) >= 2) {
            val ring = mb().color(0.12f, 0.12f, 0.13f).material(0.5f, 0.5f)
            ring.lathe(floatArrayOf(r, y, r * 1.01f, y + 0.05f, r * 1.01f, y + 0.4f, r, y + 0.45f), segs)
            for (k in 0 until 6) {
                val a = k * PI.toFloat() / 3f
                ring.box(cos(a) * r * 1.03f, y + 0.22f, sin(a) * r * 1.03f, 0.18f, 0.2f, 0.18f)
            }
            add(PAYLOAD, ring, MatType.METAL)
            if (spec.level(Stat.AVIONICS) >= 5) {   // autonomous AI navigator: glowing blue sensor dome
                val dome = mb().color(0.2f, 0.45f, 0.9f).material(0.15f, 0f)
                val a = 4.2f
                dome.transform(Mat.trs(FloatArray(16), cos(a) * r * 1.0f, y + 0.22f, sin(a) * r * 1.0f)); dome.sphere(0.16f, 14, 8); dome.transform(null)
                add(PAYLOAD, dome, MatType.PLAIN, emissive = GLOW_BLUE)
            }
            if (has("A_ION")) {   // ion attitude thrusters: blue glowing discs around the avionics ring
                val ion = mb().color(0.3f, 0.55f, 1f).material(0.2f, 0f)
                for (k in 0 until 4) {
                    val a = (PI / 4 + k * PI / 2).toFloat()
                    ion.transform(Mat.trs(FloatArray(16), cos(a) * r * 1.03f, y + 0.22f, sin(a) * r * 1.03f, sx = 0.5f)); ion.sphere(0.11f, 10, 6); ion.transform(null)
                }
                add(PAYLOAD, ion, MatType.PLAIN, emissive = GLOW_BLUE)
            }
            y += 0.45f
            // 1.3: a second avionics ring for the Saturn program's sensors and thrusters
            val ring2 = listOf("A_CMG", "AV_SLING", "AV_OPNAV", "AV_CLOCK", "AV_QUANT", "AV_OBERTH", "A_PPT", "A_LASERABL").any { has(it) }
            if (ring2) {
                val r2 = mb().color(0.14f, 0.14f, 0.16f).material(0.45f, 0.6f)
                r2.lathe(floatArrayOf(r, y, r * 1.012f, y + 0.05f, r * 1.012f, y + 0.4f, r, y + 0.45f), segs)
                add(PAYLOAD, r2, MatType.METAL)
                val hw = mb().color(0.75f, 0.76f, 0.8f).material(0.3f, 0.9f)
                val dark = mb().color(0.03f, 0.03f, 0.04f).material(0.1f, 0.2f)
                val ym = y + 0.22f
                fun onRing(deg: Float, block: (MeshBuilder) -> Unit) { hw.transform(side(deg, ym, r * 1.01f)); block(hw); hw.transform(null) }
                if (has("A_CMG")) for (d in listOf(15f, 105f, 195f, 285f)) onRing(d) { it.transform(Mat.mul(FloatArray(16), side(d, ym, r * 1.08f), Mat.trs(FloatArray(16), 0f, 0f, 0f, rx = 90f))); it.cylinder(0.12f, -0.09f, 0.09f, 12) }
                // star trackers (gravity-assist navigation; precision targeting adds a second pair)
                val trackers = (if (has("AV_SLING")) listOf(75f, 255f) else emptyList()) + (if (has("AV_SLING2")) listOf(165f, 345f) else emptyList())
                for (d in trackers) {
                    onRing(d) { it.box(0.12f, 0f, 0f, 0.24f, 0.2f, 0.2f) }
                    dark.transform(side(d, ym, r * 1.01f + 0.25f, rz = -90f)); dark.disc(0.07f, 0f, 10, true); dark.transform(null)
                }
                if (has("AV_OPNAV")) for (d in listOf(40f, 220f, 310f)) {
                    hw.transform(side(d, ym, r * 1.01f, rz = -90f)); hw.cylinder(0.08f, 0f, 0.2f, 10); hw.transform(null)
                    dark.transform(side(d, ym, r * 1.01f + 0.205f, rz = -90f)); dark.disc(0.07f, 0f, 10, true); dark.transform(null)
                }
                if (has("AV_CLOCK")) onRing(130f) { it.box(0.1f, 0f, 0f, 0.2f, 0.26f, 0.3f) }
                add(PAYLOAD, hw, MatType.METAL); add(PAYLOAD, dark, MatType.PLAIN)
                fun glowDots(col: FloatArray, em: FloatArray, degs: List<Float>, rr: Float, size: Float, dy: Float = 0f) {
                    val g = mb().color(col[0], col[1], col[2]).material(0.2f, 0f)
                    for (d in degs) { g.transform(side(d, ym + dy, r * rr)); g.sphere(size, 10, 6); g.transform(null) }
                    add(PAYLOAD, g, MatType.PLAIN, emissive = em)
                }
                if (has("AV_CLOCK")) glowDots(floatArrayOf(0.3f, 1f, 0.4f), GLOW_GREEN, listOf(130f), 1.01f + 0.21f / r, 0.04f, 0.1f)
                if (has("AV_QUANT")) glowDots(floatArrayOf(0.7f, 0.4f, 1f), GLOW_VIOLET, listOf(240f), 1.06f, 0.13f)
                if (has("AV_OBERTH")) glowDots(floatArrayOf(1f, 0.6f, 0.2f), GLOW_ORANGE, (0 until 6).map { it * 60f + 30f }, 1.013f, 0.035f, 0.15f)
                if (has("A_PPT")) glowDots(floatArrayOf(0.7f, 0.4f, 1f), GLOW_VIOLET, listOf(0f, 90f, 180f, 270f), 1.02f, 0.06f, -0.15f)
                if (has("A_LASERABL")) {
                    val le = mb().color(0.7f, 0.7f, 0.74f).material(0.3f, 0.9f)
                    for (d in listOf(60f, 150f, 240f, 330f)) { le.transform(side(d, ym - 0.1f, r * 1.01f, rz = -90f)); le.cylinder(0.05f, 0f, 0.14f, 8); le.transform(null) }
                    add(PAYLOAD, le, MatType.METAL)
                    glowDots(floatArrayOf(1f, 0.2f, 0.2f), GLOW_RED, listOf(60f, 150f, 240f, 330f), 1.01f + 0.15f / r, 0.035f, -0.1f)
                }
                y += 0.45f
            }
        }
        if (has("A_RCS")) buildRcs(PAYLOAD, r, y + 0.15f)
        val fairing = aero >= 3
        val R = if (fairing) r * 1.16f else r
        val noseCol = if (bodyMat == MatType.CARBON) floatArrayOf(0.05f, 0.05f, 0.055f) else floatArrayOf(0.93f, 0.93f, 0.92f)
        val body = mb().color(noseCol[0], noseCol[1], noseCol[2]).material(0.4f, 0f)
        if (fairing) {
            body.lathe(floatArrayOf(r, y, R, y + 0.55f, R, y + 0.6f + 1.7f * r), segs)
            y += 0.6f + 1.7f * r
        }
        val L = R * when (aero) { 0 -> 1.6f; 1 -> 2.3f; 2 -> 2.7f; 3, 4 -> 2.5f; else -> 3.4f }
        val kind = when (aero) { 0 -> 0; 1 -> 1; else -> 2 }
        val prof = noseProfile(R, L, kind)
        // split the nose: lower part body colour, upper part thermal protection (if researched)
        val heat = spec.level(Stat.HEAT)
        val split = if (heat > 0) 0.45f else 1.01f
        // the two pieces share exactly one boundary ring (no overlapping surfaces -> no z-fighting)
        val lower = ArrayList<Float>(); val upper = ArrayList<Float>()
        var splitK = prof.size / 2 - 1
        for (k in 0 until prof.size / 2) if (prof[2 * k + 1] / L > split) { splitK = max(1, k - 1); break }
        for (k in 0 until prof.size / 2) {
            if (k <= splitK) { lower += prof[2 * k]; lower += prof[2 * k + 1] + y }
            if (k >= splitK && heat > 0) { upper += prof[2 * k]; upper += prof[2 * k + 1] + y }
        }
        body.lathe(lower.toFloatArray(), segs)
        // shark-skin riblets: fine grooves all over the nose
        val riblet = has("A_RIBLET")
        add(PAYLOAD, body, MatType.BODY, floatArrayOf(if (riblet) 0.1f else if (fairing) 50f else 0.8f, if (riblet) 64f else if (fairing) 2f else 0f, 0f, 0f))
        // boundary-layer suction: a perforated band at the base of the nose
        if (has("A_SUCTION")) {
            val sb = mb().color(0.3f, 0.31f, 0.33f).material(0.6f, 0.4f)
            sb.lathe(floatArrayOf(R * 1.002f, y + 0.05f, R * 1.01f, y + 0.08f, R * 1.01f, y + 0.32f, R * 1.002f, y + 0.35f), segs)
            add(PAYLOAD, sb, MatType.BODY, floatArrayOf(0.06f, 72f, 0f, 0f))
        }
        // morphing nose: segment rings along the nose
        if (has("A_MORPH")) {
            val mr = mb().color(0.18f, 0.18f, 0.2f).material(0.4f, 0.6f)
            for (f in listOf(0.18f, 0.34f, 0.5f, 0.66f)) {
                var kk = 0
                while (kk < prof.size / 2 - 1 && prof[2 * kk + 1] < f * L) kk++
                val rr = prof[2 * kk] * 1.012f + 0.004f
                mr.lathe(floatArrayOf(rr, y + f * L - 0.03f, rr + 0.012f, y + f * L, rr, y + f * L + 0.03f), segs)
            }
            add(PAYLOAD, mr, MatType.METAL)
        }
        // plasma-actuator skin: glowing electrode strips along the lower nose
        if (has("A_PLASMA")) {
            val el = mb().color(0.4f, 0.7f, 1f).material(0.2f, 0f)
            val k1 = (prof.size / 2 * 0.45f).toInt().coerceIn(1, prof.size / 2 - 1)
            for (k in 0 until 6) {
                val a = k * PI.toFloat() / 3f + 0.25f
                el.beam(cos(a) * prof[0] * 1.012f, y + prof[1] + 0.1f, sin(a) * prof[0] * 1.012f, cos(a) * prof[2 * k1] * 1.012f, y + prof[2 * k1 + 1], sin(a) * prof[2 * k1] * 1.012f, 0.02f)
            }
            add(PAYLOAD, el, MatType.PLAIN, emissive = GLOW_BLUE)
        }
        if (heat > 0 && upper.size >= 4) {
            val tp = mb()
            when {
                has("TH_UHTC") -> tp.color(0.13f, 0.13f, 0.14f).material(0.95f, 0f)   // hafnium-carbide ceramic
                has("TH_PICA") -> tp.color(0.42f, 0.42f, 0.40f).material(0.9f, 0f)
                has("TH_TILE") -> tp.color(0.05f, 0.05f, 0.05f).material(0.8f, 0f)
                has("TH_ABL") -> tp.color(0.14f, 0.08f, 0.05f).material(0.9f, 0f)
                else -> tp.color(0.6f, 0.44f, 0.28f).material(0.85f, 0f)
            }
            tp.lathe(upper.toFloatArray(), segs)
            add(PAYLOAD, tp, if (has("TH_TILE") && !has("TH_PICA")) MatType.TILES else MatType.PLAIN)
            val yS = upper[1]; val rS = upper[0]
            // active film cooling: a silver vent ring where the heat shield starts
            if (has("TH_ACTIVE")) {
                val vr = mb().color(0.8f, 0.81f, 0.84f).material(0.2f, 1f)
                vr.lathe(floatArrayOf(rS * 1.004f, yS - 0.06f, rS * 1.025f, yS - 0.03f, rS * 1.025f, yS + 0.03f, rS * 1.004f, yS + 0.06f), segs)
                add(PAYLOAD, vr, MatType.BODY, floatArrayOf(10f, 36f, 0f, 0f))
            }
            // magnetohydrodynamic shield: copper magnet rings in the nose; plasma sheath: a violet glow ring near the tip
            fun noseRing(frac: Float, b: MeshBuilder, grow: Float) {
                var kk = 0
                while (kk < prof.size / 2 - 1 && prof[2 * kk + 1] < frac * L) kk++
                val rr = prof[2 * kk] + grow
                b.lathe(floatArrayOf(rr, y + frac * L - 0.035f, rr + 0.02f, y + frac * L, rr, y + frac * L + 0.035f), segs)
            }
            if (has("TH_MHD")) { val cu = mb().color(0.85f, 0.45f, 0.22f).material(0.3f, 1f); noseRing(0.58f, cu, 0.006f); noseRing(0.7f, cu, 0.006f); add(PAYLOAD, cu, MatType.METAL) }
            if (has("TH_PLASMASHEATH")) { val pv = mb().color(0.75f, 0.45f, 1f).material(0.2f, 0f); noseRing(0.83f, pv, 0.008f); add(PAYLOAD, pv, MatType.PLAIN, emissive = GLOW_VIOLET) }
        }
        // supersonic parachute canister: orange band at the base of the payload section
        if (has("EDL_CHUTE")) {
            val ch = mb().color(0.95f, 0.45f, 0.08f).material(0.6f, 0f)
            ch.lathe(floatArrayOf(R * 1.005f, y + 0.15f, R * 1.03f, y + 0.2f, R * 1.03f, y + 0.55f, R * 1.005f, y + 0.6f), segs)
            add(PAYLOAD, ch, MatType.PLAIN)
        }
        var top = y + L
        // aerodisk spike: a needle with a small disc pushes the shock wave off the nose
        if (has("A_SPIKE")) {
            val sp = mb().color(0.75f, 0.76f, 0.78f).material(0.25f, 1f)
            sp.cylinder(0.035f, top - 0.2f, top + R * 1.1f, 10)
            sp.lathe(floatArrayOf(0.02f, top + R * 1.1f - 0.05f, R * 0.16f, top + R * 1.1f, 0.0f, top + R * 1.1f + 0.06f), 16)
            add(PAYLOAD, sp, MatType.METAL)
            // laser air-spike: a glowing laser tip on the spike
            if (has("A_AIRSPIKE")) {
                val lt = mb().color(1f, 0.85f, 0.8f).material(0.2f, 0f)
                lt.transform(Mat.trs(FloatArray(16), 0f, top + R * 1.1f + 0.08f, 0f)); lt.sphere(0.08f, 10, 6); lt.transform(null)
                add(PAYLOAD, lt, MatType.PLAIN, emissive = GLOW_WHITE)
            }
            top += R * 1.1f + 0.06f
        }
        // laser deep-space comms: a terminal with a green lens on the nose
        if (spec.level(Stat.AVIONICS) >= 6) {
            val lt = mb().color(0.75f, 0.76f, 0.78f).material(0.3f, 0.9f)
            lt.transform(Mat.trs(FloatArray(16), -R * 0.7f, y + L * 0.5f, 0f)); lt.cylinder(0.12f, -0.1f, 0.32f, 12); lt.transform(null)
            add(PAYLOAD, lt, MatType.METAL)
            val lens = mb().color(0.2f, 1f, 0.4f).material(0.1f, 0f)
            lens.transform(Mat.trs(FloatArray(16), -R * 0.7f, y + L * 0.5f, 0f)); lens.disc(0.09f, 0.33f, 12, true); lens.transform(null)
            add(PAYLOAD, lens, MatType.PLAIN, emissive = GLOW_GREEN)
        }
        // avionics: antennas / dish
        val av = spec.level(Stat.AVIONICS)
        if (av >= 1) {
            val ant = mb().color(0.7f, 0.7f, 0.72f).material(0.3f, 0.9f)
            for (s in listOf(-1f, 1f)) ant.beam(s * R * 0.95f, y + 0.2f, 0f, s * (R * 0.95f + 0.05f), y + 1.3f, 0f, 0.025f)
            if (av >= 3) {
                ant.transform(Mat.trs(FloatArray(16), 0f, y + L * 0.25f, R * 0.85f, rx = 70f))
                ant.lathe(floatArrayOf(0.02f, 0f, 0.32f, 0.08f, 0.34f, 0.1f), 16)
                ant.lathe(floatArrayOf(0.34f, 0.1f, 0.3f, 0.08f, 0.0f, -0.02f), 16)
                ant.transform(null)
                ant.transform(Mat.trs(FloatArray(16), R * 0.9f, y + 0.4f, -R * 0.3f)); ant.sphere(0.12f, 10, 6); ant.transform(null)
                ant.transform(Mat.trs(FloatArray(16), -R * 0.9f, y + 0.4f, -R * 0.3f)); ant.sphere(0.12f, 10, 6); ant.transform(null)
            }
            if (av >= 4) {   // deep-space navigation: second, larger high-gain dish on the opposite side
                ant.transform(Mat.trs(FloatArray(16), 0f, y + L * 0.18f, -R * 0.9f, rx = -70f))
                ant.lathe(floatArrayOf(0.02f, 0f, 0.42f, 0.1f, 0.45f, 0.13f), 16)
                ant.lathe(floatArrayOf(0.45f, 0.13f, 0.4f, 0.1f, 0.0f, -0.02f), 16)
                ant.transform(null)
            }
            add(PAYLOAD, ant, MatType.METAL)
        }
        return top
    }

    private fun buildBoosters() {
        val n = spec.boosterCount
        val bs = spec.booster ?: return
        val core = spec.stages[0].radius
        val rb = bs.radius
        val lb = bs.length
        val d = core + rb + 0.06f
        val angles = when (n) { 6 -> listOf(0f, 60f, 120f, 180f, 240f, 300f); 4 -> listOf(45f, 135f, 225f, 315f); else -> listOf(0f, 180f) }
        val heavy = spec.heavySideCores
        boosterNozzleR = rb * 0.62f
        for ((k, ang) in angles.withIndex()) {
            val a = Math.toRadians(ang.toDouble())
            val off = Mat.trs(FloatArray(16), (cos(a) * d).toFloat(), 0f, (sin(a) * d).toFloat())
            boosterOffsets += off
            val g = -(k + 1)
            val noz = if (heavy) mb().color(0.3f, 0.29f, 0.3f).material(0.28f, 1f) else mb().color(0.16f, 0.16f, 0.17f).material(0.7f, 0.2f)
            bell(noz, 0f, 0f, 0f, rb * 0.62f, rb * (if (heavy) 0.95f else 0.7f), !heavy)
            add(g, noz, MatType.ENGINE, floatArrayOf(rb * 0.5f, 0f, 0f, 0f), engine = true, local = off)
            // liquid side cores wear the core stage's skin; solid boosters stay white
            val body = if (heavy) mb().color(bodyCol[0], bodyCol[1], bodyCol[2]).material(bodyRough, bodyMetal) else mb().color(0.93f, 0.93f, 0.92f).material(0.45f, 0f)
            body.lathe(floatArrayOf(rb * 0.85f, rb * 0.5f, rb, rb * 0.75f, rb, lb), segs)
            val tip = noseProfile(rb, rb * 2.2f, 1)
            for (j in 0 until tip.size / 2) tip[2 * j + 1] += lb
            body.lathe(tip, segs)
            add(g, body, if (heavy) bodyMat else MatType.BODY, floatArrayOf(lb / 4f, if (heavy && bodyMat != MatType.STEEL) 4f else 0f, 0f, 0f), local = off)
            val st = mb().color(0.3f, 0.3f, 0.32f).material(0.5f, 0.7f)
            val inward = -(d - rb) // towards the core in the booster's local frame
            val dx = (cos(a) * inward).toFloat(); val dz = (sin(a) * inward).toFloat()
            for (hh in listOf(lb * 0.18f, lb * 0.85f)) st.beam(0f, hh, 0f, dx * 0.98f, hh, dz * 0.98f, 0.09f)
            add(g, st, MatType.METAL, local = off)
            // propellant crossfeed: orange feed lines into the core stage
            if (heavy && has("ST_CROSS")) {
                val cf = mb().color(0.9f, 0.5f, 0.15f).material(0.4f, 0.6f)
                for (hh in listOf(lb * 0.4f, lb * 0.6f)) cf.beam(0f, hh, 0f, dx * 0.98f, hh + 0.4f, dz * 0.98f, 0.12f)
                add(g, cf, MatType.METAL, local = off)
            }
        }
    }

    fun release() { for (p in parts) p.mesh.release() }
}

/** Static launch complex: pad, umbilical tower, lightning masts, tanks, floodlights, buildings. */
class LaunchComplex(rocketLength: Float, segs: Int) {
    val pad: Mesh
    val structures: Mesh
    val lamps: Mesh
    val beacons: Mesh
    val arms = ArrayList<Pair<Float, Mesh>>()   // height to mesh (pivot at tower face)
    val towerX = -6.5f
    val armPivotX = towerX + 1.5f
    val lampPositions = ArrayList<FloatArray>()

    init {
        val h = rocketLength + 8f
        // pad slab: octagonal mound, top at y = 0 (terrain sits lower, so nothing is coplanar)
        val p = MeshBuilder().color(0.55f, 0.55f, 0.53f).material(0.85f, 0f)
        p.lathe(floatArrayOf(36f, -1.75f, 25f, 0f), 8)
        p.disc(25f, 0f, 8, true)
        pad = p.build()

        val s = MeshBuilder()
        // launch mount clamps
        s.color(0.2f, 0.2f, 0.21f).material(0.5f, 0.8f)
        for (k in 0 until 4) { val a = (PI / 4 + k * PI / 2).toFloat(); s.box(cos(a) * 3.4f, 0.45f, sin(a) * 3.4f, 0.7f, 0.9f, 0.7f) }
        // umbilical tower lattice (red-orange steel)
        s.color(0.62f, 0.18f, 0.1f).material(0.6f, 0.4f)
        val hw = 1.5f
        val corners = listOf(-hw to -hw, hw to -hw, hw to hw, -hw to hw)
        for ((cx, cz) in corners) s.box(towerX + cx, h / 2, cz, 0.28f, h, 0.28f)
        var yy = 0f
        val step = 2.6f
        while (yy < h - 0.1f) {
            val y1 = min(h, yy + step)
            for (i in 0 until 4) {
                val (ax, az) = corners[i]; val (bx, bz) = corners[(i + 1) % 4]
                s.beam(towerX + ax, y1, az, towerX + bx, y1, bz, 0.16f)
                if (i % 2 == 0) s.beam(towerX + ax, yy, az, towerX + bx, y1, bz, 0.09f) else s.beam(towerX + bx, yy, bz, towerX + ax, y1, az, 0.09f)
            }
            yy = y1
        }
        // crane / top deck + lightning rod
        s.color(0.55f, 0.55f, 0.58f).material(0.5f, 0.8f)
        s.box(towerX, h + 0.2f, 0f, 3.6f, 0.3f, 3.6f)
        s.beam(towerX, h + 0.3f, 0f, towerX, h + 9f, 0f, 0.12f)
        s.beam(towerX - 1.2f, h + 2.5f, 0f, towerX + 4.5f, h + 2.5f, 0f, 0.3f)
        // lightning masts + catenary wires
        s.color(0.6f, 0.6f, 0.62f).material(0.45f, 0.8f)
        val mastH = h + 26f
        val masts = listOf(floatArrayOf(0f, -42f), floatArrayOf(37f, 22f), floatArrayOf(-37f, 22f))
        for (m in masts) { s.transform(Mat.trs(FloatArray(16), m[0], 0f, m[1])); s.lathe(floatArrayOf(0.7f, 0f, 0.25f, mastH), 10); s.transform(null) }
        s.color(0.2f, 0.2f, 0.2f).material(0.6f, 0.6f)
        for (i in masts.indices) {
            val a = masts[i]; val b = masts[(i + 1) % masts.size]
            var px = a[0]; var py = mastH; var pz = a[1]
            for (k in 1..8) {
                val t = k / 8f
                val x = a[0] + (b[0] - a[0]) * t; val z = a[1] + (b[1] - a[1]) * t
                val y = mastH - 14f * 4f * t * (1 - t)
                s.beam(px, py, pz, x, y, z, 0.05f); px = x; py = y; pz = z
            }
        }
        // sound-suppression water tower
        s.color(0.88f, 0.88f, 0.86f).material(0.4f, 0.3f)
        val wx = 58f; val wz = -50f
        for (k in 0 until 4) { val a = (k * PI / 2 + PI / 4).toFloat(); s.beam(wx + cos(a) * 4.5f, 0f, wz + sin(a) * 4.5f, wx + cos(a) * 1.5f, 24f, wz + sin(a) * 1.5f, 0.35f) }
        s.transform(Mat.trs(FloatArray(16), wx, 28f, wz)); s.sphere(6.5f, segs, segs / 2); s.transform(null)
        // propellant spheres
        for ((x, z, r) in listOf(Triple(-48f, 40f, 7f), Triple(50f, 42f, 6f))) {
            s.color(0.93f, 0.93f, 0.92f).material(0.35f, 0.2f)
            s.transform(Mat.trs(FloatArray(16), x, r + 1.5f, z)); s.sphere(r, segs, segs / 2); s.transform(null)
            s.color(0.5f, 0.5f, 0.52f).material(0.5f, 0.7f)
            for (k in 0 until 4) { val a = (k * PI / 2).toFloat(); s.beam(x + cos(a) * r * 0.7f, 0f, z + sin(a) * r * 0.7f, x + cos(a) * r * 0.7f, r + 1.5f, z + sin(a) * r * 0.7f, 0.4f) }
            s.beam(x, 0.6f, z, 0f, 0.6f, 4f, 0.5f)   // feed pipe to the pad
        }
        // floodlight towers
        for ((x, z) in listOf(-28f to -28f, 28f to -28f, -28f to 28f, 28f to 28f)) {
            s.color(0.45f, 0.45f, 0.47f).material(0.5f, 0.7f)
            s.beam(x, 0f, z, x, 19f, z, 0.35f)
            s.box(x, 19.6f, z, 2.6f, 1.6f, 0.6f)
            lampPositions += floatArrayOf(x, 19.6f, z)
        }
        // service buildings + bunker
        s.color(0.78f, 0.78f, 0.76f).material(0.7f, 0f)
        s.box(-62f, 4f, -20f, 14f, 8f, 10f)
        s.box(70f, 3f, 18f, 10f, 6f, 16f)
        s.color(0.5f, 0.52f, 0.5f).material(0.9f, 0f)
        s.box(-30f, 1.2f, 70f, 16f, 2.4f, 10f)
        // the big assembly building in the distance
        s.color(0.80f, 0.81f, 0.82f).material(0.6f, 0.1f)
        s.box(-3000f, 105f, -900f, 160f, 210f, 150f)
        s.color(0.25f, 0.26f, 0.3f).material(0.5f, 0.1f)
        s.box(-3000f, 80f, -824.5f, 40f, 160f, 1f)
        structures = s.build()

        val l = MeshBuilder().color(1f, 0.95f, 0.85f).material(0.3f, 0f)
        for (lp in lampPositions) l.box(lp[0] * 0.98f, lp[1], lp[2] * 0.98f, 2.2f, 1.2f, 0.25f)
        lamps = l.build()
        val bcn = MeshBuilder().color(1f, 0.1f, 0.05f).material(0.3f, 0f)
        bcn.transform(Mat.trs(FloatArray(16), towerX, h + 9.2f, 0f)); bcn.sphere(0.3f, 8, 5); bcn.transform(null)
        for (m in masts) { bcn.transform(Mat.trs(FloatArray(16), m[0], mastH + 0.3f, m[1])); bcn.sphere(0.35f, 8, 5); bcn.transform(null) }
        beacons = bcn.build()

        // swing arms (crew access + umbilicals)
        for (frac in listOf(0.5f, 0.8f, 0.97f)) {
            val a = MeshBuilder().color(0.75f, 0.75f, 0.78f).material(0.5f, 0.6f)
            val len = abs(armPivotX) - 1.0f
            a.box(len / 2, 0f, 0f, len, 0.25f, 1.2f)
            a.beam(0f, 1.1f, 0.6f, len, 1.1f, 0.6f, 0.06f); a.beam(0f, 1.1f, -0.6f, len, 1.1f, -0.6f, 0.06f)
            var x = 0f
            while (x <= len) { a.beam(x, 0f, 0.6f, x, 1.1f, 0.6f, 0.05f); a.beam(x, 0f, -0.6f, x, 1.1f, -0.6f, 0.05f); x += 0.8f }
            if (frac > 0.9f) a.box(len * 0.8f, 1.1f, 0f, len * 0.4f, 2.2f, 1.4f) // white room
            arms += (rocketLength * frac) to a.build()
        }
    }

    fun release() { pad.release(); structures.release(); lamps.release(); beacons.release(); for ((_, m) in arms) m.release() }
}

/** Procedural terrain around the pad: apron, roads, grass/forest, beach + ocean (east), hills and mountains (west). */
object TerrainGen {
    private fun hash(x: Int, y: Int): Float { var h = x * 374761393 + y * 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xffffff) / 16777215f }
    private fun noise(x: Float, y: Float): Float {
        val xi = kotlin.math.floor(x).toInt(); val yi = kotlin.math.floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val u = fx * fx * (3 - 2 * fx); val v = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi); val b = hash(xi + 1, yi); val c = hash(xi, yi + 1); val d = hash(xi + 1, yi + 1)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }
    fun fbm(x: Float, y: Float, o: Int): Float { var s = 0f; var a = 0.5f; var px = x; var py = y; repeat(o) { s += a * noise(px, py); px = px * 2.03f + 17f; py = py * 2.03f + 9f; a *= 0.5f }; return s }

    fun coastX(z: Float) = 2600f + 450f * (fbm(z * 0.0006f, 3.3f, 3) - 0.5f) * 2f

    fun height(x: Float, z: Float): Float {
        val r = sqrt(x * x + z * z)
        if (x > coastX(z) + 40f) return -2.2f
        val hills = (fbm(x * 0.0015f, z * 0.0015f, 4) - 0.45f) * 40f
        val mountain = if (x < -6000f) ((-x - 6000f) / 9000f).coerceIn(0f, 1f).pow(1.4f) * 900f * fbm(x * 0.0005f + 3f, z * 0.0005f, 5) else 0f
        var h = hills.coerceAtLeast(-1f) + mountain
        val beach = ((coastX(z) + 40f - x) / 300f).coerceIn(0f, 1f)
        h *= beach
        val flat = ((r - 70f) / 600f).coerceIn(0f, 1f)
        return -1.75f * (1 - flat) + h * flat
    }

    private fun roadDist(x: Float, z: Float): Float {
        // crawlerway from the pad to the assembly building at (-3000, -900)
        val ax = -20f; val az = 0f; val bx = -2920f; val bz = -840f
        val dx = bx - ax; val dz = bz - az
        val t = (((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz)).coerceIn(0f, 1f)
        val px = ax + dx * t - x; val pz = az + dz * t - z
        return sqrt(px * px + pz * pz)
    }

    fun build(): Mesh {
        val b = MeshBuilder()
        val radii = ArrayList<Float>()
        var r = 0f
        radii += 0f
        while (r < 23000f) { r += max(2.5f, r * 0.075f); radii += r }
        val segs = 112
        val rows = radii.size
        val heights = Array(rows) { FloatArray(segs + 1) }
        val xs = Array(rows) { FloatArray(segs + 1) }; val zs = Array(rows) { FloatArray(segs + 1) }
        for (i in 0 until rows) for (s in 0..segs) {
            val a = (2 * PI * s / segs).toFloat()
            val x = radii[i] * cos(a); val z = radii[i] * sin(a)
            xs[i][s] = x; zs[i][s] = z; heights[i][s] = height(x, z)
        }
        val base = b.vertexCount
        for (i in 0 until rows) for (s in 0..segs) {
            val x = xs[i][s]; val z = zs[i][s]; val y = heights[i][s]
            // normal from finite differences
            val e = max(1f, radii[i] * 0.02f)
            val hx = height(x + e, z) - height(x - e, z); val hz = height(x, z + e) - height(x, z - e)
            var nx = -hx; var ny = 2 * e; var nz = -hz; val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
            val rr = sqrt(x * x + z * z)
            val water = x > coastX(z) + 40f
            val beachD = coastX(z) + 40f - x
            when {
                water -> b.color(0.015f, 0.05f, 0.075f).material(0.05f, 0f)
                rr < 80f -> b.color(0.5f, 0.5f, 0.48f).material(0.85f, 0f)
                roadDist(x, z) < 14f -> b.color(0.46f, 0.43f, 0.38f).material(0.9f, 0f)
                beachD < 120f -> b.color(0.66f, 0.6f, 0.45f).material(0.9f, 0f)
                y > 520f -> b.color(0.85f, 0.86f, 0.9f).material(0.6f, 0f)
                y > 180f -> b.color(0.30f, 0.28f, 0.26f).material(0.85f, 0f)
                fbm(x * 0.004f, z * 0.004f, 3) > 0.55f -> b.color(0.06f, 0.11f, 0.05f).material(0.9f, 0f)
                else -> { val v = fbm(x * 0.01f + 5f, z * 0.01f, 2); b.color(0.16f + 0.08f * v, 0.24f + 0.06f * v, 0.09f).material(0.9f, 0f) }
            }
            b.vertex(x, y, z, nx, ny, nz)
        }
        for (i in 0 until rows - 1) for (s in 0 until segs) {
            val a = base + i * (segs + 1) + s; val c = a + segs + 1
            b.quad(a, c, c + 1, a + 1)
        }
        return b.build()
    }
}

/** Meshes for the hazards (in their own local frames). */
class EntityMeshes(segs: Int) {
    val birdBody: Mesh; val birdWing: Mesh
    val plane: Mesh
    val balloon: Mesh
    val satellite: Mesh; val panel: Mesh
    val debris: Mesh
    val rock: Mesh; val rock2: Mesh

    init {
        birdBody = MeshBuilder().color(0.16f, 0.15f, 0.14f).material(0.8f, 0f).also {
            it.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, 0.55f, 0.24f, 0.24f)); it.sphere(1f, 12, 8)
            it.transform(Mat.trs(FloatArray(16), 0.5f, 0.08f, 0f, 0.18f, 0.16f, 0.16f)); it.sphere(1f, 10, 6); it.transform(null)
            it.color(0.9f, 0.6f, 0.1f); it.transform(Mat.trs(FloatArray(16), 0.68f, 0.06f, 0f, rz = -90f)); it.lathe(floatArrayOf(0.05f, 0f, 0f, 0.14f), 6); it.transform(null)
        }.build()
        birdWing = MeshBuilder().color(0.2f, 0.18f, 0.16f).material(0.85f, 0f).also { it.extrude(floatArrayOf(-0.2f, 0f, 0.25f, 0f, 0.05f, 1.1f, -0.35f, 0.9f), 0.03f) }.build()

        plane = MeshBuilder().also { b ->
            b.color(0.95f, 0.95f, 0.97f).material(0.3f, 0.3f)
            b.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, rz = -90f))
            b.lathe(floatArrayOf(0.0f, -5.2f, 0.45f, -4.8f, 0.8f, -3.5f, 0.85f, 3.0f, 0.55f, 4.6f, 0.0f, 5.4f), segs)
            b.transform(null)
            b.color(0.85f, 0.86f, 0.9f).material(0.35f, 0.4f)
            b.transform(Mat.trs(FloatArray(16), 0.4f, -0.25f, 0f, rx = 90f)); b.extrude(floatArrayOf(-1.4f, -6f, 0.9f, -6f, 1.8f, 6f, -1.0f, 6f).let { p -> floatArrayOf(p[0], 0f, p[2], 0f, p[2] - 1.6f, 6f, p[0] - 1.9f, 6f) }, 0.18f); b.transform(null)
            b.transform(Mat.trs(FloatArray(16), 0.4f, -0.25f, 0f, rx = -90f)); b.extrude(floatArrayOf(-1.4f, 0f, 0.9f, 0f, -0.7f, 6f, -3.3f, 6f), 0.18f); b.transform(null)
            b.color(0.15f, 0.3f, 0.75f).material(0.35f, 0.3f)
            b.extrude(floatArrayOf(-5.0f, 0.5f, -3.6f, 0.5f, -4.4f, 2.6f, -5.4f, 2.6f), 0.15f)
            b.color(0.6f, 0.6f, 0.65f).material(0.3f, 0.8f)
            for (zz in listOf(2.2f, -2.2f)) { b.transform(Mat.trs(FloatArray(16), 0.6f, -0.9f, zz, rz = -90f)); b.lathe(floatArrayOf(0.35f, -1f, 0.42f, -0.8f, 0.42f, 0.6f, 0.3f, 0.9f), 16); b.transform(null) }
        }.build()

        balloon = MeshBuilder().also { b ->
            b.color(0.95f, 0.95f, 0.92f).material(0.5f, 0f)
            b.lathe(floatArrayOf(0.15f, -1.4f, 1.0f, -0.6f, 1.8f, 0.6f, 2.0f, 1.6f, 1.6f, 2.8f, 0.8f, 3.4f, 0f, 3.6f), segs)
            b.color(0.95f, 0.5f, 0.1f).material(0.7f, 0f)
            b.box(0f, -3.4f, 0f, 0.7f, 0.6f, 0.7f)
            b.color(0.6f, 0.6f, 0.6f).material(0.8f, 0f)
            for (k in 0 until 4) { val a = (k * PI / 2).toFloat(); b.beam(cos(a) * 0.3f, -3.1f, sin(a) * 0.3f, cos(a) * 0.15f, -1.4f, sin(a) * 0.15f, 0.02f) }
        }.build()

        satellite = MeshBuilder().also { b ->
            b.color(0.85f, 0.65f, 0.22f).material(0.25f, 1f)
            b.box(0f, 0f, 0f, 1.5f, 1.5f, 1.9f)
            b.color(0.75f, 0.75f, 0.78f).material(0.3f, 0.9f)
            b.beam(-2.5f, 0f, 0f, 2.5f, 0f, 0f, 0.08f)
            b.transform(Mat.trs(FloatArray(16), 0f, 0.8f, 0f)); b.lathe(floatArrayOf(0.02f, 0f, 0.6f, 0.25f, 0.62f, 0.3f), 16); b.transform(null)
        }.build()
        panel = MeshBuilder().color(0.06f, 0.10f, 0.28f).material(0.2f, 0.6f).also { b ->
            b.box(0f, 0f, 0f, 3.2f, 0.05f, 1.4f)
            b.color(0.6f, 0.6f, 0.65f)
            for (k in 0..4) b.box(-1.6f + k * 0.8f, 0.03f, 0f, 0.03f, 0.02f, 1.4f)
        }.build()
        debris = MeshBuilder().color(0.55f, 0.56f, 0.6f).material(0.45f, 0.8f).also { b ->
            b.box(0f, 0f, 0f, 2.2f, 0.5f, 1.1f)
            b.transform(Mat.trs(FloatArray(16), 0.7f, 0.4f, 0f, rz = 30f)); b.box(0f, 0f, 0f, 0.6f, 0.9f, 0.3f); b.transform(null)
            b.color(0.75f, 0.6f, 0.3f); b.transform(Mat.trs(FloatArray(16), -0.6f, -0.1f, 0f, rz = 70f)); b.cylinder(0.3f, -0.45f, 0.45f, 10); b.transform(null)
        }.build()
        val bump1 = { th: Float, ph: Float -> 1f + 0.18f * TerrainGen.fbm(th * 2.1f + ph, ph * 1.7f, 3) - 0.09f }
        val bump2 = { th: Float, ph: Float -> 1f + 0.22f * TerrainGen.fbm(th * 1.6f + 5f, ph * 2.2f + 1f, 3) - 0.11f }
        rock = MeshBuilder().color(0.34f, 0.29f, 0.25f).material(0.9f, 0f).also { it.sphere(1f, 18, 12, bump1) }.build()
        rock2 = MeshBuilder().color(0.30f, 0.27f, 0.24f).material(0.9f, 0f).also { it.sphere(1f, 18, 12, bump2) }.build()
    }

    fun release() { for (m in listOf(birdBody, birdWing, plane, balloon, satellite, panel, debris, rock, rock2)) m.release() }
}

/** Unit plume shells: y from 0 (nozzle) to -1 (tip). */
object PlumeGen {
    fun core(segs: Int): Mesh {
        val p = ArrayList<Float>()
        for (k in 16 downTo 0) { val t = k / 16f; p += 1.0f - 0.85f * t.pow(0.8f); p += -t }
        return MeshBuilder().lathe(p.toFloatArray(), segs).build()
    }
    fun outer(segs: Int): Mesh {
        val p = ArrayList<Float>()
        for (k in 16 downTo 0) { val t = k / 16f; p += 1.05f + 0.55f * sin(t * PI.toFloat() * 0.8f) - 0.6f * t * t; p += -t }
        return MeshBuilder().lathe(p.toFloatArray(), segs).build()
    }
}
