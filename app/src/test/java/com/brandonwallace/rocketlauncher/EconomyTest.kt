package com.brandonwallace.rocketlauncher

import com.brandonwallace.rocketlauncher.game.Achievement
import com.brandonwallace.rocketlauncher.game.Arrival
import com.brandonwallace.rocketlauncher.game.Autopilot
import com.brandonwallace.rocketlauncher.game.Economy
import com.brandonwallace.rocketlauncher.game.Mission
import com.brandonwallace.rocketlauncher.game.Milestone
import com.brandonwallace.rocketlauncher.game.MissionTarget
import com.brandonwallace.rocketlauncher.game.Physics
import com.brandonwallace.rocketlauncher.game.Program
import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.SaveData
import com.brandonwallace.rocketlauncher.game.Stat
import com.brandonwallace.rocketlauncher.game.TechNode
import com.brandonwallace.rocketlauncher.game.TechTree
import com.brandonwallace.rocketlauncher.game.simulateAutopilot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.math.pow

class TechTreeTest {
    @Test fun treeIsWellFormed() {
        val ids = TechTree.nodes.map { it.id }
        assertEquals("163-node tree (54 from 1.1 + 26 in 1.2 + 83 in 1.3: twice the 1.2 tree, like Saturn is 2.04x as far as Jupiter)", 163, ids.size)
        assertEquals("24 tiers (twice 1.2's 12)", 24, TechTree.tiers)
        assertEquals(83, TechTree.nodes.count { it.saturnProgram })
        assertTrue(TechTree.nodes.filter { it.saturnProgram }.all { it.tier >= TechTree.SATURN_TIER })
        assertEquals("unique ids", ids.size, ids.toSet().size)
        for (n in TechTree.nodes) for (p in n.prereqs) {
            val pre = TechTree.byId[p]
            assertTrue("${n.id} -> missing $p", pre != null)
            assertTrue("${n.id} prereq $p must be in an earlier column", pre!!.tier < n.tier)
        }
        // every node reachable; buying everything maxes every stat exactly
        val lv = TechTree.levels(ids.toSet())
        for (s in Stat.entries) assertEquals("stat $s fully covered", s.max, lv[s] ?: 0)
        val cells = TechTree.nodes.map { it.tier to it.row }
        assertEquals("no overlapping graph cells", cells.size, cells.toSet().size)
        println("tech tree: ${TechTree.nodes.size} nodes, total cost ${TechTree.totalCost}")
    }

    @Test fun everyStatTableCoversEveryLevel() {
        // a table one entry short would crash RocketSpec the moment the last node of a line is researched
        assertEquals(Stat.ENGINE.max + 1, RocketSpec.THRUST_KN.size); assertEquals(Stat.ENGINE.max + 1, RocketSpec.ISP.size)
        assertEquals(Stat.FUEL.max + 1, RocketSpec.FUEL_KG.size)
        assertEquals(Stat.AERO.max + 1, RocketSpec.CD.size)
        assertEquals(Stat.HULL.max + 1, RocketSpec.HULL.size)
        assertEquals(Stat.HEAT.max + 1, RocketSpec.HEAT_TOL.size)
        assertEquals(Stat.FINS.max + 1, RocketSpec.MAX_TILT_DEG.size); assertEquals(Stat.FINS.max + 1, RocketSpec.STEER_RESP.size)
        assertEquals(Stat.STAGES.max, RocketSpec.UPPER_FUEL_FRAC.size); assertEquals(Stat.STAGES.max, RocketSpec.UPPER_THRUST_FRAC.size)
        assertEquals(Stat.NUCLEAR.max + 1, RocketSpec.NUCLEAR_ISP.size)
        assertEquals(Stat.SEP.max + 1, RocketSpec.SEP_TIME.size)
        assertEquals(Stat.STAGES.max, RocketSpec.UPPER_LEN_FRAC.size)
        // 1.3 tables
        assertEquals(Stat.SLING.max + 1, RocketSpec.SLING_KICK.size); assertEquals(Stat.CRUISE.max + 1, RocketSpec.CRUISE_ACCEL.size)
        assertEquals(Stat.RINGS.max + 1, RocketSpec.ICE_DAMAGE.size); assertEquals(Stat.AEROCAP.max + 1, RocketSpec.SATURN_CAPTURE_FUEL.size)
        // every table only ever improves with level
        for (a in listOf(RocketSpec.THRUST_KN, RocketSpec.ISP, RocketSpec.FUEL_KG, RocketSpec.HULL, RocketSpec.NUCLEAR_ISP, RocketSpec.HEAT_TOL, RocketSpec.SLING_KICK, RocketSpec.CRUISE_ACCEL))
            assertTrue(a.toList().zipWithNext().all { (x, y) -> y > x })
        for (a in listOf(RocketSpec.CD, RocketSpec.SEP_TIME, RocketSpec.SATURN_CAPTURE_FUEL)) assertTrue(a.toList().zipWithNext().all { (x, y) -> y < x })
        assertTrue(RocketSpec.ICE_DAMAGE.toList().zipWithNext().all { (x, y) -> y <= x })
        assertTrue(RocketSpec.MAX_TILT_DEG.toList().zipWithNext().all { (x, y) -> y > x }); assertTrue(RocketSpec.STEER_RESP.toList().zipWithNext().all { (x, y) -> y > x })
        // 1.1 and 1.2 values are untouched (Moon/Mars/Jupiter balance carries over)
        assertEquals(720.0, RocketSpec.THRUST_KN[8], 0.0); assertEquals(17000.0, RocketSpec.FUEL_KG[8], 0.0); assertEquals(0.22, RocketSpec.CD[6], 0.0)
        assertEquals(1500.0, RocketSpec.THRUST_KN[10], 0.0); assertEquals(44000.0, RocketSpec.FUEL_KG[11], 0.0); assertEquals(420.0, RocketSpec.NUCLEAR_ISP[4], 0.0)
        assertEquals(1700.0, RocketSpec.HULL[8], 0.0); assertEquals(0.19, RocketSpec.CD[7], 0.0); assertEquals(0.06, RocketSpec.SEP_TIME[2], 0.0)
        // the cruise push never beats the Sun's pull, so every coast still ends
        val weakest = minOf(Physics.SUN_PULL, Physics.SUN_PULL_OUTER, Physics.sunPull(Physics.SATURN_ARRIVAL_ALT))
        assertTrue(RocketSpec.CRUISE_ACCEL.last() < weakest)
    }

