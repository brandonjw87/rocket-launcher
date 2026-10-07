package com.brandonwallace.rocketlauncher

import com.brandonwallace.rocketlauncher.game.Economy
import com.brandonwallace.rocketlauncher.game.Destination
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

class TechTreeTest {
    @Test fun treeIsWellFormed() {
        val ids = TechTree.nodes.map { it.id }
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
        val s = SaveData(money = 1234, tech = setOf("P_GRAIN", "S_ALU"), milestones = setOf(Milestone.KM1), flights = 7, bestAltitude = 4567.5, quality = 2, tiltControl = true)
        assertEquals(s, SaveData.parse(s.serialize()))
        assertEquals(SaveData(), SaveData.parse(null))
        assertEquals(SaveData(), SaveData.parse("garbage"))
    }
}

class InterestTest {
    @Test fun interestIsTwoPointFivePercentOfCarriedBalance() {
        assertEquals(25, Economy.interest(1000))
        assertEquals(0, Economy.interest(0))
        assertEquals(0, Economy.interest(-50))
        assertEquals(250, Economy.interest(10_000))
    }

    @Test fun settlementTotalSilentlyIncludesInterest() {
        val st = Economy.settle(balanceAtLaunch = 4000, maxAlt = 20_000.0, already = setOf(Milestone.KM1))
        val pay = Economy.altitudePay(20_000.0)
        val ms = Milestone.CLOUDS.bonus + Milestone.STRATOSPHERE.bonus
        assertEquals(pay + 100, st.performancePay)          // interest folded into the one visible pay line
        assertEquals(pay + 100 + ms, st.total)
        // the visible lines (performance pay + milestone bonuses) add up exactly to the total
        assertEquals(st.total, st.performancePay + st.milestones.sumOf { it.bonus })
    }

    @Test fun interestCompoundsAcrossLaunches() {
        // a hoarder who never spends, flying the same 10 km flight 10 times
        var bal = 1000L
        val pay = Economy.altitudePay(10_000.0)
        var expected = 1000.0
        repeat(10) {
            bal += Economy.settle(bal, 10_000.0, Milestone.entries.toSet()).total
            expected = expected * 1.025 + pay
        }
        assertEquals(expected, bal.toDouble(), 6.0) // rounding per launch only
    }

    @Test fun spendingBeforeLaunchForfeitsInterest() {
        val s = SaveData(money = 2000).research("S_ALU")!!
        assertEquals(Economy.interest(2000 - TechTree.byId["S_ALU"]!!.cost), Economy.interest(s.money))
    }
}

class ProgressionTest {
    private fun bonus(maxAlt: Double, got: MutableSet<Milestone>): Long {
        val m = Economy.newMilestones(maxAlt, got); got += m; return m.sumOf { it.bonus }
    }

    /** Autopilot campaign: full throttle each flight, then research per [strategy]. Returns flights to the Moon. */
    private fun campaign(name: String, strategy: (SaveData, List<TechNode>) -> TechNode?, log: Boolean = true): Int {
        var save = SaveData()
        val got = HashSet<Milestone>()
        val sb = StringBuilder()
        for (flight in 1..200) {
            val r = simulateAutopilot(RocketSpec(save.tech))
            val st = Economy.settle(save.money, r.maxAlt, got)
            got += st.milestones
            save = save.copy(money = save.money + st.total)
            if (log && (flight <= 5 || flight % 3 == 0 || r.reachedMoon)) sb.append("\n  #$flight alt=${"%.1f".format(r.maxAlt / 1000)}km earned=${st.total} (interest ${st.interest}) bank=${save.money} nodes=${save.tech.size}")
            if (r.reachedMoon) { println("[$name] MOON at flight $flight$sb"); return flight }
            while (true) {
                val pick = strategy(save, TechTree.available(save.tech).filter { save.money >= it.cost }) ?: break
                save = save.research(pick.id)!!
            }
        }
        println("[$name] never reached the Moon$sb")
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
        // saver: only researches once it can afford the cheapest node twice over
        val saver: (SaveData, List<TechNode>) -> TechNode? = { s, c -> c.minByOrNull { it.cost }?.takeIf { s.money >= it.cost * 2 } }
        val n = campaign("saver", saver)
        assertTrue("saver took $n flights", n >= 25)
    }

