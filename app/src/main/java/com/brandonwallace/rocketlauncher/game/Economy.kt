package com.brandonwallace.rocketlauncher.game

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * First-time crossings along the one mission path pay a one-off height/distance bonus. These are the only
 * bonuses; there are no collectibles. (Enum names are stored in saves: never rename an entry without adding
 * it to [SaveData.MILESTONE_RENAMES].)
 */
enum class Milestone(val title: String, val altitude: Double, val bonus: Long) {
    KM1("Kilometer Club", 1_000.0, 40),
    CLOUDS("Cloud Deck", 5_000.0, 120),
    STRATOSPHERE("Stratosphere", 12_000.0, 250),
    MESOSPHERE("Mesosphere", 50_000.0, 500),
    KARMAN("Kármán Line — Space!", 100_000.0, 1_000),
    LEO("Orbital Altitude", 400_000.0, 1_500),
    MEO("Medium Earth Orbit", 2_000_000.0, 2_200),
    GEO("Geostationary Belt", 35_786_000.0, 3_500),
    CISLUNAR("Cislunar Space", 150_000_000.0, 5_000),
    MOON("Lunar Flyby — past the Moon", Physics.MOON_ARRIVAL_ALT, 12_000),
    SOI("Escaped Earth's Pull", Physics.EARTH_SOI, 4_000),
    INTERPLANETARY("Interplanetary Space", 5.0e9, 6_000),
    MARS_APPROACH("Mars Approach", 5.0e10, 9_000),
    MARS("Mars Arrival", Physics.MARS_ARRIVAL_ALT, 20_000),
    // 1.2: the mission continues past Mars to Jupiter
    ASTEROID_BELT("Asteroid Belt", Physics.ASTEROID_BELT_START, 30_000),
    JUPITER_APPROACH("Jupiter Approach", 5.0e11, 45_000),
    JUPITER("Jupiter Arrival", Physics.JUPITER_ARRIVAL_ALT, 80_000),
}

/**
 * The single continuous mission: launch → space → orbit → past the Moon → Mars transfer → Mars orbit →
 * Mars landing → past Mars to Jupiter → Jupiter orbit → Europa landing, all on one flight path. Progress is
 * derived from the save (no rewards of its own). Enum names are not stored in saves.
 */
enum class Mission(val title: String, val desc: String, val icon: String) {
    SPACE("Reach space", "Cross the Kármán line at 100 km", "🌌"),
    ORBIT("Orbital altitude", "Climb to 400 km", "🛰️"),
    MOON("Fly past the Moon", "Keep climbing past the Moon's distance (384,000 km)", "🌕"),
    MARS_TRANSFER("Mars transfer", "Escape Earth's pull on the way to Mars", "🚀"),
    MARS_ORBIT("Mars orbit", "Reach Mars with 3% fuel or a spare stage — Deep-Space Navigation saves it", "🔴"),
    MARS_LANDING("Land on Mars", "PICA-X aeroshell + supersonic parachute + landing fuel", "🔴"),
    JUPITER_TRANSFER("Cross the asteroid belt", "Fly past Mars and on into the asteroid belt toward Jupiter", "☄️"),
    JUPITER_ORBIT("Jupiter orbit", "Reach Jupiter with 5% fuel or a spare stage, inside a radiation vault", "🪐"),
    EUROPA_LANDING("Land on Europa", "Europa lander + radiation-hardened electronics + landing fuel", "🧊");

    fun done(s: SaveData): Boolean = when (this) {
        SPACE -> Milestone.KARMAN in s.milestones
        ORBIT -> Milestone.LEO in s.milestones
        MOON -> Milestone.MOON in s.milestones
        MARS_TRANSFER -> Milestone.SOI in s.milestones
        MARS_ORBIT -> s.marsOrbits > 0 || s.marsLandings > 0
        MARS_LANDING -> s.marsLandings > 0
        JUPITER_TRANSFER -> Milestone.ASTEROID_BELT in s.milestones || s.jupiterOrbits > 0 || s.europaLandings > 0
        JUPITER_ORBIT -> s.jupiterOrbits > 0 || s.europaLandings > 0
        EUROPA_LANDING -> s.europaLandings > 0
    }

    companion object {
        fun next(s: SaveData): Mission? = entries.firstOrNull { !it.done(s) }
        fun doneCount(s: SaveData): Int = entries.count { it.done(s) }
    }
}

