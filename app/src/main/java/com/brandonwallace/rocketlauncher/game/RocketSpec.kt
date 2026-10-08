package com.brandonwallace.rocketlauncher.game

import kotlin.math.PI
import kotlin.math.ln

class StageSpec(
    val dryMass: Double,
    val fuelMass: Double,
    val thrust: Double,   // N
    val isp: Double,      // s
    val length: Float,    // m (visual)
    val radius: Float,    // m (visual)
)

/** Everything about the rocket that derives from the researched tech. */
class RocketSpec(val tech: Set<String>) {
    private val lv = TechTree.levels(tech).let { m -> Stat.entries.associateWith { (m[it] ?: 0).coerceIn(0, it.max) } }
    fun level(t: Stat) = lv.getValue(t)
    fun has(id: String) = id in tech

    companion object {
        // Every table has one entry per stat level (0..Stat.max). 1.1 values are unchanged; 1.2 appended the Jupiter
        // program and 1.3 the Saturn program.
        val THRUST_KN = doubleArrayOf(16.0, 26.0, 42.0, 68.0, 110.0, 180.0, 300.0, 470.0, 720.0, 1050.0, 1500.0,
            2000.0, 2700.0, 3500.0, 4400.0, 5400.0, 6500.0, 7800.0)
        val ISP = doubleArrayOf(200.0, 222.0, 246.0, 272.0, 298.0, 325.0, 352.0, 366.0, 378.0, 386.0, 394.0,
            398.0, 402.0, 407.0, 411.0, 415.0, 418.0, 421.0)
        val FUEL_KG = doubleArrayOf(90.0, 190.0, 400.0, 820.0, 1650.0, 3200.0, 6200.0, 10500.0, 17000.0, 25000.0, 34000.0, 44000.0,
            50000.0, 57000.0, 64000.0, 72000.0, 80000.0, 89000.0, 98000.0, 108000.0, 118000.0, 129000.0, 140000.0, 152000.0)
        val CD = doubleArrayOf(0.85, 0.66, 0.52, 0.42, 0.34, 0.27, 0.22, 0.19, 0.17, 0.155, 0.14, 0.13, 0.12)
        val HULL = doubleArrayOf(100.0, 160.0, 240.0, 340.0, 470.0, 650.0, 900.0, 1250.0, 1700.0,
            2200.0, 2800.0, 3500.0, 4300.0, 5200.0, 6200.0, 7300.0, 8500.0)
        val HEAT_TOL = doubleArrayOf(1.0, 2.0, 3.6, 6.5, 10.0, 14.0, 19.0, 25.0, 32.0)
        val MAX_TILT_DEG = floatArrayOf(14f, 18f, 23f, 28f, 34f, 40f, 46f, 52f, 55f, 58f, 61f, 64f, 67f, 70f)
        val STEER_RESP = floatArrayOf(2.2f, 3.0f, 4.0f, 5.2f, 6.5f, 8.0f, 9.5f, 11f, 12f, 13f, 14f, 15f, 16f, 17f)
        val UPPER_FUEL_FRAC = doubleArrayOf(0.70, 0.46, 0.30, 0.20, 0.14, 0.10, 0.07)
        val UPPER_THRUST_FRAC = doubleArrayOf(0.85, 0.6, 0.4, 0.28, 0.2, 0.15, 0.11)
        /** Upper stage k length (fraction of stage 1) and radius (fraction of the core). 1.2 used 0.75-0.12k / 0.96-0.06k. */
        val UPPER_LEN_FRAC = floatArrayOf(0.75f, 0.63f, 0.51f, 0.39f, 0.27f, 0.2f, 0.16f)
        /**
         * Upper-stage Isp bonus per NUCLEAR level: nuclear-thermal (1-2), fusion plasma (3-4), direct fusion (5-6),
         * antimatter (7-8).
         */
        val NUCLEAR_ISP = doubleArrayOf(0.0, 110.0, 170.0, 300.0, 420.0, 500.0, 580.0, 680.0, 800.0)
        /** Separation delay by SEP level: springs, pyrotechnic, hot staging, zero-gap latches, laser cutting. */
        val SEP_TIME = doubleArrayOf(0.45, 0.15, 0.06, 0.03, 0.015)
        /** Jupiter gravity assist on Saturn flights: speed added when the rocket swings past Jupiter (m/s) per SLING level. */
        val SLING_KICK = doubleArrayOf(0.0, 2000.0, 4000.0, 6000.0, 8000.0)
        /**
         * Cruise propulsion (sail, ion engines, electric/magnetic sail, lightsail): a steady outward push (m/s²)
         * past Earth's pull, per CRUISE level. Always weaker than the Sun's pull, so every coast still ends.
         */
        val CRUISE_ACCEL = doubleArrayOf(0.0, 4.0e-5, 8.0e-5, 1.2e-4, 1.6e-4, 2.0e-4)
        /** Ring-ice damage multiplier per RINGS level (the first level is the ring-plane shield itself). */
        val ICE_DAMAGE = doubleArrayOf(1.0, 1.0, 0.6, 0.35)
        /** Saturn orbit insertion fuel (fraction of last stage) per AEROCAP level: 6%, aerocapture shield 4%, ballute 2%, Titan assist 1%. */
        val SATURN_CAPTURE_FUEL = doubleArrayOf(0.06, 0.04, 0.02, 0.01)
        const val RADIUS = 0.85f
        const val PAYLOAD_KG = 220.0
        const val VAC2_BONUS = 20.0
        /** Mars: fuel (fraction of last stage) needed on arrival for the orbit-capture burn. */
        const val MARS_CAPTURE_FUEL_FRAC = 0.03
        /** Jupiter orbit insertion: fuel (fraction of last stage) needed on arrival. */
        const val JUPITER_CAPTURE_FUEL_FRAC = 0.05
        /** Tanks are only part-filled if full ones would make liftoff thrust-to-weight lower than this. */
        const val MIN_TWR = 1.1
    }

