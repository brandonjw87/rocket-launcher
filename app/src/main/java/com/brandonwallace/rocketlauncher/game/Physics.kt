package com.brandonwallace.rocketlauncher.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object Physics {
    const val G0 = 9.80665
    const val R_EARTH = 6_371_000.0
    const val MU = 3.986004418e14
    const val MOON_CENTER_DIST = 384_400_000.0
    const val MOON_RADIUS = 1_737_000.0
    /** Altitude above Earth's surface at which the rocket reaches the Moon's surface. */
    const val MOON_ARRIVAL_ALT = MOON_CENTER_DIST - MOON_RADIUS - R_EARTH
    const val KARMAN = 100_000.0
    const val MARS_RADIUS = 3_389_500.0
    /** Distance from Earth's surface at which the rocket reaches the Martian surface (close-approach geometry). */
    const val MARS_ARRIVAL_ALT = 5.5e10
    const val MARS_CENTER_DIST = MARS_ARRIVAL_ALT + MARS_RADIUS + R_EARTH
    /** Edge of Earth's sphere of influence. */
    const val EARTH_SOI = 9.25e8
    /**
     * Beyond Earth's SOI the 1-D flight climbs "uphill" out of the Sun's well toward Mars: a constant
     * retarding pull that demands ~8 km/s of hyperbolic excess speed (a compressed trans-Mars injection).
     */
    const val SUN_PULL = 6.0e-4
    /** Past Mars' orbit the Sun's pull is weaker (it falls off with distance), so the long Jupiter leg is a gentler climb. */
    const val SUN_PULL_OUTER = 3.2e-4
    const val JUPITER_RADIUS = 69_911_000.0
    /** Distance from Earth's surface to Jupiter's cloud tops at a close approach (opposition, ~588 million km). */
    const val JUPITER_ARRIVAL_ALT = 5.88e11
    const val JUPITER_CENTER_DIST = JUPITER_ARRIVAL_ALT + JUPITER_RADIUS + R_EARTH
    /** Where the main asteroid belt starts on the way out to Jupiter. */
    const val ASTEROID_BELT_START = 1.6e11
    const val ASTEROID_BELT_END = 3.9e11
    const val MOON_WARP_CAP = 60_000.0
    const val MARS_WARP_CAP = 3_000_000.0
    const val JUPITER_WARP_CAP = 6_000_000.0
    /** Jupiter flights slow the time warp down around Mars so the flyby is actually visible (a couple of seconds). */
    const val MARS_FLYBY_WARP = 800.0
    /**
     * Time-warp limit while the engines are actually burning. Without it a deep-space burn ran at x70,000 and an
     * upper stage emptied in a fraction of a real second, making the capture/landing fuel window ~1 s wide.
     */
    const val BURN_WARP_CAP = 50.0

    /** Constant "uphill" pull of the Sun along the flight path (0 inside Earth's SOI). */
    fun sunPull(alt: Double): Double = when {
        alt <= EARTH_SOI -> 0.0
        alt <= MARS_ARRIVAL_ALT -> SUN_PULL
        else -> SUN_PULL_OUTER
    }

    fun gravity(alt: Double): Double { val r = R_EARTH + max(alt, 0.0); return MU / (r * r) + sunPull(alt) }

    /** Specific potential energy matching [gravity] (J/kg). */
    fun potential(alt: Double): Double = -MU / (R_EARTH + max(alt, 0.0)) +
        SUN_PULL * (alt - EARTH_SOI).coerceIn(0.0, MARS_ARRIVAL_ALT - EARTH_SOI) + SUN_PULL_OUTER * max(0.0, alt - MARS_ARRIVAL_ALT)

    /** True when the current coast (no more burning) carries the rocket to [targetAlt]. */
    fun bound(alt: Double, vy: Double, targetAlt: Double): Boolean =
        vy > 0 && 0.5 * vy * vy + potential(alt) >= potential(targetAlt)
    fun density(alt: Double): Double = if (alt > 160_000.0) 0.0 else 1.225 * exp(-max(alt, 0.0) / 8_500.0)
    fun speedOfSound(alt: Double): Double = if (alt < 11_000) 340.3 - 0.0041 * alt else 295.0

    /**
     * Sim-time multiplier so trips to the Moon take seconds, not days. ~1x near the ground (autopilot dodging),
     * easing to 4x at the Karman line so the climb through the upper atmosphere doesn't drag; continuous above.
     */
    fun timeWarp(alt: Double, cap: Double = MOON_WARP_CAP): Double {
        val km = max(0.0, alt) / 1000.0
        if (alt < KARMAN) return 1.0 + 3.0 * (km / 100.0).pow(1.2)
        return min(cap, (km / 100.0).pow(1.3) + (km - 100.0) / 25.0 + 3.0)
    }

    /** Warp cap for this point of a flight toward [target] (Mars flights keep the 1.1 cap exactly). */
    fun warpCap(target: MissionTarget, alt: Double): Double = when (target) {
        MissionTarget.MARS -> MARS_WARP_CAP
        MissionTarget.JUPITER -> min(JUPITER_WARP_CAP, MARS_FLYBY_WARP + abs(alt - MARS_ARRIVAL_ALT) * 1.5e-4)
    }
}

