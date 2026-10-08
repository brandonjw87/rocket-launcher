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
    /** Where this flight was headed (Mars until the first Mars landing, then Jupiter, then Saturn after Europa). */
    val target: MissionTarget = MissionTarget.MARS,
    val jupiterReached: Boolean = false, val jupiterOrbit: Boolean = false, val europaLanded: Boolean = false,
    /** Saturn flights: reached Saturn, and how far up the arrival ladder it got ([Arrival.FLYBY]..[Arrival.TITAN_FLIGHT]; -1 = not there). */
    val saturnReached: Boolean = false, val saturnLevel: Int = -1,
    /** Saturn flights: the Jupiter slingshot happened. */
    val slingshot: Boolean = false,
) {
    val ringsCrossed: Boolean get() = saturnLevel >= Arrival.RINGS
    val saturnOrbit: Boolean get() = saturnLevel >= Arrival.ORBIT
    val enceladus: Boolean get() = saturnLevel >= Arrival.ENCELADUS
    val titanProbe: Boolean get() = saturnLevel >= Arrival.TITAN_PROBE
    val titanFlight: Boolean get() = saturnLevel >= Arrival.TITAN_FLIGHT
}

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
    /** Log-scale progress along the one mission path (Earth → Moon → Mars → Jupiter → Saturn) toward this flight's target. */
    val missionProgress: Float = 0f,
    /** This flight's target (Mars, Jupiter once Mars has been landed on, Saturn once Europa has). */
    val target: MissionTarget = MissionTarget.MARS,
    val banner: String = "",
    val bannerId: Int = 0,
    val hits: Int = 0,
    val ending: Boolean = false,
    val mach: Float = 0f,
    val results: FlightResult? = null,
    /** Coasting from here reaches the target. */
    val bound: Boolean = false,
    /** While bound for the target: will the remaining fuel allow capture / landing? */
    val arrivalNote: String = "",
    /** Deep-space nav computer is holding the engines off (on course). */
    val navCut: Boolean = false,
    val radar: List<Float> = emptyList(),
    val autoThrottling: Boolean = false,
    /** Autopilot is steering around a hazard right now. */
    val evading: Boolean = false,
    /** The flight has passed the Moon. */
    val moonPassed: Boolean = false,
    /** Sound on (for the quick mute button). */
    val soundOn: Boolean = true,
)

/** Visual-only events the renderer turns into particles. */
sealed class Fx {
    data class StageSep(val index: Int) : Fx()
    data object BoosterSep : Fx()
    data class Hit(val x: Float, val y: Float) : Fx()
    data class Explode(val x: Float, val y: Float) : Fx()
    data object Liftoff : Fx()
    /** The Jupiter slingshot just kicked the rocket (Saturn flights). */
    data object Slingshot : Fx()
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
    /** Target of the current/last flight (set at launch from the save). */
    var target: MissionTarget = MissionTarget.of(_save.value); private set
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
    /** Banners wait their turn, so several in one moment (zone + milestone + badge) are all readable. */
    private val bannerQueue = ArrayDeque<String>()
    private var bannerCooldown = 0f
    private var endReason = ""
    private var moonPassed = false
    private var marsReached = false
    private var marsOrbit = false
    private var marsLanded = false
    private var jupiterReached = false
    private var jupiterOrbit = false
    private var europaLanded = false
    private var saturnReached = false
    private var saturnLevel = -1
    private var slingshot = false
    private var iceHits = 0
    private var iceDodges = 0
    private val newAch = ArrayList<Achievement>()
    private val newMilestones = ArrayList<Milestone>()
    private var zoneIdx = 0
    private var results: FlightResult? = null
    private var hudTimer = 0f
    private val flightEvents = ArrayList<FlightEvent>()
    private val worldEvents = ArrayList<WorldEvent>()

