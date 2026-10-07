package com.brandonwallace.rocketlauncher.game

/** Internal stat lines that the physics tables are indexed by. Tech nodes add levels to these. */
enum class Stat(val max: Int) {
    // 1.3 raised the maxima for the Saturn program (1.2 values: ENGINE 10, FUEL 11, AERO 7, HULL 8, STAGES 5,
    // BOOSTERS 5, HEAT 4, FINS 7, AVIONICS 6, VACUUM 4, REGEN 1, SEP 2, NUCLEAR 4, GIMBAL 2, POWER 2)
    ENGINE(17), FUEL(23), AERO(12), HULL(16), STAGES(7), BOOSTERS(6), HEAT(8), FINS(13),
    AVIONICS(9), VACUUM(7), REGEN(3), SEP(4), NUCLEAR(8), EDL(2),
    // 1.2 (Jupiter program and friends)
    GIMBAL(3), POWER(6), RADIATION(2), LANDER(2),
    // 1.3 (Saturn program)
    SLING(4), CRUISE(5), RINGS(3), AEROCAP(3), TITAN(4), PLUME(1),
}

enum class Branch(val title: String, val color: Long, val icon: String) {
    PROPULSION("Propulsion", 0xFFFF7A3D, "🔥"),
    STRUCTURES("Structures", 0xFF9FB4C8, "🧱"),
    STAGING("Staging", 0xFFB38CFF, "🚀"),
    AERO("Aerodynamics", 0xFF5CD3FF, "🔺"),
    AVIONICS("Avionics", 0xFF6CFFB0, "📡"),
    THERMAL("Thermal", 0xFFFF5A7A, "♨️"),
}

/** Which part of the one mission a tech tier belongs to (the later programs open as the mission goes on). */
enum class Program(val title: String) { EARTH("Earth"), MARS("Mars"), JUPITER("Jupiter"), SATURN("Saturn") }

class TechNode(
    val id: String,
    val branch: Branch,
    val title: String,
    val desc: String,
    /** Column in the graph (0..23) — also drives cost. */
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
    val program: Program get() = when {
        tier >= TechTree.SATURN_TIER -> Program.SATURN
        tier >= TechTree.JUPITER_TIER -> Program.JUPITER
        tier >= TechTree.MARS_TIER -> Program.MARS
        else -> Program.EARTH
    }
    /** Tiers 10–12: the Jupiter program, which opens after the first Mars landing. */
    val jupiterProgram: Boolean get() = program == Program.JUPITER
    /** Tiers 13–24: the Saturn program, which opens after the first Europa landing. */
    val saturnProgram: Boolean get() = program == Program.SATURN
}

/**
 * The research tree. Pure Kotlin so it can be unit-tested; RocketSpec reads stat levels from it and
 * RocketModel reads the owned node ids for visuals.
 */
object TechTree {
    /**
     * Base price per tier (column). Tiers 1–6 are tuned so the Moon takes 25–35 flights; tiers 7–9 are the
     * Mars program, tiers 10–12 the Jupiter program, tiers 13–24 the Saturn program (see EconomyTest /
     * SmokeTest for the campaign simulations).
     */
    val TIER_COST = doubleArrayOf(
        42.5, 127.5, 340.0, 977.5, 2465.0, 4760.0, 16000.0, 32000.0, 52000.0, 55000.0, 70000.0, 90000.0,
        80000.0, 83000.0, 86000.0, 89000.0, 92000.0, 96000.0, 100000.0, 104000.0, 108000.0, 112000.0, 117000.0, 122000.0,
    )