/**
 * Where the one mission is flying on this launch. Mars until the first Mars landing; after that every flight
 * keeps going past Mars (a flyby) and across the asteroid belt to Jupiter. Derived from the save, never picked.
 */
enum class MissionTarget(val title: String, val arrivalAlt: Double, val icon: String) {
    MARS("Mars", Physics.MARS_ARRIVAL_ALT, "🔴"),
    JUPITER("Jupiter", Physics.JUPITER_ARRIVAL_ALT, "🪐");

    companion object {
        fun of(s: SaveData): MissionTarget = if (s.marsLandings > 0) JUPITER else MARS
    }
}

sealed class FlightEvent {
    data class StageSeparated(val index: Int) : FlightEvent()
    data object BoostersSeparated : FlightEvent()
    data object Liftoff : FlightEvent()
}

/** 1-D vertical rocket flight with stages, boosters, drag, inverse-square gravity and heating. */
class FlightState(val spec: RocketSpec) {
    var alt = 0.0
    var vy = 0.0
    var time = 0.0
    var distance = 0.0
    var maxAlt = 0.0
    var topSpeed = 0.0
    var stage = 0
    val fuel = DoubleArray(spec.stages.size) { spec.stages[it].fuelMass }
    var boosterFuel = spec.booster?.fuelMass ?: 0.0
    var boostersAttached = spec.boosterCount > 0
    var heat = 0.0
    var hull = spec.maxHull
    var throttle = 0.0
    var tilt = 0.0
    var thrustNow = 0.0
    var accel = 0.0
    var dynPressure = 0.0
    var liftedOff = false
    var stagesSeparated = 0
    private var sepTimer = 0.0

    fun mass(): Double {
        var m = spec.payloadMass
        for (i in stage until spec.stages.size) m += spec.stages[i].dryMass + fuel[i]
        val b = spec.booster
        if (boostersAttached && b != null) m += spec.boosterCount * (b.dryMass + boosterFuel)
        return m
    }

    fun currentFuelFraction(): Double =
        if (stage >= spec.stages.size) 0.0 else fuel[stage] / spec.stages[stage].fuelMass

    fun remainingFuel(): Double { var f = 0.0; for (i in stage until fuel.size) f += fuel[i]; return f }
    fun totalFuelFraction(): Double = remainingFuel() / spec.stages.sumOf { it.fuelMass }
    fun hasThrustAvailable(): Boolean = remainingFuel() > 0.0 || (boostersAttached && boosterFuel > 0.0)

    fun step(dt: Double, throttleCmdIn: Double, out: MutableList<FlightEvent>) {
        // flight computer: ease off through peak heating instead of cooking the hull
        val throttleCmd = if (spec.autoThrottle && heat > 0.7) throttleCmdIn * ((1.0 - (heat - 0.7) * 2.5).coerceIn(0.35, 1.0)) else throttleCmdIn
        val rate = 5.0 * dt
        throttle += (throttleCmd - throttle).coerceIn(-rate, rate)

        var thrust = 0.0
        val stages = spec.stages
        if (sepTimer > 0) {
            sepTimer -= dt
            if (sepTimer <= 0) {
                out.add(FlightEvent.StageSeparated(stage))
                stage++
                stagesSeparated++
            }
        } else if (stage < stages.size && fuel[stage] > 0 && throttle > 0.001) {
            val s = stages[stage]
            val f = s.thrust * throttle
            var burn = f / (s.isp * Physics.G0) * dt
            var scale = 1.0
            if (burn > fuel[stage]) { scale = fuel[stage] / burn; burn = fuel[stage] }
            fuel[stage] -= burn
            thrust += f * scale
        }
        val b = spec.booster
        if (boostersAttached && b != null && stage == 0 && boosterFuel > 0 && throttle > 0.001) {
            val f = b.thrust * throttle
            var burn = f / (b.isp * Physics.G0) * dt
            var scale = 1.0
            if (burn > boosterFuel) { scale = boosterFuel / burn; burn = boosterFuel }
            boosterFuel -= burn
            thrust += spec.boosterCount * f * scale
        }
        if (boostersAttached && (boosterFuel <= 0.0 || stage > 0)) {
            boostersAttached = false
            out.add(FlightEvent.BoostersSeparated)
        }
        if (sepTimer <= 0 && stage < stages.size - 1 && fuel[stage] <= 0.0) sepTimer = spec.separationTime

        val m = mass()
        val rho = Physics.density(alt)
        dynPressure = 0.5 * rho * vy * vy
        val drag = dynPressure * spec.cd * spec.area * (if (vy >= 0) 1.0 else -1.0)
        // autopilot steering costs a little vertical thrust (35% of the pure cosine loss)
        var a = (thrust * (1.0 - 0.35 * (1.0 - cos(tilt))) - drag) / m - Physics.gravity(alt)
        if (!liftedOff) {
            if (a > 0) { liftedOff = true; out.add(FlightEvent.Liftoff) } else a = 0.0
        }
        vy += a * dt
        alt += vy * dt
        if (alt < 0 && liftedOff) alt = 0.0
        thrustNow = thrust
        accel = a

        val v = abs(vy)
        val q = 1.83e-4 * sqrt(rho / 0.5) * v * v * v
        heat += (q / (5.5e5 * spec.heatTolerance) - heat * 0.15) * dt
        if (heat < 0) heat = 0.0
        if (heat > 1.0) hull -= (heat - 1.0) * 55.0 * dt

        distance += v * dt
        time += dt
        if (alt > maxAlt) maxAlt = alt
        if (v > topSpeed) topSpeed = v
    }
}

