package com.brandonwallace.rocketlauncher

import com.brandonwallace.rocketlauncher.game.Economy
import com.brandonwallace.rocketlauncher.game.Mission
import com.brandonwallace.rocketlauncher.game.Milestone
import com.brandonwallace.rocketlauncher.game.Physics
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

class TechTreeTest {
    @Test fun treeIsWellFormed() {
        val ids = TechTree.nodes.map { it.id }
        assertEquals("54-node tree kept", 54, ids.size)
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
 * Hidden bank interest: 6.769% of the money left unspent at launch, per launch, compounding.
 * Rounding rule: interest is computed exactly (BigDecimal); whole dollars are paid (rounded down) and the
 * sub-dollar remainder is carried in the save to the next launch's payout — nothing is dropped.
 */
class InterestTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Economy.CARRY_SCALE)

    @Test fun interestIs6769PercentOfUnspentMoney() {
        assertEquals(0.06769, Economy.INTEREST_RATE, 0.0)
        assertEquals(BigDecimal("0.06769"), BigDecimal.valueOf(Economy.INTEREST_RATE))   // exact decimal, no float drift
        assertEquals(bd("67.69"), Economy.interestExact(1000))          // unspent $1,000 -> +$67.69 exactly
        assertEquals(67L to bd("0.69"), Economy.interestPayout(1000))   // $67 paid now, $0.69 carried
        assertEquals(67, Economy.interest(1000))
        assertEquals(bd("676.90"), Economy.interestExact(10_000))
        assertEquals(bd("6769"), Economy.interestExact(100_000))
        assertEquals(bd("0.06769"), Economy.interestExact(1))
        assertEquals(0, Economy.interest(0))
        assertEquals(0, Economy.interest(-50))
        assertEquals(bd("0"), Economy.interestExact(-50))
    }

    @Test fun settlementTotalSilentlyIncludesInterest() {
        val st = Economy.settle(unspentAtLaunch = 4000, maxAlt = 20_000.0, already = setOf(Milestone.KM1))
        val pay = Economy.altitudePay(20_000.0)
        val ms = Milestone.CLOUDS.bonus + Milestone.STRATOSPHERE.bonus
        assertEquals(pay + 270, st.performancePay)          // 4000 * 0.06769 = 270.76 -> $270 folded into the one visible pay line
        assertEquals(pay + 270 + ms, st.total)
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
        // balance + carried cents equals exact compound growth, short only by interest-on-pending-cents (< 7¢/launch, compounded)
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
        // launch 1: 1000 * .06769 = 67.69 -> $67 + .69 carried
        // launch 2: 1067 * .06769 = 72.22523 (+.69) = 72.91523 -> $72 + .91523
        // launch 3: 1139 * .06769 = 77.09891 (+.91523) = 78.01414 -> $78 + .01414
        assertEquals(listOf(67L, 72L, 78L), paid)
        assertEquals(listOf(bd("0.69"), bd("0.91523"), bd("0.01414")), carries)
        assertEquals(1217L, bal)
    }

    @Test fun remainderIsNeverDropped() {
        // a player who keeps exactly $1,000 unspent for 100 launches gets exactly 100 x 67.69 = 6,769.00
        var carry = BigDecimal.ZERO
        var total = 0L
        repeat(100) { val (p, c) = Economy.interestPayout(1000, carry); total += p; carry = c }
        assertEquals(6769L, total)
        assertEquals(bd("0"), carry)
        // and an odd balance: 3 launches of $7 unspent = 3 x 0.47383 = 1.42149 -> $1 paid, .42149 still owed
        carry = BigDecimal.ZERO; total = 0
        repeat(3) { val (p, c) = Economy.interestPayout(7, carry); total += p; carry = c }
        assertEquals(1L, total); assertEquals(bd("0.42149"), carry)
    }

    @Test fun carriedCentsSurviveSaveLoad() {
        val st = Economy.settle(1000, 0.0, emptySet())
        val s = SaveData(money = 1000 + st.total, interestCarry = st.interestCarry)
        val back = SaveData.parse(s.serialize())
        assertEquals(bd("0.69"), back.interestCarry)
        assertEquals(1067, back.money)
        assertTrue(s.serialize().contains(";ic=0.69000;"))
        // next launch uses the reloaded carry: 1067*.06769 + .69 = 72.91523
        assertEquals(72L, Economy.settle(back.money, 0.0, emptySet(), back.interestCarry).interest)
        // corrupt carry values never crash or inflate pay
        assertEquals(bd("0"), SaveData.parse("v=3;money=5;ic=abc").interestCarry)
        assertEquals(bd("0"), SaveData.parse("v=3;money=5;ic=7.5").interestCarry)
    }
}

