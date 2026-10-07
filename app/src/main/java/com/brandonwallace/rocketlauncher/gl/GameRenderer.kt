package com.brandonwallace.rocketlauncher.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.brandonwallace.rocketlauncher.audio.SoundEngine
import com.brandonwallace.rocketlauncher.game.EntityKind
import com.brandonwallace.rocketlauncher.game.Fx
import com.brandonwallace.rocketlauncher.game.GameEngine
import com.brandonwallace.rocketlauncher.game.Phase
import com.brandonwallace.rocketlauncher.game.Physics
import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.World
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frame structure (see README in the report):
 *  1. engine.advance(dt) runs fixed 1/120 s steps; we render the state interpolated by the returned alpha.
 *  2. Camera-relative rendering: every model matrix is built from (objectPos - cameraPos) in double precision,
 *     the view matrix is rotation-only. No float jitter even 380,000 km from the pad.
 *  3. Depth partitioning: far pass (1.2-80 km: terrain, distant buildings) -> clear depth -> near pass
 *     (0.3 m-1.5 km). Two well-conditioned depth ranges instead of one 0.3 m-80 km range (no z-fighting).
 *  4. Sky-view scattering LUT -> sky, stars, terrain, complex, rocket, hazards, plumes, sorted billboards.
 *  5. MSAA resolve -> bloom (Karis-averaged dual filter) -> ACES composite with heat shimmer + dither.
 */
class GameRenderer(private val engine: GameEngine, private val sound: SoundEngine) : GLSurfaceView.Renderer {
    @Volatile var vsyncNanos = 0L
    private var lastFrameNanos = 0L

    private val pipe = Pipeline()
    private var lit: ShaderProgram? = null
    private var shadowProg: ShaderProgram? = null
    private var skyLutProg: ShaderProgram? = null
    private var skyProg: ShaderProgram? = null
    private var starProg: ShaderProgram? = null
    private var plumeProg: ShaderProgram? = null
    private var bbProg: ShaderProgram? = null
    private var downProg: ShaderProgram? = null
    private var upProg: ShaderProgram? = null
    private var compProg: ShaderProgram? = null

    private var rocket: RocketModel? = null; private var rocketKey = ""
    private var complex: LaunchComplex? = null; private var complexKey = -1
    private var terrain: Mesh? = null
    private var ents: EntityMeshes? = null
    private var plumeCore: Mesh? = null; private var plumeOuter: Mesh? = null
    private var decalTex = 0
    private var starVao = 0; private var starCount = 0
    private var bbVao = 0; private var bbInstVbo = 0
    private val particles = Particles(4000)
    private val batch = BillboardBatch(6000)
    private val bbBuf = floatBuffer(6000 * 12)
    private var surfW = 0; private var surfH = 0
    private var quality = Quality.MEDIUM

    // matrices (pre-allocated: zero allocations per frame)
    private val projN = FloatArray(16); private val projF = FloatArray(16); private val viewRot = FloatArray(16)
    private val vpN = FloatArray(16); private val vpF = FloatArray(16); private val invVP = FloatArray(16)
    private val m = FloatArray(16); private val m2 = FloatArray(16); private val m3 = FloatArray(16)
    private val rocketM = FloatArray(16); private val nrm = FloatArray(9)
    private val shadowVP = FloatArray(16); private val shadowVPCam = FloatArray(16); private val lightMVP = FloatArray(16)
    private val lightRot = FloatArray(16); private val lightProj = FloatArray(16)
    private val tmp3 = FloatArray(3); private val tmp3b = FloatArray(3)

    // ---- snapshot of game state for this frame (taken under the engine lock, interpolated)
    private var sPhase = Phase.MENU
    private var sAlt = 0.0
    private var sLaneX = 0f; private var sTilt = 0f
    private var sStage = 0; private var sBoosters = false
    private var sGlow = 0f; private var sShake = 0f; private var sExploded = false
    private var sScroll = 0f; private var sHeat = 0f; private var sPaused = false
    private var sLiftedOff = false; private var sFlightTime = 0.0; private var sBoosterBurn = false
    private var sCountdown = 0f
    private var spec: RocketSpec = engine.spec
    private var specVersion = -1
    private val MAXE = 256
    private val eKind = IntArray(MAXE); private val eX = FloatArray(MAXE); private val eY = FloatArray(MAXE); private val eZ = FloatArray(MAXE)
    private val eRot = FloatArray(MAXE); private val eSeed = FloatArray(MAXE); private val eAge = FloatArray(MAXE); private val eVx = FloatArray(MAXE)
    private var eN = 0
    private val MAXC = 160
    private val cX = FloatArray(MAXC); private val cY = FloatArray(MAXC); private val cZ = FloatArray(MAXC)
    private val cSize = FloatArray(MAXC); private val cSeed = FloatArray(MAXC); private val cAge = FloatArray(MAXC)
    private var cN = 0
    private val MAXS = 24
    private val spG = IntArray(MAXS); private val spX = FloatArray(MAXS); private val spY = FloatArray(MAXS); private val spRot = FloatArray(MAXS)
    private var spN = 0
    private val fxLocal = ArrayList<Fx>()

    // ---- camera state (pad frame, double precision position)
    private var camX = 0.0; private var camY = 0.0; private var camZ = 0.0
    private val off = floatArrayOf(20f, 8f, 40f); private val look = floatArrayOf(0f, 10f, 0f)
    private var lensShift = 0f
    private var camInit = false
    private var groundCam = false
    private var animTime = 0f
    private var whiteout = 0f
    private var flash = 0f
    private var armAngle = 0f
    private var emitAcc = FloatArray(8)
    private var lastPhase = Phase.MENU