/** Badges only (no money). Enum names are stored in saves. */
enum class Achievement(val title: String, val desc: String) {
    FIRST_FLIGHT("Liftoff!", "Complete your first flight"),
    SOUND_BARRIER("Sound Barrier", "Fly faster than 343 m/s"),
    HYPERSONIC("Hypersonic", "Fly faster than 5 km/s"),
    ESCAPE("Escape Velocity", "Reach 11 km/s"),
    TOUGH("Tough as Nails", "Survive 3 hazard hits in one flight"),
    STAGER("Staging Master", "Separate 3 stages in one flight"),
    /** Name kept from 1.0 (Moon landing) so earned badges survive; 1.1's mission flies past the Moon. */
    MOON_LANDING("Lunar Flyby", "Fly past the Moon on the way to Mars"),
    BRANCH("Specialist", "Complete an entire tech branch"),
    TREE("Chief Engineer", "Research the whole tech tree"),
    MARS_ORBIT("Red Planet Orbit", "Capture into orbit around Mars"),
    MARS_LANDING("Footprints on Mars", "Land on Mars"),
    // 1.2
    ASTEROID_BELT("Rock Hopper", "Cross into the asteroid belt"),
    JUPITER_ORBIT("King of the Planets", "Capture into orbit around Jupiter"),
    EUROPA_LANDING("Ice Explorer", "Land on Europa"),
}

/**
 * Settlement of one launch. [performancePay] is what the results screen shows as one line; it silently
 * contains the bank interest on the money left unspent at launch (by design, the player never sees interest
 * as its own line).
 */
class Settlement(
    val altitudePay: Long,
    val milestones: List<Milestone>,
    /** Whole dollars of hidden interest paid out with this launch (exact interest + carried cents, floored). */
    internal val interest: Long,
    /** Exact (unrounded) interest earned by this launch alone. */
    internal val interestExact: BigDecimal,
    /** Sub-dollar interest remainder carried in the save into the next launch's payout (0 ≤ carry < 1). */
    internal val interestCarry: BigDecimal,
) {
    val milestonePay: Long get() = milestones.sumOf { it.bonus }
    val performancePay: Long get() = altitudePay + interest
    val total: Long get() = performancePay + milestonePay
}

object Economy {
    /**
     * Hidden compound "bank" interest on money left unspent at launch: 16.769% per launch (1.2; was 6.769% in
     * 1.1). The ONLY place the rate is defined. Math is done in BigDecimal (BigDecimal.valueOf(0.16769) is exactly
     * 0.16769), so the product of a whole-dollar balance and the rate is exact to 1/100,000 of a dollar.
     */
    const val INTEREST_RATE = 0.16769
    private val RATE: BigDecimal = BigDecimal.valueOf(INTEREST_RATE)
    /** Carry precision: rate has 5 decimals and balances are whole dollars, so 5 decimals is exact. */
    const val CARRY_SCALE = 5

    /** Extra distance pay for the leg past Mars, reaching this at Jupiter (1.2). */
    const val DEEP_SPACE_PAY = 20_000.0

    /**
     * Pay for the highest altitude reached. Grows fast in the atmosphere, logarithmically in space, and past Mars
     * (Jupiter flights) by distance travelled beyond Mars. Up to Mars it is exactly the 1.1 formula.
     */
    fun altitudePay(maxAltM: Double): Long {
        val km = maxAltM / 1000.0
        if (km <= 0.0) return 0
        val low = 95.0 * km.coerceAtMost(100.0).pow(0.6)
        val high = if (km > 100.0) 420.0 * ln(km / 100.0) else 0.0
        val beyondMars = (maxAltM - Physics.MARS_ARRIVAL_ALT) / (Physics.JUPITER_ARRIVAL_ALT - Physics.MARS_ARRIVAL_ALT)
        val deep = if (beyondMars > 0.0) DEEP_SPACE_PAY * beyondMars.coerceAtMost(1.0).pow(0.7) else 0.0
        return (low + high + deep).roundToLong()
    }

    /** Exact interest on the money left unspent at launch (no rounding at all). */
    fun interestExact(unspentAtLaunch: Long): BigDecimal =
        if (unspentAtLaunch <= 0) BigDecimal.ZERO.setScale(CARRY_SCALE) else BigDecimal.valueOf(unspentAtLaunch).multiply(RATE).setScale(CARRY_SCALE, RoundingMode.UNNECESSARY)

    /**
     * Rounding rule: exact interest + the carried remainder from earlier launches is paid in whole dollars,
     * rounded DOWN; the leftover cents (0 ≤ carry < $1) stay in the save and are paid by a later launch.
     * Nothing is ever dropped and nothing is overpaid. Returns (whole dollars paid now, new carry).
     */
    fun interestPayout(unspentAtLaunch: Long, carryIn: BigDecimal = BigDecimal.ZERO): Pair<Long, BigDecimal> {
        val due = interestExact(unspentAtLaunch).add(carryIn.max(BigDecimal.ZERO))
        val paid = due.setScale(0, RoundingMode.FLOOR)
        return paid.longValueExact() to due.subtract(paid).setScale(CARRY_SCALE, RoundingMode.UNNECESSARY)
    }

