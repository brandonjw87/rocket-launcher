package com.brandonwallace.rocketlauncher.game

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Phase { MENU, HANGAR, TECH, COUNTDOWN, FLIGHT, RESULTS }
enum class Sfx { HIT, EXPLODE, STAGE, BEEP, LIFTOFF, ACHIEVE, CLICK, WIN, MILESTONE, RESEARCH, WHOOSH }

interface SaveStore {
    fun load(): String?
    fun save(data: String)
    /** Write synchronously (app going to the background). Defaults to [save]. */
    fun saveNow(data: String) = save(data)
}

/** What the results screen shows. Note: no interest field — it is folded into [performancePay]. */
data class FlightResult(
    val maxAlt: Double, val topSpeed: Double, val time: Double,
    val performancePay: Long,
    val milestones: List<Milestone>,
    val total: Long,
    val balanceAfter: Long,
    val records: List<String>, val achievements: List<Achievement>, val reason: String,
    /** The flight got past the Moon (the mission continues on to Mars). */
    val moonPassed: Boolean, val hits: Int,
    val marsReached: Boolean = false, val marsOrbit: Boolean = false, val marsLanded: Boolean = false,
)

data class HudState(
    val phase: Phase = Phase.MENU,
    val paused: Boolean = false,
    val countdown: Int = 0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,
    val vSpeed: Double = 0.0,
    val fuel: Float = 1f,
    val boosterFuel: Float = -1f,
    val stage: Int = 0,
    val stageCount: Int = 1,
    val hull: Float = 1f,
    val heat: Float = 0f,
    val zone: String = "",
    val warp: Float = 1f,
    val throttle: Float = 0f,
    /** Log-scale progress along the one mission path (Earth → Moon → Mars). */
    val missionProgress: Float = 0f,
    val banner: String = "",
    val bannerId: Int = 0,
    val hits: Int = 0,
    val ending: Boolean = false,
    val mach: Float = 0f,
    val results: FlightResult? = null,
    /** Coasting from here reaches Mars. */
    val marsBound: Boolean = false,
    /** While Mars-bound: will the remaining fuel allow capture / landing? */
    val arrivalNote: String = "",
    /** Deep-space nav computer is holding the engines off (on course). */
    val navCut: Boolean = false,
    val radar: List<Float> = emptyList(),
    val autoThrottling: Boolean = false,
    /** Autopilot is steering around a hazard right now. */
    val evading: Boolean = false,
    /** The flight has passed the Moon. */
    val moonPassed: Boolean = false,
)

/** Visual-only events the renderer turns into particles. */
sealed class Fx {
    data class StageSep(val index: Int) : Fx()
    data object BoosterSep : Fx()
    data class Hit(val x: Float, val y: Float) : Fx()
    data class Explode(val x: Float, val y: Float) : Fx()
    data object Liftoff : Fx()
}

/** A separated stage or booster tumbling away. group >= 0: stage index, group < 0: booster -(group+1). */
class SpentPart(val group: Int, var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val spin: Float) {
    var age = 0f
    var px = x; var py = y; var prot = rot
}

class GameEngine(private val store: SaveStore) {
    companion object {
        /** Fixed simulation step (s). Rendering interpolates between steps. */
        const val FIXED_DT = 1f / 120f
        private const val MAX_STEPS_PER_FRAME = 12
    }

    val lock = Any()
    /** Last save string known to be on disk (null = nothing stored yet / unreadable). */
    private var lastStored: String? = store.load()
    private val _save = MutableStateFlow(SaveData.parse(lastStored))
    val save: StateFlow<SaveData> = _save
    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud

    var sfxListener: ((Sfx) -> Unit)? = null

    var phase = Phase.MENU; private set
    var paused = false; private set
    var spec = RocketSpec(_save.value.tech); private set
    var specVersion = 0; private set
    var flight: FlightState? = null; private set
    val world = World()
    val spent = ArrayList<SpentPart>()
    /** Fx produced since the renderer last drained it. */
    val fx = ArrayList<Fx>()