    private val nearZones = listOf(
        2_500.0 to "Lower clouds",
        12_000.0 to "Stratosphere",
        50_000.0 to "Mesosphere — the sky turns black",
        100_000.0 to "🌌 KÁRMÁN LINE — YOU'RE IN SPACE!",
        400_000.0 to "🛰️ Orbital altitude (ISS height)",
        2_000_000.0 to "High Earth orbit",
        20_000_000.0 to "Deep space",
        300_000_000.0 to "🌕 Approaching the Moon",
    )
    private val marsZones = nearZones + listOf(
        Physics.MOON_ARRIVAL_ALT to "🌕 LUNAR FLYBY — past the Moon, on to Mars!",
        6.0e8 to "🚀 TRANS-MARS INJECTION — leaving Earth's pull",
        1.5e10 to "☀️ Interplanetary cruise",
        3.0e10 to "🔴 Mars grows ahead",
        5.3e10 to "🔴 Mars approach — capture burn ahead",
    )
    private val jupiterZones = nearZones + listOf(
        Physics.MOON_ARRIVAL_ALT to "🌕 LUNAR FLYBY — on to Mars and Jupiter!",
        6.0e8 to "🚀 TRANS-JUPITER INJECTION — leaving Earth's pull",
        1.5e10 to "☀️ Interplanetary cruise",
        4.0e10 to "🔴 Mars grows ahead",
        Physics.MARS_ARRIVAL_ALT to "🔴 MARS FLYBY — on to Jupiter!",
        Physics.ASTEROID_BELT_START to "☄️ ENTERING THE ASTEROID BELT",
        Physics.ASTEROID_BELT_END to "☄️ Through the asteroid belt",
        3.5e11 to "🟠 Jupiter grows ahead",
        5.7e11 to "🟠 Jupiter approach — orbit insertion ahead",
    )
    private val saturnZones = nearZones + listOf(
        Physics.MOON_ARRIVAL_ALT to "🌕 LUNAR FLYBY — on to Mars, Jupiter and Saturn!",
        6.0e8 to "🚀 TRANS-SATURN INJECTION — leaving Earth's pull",
        1.5e10 to "☀️ Interplanetary cruise",
        4.0e10 to "🔴 Mars grows ahead",
        Physics.MARS_ARRIVAL_ALT to "🔴 MARS FLYBY — on to Jupiter and Saturn!",
        Physics.ASTEROID_BELT_START to "☄️ ENTERING THE ASTEROID BELT",
        Physics.ASTEROID_BELT_END to "☄️ Through the asteroid belt",
        3.5e11 to "🟠 Jupiter grows ahead",
        5.6e11 to "🟠 Jupiter flyby ahead — slingshot to Saturn",
        Physics.JUPITER_ARRIVAL_ALT to "🟠 JUPITER FLYBY — on to Saturn!",
        Physics.CENTAUR_ZONE_START to "🧊 Icy centaurs ahead",
        9.0e11 to "🌌 Deep cruise — Saturn is a bright star ahead",
        1.1e12 to "🪐 Saturn grows ahead — you can see the rings",
        Physics.RING_ZONE_START to "💫 SATURN'S RINGS — ring-plane crossing ahead!",
    )
    /** Same Saturn banners for rockets without Gravity-Assist Navigation: no slingshot is promised. */
    private val saturnZonesNoSling = saturnZones.map { if (it.first == 5.6e11) it.first to "🟠 Jupiter flyby ahead — on to Saturn" else it }
    private val zones get() = when (target) {
        MissionTarget.MARS -> marsZones; MissionTarget.JUPITER -> jupiterZones
        MissionTarget.SATURN -> if (spec.slingKick > 0) saturnZones else saturnZonesNoSling
    }