    /** First column (0-based) of each later program. */
    const val MARS_TIER = 6
    const val JUPITER_TIER = 9
    const val SATURN_TIER = 12

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
        n("P_GIMBAL", Branch.PROPULSION, "Gimballed Nozzle", "The nozzle swivels: the autopilot steers around hazards faster.", 1, 1, listOf("P_GRAIN"), mapOf(Stat.GIMBAL to 1), "Gimbal actuators on the engine", 0.5),
        n("P_TVC", Branch.PROPULSION, "Electric Thrust-Vector Control", "Electric actuators swing every engine: even quicker dodges.", 2, 1, listOf("P_GIMBAL", "P_LIQUID"), mapOf(Stat.GIMBAL to 1), "Red actuator struts on every engine", 0.5),
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
        n("ST_4", Branch.STAGING, "Lunar Transfer Stage", "A fourth stage to carry you past the Moon.", 5, 5, listOf("ST_3", "S_CRYO"), mapOf(Stat.STAGES to 1), "Fourth stage", 1.1),
        n("ST_PYRO", Branch.STAGING, "Pyrotechnic Separation", "Near-instant clean staging.", 3, 6, listOf("ST_2"), mapOf(Stat.SEP to 1), "Separation rings", 0.2),
        // ---------------- Aero
        n("A_FINS", Branch.AERO, "Tail Fins", "Stability: sharper autopilot evasive turns.", 0, 7, listOf(), mapOf(Stat.FINS to 1), "Tail fins"),
        n("A_OGIVE", Branch.AERO, "Ogive Nose Cone", "Less drag than a blunt cone.", 1, 8, listOf("A_FINS"), mapOf(Stat.AERO to 1), "Ogive nose"),
        n("A_SWEPT", Branch.AERO, "Swept Fins", "Bigger, swept fins.", 2, 7, listOf("A_FINS"), mapOf(Stat.FINS to 1), "Swept fins", 0.25),
        n("A_VK", Branch.AERO, "Von Kármán Nose", "Minimum-drag nose profile.", 2, 8, listOf("A_OGIVE"), mapOf(Stat.AERO to 1), "Von Kármán nose"),
        n("A_FAIRING", Branch.AERO, "Payload Fairing", "Smooth clamshell fairing.", 3, 8, listOf("A_VK", "S_ALLI"), mapOf(Stat.AERO to 1), "Clamshell fairing"),
        n("A_GRID", Branch.AERO, "Grid Fins", "Lattice control fins: more autopilot steering authority.", 3, 7, listOf("A_SWEPT", "AV_RADIO"), mapOf(Stat.FINS to 1), "Grid fins", 0.25),
        n("A_SMOOTH", Branch.AERO, "Low-Drag Coating", "Polished skin, flush joints.", 4, 8, listOf("A_FAIRING"), mapOf(Stat.AERO to 1), "Gloss finish"),
        n("A_ACTIVE", Branch.AERO, "Active Control Fins", "Powered fins: the autopilot dodges harder and faster.", 4, 7, listOf("A_GRID", "AV_INERTIAL"), mapOf(Stat.FINS to 1), "Actuated fins", 0.2),
        n("A_AREA", Branch.AERO, "Area-Ruled Stack", "Whole-vehicle drag optimisation.", 5, 8, listOf("A_SMOOTH", "S_MONO"), mapOf(Stat.AERO to 1), "Long tangent-ogive nose", 0.8),
        // ---------------- Avionics
        n("AV_RADIO", Branch.AVIONICS, "Radio Guidance", "Hazard radar: the autopilot spots debris early and steers around it.", 1, 9, listOf(), mapOf(Stat.AVIONICS to 1), "Whip antennas", 0.3),
        n("AV_INERTIAL", Branch.AVIONICS, "Inertial Guidance", "Autopilot sees further ahead, +3° steering authority, faster response.", 3, 9, listOf("AV_RADIO"), mapOf(Stat.AVIONICS to 1), "Guidance ring", 0.25),
        n("AV_COMPUTER", Branch.AVIONICS, "Digital Flight Computer", "Autopilot throttles back through max-Q heating instead of cooking the hull.", 4, 9, listOf("AV_INERTIAL", "P_TURBO"), mapOf(Stat.AVIONICS to 1), "Dish antenna & sensor pods", 0.25),
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
        n("A_STRAKE", Branch.AERO, "Body Strakes", "Long strakes and big actuated fins for the autopilot.", 6, 7, listOf("A_ACTIVE"), mapOf(Stat.FINS to 1), "Large strake fins", 0.3),
        n("A_WAVE", Branch.AERO, "Mirror-Finish Skin", "Polished, seamless skin for the lowest drag.", 6, 8, listOf("A_AREA"), mapOf(Stat.AERO to 1), "Mirror-gloss finish", 0.7),
        n("AV_DSN", Branch.AVIONICS, "Deep-Space Navigation", "Interplanetary guidance: cuts the engines once on course for Mars, saving fuel for capture and landing.", 6, 9, listOf("AV_COMPUTER", "ST_4"), mapOf(Stat.AVIONICS to 1), "Second high-gain antenna", 0.4),
        n("TH_PICA", Branch.THERMAL, "PICA-X Aeroshell", "Mars-rated heat shield for atmospheric entry.", 6, 10, listOf("TH_TILE"), mapOf(Stat.HEAT to 1), "Grey PICA-X nose shield", 1.0),
        n("EDL_CHUTE", Branch.THERMAL, "Supersonic Parachute", "Mars landing: parachute + aeroshell. Needs 8% landing fuel.", 7, 11, listOf("TH_PICA", "AV_DSN"), mapOf(Stat.EDL to 1), "Orange parachute canister", 1.0),
        n("EDL_SRP", Branch.THERMAL, "Supersonic Retropropulsion", "Engine-braked descent on landing legs: only 4% landing fuel.", 8, 11, listOf("EDL_CHUTE", "P_RAPTOR2"), mapOf(Stat.EDL to 1), "Landing legs", 0.7),
        n("ST_HOT", Branch.STAGING, "Hot Staging", "Upper stage lights before separation: almost no gap in thrust.", 8, 6, listOf("ST_PYRO", "ST_5"), mapOf(Stat.SEP to 1), "Vented hot-staging rings", 0.3),
        n("A_SPIKE", Branch.AERO, "Aerodisk Nose Spike", "A spike pushes the shock wave forward: the lowest drag of all.", 7, 8, listOf("A_WAVE"), mapOf(Stat.AERO to 1), "Needle spike on the nose", 0.4),
        n("AV_SOLAR", Branch.AVIONICS, "Juno-Class Solar Arrays", "Huge solar wings power the nav computer past Mars (needed to save fuel on Jupiter flights).", 8, 9, listOf("AV_DSN"), mapOf(Stat.POWER to 1), "Three solar wings that unfold in space", 0.5),

