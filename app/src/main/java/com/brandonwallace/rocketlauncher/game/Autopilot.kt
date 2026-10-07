package com.brandonwallace.rocketlauncher.game

import kotlin.math.abs

/**
 * 1.1: the rocket flies itself. There is no player throttle or steering — every decision here is a pure,
 * deterministic function of the researched tech and the flight state, so the outcome of a launch is decided
 * by upgrades and staging in the tech tree.
 *
 *  - Throttle: full burn from liftoff (stages and boosters separate automatically as they run dry).
 *    The Digital Flight Computer (avionics 3) eases off through peak heating (see [FlightState.step]).
 *    Deep-Space Navigation (avionics 4) cuts the engines as soon as the coast alone reaches the target, keeping
 *    the remaining fuel for the capture and landing burns. Without it the autopilot burns every drop. Past Mars
 *    (Jupiter flights) the nav computer also needs deep-space power (Juno-class solar arrays); past Jupiter
 *    (Saturn flights) it needs Next-Gen RTGs. The coast prediction includes the Jupiter slingshot and the
 *    cruise push of sails / ion engines.
 *  - Hazards: the autopilot steers around obstacles it can see. How far ahead it sees depends on avionics
 *    (radio guidance adds the hazard radar) and deep-space power, how hard it can turn depends on fins +
 *    avionics ([RocketSpec.maxTiltRad], [RocketSpec.steerResponse]); the hull upgrades decide how many hits it survives.
 */
object Autopilot {
    /** The nav computer can steer this flight's coast (Deep-Space Navigation + enough power for the target). */
    fun canNavCut(spec: RocketSpec, target: MissionTarget): Boolean = spec.navAutoCut && when (target) {
        MissionTarget.MARS -> true
        MissionTarget.JUPITER -> spec.deepSpacePower
        MissionTarget.SATURN -> spec.saturnNavPower
    }

    /** Engines cut once the coast reaches the target. */
    fun navCut(spec: RocketSpec, alt: Double, vy: Double, target: MissionTarget = MissionTarget.MARS, slung: Boolean = false): Boolean =
        canNavCut(spec, target) && alt > 160_000 && coastReaches(spec, alt, vy, target, slung)

    /** The coast from here (no more burning) reaches [target], counting the Jupiter slingshot and cruise push. */
    fun coastReaches(spec: RocketSpec, alt: Double, vy: Double, target: MissionTarget, slung: Boolean = false): Boolean =
        Physics.bound(alt, vy, target.arrivalAlt, spec.cruiseAccel,
            if (target == MissionTarget.SATURN && !slung) spec.slingKick else 0.0)

    /** Throttle command 0..1 for this step. */
    fun throttle(spec: RocketSpec, f: FlightState, target: MissionTarget = MissionTarget.MARS): Double =
        if (navCut(spec, f.alt, f.vy, target, f.slung)) 0.0 else 1.0

    /** How far ahead (visual metres) the autopilot reacts to hazards: eyeballing → radar → inertial/computer → AI → quantum. */
    fun lookahead(spec: RocketSpec): Float = when (spec.level(Stat.AVIONICS)) {
        0 -> 12f
        1 -> 38f
        2 -> 52f
        3, 4 -> 64f
        5 -> 76f
        6 -> 88f
        7 -> 94f
        8 -> 100f
        else -> 106f
    } + 6f * spec.level(Stat.POWER).coerceAtMost(2) + 3f * (spec.level(Stat.POWER) - 2).coerceAtLeast(0)

    /** Steering command in [-1, 1] (fraction of the rocket's max tilt) to avoid the nearest threatening hazard. */
    fun steer(spec: RocketSpec, entities: List<Entity>, laneX: Float): Float {
        val look = lookahead(spec)
        var threat: Entity? = null
        for (e in entities) {
            if (e.y < 3f || e.y > look) continue
            if (abs(e.x - laneX) >= e.kind.radius + 3f) continue
            if (threat == null || e.y < threat.y) threat = e
        }
        if (threat == null) return (-laneX / 25f).coerceIn(-0.3f, 0.3f)   // drift back to the centre line
        var dir = if (threat.x > laneX) -1f else 1f
        // pinned against the edge of the corridor: pass on the other side instead
        if (dir < 0 && laneX < -World.LANE + 1.5f) dir = 1f
        if (dir > 0 && laneX > World.LANE - 1.5f) dir = -1f
        return dir
    }
}

/**
 * Outcome at the target: shared by the live engine and the offline simulation so they always agree.
 *  - Mars: capture needs 3% of the last stage (or a spare stage); landing needs PICA-X + parachute + landing fuel.
 *  - Jupiter: capture needs 5% (or a spare stage) and a radiation vault; a Europa landing also needs the
 *    Europa lander, radiation-hardened electronics and landing fuel.
 *  - Saturn: a ladder ([level]): ring crossing (dust shield) → orbit (6% fuel, less with aerocapture, or a
 *    spare stage) → Enceladus plumes (mass spectrometer) → Titan probe (aeroshell + Huygens probe) → Titan
 *    flight (Dragonfly rotorcraft + 6% landing fuel, 3% with the descent stage). Each step needs the one before.
 */
class Arrival(val orbit: Boolean, val landed: Boolean, val level: Int = if (landed) 2 else if (orbit) 1 else 0) {
    companion object {
        // Saturn ladder levels
        const val FLYBY = 0
        const val RINGS = 1
        const val ORBIT = 2
        const val ENCELADUS = 3
        const val TITAN_PROBE = 4
        const val TITAN_FLIGHT = 5

        fun evaluate(spec: RocketSpec, target: MissionTarget, stage: Int, remainingFuel: Double): Arrival {
            val last = spec.stages.last().fuelMass
            val spare = stage < spec.stages.size - 1
            return when (target) {
                MissionTarget.MARS -> {
                    val capture = spare || remainingFuel >= RocketSpec.MARS_CAPTURE_FUEL_FRAC * last
                    val landFuel = spare || remainingFuel >= spec.marsLandingFuelFrac * last
                    Arrival(capture, capture && landFuel && spec.marsEntryReady)
                }
                MissionTarget.JUPITER -> {
                    val capture = spec.jupiterRadiationReady && (spare || remainingFuel >= RocketSpec.JUPITER_CAPTURE_FUEL_FRAC * last)
                    val landFuel = spare || remainingFuel >= spec.europaLandingFuelFrac * last
                    Arrival(capture, capture && landFuel && spec.europaLandingReady)
                }
                MissionTarget.SATURN -> {
                    val captureFuel = spare || remainingFuel >= spec.saturnCaptureFuelFrac * last
                    val landFuel = spare || remainingFuel >= spec.titanLandingFuelFrac * last
                    val level = when {
                        !spec.ringShield -> FLYBY
                        !captureFuel -> RINGS
                        !spec.plumeReady -> ORBIT
                        !spec.titanProbeReady -> ENCELADUS
                        !(spec.titanFlightReady && landFuel) -> TITAN_PROBE
                        else -> TITAN_FLIGHT
                    }
                    Arrival(level >= ORBIT, level >= TITAN_PROBE, level)
                }
            }
        }
    }
}