    fun zoneName(alt: Double): String = when {
        alt < 2_500 -> "Lower atmosphere"
        alt < 12_000 -> "Cloud layer"
        alt < 50_000 -> "Stratosphere"
        alt < 100_000 -> "Mesosphere"
        alt < 2_000_000 -> "Low Earth orbit"
        alt < 36_000_000 -> "High orbit"
        alt < Physics.EARTH_SOI -> "Cislunar space"
        alt < 5.0e10 -> "Interplanetary space"
        alt < Physics.MARS_ARRIVAL_ALT || target == MissionTarget.MARS -> "Mars approach"
        alt < Physics.ASTEROID_BELT_START -> "Beyond Mars"
        alt < Physics.ASTEROID_BELT_END -> "Asteroid belt"
        alt < 5.0e11 -> "Outer solar system"
        alt < Physics.JUPITER_ARRIVAL_ALT || target == MissionTarget.JUPITER -> "Jupiter approach"
        alt < Physics.CENTAUR_ZONE_START -> "Beyond Jupiter"
        alt < Physics.CENTAUR_ZONE_END -> "Centaur zone"
        alt < 1.15e12 -> "Outer solar system"
        alt < Physics.RING_ZONE_START -> "Saturn approach"
        else -> "Saturn's ring system"
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
        target = MissionTarget.of(_save.value)
        balanceAtLaunch = _save.value.money   // money left unspent at launch
        // the last flight's banners (and badges still queued) were already on its results screen
        bannerQueue.clear(); bannerText = ""; bannerCooldown = 0f
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

    fun setSound(on: Boolean) = synchronized(lock) { commit(_save.value.copy(soundOn = on)); publishHud() }
    /** Quick mute button (every screen): flips sound on/off and saves it. */
    fun toggleSound() = setSound(!_save.value.soundOn)
    fun setQuality(q: Int) = synchronized(lock) { commit(_save.value.copy(quality = q.coerceIn(0, 2))) }
    fun resetProgress() = synchronized(lock) {
        val s = _save.value
        commit(SaveData(soundOn = s.soundOn, quality = s.quality))
        rebuildSpec(); target = MissionTarget.of(_save.value); phase = Phase.MENU; resetVisuals()
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
        // the outcome of an ending flight (arrival, hull failure, apogee) is already decided: bank it now, so a
        // process killed in the background cannot lose it (the end animation is frozen while paused)
        if (phase == Phase.FLIGHT && ending) {
            finishFlight(); paused = false; publishHud()
            val d = _save.value.serialize(); store.saveNow(d); lastStored = d
            return@synchronized
        }
        val d = _save.value.serialize()
        if (d == lastStored) return@synchronized
        if (lastStored == null && _save.value == SaveData()) return@synchronized
        store.saveNow(d); lastStored = d
    }
    private fun rebuildSpec() { spec = RocketSpec(_save.value.tech); specVersion++ }
    private fun sfx(s: Sfx) { if (_save.value.soundOn) sfxListener?.invoke(s) }
    /** Queue a banner; [urgent] ones (arrival, hull failure) jump the queue and show right away. */
    private fun showBanner(t: String, urgent: Boolean = false) {
        if (urgent) { bannerQueue.addFirst(t); bannerCooldown = 0f; return }
        if (bannerQueue.size >= 6) bannerQueue.removeFirst()
        bannerQueue.addLast(t)
    }

    private fun updateBanners(dt: Float) {
        bannerCooldown -= dt
        if (bannerCooldown <= 0f && bannerQueue.isNotEmpty()) {
            bannerText = bannerQueue.removeFirst(); bannerId++
            bannerCooldown = if (bannerQueue.isEmpty()) 0.4f else 1.5f
        }
    }

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
            updateBanners(dt)
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
        flight = FlightState(spec, target)
        autoThrottle = 1.0; autoSteer = 0f
        endTimer = 0f; descendTimer = 0f; hits = 0; iceHits = 0; iceDodges = 0
        endReason = ""; moonPassed = false; marsReached = false; marsOrbit = false; marsLanded = false
        jupiterReached = false; jupiterOrbit = false; europaLanded = false
        saturnReached = false; saturnLevel = -1; slingshot = false
        newAch.clear(); newMilestones.clear(); zoneIdx = 0
        bannerQueue.clear(); bannerCooldown = 0f
        sfx(Sfx.LIFTOFF)
        showBanner("🚀 LIFTOFF!")
    }