        // ================= Jupiter program (tiers 10–12) =================
        n("P_RAPTOR3", Branch.PROPULSION, "Methalox Mk III Cluster", "Twenty uprated methalox engines.", 9, 0, listOf("P_RAPTOR2"), mapOf(Stat.ENGINE to 1), "Twenty-engine cluster, bluer flame"),
        n("P_VAC4", Branch.PROPULSION, "Carbon-Carbon Nozzle Skirts", "Extendable carbon-carbon skirts (+14 s Isp).", 9, 1, listOf("P_NTR2"), mapOf(Stat.VACUUM to 1), "Long black nozzle skirt", 0.7),
        n("B_QUADHEAVY", Branch.PROPULSION, "Four Heavy Side Cores", "Four full liquid side cores around the core stage.", 9, 2, listOf("B_HEAVY", "P_RAPTOR2"), mapOf(Stat.BOOSTERS to 1), "Four liquid side cores"),
        n("S_ZBO", Branch.STRUCTURES, "Zero-Boil-Off Tanks", "Cryocoolers keep propellant cold for years: much bigger tanks.", 9, 3, listOf("S_REFUEL"), mapOf(Stat.FUEL to 1), "Longer stages + white cryocooler radiators"),
        n("S_WHIPPLE", Branch.STRUCTURES, "Whipple Shield", "Stand-off bumper panels shatter micrometeoroids: survive the asteroid belt.", 9, 4, listOf("S_TITAN"), mapOf(Stat.HULL to 1), "Quilted white bumper panels", 0.6),
        n("ST_6", Branch.STAGING, "Jupiter Injection Stage", "A sixth stage for the long burn out to Jupiter.", 9, 5, listOf("ST_5", "S_REFUEL"), mapOf(Stat.STAGES to 1), "Sixth stage"),
        n("A_RCS", Branch.AERO, "Cold-Gas RCS Thrusters", "Attitude thrusters: the autopilot dodges asteroids even in vacuum.", 9, 7, listOf("A_STRAKE"), mapOf(Stat.FINS to 1), "RCS thruster quads", 0.4),
        n("AV_AI", Branch.AVIONICS, "Autonomous AI Navigator", "Onboard AI sees hazards much further ahead and steers sooner.", 9, 9, listOf("AV_SOLAR"), mapOf(Stat.AVIONICS to 1), "Glowing blue sensor dome", 0.5),
        n("TH_VAULT", Branch.THERMAL, "Titanium Radiation Vault", "Shields the electronics from Jupiter's radiation belts: needed for Jupiter orbit.", 9, 10, listOf("TH_PICA", "S_TITAN"), mapOf(Stat.RADIATION to 1), "Gold foil wrap + titanium vault", 0.8),
        n("P_PLASMA", Branch.PROPULSION, "Fusion Plasma Drive", "Magnetic-nozzle plasma engines on the upper stages (huge Isp).", 10, 1, listOf("P_VAC4"), mapOf(Stat.NUCLEAR to 1), "Copper magnet coils, violet plasma exhaust"),
        n("S_DEPOT", Branch.STRUCTURES, "Orbital Propellant Depot", "Fill up at a depot in orbit before heading out.", 10, 3, listOf("S_ZBO"), mapOf(Stat.FUEL to 1), "Even longer stages"),
        n("A_ION", Branch.AERO, "Ion Attitude Thrusters", "Tireless ion thrusters: the sharpest dodges yet.", 10, 7, listOf("A_RCS"), mapOf(Stat.FINS to 1), "Blue glowing ion thrusters", 0.4),
        n("AV_RTG", Branch.AVIONICS, "Radioisotope Generators", "Nuclear batteries power a longer-range hazard radar far from the Sun.", 10, 9, listOf("AV_AI"), mapOf(Stat.POWER to 1), "Two finned RTGs on booms", 0.5),
        n("TH_RADHARD", Branch.THERMAL, "Radiation-Hardened Electronics", "Electronics that survive Europa's radiation: needed to land there.", 10, 10, listOf("TH_VAULT"), mapOf(Stat.RADIATION to 1), "Dark shielding plates", 0.7),
        n("EDL_EUROPA", Branch.THERMAL, "Europa Lander", "An icy-moon lander: lands on Europa with 8% landing fuel.", 10, 11, listOf("EDL_SRP", "TH_VAULT"), mapOf(Stat.LANDER to 1), "Gold lander with folded legs"),
        n("P_SUPER33", Branch.PROPULSION, "33-Engine Super Booster", "Thirty-three engines on the biggest first stage ever.", 11, 0, listOf("P_RAPTOR3", "B_QUADHEAVY"), mapOf(Stat.ENGINE to 1), "Thirty-three-engine cluster"),
        n("P_PLASMA2", Branch.PROPULSION, "Fusion Torch", "Hotter fusion plasma (even more Isp).", 11, 1, listOf("P_PLASMA"), mapOf(Stat.NUCLEAR to 1), "Extra coils, white-violet plasma", 0.8),
        n("S_SLUSH", Branch.STRUCTURES, "Slush-Densified Propellant", "Super-chilled slush packs more propellant into every tank.", 11, 3, listOf("S_DEPOT"), mapOf(Stat.FUEL to 1), "Frosted, longest stages", 0.8),
        n("S_GRAPHENE", Branch.STRUCTURES, "Graphene Composite Frame", "The strongest, lightest structure: survives almost anything.", 11, 4, listOf("S_WHIPPLE"), mapOf(Stat.HULL to 1), "Graphite skin with glowing blue bands", 0.6),
        n("AV_LASER", Branch.AVIONICS, "Laser Deep-Space Comms", "Laser links to Earth: the AI navigator reacts even earlier.", 11, 9, listOf("AV_RTG"), mapOf(Stat.AVIONICS to 1), "Laser terminal with a green beam lens", 0.5),
        n("EDL_SKYCRANE", Branch.THERMAL, "Sky-Crane Descent", "A rocket crane lowers the lander gently: only 4% landing fuel.", 11, 11, listOf("EDL_EUROPA"), mapOf(Stat.LANDER to 1), "Red sky-crane frame with four thrusters", 0.8),