/** Save schema versioning and migration (1.0 saves must load in 1.1 without losing money or unlocks). */
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
        assertTrue(out.startsWith("v=3;money=48213;"))
        assertFalse(out.contains("dest=")); assertFalse(out.contains("tilt="))
        assertEquals(s, SaveData.parse(out))
        // already-passed Moon counts toward the one mission
        assertEquals(3, Mission.doneCount(s))
        assertEquals(Mission.MARS_TRANSFER, Mission.next(s))
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
        val s = SaveData.parse("v=4;money=5;tech=P_GRAIN;futureThing=abc,def;rocketName=Bob")
        assertEquals(5, s.money)
        assertEquals(mapOf("futureThing" to "abc,def", "rocketName" to "Bob"), s.extras)
        val out = s.serialize()
        assertTrue(out.contains(";futureThing=abc,def")); assertTrue(out.contains(";rocketName=Bob"))
        assertEquals(s, SaveData.parse(out))
    }

    @Test fun migrationStepsRunInOrder() {
        val m = SaveData.migrate(mapOf("v" to "2", "money" to "1", "dest" to "MOON", "tilt" to "false"))
        assertEquals("3", m["v"]); assertEquals("1", m["money"]); assertEquals("0", m["ic"])
        assertFalse("dest" in m); assertFalse("tilt" in m)
        assertEquals("3", SaveData.migrate(mapOf("money" to "1"))["v"])
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
                val pick = strategy(save, TechTree.available(save.tech).filter { save.money >= it.cost }) ?: break
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
        assertTrue("apogee-greedy took $b flights", b in 25..35)
    }

    @Test fun hoardingIsNotAnExploit() {
        // savers only research once they can afford the cheapest node 2x / 5x over; even at 6.769% interest
        // hoarding must not beat simply spending
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
                val pick = TechTree.available(save.tech).filter { save.money >= it.cost }.minByOrNull { it.cost } ?: break
                save = save.research(pick.id)!!
            }
        }
        println("[mars-campaign] past Moon at $moonAt, Mars reached at $marsAt, Mars landing at $landAt$sb")
        assertTrue("Moon still 25..35 flights ($moonAt)", moonAt in 25..35)
        assertTrue("Mars landing should be a substantial second act: $landAt", landAt - moonAt in 12..80)
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
        val noDsn = simulateAutopilot(RocketSpec(TechTree.nodes.map { it.id }.toSet() - "AV_DSN" - "EDL_CHUTE" - "EDL_SRP"))
        assertFalse(noDsn.marsLanded)
    }

    @Test fun missionLineAndSaveRoundTrip() {
        val s = SaveData(money = 5, moonLandings = 1, marsOrbits = 2, marsLandings = 0,
            milestones = setOf(Milestone.KARMAN, Milestone.LEO, Milestone.MOON, Milestone.SOI))
        assertEquals(s, SaveData.parse(s.serialize()))
        assertEquals("one mission, six steps", 6, Mission.entries.size)
        assertEquals(5, Mission.doneCount(s))
        assertEquals(Mission.MARS_LANDING, Mission.next(s))
        assertEquals(Mission.SPACE, Mission.next(SaveData()))
        assertEquals(listOf("SPACE", "ORBIT", "MOON", "MARS_TRANSFER", "MARS_ORBIT", "MARS_LANDING"), Mission.entries.map { it.name })
        // warp stays continuous at the Moon-cap boundary and is never below the Moon value
        for (a in listOf(1e8, 3.8e8, 6e8, 1e9, 5e10)) assertTrue(Physics.timeWarp(a, Physics.MARS_WARP_CAP) >= Physics.timeWarp(a))
        assertEquals(Physics.potential(Physics.EARTH_SOI - 1.0), Physics.potential(Physics.EARTH_SOI + 1.0), 1.0)
        // milestones are ordered along the single flight path
        assertEquals(Milestone.entries.sortedBy { it.altitude }, Milestone.entries)
    }

    @Test fun environment() {
        assertEquals(1.225, Physics.density(0.0), 1e-9)
        assertEquals(1.0, Physics.timeWarp(0.0), 0.0)
        assertEquals(Physics.timeWarp(99_999.0), Physics.timeWarp(100_001.0), 0.01)
    }
}