    // visual state read by the renderer (current fixed step + previous for interpolation)
    var time = 0.0; private set
    var countdown = 0f; private set
    var laneX = 0f; private set
    var tilt = 0f; private set
    var prevLaneX = 0f; private set
    var prevTilt = 0f; private set
    var prevAlt = 0.0; private set
    var scroll = 0f; private set
    var warp = 1.0; private set
    var shake = 0f; private set
    var exploded = false; private set
    var ending = false; private set
    var engineGlow = 0f; private set
    private var laneVx = 0f
    private var accumulator = 0f

    /** Money left unspent when LAUNCH was pressed: the only base of the hidden interest. */
    private var balanceAtLaunch = 0L
    /** Autopilot outputs for the current step (read by HUD/tests). */
    var autoThrottle = 0.0; private set
    var autoSteer = 0f; private set
    private var endTimer = 0f
    private var descendTimer = 0f
    private var hits = 0
    private var bannerText = ""
    private var bannerId = 0
    private var endReason = ""
    private var moonPassed = false
    private var marsReached = false
    private var marsOrbit = false
    private var marsLanded = false
    private val newAch = ArrayList<Achievement>()
    private val newMilestones = ArrayList<Milestone>()
    private var zoneIdx = 0
    private var results: FlightResult? = null
    private var hudTimer = 0f
    private val flightEvents = ArrayList<FlightEvent>()
    private val worldEvents = ArrayList<WorldEvent>()

    private val zones = listOf(
        2_500.0 to "Lower clouds",
        12_000.0 to "Stratosphere",
        50_000.0 to "Mesosphere — the sky turns black",
        100_000.0 to "🌌 KÁRMÁN LINE — YOU'RE IN SPACE!",
        400_000.0 to "🛰️ Orbital altitude (ISS height)",
        2_000_000.0 to "High Earth orbit",
        20_000_000.0 to "Deep space",
        300_000_000.0 to "🌕 Approaching the Moon",
        Physics.MOON_ARRIVAL_ALT to "🌕 LUNAR FLYBY — past the Moon, on to Mars!",
        6.0e8 to "🚀 TRANS-MARS INJECTION — leaving Earth's pull",
        1.5e10 to "☀️ Interplanetary cruise",
        3.0e10 to "🔴 Mars grows ahead",
        5.3e10 to "🔴 Mars approach — capture burn ahead",
    )

    fun zoneName(alt: Double): String = when {
        alt < 2_500 -> "Lower atmosphere"
        alt < 12_000 -> "Cloud layer"
        alt < 50_000 -> "Stratosphere"
        alt < 100_000 -> "Mesosphere"
        alt < 2_000_000 -> "Low Earth orbit"
        alt < 36_000_000 -> "High orbit"
        alt < Physics.EARTH_SOI -> "Cislunar space"
        alt < 5.0e10 -> "Interplanetary space"
        else -> "Mars approach"
    }

    // ---------------- UI commands (main thread) ----------------

    fun openHangar() = synchronized(lock) { phase = Phase.HANGAR; paused = false; resetVisuals(); sfx(Sfx.CLICK) }
    fun openTech() = synchronized(lock) { if (phase == Phase.RESULTS) resetVisuals(); phase = Phase.TECH; paused = false; sfx(Sfx.CLICK) }
    fun openMenu() = synchronized(lock) { phase = Phase.MENU; paused = false; resetVisuals() }

    fun launch() = synchronized(lock) {
        rebuildSpec()
        resetVisuals()
        // hazards are laid out deterministically per flight number: same rocket + same flight = same outcome
        world.reset(hazardSeed(_save.value.flights))
        balanceAtLaunch = _save.value.money   // money left unspent at launch
        phase = Phase.COUNTDOWN
        countdown = 3.6f
        paused = false
    }

    fun setPaused(p: Boolean) = synchronized(lock) { if (phase == Phase.FLIGHT || phase == Phase.COUNTDOWN) paused = p; publishHud() }