        // ================= Saturn program (tiers 13–24) =================
        // tier 13
        n("P_MK4", Branch.PROPULSION, "Methalox Mk IV", "Uprated 350-bar chambers on every first-stage engine.", 12, 0, listOf("P_RAPTOR3"), mapOf(Stat.ENGINE to 1), "Wider polished bells, brighter blue flame", 0.6),
        n("P_DUALBELL", Branch.PROPULSION, "Dual-Bell Nozzles", "A second bell contour takes over in vacuum (+14 s Isp).", 12, 1, listOf("P_VAC4", "P_PLASMA2"), mapOf(Stat.VACUUM to 1), "Stepped dual-bell upper nozzle", 0.6),
        n("S_JUPTANK", Branch.STRUCTURES, "Outer-Planet Tanks", "Longer, wider tanks for the long haul past Jupiter.", 12, 3, listOf("S_SLUSH"), mapOf(Stat.FUEL to 1), "Longer, chunkier stages", 0.7),
        n("S_SELFHEAL", Branch.STRUCTURES, "Self-Healing Skin", "A polymer layer seals small punctures by itself.", 12, 4, listOf("S_GRAPHENE"), mapOf(Stat.HULL to 1), "Glossy amber healing bands", 0.9),
        n("A_CMG", Branch.AERO, "Control Moment Gyroscopes", "Spinning gyros swing the rocket around: sharper dodges.", 12, 7, listOf("A_ION"), mapOf(Stat.FINS to 1), "Gyro drums around the avionics ring", 0.9),
        n("AV_SLING", Branch.AVIONICS, "Gravity-Assist Navigation", "Saturn flights swing past Jupiter for a free speed boost.", 12, 9, listOf("AV_LASER"), mapOf(Stat.SLING to 1), "Twin star-tracker cameras", 0.6),
        n("TH_TRANSP", Branch.THERMAL, "Transpiration Cooling", "Coolant seeps through porous chamber walls: +8 s Isp on every engine.", 12, 10, listOf("TH_RADHARD"), mapOf(Stat.REGEN to 1), "Dark porous engine chambers", 0.6),
        // tier 14
        n("P_TRIPROP", Branch.PROPULSION, "Tripropellant Engines", "Hydrogen joins the methane and oxygen at liftoff for extra kick.", 13, 0, listOf("P_MK4"), mapOf(Stat.ENGINE to 1), "Hydrogen manifold ring, white-hot flame core", 0.6),
        n("P_DFD", Branch.PROPULSION, "Direct Fusion Drive", "Compact fusion reactors heat the exhaust directly (+80 s Isp).", 13, 1, listOf("P_DUALBELL"), mapOf(Stat.NUCLEAR to 1), "Twin reactor rings, pink-white plasma", 0.7),
        n("P_DIFFTHR", Branch.PROPULSION, "Differential Throttling", "Every engine throttles on its own to steer: quicker dodges.", 13, 2, listOf("P_MK4", "B_QUADHEAVY"), mapOf(Stat.GIMBAL to 1), "Gold actuators on every engine", 0.9),
        n("S_STUFFED", Branch.STRUCTURES, "Stuffed Whipple Shield", "Kevlar and ceramic-cloth layers inside the bumper stop bigger particles.", 13, 4, listOf("S_SELFHEAL"), mapOf(Stat.HULL to 1), "Thicker quilted bumper panels", 0.9),
        n("ST_7", Branch.STAGING, "Jupiter Kick Stage", "A seventh stage for the long climb past Jupiter.", 13, 5, listOf("ST_6", "S_JUPTANK"), mapOf(Stat.STAGES to 1), "Seventh stage", 0.8),
        n("A_PLASMA", Branch.AERO, "Plasma-Actuator Skin", "Electric discharges smooth the airflow over the nose: less drag.", 13, 8, listOf("A_SPIKE", "A_CMG"), mapOf(Stat.AERO to 1), "Glowing electrode strips on the nose", 0.9),
        n("AV_NGRTG", Branch.AVIONICS, "Next-Gen RTGs", "Stronger nuclear batteries keep the nav computer running all the way to Saturn (needed to save fuel on Saturn flights).", 13, 9, listOf("AV_SLING", "AV_RTG"), mapOf(Stat.POWER to 1), "Four finned RTGs", 0.9),
        n("TH_UHTC", Branch.THERMAL, "Ultra-High-Temperature Ceramics", "Hafnium-carbide nose for the hottest climbs.", 13, 10, listOf("TH_TRANSP"), mapOf(Stat.HEAT to 1), "Matte charcoal ceramic nose", 0.9),
        // tier 15
        n("B_SIXHEAVY", Branch.PROPULSION, "Six Heavy Side Cores", "Six full liquid side cores around the core stage.", 14, 2, listOf("B_QUADHEAVY", "P_TRIPROP"), mapOf(Stat.BOOSTERS to 1), "Six liquid side cores", 0.8),
        n("S_TRIPLE", Branch.STRUCTURES, "Triple-Point Propellants", "Oxygen chilled to its triple point: the densest load yet.", 14, 3, listOf("S_JUPTANK"), mapOf(Stat.FUEL to 1), "Frost-white lower tanks", 0.7),
        n("S_RINGSHIELD", Branch.STRUCTURES, "Ring-Plane Dust Shield", "A Kevlar-and-ceramic umbrella to survive crossing Saturn's ring plane (needed for Saturn orbit).", 14, 4, listOf("S_STUFFED"), mapOf(Stat.RINGS to 1), "White dust-shield collar around the payload"),
        n("ST_CROSS", Branch.STAGING, "Propellant Crossfeed", "The side cores feed the core stage, so it reaches staging almost full.", 14, 6, listOf("ST_7"), mapOf(Stat.FUEL to 1), "Crossfeed pipes between the cores", 0.7),
        n("A_HALL", Branch.AERO, "Hall-Effect Thrusters", "Electric attitude thrusters: harder dodges in deep space.", 14, 7, listOf("A_CMG"), mapOf(Stat.FINS to 1), "Violet-glowing Hall thrusters", 0.9),
        n("AV_OPNAV", Branch.AVIONICS, "Optical Navigation", "Cameras track stars and moons: the AI spots hazards earlier.", 14, 9, listOf("AV_NGRTG"), mapOf(Stat.AVIONICS to 1), "Camera pods with dark lenses", 0.9),
        n("TH_AEROSHIELD", Branch.THERMAL, "Aerocapture Heat Shield", "Brakes in Saturn's upper atmosphere: Saturn orbit needs 4% fuel instead of 6%.", 14, 11, listOf("TH_UHTC", "EDL_SKYCRANE"), mapOf(Stat.AEROCAP to 1), "Wide grey heat-shield brim under the payload"),
        // tier 16
        n("P_RDE", Branch.PROPULSION, "Rotating Detonation Engines", "Detonation waves race around ring-shaped chambers: much more thrust.", 15, 0, listOf("P_TRIPROP"), mapOf(Stat.ENGINE to 1), "Ring-shaped engines, rippling flame", 0.6),
        n("P_DFD2", Branch.PROPULSION, "Twin-Reactor Fusion Drive", "Two fusion reactors per engine (+80 s Isp).", 15, 1, listOf("P_DFD"), mapOf(Stat.NUCLEAR to 1), "Second reactor ring, hotter plasma", 0.7),
        n("P_SAIL", Branch.PROPULSION, "Solar Sail", "A huge mirror sail catches sunlight: a gentle push all through the cruise.", 15, 2, listOf("A_HALL", "S_TRIPLE"), mapOf(Stat.CRUISE to 1), "Mirror sail that unfolds in space", 0.7),
        n("S_TANKERS", Branch.STRUCTURES, "Tanker Fleet Refill", "Eight tanker flights fill every stage to the brim in orbit.", 15, 3, listOf("S_TRIPLE"), mapOf(Stat.FUEL to 1), "Even longer stages + docking rings", 0.7),
        n("S_SPIDER", Branch.STRUCTURES, "Spider-Silk Composite", "Bio-engineered silk fibres: stretchy, tough armour.", 15, 4, listOf("S_RINGSHIELD"), mapOf(Stat.HULL to 1), "Pearly silk-wrapped bands", 0.9),
        n("A_RIBLET", Branch.AERO, "Shark-Skin Riblets", "Micro-grooves like shark skin cut skin friction.", 15, 8, listOf("A_PLASMA"), mapOf(Stat.AERO to 1), "Finely grooved skin", 0.9),
        n("AV_SLING2", Branch.AVIONICS, "Precision Flyby Targeting", "Skims closer to Jupiter for a bigger slingshot.", 15, 9, listOf("AV_OPNAV"), mapOf(Stat.SLING to 1), "Second pair of star trackers", 0.6),
        n("TH_ACTIVE", Branch.THERMAL, "Active Film Cooling", "A thin coolant film flows over the nose during the climb.", 15, 10, listOf("TH_UHTC"), mapOf(Stat.HEAT to 1), "Silver vent ring at the nose", 0.9),
        // tier 17
        n("P_PETAL", Branch.PROPULSION, "Folding Petal Nozzles", "Petals unfold into an enormous bell in space (+14 s Isp).", 16, 1, listOf("P_DUALBELL"), mapOf(Stat.VACUUM to 1), "Petal-segmented upper bell", 0.6),
        n("S_ANNULAR", Branch.STRUCTURES, "Wrap-Around Tanks", "Extra ring-shaped tanks wrap around every stage.", 16, 3, listOf("S_TANKERS"), mapOf(Stat.FUEL to 1), "Ribbed tank bands", 0.7),
        n("S_BNNT", Branch.STRUCTURES, "Boron-Nitride Nanotube Armor", "Ultra-tough nanotube plating.", 16, 4, listOf("S_SPIDER"), mapOf(Stat.HULL to 1), "Pale ceramic armour plates", 0.9),
        n("ST_DROP", Branch.STAGING, "Drop Tanks", "Slim jettisonable tanks feed the second stage first.", 16, 5, listOf("ST_CROSS"), mapOf(Stat.FUEL to 1), "Two slim drop tanks", 0.7),
        n("A_PPT", Branch.AERO, "Pulsed Plasma Thrusters", "Tiny plasma pulses for millisecond-fast steering.", 16, 7, listOf("A_HALL"), mapOf(Stat.FINS to 1), "Violet plasma thrusters by the nose", 0.9),
        n("AV_CLOCK", Branch.AVIONICS, "Deep-Space Atomic Clock", "Pinpoint timing for self-navigation: earlier hazard warnings.", 16, 9, listOf("AV_SLING2"), mapOf(Stat.AVIONICS to 1), "Clock module with a green status light", 0.9),
        n("TH_TITANSHELL", Branch.THERMAL, "Titan Entry Aeroshell", "A heat shield for entering Titan's thick, hazy air.", 16, 11, listOf("TH_AEROSHIELD"), mapOf(Stat.TITAN to 1), "Orange-tan Titan aeroshell"),
        // tier 18
        n("P_RDE42", Branch.PROPULSION, "42-Engine Detonation Cluster", "Forty-two rotating detonation engines.", 17, 0, listOf("P_RDE", "B_SIXHEAVY"), mapOf(Stat.ENGINE to 1), "Forty-two-engine cluster", 0.6),
        n("P_IONCRUISE", Branch.PROPULSION, "Ion Cruise Engines", "Gridded ion engines push gently for months of cruise.", 17, 2, listOf("P_SAIL", "AV_NGRTG"), mapOf(Stat.CRUISE to 1), "Blue-glowing ion engine pods", 0.7),
        n("S_ICEARMOR", Branch.STRUCTURES, "Ice-Particle Armor", "Layered shields: Saturn's ring ice does less damage.", 17, 4, listOf("S_BNNT"), mapOf(Stat.RINGS to 1), "Layered white armour on the upper stages", 0.9),
        n("ST_ZERO", Branch.STAGING, "Zero-Gap Staging", "Magnetic latches let go the instant the next engine lights.", 17, 6, listOf("ST_DROP"), mapOf(Stat.SEP to 1), "Blue magnetic latch rings", 0.8),
        n("A_MORPH", Branch.AERO, "Morphing Nose", "The nose reshapes itself for every speed: lowest drag at every Mach.", 17, 8, listOf("A_RIBLET"), mapOf(Stat.AERO to 1), "Segmented morphing nose", 0.9),
        n("AV_KILO", Branch.AVIONICS, "Kilopower Fission Reactor", "A small reactor powers a longer-range hazard radar.", 17, 9, listOf("AV_CLOCK"), mapOf(Stat.POWER to 1), "Reactor on a boom with a radiator cone", 0.9),
        n("TH_MHD", Branch.THERMAL, "Magnetohydrodynamic Shield", "Magnets push the hot air away from the nose.", 17, 10, listOf("TH_ACTIVE"), mapOf(Stat.HEAT to 1), "Copper magnet rings in the nose", 0.9),
        // tier 19
        n("P_AMAT", Branch.PROPULSION, "Antimatter-Catalyzed Fusion", "A whiff of antimatter ignites the fusion fuel (+100 s Isp).", 18, 1, listOf("P_DFD2", "P_PETAL"), mapOf(Stat.NUCLEAR to 1), "Glowing antimatter trap pods, white-blue plasma", 0.8),
        n("S_AEROGEL", Branch.STRUCTURES, "Aerogel-Insulated Tanks", "Aerogel blankets stop boil-off on the long cruise.", 18, 3, listOf("S_ANNULAR"), mapOf(Stat.FUEL to 1), "Pale blue aerogel blankets", 0.7),
        n("ST_8", Branch.STAGING, "Saturn Insertion Stage", "An eighth stage for the burn into Saturn orbit.", 18, 5, listOf("ST_DROP"), mapOf(Stat.STAGES to 1), "Eighth stage", 0.8),
        n("A_LASERABL", Branch.AERO, "Laser-Ablation Thrusters", "Lasers flash propellant into thrust: the sharpest dodges yet.", 18, 7, listOf("A_PPT"), mapOf(Stat.FINS to 1), "Laser emitters with red lenses", 0.9),
        n("AV_PLUME", Branch.AVIONICS, "Plume Mass Spectrometer", "Tastes the ice spray of Enceladus' geysers (needed to fly through them).", 18, 9, listOf("AV_KILO"), mapOf(Stat.PLUME to 1), "Gold instrument with an intake funnel", 0.9),
        n("TH_HUYGENS", Branch.THERMAL, "Huygens-Class Probe", "A parachute probe that lands on Titan.", 18, 11, listOf("TH_TITANSHELL"), mapOf(Stat.TITAN to 1), "Saucer-shaped probe on the side"),
        // tier 20
        n("P_SPIKE", Branch.PROPULSION, "Aerospike Core Engine", "A giant aerospike plug in the middle: full thrust at every altitude.", 19, 0, listOf("P_RDE42"), mapOf(Stat.ENGINE to 1), "Aerospike plug in the middle of the cluster", 0.6),
        n("P_ESAIL", Branch.PROPULSION, "Electric Sail", "Long charged tethers ride the solar wind.", 19, 2, listOf("P_IONCRUISE"), mapOf(Stat.CRUISE to 1), "Long glowing tethers that unreel in space", 0.8),
        n("S_METAH", Branch.STRUCTURES, "Metallic-Hydrogen Fuel", "Exotic metastable hydrogen packs huge energy into small tanks.", 19, 3, listOf("S_AEROGEL"), mapOf(Stat.FUEL to 1), "Silver-blue tank bands", 0.7),
        n("S_METAMAT", Branch.STRUCTURES, "Metamaterial Lattice Frame", "3D-printed lattices: lighter and tougher.", 19, 4, listOf("S_ICEARMOR"), mapOf(Stat.HULL to 1), "Lattice interstages", 0.9),
        n("A_BALLUTE", Branch.AERO, "Inflatable Ballute", "A balloon-parachute grabs Saturn's upper air: orbit needs only 2% fuel.", 19, 8, listOf("A_MORPH", "TH_AEROSHIELD"), mapOf(Stat.AEROCAP to 1), "Folded orange ballute ring"),
        n("AV_IOFLYBY", Branch.AVIONICS, "Io–Jupiter Double Flyby", "Swing past Io and then Jupiter: an even bigger slingshot.", 19, 9, listOf("AV_SLING2", "AV_KILO"), mapOf(Stat.SLING to 1), "Big white high-gain dish", 0.7),
        // tier 21
        n("P_MAGNOZ", Branch.PROPULSION, "Superconducting Magnetic Nozzle", "Magnets shape the exhaust perfectly (+14 s Isp).", 20, 1, listOf("P_AMAT"), mapOf(Stat.VACUUM to 1), "Glowing blue magnet rings at the nozzle", 0.6),
        n("S_DUSTFIELD", Branch.STRUCTURES, "Electrostatic Dust Field", "A charged field pushes ring dust away: ring ice does even less damage.", 20, 4, listOf("S_METAMAT"), mapOf(Stat.RINGS to 1), "Violet field-emitter spikes", 0.9),
        n("ST_STRETCH", Branch.STAGING, "Stretched Upper Stages", "Every upper stage grows longer.", 20, 5, listOf("ST_8"), mapOf(Stat.FUEL to 1), "Longer upper stages", 0.7),
        n("A_MICRO", Branch.AERO, "Micro-Thruster Skin", "Thousands of tiny thrusters in the skin: instant steering.", 20, 7, listOf("A_LASERABL"), mapOf(Stat.FINS to 1), "Dotted thruster bands", 0.9),
        n("A_AIRSPIKE", Branch.AERO, "Laser Air-Spike", "A laser heats the air ahead of the nose: a needle-thin shock wave.", 20, 8, listOf("A_MORPH"), mapOf(Stat.AERO to 1), "Glowing laser tip on the nose", 0.9),
        n("AV_QUANT", Branch.AVIONICS, "Quantum Inertial Navigator", "Atom-interferometer sensors: the AI reacts almost instantly.", 20, 9, listOf("AV_PLUME"), mapOf(Stat.AVIONICS to 1), "Glowing quantum sensor core", 0.9),
        n("TH_REGEN3", Branch.THERMAL, "Regenerative Spike Cooling", "Fuel cools the aerospike and every chamber: +8 s Isp on every engine.", 20, 10, listOf("TH_MHD", "P_SPIKE"), mapOf(Stat.REGEN to 1), "Cooling channels on the aerospike", 0.7),
        // tier 22
        n("P_RDE60", Branch.PROPULSION, "Sixty-Engine Mega Cluster", "Sixty detonation engines around the aerospike.", 21, 0, listOf("P_SPIKE"), mapOf(Stat.ENGINE to 1), "Sixty-engine cluster", 0.7),
        n("P_MAGSAIL", Branch.PROPULSION, "Magnetic Sail", "A giant superconducting loop surfs the solar wind.", 21, 2, listOf("P_ESAIL"), mapOf(Stat.CRUISE to 1), "Huge glowing loop that opens in space", 0.8),
        n("S_NANO", Branch.STRUCTURES, "Carbon-Nanotube Tanks", "Nanotube-wound tanks: lighter, bigger and tougher.", 21, 3, listOf("S_METAH", "S_METAMAT"), mapOf(Stat.FUEL to 1, Stat.HULL to 1), "Satin-black nanotube skin", 0.8),
        n("A_TITANCAP", Branch.AERO, "Titan-Assisted Capture", "A Titan flyby helps brake into Saturn orbit: only 1% fuel.", 21, 7, listOf("A_BALLUTE", "TH_TITANSHELL"), mapOf(Stat.AEROCAP to 1), "Second ballute ring", 0.9),
        n("A_DRAGONFLY", Branch.AERO, "Dragonfly Rotorcraft", "An eight-rotor drone that flies in Titan's thick air (needs 6% landing fuel).", 21, 8, listOf("A_BALLUTE", "TH_HUYGENS"), mapOf(Stat.TITAN to 1), "Rotorcraft with eight rotors on the payload"),
        n("AV_OBERTH", Branch.AVIONICS, "Oberth-Maneuver Autopilot", "Fires the engines right at Jupiter for the biggest slingshot of all.", 21, 9, listOf("AV_IOFLYBY", "AV_QUANT"), mapOf(Stat.SLING to 1), "Orange status lights on the guidance ring", 0.7),
        // tier 23
        n("P_AMAT2", Branch.PROPULSION, "Antimatter Beam Core", "Pure antimatter annihilation: the ultimate drive (+120 s Isp).", 22, 1, listOf("P_MAGNOZ"), mapOf(Stat.NUCLEAR to 1), "Blinding white beam nozzle", 0.8),
        n("S_DOMES", Branch.STRUCTURES, "Ultra-Light Tank Domes", "Thin-wall domes leave room for more propellant.", 22, 3, listOf("S_NANO"), mapOf(Stat.FUEL to 1), "White tank domes", 0.7),
        n("S_DIAMOND", Branch.STRUCTURES, "Diamond-Film Hull", "A diamond coating makes the hull nearly scratch-proof.", 22, 4, listOf("S_DUSTFIELD", "S_NANO"), mapOf(Stat.HULL to 1), "Glassy, sparkling hull", 0.9),
        n("ST_LASERSEP", Branch.STAGING, "Laser-Cut Separation", "Lasers cut the stages apart in a hundredth of a second.", 22, 6, listOf("ST_ZERO", "ST_8"), mapOf(Stat.SEP to 1), "Glowing separation seams", 0.8),
        n("A_VGRID", Branch.AERO, "Variable-Geometry Grid Fins", "Grid fins that grow and fold for the best bite.", 22, 7, listOf("A_MICRO"), mapOf(Stat.FINS to 1), "Bigger folding grid fins", 0.9),
        n("A_SUCTION", Branch.AERO, "Boundary-Layer Suction", "Tiny holes suck away turbulent air: the cleanest airflow.", 22, 8, listOf("A_AIRSPIKE"), mapOf(Stat.AERO to 1), "Perforated suction band behind the nose", 0.9),
        n("AV_MEGAWATT", Branch.AVIONICS, "Megawatt Reactor", "A bigger reactor with huge radiators: an even longer-range radar.", 22, 9, listOf("AV_KILO", "AV_QUANT"), mapOf(Stat.POWER to 1), "Big radiator panels on the reactor boom", 0.9),
        n("TH_PLASMASHEATH", Branch.THERMAL, "Plasma-Sheath Shield", "A magnetic field holds the hottest plasma off the hull.", 22, 10, listOf("TH_MHD"), mapOf(Stat.HEAT to 1), "Violet glowing nose ring", 0.9),
        // tier 24
        n("P_BEAMED", Branch.PROPULSION, "Beamed-Energy Boost", "Laser arrays on the ground heat the exhaust during the climb.", 23, 0, listOf("P_RDE60"), mapOf(Stat.ENGINE to 1), "Gold laser-receiver ring, white-hot flame", 0.7),
        n("P_LASERSAIL", Branch.PROPULSION, "Laser-Boosted Lightsail", "Lasers on Earth push a shining lightsail.", 23, 2, listOf("P_MAGSAIL"), mapOf(Stat.CRUISE to 1), "Round shining lightsail that opens in space", 0.8),
        n("S_MEGA2", Branch.STRUCTURES, "Titan-Class Mega Tanks", "The biggest tanks ever flown.", 23, 3, listOf("S_DOMES"), mapOf(Stat.FUEL to 1), "Longest, widest stages", 0.8),
        n("S_NANOMONO", Branch.STRUCTURES, "Nanocomposite Monocoque", "The ultimate shell: nearly indestructible.", 23, 4, listOf("S_DIAMOND"), mapOf(Stat.HULL to 1), "Midnight-blue hull with glowing cyan seams", 0.9),
        n("ST_TITANDESC", Branch.STAGING, "Titan Descent Stage", "A small rocket stage lowers the rotorcraft: Titan landing needs only 3% fuel.", 23, 6, listOf("A_DRAGONFLY", "ST_8"), mapOf(Stat.TITAN to 1), "Orange descent frame with four thrusters", 0.9),
        n("AV_FUSPWR", Branch.AVIONICS, "Compact Fusion Power", "A fusion power core: the longest-range radar of all.", 23, 9, listOf("AV_MEGAWATT"), mapOf(Stat.POWER to 1), "Glowing fusion power ring", 0.9),
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

    /** The Jupiter program opens once the rocket has landed on Mars; the Saturn program once it has landed on Europa. */
    fun programOpen(s: SaveData, node: TechNode): Boolean = when (node.program) {
        Program.JUPITER -> s.marsLandings > 0
        Program.SATURN -> s.europaLandings > 0
        else -> true
    }

    /** Researchable now (prerequisites owned and program open), ignoring price. */
    fun isAvailable(s: SaveData, node: TechNode) = isAvailable(s.tech, node) && programOpen(s, node)

    fun available(s: SaveData): List<TechNode> = nodes.filter { isAvailable(s, it) }
}
