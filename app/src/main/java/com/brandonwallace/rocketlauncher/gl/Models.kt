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

object MatType { const val PLAIN = 0; const val BODY = 1; const val CARBON = 2; const val FOAM = 3; const val STEEL = 4; const val TILES = 5; const val TERRAIN = 6; const val ENGINE = 7; const val METAL = 8; const val PAD = 9; const val FOIL = 10 }

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
    /** Solar wing: folded against the body on the pad, swings out once in space. */
    val hinge: Boolean = false,
)

class Nozzle(val x: Float, val z: Float, val radius: Float)

/**
 * Procedural rocket. Everything visible comes from the researched tech: body material (paint → aluminium →
 * carbon → cryo foam → stainless → graphene), engine type and count (1 → 3 → 9 → 13 → 20 → 33), gimbal
 * actuators, vacuum bells and plasma coils, nose profile and spike, fins, grid fins, RCS/ion thrusters,
 * avionics hardware (antennas, dishes, AI dome, laser terminal, solar wings, RTGs), thermal and radiation
 * protection (gold foil vault), Whipple shields, the Europa lander and sky-crane, boosters/side cores and the
 * number of stages. (The exhaust itself changes too: see [Exhaust].)
 */
class RocketModel(val spec: RocketSpec, private val segs: Int) {
    companion object {
        const val PAYLOAD = 100
        /** Hinged solar wings (attached to the payload, unfold in space). */
        const val ARRAYS = 101
        private val GLOW_BLUE = floatArrayOf(0.35f, 1.1f, 3.2f)
        private val GLOW_GREEN = floatArrayOf(0.3f, 3.0f, 0.8f)
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
    private val bodyMat = when { graphene -> MatType.CARBON; has("S_BALLOON") -> MatType.STEEL; has("S_CRYO") -> MatType.FOAM; has("S_COMP") -> MatType.CARBON; else -> MatType.BODY }
    private val alu = has("S_ALU")
    private val bodyCol = floatArrayOf(0.92f, 0.92f, 0.9f).let { if (alu && bodyMat == MatType.BODY) floatArrayOf(0.80f, 0.81f, 0.83f) else it }
    private val bodyRough = if (alu) 0.36f else 0.42f
    private val bodyMetal = if (alu && bodyMat == MatType.BODY) 0.9f else 0f
    private val paintPattern = if (!alu && bodyMat == MatType.BODY) 1f else 0f
    private val roughMul = when { has("A_WAVE") -> 0.35f; has("A_SMOOTH") -> 0.55f; else -> 1f }
    private val accent = when (bodyMat) {
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
                val bl = r * (if (solid) 0.5f else 0.8f + 0.05f * engineLv)
                val positions = when (count) {
                    33 -> ring(3, r * 0.18f, 0f) + ring(10, r * 0.48f, 0.3f) + ring(20, r * 0.82f, 0.1f)
                    20 -> ring(6, r * 0.33f, 0f) + ring(14, r * 0.76f, 0.2f)
                    13 -> (0 until 3).map { k -> val a = k * 2f * PI.toFloat() / 3f; r * 0.2f * cos(a) to r * 0.2f * sin(a) } +
                        (0 until 10).map { k -> val a = k * PI.toFloat() / 5f + 0.3f; r * 0.7f * cos(a) to r * 0.7f * sin(a) }
                    9 -> listOf(0f to 0f) + (0 until 8).map { k -> val a = k * PI.toFloat() / 4f; r * 0.62f * cos(a) to r * 0.62f * sin(a) }
                    3 -> (0 until 3).map { k -> val a = k * 2f * PI.toFloat() / 3f + PI.toFloat() / 2f; r * 0.45f * cos(a) to r * 0.45f * sin(a) }
                    else -> listOf(0f to 0f)
                }
                val re = when (count) { 33 -> r * 0.115f; 20 -> r * 0.15f; 13 -> r * 0.165f; 9 -> r * 0.21f; 3 -> r * 0.36f; else -> r * (if (solid) 0.5f else 0.64f) }
                if (solid) engines.color(0.16f, 0.16f, 0.17f).material(0.7f, 0.1f)
                else if (has("P_METHALOX")) engines.color(0.34f, 0.33f, 0.35f).material(0.24f, 1f)   // polished methalox bells
                for ((x, z) in positions) { bell(engines, x, z, 0f, re, bl, solid); noz += Nozzle(x, z, re) }
                // gimbal actuators (thrust-vector control: red electric struts)
                if (spec.level(Stat.GIMBAL) >= 1) {
                    val tvc = spec.level(Stat.GIMBAL) >= 2
                    val gb = mb().color(if (tvc) 0.78f else 0.32f, if (tvc) 0.12f else 0.32f, if (tvc) 0.08f else 0.34f).material(0.45f, 0.6f)
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
                // boat-tail skirt
                val sk = mb().color(accent[0] * 0.4f + 0.1f, accent[1] * 0.4f + 0.1f, accent[2] * 0.4f + 0.1f).material(0.5f, 0.3f)
                sk.lathe(floatArrayOf(r * 0.9f, bl * 0.55f, r, bl + 0.25f), segs)
                add(0, sk, MatType.BODY, floatArrayOf(0.35f, 16f, 0f, 0f))
                tankStart = bl + 0.25f
            } else {
                val re = ure[i]; val bl = ubl[i]
                val ext = vac >= 2
                val plasma = spec.level(Stat.NUCLEAR) >= 3
                if (plasma) engines.color(0.55f, 0.56f, 0.6f).material(0.22f, 1f)               // polished plasma chamber
                else if (has("P_NTR")) engines.color(0.62f, 0.48f, 0.18f).material(0.28f, 1f)   // gold reactor shielding
                else engines.color(0.25f, 0.24f, 0.23f).material(0.32f, 0.9f)
                bell(engines, 0f, 0f, 0f, re, bl, false)
                var exitR = re
                if (ext) { // dark niobium extension ring (carbon-carbon skirts make it much longer)
                    val skirt = if (vac >= 4) 0.55f else 0.25f
                    exitR = re * (if (vac >= 4) 1.22f else 1.12f)
                    val ex = mb().color(if (vac >= 4) 0.07f else 0.16f, if (vac >= 4) 0.07f else 0.13f, if (vac >= 4) 0.08f else 0.17f).material(0.4f, 0.8f)
                    ex.lathe(floatArrayOf(exitR, -bl * skirt, re, 0f), segs)
                    ex.lathe(floatArrayOf(exitR * 0.95f, -bl * skirt, re * 0.95f, 0f), segs, inside = true)
                    add(i, ex, MatType.ENGINE, floatArrayOf(0f, 0f, 0f, 0f), engine = true)
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
            // Whipple shield: quilted stand-off bumper panels around the upper stages
            if (i > 0 && !foilTop && has("S_WHIPPLE")) {
                val wb = mb().color(0.93f, 0.93f, 0.9f).material(0.75f, 0f)
                val y0w = tankStart + 0.25f; val y1w = top - 0.3f
                if (y1w > y0w + 0.3f) {
                    wb.lathe(floatArrayOf(r * 1.012f, y0w, r * 1.035f, y0w + 0.05f, r * 1.035f, y1w - 0.05f, r * 1.012f, y1w), segs)
                    add(i, wb, MatType.BODY, floatArrayOf(0.45f, 12f, 0f, 0f))
                }
            }
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
                val fr = mb().color(0.9f, 0.93f, 0.97f).material(0.95f, 0f)
                fr.lathe(floatArrayOf(r * 1.002f, tankStart + 0.1f, r * 1.006f, tankStart + 0.2f, r * 1.006f, tankStart + (top - tankStart) * 0.35f, r * 1.002f, tankStart + (top - tankStart) * 0.38f), segs)
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
        val w = r * 0.9f; val h = r * 0.7f
        for (k in 0 until 4) {
            val ang = 45f + k * 90f
            b.transform(Mat.trs(FloatArray(16), 0f, 0f, 0f, ry = -ang))
            val x0 = r * 1.02f; val y0 = top - 0.9f - h
            // frame
            b.beam(x0, y0, -w / 2, x0 + h, y0, -w / 2, 0.04f); b.beam(x0, y0, w / 2, x0 + h, y0, w / 2, 0.04f)
            b.beam(x0 + h, y0, -w / 2, x0 + h, y0, w / 2, 0.04f); b.beam(x0, y0, -w / 2, x0, y0, w / 2, 0.05f)
            // lattice
            for (j in 1 until 5) { val t = j / 5f; b.beam(x0 + h * t, y0, -w / 2, x0 + h * t, y0, w / 2, 0.015f); b.beam(x0, y0, -w / 2 + w * t, x0 + h, y0, -w / 2 + w * t, 0.015f) }
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
            for (deg in listOf(60f, 180f)) {
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
        add(PAYLOAD, body, MatType.BODY, floatArrayOf(if (fairing) 50f else 0.8f, if (fairing) 2f else 0f, 0f, 0f))
        if (heat > 0 && upper.size >= 4) {
            val tp = mb()
            when {
                has("TH_PICA") -> tp.color(0.42f, 0.42f, 0.40f).material(0.9f, 0f)
                has("TH_TILE") -> tp.color(0.05f, 0.05f, 0.05f).material(0.8f, 0f)
                has("TH_ABL") -> tp.color(0.14f, 0.08f, 0.05f).material(0.9f, 0f)
                else -> tp.color(0.6f, 0.44f, 0.28f).material(0.85f, 0f)
            }
            tp.lathe(upper.toFloatArray(), segs)
            add(PAYLOAD, tp, if (has("TH_TILE") && !has("TH_PICA")) MatType.TILES else MatType.PLAIN)
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
        val angles = if (n == 4) listOf(45f, 135f, 225f, 315f) else listOf(0f, 180f)
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