    /** App came back from background: the flight waits paused until the player resumes it. */
    fun onAppResumed() = synchronized(lock) { if ((phase == Phase.FLIGHT && !ending) || phase == Phase.COUNTDOWN) paused = true; accumulator = 0f; publishHud() }

    fun abortFlight() = synchronized(lock) {
        if (phase == Phase.FLIGHT && !ending) { endReason = "Flight aborted"; finishFlight() }
        else if (phase == Phase.COUNTDOWN) { phase = Phase.HANGAR; resetVisuals() }
        paused = false
    }

    fun research(id: String): Boolean = synchronized(lock) {
        var s = _save.value.research(id) ?: return false
        val node = TechTree.byId.getValue(id)
        if (Achievement.BRANCH !in s.achievements && TechTree.nodes.filter { it.branch == node.branch }.all { it.id in s.tech })
            s = s.copy(achievements = s.achievements + Achievement.BRANCH)
        if (Achievement.TREE !in s.achievements && TechTree.nodes.all { it.id in s.tech })
            s = s.copy(achievements = s.achievements + Achievement.TREE)
        commit(s)
        rebuildSpec()
        sfx(Sfx.RESEARCH)
        true
    }

    fun setSound(on: Boolean) = synchronized(lock) { commit(_save.value.copy(soundOn = on)) }
    fun setQuality(q: Int) = synchronized(lock) { commit(_save.value.copy(quality = q.coerceIn(0, 2))) }
    fun resetProgress() = synchronized(lock) {
        val s = _save.value
        commit(SaveData(soundOn = s.soundOn, quality = s.quality))
        rebuildSpec(); phase = Phase.MENU; resetVisuals()
    }

    /** Debug/test helper. */
    fun grantMoney(n: Long) = synchronized(lock) { commit(_save.value.copy(money = _save.value.money + n)) }

    private fun commit(s: SaveData) { _save.value = s; val d = s.serialize(); store.save(d); lastStored = d }

    /**
     * Synchronously persist the current save (called when the app pauses / goes to the background, and right after
     * a migration). Skips the write when nothing changed, and never replaces a missing/unreadable store with an
     * untouched default save.
     */
    fun persistNow() = synchronized(lock) {
        val d = _save.value.serialize()
        if (d == lastStored) return@synchronized
        if (lastStored == null && _save.value == SaveData()) return@synchronized
        store.saveNow(d); lastStored = d
    }
    private fun rebuildSpec() { spec = RocketSpec(_save.value.tech); specVersion++ }
    private fun sfx(s: Sfx) { if (_save.value.soundOn) sfxListener?.invoke(s) }
    private fun showBanner(t: String) { bannerText = t; bannerId++ }

    private fun resetVisuals() {
        flight = null; world.reset((System.nanoTime() and 0xffff).toInt()); spent.clear()
        laneX = 0f; laneVx = 0f; tilt = 0f; scroll = 0f; warp = 1.0; shake = 0f
        prevLaneX = 0f; prevTilt = 0f; prevAlt = 0.0
        exploded = false; ending = false; engineGlow = 0f
        results = null
    }

    // ---------------- simulation (GL thread) ----------------

    /**
     * Frame entry point: accumulates real frame time and runs whole fixed steps.
     * Returns the interpolation factor in [0,1) between the previous and current step.
     */
    fun advance(frameDt: Float): Float = synchronized(lock) {
        accumulator += frameDt.coerceIn(0f, 0.1f)
        var n = 0
        while (accumulator >= FIXED_DT && n < MAX_STEPS_PER_FRAME) { update(FIXED_DT); accumulator -= FIXED_DT; n++ }
        if (n == MAX_STEPS_PER_FRAME) accumulator = 0f  // never spiral after a hitch
        accumulator / FIXED_DT
    }