    @Test fun maxRocketReachesMoon() {
        val r = simulateAutopilot(RocketSpec(TechTree.nodes.map { it.id }.toSet()))
        println("maxed: moon=${r.reachedMoon} top=${r.topSpeed} realTime=${r.warpedRealTime}")
        assertTrue(r.reachedMoon)
        assertTrue(r.warpedRealTime < 240)
        val base = simulateAutopilot(RocketSpec(emptySet()))
        assertTrue("base apogee ${base.maxAlt}", base.maxAlt in 500.0..2000.0)
    }

    /** Continues the cheapest-first campaign to Mars: flights until the autopilot reaches Mars with a landing-ready rocket. */
    @Test fun marsProgramIsALongerSecondAct() {
        var save = SaveData()
        val got = HashSet<Milestone>()
        var moonAt = -1; var marsAt = -1
        val sb = StringBuilder()
        for (flight in 1..400) {
            val target = if (moonAt > 0) Physics.MARS_ARRIVAL_ALT else Physics.MOON_ARRIVAL_ALT
            val spec = RocketSpec(save.tech)
            val r = simulateAutopilot(spec, targetAlt = target)
            val st = Economy.settle(save.money, r.maxAlt, got)
            got += st.milestones
            save = save.copy(money = save.money + st.total)
            if (flight % 10 == 0 || r.reachedTarget) sb.append("\n  #$flight alt=${"%.0f".format(r.maxAlt / 1000)}km earned=${st.total} bank=${save.money} nodes=${save.tech.size}")
            if (moonAt < 0 && r.reachedTarget) moonAt = flight
            else if (moonAt > 0 && r.reachedTarget && spec.marsEntryReady) { marsAt = flight; break }
            while (true) {
                val pick = TechTree.available(save.tech).filter { save.money >= it.cost }.minByOrNull { it.cost } ?: break
                save = save.research(pick.id)!!
            }
        }
        println("[mars-campaign] Moon at $moonAt, Mars (landing-ready) at $marsAt$sb")
        assertTrue("Moon still 25..35 flights ($moonAt)", moonAt in 25..35)
        assertTrue("Mars should be a substantial second act: $marsAt", marsAt - moonAt in 12..80)
    }

    @Test fun maxRocketReachesMarsInReasonableTime() {
        val r = simulateAutopilot(RocketSpec(TechTree.nodes.map { it.id }.toSet()), targetAlt = Physics.MARS_ARRIVAL_ALT)
        println("maxed Mars: reached=${r.reachedTarget} top=${r.topSpeed} simDays=${r.time / 86400} realTime=${r.warpedRealTime}")
        assertTrue(r.reachedTarget)
        assertTrue("real time ${r.warpedRealTime}", r.warpedRealTime in 20.0..240.0)
        // the v2 endgame rocket (no Mars-program tech) must not be able to reach Mars
        val v2 = TechTree.nodes.filter { it.tier <= 5 }.map { it.id }.toSet()
        val r2 = simulateAutopilot(RocketSpec(v2), targetAlt = Physics.MARS_ARRIVAL_ALT)
        println("v2-maxed Mars attempt: reached=${r2.reachedTarget} maxAlt=${r2.maxAlt / 1000} km")
        assertFalse("Mars needs Mars-program tech", r2.reachedTarget)
    }

    @Test fun missionLineAndSaveRoundTrip() {
        val s = SaveData(money = 5, moonLandings = 1, marsOrbits = 2, marsLandings = 0, destination = Destination.MARS,
            milestones = setOf(Milestone.KARMAN, Milestone.LEO, Milestone.MOON, Milestone.SOI))
        assertEquals(s, SaveData.parse(s.serialize()))
        assertEquals(6, Mission.doneCount(s))
        assertEquals(Mission.MARS_LANDING, Mission.next(s))
        assertEquals(Destination.MARS, s.effectiveDestination)
        assertEquals(Destination.MOON, s.copy(moonLandings = 0).effectiveDestination)
        assertEquals(Mission.SPACE, Mission.next(SaveData()))
        // warp stays continuous at the Moon-cap boundary for Mars flights and is never below the Moon value
        for (a in listOf(1e8, 3.8e8, 6e8, 1e9, 5e10)) assertTrue(Physics.timeWarp(a, Physics.MARS_WARP_CAP) >= Physics.timeWarp(a))
        assertEquals(Physics.potential(Physics.EARTH_SOI - 1.0), Physics.potential(Physics.EARTH_SOI + 1.0), 1.0)
    }

    @Test fun environment() {
        assertEquals(1.225, Physics.density(0.0), 1e-9)
        assertEquals(1.0, Physics.timeWarp(0.0), 0.0)
        assertEquals(Physics.timeWarp(99_999.0), Physics.timeWarp(100_001.0), 0.01)
    }
}
