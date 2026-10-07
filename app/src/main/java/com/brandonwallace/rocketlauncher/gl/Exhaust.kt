package com.brandonwallace.rocketlauncher.gl

import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.Stat

/**
 * How one engine's exhaust looks. Every propulsion upgrade changes it: solid motors burn white-orange with thick
 * smoke, kerosene engines yellow-orange with soot (and a dark turbine-exhaust trail on the gas-generator cycle),
 * staged combustion cleaner with mach diamonds, methalox blue-violet with almost no smoke, nuclear upper stages
 * a long pale-blue jet and the fusion drives a collimated violet plasma beam. 1.3 (Saturn program): rotating
 * detonation engines ripple with detonation rings, the aerospike keeps a tight plume even in vacuum, beamed
 * energy burns white-hot, direct fusion glows pink-white and antimatter drives shine as a blinding white beam.
 * Colours are linear HDR (the composite pass tone-maps them).
 */
class ExhaustStyle(
    val name: String,
    /** Plume colour near the nozzle and toward the tail. */
    val core: FloatArray, val outer: FloatArray,
    /** Plume length and width multipliers. */
    val length: Float, val width: Float,
    /** Mach-diamond strength (only drawn in air) and colour. */
    val diamonds: Float, val diamondCol: FloatArray,
    /** Exhaust smoke particles per second at sea level, and their colour. */
    val smoke: Float, val smokeCol: FloatArray,
    /** Additive flame-lick particle colour. */
    val flame: FloatArray,
    /** Dark gas-generator exhaust trail (0 = none). */
    val soot: Float,
    /** Glow colour of the hot nozzle bell. */
    val glow: FloatArray,
    /** Point-light colour the flame throws on the rocket and pad. */
    val light: FloatArray,
    /** Diamond-like striations that also show in vacuum (magnetically confined plasma, detonation rings). */
    val striations: Boolean = false,
    /** How much the plume balloons out in vacuum (1 = normal; an aerospike stays tight). */
    val vacSpread: Float = 1f,
)

object Exhaust {
    private fun c(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)
    private val ORANGE_GLOW = c(1.0f, 0.35f, 0.08f)

    val SOLID = ExhaustStyle("Solid motor", c(7f, 6.2f, 4.6f), c(3.2f, 1.7f, 0.6f), 1.29f, 1f, 0f, c(1f, 0.9f, 0.75f),
        40f, c(0.92f, 0.92f, 0.93f), c(2.6f, 1.9f, 1.1f), 0f, ORANGE_GLOW, c(1f, 0.55f, 0.22f))
    val COMPOSITE = ExhaustStyle("Composite solid", c(7.6f, 6.8f, 5.2f), c(3.4f, 1.9f, 0.7f), 1.32f, 1.05f, 0f, c(1f, 0.9f, 0.75f),
        44f, c(0.95f, 0.95f, 0.96f), c(2.8f, 2.1f, 1.3f), 0f, ORANGE_GLOW, c(1f, 0.6f, 0.28f))
    val KEROSENE = ExhaustStyle("Kerosene", c(6.2f, 4.6f, 2.4f), c(3.4f, 1.2f, 0.25f), 1f, 1f, 0.35f, c(1f, 0.85f, 0.6f),
        24f, c(0.82f, 0.79f, 0.75f), c(2.4f, 1.2f, 0.35f), 0f, ORANGE_GLOW, c(1f, 0.5f, 0.18f))
    val STAGED = ExhaustStyle("Staged combustion", c(7.2f, 6.0f, 3.8f), c(3.3f, 1.5f, 0.45f), 1.08f, 1f, 1.2f, c(1f, 0.9f, 0.7f),
        18f, c(0.88f, 0.87f, 0.85f), c(2.6f, 1.6f, 0.6f), 0f, ORANGE_GLOW, c(1f, 0.6f, 0.25f))
    val FULL_FLOW = ExhaustStyle("Full-flow", c(7.6f, 6.8f, 4.8f), c(3.0f, 1.6f, 0.7f), 1.12f, 1f, 1.4f, c(1f, 0.95f, 0.8f),
        14f, c(0.92f, 0.92f, 0.91f), c(2.7f, 1.9f, 0.9f), 0f, ORANGE_GLOW, c(1f, 0.65f, 0.32f))
    val METHALOX = ExhaustStyle("Methalox", c(5.2f, 5.4f, 8.0f), c(3.6f, 1.2f, 2.6f), 1.15f, 1f, 1.6f, c(0.8f, 0.85f, 1f),
        8f, c(0.95f, 0.96f, 0.98f), c(1.6f, 1.2f, 2.6f), 0f, c(1f, 0.45f, 0.4f), c(0.75f, 0.6f, 1f))
    val HYDROLOX = ExhaustStyle("Vacuum hydrolox", c(3.6f, 4.2f, 6.5f), c(0.6f, 0.9f, 2.6f), 1f, 1f, 0f, c(0.8f, 0.9f, 1f),
        6f, c(0.95f, 0.96f, 0.98f), c(1.0f, 1.3f, 2.4f), 0f, ORANGE_GLOW, c(0.7f, 0.8f, 1f))
    val NUCLEAR = ExhaustStyle("Nuclear thermal", c(6.0f, 7.0f, 8.5f), c(1.3f, 2.0f, 4.2f), 1.35f, 0.9f, 0f, c(0.8f, 0.9f, 1f),
        4f, c(0.95f, 0.96f, 0.98f), c(1.6f, 2.2f, 3.2f), 0f, c(0.9f, 0.6f, 0.25f), c(0.75f, 0.85f, 1f))
    val PLASMA = ExhaustStyle("Fusion plasma", c(6.5f, 3.2f, 9.5f), c(2.6f, 0.5f, 4.4f), 1.7f, 0.72f, 1.0f, c(1f, 0.6f, 1f),
        0f, c(0.95f, 0.9f, 1f), c(2.2f, 0.9f, 3.2f), 0f, c(0.65f, 0.25f, 1f), c(0.8f, 0.45f, 1f), striations = true)

