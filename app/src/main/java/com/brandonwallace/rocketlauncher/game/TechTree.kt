package com.brandonwallace.rocketlauncher.game

/** Internal stat lines that the physics tables are indexed by. Tech nodes add levels to these. */
enum class Stat(val max: Int) {
    ENGINE(8), FUEL(8), AERO(6), HULL(6), STAGES(4), BOOSTERS(4), HEAT(4), FINS(5),
    AVIONICS(4), VACUUM(3), REGEN(1), SEP(1), NUCLEAR(2), EDL(2),
}

enum class Branch(val title: String, val color: Long, val icon: String) {
    PROPULSION("Propulsion", 0xFFFF7A3D, "🔥"),
    STRUCTURES("Structures", 0xFF9FB4C8, "🧱"),
    STAGING("Staging", 0xFFB38CFF, "🚀"),
    AERO("Aerodynamics", 0xFF5CD3FF, "🔺"),
    AVIONICS("Avionics", 0xFF6CFFB0, "📡"),
    THERMAL("Thermal", 0xFFFF5A7A, "♨️"),
}

class TechNode(
    val id: String,
    val branch: Branch,
    val title: String,
    val desc: String,
    /** Column in the graph (0..8) — also drives cost. */
    val tier: Int,
    /** Row in the graph. */
    val row: Int,
    val prereqs: List<String>,
    val effects: Map<Stat, Int>,
    /** What visibly changes on the rocket. */
    val visual: String,
    costFactor: Double = 1.0,
) {
    val cost: Long = Math.round(TechTree.TIER_COST[tier] * costFactor / 5.0) * 5
}

/**
 * The research tree. Pure Kotlin so it can be unit-tested; RocketSpec reads stat levels from it and
 * RocketModel reads the owned node ids for visuals.
 */
object TechTree {
    /**
     * Base price per tier (column). Tiers 1–6 are tuned so the Moon takes 25–35 flights; tiers 7–9 are the
     * Mars program (see EconomyTest / SmokeTest for the campaign simulations).
     */
    val TIER_COST = doubleArrayOf(42.5, 127.5, 340.0, 977.5, 2465.0, 4760.0, 16000.0, 32000.0, 52000.0)

    private fun n(
        id: String, b: Branch, title: String, desc: String, tier: Int, row: Int, pre: List<String>,
        fx: Map<Stat, Int>, visual: String, cf: Double = 1.0,
    ) = TechNode(id, b, title, desc, tier, row, pre, fx, visual, cf)