    private fun updateFlight(dt: Float) {
        val f = flight ?: return
        // ---- autopilot (no player input): throttle + hazard avoidance from the researched tech only
        autoThrottle = if (ending || exploded) 0.0 else Autopilot.throttle(spec, f, target)
        autoSteer = if (ending || exploded) 0f else Autopilot.steer(spec, world.entities, laneX)
        val holding = autoThrottle > 0.0

        val tiltGoal = (autoSteer.coerceIn(-1f, 1f) * spec.maxTiltRad).toFloat()
        tilt += (tiltGoal - tilt) * min(1f, dt * spec.steerResponse)
        f.tilt = tilt.toDouble()
        laneVx = sin(tilt) * 75f
        laneX += laneVx * dt
        if (laneX > World.LANE) { laneX = World.LANE; laneVx = 0f }
        if (laneX < -World.LANE) { laneX = -World.LANE; laneVx = 0f }

        val targetAlt = target.arrivalAlt
        val arrived = when (target) { MissionTarget.MARS -> marsReached; MissionTarget.JUPITER -> jupiterReached; MissionTarget.SATURN -> saturnReached }
        if (!arrived && !exploded) {
            val coastWarp = Physics.timeWarp(f.alt, Physics.warpCap(target, f.alt, f.vy))
            val burning = holding && f.hasThrustAvailable()
            val warpGoal = if (burning) min(coastWarp, Physics.BURN_WARP_CAP) else coastWarp
            // drop instantly when a burn starts (fuel use stays controllable), ease back up when coasting
            warp = if (warpGoal <= warp) warpGoal else warp + (warpGoal - warp) * (1.0 - kotlin.math.exp(-dt * 1.2))
            val simDt = dt * warp
            val maxStep = if (f.alt < 160_000) 0.02 else min(30.0, 0.02 * warp)
            val n = ceil(simDt / maxStep).toInt().coerceIn(1, 300)
            val h = simDt / n
            for (i in 0 until n) {
                f.step(h, Autopilot.throttle(spec, f, target).takeIf { holding } ?: 0.0, flightEvents)
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
            is FlightEvent.Slingshot -> {
                slingshot = true
                fx.add(Fx.Slingshot); sfx(Sfx.WHOOSH); shake = max(shake, 0.8f)
                showBanner("🌀 JUPITER SLINGSHOT  +%.1f km/s!".format(e.kick / 1000.0), urgent = true)
                unlock(Achievement.SLINGSHOT)
            }
        }
    }

    private fun handleWorldEvent(e: WorldEvent, f: FlightState) {
        when (e) {
            is WorldEvent.Hit -> {
                // ring ice is softened by the ring-plane shields
                f.hull -= if (e.kind == EntityKind.ICE) e.damage * spec.iceDamageMul else e.damage
                hits++
                if (e.kind == EntityKind.ICE) iceHits++
                fx.add(Fx.Hit(e.x, e.y))
                shake = 1.2f
                sfx(Sfx.HIT)
                // a hit costs 3% of vertical speed; capped hard above the atmosphere so debris while coasting cannot burn the landing reserve
                f.vy -= kotlin.math.sign(f.vy) * min(abs(f.vy) * 0.03, if (f.alt > 160_000) 5.0 else 40.0)
            }
            is WorldEvent.NearMiss -> { sfx(Sfx.WHOOSH); if (e.kind == EntityKind.ICE) iceDodges++ }
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
        if (target != MissionTarget.MARS && !marsReached && f.maxAlt >= Physics.MARS_ARRIVAL_ALT) marsReached = true
        if (target == MissionTarget.SATURN && !jupiterReached && f.maxAlt >= Physics.JUPITER_ARRIVAL_ALT) jupiterReached = true
        if (f.maxAlt >= Physics.ASTEROID_BELT_START) unlock(Achievement.ASTEROID_BELT)
        if (f.maxAlt >= Physics.LIGHT_HOUR) unlock(Achievement.LIGHT_HOUR)
        if (target == MissionTarget.SATURN && f.time >= 365.25 * 86_400) unlock(Achievement.LONG_HAUL)
        if (target == MissionTarget.SATURN && spec.cruiseAccel > 0.0 && f.alt > Physics.JUPITER_ARRIVAL_ALT) unlock(Achievement.SAILOR)
        if (spec.has("P_AMAT2") && f.liftedOff) unlock(Achievement.ANTIMATTER)
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
        if (v >= 25_000) unlock(Achievement.OUTER_EXPRESS)
        if (f.stagesSeparated >= 3) unlock(Achievement.STAGER)
        if (f.stagesSeparated >= 7) unlock(Achievement.BIG_STACK)
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
                showBanner("💥 Hull failure!", urgent = true)
            }
            f.alt >= target.arrivalAlt -> when (target) {
                MissionTarget.MARS -> arriveAtMars(f)
                MissionTarget.JUPITER -> arriveAtJupiter(f)
                MissionTarget.SATURN -> arriveAtSaturn(f)
            }
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
        if (target == MissionTarget.SATURN) return when {
            !(spare || fuel >= spec.saturnCaptureFuelFrac * last) -> "⚠ not enough fuel for Saturn orbit"
            !spec.ringShield -> "fuel ✓ · crossing the rings needs a Ring-Plane Dust Shield"
            !spec.plumeReady -> "orbit fuel ✓ · Enceladus needs a Plume Mass Spectrometer"
            !spec.titanProbeReady -> "orbit fuel ✓ · Titan needs " + (if (spec.has("TH_TITANSHELL")) "a Huygens probe" else "an aeroshell + Huygens probe")
            !spec.titanFlightReady -> "orbit fuel ✓ · flying on Titan needs the Dragonfly Rotorcraft"
            spare || fuel >= spec.titanLandingFuelFrac * last -> "Titan landing fuel ✓"
            else -> "orbit fuel ✓ · not enough to fly on Titan"
        }
        if (target == MissionTarget.JUPITER) return when {
            !(spare || fuel >= RocketSpec.JUPITER_CAPTURE_FUEL_FRAC * last) -> "⚠ not enough fuel for Jupiter orbit"
            !spec.jupiterRadiationReady -> "fuel ✓ · Jupiter orbit needs a Radiation Vault"
            spec.europaLandingReady && (spare || fuel >= spec.europaLandingFuelFrac * last) -> "Europa landing fuel ✓"
            spec.europaLandingReady -> "orbit fuel ✓ · not enough to land on Europa"
            else -> "orbit fuel ✓ · Europa needs a lander + rad-hard electronics"
        }
        return when {
            !(spare || fuel >= RocketSpec.MARS_CAPTURE_FUEL_FRAC * last) -> "⚠ not enough fuel for Mars capture"
            spec.marsEntryReady && (spare || fuel >= spec.marsLandingFuelFrac * last) -> "landing fuel ✓"
            spec.marsEntryReady -> "capture fuel ✓ · not enough to land"
            else -> "capture fuel ✓ · landing needs PICA-X + parachute"
        }
    }

    private fun arriveAtJupiter(f: FlightState) {
        jupiterReached = true
        marsReached = true
        val a = Arrival.evaluate(spec, MissionTarget.JUPITER, f.stage, f.remainingFuel())
        europaLanded = a.landed
        jupiterOrbit = a.orbit
        if (jupiterOrbit) unlock(Achievement.JUPITER_ORBIT)
        if (europaLanded) unlock(Achievement.EUROPA_LANDING)
        ending = true; endTimer = 4.5f
        val pct = (spec.europaLandingFuelFrac * 100).toInt()
        val fuelOk = f.stage < spec.stages.size - 1 || f.remainingFuel() >= RocketSpec.JUPITER_CAPTURE_FUEL_FRAC * spec.stages.last().fuelMass
        endReason = when {
            europaLanded -> "Landed on Europa!"
            jupiterOrbit && !spec.europaLandingReady -> "Captured into Jupiter orbit — research the Europa Lander and Radiation-Hardened Electronics to land on Europa"
            jupiterOrbit -> "Captured into Jupiter orbit — arrive with $pct% fuel or a spare stage to land on Europa"
            fuelOk && !spec.jupiterRadiationReady -> "Jupiter flyby — the radiation belts fried the electronics; research the Titanium Radiation Vault"
            !Autopilot.canNavCut(spec, MissionTarget.JUPITER) -> "Jupiter flyby — the autopilot burned every drop; research Juno-Class Solar Arrays to power the nav computer past Mars"
            else -> "Jupiter flyby — no fuel left for orbit insertion (needs 5% or a spare stage)"
        }
        showBanner(when { europaLanded -> "🧊 TOUCHDOWN ON EUROPA!"; jupiterOrbit -> "🟠 JUPITER ORBIT INSERTION"; else -> "🟠 Jupiter flyby" }, urgent = true)
        sfx(Sfx.WIN)
    }

    private fun arriveAtSaturn(f: FlightState) {
        saturnReached = true
        jupiterReached = true
        marsReached = true
        val a = Arrival.evaluate(spec, MissionTarget.SATURN, f.stage, f.remainingFuel())
        saturnLevel = a.level
        if (saturnLevel >= Arrival.RINGS) unlock(Achievement.RING_RIDER)
        if (saturnLevel >= Arrival.ORBIT) unlock(Achievement.LORD_OF_RINGS)
        if (saturnLevel >= Arrival.ENCELADUS) unlock(Achievement.GEYSER)
        if (saturnLevel >= Arrival.TITAN_PROBE) unlock(Achievement.HUYGENS)
        if (saturnLevel >= Arrival.TITAN_FLIGHT) unlock(Achievement.TITAN_FLYER)
        ending = true; endTimer = 4.5f
        val pct = (spec.titanLandingFuelFrac * 100).toInt()
        val capPct = (spec.saturnCaptureFuelFrac * 100).toInt()
        endReason = when (saturnLevel) {
            Arrival.TITAN_FLIGHT -> "Flew on Titan!"
            Arrival.TITAN_PROBE -> if (!spec.titanFlightReady) "Probe landed on Titan — research the Dragonfly Rotorcraft to fly there"
                else "Probe landed on Titan — arrive with $pct% fuel or a spare stage to fly the rotorcraft"
            Arrival.ENCELADUS -> "Flew through Enceladus' plumes — research ${spec.titanProbeMissing} to land on Titan"
            Arrival.ORBIT -> "Captured into Saturn orbit — research the Plume Mass Spectrometer to fly through Enceladus' geysers"
            Arrival.RINGS -> if (!Autopilot.canNavCut(spec, MissionTarget.SATURN))
                "Crossed the rings — the autopilot burned every drop; research Next-Gen RTGs to power the nav computer past Jupiter"
                else "Crossed the rings — no fuel left for Saturn orbit (needs $capPct% or a spare stage)"
            else -> "Saturn flyby — ring dust sandblasted the electronics; research the Ring-Plane Dust Shield"
        }
        showBanner(when (saturnLevel) {
            Arrival.TITAN_FLIGHT -> "🚁 DRAGONFLY FLIES ON TITAN!"
            Arrival.TITAN_PROBE -> "🪂 TOUCHDOWN ON TITAN!"
            Arrival.ENCELADUS -> "⛲ THROUGH ENCELADUS' GEYSERS!"
            Arrival.ORBIT -> "🪐 SATURN ORBIT INSERTION"
            Arrival.RINGS -> "💫 Ring-plane crossing"
            else -> "🪐 Saturn flyby"
        }, urgent = true)
        sfx(Sfx.WIN)
    }

    private fun arriveAtMars(f: FlightState) {
        marsReached = true
        val a = Arrival.evaluate(spec, MissionTarget.MARS, f.stage, f.remainingFuel())
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
        showBanner(when { marsLanded -> "🔴 TOUCHDOWN ON MARS!"; marsOrbit -> "🔴 MARS ORBIT INSERTION"; else -> "🔴 Mars flyby" }, urgent = true)
        sfx(Sfx.WIN)
    }

    private fun finishFlight() {
        val f = flight ?: return
        val s0 = _save.value
        if (s0.flights == 0) unlock(Achievement.FIRST_FLIGHT)
        if (hits >= 3 && f.hull > 0) unlock(Achievement.TOUGH)
        // the autopilot dodges almost all ring ice, so close dodges count too
        if ((iceHits >= 3 || iceDodges >= 10) && f.hull > 0) unlock(Achievement.ICE_BREAKER)
        if (s0.flights + 1 >= 150) unlock(Achievement.VETERAN)
        // hidden interest on the money left unspent at launch, folded into "Flight performance"
        val st = Economy.settle(balanceAtLaunch, f.maxAlt, s0.milestones, s0.interestCarry)
        val records = ArrayList<String>()
        if (f.maxAlt > s0.bestAltitude) records.add("Altitude")
        if (f.topSpeed > s0.bestSpeed) records.add("Speed")
        val newBalance = if (st.total > Long.MAX_VALUE - s0.money) Long.MAX_VALUE else s0.money + st.total
        var next = s0.copy(
            money = newBalance,
            milestones = s0.milestones + st.milestones,
            bestAltitude = max(s0.bestAltitude, f.maxAlt),
            bestSpeed = max(s0.bestSpeed, f.topSpeed),
            flights = s0.flights + 1,
            achievements = s0.achievements + newAch,
            totalEarned = if (st.total > Long.MAX_VALUE - s0.totalEarned) Long.MAX_VALUE else s0.totalEarned + st.total,
            marsOrbits = s0.marsOrbits + (if (marsOrbit) 1 else 0),
            marsLandings = s0.marsLandings + (if (marsLanded) 1 else 0),
            jupiterOrbits = s0.jupiterOrbits + (if (jupiterOrbit) 1 else 0),
            europaLandings = s0.europaLandings + (if (europaLanded) 1 else 0),
            ringCrossings = s0.ringCrossings + (if (saturnLevel >= Arrival.RINGS) 1 else 0),
            saturnOrbits = s0.saturnOrbits + (if (saturnLevel >= Arrival.ORBIT) 1 else 0),
            enceladusPasses = s0.enceladusPasses + (if (saturnLevel >= Arrival.ENCELADUS) 1 else 0),
            titanProbes = s0.titanProbes + (if (saturnLevel >= Arrival.TITAN_PROBE) 1 else 0),
            titanFlights = s0.titanFlights + (if (saturnLevel >= Arrival.TITAN_FLIGHT) 1 else 0),
            interestCarry = st.interestCarry,
        )
        // this flight may have finished the whole mission: one more badge
        if (Mission.next(next) == null && Achievement.GRAND_TOUR !in next.achievements && Achievement.GRAND_TOUR !in newAch) {
            newAch.add(Achievement.GRAND_TOUR)
            next = next.copy(achievements = next.achievements + Achievement.GRAND_TOUR)
        }
        results = FlightResult(
            f.maxAlt, f.topSpeed, f.time, st.performancePay, st.milestones, st.total, newBalance,
            records, newAch.toList(), endReason.ifEmpty { "Flight over" }, moonPassed, hits,
            marsReached, marsOrbit, marsLanded,
            target, jupiterReached, jupiterOrbit, europaLanded,
            saturnReached, saturnLevel, slingshot,
        )
        commit(next)
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
            missionProgress = if (f == null) 0f else missionFraction(f.alt, target),
            target = target,
            banner = bannerText,
            bannerId = bannerId,
            hits = hits,
            ending = ending,
            mach = if (f == null) 0f else (abs(f.vy) / Physics.speedOfSound(f.alt)).toFloat(),
            results = results,
            bound = inFlight && f.alt > 160_000 && Autopilot.coastReaches(spec, f.alt, f.vy, target, f.slung),
            arrivalNote = if (inFlight && f.alt > 160_000 && Autopilot.coastReaches(spec, f.alt, f.vy, target, f.slung)) arrivalStatus(f) else "",
            radar = if (inFlight && spec.hazardRadar) world.incoming() else emptyList(),
            autoThrottling = inFlight && spec.autoThrottle && f.heat > 0.7 && autoThrottle > 0.0,
            navCut = inFlight && Autopilot.navCut(spec, f.alt, f.vy, target, f.slung),
            evading = inFlight && abs(autoSteer) >= 0.99f,
            moonPassed = inFlight && moonPassed,
            soundOn = _save.value.soundOn,
        )
    }
}