    // 1.3
    val DETONATION = ExhaustStyle("Rotating detonation", c(7.4f, 8.0f, 11.5f), c(2.4f, 1.6f, 4.6f), 1.25f, 1f, 2.6f, c(0.9f, 0.95f, 1f),
        6f, c(0.95f, 0.96f, 0.98f), c(1.8f, 1.6f, 3.0f), 0f, c(1f, 0.5f, 0.45f), c(0.8f, 0.75f, 1f), striations = true)
    val DIRECT_FUSION = ExhaustStyle("Direct fusion", c(9.0f, 6.0f, 9.0f), c(4.0f, 1.2f, 3.0f), 1.9f, 0.62f, 1.2f, c(1f, 0.7f, 0.95f),
        0f, c(0.95f, 0.9f, 1f), c(2.8f, 1.4f, 2.6f), 0f, c(1f, 0.35f, 0.75f), c(1f, 0.55f, 0.9f), striations = true)
    val ANTIMATTER = ExhaustStyle("Antimatter-catalyzed", c(9.5f, 10.5f, 13.5f), c(2.4f, 2.8f, 6.0f), 2.2f, 0.5f, 1.0f, c(0.9f, 0.95f, 1f),
        0f, c(0.95f, 0.96f, 1f), c(2.6f, 2.9f, 4.0f), 0f, c(0.75f, 0.85f, 1f), c(0.85f, 0.9f, 1f), striations = true)

    private fun tuned(s: ExhaustStyle, name: String = s.name, coreMul: Float = 1f, length: Float = s.length, diamonds: Float = s.diamonds,
                      smokeMul: Float = 1f, soot: Float = s.soot, width: Float = s.width, core: FloatArray? = null, outer: FloatArray? = null,
                      vacSpread: Float = s.vacSpread) =
        ExhaustStyle(name, (core ?: s.core).let { c(it[0] * coreMul, it[1] * coreMul, it[2] * coreMul) }, outer ?: s.outer, length, width,
            diamonds, s.diamondCol, s.smoke * smokeMul, s.smokeCol, s.flame, soot, s.glow, s.light, s.striations, vacSpread)