    val thrust1 = THRUST_KN[level(Stat.ENGINE)] * 1000.0
    /** Saturn-era engines push harder without getting heavier: engine mass stops growing past the 1.2 top engine. */
    private val engineMassScale = (THRUST_KN[10] / THRUST_KN[level(Stat.ENGINE)]).coerceAtMost(1.0)
    val isp1 = ISP[level(Stat.ENGINE)] + 8.0 * level(Stat.REGEN)
    /** Vacuum nozzle: +14 s at level 1, +34 s at level 2 (the niobium extension: the landing margin), +28 s per level from 3. */
    val upperIsp = isp1 + 42.0 + 14.0 * level(Stat.VACUUM) + (if (level(Stat.VACUUM) >= 2) VAC2_BONUS else 0.0) +
        14.0 * (level(Stat.VACUUM) - 2).coerceAtLeast(0) + NUCLEAR_ISP[level(Stat.NUCLEAR)]
    /** Tank size the FUEL research allows. */
    val tankFuel1 = FUEL_KG[level(Stat.FUEL)]

    /** Saturn-era tanks get wider as well as longer (visual only; drag keeps using [RADIUS]). */
    val girth: Float = 1f + 0.035f * (level(Stat.FUEL) - 11).coerceAtLeast(0)

    val boosterCount: Int = when (level(Stat.BOOSTERS)) { 0 -> 0; 3, 5 -> 4; 6 -> 6; else -> 2 }
    /** Level 4: two liquid side cores (Heavy-style) instead of solid boosters; level 5: four; level 6: six. */
    val heavySideCores = level(Stat.BOOSTERS) >= 4

    private fun stageStack(f1: Double): List<StageSpec> = buildList {
        val fl = level(Stat.FUEL)
        val len1 = 3.2f + 0.85f * fl.coerceAtMost(11) + 0.45f * (fl - 11).coerceAtLeast(0)
        val r1 = RADIUS * girth
        add(StageSpec(dryMass(f1, thrust1, 230.0), f1, thrust1, isp1, len1, r1))
        for (k in 0 until level(Stat.STAGES)) {
            val f = f1 * UPPER_FUEL_FRAC[k]
            val t = thrust1 * UPPER_THRUST_FRAC[k]
            add(StageSpec(dryMass(f, t, 45.0, 0.06, 0.8), f, t, upperIsp, len1 * UPPER_LEN_FRAC[k], r1 * (0.96f - 0.06f * k)))
        }
    }

    private fun boosterOf(f1: Double, len1: Float): StageSpec? = if (boosterCount == 0) null else if (heavySideCores) {
        val f = f1 * 0.95; val t = thrust1 * 1.0
        StageSpec(dryMass(f, t, 60.0), f, t, isp1 - 4.0, len1 * 0.95f, 0.72f * girth)
    } else {
        val big = level(Stat.BOOSTERS) >= 2
        val f = f1 * (if (big) 0.42 else 0.26)
        val t = thrust1 * (if (big) 0.52 else 0.36)
        StageSpec(dryMass(f, t, 35.0), f, t, isp1 - 15.0, len1 * (if (big) 0.85f else 0.68f), if (big) 0.46f else 0.38f)
    }