/** Hazard layout seed for a given flight number (deterministic, so outcomes depend only on upgrades). */
fun hazardSeed(flightIndex: Int): Int = 0x5EED + 7919 * flightIndex

/**
 * Hangar forecast: flies this exact launch (same rocket and flight number, so the same hazards and the same fuel
 * spent dodging them) in a throwaway engine and returns its result. Flights are deterministic, so this is what
 * LAUNCH will do. Takes a few hundred thousand cheap steps at most: call it off the main thread.
 */
fun forecastFlight(save: SaveData): FlightResult? {
    val e = GameEngine(object : SaveStore {
        var d: String? = save.serialize()
        override fun load() = d
        override fun save(data: String) { d = data }
    })
    e.launch()
    var n = 0
    while (e.phase != Phase.RESULTS && n++ < 600_000) e.update(GameEngine.FIXED_DT)
    return e.hud.value.results
}

/**
 * Fraction of the one mission path (0 at the pad, 1 at the target), for the HUD bar and the results row.
 * Mars and Jupiter: log scale. Saturn: the log scale up to Jupiter fills Jupiter's share of the distance (49%),
 * then real distance on to Saturn, so the whole Saturn chapter is not squeezed into 97-100%.
 */
fun missionFraction(alt: Double, target: MissionTarget = MissionTarget.MARS): Float {
    val f = if (target == MissionTarget.SATURN) {
        val j = Physics.JUPITER_ARRIVAL_ALT
        if (alt >= j) alt / target.arrivalAlt
        else j / target.arrivalAlt * ln(1.0 + alt / 1000.0) / ln(1.0 + j / 1000.0)
    } else ln(1.0 + alt / 1000.0) / ln(1.0 + target.arrivalAlt / 1000.0)
    return f.toFloat().coerceIn(0f, 1f)
}

/** Whole percent of the way to the target for the results screen: 100% only when the flight actually got there. */
fun missionPercent(alt: Double, target: MissionTarget, arrived: Boolean): Int =
    if (arrived) 100 else Math.round(missionFraction(alt, target) * 100).coerceAtMost(99)

fun formatAlt(m: Double): String = when {
    m < 1_000 -> "${m.toInt()} m"
    m < 100_000 -> "%.2f km".format(m / 1000)
    else -> "%,d km".format((m / 1000).toLong())
}

fun formatSpeed(v: Double): String = if (v < 1000) "${v.toInt()} m/s" else "%.2f km/s".format(v / 1000)