    /** Whole dollars of interest paid now for a balance with no carried cents (convenience for tests/UI). */
    fun interest(unspentAtLaunch: Long): Long = interestPayout(unspentAtLaunch).first

    fun newMilestones(maxAlt: Double, already: Set<Milestone>): List<Milestone> =
        Milestone.entries.filter { maxAlt >= it.altitude && it !in already }

    fun settle(unspentAtLaunch: Long, maxAlt: Double, already: Set<Milestone>, carryIn: BigDecimal = BigDecimal.ZERO): Settlement {
        val (paid, carry) = interestPayout(unspentAtLaunch, carryIn)
        return Settlement(altitudePay(maxAlt), newMilestones(maxAlt, already), paid, interestExact(unspentAtLaunch), carry)
    }
}

/**
 * Immutable save game, serialized as "k=v;" (no Android deps, unit-testable). Stored in SharedPreferences file
 * "rocket_launcher", key "save" — never change either, or updates would lose progress.
 *
 * Schema versions (key "v"):
 *  - 1: pre-versioned saves (no "v" key) — same keys as 2.
 *  - 2: Rocket Launcher 1.0 (Moon/Mars destination picker "dest", tilt steering "tilt").
 *  - 3: Rocket Launcher 1.1 — single Moon→Mars mission (no "dest"), autopilot only (no "tilt"), hidden
 *       interest remainder "ic".
 *  - 4: Rocket Launcher 1.2 — the mission continues to Jupiter: career counters "jupOrbit" and "europa".
 * Rules for future builds: bump [SAVE_SCHEMA_VERSION], add a step to [MIGRATIONS] that renames/removes keys,
 * give every new field a default. Unknown keys (e.g. written by a newer build) are kept in [extras] and written
 * back untouched, so nothing is ever wiped.
 */