    /** One simulation step. Public for tests. */
    fun update(dtIn: Float) = synchronized(lock) {
        val dt = dtIn.coerceIn(0f, 0.05f)
        prevLaneX = laneX; prevTilt = tilt; prevAlt = flight?.alt ?: 0.0
        world.snapshot()
        for (p in spent) { p.px = p.x; p.py = p.y; p.prot = p.rot }
        if (!paused) {
            time += dt
            when (phase) {
                Phase.COUNTDOWN -> updateCountdown(dt)
                Phase.FLIGHT -> updateFlight(dt)
                else -> { engineGlow = 0f; shake = max(0f, shake - dt * 2f) }
            }
        }
        hudTimer -= dt
        if (hudTimer <= 0f) { hudTimer = 1f / 30f; publishHud() }
    }

    private fun updateCountdown(dt: Float) {
        val before = ceil(countdown).toInt()
        countdown -= dt
        val after = ceil(countdown).toInt()
        if (after != before && after in 1..3) sfx(Sfx.BEEP)
        engineGlow = if (countdown < 1.1f) ((1.1f - countdown) / 1.1f).coerceIn(0f, 1f) * 0.6f else 0f
        shake = engineGlow * 0.5f
        if (countdown <= 0f) beginFlight()
    }

    private fun beginFlight() {
        phase = Phase.FLIGHT
        flight = FlightState(spec)
        autoThrottle = 1.0; autoSteer = 0f
        endTimer = 0f; descendTimer = 0f; hits = 0
        endReason = ""; moonPassed = false; marsReached = false; marsOrbit = false; marsLanded = false; newAch.clear(); newMilestones.clear(); zoneIdx = 0
        sfx(Sfx.LIFTOFF)
        showBanner("🚀 LIFTOFF!")
    }

    private fun updateFlight(dt: Float) {
        val f = flight ?: return
        // ---- autopilot (no player input): throttle + hazard avoidance from the researched tech only
        autoThrottle = if (ending || exploded) 0.0 else Autopilot.throttle(spec, f)
        autoSteer = if (ending || exploded) 0f else Autopilot.steer(spec, world.entities, laneX)
        val holding = autoThrottle > 0.0

        val target = (autoSteer.coerceIn(-1f, 1f) * spec.maxTiltRad).toFloat()
        tilt += (target - tilt) * min(1f, dt * spec.steerResponse)
        f.tilt = tilt.toDouble()
        laneVx = sin(tilt) * 75f
        laneX += laneVx * dt
        if (laneX > World.LANE) { laneX = World.LANE; laneVx = 0f }
        if (laneX < -World.LANE) { laneX = -World.LANE; laneVx = 0f }

        val arrived = marsReached
        val targetAlt = Physics.MARS_ARRIVAL_ALT
        if (!arrived && !exploded) {
            val coastWarp = Physics.timeWarp(f.alt, Physics.MARS_WARP_CAP)
            val burning = holding && f.hasThrustAvailable()
            val target = if (burning) min(coastWarp, Physics.BURN_WARP_CAP) else coastWarp
            // drop instantly when a burn starts (fuel use stays controllable), ease back up when coasting
            warp = if (target <= warp) target else warp + (target - warp) * (1.0 - kotlin.math.exp(-dt * 1.2))
            val simDt = dt * warp
            val maxStep = if (f.alt < 160_000) 0.02 else min(30.0, 0.02 * warp)
            val n = ceil(simDt / maxStep).toInt().coerceIn(1, 300)
            val h = simDt / n
            for (i in 0 until n) {
                f.step(h, Autopilot.throttle(spec, f).takeIf { holding } ?: 0.0, flightEvents)
                if (f.alt >= targetAlt) break
            }
        }
        for (e in flightEvents) handleFlightEvent(e)
        flightEvents.clear()

        val vEff = abs(f.vy) * warp
        val comp = if (vEff < 80) vEff else min(260.0, 80 + 9 * sqrt(vEff - 80))
        scroll = (if (f.vy >= 0) comp else -comp).toFloat()
        engineGlow = (f.throttle * (if (f.thrustNow > 0) 1.0 else 0.0)).toFloat()

        val rocketLen = remainingLength()
        val radius = if (f.boostersAttached) 1.9f else 1.1f
        world.update(dt, scroll, f.alt, laneX, rocketLen, radius, !ending && !exploded && f.liftedOff, worldEvents)
        for (e in worldEvents) handleWorldEvent(e, f)
        worldEvents.clear()

        val rel = (f.accel.toFloat() + 9.8f).coerceIn(8f, 40f)
        for (p in spent) { p.age += dt; p.vy -= rel * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.spin * dt }
        spent.removeAll { it.age > 7f }

        val atm = Physics.density(f.alt) / 1.225
        val base = (engineGlow * (0.06 + 0.35 * atm * (if (f.alt < 400) 1.0 else 0.35)) + min(0.5, f.dynPressure / 60_000.0)).toFloat()
        shake = max(base, shake - dt * 2.2f)

        checkProgress(f)
        checkEnd(f, holding, dt)
    }