    @Test fun tanksBeforeEnginesNeverGroundOrWeakenTheRocket() {
        // walk the tree in tier order: liftoff thrust-to-weight never drops below 1.1 (tanks too big for the engines
        // are built smaller instead), so no research order can ever leave the rocket unable to lift off
        val owned = LinkedHashSet<String>()
        for (n in TechTree.nodes.sortedBy { it.tier * 100 + it.row }) {
            if (!n.prereqs.all { it in owned }) continue
            owned += n.id
            val spec = RocketSpec(owned)
            assertTrue("${n.id}: TWR ${spec.liftoffTwr()}", spec.liftoffTwr() >= RocketSpec.MIN_TWR - 1e-6)
        }
        // worst case: every tank and stage of the Saturn program on the Jupiter-era engines
        val tanksOnly = TechTree.nodes.filter { !it.saturnProgram || Stat.ENGINE !in it.effects }.map { it.id }.toSet()
        val heavy = RocketSpec(tanksOnly)
        assertTrue(heavy.liftoffTwr() >= RocketSpec.MIN_TWR - 1e-6)
        assertTrue("part of the tankage waits for engines", heavy.fuelLoad < 1.0)
        assertTrue(RocketSpec(TechTree.nodes.map { it.id }.toSet()).fuelLoad >= 1.0)
        // and buying a bigger tank on top of a given rocket never costs delta-v
        val base = TechTree.nodes.filter { !it.saturnProgram }.map { it.id }.toSet()
        val dv0 = RocketSpec(base + "P_MK4").idealDeltaV()
        assertTrue(RocketSpec(base + "P_MK4" + "S_JUPTANK").idealDeltaV() >= dv0 - 1.0)
    }

    @Test fun jupiterProgramOpensAfterTheFirstMarsLanding() {
        val all = TechTree.nodes.filter { it.tier < TechTree.JUPITER_TIER }.map { it.id }.toSet()
        val rich = SaveData(money = 10_000_000, tech = all)
        val jup = TechTree.nodes.filter { it.jupiterProgram }
        assertEquals(21, jup.size)
        assertTrue(jup.all { it.tier >= TechTree.JUPITER_TIER })
        assertTrue("prereqs met but the program is closed", jup.none { TechTree.isAvailable(rich, it) })
        assertNull(rich.research("P_RAPTOR3"))
        val landed = rich.copy(marsLandings = 1)
        assertTrue(TechTree.isAvailable(landed, TechTree.byId.getValue("P_RAPTOR3")))
        assertTrue(landed.research("P_RAPTOR3")!!.has("P_RAPTOR3"))
        // the target follows the same rule
        assertEquals(MissionTarget.MARS, MissionTarget.of(rich))
        assertEquals(MissionTarget.JUPITER, MissionTarget.of(landed))
    }

    @Test fun saturnProgramOpensAfterTheFirstEuropaLanding() {
        val all = TechTree.nodes.filter { it.tier < TechTree.SATURN_TIER }.map { it.id }.toSet()
        val rich = SaveData(money = 100_000_000, tech = all, marsLandings = 1)
        val sat = TechTree.nodes.filter { it.saturnProgram }
        assertEquals(83, sat.size)
        assertTrue(sat.all { it.program == Program.SATURN && !it.jupiterProgram })
        assertTrue("prereqs met but the program is closed", sat.none { TechTree.isAvailable(rich, it) })
        assertNull(rich.research("P_MK4"))
        assertEquals(MissionTarget.JUPITER, MissionTarget.of(rich))
        val europa = rich.copy(europaLandings = 1)
        assertEquals(MissionTarget.SATURN, MissionTarget.of(europa))
        assertTrue(TechTree.isAvailable(europa, TechTree.byId.getValue("P_MK4")))
        assertTrue(europa.research("AV_SLING")!!.has("AV_SLING"))
        // the tier labels of the tree
        assertEquals(listOf(Program.EARTH, Program.MARS, Program.JUPITER, Program.SATURN), listOf(0, 6, 9, 12).map { t -> TechTree.nodes.first { it.tier == t }.program })
    }

    @Test fun researchRules() {
        var s = SaveData(money = 100_000)
        assertNull("locked node", s.research("P_LIQUID"))
        s = s.research("P_GRAIN")!!
        assertTrue(s.has("P_GRAIN"))
        assertNull("already owned", s.research("P_GRAIN"))
        s = s.research("P_LIQUID")!!
        assertEquals(100_000 - TechTree.byId["P_GRAIN"]!!.cost - TechTree.byId["P_LIQUID"]!!.cost, s.money)
        assertNull("cannot afford", SaveData(money = 1).research("S_ALU"))
        assertEquals(2, RocketSpec(s.tech).level(Stat.ENGINE))
    }

    @Test fun saveRoundTrip() {
        val s = SaveData(money = 1234, tech = setOf("P_GRAIN", "S_ALU"), milestones = setOf(Milestone.KM1), flights = 7, bestAltitude = 4567.5, quality = 2,
            interestCarry = BigDecimal("0.69").setScale(Economy.CARRY_SCALE))
        assertEquals(s, SaveData.parse(s.serialize()))
        assertTrue(s.serialize().startsWith("v=${SaveData.SAVE_SCHEMA_VERSION};"))
        assertEquals(SaveData(), SaveData.parse(null))
        assertEquals(SaveData(), SaveData.parse("garbage"))
    }
}

/**
 * Hidden bank interest: 16.769% of the money left unspent at launch, per launch, compounding (1.2; 1.1 was 6.769%).
 * Rounding rule: interest is computed exactly (BigDecimal); whole dollars are paid (rounded down) and the
 * sub-dollar remainder is carried in the save to the next launch's payout — nothing is dropped.
 */
class InterestTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Economy.CARRY_SCALE)

    @Test fun interestIs16769PercentOfUnspentMoney() {
        assertEquals(0.16769, Economy.INTEREST_RATE, 0.0)
        assertEquals(BigDecimal("0.16769"), BigDecimal.valueOf(Economy.INTEREST_RATE))   // exact decimal, no float drift
        assertEquals(bd("167.69"), Economy.interestExact(1000))          // unspent $1,000 -> +$167.69 exactly
        assertEquals(167L to bd("0.69"), Economy.interestPayout(1000))   // $167 paid now, $0.69 carried
        assertEquals(167, Economy.interest(1000))
        assertEquals(bd("1676.90"), Economy.interestExact(10_000))
        assertEquals(bd("16769"), Economy.interestExact(100_000))
        assertEquals(bd("0.16769"), Economy.interestExact(1))
        assertEquals(0, Economy.interest(0))
        assertEquals(0, Economy.interest(-50))
        assertEquals(bd("0"), Economy.interestExact(-50))
    }

    @Test fun settlementTotalSilentlyIncludesInterest() {
        val st = Economy.settle(unspentAtLaunch = 4000, maxAlt = 20_000.0, already = setOf(Milestone.KM1))
        val pay = Economy.altitudePay(20_000.0)
        val ms = Milestone.CLOUDS.bonus + Milestone.STRATOSPHERE.bonus
        assertEquals(pay + 670, st.performancePay)          // 4000 * 0.16769 = 670.76 -> $670 folded into the one visible pay line
        assertEquals(pay + 670 + ms, st.total)
        // the visible lines (performance pay + milestone bonuses) add up exactly to the total
        assertEquals(st.total, st.performancePay + st.milestones.sumOf { it.bonus })
    }

    @Test fun interestCompoundsAcrossLaunches() {
        // a hoarder who never spends, flying the same 10 km flight 10 times
        var bal = 1000L
        var carry = BigDecimal.ZERO
        val pay = Economy.altitudePay(10_000.0)
        var exact = BigDecimal(1000)
        val rate = BigDecimal.valueOf(Economy.INTEREST_RATE)
        var lostToCarry = BigDecimal.ZERO
        val interests = ArrayList<Long>()
        repeat(10) {
            val st = Economy.settle(bal, 10_000.0, Milestone.entries.toSet(), carry)
            interests += st.interest
            // the pending cents themselves are not "unspent money": what they would have earned, compounded
            lostToCarry = lostToCarry.multiply(BigDecimal.ONE + rate) + carry.multiply(rate)
            bal += st.total; carry = st.interestCarry
            exact = exact.multiply(BigDecimal.ONE + rate) + BigDecimal(pay)
        }
        // balance + carried cents equals exact compound growth, short only by interest-on-pending-cents (< 17¢/launch, compounded)
        val diff = exact - (BigDecimal(bal) + carry)
        assertTrue("diff $diff vs $lostToCarry", diff.signum() >= 0 && diff.compareTo(lostToCarry.add(BigDecimal("0.0000001"))) <= 0)
        assertTrue("interest grows every launch (compounding): $interests", interests.zipWithNext().all { (a, b) -> b > a })
        println("hoarder 10 launches: balance=$bal carry=$carry exact=${exact.setScale(5, java.math.RoundingMode.HALF_EVEN)} interests=$interests")
    }

    @Test fun spendingBeforeLaunchForfeitsInterest() {
        val s = SaveData(money = 2000).research("S_ALU")!!
        assertEquals(Economy.interest(2000 - TechTree.byId["S_ALU"]!!.cost), Economy.interest(s.money))
        assertTrue(Economy.interest(s.money) < Economy.interest(2000))
    }

    // ---------------- new in 1.1

    @Test fun pureInterestCompoundsPerLaunchWithCarry() {
        // no altitude pay (maxAlt 0): watch $1,000 grow on interest alone
        var bal = 1000L; var carry = BigDecimal.ZERO
        val paid = ArrayList<Long>(); val carries = ArrayList<BigDecimal>()
        repeat(3) {
            val st = Economy.settle(bal, 0.0, emptySet(), carry)
            assertEquals(st.interest, st.total)
            paid += st.interest; carries += st.interestCarry
            bal += st.total; carry = st.interestCarry
        }
        // launch 1: 1000 * .16769 = 167.69 -> $167 + .69 carried
        // launch 2: 1167 * .16769 = 195.69423 (+.69) = 196.38423 -> $196 + .38423
        // launch 3: 1363 * .16769 = 228.56147 (+.38423) = 228.94570 -> $228 + .94570
        assertEquals(listOf(167L, 196L, 228L), paid)
        assertEquals(listOf(bd("0.69"), bd("0.38423"), bd("0.94570")), carries)
        assertEquals(1591L, bal)
    }

    @Test fun remainderIsNeverDropped() {
        // a player who keeps exactly $1,000 unspent for 100 launches gets exactly 100 x 167.69 = 16,769.00
        var carry = BigDecimal.ZERO
        var total = 0L
        repeat(100) { val (p, c) = Economy.interestPayout(1000, carry); total += p; carry = c }
        assertEquals(16769L, total)
        assertEquals(bd("0"), carry)
        // and an odd balance: 3 launches of $7 unspent = 3 x 1.17383 = 3.52149 -> $3 paid, .52149 still owed
        carry = BigDecimal.ZERO; total = 0
        repeat(3) { val (p, c) = Economy.interestPayout(7, carry); total += p; carry = c }
        assertEquals(3L, total); assertEquals(bd("0.52149"), carry)
    }

    @Test fun carriedCentsSurviveSaveLoad() {
        val st = Economy.settle(1000, 0.0, emptySet())
        val s = SaveData(money = 1000 + st.total, interestCarry = st.interestCarry)
        val back = SaveData.parse(s.serialize())
        assertEquals(bd("0.69"), back.interestCarry)
        assertEquals(1167, back.money)
        assertTrue(s.serialize().contains(";ic=0.69000;"))
        // next launch uses the reloaded carry: 1167*.16769 + .69 = 196.38423
        assertEquals(196L, Economy.settle(back.money, 0.0, emptySet(), back.interestCarry).interest)
        // corrupt carry values never crash or inflate pay
        assertEquals(bd("0"), SaveData.parse("v=3;money=5;ic=abc").interestCarry)
        assertEquals(bd("0"), SaveData.parse("v=3;money=5;ic=7.5").interestCarry)
    }
}

/** Save schema versioning and migration (1.0, 1.1 and 1.2 saves must load in 1.3 without losing money or unlocks). */
class SaveMigrationTest {
    /** A real-format Rocket Launcher 1.0 save (schema 2) with destination + tilt fields. */
    private val v10 = "v=2;money=48213;bestAlt=3.76e8;bestSpd=11000.0;flights=40;earned=300000;moon=1;marsOrbit=0;mars=0;dest=MARS;sound=false;tilt=true;q=2;" +
        "tech=AV_RADIO,A_FINS,P_GRAIN,P_LIQUID,S_ALU,S_STRETCH;ms=KM1,CLOUDS,STRATOSPHERE,MESOSPHERE,KARMAN,LEO,MEO,GEO,CISLUNAR,MOON;ach=FIRST_FLIGHT,MOON_LANDING"

    @Test fun v10SaveMigratesWithoutLosingAnything() {
        assertEquals(2, SaveData.schemaOf(v10))
        val s = SaveData.parse(v10)
        assertEquals(48213, s.money)
        assertEquals(setOf("AV_RADIO", "A_FINS", "P_GRAIN", "P_LIQUID", "S_ALU", "S_STRETCH"), s.tech)
        assertEquals(10, s.milestones.size)
        assertTrue(Milestone.MOON in s.milestones)
        assertEquals(40, s.flights); assertEquals(300000, s.totalEarned); assertEquals(1, s.moonLandings)
        assertEquals(3.76e8, s.bestAltitude, 0.0); assertEquals(11000.0, s.bestSpeed, 0.0)
        assertFalse(s.soundOn); assertEquals(2, s.quality)
        assertEquals(2, s.achievements.size)
        assertTrue("removed fields are migrated away, not kept as junk: ${s.extras}", s.extras.isEmpty())
        val out = s.serialize()
        assertTrue(out.startsWith("v=5;money=48213;"))
        assertFalse(out.contains("dest=")); assertFalse(out.contains("tilt="))
        assertEquals(s, SaveData.parse(out))
        // already-passed Moon counts toward the one mission
        assertEquals(3, Mission.doneCount(s))
        assertEquals(Mission.MARS_TRANSFER, Mission.next(s))
    }