    /** First stage (and liquid side cores). */
    fun firstStage(spec: RocketSpec): ExhaustStyle {
        val regen = spec.level(Stat.REGEN) > 0
        val base = when {
            spec.has("P_BEAMED") -> tuned(DETONATION, "Laser-heated aerospike", length = 1.6f, diamonds = 3.0f, core = c(11f, 11f, 12.5f), outer = c(3.2f, 2.4f, 4.4f), vacSpread = 0.35f)
            spec.has("P_SPIKE") -> tuned(DETONATION, if (spec.has("P_RDE60")) "Mega detonation aerospike" else "Detonation aerospike", length = 1.45f, vacSpread = 0.35f)
            spec.has("P_RDE42") -> tuned(DETONATION, "Detonation cluster", length = 1.35f, diamonds = 2.9f)
            spec.has("P_RDE") -> DETONATION
            spec.has("P_TRIPROP") -> tuned(METHALOX, "Tripropellant", length = 1.42f, diamonds = 2.3f, core = c(7.6f, 8.2f, 11.0f), outer = c(3.4f, 1.6f, 3.4f))
            spec.has("P_MK4") -> tuned(METHALOX, "Methalox Mk IV", length = 1.38f, diamonds = 2.1f, core = c(6.8f, 7.4f, 11.0f), outer = c(3.8f, 1.3f, 3.0f))
            spec.has("P_SUPER33") -> tuned(METHALOX, "Methalox super booster", length = 1.4f, diamonds = 2.2f, core = c(6.4f, 7.0f, 10.5f), outer = c(4.0f, 1.3f, 3.0f))
            spec.has("P_RAPTOR3") -> tuned(METHALOX, "Methalox Mk III", length = 1.3f, diamonds = 1.9f, core = c(5.8f, 6.4f, 9.8f))
            spec.has("P_RAPTOR2") -> tuned(METHALOX, "Methalox cluster", length = 1.2f, core = c(5.6f, 6.0f, 9.0f))
            spec.has("P_METHALOX") -> METHALOX
            spec.has("P_FFSC") -> FULL_FLOW
            spec.has("P_STAGED") -> STAGED
            spec.has("P_LIQUID") -> tuned(KEROSENE, name = when { spec.has("P_GG") -> "Kerosene gas-generator"; spec.has("P_TURBO") -> "Kerosene turbopump"; else -> "Kerosene" },
                diamonds = if (spec.has("P_TURBO")) 0.7f else 0.35f,
                // open-cycle gas generator dumps a dark, fuel-rich turbine exhaust beside the plume
                soot = if (spec.has("P_GG")) 0.8f else 0f)
            spec.has("P_GRAIN") -> COMPOSITE
            else -> SOLID
        }
        // regenerative cooling: a hotter, cleaner burn; every engine level adds length
        val lv = spec.level(Stat.ENGINE).coerceAtMost(10)
        return tuned(base, coreMul = if (regen) 1.08f else 1f, smokeMul = if (regen) 0.85f else 1f, length = base.length * (1f + 0.03f * lv))
    }

    /** Upper (vacuum) stages. */
    fun upperStage(spec: RocketSpec): ExhaustStyle {
        val vac = spec.level(Stat.VACUUM)
        val base = when (spec.level(Stat.NUCLEAR)) {
            0 -> HYDROLOX
            1 -> NUCLEAR
            2 -> tuned(NUCLEAR, "Bimodal nuclear", length = 1.5f, core = c(7.0f, 8.0f, 9.5f))
            3 -> PLASMA
            4 -> tuned(PLASMA, "Fusion torch", length = 2.0f, diamonds = 1.4f, width = 0.68f, core = c(8.0f, 6.0f, 11f), outer = c(3.2f, 1.2f, 5.5f))
            5 -> DIRECT_FUSION
            6 -> tuned(DIRECT_FUSION, "Twin-reactor fusion", length = 2.1f, core = c(10.5f, 7.5f, 10.5f))
            7 -> ANTIMATTER
            else -> tuned(ANTIMATTER, "Antimatter beam", length = 2.8f, width = 0.38f, diamonds = 0.6f, core = c(13f, 13f, 15f), outer = c(3.0f, 3.5f, 7.0f))
        }
        // bigger vacuum bells: a wider, fuller plume (the plasma beam stays collimated by its magnetic nozzle)
        val widen = if (spec.level(Stat.NUCLEAR) >= 3) 1f else 1f + 0.12f * vac
        return tuned(base, width = base.width * widen)
    }

    /** Strap-on boosters: solid until the liquid Heavy side cores, which burn like the core stage. */
    fun booster(spec: RocketSpec): ExhaustStyle = when {
        spec.heavySideCores -> firstStage(spec)
        spec.has("P_GRAIN") -> COMPOSITE
        else -> SOLID
    }
}