    val nodes: List<TechNode> = listOf(
        // ---------------- Propulsion
        n("P_GRAIN", Branch.PROPULSION, "Composite Propellant", "Hotter, denser solid grain.", 0, 0, listOf(), mapOf(Stat.ENGINE to 1), "Bigger solid-motor nozzle"),
        n("P_LIQUID", Branch.PROPULSION, "Liquid-Fuel Engine", "Pump-fed kerosene engine replaces the solid motor.", 1, 0, listOf("P_GRAIN"), mapOf(Stat.ENGINE to 1), "Gimballed bell engine"),
        n("P_TURBO", Branch.PROPULSION, "Turbopump Feed", "High-pressure turbopumps.", 2, 0, listOf("P_LIQUID"), mapOf(Stat.ENGINE to 1), "Turbopump & plumbing"),
        n("P_GG", Branch.PROPULSION, "Gas-Generator Cycle", "Open-cycle engine with a turbine exhaust.", 3, 0, listOf("P_TURBO"), mapOf(Stat.ENGINE to 1), "Larger bell + turbine exhaust"),
        n("P_STAGED", Branch.PROPULSION, "Staged Combustion", "Closed cycle, 3-engine cluster.", 4, 0, listOf("P_GG", "S_ALLI"), mapOf(Stat.ENGINE to 1), "Three-engine cluster"),
        n("P_FFSC", Branch.PROPULSION, "Full-Flow Staged Combustion", "Nine high-pressure engines.", 5, 0, listOf("P_STAGED", "TH_ABL"), mapOf(Stat.ENGINE to 1), "Nine-engine cluster"),
        n("TH_REGEN", Branch.THERMAL, "Regenerative Cooling", "Fuel-cooled chambers: +8 s Isp on every engine.", 3, 1, listOf("P_TURBO", "TH_CORK"), mapOf(Stat.REGEN to 1), "Cooling-tube ribs on bells", 0.8),
        n("P_VAC", Branch.PROPULSION, "Vacuum-Optimized Nozzles", "Upper stages get big expansion bells (+14 s Isp).", 4, 1, listOf("P_GG", "ST_2"), mapOf(Stat.VACUUM to 1), "Huge upper-stage bell", 1.3),
        n("P_VAC2", Branch.PROPULSION, "Niobium Nozzle Extensions", "Radiatively cooled extensions (+14 s Isp).", 5, 1, listOf("P_VAC"), mapOf(Stat.VACUUM to 1), "Dark nozzle extension", 0.8),
        n("B_SRB", Branch.PROPULSION, "Strap-on Solid Boosters", "Two small boosters for liftoff.", 2, 2, listOf("P_GRAIN", "S_ALU"), mapOf(Stat.BOOSTERS to 1), "2 side boosters", 1.5),
        n("B_LARGE", Branch.PROPULSION, "Segmented Boosters", "Two big segmented boosters.", 3, 2, listOf("B_SRB", "S_STRETCH"), mapOf(Stat.BOOSTERS to 1), "Bigger segmented boosters", 1.3),
        n("B_QUAD", Branch.PROPULSION, "Quad Booster Cluster", "Four big boosters.", 5, 2, listOf("B_LARGE", "P_STAGED"), mapOf(Stat.BOOSTERS to 1), "4 side boosters", 0.9),
        // ---------------- Structures
        n("S_ALU", Branch.STRUCTURES, "Aluminum Airframe", "Lighter, tougher airframe; slightly larger tank.", 0, 3, listOf(), mapOf(Stat.HULL to 1, Stat.FUEL to 1), "Brushed aluminum skin"),
        n("S_STRETCH", Branch.STRUCTURES, "Stretched Tanks", "Longer propellant tanks.", 1, 3, listOf("S_ALU"), mapOf(Stat.FUEL to 1), "Longer stages"),
        n("S_ALLI", Branch.STRUCTURES, "Al-Li Alloy Tanks", "Aluminum-lithium: more fuel, more strength.", 2, 3, listOf("S_STRETCH"), mapOf(Stat.FUEL to 1, Stat.HULL to 1), "Longer, panelled stages"),
        n("S_COMP", Branch.STRUCTURES, "Carbon Composites", "Carbon-fibre structure.", 3, 3, listOf("S_ALLI"), mapOf(Stat.FUEL to 1, Stat.HULL to 1), "Black carbon-fibre skin"),
        n("S_CRYO", Branch.STRUCTURES, "Lightweight Cryo Tanks", "Foam-insulated cryogenic tanks.", 4, 3, listOf("S_COMP"), mapOf(Stat.FUEL to 1), "Orange foam insulation"),
        n("S_BALLOON", Branch.STRUCTURES, "Stainless Balloon Tanks", "Pressure-stabilised steel: huge & tough.", 5, 3, listOf("S_CRYO", "S_MONO"), mapOf(Stat.FUEL to 1, Stat.HULL to 1), "Polished stainless steel"),
        n("S_MONO", Branch.STRUCTURES, "Composite Monocoque", "Stiffer shell shrugs off impacts.", 4, 4, listOf("S_COMP"), mapOf(Stat.HULL to 1), "Reinforcement bands", 0.45),
        // ---------------- Staging
        n("ST_2", Branch.STAGING, "Two-Stage Design", "Stack a second stage.", 2, 5, listOf("S_STRETCH", "P_LIQUID"), mapOf(Stat.STAGES to 1), "Second stage + interstage", 2.0),
        n("ST_3", Branch.STAGING, "Three-Stage Stack", "Add a third stage.", 3, 5, listOf("ST_2", "P_TURBO"), mapOf(Stat.STAGES to 1), "Third stage", 1.8),
        n("ST_4", Branch.STAGING, "Lunar Transfer Stage", "A fourth stage for the trip to the Moon.", 5, 5, listOf("ST_3", "S_CRYO"), mapOf(Stat.STAGES to 1), "Fourth stage", 1.1),
        n("ST_PYRO", Branch.STAGING, "Pyrotechnic Separation", "Near-instant clean staging.", 3, 6, listOf("ST_2"), mapOf(Stat.SEP to 1), "Separation rings", 0.2),
        // ---------------- Aero
        n("A_FINS", Branch.AERO, "Tail Fins", "Stability = sharper steering.", 0, 7, listOf(), mapOf(Stat.FINS to 1), "Tail fins"),
        n("A_OGIVE", Branch.AERO, "Ogive Nose Cone", "Less drag than a blunt cone.", 1, 8, listOf("A_FINS"), mapOf(Stat.AERO to 1), "Ogive nose"),
        n("A_SWEPT", Branch.AERO, "Swept Fins", "Bigger, swept fins.", 2, 7, listOf("A_FINS"), mapOf(Stat.FINS to 1), "Swept fins", 0.25),
        n("A_VK", Branch.AERO, "Von Kármán Nose", "Minimum-drag nose profile.", 2, 8, listOf("A_OGIVE"), mapOf(Stat.AERO to 1), "Von Kármán nose"),
        n("A_FAIRING", Branch.AERO, "Payload Fairing", "Smooth clamshell fairing.", 3, 8, listOf("A_VK", "S_ALLI"), mapOf(Stat.AERO to 1), "Clamshell fairing"),
        n("A_GRID", Branch.AERO, "Grid Fins", "Lattice control fins.", 3, 7, listOf("A_SWEPT", "AV_RADIO"), mapOf(Stat.FINS to 1), "Grid fins", 0.25),
        n("A_SMOOTH", Branch.AERO, "Low-Drag Coating", "Polished skin, flush joints.", 4, 8, listOf("A_FAIRING"), mapOf(Stat.AERO to 1), "Gloss finish"),
        n("A_ACTIVE", Branch.AERO, "Active Control Fins", "Powered fins with actuators.", 4, 7, listOf("A_GRID", "AV_INERTIAL"), mapOf(Stat.FINS to 1), "Actuated fins", 0.2),
        n("A_AREA", Branch.AERO, "Area-Ruled Stack", "Whole-vehicle drag optimisation.", 5, 8, listOf("A_SMOOTH", "S_MONO"), mapOf(Stat.AERO to 1), "Long tangent-ogive nose", 0.8),
        // ---------------- Avionics
        n("AV_RADIO", Branch.AVIONICS, "Radio Guidance", "Snappier steering + hazard radar on the HUD.", 1, 9, listOf(), mapOf(Stat.AVIONICS to 1), "Whip antennas", 0.3),
        n("AV_INERTIAL", Branch.AVIONICS, "Inertial Guidance", "+3° steering authority, faster response.", 3, 9, listOf("AV_RADIO"), mapOf(Stat.AVIONICS to 1), "Guidance ring", 0.25),
        n("AV_COMPUTER", Branch.AVIONICS, "Digital Flight Computer", "Auto-throttles through max-Q and cuts the engines once on course for the Moon.", 4, 9, listOf("AV_INERTIAL", "P_TURBO"), mapOf(Stat.AVIONICS to 1), "Dish antenna & sensor pods", 0.25),
        // ---------------- Thermal
        n("TH_CORK", Branch.THERMAL, "Cork Insulation", "Doubles heat tolerance.", 2, 10, listOf("A_OGIVE"), mapOf(Stat.HEAT to 1), "Tan cork nose", 0.6),
        n("TH_ABL", Branch.THERMAL, "Ablative Heat Shield", "Charring shield for hypersonic flight.", 3, 10, listOf("TH_CORK"), mapOf(Stat.HEAT to 1), "Dark ablative nose"),
        n("TH_TILE", Branch.THERMAL, "Ceramic Tile TPS", "Shuttle-style tiles: near heat-proof.", 5, 10, listOf("TH_ABL", "S_COMP"), mapOf(Stat.HEAT to 1), "Black hex tiles", 0.7),

        // ================= Mars program (tiers 7–9) =================
        n("P_METHALOX", Branch.PROPULSION, "Methalox Engines", "Methane/oxygen engines you could refuel on Mars.", 6, 0, listOf("P_FFSC"), mapOf(Stat.ENGINE to 1), "Larger, hotter bells"),
        n("P_RAPTOR2", Branch.PROPULSION, "Methalox Super-Cluster", "Thirteen high-thrust methalox engines.", 7, 0, listOf("P_METHALOX", "S_TITAN"), mapOf(Stat.ENGINE to 1), "Thirteen-engine cluster"),
        n("P_VAC3", Branch.PROPULSION, "Deep-Space Nozzles", "Enormous expansion ratio for interplanetary burns (+14 s Isp).", 6, 1, listOf("P_VAC2"), mapOf(Stat.VACUUM to 1), "Wider upper-stage bell", 0.8),
        n("P_NTR", Branch.PROPULSION, "Nuclear Thermal Rocket", "Reactor-heated hydrogen upper stages (+110 s Isp).", 7, 1, listOf("P_VAC3", "ST_5"), mapOf(Stat.NUCLEAR to 1), "Gold-shielded nuclear upper engines", 1.1),
        n("P_NTR2", Branch.PROPULSION, "Bimodal Nuclear Core", "Hotter reactor core (+60 s more Isp).", 8, 1, listOf("P_NTR"), mapOf(Stat.NUCLEAR to 1), "Radiator panels on the upper stage", 0.6),
        n("B_HEAVY", Branch.PROPULSION, "Heavy Side Cores", "Two full liquid side cores replace the solid boosters.", 6, 2, listOf("B_QUAD", "P_FFSC"), mapOf(Stat.BOOSTERS to 1), "Two liquid side cores", 1.2),
        n("S_MEGA", Branch.STRUCTURES, "Mega Tanks", "Stretched, widened propellant tanks.", 6, 3, listOf("S_BALLOON"), mapOf(Stat.FUEL to 1), "Much longer stages"),
        n("S_REFUEL", Branch.STRUCTURES, "Orbital Refueling", "Tanker flights top you up in orbit: a huge fuel load.", 7, 3, listOf("S_MEGA", "ST_5"), mapOf(Stat.FUEL to 1), "Even longer stages", 1.2),
        n("S_TITAN", Branch.STRUCTURES, "Titanium Frame", "Titanium ring frames: the toughest hull yet.", 6, 4, listOf("S_MONO", "S_BALLOON"), mapOf(Stat.HULL to 1), "Titanium reinforcement bands", 0.6),
        n("ST_5", Branch.STAGING, "Mars Transfer Stage", "A fifth stage for the trans-Mars injection burn.", 6, 5, listOf("ST_4", "P_VAC2"), mapOf(Stat.STAGES to 1), "Fifth stage", 1.1),
        n("A_STRAKE", Branch.AERO, "Body Strakes", "Long strakes and big actuated fins.", 6, 7, listOf("A_ACTIVE"), mapOf(Stat.FINS to 1), "Large strake fins", 0.3),
        n("A_WAVE", Branch.AERO, "Mirror-Finish Skin", "Polished, seamless skin for the lowest drag.", 6, 8, listOf("A_AREA"), mapOf(Stat.AERO to 1), "Mirror-gloss finish", 0.7),
        n("AV_DSN", Branch.AVIONICS, "Deep-Space Navigation", "Interplanetary guidance: auto engine cut once on course for Mars.", 6, 9, listOf("AV_COMPUTER", "ST_4"), mapOf(Stat.AVIONICS to 1), "Second high-gain antenna", 0.4),
        n("TH_PICA", Branch.THERMAL, "PICA-X Aeroshell", "Mars-rated heat shield for atmospheric entry.", 6, 10, listOf("TH_TILE"), mapOf(Stat.HEAT to 1), "Grey PICA-X nose shield", 1.0),
        n("EDL_CHUTE", Branch.THERMAL, "Supersonic Parachute", "Mars landing: parachute + aeroshell. Needs 8% landing fuel.", 7, 11, listOf("TH_PICA", "AV_DSN"), mapOf(Stat.EDL to 1), "Orange parachute canister", 1.0),
        n("EDL_SRP", Branch.THERMAL, "Supersonic Retropropulsion", "Engine-braked descent on landing legs: only 4% landing fuel.", 8, 11, listOf("EDL_CHUTE", "P_RAPTOR2"), mapOf(Stat.EDL to 1), "Landing legs", 0.7),
    )

    val byId: Map<String, TechNode> = nodes.associateBy { it.id }
    val rows: Int = nodes.maxOf { it.row } + 1
    val tiers: Int = nodes.maxOf { it.tier } + 1
    val totalCost: Long = nodes.sumOf { it.cost }

    fun levels(owned: Set<String>): Map<Stat, Int> {
        val m = HashMap<Stat, Int>()
        for (id in owned) {
            val node = byId[id] ?: continue
            for ((s, v) in node.effects) m[s] = ((m[s] ?: 0) + v).coerceAtMost(s.max)
        }
        return m
    }

    fun isAvailable(owned: Set<String>, node: TechNode) = node.id !in owned && node.prereqs.all { it in owned }

    fun available(owned: Set<String>): List<TechNode> = nodes.filter { isAvailable(owned, it) }
}