    /** A real-format Rocket Launcher 1.1 save (schema 3): Mars landed, all 54 techs, money and carried cents. */
    private val v11 = "v=3;money=47082;bestAlt=5.5000008E10;bestSpd=12983.0;flights=55;earned=1200000;moon=1;marsOrbit=3;mars=1;sound=true;q=2;ic=0.91523;" +
        "tech=" + TechTree.nodes.filter { it.tier < TechTree.JUPITER_TIER && it.id !in setOf("P_GIMBAL", "P_TVC", "ST_HOT", "A_SPIKE", "AV_SOLAR") }.joinToString(",") { it.id } +
        ";ms=" + Milestone.entries.filter { it.altitude <= Physics.MARS_ARRIVAL_ALT }.joinToString(",") { it.name } + ";ach=FIRST_FLIGHT,MOON_LANDING,MARS_ORBIT,MARS_LANDING"

    @Test fun v11SaveMigratesAndContinuesToJupiter() {
        assertEquals(3, SaveData.schemaOf(v11))
        val s = SaveData.parse(v11)
        assertEquals(47082, s.money)
        assertEquals("all 54 techs from 1.1 kept", 54, s.tech.size)
        assertEquals(BigDecimal("0.91523"), s.interestCarry)
        assertEquals(1, s.marsLandings); assertEquals(3, s.marsOrbits); assertEquals(0, s.jupiterOrbits); assertEquals(0, s.europaLandings)
        assertEquals(4, s.achievements.size); assertEquals(55, s.flights); assertEquals(1_200_000, s.totalEarned)
        assertTrue(s.extras.isEmpty())
        val out = s.serialize()
        assertTrue(out.startsWith("v=5;money=47082;"))
        assertTrue(out.contains(";jupOrbit=0;europa=0;rings=0;satOrbit=0;encel=0;titanProbe=0;titan=0;"))
        assertEquals(s, SaveData.parse(out))
        // the 1.1 endgame player picks up the new third act straight away
        assertEquals(Mission.JUPITER_TRANSFER, Mission.next(s))
        assertEquals(6, Mission.doneCount(s))
        assertEquals(MissionTarget.JUPITER, MissionTarget.of(s))
        assertTrue(TechTree.available(s).any { it.jupiterProgram })
        assertTrue("Saturn program still closed", TechTree.available(s).none { it.saturnProgram })
    }

    /** A real-format Rocket Launcher 1.2 save (schema 4): landed on Europa, all 80 techs, money and carried cents. */
    private val v12 = "v=4;money=912345;bestAlt=5.88E11;bestSpd=21449.0;flights=91;earned=6500000;moon=1;marsOrbit=4;mars=2;jupOrbit=3;europa=1;sound=false;q=1;ic=0.12345;" +
        "tech=" + TechTree.nodes.filter { it.tier < TechTree.SATURN_TIER }.joinToString(",") { it.id } +
        ";ms=" + Milestone.entries.filter { it.altitude <= Physics.JUPITER_ARRIVAL_ALT }.joinToString(",") { it.name } +
        ";ach=FIRST_FLIGHT,MOON_LANDING,MARS_ORBIT,MARS_LANDING,ASTEROID_BELT,JUPITER_ORBIT,EUROPA_LANDING"

    @Test fun v12SaveMigratesAndContinuesToSaturn() {
        assertEquals(4, SaveData.schemaOf(v12))
        val s = SaveData.parse(v12)
        assertEquals(912_345, s.money)
        assertEquals("all 80 techs from 1.2 kept", 80, s.tech.size)
        assertEquals(17, s.milestones.size)
        assertEquals(BigDecimal("0.12345"), s.interestCarry)
        assertEquals(2, s.marsLandings); assertEquals(3, s.jupiterOrbits); assertEquals(1, s.europaLandings)
        assertEquals(0, s.ringCrossings); assertEquals(0, s.saturnOrbits); assertEquals(0, s.titanFlights)
        assertEquals(7, s.achievements.size); assertEquals(91, s.flights); assertEquals(6_500_000, s.totalEarned)
        assertFalse(s.soundOn); assertEquals(1, s.quality)
        assertTrue(s.extras.isEmpty())
        val out = s.serialize()
        assertTrue(out.startsWith("v=5;money=912345;"))
        assertTrue(out.contains(";europa=1;rings=0;satOrbit=0;encel=0;titanProbe=0;titan=0;"))
        assertEquals(s, SaveData.parse(out))
        // the 1.2 endgame player goes straight on to Saturn: steps 1-9 done, the Saturn program open
        assertEquals(9, Mission.doneCount(s))
        assertEquals(Mission.JUPITER_SLINGSHOT, Mission.next(s))
        assertEquals(MissionTarget.SATURN, MissionTarget.of(s))
        assertTrue(TechTree.available(s).any { it.saturnProgram })
        assertTrue(TechTree.available(s).all { it.saturnProgram })
    }

    @Test fun unversionedAndPartialSavesLoadWithDefaults() {
        val s = SaveData.parse("money=777;tech=P_GRAIN;ms=KM1")
        assertEquals(1, SaveData.schemaOf("money=777"))
        assertEquals(777, s.money); assertEquals(setOf("P_GRAIN"), s.tech)
        // a future build may add fields: anything missing simply defaults
        val p = SaveData.parse("v=3;money=9")
        assertEquals(9, p.money); assertEquals(1, p.quality); assertTrue(p.soundOn); assertEquals(0, p.flights)
    }

    @Test fun unknownFieldsFromNewerBuildsArePreserved() {
        val s = SaveData.parse("v=6;money=5;tech=P_GRAIN;futureThing=abc,def;rocketName=Bob")
        assertEquals(5, s.money)
        assertEquals(mapOf("futureThing" to "abc,def", "rocketName" to "Bob"), s.extras)
        val out = s.serialize()
        assertTrue(out.contains(";futureThing=abc,def")); assertTrue(out.contains(";rocketName=Bob"))
        assertEquals(s, SaveData.parse(out))
    }

    @Test fun migrationStepsRunInOrder() {
        val m = SaveData.migrate(mapOf("v" to "2", "money" to "1", "dest" to "MOON", "tilt" to "false"))
        assertEquals("5", m["v"]); assertEquals("1", m["money"]); assertEquals("0", m["ic"])
        assertEquals("0", m["jupOrbit"]); assertEquals("0", m["europa"]); assertEquals("0", m["titan"])
        assertFalse("dest" in m); assertFalse("tilt" in m)
        assertEquals("5", SaveData.migrate(mapOf("money" to "1"))["v"])
        val sat = mapOf("rings" to "0", "satOrbit" to "0", "encel" to "0", "titanProbe" to "0", "titan" to "0")
        // 1.1 -> 1.2 -> 1.3 keeps every value and only adds the new counters
        val m3 = SaveData.migrate(mapOf("v" to "3", "money" to "77", "ic" to "0.5", "mars" to "2"))
        assertEquals(mapOf("v" to "5", "money" to "77", "ic" to "0.5", "mars" to "2", "jupOrbit" to "0", "europa" to "0") + sat, m3)
        // 1.2 -> 1.3 likewise
        val m4 = SaveData.migrate(mapOf("v" to "4", "money" to "9", "europa" to "1", "jupOrbit" to "2"))
        assertEquals(mapOf("v" to "5", "money" to "9", "europa" to "1", "jupOrbit" to "2") + sat, m4)
    }
}