    fun remainingLength(): Float {
        val f = flight ?: return spec.stages.sumOf { it.length.toDouble() }.toFloat() + 3f
        var l = 3f
        for (i in f.stage until spec.stages.size) l += spec.stages[i].length
        return l
    }

    private fun handleFlightEvent(e: FlightEvent) {
        when (e) {
            is FlightEvent.StageSeparated -> {
                spent.add(SpentPart(e.index, laneX, -spec.stages[e.index].length, laneVx * 0.3f, -2f, tilt, (if (e.index % 2 == 0) 1 else -1) * 0.5f))
                fx.add(Fx.StageSep(e.index)); sfx(Sfx.STAGE); shake = max(shake, 0.6f)
                showBanner("Stage ${e.index + 1} separation")
            }
            FlightEvent.BoostersSeparated -> {
                for (b in 0 until spec.boosterCount) {
                    val side = if (b % 2 == 0) -1f else 1f
                    spent.add(SpentPart(-(b + 1), laneX, 0f, laneVx * 0.3f + side * 4f, -1f, tilt, side * 0.7f))
                }
                fx.add(Fx.BoosterSep); sfx(Sfx.STAGE); shake = max(shake, 0.5f)
                showBanner("Booster separation")
            }
            FlightEvent.Liftoff -> fx.add(Fx.Liftoff)
        }
    }

    private fun handleWorldEvent(e: WorldEvent, f: FlightState) {
        when (e) {
            is WorldEvent.Hit -> {
                f.hull -= e.damage
                hits++
                fx.add(Fx.Hit(e.x, e.y))
                shake = 1.2f
                sfx(Sfx.HIT)
                // a hit costs 3% of vertical speed; capped hard above the atmosphere so debris while coasting cannot burn the landing reserve
                f.vy -= kotlin.math.sign(f.vy) * min(abs(f.vy) * 0.03, if (f.alt > 160_000) 5.0 else 40.0)
            }
            is WorldEvent.NearMiss -> sfx(Sfx.WHOOSH)
        }
    }

    private fun unlock(a: Achievement) {
        if (a in _save.value.achievements || a in newAch) return
        newAch.add(a)
        showBanner("🏆 ${a.title}")
        sfx(Sfx.ACHIEVE)
    }

    private fun checkProgress(f: FlightState) {
        val zl = zones
        while (zoneIdx < zl.size && f.alt >= zl[zoneIdx].first) { showBanner(zl[zoneIdx].second); zoneIdx++ }
        if (!moonPassed && f.maxAlt >= Physics.MOON_ARRIVAL_ALT) {
            moonPassed = true
            unlock(Achievement.MOON_LANDING)   // "Lunar Flyby" badge (enum name kept for old saves)
        }
        for (m in Milestone.entries) {
            if (f.maxAlt >= m.altitude && m !in _save.value.milestones && m !in newMilestones) {
                newMilestones.add(m)
                showBanner("⭐ ${m.title}  +${formatMoney(m.bonus)}")
                sfx(Sfx.MILESTONE)
            }
        }
        val v = f.topSpeed
        if (v >= 343) unlock(Achievement.SOUND_BARRIER)
        if (v >= 5_000) unlock(Achievement.HYPERSONIC)
        if (v >= 11_000) unlock(Achievement.ESCAPE)
        if (f.stagesSeparated >= 3) unlock(Achievement.STAGER)
    }

