package com.brandonwallace.rocketlauncher.game

import kotlin.math.abs

/**
 * 1.1: the rocket flies itself. There is no player throttle or steering — every decision here is a pure,
 * deterministic function of the researched tech and the flight state, so the outcome of a launch is decided
 * by upgrades and staging in the tech tree.
 *
 *  - Throttle: full burn from liftoff (stages and boosters separate automatically as they run dry).
 *    The Digital Flight Computer (avionics 3) eases off through peak heating (see [FlightState.step]).
 *    Deep-Space Navigation (avionics 4) cuts the engines as soon as the coast alone reaches Mars, keeping the
 *    remaining fuel for the Mars capture and landing burns. Without it the autopilot burns every drop.
 *  - Hazards: the autopilot steers around obstacles it can see. How far ahead it sees depends on avionics
 *    (radio guidance adds the hazard radar), how hard it can turn depends on fins + avionics
 *    ([RocketSpec.maxTiltRad], [RocketSpec.steerResponse]); the hull upgrades decide how many hits it survives.
 */
object Autopilot {
    /** Engines cut once the coast reaches Mars (needs Deep-Space Navigation). */
    fun navCut(spec: RocketSpec, alt: Double, vy: Double): Boolean =
        spec.navAutoCut && alt > 160_000 && Physics.bound(alt, vy, Physics.MARS_ARRIVAL_ALT)

    /** Throttle command 0..1 for this step. */
    fun throttle(spec: RocketSpec, f: FlightState): Double = if (navCut(spec, f.alt, f.vy)) 0.0 else 1.0

    /** How far ahead (visual metres) the autopilot reacts to hazards: eyeballing → radar → inertial/computer. */
    fun lookahead(spec: RocketSpec): Float = when (spec.level(Stat.AVIONICS)) {
        0 -> 12f
        1 -> 38f
        2 -> 52f
        else -> 64f
    }

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

/** Outcome at Mars: shared by the live engine and the offline simulation so they always agree. */
class MarsArrival(val orbit: Boolean, val landed: Boolean) {
    companion object {
        fun evaluate(spec: RocketSpec, stage: Int, remainingFuel: Double): MarsArrival {
            val last = spec.stages.last().fuelMass
            val spare = stage < spec.stages.size - 1
            val capture = spare || remainingFuel >= RocketSpec.MARS_CAPTURE_FUEL_FRAC * last
            val landFuel = spare || remainingFuel >= spec.marsLandingFuelFrac * last
            return MarsArrival(capture, capture && landFuel && spec.marsEntryReady)
        }
    }
}