class ProgressionTest {
    private fun bonus(maxAlt: Double, got: MutableSet<Milestone>): Long {
        val m = Economy.newMilestones(maxAlt, got); got += m; return m.sumOf { it.bonus }
    }

    /** Autopilot campaign (the real autopilot, no hazards), then research per [strategy]. Returns flights until the flight passes the Moon. */
    private fun campaign(name: String, strategy: (SaveData, List<TechNode>) -> TechNode?, log: Boolean = true): Int {
        var save = SaveData()
        val got = HashSet<Milestone>()
        val sb = StringBuilder()
        for (flight in 1..200) {
            val r = simulateAutopilot(RocketSpec(save.tech))
            val st = Economy.settle(save.money, r.maxAlt, got, save.interestCarry)
            got += st.milestones
            save = save.copy(money = save.money + st.total, interestCarry = st.interestCarry)
            if (log && (flight <= 5 || flight % 3 == 0 || r.passedMoon)) sb.append("\n  #$flight alt=${"%.1f".format(r.maxAlt / 1000)}km earned=${st.total} (interest ${st.interest}) bank=${save.money} nodes=${save.tech.size}")
            if (r.passedMoon) { println("[$name] past the MOON at flight $flight$sb"); return flight }
            while (true) {
                val pick = strategy(save, TechTree.available(save).filter { save.money >= it.cost }) ?: break
                save = save.research(pick.id)!!
            }
        }
        println("[$name] never got past the Moon$sb")
        return 999
    }

    private val cheapest: (SaveData, List<TechNode>) -> TechNode? = { _, c -> c.minByOrNull { it.cost } }

    /** Buys whatever raises predicted apogee the most per dollar (a smart, min-maxing player). */
    private val greedyApogee: (SaveData, List<TechNode>) -> TechNode? = { s, c ->
        val base = simulateAutopilot(RocketSpec(s.tech)).maxAlt
        c.maxByOrNull { n -> (simulateAutopilot(RocketSpec(s.tech + n.id)).maxAlt - base) / n.cost + 1e-12 / n.cost }
    }

    @Test fun moonTakes25to35Flights() {
        val a = campaign("cheapest-first", cheapest)
        val b = campaign("apogee-greedy", greedyApogee)
        assertTrue("cheapest-first took $a flights", a in 25..35)
        // the simulated min-maxer is chaotic (one cheap node changes its whole path: 1.1 stalled at 14 km for
        // five flights and took 26); it must still need a real campaign
        assertTrue("apogee-greedy took $b flights", b in 18..35)
    }

    @Test fun hoardingIsNotAnExploit() {
        // savers only research once they can afford the cheapest node 2x / 5x over; even at 16.769% interest
        // hoarding must not beat simply spending on the way to the Moon (it does pay off later: see jupiterIsAThirdAct)
        val saver: (SaveData, List<TechNode>) -> TechNode? = { s, c -> c.minByOrNull { it.cost }?.takeIf { s.money >= it.cost * 2 } }
        val saver5: (SaveData, List<TechNode>) -> TechNode? = { s, c -> c.minByOrNull { it.cost }?.takeIf { s.money >= it.cost * 5 } }
        val spender = campaign("spender", cheapest, log = false)
        val n = campaign("saver", saver)
        val n5 = campaign("saver-5x", saver5)
        assertTrue("saver took $n flights", n >= 25 && n >= spender)
        assertTrue("saver-5x took $n5 flights", n5 >= spender)
    }

    @Test fun maxRocketReachesMoon() {
        val r = simulateAutopilot(RocketSpec(TechTree.nodes.map { it.id }.toSet()))
        println("maxed: pastMoon=${r.passedMoon} top=${r.topSpeed} realTime=${r.warpedRealTime}")
        assertTrue(r.passedMoon)
        assertTrue(r.warpedRealTime < 240)
        val base = simulateAutopilot(RocketSpec(emptySet()))
        assertTrue("base apogee ${base.maxAlt}", base.maxAlt in 500.0..2000.0)
    }

    /** Cheapest-first campaign along the one mission: flights until the autopilot actually lands on Mars. */
    @Test fun marsProgramIsALongerSecondAct() {
        var save = SaveData()
        val got = HashSet<Milestone>()
        var moonAt = -1; var marsAt = -1; var landAt = -1
        val sb = StringBuilder()
        for (flight in 1..400) {
            val spec = RocketSpec(save.tech)
            val r = simulateAutopilot(spec)
            val st = Economy.settle(save.money, r.maxAlt, got, save.interestCarry)
            got += st.milestones
            save = save.copy(money = save.money + st.total, interestCarry = st.interestCarry)
            if (flight % 10 == 0 || r.reachedMars) sb.append("\n  #$flight alt=${"%.0f".format(r.maxAlt / 1000)}km earned=${st.total} bank=${save.money} nodes=${save.tech.size} orbit=${r.marsOrbit} landed=${r.marsLanded}")
            if (moonAt < 0 && r.passedMoon) moonAt = flight
            if (marsAt < 0 && r.reachedMars) marsAt = flight
            if (r.marsLanded) { landAt = flight; break }
            while (true) {
                val pick = TechTree.available(save).filter { save.money >= it.cost }.minByOrNull { it.cost } ?: break
                save = save.research(pick.id)!!
            }
        }
        println("[mars-campaign] past Moon at $moonAt, Mars reached at $marsAt, Mars landing at $landAt$sb")
        assertTrue("Moon still 25..35 flights ($moonAt)", moonAt in 25..35)
        assertTrue("Mars landing should be a substantial second act: $landAt", landAt - moonAt in 12..80)
    }