data class SaveData(
    val money: Long = 0,
    val tech: Set<String> = emptySet(),
    val milestones: Set<Milestone> = emptySet(),
    val achievements: Set<Achievement> = emptySet(),
    val bestAltitude: Double = 0.0,
    val bestSpeed: Double = 0.0,
    val flights: Int = 0,
    val totalEarned: Long = 0,
    /** Moon landings made in 1.0 (kept as a career stat; 1.1 flies past the Moon instead). */
    val moonLandings: Int = 0,
    val marsOrbits: Int = 0,
    val marsLandings: Int = 0,
    val jupiterOrbits: Int = 0,
    val europaLandings: Int = 0,
    val soundOn: Boolean = true,
    /** 0 = Low, 1 = Medium, 2 = High. */
    val quality: Int = 1,
    /** Hidden interest cents not yet paid out (0 ≤ carry < $1, scale 5). Never shown. */
    val interestCarry: BigDecimal = BigDecimal.ZERO.setScale(Economy.CARRY_SCALE),
    /** Keys this build doesn't know (from a newer build); preserved verbatim. */
    val extras: Map<String, String> = emptyMap(),
) {
    fun has(id: String) = id in tech
    fun canResearch(node: TechNode) = TechTree.isAvailable(this, node) && money >= node.cost

    /** New save after researching a node, or null if locked / unaffordable / owned. */
    fun research(id: String): SaveData? {
        val node = TechTree.byId[id] ?: return null
        if (!canResearch(node)) return null
        return copy(money = money - node.cost, tech = tech + id)
    }

    fun serialize(): String = buildString {
        append("v=").append(SAVE_SCHEMA_VERSION)
        append(";money=").append(money)
        append(";bestAlt=").append(bestAltitude)
        append(";bestSpd=").append(bestSpeed)
        append(";flights=").append(flights)
        append(";earned=").append(totalEarned)
        append(";moon=").append(moonLandings)
        append(";marsOrbit=").append(marsOrbits)
        append(";mars=").append(marsLandings)
        append(";jupOrbit=").append(jupiterOrbits)
        append(";europa=").append(europaLandings)
        append(";sound=").append(soundOn)
        append(";q=").append(quality)
        append(";ic=").append(interestCarry.setScale(Economy.CARRY_SCALE, RoundingMode.FLOOR).toPlainString())
        append(";tech=").append(tech.sorted().joinToString(","))
        append(";ms=").append(milestones.joinToString(",") { it.name })
        append(";ach=").append(achievements.joinToString(",") { it.name })
        for ((k, v) in extras.toSortedMap()) append(';').append(k).append('=').append(v)
    }

    companion object {
        /** Current save schema. See the class doc for the history. */
        const val SAVE_SCHEMA_VERSION = 4

        /** Keys written by [serialize]; anything else found in a save is preserved in [extras]. */
        val KNOWN_KEYS = setOf("v", "money", "bestAlt", "bestSpd", "flights", "earned", "moon", "marsOrbit", "mars", "jupOrbit", "europa", "sound", "q", "ic", "tech", "ms", "ach")

        /** Tech/milestone/achievement ids renamed between builds (old -> new). Empty so far; add, never remove. */
        val TECH_RENAMES: Map<String, String> = emptyMap()
        val MILESTONE_RENAMES: Map<String, String> = emptyMap()
        val ACHIEVEMENT_RENAMES: Map<String, String> = emptyMap()

        /** MIGRATIONS[n] upgrades a schema-n key map to schema n+1 in place. */
        private val MIGRATIONS: Map<Int, (MutableMap<String, String>) -> Unit> = mapOf(
            1 to { _ -> },                                   // unversioned -> 2: identical keys
            2 to { m ->                                      // 1.0 -> 1.1
                m.remove("dest")                             // destination picker removed: one Moon→Mars mission
                m.remove("tilt")                             // tilt steering removed: autopilot only
                m.putIfAbsent("ic", "0")                     // new: hidden interest remainder
            },
            3 to { m ->                                      // 1.1 -> 1.2: Jupiter leg (money, tech, milestones untouched)
                m.putIfAbsent("jupOrbit", "0")
                m.putIfAbsent("europa", "0")
            },
        )

        /** Schema version of a raw save string (1 for pre-versioned, 0 for empty). */
        fun schemaOf(s: String?): Int {
            if (s.isNullOrBlank()) return 0
            return rawMap(s)["v"]?.toIntOrNull() ?: 1
        }

        private fun rawMap(s: String): LinkedHashMap<String, String> {
            val m = LinkedHashMap<String, String>()
            for (part in s.split(';')) {
                val i = part.indexOf('=')
                if (i > 0) m[part.substring(0, i)] = part.substring(i + 1)
            }
            return m
        }

        /** Runs every migration step from the save's version up to [SAVE_SCHEMA_VERSION]. */
        fun migrate(raw: Map<String, String>): Map<String, String> {
            val m = LinkedHashMap(raw)
            var v = m["v"]?.toIntOrNull() ?: 1
            while (v < SAVE_SCHEMA_VERSION) {
                MIGRATIONS[v]?.invoke(m)
                v++
            }
            m["v"] = maxOf(v, SAVE_SCHEMA_VERSION).toString()
            return m
        }

        fun parse(s: String?): SaveData {
            if (s.isNullOrBlank()) return SaveData()
            val m = migrate(rawMap(s))
            fun list(k: String) = m[k].orEmpty().split(',').filter { it.isNotBlank() }
            val carry = m["ic"]?.let { runCatching { BigDecimal(it) }.getOrNull() }
                ?.takeIf { it.signum() >= 0 && it < BigDecimal.ONE } ?: BigDecimal.ZERO
            return SaveData(
                money = m["money"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                tech = list("tech").map { TECH_RENAMES[it] ?: it }.filter { it in TechTree.byId }.toSet(),
                milestones = list("ms").map { MILESTONE_RENAMES[it] ?: it }.mapNotNull { n -> Milestone.entries.firstOrNull { it.name == n } }.toSet(),
                achievements = list("ach").map { ACHIEVEMENT_RENAMES[it] ?: it }.mapNotNull { n -> Achievement.entries.firstOrNull { it.name == n } }.toSet(),
                bestAltitude = m["bestAlt"]?.toDoubleOrNull() ?: 0.0,
                bestSpeed = m["bestSpd"]?.toDoubleOrNull() ?: 0.0,
                flights = m["flights"]?.toIntOrNull() ?: 0,
                totalEarned = m["earned"]?.toLongOrNull() ?: 0,
                moonLandings = m["moon"]?.toIntOrNull() ?: 0,
                marsOrbits = m["marsOrbit"]?.toIntOrNull() ?: 0,
                marsLandings = m["mars"]?.toIntOrNull() ?: 0,
                jupiterOrbits = m["jupOrbit"]?.toIntOrNull() ?: 0,
                europaLandings = m["europa"]?.toIntOrNull() ?: 0,
                soundOn = m["sound"]?.toBooleanStrictOrNull() ?: true,
                quality = (m["q"]?.toIntOrNull() ?: 1).coerceIn(0, 2),
                interestCarry = carry.setScale(Economy.CARRY_SCALE, RoundingMode.FLOOR),
                extras = m.filterKeys { it !in KNOWN_KEYS && it.isNotBlank() },
            )
        }
    }
}

fun formatMoney(v: Long): String = "$" + "%,d".format(v)