    // ---- lighting for this frame
    private val sunDir = floatArrayOf(0.70f, 0.407f, 0.587f).also { val l = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]); for (i in 0..2) it[i] /= l }
    private val SUN_I = 20f
    private val sunCol = FloatArray(3); private val skyAmb = FloatArray(3); private val groundAmb = FloatArray(3); private val horizon = FloatArray(3)
    private var fogDensity = 0f
    private var exposure = 0.42f

    companion object {
        const val NEAR = 0.3f; const val NEAR_FAR = 1500f; const val FAR_NEAR = 1200f; const val FAR_FAR = 80000f
        const val R_KM = 6371.0
        const val EARTH_VIEW_MAX_KM = 1.5e6
        fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context means every old GL name is gone: drop references (don't delete) and rebuild all.
        rocket = null; rocketKey = ""; complex = null; complexKey = -1; terrain = null; ents = null
        plumeCore = null; plumeOuter = null
        pipe.init()
        lit = ShaderProgram("lit", Shaders.LIT_VS, Shaders.LIT_FS)
        shadowProg = ShaderProgram("shadow", Shaders.SHADOW_VS, Shaders.SHADOW_FS)
        skyLutProg = ShaderProgram("skylut", Shaders.FULL_VS, Shaders.SKYLUT_FS)
        skyProg = ShaderProgram("sky", Shaders.FULL_VS, Shaders.SKY_FS)
        starProg = ShaderProgram("stars", Shaders.STAR_VS, Shaders.STAR_FS)
        plumeProg = ShaderProgram("plume", Shaders.PLUME_VS, Shaders.PLUME_FS)
        bbProg = ShaderProgram("billboard", Shaders.BB_VS, Shaders.BB_FS)
        downProg = ShaderProgram("bloomDown", Shaders.FULL_VS, Shaders.BLOOM_DOWN_FS)
        upProg = ShaderProgram("bloomUp", Shaders.FULL_VS, Shaders.BLOOM_UP_FS)
        compProg = ShaderProgram("composite", Shaders.FULL_VS, Shaders.COMPOSITE_FS)
        decalTex = Decals.create()
        terrain = TerrainGen.build()
        plumeCore = PlumeGen.core(32); plumeOuter = PlumeGen.outer(32)
        createStars()
        createBillboardVao()
        lastFrameNanos = 0L
        camInit = false
        if (surfW > 0) pipe.configure(surfW, surfH, quality)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfW = width; surfH = height
        pipe.configure(width, height, quality)
        lastFrameNanos = 0L   // a resize/resume must not turn into one giant simulation step
    }

    private fun createStars() {
        val n = 2600
        val d = FloatArray(n * 5)
        var s = 12345
        fun r(): Float { s = s * 1103515245 + 12345; return ((s ushr 8) and 0xFFFFFF) / 16777216f }
        for (i in 0 until n) {
            val z = r() * 2 - 1; val a = r() * 6.2832f; val rr = sqrt(1 - z * z)
            d[i * 5] = rr * cos(a); d[i * 5 + 1] = z; d[i * 5 + 2] = rr * sin(a)
            d[i * 5 + 3] = 0.15f + 0.85f * r().pow(7f); d[i * 5 + 4] = r()
        }
        val vao = IntArray(1); val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0]); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val fb = floatBuffer(d.size).put(d); fb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, d.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindVertexArray(0)
        starVao = vao[0]; starCount = n
    }

    private fun createBillboardVao() {
        val vao = IntArray(1); val vbo = IntArray(2)
        GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(2, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val c = floatBuffer(8).put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); c.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, c, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.capacity * 48, null, GLES30.GL_STREAM_DRAW)
        bbInstVbo = vbo[1]
        bindInstanceAttribs(0)
        for (i in 1..3) GLES30.glVertexAttribDivisor(i, 1)
        GLES30.glBindVertexArray(0)
        bbVao = vao[0]
    }

    /** ES 3.0 has no base-instance, so the additive range is drawn by offsetting the instance pointers. */
    private fun bindInstanceAttribs(firstInstance: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bbInstVbo)
        val o = firstInstance * 48
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 48, o)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 48, o + 16)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 48, o + 32)
    }

    // ------------------------------------------------------------------ frame

    override fun onDrawFrame(gl: GL10?) {
        val now = if (vsyncNanos != 0L) vsyncNanos else System.nanoTime()
        val dt = if (lastFrameNanos == 0L || now <= lastFrameNanos) 1f / 60f else ((now - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastFrameNanos = now

        val q = Quality.of(engine.save.value.quality)
        if (q != quality) { quality = q; pipe.configure(surfW, surfH, q); rocketKey = "" }
        particles.budget = q.particles

        val alpha = engine.advance(dt)
        snapshot(alpha)
        ensureModels()
        val rk = rocket ?: return
        val cx = complex ?: return

        val pdt = if (sPaused) 0f else dt
        animTime += pdt
        handleFx(rk)
        emitParticles(rk, pdt)
        particles.update(pdt, sScroll, 1.5f)
        if (sAlt > 4000) clearGroundParticles()
        updateCamera(rk, dt)
        computeLighting()
        updateSound()

        val aspect = pipe.sceneW.toFloat() / max(1, pipe.sceneH)
        val fov = if (aspect < 1f) 52f else 40f
        Mat.perspective(projN, fov, aspect, NEAR, NEAR_FAR); projN[9] = lensShift
        Mat.perspective(projF, fov, aspect, FAR_NEAR, FAR_FAR); projF[9] = lensShift
        val dx = look[0] + lookShake[0] - off[0] + sLaneX * 0.2f; val dy = look[1] + lookShake[1] - off[1]; val dz = look[2] - off[2]
        Mat.lookAt(viewRot, 0f, 0f, 0f, dx, dy, dz, 0f, 1f, 0f)
        Mat.mul(vpN, projN, viewRot); Mat.mul(vpF, projF, viewRot)
        Mat.invert(invVP, vpN)

        val l = lit ?: return
        if (!l.ok) Log.w("RocketGL", "lit shader failed")

        // ---------- sky LUT
        renderSkyLut()
        // ---------- shadows
        val shadowsOn = pipe.shadowSize > 0 && sunDir[1] > 0.05f && (sPhase != Phase.FLIGHT || sAlt < 2500)
        if (shadowsOn) renderShadows(rk, cx)

        // ---------- scene
        pipe.bindScene()
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        renderSky()
        renderStars()

        setupLitFrame(l, shadowsOn)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        if (camY < 30000) {
            // far partition
            l.m4("uVP", vpF)
            drawGround(l, cx, far = true)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        }
        l.m4("uVP", vpN)
        if (camY < NEAR_FAR + 200) drawGround(l, cx, far = false)
        if (!sExploded) drawRocket(l, rk)
        drawSpent(l, rk)
        drawEntities(l)

        // ---------- plumes + billboards (no depth writes)
        GLES30.glDepthMask(false)
        if (!sExploded) drawPlumes(rk)
        drawBillboards()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        // ---------- post
        pipe.resolve()
        renderBloom()
        renderComposite(rk, dt)
    }

    // ------------------------------------------------------------------ state

    private fun snapshot(a: Float) = synchronized(engine.lock) {
        sPhase = engine.phase
        val f = engine.flight
        sAlt = if (f != null && (sPhase == Phase.FLIGHT || sPhase == Phase.RESULTS)) engine.prevAlt + (f.alt - engine.prevAlt) * a else 0.0
        sLaneX = engine.prevLaneX + (engine.laneX - engine.prevLaneX) * a
        sTilt = engine.prevTilt + (engine.tilt - engine.prevTilt) * a
        spec = engine.spec
        specVersion = engine.specVersion
        sStage = f?.stage ?: 0
        sBoosters = f?.boostersAttached ?: (spec.boosterCount > 0)
        sBoosterBurn = f != null && f.boostersAttached && f.boosterFuel > 0 && f.throttle > 0.01
        sGlow = engine.engineGlow; sShake = engine.shake; sExploded = engine.exploded
        sScroll = engine.scroll; sHeat = (f?.heat ?: 0.0).toFloat(); sPaused = engine.paused
        sLiftedOff = f?.liftedOff ?: false; sFlightTime = f?.time ?: 0.0
        sCountdown = engine.countdown
        eN = 0
        for (e in engine.world.entities) {
            if (eN >= MAXE) break
            eKind[eN] = e.kind.ordinal
            eX[eN] = e.px + (e.x - e.px) * a; eY[eN] = e.py + (e.y - e.py) * a; eZ[eN] = e.z
            eRot[eN] = e.prot + (e.rot - e.prot) * a; eSeed[eN] = e.seed; eAge[eN] = e.age; eVx[eN] = e.vx
            eN++
        }
        cN = 0
        for (c in engine.world.clouds) {
            if (cN >= MAXC) break
            cX[cN] = c.x; cY[cN] = c.py + (c.y - c.py) * a; cZ[cN] = c.z; cSize[cN] = c.size; cSeed[cN] = c.seed; cAge[cN] = c.age
            cN++
        }
        spN = 0
        for (p in engine.spent) {
            if (spN >= MAXS) break
            spG[spN] = p.group; spX[spN] = p.px + (p.x - p.px) * a; spY[spN] = p.py + (p.y - p.py) * a; spRot[spN] = p.prot + (p.rot - p.prot) * a
            spN++
        }
        fxLocal.addAll(engine.fx); engine.fx.clear()
    }

    private fun ensureModels() {
        val key = "$specVersion-${quality.segs}-${spec.tech.hashCode()}"
        if (key != rocketKey || rocket == null) {
            rocket?.release()
            rocket = RocketModel(spec, quality.segs)
            rocketKey = key
        }
        val rk = rocket!!
        val ck = (rk.totalLength * 2).toInt()
        if (ck != complexKey || complex == null) {
            complex?.release()
            complex = LaunchComplex(rk.totalLength, quality.segs)
            complexKey = ck
        }
        if (ents == null) ents = EntityMeshes(24)
    }

    private fun clearGroundParticles() {
        // ground steam is irrelevant once far away; dropping it also keeps float precision trivial
        var i = 0
        while (i < particles.count) { if (particles.frame[i] == Particles.GROUND) particles.life[i] = particles.maxLife[i]; i++ }
    }

    private fun atm(): Float = (Physics.density(sAlt) / 1.225).toFloat()

    private fun remainingLength(rk: RocketModel) = rk.totalLength - rk.stageBase[sStage.coerceIn(0, rk.stageBase.size - 1)]

    // ------------------------------------------------------------------ camera

    private fun updateCamera(rk: RocketModel, dt: Float) {
        val L = remainingLength(rk)
        val widen = if (sBoosters && spec.boosterCount > 0) 6f else 0f
        val tOff = camTOff; val tLook = camTLook
        tOff.fill(0f); tLook.fill(0f)
        var tShift = 0f
        var direct = false
        var rate = 2.2f
        if (sPhase != lastPhase) { if (sPhase == Phase.FLIGHT) groundCam = true; lastPhase = sPhase }
        when (sPhase) {
            Phase.MENU -> {
                val ang = animTime * 0.05f + 0.6f; val d = 1.7f * L + 16f + widen
                tOff[0] = sin(ang) * d; tOff[1] = L * 0.3f + 2f; tOff[2] = cos(ang) * d
                tLook[1] = L * 0.55f; tShift = -0.08f
            }
            Phase.HANGAR, Phase.TECH -> {
                // far enough back that even a five-stage Mars stack fits above the hangar panels
                val ang = 0.55f + 0.22f * sin(animTime * 0.12f); val d = 2.1f * L + 8f + widen
                tOff[0] = sin(ang) * d; tOff[1] = L * 0.42f; tOff[2] = cos(ang) * d
                tLook[1] = L * 0.5f; tShift = -0.42f
            }
            Phase.COUNTDOWN -> {
                tOff[0] = 9f + L * 0.3f; tOff[1] = 1.8f; tOff[2] = 18f + L * 0.5f
                tLook[1] = L * 0.62f; rate = 1.4f
            }
            Phase.FLIGHT, Phase.RESULTS -> {
                if (groundCam && (sFlightTime > 4.5 || sAlt > 180.0 || sPhase == Phase.RESULTS)) groundCam = false
                if (groundCam) {
                    // camera stays on the ground while the rocket climbs past it
                    tOff[0] = 9f + L * 0.3f - sLaneX * 0.8f; tOff[1] = 1.8f - sAlt.toFloat(); tOff[2] = 18f + L * 0.5f
                    tLook[1] = L * 0.62f; direct = true
                } else {
                    val d = 1.55f * L + 20f + widen
                    val sway = 0.05f * sin(animTime * 0.3f)
                    val ang = if (sPhase == Phase.RESULTS) 0.32f + animTime * 0.03f else 0.30f + sway
                    tOff[0] = sin(ang) * d; tOff[1] = L * 0.35f + 3f; tOff[2] = cos(ang) * d
                    tLook[1] = L * 0.5f + 10f
                    // Deep space: swing the camera below/behind the rocket and look along the flight path, so the
                    // rocket and the target (Moon/Mars, drifting toward the zenith) are both in frame. The old
                    // "tilt the side camera up 48 degrees" pushed the rocket out of the picture past ~50,000 km.
                    val deep = smooth(1.5e6f, 2.5e7f, sAlt.toFloat())
                    if (deep > 0f) {
                        val td = targetDir(camTDir)
                        var hx = td[0]; var hz = td[2]
                        val hn = sqrt(hx * hx + hz * hz)
                        if (hn > 1e-4f) { hx /= hn; hz /= hn } else { hx = 0f; hz = -1f }
                        val dOx = -hx * 0.55f * d; val dOy = -0.75f * d; val dOz = -hz * 0.55f * d
                        val dLx = td[0] * 0.9f * d; val dLy = L * 0.5f + td[1] * 0.9f * d; val dLz = td[2] * 0.9f * d
                        tOff[0] += (dOx - tOff[0]) * deep; tOff[1] += (dOy - tOff[1]) * deep; tOff[2] += (dOz - tOff[2]) * deep
                        tLook[0] += (dLx - tLook[0]) * deep; tLook[1] += (dLy - tLook[1]) * deep; tLook[2] += (dLz - tLook[2]) * deep
                    }
                    // lift the rocket clear of the bottom stage/fuel HUD bar (was half hidden behind it)
                    if (sPhase == Phase.FLIGHT) tShift = -0.2f * (1f - deep)
                    rate = 1.3f
                }
            }
        }
        if (!camInit) { System.arraycopy(tOff, 0, off, 0, 3); System.arraycopy(tLook, 0, look, 0, 3); lensShift = tShift; camInit = true }
        if (direct) { System.arraycopy(tOff, 0, off, 0, 3); System.arraycopy(tLook, 0, look, 0, 3) }
        else {
            val k = 1f - exp(-dt * rate)
            for (i in 0..2) { off[i] += (tOff[i] - off[i]) * k; look[i] += (tLook[i] - look[i]) * k }
        }
        lensShift += (tShift - lensShift) * (1f - exp(-dt * 3f))
        // shake (smooth, low-frequency noise; scaled with distance so it reads the same at any zoom)
        val shakeAmp = sShake * 0.035f * (abs(off[2]) / 30f)
        val t = animTime
        val shX = (sin(t * 31f) * 0.6f + sin(t * 47f + 1.3f) * 0.4f) * shakeAmp
        val shY = (sin(t * 37f + 2f) * 0.6f + sin(t * 53f) * 0.4f) * shakeAmp
        camX = sLaneX * 0.8 + off[0]; camY = sAlt + off[1]; camZ = off[2].toDouble()
        // never let the camera dip under the ground
        if (camY < 1.2) { val d = 1.2 - camY; camY = 1.2; off[1] += d.toFloat() }
        lookShake[0] = shX; lookShake[1] = shY
    }
    private val lookShake = FloatArray(2)
    private val camTOff = FloatArray(3); private val camTLook = FloatArray(3)
    private val camTDir = FloatArray(3)

    /** Unit direction from the rocket toward the flight target (Moon or Mars), for the deep-space camera. */
    private fun targetDir(out: FloatArray): FloatArray {
        // 1.1: every flight is the one Moon→Mars mission, so the camera always looks toward Mars
        marsDirection(out); return out
    }

    // ------------------------------------------------------------------ lighting

    private fun computeLighting() {
        val h = (sAlt / 1000.0).toFloat()
        val sinEl = sunDir[1]
        val am = 1f / (sinEl + 0.025f * exp(-11f * sinEl))
        val dR = exp(-h / 8f); val dM = exp(-h / 1.2f)
        val br = floatArrayOf(5.802e-3f, 13.558e-3f, 33.1e-3f)
        for (i in 0..2) {
            val tau = (br[i] * 8f * dR + 4.44e-3f * 1.2f * dM) * am
            sunCol[i] = SUN_I * exp(-tau)
        }
        val af = exp(-h / 7f).coerceIn(0f, 1f)
        val earth = (6371f / (6371f + h)).let { it * it }
        skyAmb[0] = SUN_I * (0.016f * af + 0.0008f); skyAmb[1] = SUN_I * (0.03f * af + 0.0008f); skyAmb[2] = SUN_I * (0.062f * af + 0.001f)
        val gs = 0.012f * af + 0.028f * (1f - af) * earth
        groundAmb[0] = SUN_I * gs * 0.55f; groundAmb[1] = SUN_I * gs * 0.55f; groundAmb[2] = SUN_I * gs * (0.45f + 0.35f * (1 - af))
        horizon[0] = SUN_I * 0.05f * (0.25f + 0.75f * af); horizon[1] = SUN_I * 0.055f * (0.25f + 0.75f * af); horizon[2] = SUN_I * 0.065f * (0.25f + 0.75f * af)
        fogDensity = 6.5e-5f * exp(-h / 8f)
    }

    private fun updateSound() {
        val on = engine.save.value.soundOn
        sound.engineLevel = if (on && (sPhase == Phase.FLIGHT || sPhase == Phase.COUNTDOWN)) sGlow * (if (sPaused) 0f else 1f) else 0f
        sound.atmosphere = atm().coerceIn(0f, 1f).let { 0.15f + 0.85f * it }
    }

    // ------------------------------------------------------------------ passes

    private fun setLutUniforms(p: ShaderProgram) {
        // Earth is a sub-pixel dot beyond ~1.5 million km; clamping keeps the float ray/sphere maths exact
        // (at Mars range h*(2R+h) ~ 3e15 would cancel and paint garbage "Earth" pixels).
        val h = (camY / 1000.0).coerceIn(0.002, EARTH_VIEW_MAX_KM)
        p.f("uAltKm", h.toFloat()); p.f("uRkm", R_KM.toFloat()); p.f("uC0", (h * (2 * R_KM + h)).toFloat())
    }

    private fun renderSkyLut() {
        val p = skyLutProg ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pipe.lutFbo)
        GLES30.glViewport(0, 0, pipe.lutW, pipe.lutH)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_BLEND)
        p.use()
        setLutUniforms(p)
        p.f3("uSun", sunDir); p.f("uSunI", SUN_I); p.i("uSteps", quality.lutSteps)
        pipe.drawQuad()
    }

    private fun renderSky() {
        val p = skyProg ?: return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDepthMask(false); GLES30.glDisable(GLES30.GL_BLEND)
        p.use()
        setLutUniforms(p)
        p.m4("uInvVP", invVP)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pipe.lutTex); p.i("uLut", 1)
        p.f3("uSun", sunDir); p.f("uSunI", SUN_I)
        moonPos(tmp3)
        p.f3("uMoonPos", tmp3); p.f("uMoonR", moonR(tmp3))
        setMarsUniforms(p)
        p.f("uTime", (animTime % 1000f)); p.f("uDeckKm", (World.DECK_ALT / 1000.0).toFloat()); p.f("uOutScale", pipe.outScale)
        p.f2("uPadKm", (-camX / 1000.0).toFloat(), (-camZ / 1000.0).toFloat())
        pipe.drawQuad()
        GLES30.glDepthMask(true)
    }

    /** Moon radius uniform; negative (= hidden) once it is far behind on the way to Mars. */
    private fun moonR(pos: FloatArray): Float {
        val d2 = pos[0].toDouble() * pos[0] + pos[1].toDouble() * pos[1] + pos[2].toDouble() * pos[2]
        return if (d2 > 3.0e6 * 3.0e6) -1f else 1737f
    }

    private val marsDir = FloatArray(3)

    /** Mars direction + distance (in Mars radii) for Mars flights; it drifts to the zenith on approach, like the Moon. */
    private fun setMarsUniforms(p: ShaderProgram) {
        val show = sPhase == Phase.FLIGHT || sPhase == Phase.RESULTS
        if (!show) { p.f3("uMarsDir", 0f, 1f, 0f); p.f("uMarsD", 0f); return }
        val camKm = camY / 1000.0
        val distKm = Physics.MARS_CENTER_DIST / 1000.0 - (R_KM + camKm)
        val d = (distKm / (Physics.MARS_RADIUS / 1000.0)).coerceAtLeast(1.015)
        marsDirection(marsDir)
        p.f3("uMarsDir", marsDir); p.f("uMarsD", d.toFloat())
    }

    private fun marsDirection(out: FloatArray) {
        val prog = (sAlt / Physics.MARS_ARRIVAL_ALT).coerceIn(0.0, 1.0)
        val th = Math.toRadians(24.0 * sqrt(1.0 - prog))
        val dx = 0.4 * sin(th); val dz = -0.9 * sin(th); val dy = cos(th)
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        out[0] = (dx / n).toFloat(); out[1] = (dy / n).toFloat(); out[2] = (dz / n).toFloat()
    }

    /** Moon centre relative to the camera, in km. It drifts toward the zenith as you approach so you arrive on it. */
    private fun moonPos(out: FloatArray) {
        // The one mission flies past the Moon: it stays off to the side (a flyby) instead of drifting into the flight path.
        val p = 0.0
        val th = Math.toRadians(16.0 * sqrt(1.0 - p) + 0.0)
        val dist = Physics.MOON_CENTER_DIST / 1000.0
        val dx = -0.3 * sin(th); val dz = -0.95 * sin(th); val dy = cos(th)
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        val mx = dx / n * dist; val my = -R_KM + dy / n * dist; val mz = dz / n * dist
        out[0] = (mx - camX / 1000.0).toFloat(); out[1] = (my - camY / 1000.0).toFloat(); out[2] = (mz - camZ / 1000.0).toFloat()
    }

    private fun renderStars() {
        val p = starProg ?: return
        val vis = smooth(25000f, 90000f, camY.toFloat())
        if (vis <= 0.001f) return
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        p.use()
        p.m4("uVP", vpN)
        setLutUniforms(p)
        p.f("uPx", pipe.sceneH / 1100f * 1.4f)
        moonPos(tmp3); p.f3("uMoonPos", tmp3); p.f("uMoonR", moonR(tmp3))
        setMarsUniforms(p)
        p.f("uVis", vis); p.f("uOutScale", pipe.outScale)
        GLES30.glBindVertexArray(starVao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starCount)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun setupLitFrame(l: ShaderProgram, shadowsOn: Boolean) {
        l.use()
        setLutUniforms(l)
        l.f3("uSunDir", sunDir); l.f3("uSunCol", sunCol); l.f3("uSkyAmb", skyAmb); l.f3("uGroundAmb", groundAmb); l.f3("uHorizon", horizon)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pipe.lutTex); l.i("uLut", 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pipe.shadowTex); l.i("uShadowMap", 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, decalTex); l.i("uDecalTex", 3)
        l.f("uShadowOn", if (shadowsOn) 1f else 0f)
        l.f("uShadowTexel", 1f / max(1, pipe.shadowSize))
        l.i("uShadowTaps", quality.shadowTaps)
        l.m4("uShadowVP", shadowVPCam)
        l.f("uFogDensity", fogDensity); l.f("uOutScale", pipe.outScale)
        l.f("uTime", animTime % 1000f)
        l.f3("uPadRel", (-camX).toFloat(), (-camY).toFloat(), (-camZ).toFloat())
        // engine flame light (from the current stage's nozzles)
        val glow = if (sExploded) 0f else sGlow
        l.f3("uFlamePos", flamePos[0], flamePos[1], flamePos[2])
        val fa = glow * 30f * (0.4f + 0.6f * atm())
        l.f3("uFlameCol", fa, fa * 0.55f, fa * 0.22f)
    }
    private val flamePos = FloatArray(3)

    private fun setDraw(l: ShaderProgram, model: FloatArray, mat: Int, rough: Float = 1f, tint: Float = 1f, emissive: FloatArray? = null, alpha: Float = 1f) {
        l.m4("uModel", model)
        Mat.normalMatrix(nrm, model); l.m3("uNrmMat", nrm)
        l.i("uMatType", mat)
        l.f3("uTint", tint, tint, tint)
        if (emissive != null) l.f3("uEmissive", emissive) else l.f3("uEmissive", 0f, 0f, 0f)
        l.f("uRoughMul", rough); l.f("uAlpha", alpha)
        l.f4("uPanel", 1.2f, 0f, 0f, 0f); l.f4("uDecalB", 0f, 0f, 0f, 0f)
    }

    private fun camRelWorld(l: ShaderProgram) = l.f3("uCamWorld", camX.toFloat(), camY.toFloat(), camZ.toFloat())

    private fun drawGround(l: ShaderProgram, cx: LaunchComplex, far: Boolean) {
        camRelWorld(l)
        val tx = (-camX).toFloat(); val ty = (-camY).toFloat(); val tz = (-camZ).toFloat()
        Mat.trs(m, tx, ty, tz)
        terrain?.let { setDraw(l, m, MatType.TERRAIN); it.draw() }
        setDraw(l, m, MatType.METAL); cx.structures.draw()
        if (far) return
        setDraw(l, m, MatType.PAD); cx.pad.draw()
        setDraw(l, m, MatType.PLAIN, emissive = lampEm); cx.lamps.draw()
        val blink = if ((animTime % 1.6f) < 0.25f) 1f else 0.04f
        beaconEm[0] = 14f * blink; beaconEm[1] = 1.2f * blink; beaconEm[2] = 0.5f * blink
        setDraw(l, m, MatType.PLAIN, emissive = beaconEm); cx.beacons.draw()
        for ((h, mesh) in cx.arms) {
            Mat.trs(m2, cx.armPivotX + tx, h + ty, tz, ry = armAngleFor(h))
            setDraw(l, m2, MatType.METAL); mesh.draw()
        }
    }
    private val lampEm = floatArrayOf(22f, 20f, 16f)
    private val beaconEm = FloatArray(3)

    private fun armAngleFor(h: Float): Float {
        return 80f * armProgress * (0.85f + 0.15f * (h / max(1f, complexHeight)))
    }
    private var armProgress = 0f
    private val complexHeight get() = rocket?.totalLength ?: 20f

    /** rocketM = T(base - cam) * Rz(-tilt) about the pivot, base at the current stage's bottom. */
    private fun rocketBaseMatrix(rk: RocketModel) {
        val L = remainingLength(rk)
        val c = L * 0.4f
        val tx = (sLaneX - camX).toFloat(); val ty = (sAlt + c - camY).toFloat(); val tz = (-camZ).toFloat()
        Mat.trs(m3, tx, ty, tz, rz = -Math.toDegrees(sTilt.toDouble()).toFloat())
        Mat.trs(rocketM, 0f, -c, 0f, pre = m3)
    }

    private fun drawRocket(l: ShaderProgram, rk: RocketModel) {
        rocketBaseMatrix(rk)
        val st = sStage.coerceIn(0, rk.stageBase.size - 1)
        l.f3("uCamWorld", (camX - sLaneX).toFloat(), (camY - sAlt).toFloat(), camZ.toFloat())
        for (p in rk.parts) {
            val g = p.group
            if (g >= 0 && g < RocketModel.PAYLOAD && g < st) continue
            if (g < 0 && !sBoosters) continue
            when {
                g == RocketModel.PAYLOAD -> Mat.trs(m, 0f, -rk.stageBase[st], 0f, pre = rocketM)
                g < 0 -> { Mat.trs(m2, 0f, -rk.stageBase[st], 0f, pre = rocketM); Mat.mul(m, m2, p.local) }
                else -> Mat.trs(m, 0f, rk.stageBase[g] - rk.stageBase[st], 0f, pre = rocketM)
            }
            drawPart(l, p, glowFor(g, st))
        }
    }

    private fun glowFor(g: Int, st: Int): Float = when {
        g == st -> sGlow * 2.2f
        g < 0 -> if (sBoosterBurn || sPhase == Phase.COUNTDOWN) sGlow * 2.2f else 0f
        else -> 0f
    }

    private fun drawPart(l: ShaderProgram, p: Part, heat: Float) {
        setDraw(l, m, p.mat, p.roughMul)
        l.f4("uPanel", p.panel[0], p.panel[1], p.panel[2], p.panel[3])
        val d = p.decal
        if (d != null) { l.f4("uDecalA", d[0], d[1], d[2], d[3]); l.f4("uDecalB", p.decalRow.toFloat(), 1f, 0f, 0f) }
        else l.f4("uDecalB", 0f, 0f, if (p.engine) heat else 0f, 0f)
        p.mesh.draw()
    }

    private fun drawSpent(l: ShaderProgram, rk: RocketModel) {
        for (i in 0 until spN) {
            val g = spG[i]
            val rel = (sAlt - camY).toFloat()
            var y = spY[i]
            if (g >= 0 && g + 1 < rk.stageBase.size) y += spec.stages[g].length - (rk.stageBase[g + 1] - rk.stageBase[g])
            Mat.trs(m3, (spX[i] - camX).toFloat(), y + rel, (-camZ).toFloat(), rz = -Math.toDegrees(spRot[i].toDouble()).toFloat())
            l.f3("uCamWorld", (camX - spX[i]).toFloat(), (camY - sAlt - y).toFloat(), camZ.toFloat())
            for (p in rk.parts) {
                if (p.group != g) continue
                if (g < 0) Mat.mul(m, m3, p.local) else System.arraycopy(m3, 0, m, 0, 16)
                drawPart(l, p, 0f)
            }
        }
    }

    private fun drawEntities(l: ShaderProgram) {
        val em = ents ?: return
        val rel = (sAlt - camY).toFloat()
        camRelWorld(l)
        for (i in 0 until eN) {
            val k = EntityKind.entries[eKind[i]]
            val x = (eX[i] - camX).toFloat(); val y = eY[i] + rel; val z = (eZ[i] - camZ).toFloat()
            when (k) {
                EntityKind.BIRD -> {
                    val face = if (eVx[i] >= 0) 0f else 180f
                    Mat.trs(m2, x, y, z, ry = face)
                    System.arraycopy(m2, 0, m, 0, 16); setDraw(l, m, MatType.PLAIN); em.birdBody.draw()
                    val flap = 35f * sin(animTime * 11f + eSeed[i] * 20f)
                    Mat.trs(m, 0f, 0.05f, 0f, rx = 90f - flap, pre = m2); setDraw(l, m, MatType.PLAIN); em.birdWing.draw()
                    Mat.trs(m, 0f, 0.05f, 0f, rx = -90f + flap, pre = m2); setDraw(l, m, MatType.PLAIN); em.birdWing.draw()
                }
                EntityKind.PLANE -> {
                    Mat.trs(m, x, y, z, 0.85f, 0.85f, 0.85f, ry = if (eVx[i] > 0) 180f else 0f, rx = 4f * sin(animTime + eSeed[i] * 9f))
                    setDraw(l, m, MatType.METAL); em.plane.draw()
                }
                EntityKind.BALLOON -> {
                    Mat.trs(m, x, y, z, rz = 4f * sin(animTime * 0.8f + eSeed[i] * 6f)); setDraw(l, m, MatType.PLAIN); em.balloon.draw()
                }
                EntityKind.SATELLITE -> {
                    Mat.trs(m2, x, y, z, 0.7f, 0.7f, 0.7f, ry = Math.toDegrees(eRot[i].toDouble()).toFloat() * 0.5f, rx = 20f)
                    System.arraycopy(m2, 0, m, 0, 16); setDraw(l, m, MatType.METAL); em.satellite.draw()
                    for (si in 0..1) { val s = if (si == 0) -1f else 1f; Mat.trs(m, s * 4.2f, 0f, 0f, rx = 25f, pre = m2); setDraw(l, m, MatType.METAL); em.panel.draw() }
                }
                EntityKind.DEBRIS -> {
                    val r = Math.toDegrees(eRot[i].toDouble()).toFloat()
                    Mat.trs(m, x, y, z, rz = r, rx = r * 0.7f, ry = eSeed[i] * 360f); setDraw(l, m, MatType.METAL); em.debris.draw()
                }
                EntityKind.METEOR -> {
                    val r = Math.toDegrees(eRot[i].toDouble()).toFloat()
                    Mat.trs(m, x, y, z, 2.2f, 2.2f, 2.2f, rz = r, rx = r * 0.6f)
                    meteorEm[0] = 1.6f; meteorEm[1] = 0.45f; meteorEm[2] = 0.12f
                    setDraw(l, m, MatType.PLAIN, emissive = meteorEm)
                    (if (eSeed[i] > 0.5f) em.rock else em.rock2).draw()
                }
                EntityKind.ASTEROID -> {
                    // cold, dark belt asteroid (no entry glow), lumpy and slowly tumbling
                    val r = Math.toDegrees(eRot[i].toDouble()).toFloat()
                    val sc = 2.2f + 1.4f * eSeed[i]
                    Mat.trs(m, x, y, z, sc, sc * (0.7f + 0.3f * eSeed[i]), sc * 0.85f, rz = r, rx = r * 0.4f, ry = eSeed[i] * 360f)
                    setDraw(l, m, MatType.PLAIN, tint = 0.75f)
                    (if (eSeed[i] > 0.5f) em.rock2 else em.rock).draw()
                }
            }
        }
    }
    private val meteorEm = FloatArray(3)

    // ------------------------------------------------------------------ shadows

    private fun renderShadows(rk: RocketModel, cx: LaunchComplex) {
        val sp = shadowProg ?: return
        val L = remainingLength(rk)
        // centre on the pad, or on the rocket once it's climbing
        val onPad = sAlt < 300
        val cxw = if (onPad) 0f else sLaneX
        val cyw = if (onPad) L * 0.4f else (sAlt + L * 0.4).toFloat()
        val R = if (onPad) max(48f, L * 1.25f) else max(25f, L * 0.9f)
        // light rotation (looking along -sun)
        val upx: Float; val upy: Float; val upz: Float
        if (abs(sunDir[1]) > 0.95f) { upx = 0f; upy = 0f; upz = 1f } else { upx = 0f; upy = 1f; upz = 0f }
        Mat.lookAt(lightRot, 0f, 0f, 0f, -sunDir[0], -sunDir[1], -sunDir[2], upx, upy, upz)
        // stabilise: snap the centre to whole shadow-map texels in light space (no shimmering edges as things move)
        Mat.transformPoint(lightRot, cxw, cyw, 0f, tmp3)
        val texel = 2f * R / pipe.shadowSize
        val sx = floor(tmp3[0] / texel) * texel; val sy = floor(tmp3[1] / texel) * texel
        Mat.ortho(lightProj, sx - R, sx + R, sy - R, sy + R, -tmp3[2] - 600f, -tmp3[2] + 600f)
        Mat.mul(shadowVP, lightProj, lightRot)            // pad-frame world -> light clip
        Mat.trs(m3, camX.toFloat(), camY.toFloat(), camZ.toFloat())
        Mat.mul(shadowVPCam, shadowVP, m3)                // camera-relative -> light clip

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pipe.shadowFbo)
        GLES30.glViewport(0, 0, pipe.shadowSize, pipe.shadowSize)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glDepthFunc(GLES30.GL_LEQUAL); GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(1.5f, 2.5f)
        sp.use()
        val tx = (-camX).toFloat(); val ty = (-camY).toFloat(); val tz = (-camZ).toFloat()
        // static complex
        Mat.trs(m, tx, ty, tz)
        Mat.mul(lightMVP, shadowVPCam, m); sp.m4("uLightMVP", lightMVP); cx.structures.draw()
        for ((h, mesh) in cx.arms) { Mat.trs(m2, cx.armPivotX + tx, h + ty, tz, ry = armAngleFor(h)); Mat.mul(lightMVP, shadowVPCam, m2); sp.m4("uLightMVP", lightMVP); mesh.draw() }
        // rocket
        if (!sExploded) {
            rocketBaseMatrix(rk)
            val st = sStage.coerceIn(0, rk.stageBase.size - 1)
            for (p in rk.parts) {
                val g = p.group
                if (g >= 0 && g < RocketModel.PAYLOAD && g < st) continue
                if (g < 0 && !sBoosters) continue
                when {
                    g == RocketModel.PAYLOAD -> Mat.trs(m, 0f, -rk.stageBase[st], 0f, pre = rocketM)
                    g < 0 -> { Mat.trs(m2, 0f, -rk.stageBase[st], 0f, pre = rocketM); Mat.mul(m, m2, p.local) }
                    else -> Mat.trs(m, 0f, rk.stageBase[g] - rk.stageBase[st], 0f, pre = rocketM)
                }
                Mat.mul(lightMVP, shadowVPCam, m); sp.m4("uLightMVP", lightMVP); p.mesh.draw()
            }
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    // ------------------------------------------------------------------ plumes

    private fun nozzleWorld(rk: RocketModel, nx: Float, ny: Float, nz: Float, out: FloatArray) {
        // flight-frame position (x includes the lane offset, y relative to the rocket base)
        val L = remainingLength(rk); val c = L * 0.4f
        val ct = cos(sTilt); val st = sin(sTilt)
        out[0] = sLaneX + nx * ct + (ny - c) * st
        out[1] = c + (-nx * st + (ny - c) * ct)
        out[2] = nz
    }

    private fun drawPlumes(rk: RocketModel) {
        val p = plumeProg ?: return
        val core = plumeCore ?: return; val outer = plumeOuter ?: return
        val glow = sGlow
        if (glow < 0.02f) { flamePos[0] = 0f; flamePos[1] = -1e4f; flamePos[2] = 0f; return }
        val a = atm()
        val solid = !spec.has("P_LIQUID")
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        p.use()
        p.m4("uVP", vpN)
        p.f("uTime", animTime % 1000f); p.f("uOutScale", pipe.outScale)
        rocketBaseMatrix(rk)
        val st = sStage.coerceIn(0, rk.nozzles.size - 1)
        val upper = st > 0
        val vac = 1f - a.coerceIn(0f, 1f)
        fun draw(nx: Float, nz: Float, r: Float, booster: Boolean, local: FloatArray?) {
            val len = r * ((if (booster || solid) 9f else 7f) + 6f * glow) * (1f + 1.8f * vac)
            if (local != null) Mat.mul(m2, rocketM, local) else System.arraycopy(rocketM, 0, m2, 0, 16)
            Mat.trs(m, nx, 0f, nz, pre = m2)
            p.m4("uModel", m)
            p.f("uLen", len); p.f("uRad", r * 0.95f); p.f("uExpand", 0.15f + 3.2f * vac)
            p.f("uDiamonds", if (!solid && !booster && a > 0.25f) 1.2f else 0f)
            p.f("uSeed", nx * 13f + nz * 7f)
            val inten = glow * (0.55f + 0.45f * a) * (if (vac > 0.9f) 0.6f else 1f)
            if (booster || solid) {
                p.f3("uCore", 7f, 6.2f, 4.6f); p.f3("uOuter", 3.2f, 1.7f, 0.6f)
            } else if (upper) {
                p.f3("uCore", 3.6f, 4.2f, 6.5f); p.f3("uOuter", 0.6f, 0.9f, 2.6f)
            } else {
                p.f3("uCore", 6.5f, 5.2f, 3.2f); p.f3("uOuter", 3.0f, 1.25f, 0.35f)
            }
            p.f("uIntensity", inten * 0.6f); outer.draw()
            Mat.trs(m3, 0f, 0f, 0f, 0.55f, 0.75f, 0.55f); Mat.mul(m2, m, m3)
            p.m4("uModel", m2)
            p.f("uIntensity", inten * 1.2f); core.draw()
        }
        val noz = rk.nozzles[st]
        for (n in noz) draw(n.x, n.z, n.radius, false, null)
        if (sBoosters && (sBoosterBurn || sPhase == Phase.COUNTDOWN)) for (o in rk.boosterOffsets) draw(0f, 0f, rk.boosterNozzleR, true, o)
        // flame light position: just below the nozzles (camera-relative)
        nozzleWorld(rk, 0f, -2.5f, 0f, tmp3)
        flamePos[0] = (tmp3[0] - camX).toFloat(); flamePos[1] = (tmp3[1] + sAlt - camY).toFloat(); flamePos[2] = (tmp3[2] - camZ).toFloat()
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    // ------------------------------------------------------------------ particles

    private fun handleFx(rk: RocketModel) {
        val P = particles
        for (f in fxLocal) when (f) {
            is Fx.Liftoff -> repeat(70) {
                val dir = if (P.rnd() < 0.5f) -1f else 1f
                P.emit(Particles.SMOKE, Particles.GROUND, dir * P.rnd(2f, 10f), 0.5f, P.rnd(-3f, 3f), dir * P.rnd(12f, 30f), P.rnd(1f, 6f), P.rnd(-5f, 5f),
                    P.rnd(6f, 10f), P.rnd(3f, 5f), P.rnd(12f, 22f), 0.93f, 0.93f, 0.92f, 0.65f, 0.55f, 1.2f)
            }
            is Fx.StageSep -> {
                repeat(26) {
                    val a = P.rnd() * 6.283f
                    P.emit(Particles.SMOKE, Particles.FLIGHT, sLaneX + cos(a) * 0.9f, 0f, sin(a) * 0.9f, cos(a) * P.rnd(4f, 9f), P.rnd(-2f, 2f), sin(a) * P.rnd(4f, 9f),
                        P.rnd(1.5f, 3f), 0.6f, P.rnd(3f, 5f), 0.9f, 0.9f, 0.92f, 0.6f * max(0.15f, atm()), 1.2f)
                }
                repeat(30) {
                    val a = P.rnd() * 6.283f
                    P.emit(Particles.SPARK, Particles.FLIGHT, sLaneX, 0f, 0f, cos(a) * P.rnd(6f, 18f), P.rnd(-6f, 6f), sin(a) * P.rnd(6f, 18f),
                        P.rnd(0.3f, 0.8f), 0.12f, 0.05f, 2.5f, 1.6f, 0.7f, 1f, 0.4f)
                }
            }
            is Fx.BoosterSep -> repeat(24) {
                val s = if (P.rnd() < 0.5f) -1f else 1f
                P.emit(Particles.SMOKE, Particles.FLIGHT, sLaneX + s * 1.6f, P.rnd(0f, 5f), 0f, s * P.rnd(3f, 8f), P.rnd(-2f, 2f), P.rnd(-2f, 2f),
                    P.rnd(1.5f, 3f), 0.6f, P.rnd(2.5f, 4f), 0.9f, 0.9f, 0.9f, 0.6f * max(0.15f, atm()), 1.2f)
            }
            is Fx.Hit -> {
                repeat(40) {
                    P.emit(Particles.SPARK, Particles.FLIGHT, f.x, f.y, 0f, P.rnd(-15f, 15f), P.rnd(-15f, 15f), P.rnd(-15f, 15f),
                        P.rnd(0.3f, 0.9f), 0.15f, 0.05f, 3f, 1.8f, 0.6f, 1f, 0.5f)
                }
                repeat(10) {
                    P.emit(Particles.SMOKE, Particles.FLIGHT, f.x, f.y, 0f, P.rnd(-3f, 3f), P.rnd(-3f, 3f), P.rnd(-3f, 3f),
                        P.rnd(1f, 2f), 0.8f, 3f, 0.25f, 0.24f, 0.23f, 0.7f * max(0.2f, atm()), 1f)
                }
            }
            is Fx.Explode -> {
                flash = 2.5f
                repeat(140) {
                    val a = P.rnd() * 6.283f; val b = P.rnd(-1f, 1f); val sp = P.rnd(4f, 26f); val rr = sqrt(1 - b * b)
                    P.emit(Particles.FLAME, Particles.FLIGHT, f.x, f.y, 0f, cos(a) * rr * sp, b * sp, sin(a) * rr * sp,
                        P.rnd(0.5f, 1.4f), P.rnd(2f, 4f), P.rnd(5f, 9f), 3.2f, 1.4f, 0.35f, 1f, 1.5f)
                }
                repeat(90) {
                    P.emit(Particles.SPARK, Particles.FLIGHT, f.x, f.y, 0f, P.rnd(-35f, 35f), P.rnd(-35f, 35f), P.rnd(-35f, 35f),
                        P.rnd(0.6f, 1.8f), 0.2f, 0.06f, 3f, 1.6f, 0.5f, 1f, 0.3f)
                }
                repeat(60) {
                    P.emit(Particles.SMOKE, Particles.FLIGHT, f.x + P.rnd(-3f, 3f), f.y + P.rnd(-3f, 3f), P.rnd(-3f, 3f), P.rnd(-6f, 6f), P.rnd(-4f, 6f), P.rnd(-6f, 6f),
                        P.rnd(3f, 6f), P.rnd(3f, 5f), P.rnd(9f, 15f), 0.12f, 0.11f, 0.1f, 0.85f * max(0.25f, atm()), 0.6f, 0.8f)
                }
            }
        }
        fxLocal.clear()
    }

    private fun emitParticles(rk: RocketModel, dt: Float) {
        if (dt <= 0f) return
        val P = particles
        if (sExploded) return
        val glow = sGlow
        val a = atm()
        val st = sStage.coerceIn(0, rk.nozzles.size - 1)
        val solid = !spec.has("P_LIQUID")
        val ct = cos(sTilt); val sn = sin(sTilt)
        val exX = -sn; val exY = -ct     // exhaust direction in the flight frame
        // ground steam during ignition and the first seconds of the climb
        if (glow > 0.05f && sAlt < 200) {
            emitAcc[0] += dt * 45f * glow
            while (emitAcc[0] >= 1f) {
                emitAcc[0] -= 1f
                val dir = if (P.rnd() < 0.5f) -1f else 1f
                P.emit(Particles.SMOKE, Particles.GROUND, dir * P.rnd(10f, 15f), P.rnd(-0.5f, 1.5f), P.rnd(-2f, 2f), dir * P.rnd(14f, 30f), P.rnd(2f, 7f), P.rnd(-4f, 4f),
                    P.rnd(6f, 11f), P.rnd(3f, 5f), P.rnd(14f, 26f), 0.94f, 0.94f, 0.93f, 0.6f, 0.5f, 1.3f)
            }
        }
        if (glow < 0.03f) return
        fun nozzle(nx: Float, nz: Float, r: Float, booster: Boolean) {
            nozzleWorld(rk, nx, 0f, nz, tmp3b)
            val px = tmp3b[0]; val py = tmp3b[1]; val pz = tmp3b[2]
            // flame licks (additive)
            val nFlame = (dt * 70f * glow).let { it + P.rnd() }.toInt()
            for (i in 0 until nFlame) {
                val sp = P.rnd(35f, 70f)
                P.emit(Particles.FLAME, Particles.FLIGHT, px + P.rnd(-0.3f, 0.3f) * r, py, pz + P.rnd(-0.3f, 0.3f) * r,
                    exX * sp + P.rnd(-3f, 3f), exY * sp, P.rnd(-3f, 3f), P.rnd(0.12f, 0.3f), r * 1.3f, r * (0.6f + 2f * (1f - a)),
                    if (booster || solid) 2.6f else 2.2f, if (booster || solid) 1.9f else 1.1f, if (booster || solid) 1.1f else 0.35f, 0.8f * glow, 0.3f)
            }
            // exhaust smoke trail (alpha) — only while there is air
            if (a > 0.004f) {
                val rate = (if (booster || solid) 40f else 22f) * glow * (0.3f + 0.7f * sqrt(a))
                val nSmoke = (dt * rate + P.rnd()).toInt()
                val c = if (booster || solid) 0.92f else 0.86f
                for (i in 0 until nSmoke) {
                    val sp = P.rnd(18f, 32f)
                    val grow = 1f + 2.5f * (1f - a)
                    P.emit(Particles.SMOKE, Particles.FLIGHT, px + P.rnd(-0.4f, 0.4f) * r, py - r * 2.5f, pz + P.rnd(-0.4f, 0.4f) * r,
                        exX * sp + P.rnd(-2f, 2f), exY * sp, P.rnd(-2f, 2f), P.rnd(2.5f, 5f), r * 1.6f, r * P.rnd(5f, 8f) * grow,
                        c, c, c * 1.01f, (if (booster || solid) 0.7f else 0.5f) * (0.35f + 0.65f * a.pow(0.3f)), 1.4f, 0.3f)
                }
            }
        }
        val noz = rk.nozzles[st]
        if (noz.size > 3) nozzle(0f, 0f, spec.stages[0].radius * 0.85f, false) else for (n in noz) nozzle(n.x, n.z, n.radius, false)
        if (sBoosters && (sBoosterBurn || sPhase == Phase.COUNTDOWN)) {
            for (o in rk.boosterOffsets) nozzle(o[12], o[14], rk.boosterNozzleR, true)
        }
        // re-entry style heating glow around the nose when the hull is hot
        if (sHeat > 0.35f) {
            val n = (dt * 60f * (sHeat - 0.35f) + P.rnd()).toInt()
            for (i in 0 until n) {
                nozzleWorld(rk, P.rnd(-0.6f, 0.6f), remainingLength(rk) * P.rnd(0.6f, 1f), 0f, tmp3b)
                P.emit(Particles.FLAME, Particles.FLIGHT, tmp3b[0], tmp3b[1], P.rnd(-0.6f, 0.6f), P.rnd(-2f, 2f), -P.rnd(10f, 25f), 0f,
                    P.rnd(0.1f, 0.25f), 0.7f, 0.3f, 2.4f, 0.8f, 0.3f, sHeat, 0.5f)
            }
        }
        // meteor trails
        for (i in 0 until eN) if (eKind[i] == EntityKind.METEOR.ordinal && P.rnd() < dt * 50f) {
            P.emit(Particles.FLAME, Particles.FLIGHT, eX[i], eY[i] + 1.5f, eZ[i], P.rnd(-1f, 1f), P.rnd(2f, 6f), 0f, P.rnd(0.3f, 0.6f), 2.2f, 0.6f, 2.6f, 1.0f, 0.3f, 0.9f, 0.5f)
        }
    }

    private fun drawBillboards() {
        val p = bbProg ?: return
        batch.begin()
        val rx = viewRot[0]; val ry = viewRot[4]; val rz = viewRot[8]
        val ux = viewRot[1]; val uy = viewRot[5]; val uz = viewRot[9]
        val fx = -viewRot[2]; val fy = -viewRot[6]; val fz = -viewRot[10]
        val rel = (sAlt - camY).toFloat()
        val cxf = camX.toFloat(); val czf = camZ.toFloat()
        val P = particles
        for (i in 0 until P.count) {
            val x = P.x[i] - cxf
            val y = if (P.frame[i] == Particles.FLIGHT) P.y[i] + rel else (P.y[i] - camY).toFloat()
            val z = P.z[i] - czf
            val depth = x * fx + y * fy + z * fz
            val s = P.size(i)
            if (depth < -s || depth > NEAR_FAR) continue
            batch.stage(x, y, z, s, P.r[i], P.g[i], P.b[i], P.alpha(i), P.kind[i], P.rot[i], P.t(i), P.seed[i], max(depth, 0.06f))
        }
        // cloud puffs; also measure how deep the camera is inside a cloud (for the whiteout fade)
        var wo = 0f
        val puffs = quality.cloudPuffs
        for (c in 0 until cN) {
            val fadeAge = smooth(0f, 1.5f, cAge[c])
            val yy = cY[c]
            // fade in near the spawn edge and out near the despawn edge: clouds never pop in or out
            val fadeEdge = (1f - smooth(70f, World.SPAWN_Y + 40f, yy)) * (1f - smooth(-80f, -190f, yy))
            val alpha = 0.85f * fadeAge * fadeEdge
            if (alpha < 0.01f) continue
            for (k in 0 until puffs) {
                val h1 = frac(cSeed[c] * 91.7f + k * 7.31f); val h2 = frac(cSeed[c] * 53.3f + k * 3.17f); val h3 = frac(cSeed[c] * 17.9f + k * 5.71f)
                val size = cSize[c] * (0.42f + 0.3f * frac(h1 * 13.7f + h3))
                val x = cX[c] + (h1 - 0.5f) * cSize[c] * 1.4f - cxf
                val y = yy + (h2 - 0.5f) * cSize[c] * 0.35f + rel
                val z = cZ[c] + (h3 - 0.5f) * cSize[c] * 0.6f - czf
                val depth = x * fx + y * fy + z * fz
                val d = sqrt(x * x + y * y + z * z) / size
                wo = max(wo, (1f - smooth(0.35f, 1.0f, d)) * alpha)
                if (depth < -size || depth > NEAR_FAR) continue
                batch.stage(x, y, z, size, 1f, 1f, 1f, alpha, Particles.CLOUD, h1 * 6.28f, 0.5f, frac(cSeed[c] + k * 0.137f), max(depth, 0.06f))
            }
        }
        targetWhiteout = wo
        val n = batch.finish()
        if (n == 0) return
        bbBuf.position(0); bbBuf.put(batch.data, 0, n * 12); bbBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bbInstVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.capacity * 48, null, GLES30.GL_STREAM_DRAW)   // orphan: no GPU stall
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 48, bbBuf)
        p.use()
        p.m4("uVP", vpN)
        p.f3("uRight", rx, ry, rz); p.f3("uUp", ux, uy, uz); p.f3("uFwd", fx, fy, fz)
        Mat.transformDir(viewRot, sunDir[0], sunDir[1], sunDir[2], tmp3)
        p.f3("uSunV", tmp3); p.f3("uSunCol", sunCol[0] * 0.22f, sunCol[1] * 0.22f, sunCol[2] * 0.22f)
        p.f3("uAmb", skyAmb[0] * 0.9f + groundAmb[0] * 0.3f, skyAmb[1] * 0.9f + groundAmb[1] * 0.3f, skyAmb[2] * 0.9f + groundAmb[2] * 0.3f)
        p.f("uOutScale", pipe.outScale)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(bbVao)
        if (batch.alphaCount > 0) {
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)    // premultiplied alpha
            bindInstanceAttribs(0)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, batch.alphaCount)
        }
        if (batch.addCount > 0) {
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            bindInstanceAttribs(batch.alphaCount)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, batch.addCount)
        }
        bindInstanceAttribs(0)
        GLES30.glBindVertexArray(0)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }
    private var targetWhiteout = 0f
    private fun frac(v: Float) = v - floor(v)

    // ------------------------------------------------------------------ post

    private fun renderBloom() {
        val n = pipe.bloomLevels
        if (n == 0) return
        val d = downProg ?: return; val u = upProg ?: return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_BLEND)
        d.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        d.i("uTex", 0)
        d.f("uThreshold", 3.0f / exposure); d.f("uInScale", 1f / pipe.outScale)
        var srcTex = pipe.sceneTex; var sw = pipe.sceneW; var sh = pipe.sceneH
        for (i in 0 until n) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pipe.bloomFbo[i])
            GLES30.glViewport(0, 0, pipe.bloomW[i], pipe.bloomH[i])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            d.f2("uTexel", 1f / sw, 1f / sh)
            d.f("uPrefilter", if (i == 0) 1f else 0f)
            if (i == 1) d.f("uInScale", 1f)
            pipe.drawQuad()
            srcTex = pipe.bloomTex[i]; sw = pipe.bloomW[i]; sh = pipe.bloomH[i]
        }
        u.use(); u.i("uTex", 0); u.f("uRadius", 1f)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        for (i in n - 1 downTo 1) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, pipe.bloomFbo[i - 1])
            GLES30.glViewport(0, 0, pipe.bloomW[i - 1], pipe.bloomH[i - 1])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pipe.bloomTex[i])
            u.f2("uTexel", 1f / pipe.bloomW[i], 1f / pipe.bloomH[i])
            pipe.drawQuad()
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun renderComposite(rk: RocketModel, dt: Float) {
        val c = compProg ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfW, surfH)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_BLEND)
        c.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pipe.sceneTex); c.i("uScene", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (pipe.bloomLevels > 0) pipe.bloomTex[0] else pipe.sceneTex); c.i("uBloom", 1)
        c.f("uBloomOn", if (pipe.bloomLevels > 0) 1f else 0f)
        c.f("uBloomStrength", 0.07f)
        c.f("uInScale", 1f / pipe.outScale)
        c.f("uExposure", exposure)
        // heat shimmer along the exhaust (screen space), only in air
        var shimmer = 0f
        if (quality.shimmer && !sExploded && sGlow > 0.05f) {
            nozzleWorld(rk, 0f, -0.5f, 0f, tmp3b)
            val ax = (tmp3b[0] - camX).toFloat(); val ay = (tmp3b[1] + sAlt - camY).toFloat(); val az = (tmp3b[2] - camZ).toFloat()
            val len = remainingLength(rk) * 0.9f + 8f
            val bx = ax - sin(sTilt) * len; val by = ay - cos(sTilt) * len
            if (Mat.project(vpN, ax, ay, az, tmp3) ) {
                val u0 = tmp3[0] * 0.5f + 0.5f; val v0 = tmp3[1] * 0.5f + 0.5f
                if (Mat.project(vpN, bx, by, az, tmp3)) {
                    c.f4("uShimmerSeg", u0, v0, tmp3[0] * 0.5f + 0.5f, tmp3[1] * 0.5f + 0.5f)
                    shimmer = sGlow * atm().coerceIn(0f, 1f)
                }
            }
        }
        c.f("uShimmer", shimmer)
        c.f("uTime", animTime % 100f)
        c.f("uAspect", surfW.toFloat() / max(1, surfH))
        whiteout += (targetWhiteout - whiteout) * (1f - exp(-dt * 9f))
        c.f("uWhiteout", whiteout * 0.9f)
        val wc = SUN_I * 0.16f
        c.f3("uWhiteCol", wc * sunCol[0] / SUN_I + skyAmb[0] * 0.5f, wc * sunCol[1] / SUN_I + skyAmb[1] * 0.5f, wc * sunCol[2] / SUN_I + skyAmb[2] * 0.5f)
        flash *= exp(-dt * 3.5f)
        c.f("uFlash", flash)
        c.f("uVignette", 0.22f)
        pipe.drawQuad()
        // arms retract smoothly once the countdown passes T-2.6
        val armTarget = if (sPhase == Phase.COUNTDOWN && sCountdown < 2.6f || sPhase == Phase.FLIGHT || sPhase == Phase.RESULTS) 1f else 0f
        armProgress += (armTarget - armProgress) * (1f - exp(-dt * 1.6f))
    }
}