    /** Cheapest-first (or [strategy]) campaign along the whole mission, through all 18 steps. Returns the flight each step was first done. */
    private fun wholeMission(strategy: (SaveData, List<TechNode>) -> TechNode?): Map<Mission, Int> {
        var save = SaveData()
        val at = LinkedHashMap<Mission, Int>()
        for (flight in 1..400) {
            val target = MissionTarget.of(save)
            val r = simulateAutopilot(RocketSpec(save.tech), target)
            val st = Economy.settle(save.money, r.maxAlt, save.milestones, save.interestCarry)
            save = save.copy(money = save.money + st.total, interestCarry = st.interestCarry, milestones = save.milestones + st.milestones, flights = flight,
                marsLandings = save.marsLandings + (if (r.marsLanded) 1 else 0),
                jupiterOrbits = save.jupiterOrbits + (if (r.jupiterOrbit) 1 else 0), europaLandings = save.europaLandings + (if (r.europaLanded) 1 else 0),
                ringCrossings = save.ringCrossings + (if (r.ringsCrossed) 1 else 0), saturnOrbits = save.saturnOrbits + (if (r.saturnOrbit) 1 else 0),
                enceladusPasses = save.enceladusPasses + (if (r.enceladus) 1 else 0), titanProbes = save.titanProbes + (if (r.titanProbe) 1 else 0),
                titanFlights = save.titanFlights + (if (r.titanFlight) 1 else 0))
            for (m in Mission.entries) if (m.done(save) && m !in at) at[m] = flight
            if (Mission.next(save) == null) break
            while (true) { val pick = strategy(save, TechTree.available(save).filter { save.money >= it.cost }) ?: break; save = save.research(pick.id)!! }
        }
        return at
    }

    /**
     * Cheapest-first campaign along the whole mission: the target switches to Jupiter after the first Mars landing
     * (opening the Jupiter program) and to Saturn after the first Europa landing (opening the Saturn program).
     * 1.3 doubles the game, like Saturn is 2.04x as far as Jupiter: the whole mission takes about twice the flights.
     */
    @Test fun saturnDoublesTheGame() {
        val c = wholeMission { _, l -> l.minByOrNull { it.cost } }
        val saver = wholeMission { s, l -> l.minByOrNull { it.cost }?.takeIf { s.money >= it.cost * 5 } }
        println("[whole-mission] cheapest-first: " + c.entries.joinToString { "${it.key}=${it.value}" })
        println("[whole-mission] saver-5x: " + saver.entries.joinToString { "${it.key}=${it.value}" })
        val moon = c.getValue(Mission.MOON); val mars = c.getValue(Mission.MARS_LANDING); val europa = c.getValue(Mission.EUROPA_LANDING)
        val jup = c.getValue(Mission.JUPITER_ORBIT); val saturn = c.getValue(Mission.SATURN_ARRIVAL); val titan = c.getValue(Mission.TITAN_FLIGHT)
        // the first three acts are exactly as in 1.2
        assertTrue("Moon still 25..35 flights ($moon)", moon in 25..35)
        assertTrue("Mars act 12..40 flights (${mars - moon})", mars - moon in 12..40)
        assertTrue("Jupiter act 15..60 flights (${europa - mars})", europa - mars in 15..60)
        assertTrue(jup <= europa)
        // the Saturn act: the whole game is about twice as long (1.2 ended at the Europa landing)
        assertEquals("all 18 steps done", Mission.entries.size, c.size)
        assertTrue("whole mission ~2.04x the 1.2 game: $titan flights vs $europa", titan in (europa * 1.85).toInt()..(europa * 2.25).toInt())
        assertTrue("Saturn needs a long climb (${saturn - europa} flights)", saturn - europa >= 40)
        // the distance steps come in order, spread over the act, and the ladder at Saturn takes a while too
        val order = listOf(Mission.JUPITER_SLINGSHOT, Mission.HALFWAY_SATURN, Mission.BILLION_KM, Mission.SATURN_ARRIVAL, Mission.RING_CROSSING,
            Mission.SATURN_ORBIT, Mission.ENCELADUS, Mission.TITAN_PROBE, Mission.TITAN_FLIGHT).map { c.getValue(it) }
        assertEquals(order.sorted(), order)
        assertTrue("halfway well before Saturn", c.getValue(Mission.HALFWAY_SATURN) < saturn - 8)
        assertTrue("Titan takes more research after Saturn (${titan - saturn})", titan - saturn >= 10)
        assertTrue("saver finishes too, and sooner (${saver[Mission.TITAN_FLIGHT]})", (saver[Mission.TITAN_FLIGHT] ?: 999) < titan)
    }

    @Test fun maxRocketLandsOnEuropa() {
        val all = TechTree.nodes.map { it.id }.toSet()
        val jupEra = TechTree.nodes.filter { it.tier < TechTree.SATURN_TIER }.map { it.id }.toSet()
        val r = simulateAutopilot(RocketSpec(jupEra), MissionTarget.JUPITER)
        println("maxed Jupiter: reached=${r.reachedJupiter} orbit=${r.jupiterOrbit} europa=${r.europaLanded} top=${r.topSpeed} simDays=${r.time / 86400} realTime=${r.warpedRealTime}")
        assertTrue(r.reachedJupiter && r.jupiterOrbit && r.europaLanded)
        assertTrue("real time ${r.warpedRealTime}", r.warpedRealTime in 20.0..240.0)
        // the full Mars-program rocket (everything up to tier 9) falls short of Jupiter
        val marsEra = simulateAutopilot(RocketSpec(TechTree.nodes.filter { it.tier < TechTree.JUPITER_TIER }.map { it.id }.toSet()), MissionTarget.JUPITER)
        println("Mars-era rocket toward Jupiter: maxAlt=${marsEra.maxAlt / 1000} km")
        assertFalse(marsEra.reachedJupiter)
        assertTrue("…but it does make it past Mars", marsEra.maxAlt > Physics.MARS_ARRIVAL_ALT)
        // no radiation vault: Jupiter flyby only; no solar power: the nav computer cannot save the capture fuel
        assertFalse(simulateAutopilot(RocketSpec(jupEra - "TH_VAULT" - "TH_RADHARD" - "EDL_EUROPA" - "EDL_SKYCRANE"), MissionTarget.JUPITER).jupiterOrbit)
        assertFalse(simulateAutopilot(RocketSpec(jupEra - "AV_SOLAR" - "AV_AI" - "AV_RTG" - "AV_LASER"), MissionTarget.JUPITER).europaLanded)
        // the same maxed rockets (Jupiter-era and the full 1.3 tree) still land on Mars and Europa when those are the target
        assertTrue(simulateAutopilot(RocketSpec(jupEra), MissionTarget.MARS).marsLanded)
        assertTrue(simulateAutopilot(RocketSpec(all), MissionTarget.MARS).marsLanded)
        assertTrue(simulateAutopilot(RocketSpec(all), MissionTarget.JUPITER).europaLanded)
    }