class SimResult(
    val maxAlt: Double, val topSpeed: Double,
    /** The flight reached its target (Mars or Jupiter). */
    val arrived: Boolean,
    val destroyed: Boolean, val time: Double, val warpedRealTime: Double,
    val target: MissionTarget = MissionTarget.MARS,
    /** Captured into orbit / landed at the target (Mars, or Jupiter orbit / Europa landing). */
    val orbit: Boolean = false, val landed: Boolean = false,
) {
    val passedMoon: Boolean get() = maxAlt >= Physics.MOON_ARRIVAL_ALT
    /** Kept for older callers: true when the flight got past the Moon. */
    val reachedMoon: Boolean get() = passedMoon
    val reachedMars: Boolean get() = if (target == MissionTarget.MARS) arrived else maxAlt >= Physics.MARS_ARRIVAL_ALT
    val marsOrbit: Boolean get() = target == MissionTarget.MARS && orbit
    val marsLanded: Boolean get() = target == MissionTarget.MARS && landed
    val reachedJupiter: Boolean get() = target == MissionTarget.JUPITER && arrived
    val jupiterOrbit: Boolean get() = target == MissionTarget.JUPITER && orbit
    val europaLanded: Boolean get() = target == MissionTarget.JUPITER && landed
}

/**
 * Offline run of the real [Autopilot] (same throttle/nav-cut rules as the live flight, no hazards) along the one
 * mission path to [target]. Used by the hangar prediction and by unit tests.
 */
fun simulateAutopilot(spec: RocketSpec, target: MissionTarget = MissionTarget.MARS, maxSimTime: Double = 3.0e8): SimResult {
    val targetAlt = target.arrivalAlt
    val s = FlightState(spec)
    val ev = ArrayList<FlightEvent>()
    var realTime = 0.0
    var descending = 0.0
    while (s.time < maxSimTime) {
        val dt = if (s.alt < 160_000) 0.02 else (s.alt / max(abs(s.vy), 1.0) * 0.002).coerceIn(0.02, 3600.0)
        // while burning, keep steps short enough that the nav cut lands close to the real engine's
        val burning = Autopilot.throttle(spec, s, target) > 0 && s.hasThrustAvailable()
        val h = if (burning && s.alt >= 160_000) min(dt, 2.0) else dt
        s.step(h, Autopilot.throttle(spec, s, target), ev)
        ev.clear()
        realTime += h / Physics.timeWarp(s.alt, Physics.warpCap(target, s.alt))
        if (s.hull <= 0) return SimResult(s.maxAlt, s.topSpeed, false, true, s.time, realTime, target)
        if (s.alt >= targetAlt) {
            val a = Arrival.evaluate(spec, target, s.stage, s.remainingFuel())
            return SimResult(s.maxAlt, s.topSpeed, true, false, s.time, realTime, target, a.orbit, a.landed)
        }
        if (s.liftedOff && s.vy < 0) { descending += h; if (descending > 1.0) break }
        if (s.time > 5 && !s.liftedOff) break
    }
    return SimResult(s.maxAlt, s.topSpeed, false, false, s.time, realTime, target)
}