    private fun checkEnd(f: FlightState, holding: Boolean, dt: Float) {
        if (ending) {
            endTimer -= dt
            if (endTimer <= 0f) finishFlight()
            return
        }
        when {
            f.hull <= 0 -> {
                exploded = true; ending = true; endTimer = 2.4f; endReason = "Rocket destroyed"
                fx.add(Fx.Explode(laneX, remainingLength() * 0.5f)); sfx(Sfx.EXPLODE); shake = 2f
                showBanner("💥 Hull failure!")
            }
            f.alt >= Physics.MARS_ARRIVAL_ALT -> arriveAtMars(f)
            f.liftedOff && f.alt <= 0.0 && f.vy <= 0 -> { ending = true; endTimer = 0.8f; endReason = "Crashed back to Earth" }
            else -> {
                val canBurn = holding && f.hasThrustAvailable()
                if (f.liftedOff && f.vy < 0 && !canBurn) descendTimer += dt else descendTimer = 0f
                if (descendTimer > 2.2f) {
                    ending = true; endTimer = 1.4f
                    endReason = if (f.hasThrustAvailable()) "Engines cut — apogee reached" else "Out of fuel — apogee reached"
                    showBanner("Apogee: ${formatAlt(f.maxAlt)}")
                }
            }
        }
    }

    private fun arrivalStatus(f: FlightState): String {
        val last = spec.stages.last().fuelMass
        val spare = f.stage < spec.stages.size - 1
        val fuel = f.remainingFuel()
        return when {
            !(spare || fuel >= RocketSpec.MARS_CAPTURE_FUEL_FRAC * last) -> "⚠ not enough fuel for Mars capture"
            spec.marsEntryReady && (spare || fuel >= spec.marsLandingFuelFrac * last) -> "landing fuel ✓"
            spec.marsEntryReady -> "capture fuel ✓ · not enough to land"
            else -> "capture fuel ✓ · landing needs PICA-X + parachute"
        }
    }

    private fun arriveAtMars(f: FlightState) {
        marsReached = true
        val a = MarsArrival.evaluate(spec, f.stage, f.remainingFuel())
        marsLanded = a.landed
        marsOrbit = a.orbit
        if (marsOrbit) unlock(Achievement.MARS_ORBIT)
        if (marsLanded) unlock(Achievement.MARS_LANDING)
        ending = true; endTimer = 4.5f
        val pct = (spec.marsLandingFuelFrac * 100).toInt()
        endReason = when {
            marsLanded -> "Landed on Mars!"
            marsOrbit && !spec.marsEntryReady -> "Captured into Mars orbit — research PICA-X Aeroshell and Supersonic Parachute to land"
            marsOrbit -> "Captured into Mars orbit — arrive with $pct% fuel or a spare stage to land"
            !spec.navAutoCut -> "Mars flyby — the autopilot burned every drop; research Deep-Space Navigation to save capture fuel"
            else -> "Mars flyby — no fuel left for the capture burn (needs 3% or a spare stage)"
        }
        showBanner(when { marsLanded -> "🔴 TOUCHDOWN ON MARS!"; marsOrbit -> "🔴 MARS ORBIT INSERTION"; else -> "🔴 Mars flyby" })
        sfx(Sfx.WIN)
    }