    /** 1.3: the Saturn ladder (rings → orbit → Enceladus → Titan probe → Titan flight) and the Jupiter slingshot. */
    @Test fun maxRocketSlingshotsToSaturnAndFliesOnTitan() {
        val all = TechTree.nodes.map { it.id }.toSet()
        val r = simulateAutopilot(RocketSpec(all), MissionTarget.SATURN)
        println("maxed Saturn: reached=${r.reachedSaturn} level=${r.level} sling=${r.slingshot} top=${r.topSpeed} simDays=${r.time / 86400} realTime=${r.warpedRealTime}")
        assertTrue(r.reachedSaturn && r.ringsCrossed && r.saturnOrbit && r.enceladus && r.titanProbe && r.titanFlight)
        assertEquals(Arrival.TITAN_FLIGHT, r.level)
        assertTrue("the Jupiter slingshot happened", r.slingshot)
        assertTrue(r.reachedJupiter && r.reachedMars && r.passedMoon)
        assertTrue("real time ${r.warpedRealTime}", r.warpedRealTime in 150.0..360.0)
        // the fully upgraded Jupiter-era rocket gets past Jupiter but nowhere near Saturn: the Saturn program is needed
        val jupEra = TechTree.nodes.filter { it.tier < TechTree.SATURN_TIER }.map { it.id }.toSet()
        val j = simulateAutopilot(RocketSpec(jupEra), MissionTarget.SATURN)
        println("Jupiter-era rocket toward Saturn: maxAlt=${j.maxAlt / 1000} km")
        assertTrue(j.maxAlt > Physics.JUPITER_ARRIVAL_ALT && j.maxAlt < Milestone.KM_700M.altitude)
        assertFalse(j.reachedSaturn)
        // each rung of the ladder needs its own research
        fun level(without: Set<String>) = simulateAutopilot(RocketSpec(all - without), MissionTarget.SATURN).level
        assertEquals(Arrival.FLYBY, level(setOf("S_RINGSHIELD", "S_ICEARMOR", "S_DUSTFIELD")))
        assertEquals(Arrival.ORBIT, level(setOf("AV_PLUME")))
        assertEquals(Arrival.ENCELADUS, level(setOf("TH_HUYGENS", "A_DRAGONFLY", "ST_TITANDESC")))
        assertEquals(Arrival.TITAN_PROBE, level(setOf("A_DRAGONFLY", "ST_TITANDESC")))
        // without the next-gen RTGs the nav computer quits past Jupiter: the autopilot burns every drop (no Saturn orbit)
        val noPower = simulateAutopilot(RocketSpec(all - "AV_NGRTG" - "AV_KILO" - "AV_MEGAWATT" - "AV_FUSPWR"), MissionTarget.SATURN)
        assertFalse(Autopilot.canNavCut(RocketSpec(all - "AV_NGRTG" - "AV_KILO" - "AV_MEGAWATT" - "AV_FUSPWR"), MissionTarget.SATURN))
        assertTrue(noPower.reachedSaturn && !noPower.saturnOrbit)
        // the slingshot and the cruise push both help: without them the same rocket flies less far
        val bare = all - setOf("AV_SLING", "AV_SLING2", "AV_IOFLYBY", "AV_OBERTH", "P_SAIL", "P_IONCRUISE", "P_ESAIL", "P_MAGSAIL", "P_LASERSAIL",
            "AV_NGRTG", "AV_KILO", "AV_MEGAWATT", "AV_FUSPWR", "P_AMAT2", "P_BEAMED", "P_MAGNOZ")
        val b = simulateAutopilot(RocketSpec(bare), MissionTarget.SATURN)
        val sl = simulateAutopilot(RocketSpec(bare + "AV_SLING" + "AV_SLING2"), MissionTarget.SATURN)
        val cr = simulateAutopilot(RocketSpec(bare + "P_SAIL" + "P_IONCRUISE"), MissionTarget.SATURN)
        println("bare=${b.maxAlt} +slingshot=${sl.maxAlt} +cruise=${cr.maxAlt}")
        assertTrue(sl.slingshot && sl.maxAlt > b.maxAlt * 1.02)
        assertTrue(cr.maxAlt > b.maxAlt * 1.015)
        // and Mars / Jupiter flights never get the slingshot
        assertFalse(simulateAutopilot(RocketSpec(all), MissionTarget.JUPITER).slingshot)
    }

    @Test fun coastPredictionMatchesTheFlight() {
        // the nav computer's "bound" test includes the Jupiter slingshot and the cruise push: a coast it predicts
        // reaches Saturn really does, and a coast just short of that does not
        val spec = RocketSpec(TechTree.nodes.map { it.id }.toSet())
        val alt = 2.0e9
        var lo = 1_000.0; var hi = 120_000.0
        repeat(60) { val mid = (lo + hi) / 2; if (Autopilot.coastReaches(spec, alt, mid, MissionTarget.SATURN)) hi = mid else lo = mid }
        fun coast(v: Double): Double {
            val f = com.brandonwallace.rocketlauncher.game.FlightState(spec, MissionTarget.SATURN)
            f.alt = alt; f.vy = v; f.liftedOff = true; f.stage = spec.stages.size - 1; f.fuel.fill(0.0); f.boostersAttached = false
            val ev = ArrayList<com.brandonwallace.rocketlauncher.game.FlightEvent>()
            while (f.vy > 0 && f.alt < Physics.SATURN_ARRIVAL_ALT) f.step((f.alt / f.vy * 1e-4).coerceIn(1.0, 600.0), 0.0, ev)
            return f.maxAlt
        }
        assertTrue(coast(hi * 1.003) >= Physics.SATURN_ARRIVAL_ALT)
        assertTrue(coast(lo * 0.99) < Physics.SATURN_ARRIVAL_ALT)
    }

    @Test fun maxRocketReachesMarsInReasonableTime() {
        val r = simulateAutopilot(RocketSpec(TechTree.nodes.map { it.id }.toSet()))
        println("maxed Mars: reached=${r.reachedMars} orbit=${r.marsOrbit} landed=${r.marsLanded} top=${r.topSpeed} simDays=${r.time / 86400} realTime=${r.warpedRealTime}")
        assertTrue(r.reachedMars)
        assertTrue("maxed autopilot lands on Mars", r.marsLanded)
        assertTrue("real time ${r.warpedRealTime}", r.warpedRealTime in 20.0..240.0)
        // the v2 endgame rocket (no Mars-program tech) must not be able to reach Mars
        val v2 = TechTree.nodes.filter { it.tier <= 5 }.map { it.id }.toSet()
        val r2 = simulateAutopilot(RocketSpec(v2))
        println("v2-maxed Mars attempt: reached=${r2.reachedMars} maxAlt=${r2.maxAlt / 1000} km")
        assertFalse("Mars needs Mars-program tech", r2.reachedMars)
        // without Deep-Space Navigation the autopilot burns every drop: Mars flyby at best, never a landing
        val noDsn = simulateAutopilot(RocketSpec(TechTree.nodes.filter { it.tier < TechTree.SATURN_TIER }.map { it.id }.toSet() - "AV_DSN" - "EDL_CHUTE" - "EDL_SRP"))
        assertFalse(noDsn.marsLanded)
    }