    private fun liftoffMassFor(f1: Double): Double {
        val st = stageStack(f1); val b = boosterOf(f1, st[0].length)
        return payloadMassOf() + st.sumOf { it.dryMass + it.fuelMass } + boosterCount * ((b?.dryMass ?: 0.0) + (b?.fuelMass ?: 0.0))
    }

    /**
     * Stage-1 propellant actually flown. Tanks bigger than the engines can lift are not built bigger: the load is
     * capped so liftoff thrust-to-weight never drops below [MIN_TWR]. Researching tanks before engines can never
     * ground the rocket or make it worse; the extra tankage pays off once the engines catch up.
     */
    val fuel1: Double = run {
        val thrust = thrust1 + boosterCount * (boosterOf(tankFuel1, 1f)?.thrust ?: 0.0)
        val maxMass = thrust / (Physics.G0 * MIN_TWR)
        if (liftoffMassFor(tankFuel1) <= maxMass) tankFuel1 else {
            // liftoff mass is linear in the stage-1 load: solve m0 + k * f1 = maxMass
            val m0 = liftoffMassFor(0.0); val k = (liftoffMassFor(1000.0) - m0) / 1000.0
            ((maxMass - m0) / k).coerceIn(FUEL_KG[0], tankFuel1)
        }
    }
    /** Fraction of the researched tank size that the engines can lift (1 = all of it). */
    val fuelLoad: Double = fuel1 / tankFuel1

    val stages: List<StageSpec> = stageStack(fuel1)
    val booster: StageSpec? = boosterOf(fuel1, stages[0].length)

    val payloadMass = payloadMassOf()
    private fun payloadMassOf() = PAYLOAD_KG + 35.0 * level(Stat.HULL) + 30.0 * level(Stat.HEAT) +
        40.0 * level(Stat.RADIATION) + 60.0 * level(Stat.LANDER) + 20.0 * level(Stat.POWER) +
        40.0 * level(Stat.RINGS) + 35.0 * level(Stat.AEROCAP) + 45.0 * level(Stat.TITAN) + 20.0 * level(Stat.PLUME) + 30.0 * level(Stat.CRUISE)
    val cd = CD[level(Stat.AERO)]
    val area = PI * RADIUS * RADIUS + boosterCount * 0.28
    val maxHull = HULL[level(Stat.HULL)]
    val heatTolerance = HEAT_TOL[level(Stat.HEAT)]
    val maxTiltRad = Math.toRadians(MAX_TILT_DEG[level(Stat.FINS)].toDouble() + 3.0 * level(Stat.AVIONICS).coerceAtMost(2))
    val steerResponse = STEER_RESP[level(Stat.FINS)] * (1f + 0.2f * level(Stat.AVIONICS).coerceAtMost(4) + 0.06f * (level(Stat.AVIONICS) - 6).coerceAtLeast(0)) *
        (1f + 0.12f * level(Stat.GIMBAL))
    /** Digital flight computer throttles back through max-Q heating. */
    val autoThrottle = level(Stat.AVIONICS) >= 3
    /** Radio guidance: the autopilot sees hazards early (and the HUD shows them). */
    val hazardRadar = level(Stat.AVIONICS) >= 1
    /** Deep-space navigation (avionics 4): the autopilot cuts the engines once the coast reaches the target. */
    val navAutoCut = level(Stat.AVIONICS) >= 4
    /** Juno-class solar arrays (power 1): the nav computer keeps working past Mars. */
    val deepSpacePower = level(Stat.POWER) >= 1
    /** Next-gen RTGs (power 3): the nav computer keeps working past Jupiter, all the way to Saturn. */
    val saturnNavPower = level(Stat.POWER) >= 3
    val separationTime = SEP_TIME[level(Stat.SEP)]
    val engineCount: Int = when {
        has("P_RDE60") -> 60; has("P_RDE42") -> 42; has("P_SUPER33") -> 33; has("P_RAPTOR3") -> 20; has("P_RAPTOR2") -> 13
        has("P_FFSC") -> 9; has("P_STAGED") -> 3; else -> 1
    }
    /** Mars entry, descent & landing needs a Mars-rated aeroshell and a supersonic parachute. */
    val marsEntryReady: Boolean = level(Stat.HEAT) >= 4 && level(Stat.EDL) >= 1
    /** Landing fuel (fraction of last stage); retropropulsion halves it. */
    val marsLandingFuelFrac: Double = if (level(Stat.EDL) >= 2) 0.04 else 0.08
    /** Jupiter orbit needs the radiation vault (the belts fry unshielded electronics). */
    val jupiterRadiationReady: Boolean = level(Stat.RADIATION) >= 1
    /** A Europa landing needs the Europa lander and radiation-hardened electronics. */
    val europaLandingReady: Boolean = level(Stat.LANDER) >= 1 && level(Stat.RADIATION) >= 2
    /** Europa landing fuel (fraction of last stage); the sky-crane halves it. */
    val europaLandingFuelFrac: Double = if (level(Stat.LANDER) >= 2) 0.04 else 0.08