    private fun finishFlight() {
        val f = flight ?: return
        val s0 = _save.value
        if (s0.flights == 0) unlock(Achievement.FIRST_FLIGHT)
        if (hits >= 3 && f.hull > 0) unlock(Achievement.TOUGH)
        // hidden interest on the money left unspent at launch, folded into "Flight performance"
        val st = Economy.settle(balanceAtLaunch, f.maxAlt, s0.milestones, s0.interestCarry)
        val records = ArrayList<String>()
        if (f.maxAlt > s0.bestAltitude) records.add("Altitude")
        if (f.topSpeed > s0.bestSpeed) records.add("Speed")
        val newBalance = s0.money + st.total
        val r = FlightResult(
            f.maxAlt, f.topSpeed, f.time, st.performancePay, st.milestones, st.total, newBalance,
            records, newAch.toList(), endReason.ifEmpty { "Flight over" }, moonPassed, hits,
            marsReached, marsOrbit, marsLanded,
        )
        commit(
            s0.copy(
                money = newBalance,
                milestones = s0.milestones + st.milestones,
                bestAltitude = max(s0.bestAltitude, f.maxAlt),
                bestSpeed = max(s0.bestSpeed, f.topSpeed),
                flights = s0.flights + 1,
                achievements = s0.achievements + newAch,
                totalEarned = s0.totalEarned + st.total,
                marsOrbits = s0.marsOrbits + (if (marsOrbit) 1 else 0),
                marsLandings = s0.marsLandings + (if (marsLanded) 1 else 0),
                interestCarry = st.interestCarry,
            )
        )
        results = r
        phase = Phase.RESULTS
        ending = false
        engineGlow = 0f
        publishHud()
    }

    private fun publishHud() {
        val f = flight
        val inFlight = phase == Phase.FLIGHT && f != null
        _hud.value = HudState(
            phase = phase,
            paused = paused,
            countdown = ceil(countdown).toInt().coerceAtLeast(0),
            altitude = f?.alt ?: 0.0,
            speed = abs(f?.vy ?: 0.0),
            vSpeed = f?.vy ?: 0.0,
            fuel = (f?.currentFuelFraction() ?: 1.0).toFloat(),
            boosterFuel = if (f != null && f.boostersAttached) (f.boosterFuel / (spec.booster?.fuelMass ?: 1.0)).toFloat() else -1f,
            stage = f?.stage ?: 0,
            stageCount = spec.stages.size,
            hull = ((f?.hull ?: spec.maxHull) / spec.maxHull).toFloat().coerceIn(0f, 1f),
            heat = (f?.heat ?: 0.0).toFloat(),
            zone = zoneName(f?.alt ?: 0.0),
            warp = warp.toFloat(),
            throttle = (f?.throttle ?: 0.0).toFloat(),
            missionProgress = if (f == null) 0f else missionFraction(f.alt),
            banner = bannerText,
            bannerId = bannerId,
            hits = hits,
            ending = ending,
            mach = if (f == null) 0f else (abs(f.vy) / Physics.speedOfSound(f.alt)).toFloat(),
            results = results,
            marsBound = inFlight && f.alt > 160_000 && Physics.bound(f.alt, f.vy, Physics.MARS_ARRIVAL_ALT),
            arrivalNote = if (inFlight && f.alt > 160_000 && Physics.bound(f.alt, f.vy, Physics.MARS_ARRIVAL_ALT)) arrivalStatus(f) else "",
            radar = if (inFlight && spec.hazardRadar) world.incoming() else emptyList(),
            autoThrottling = inFlight && spec.autoThrottle && f.heat > 0.7 && autoThrottle > 0.0,
            navCut = inFlight && Autopilot.navCut(spec, f.alt, f.vy),
            evading = inFlight && abs(autoSteer) >= 0.99f,
            moonPassed = inFlight && moonPassed,
        )
    }
}

/** Hazard layout seed for a given flight number (deterministic, so outcomes depend only on upgrades). */
fun hazardSeed(flightIndex: Int): Int = 0x5EED + 7919 * flightIndex

/** Log-scale fraction of the one mission path (0 at the pad, 1 at Mars). */
fun missionFraction(alt: Double): Float =
    (ln(1.0 + alt / 1000.0) / ln(1.0 + Physics.MARS_ARRIVAL_ALT / 1000.0)).toFloat().coerceIn(0f, 1f)

fun formatAlt(m: Double): String = when {
    m < 1_000 -> "${m.toInt()} m"
    m < 100_000 -> "%.2f km".format(m / 1000)
    else -> "%,d km".format((m / 1000).toLong())
}

fun formatSpeed(v: Double): String = if (v < 1000) "${v.toInt()} m/s" else "%.2f km/s".format(v / 1000)