    @Test fun missionLineAndSaveRoundTrip() {
        val s = SaveData(money = 5, moonLandings = 1, marsOrbits = 2, marsLandings = 0,
            milestones = setOf(Milestone.KARMAN, Milestone.LEO, Milestone.MOON, Milestone.SOI))
        assertEquals(s, SaveData.parse(s.serialize()))
        assertEquals("one mission, 18 steps (twice 1.2's nine)", 18, Mission.entries.size)
        assertEquals(5, Mission.doneCount(s))
        assertEquals(Mission.MARS_LANDING, Mission.next(s))
        assertEquals(Mission.SPACE, Mission.next(SaveData()))
        assertEquals(listOf("SPACE", "ORBIT", "MOON", "MARS_TRANSFER", "MARS_ORBIT", "MARS_LANDING", "JUPITER_TRANSFER", "JUPITER_ORBIT", "EUROPA_LANDING",
            "JUPITER_SLINGSHOT", "HALFWAY_SATURN", "BILLION_KM", "SATURN_ARRIVAL", "RING_CROSSING", "SATURN_ORBIT", "ENCELADUS", "TITAN_PROBE", "TITAN_FLIGHT"),
            Mission.entries.map { it.name })
        assertEquals(9, Mission.entries.count { it.saturnChapter })
        val europa = s.copy(marsLandings = 1, jupiterOrbits = 1, europaLandings = 1, milestones = Milestone.entries.filter { it.altitude <= Physics.JUPITER_ARRIVAL_ALT }.toSet())
        assertEquals(9, Mission.doneCount(europa)); assertEquals(Mission.JUPITER_SLINGSHOT, Mission.next(europa))
        assertEquals(europa, SaveData.parse(europa.serialize()))
        // each Saturn counter ticks its step (a Titan flight also counts as every step below it)
        val ringed = europa.copy(milestones = Milestone.entries.toSet(), ringCrossings = 1)
        assertEquals(Mission.SATURN_ORBIT, Mission.next(ringed))
        assertEquals(Mission.ENCELADUS, Mission.next(ringed.copy(saturnOrbits = 1)))
        assertEquals(Mission.TITAN_PROBE, Mission.next(ringed.copy(saturnOrbits = 1, enceladusPasses = 1)))
        assertEquals(Mission.TITAN_FLIGHT, Mission.next(ringed.copy(saturnOrbits = 1, enceladusPasses = 1, titanProbes = 1)))
        val done = ringed.copy(saturnOrbits = 2, enceladusPasses = 2, titanProbes = 2, titanFlights = 1)
        assertEquals(18, Mission.doneCount(done)); assertEquals(null, Mission.next(done))
        assertEquals(done, SaveData.parse(done.serialize()))
        // warp stays continuous at the Moon-cap boundary and is never below the Moon value
        for (a in listOf(1e8, 3.8e8, 6e8, 1e9, 5e10)) assertTrue(Physics.timeWarp(a, Physics.MARS_WARP_CAP) >= Physics.timeWarp(a))
        assertEquals(Physics.potential(Physics.EARTH_SOI - 1.0), Physics.potential(Physics.EARTH_SOI + 1.0), 1.0)
        assertEquals(Physics.potential(Physics.MARS_ARRIVAL_ALT - 1.0), Physics.potential(Physics.MARS_ARRIVAL_ALT + 1.0), 1.0)
        assertEquals(Physics.potential(Physics.JUPITER_ARRIVAL_ALT - 1.0), Physics.potential(Physics.JUPITER_ARRIVAL_ALT + 1.0), 1.0)
        // the pull past Jupiter falls off with distance, and the potential keeps climbing all the way to Saturn
        assertTrue(Physics.sunPull(6.0e11) > Physics.sunPull(9.0e11) && Physics.sunPull(9.0e11) > Physics.sunPull(Physics.SATURN_ARRIVAL_ALT))
        assertTrue(Physics.potential(1.0e12) > Physics.potential(9.0e11))
        // Mars flights keep the 1.1 warp; Jupiter flights slow down around Mars and run faster in the outer system
        assertEquals(Physics.MARS_WARP_CAP, Physics.warpCap(MissionTarget.MARS, 3e11), 0.0)
        assertTrue(Physics.warpCap(MissionTarget.JUPITER, Physics.MARS_ARRIVAL_ALT) < 10_000.0)
        assertEquals(Physics.JUPITER_WARP_CAP, Physics.warpCap(MissionTarget.JUPITER, 3e11), 0.0)
        // Saturn flights also slow for Mars, for the Jupiter slingshot and on the final approach
        for (a in listOf(Physics.MARS_ARRIVAL_ALT, Physics.JUPITER_ARRIVAL_ALT, Physics.SATURN_ARRIVAL_ALT - 1e7))
            assertTrue(Physics.warpCap(MissionTarget.SATURN, a, 20_000.0) < 10_000.0)
        assertEquals(Physics.SATURN_WARP_CAP, Physics.warpCap(MissionTarget.SATURN, 8.5e11, 30_000.0), 0.0)
        assertTrue("slow coasts get more warp", Physics.warpCap(MissionTarget.SATURN, 8.5e11, 500.0) > Physics.SATURN_WARP_CAP)
        // altitude pay is the 1.1 formula up to Mars, then distance pay out to Jupiter, then out to Saturn
        assertEquals(Math.round(95.0 * 100.0.pow(0.6) + 420.0 * kotlin.math.ln(Physics.MARS_ARRIVAL_ALT / 1000.0 / 100.0)), Economy.altitudePay(Physics.MARS_ARRIVAL_ALT))
        assertTrue(Economy.altitudePay(Physics.JUPITER_ARRIVAL_ALT) > Economy.altitudePay(Physics.MARS_ARRIVAL_ALT) + 19_000)
        val payRatio = Economy.altitudePay(Physics.SATURN_ARRIVAL_ALT).toDouble() / Economy.altitudePay(Physics.JUPITER_ARRIVAL_ALT)
        assertEquals("Saturn pays 2.04x Jupiter, like it is 2.04x as far", 2.04, payRatio, 0.02)
        assertEquals(Physics.SATURN_ARRIVAL_ALT / Physics.JUPITER_ARRIVAL_ALT, payRatio, 0.02)
        assertEquals(163_000L, Milestone.SATURN.bonus); assertEquals(2.04, Milestone.SATURN.bonus / Milestone.JUPITER.bonus.toDouble(), 0.01)
        // milestones are ordered along the single flight path; 1.3 doubled them (17 -> 35) and the badges (14 -> 29)
        assertEquals(Milestone.entries.sortedBy { it.altitude }, Milestone.entries)
        assertEquals(35, Milestone.entries.size); assertEquals(29, Achievement.entries.size)
        assertEquals(Physics.LIGHT_HOUR, 299_792_458.0 * 3600, 1.0)
    }

    @Test fun environment() {
        assertEquals(1.225, Physics.density(0.0), 1e-9)
        assertEquals(1.0, Physics.timeWarp(0.0), 0.0)
        assertEquals(Physics.timeWarp(99_999.0), Physics.timeWarp(100_001.0), 0.01)
    }
}