    // ---- 1.3: the Saturn program
    /** Speed gained from the Jupiter flyby on Saturn flights (m/s). */
    val slingKick: Double = SLING_KICK[level(Stat.SLING)]
    /** Steady outward push from cruise propulsion past Earth's pull (m/s²). */
    val cruiseAccel: Double = CRUISE_ACCEL[level(Stat.CRUISE)]
    /** Crossing Saturn's ring plane (needed for Saturn orbit) needs the ring-plane dust shield. */
    val ringShield: Boolean = level(Stat.RINGS) >= 1
    /** Damage multiplier for ring-ice hits. */
    val iceDamageMul: Double = ICE_DAMAGE[level(Stat.RINGS)]
    /** Saturn orbit insertion fuel (fraction of last stage); aerocapture lowers it. */
    val saturnCaptureFuelFrac: Double = SATURN_CAPTURE_FUEL[level(Stat.AEROCAP)]
    /** Flying through Enceladus' plumes (from Saturn orbit) needs the plume mass spectrometer. */
    val plumeReady: Boolean = level(Stat.PLUME) >= 1
    /** A probe landing on Titan needs the Titan entry aeroshell and the Huygens-class probe. */
    val titanProbeReady: Boolean = level(Stat.TITAN) >= 2
    /** What a Titan probe still needs, for hints and results (the aeroshell comes first in the tree). */
    val titanProbeMissing: String get() = if (has("TH_TITANSHELL")) "the Huygens-Class Probe" else "the Titan Entry Aeroshell and Huygens-Class Probe"
    /** Flying on Titan needs the Dragonfly rotorcraft (and landing fuel). */
    val titanFlightReady: Boolean = level(Stat.TITAN) >= 3
    /** Titan landing fuel (fraction of last stage); the descent stage halves it. */
    val titanLandingFuelFrac: Double = if (level(Stat.TITAN) >= 4) 0.03 else 0.06

    private fun dryMass(fuel: Double, thrustN: Double, base: Double, frac: Double = 0.085, perKn: Double = 1.6) = base + frac * fuel + perKn * engineMassScale * thrustN / 1000.0

    fun liftoffMass(): Double =
        payloadMass + stages.sumOf { it.dryMass + it.fuelMass } + boosterCount * ((booster?.dryMass ?: 0.0) + (booster?.fuelMass ?: 0.0))

    fun liftoffTwr(): Double =
        (thrust1 + boosterCount * (booster?.thrust ?: 0.0)) / (liftoffMass() * Physics.G0)

    /** Ideal (no gravity/drag) delta-v, boosters approximated as burning with stage 1. */
    fun idealDeltaV(): Double {
        var m = liftoffMass()
        var dv = 0.0
        val b = booster
        if (b != null) {
            val flowB = boosterCount * b.thrust / (b.isp * Physics.G0)
            val flow1 = stages[0].thrust / (stages[0].isp * Physics.G0)
            val tb = b.fuelMass / (b.thrust / (b.isp * Physics.G0))
            val burned = flowB * tb + flow1 * tb
            val ispEff = (boosterCount * b.thrust + stages[0].thrust) / ((flowB + flow1) * Physics.G0)
            dv += ispEff * Physics.G0 * ln(m / (m - burned))
            m -= burned + boosterCount * b.dryMass
            val left = stages[0].fuelMass - flow1 * tb
            dv += stages[0].isp * Physics.G0 * ln(m / (m - left))
            m -= left + stages[0].dryMass
            for (s in stages.drop(1)) { dv += s.isp * Physics.G0 * ln(m / (m - s.fuelMass)); m -= s.fuelMass + s.dryMass }
        } else {
            for (s in stages) { dv += s.isp * Physics.G0 * ln(m / (m - s.fuelMass)); m -= s.fuelMass + s.dryMass }
        }
        return dv
    }

}
