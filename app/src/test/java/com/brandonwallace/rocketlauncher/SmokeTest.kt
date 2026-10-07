package com.brandonwallace.rocketlauncher

import com.brandonwallace.rocketlauncher.audio.SoundEngine
import com.brandonwallace.rocketlauncher.game.Economy
import com.brandonwallace.rocketlauncher.game.Milestone
import com.brandonwallace.rocketlauncher.game.Mission
import com.brandonwallace.rocketlauncher.game.Physics
import com.brandonwallace.rocketlauncher.game.GameEngine
import com.brandonwallace.rocketlauncher.game.Phase
import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.SaveData
import com.brandonwallace.rocketlauncher.game.SaveStore
import com.brandonwallace.rocketlauncher.game.TechTree
import com.brandonwallace.rocketlauncher.game.Autopilot
import com.brandonwallace.rocketlauncher.game.FlightResult
import java.math.BigDecimal
import com.brandonwallace.rocketlauncher.gl.BillboardBatch
import com.brandonwallace.rocketlauncher.gl.GameRenderer
import com.brandonwallace.rocketlauncher.gl.LaunchComplex
import com.brandonwallace.rocketlauncher.gl.MeshBuilder
import com.brandonwallace.rocketlauncher.gl.Particles
import com.brandonwallace.rocketlauncher.gl.RocketModel
import com.brandonwallace.rocketlauncher.gl.TerrainGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless smoke test: the real GameEngine plays a whole campaign while the real GameRenderer draws frames
 * (GL calls are JVM stubs, so every CPU path — mesh generation, camera, particles, sorting, interpolation,
 * Choreographer-style frame timing — actually runs). Also checks save reload, abort, resume and results.
 */
class SmokeTest {
    private class MemStore : SaveStore {
        var data: String? = null
        var syncWrites = 0
        override fun load() = data
        override fun save(data: String) { this.data = data }
        override fun saveNow(data: String) { this.data = data; syncWrites++ }
    }

    private class Harness(initial: String? = null) {
        val store = MemStore().also { it.data = initial }
        val engine = GameEngine(store)
        val renderer = GameRenderer(engine, SoundEngine())
        var t = 1_000_000_000L
        init { renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null, 1080, 2400) }
        fun frame(render: Boolean) {
            t += 16_666_667L
            if (render) { renderer.vsyncNanos = t; renderer.onDrawFrame(null) } else engine.advance(1f / 60f)
        }
    }

    /** Press LAUNCH and watch: 1.1 has no flight input at all, the autopilot flies. Returns true once results show. */
    private fun fly(h: Harness, render: Boolean, maxFrames: Int = 40_000): Boolean {
        val e = h.engine
        e.openHangar(); h.frame(render)
        e.launch()
        var frames = 0
        while (e.phase != Phase.RESULTS && frames < maxFrames) { h.frame(render); frames++ }
        return e.phase == Phase.RESULTS
    }

    @Test fun fullCampaignThroughEngineAndRenderer() {
        val h = Harness()
        val e = h.engine
        var flights = 0
        var moonFlight = -1
        var lastMoney = 0L
        while (flights < 80) {
            val before = e.save.value
            val balanceAtLaunch = before.money
            val carryAtLaunch = before.interestCarry
            val render = flights % 6 == 0 || flights > 24
            assertTrue("flight $flights finished", fly(h, render))
            flights++
            val r = e.hud.value.results ?: error("no results after flight $flights")
            val after = e.save.value
            println("flight $flights: alt=${r.maxAlt.toInt()} t=${r.time.toInt()} reason=${r.reason} hits=${r.hits} total=${r.total} techs=${after.tech.size}")
            // visible lines add up exactly to the total; the hidden interest is inside "Flight performance"
            assertEquals(r.total, r.performancePay + r.milestones.sumOf { it.bonus })
            assertEquals(Economy.altitudePay(r.maxAlt) + Economy.interestPayout(balanceAtLaunch, carryAtLaunch).first, r.performancePay)
            assertEquals(Economy.interestPayout(balanceAtLaunch, carryAtLaunch).second, after.interestCarry)
            assertEquals(balanceAtLaunch + r.total, after.money)
            assertEquals(after.money, r.balanceAfter)
            assertTrue(after.money >= 0)
            lastMoney = after.money
            if (r.moonPassed && moonFlight < 0) moonFlight = flights
            if (r.moonPassed) break
            // research: cheapest affordable available node first (renderer rebuilds the rocket each time)
            while (true) {
                val n = TechTree.available(e.save.value.tech).filter { it.cost <= e.save.value.money }.minByOrNull { it.cost } ?: break
                assertTrue(e.research(n.id))
                h.frame(true)
            }
            e.openHangar(); repeat(3) { h.frame(true) }
            e.openTech(); h.frame(true)
        }
        println("SmokeTest campaign: flew past the Moon on flight $moonFlight with ${e.save.value.tech.size} techs, balance $lastMoney")
        assertTrue("got past the Moon through the real engine (flights=$flights)", moonFlight in 20..50)

        // save reload: a fresh engine on the same store sees identical progress
        val reloaded = GameEngine(h.store)
        assertEquals(e.save.value, reloaded.save.value)
        assertEquals(SaveData.parse(e.save.value.serialize()), e.save.value)
    }

    @Test fun maxedRocketFliesPastMoonOnTheWayToMars() {
        val h = Harness()
        val e = h.engine
        e.grantMoney(TechTree.totalCost + 10)
        while (true) { val n = TechTree.available(e.save.value.tech).firstOrNull() ?: break; assertTrue(n.id, e.research(n.id)) }
        assertEquals(TechTree.nodes.size, e.save.value.tech.size)
        for (q in listOf(0, 2, 1)) { e.setQuality(q); h.frame(true) }
        e.openHangar(); h.frame(true); e.launch()
        var frames = 0
        var moonSeenAtFrame = -1
        var flightStillGoingAfterMoon = false
        while (e.phase != Phase.RESULTS && frames < 60_000) {
            h.frame(true); frames++
            if (e.hud.value.moonPassed && moonSeenAtFrame < 0) moonSeenAtFrame = frames
            if (moonSeenAtFrame > 0 && e.phase == Phase.FLIGHT && (e.flight?.alt ?: 0.0) > Physics.EARTH_SOI) flightStillGoingAfterMoon = true
        }
        val r = e.hud.value.results!!
        println("Maxed one-mission flight: moonPassed=${r.moonPassed} (frame $moonSeenAtFrame) mars=${r.marsReached} landed=${r.marsLanded} hits=${r.hits} frames=$frames reason=${r.reason}")
        assertTrue("the flight passes the Moon: ${r.reason}", r.moonPassed)
        assertTrue("…and keeps going on the same flight toward Mars", flightStillGoingAfterMoon)
        assertTrue("maxed rocket reaches Mars: ${r.reason}", r.marsReached)
        assertTrue(Milestone.MOON in e.save.value.milestones && Milestone.SOI in e.save.value.milestones)
    }

    @Test fun maxedRocketLandsOnMarsWithRendererRunning() {
        val all = TechTree.nodes.map { it.id }.toSet()
        // a 1.0-format (schema 2) late-game save, like the emulator test save: migrates and flies the one mission
        val start = SaveData(money = 0, tech = all, moonLandings = 1,
            milestones = Milestone.entries.filter { it.altitude <= Physics.MOON_ARRIVAL_ALT }.toSet())
        val legacy = start.serialize().replace("v=3;", "v=2;") + ";dest=MARS;tilt=false"
        val h = Harness(legacy)
        val e = h.engine
        assertEquals(start, e.save.value)
        e.openHangar(); h.frame(true); e.launch()
        var frames = 0
        var maxFrameAltJump = 0.0
        var lastAlt = 0.0
        val zonesSeen = ArrayList<String>()
        var lastBanner = ""
        var navCutSeen = false
        while (e.phase != Phase.RESULTS && frames < 120_000) {
            h.frame(true); frames++
            if (e.hud.value.navCut) navCutSeen = true
            val hb = e.hud.value.banner
            if (hb != lastBanner) { zonesSeen += hb; lastBanner = hb }
            val a = e.flight?.alt ?: 0.0
            if (a > 1e9) maxFrameAltJump = maxOf(maxFrameAltJump, (a - lastAlt) / Physics.MARS_ARRIVAL_ALT)
            lastAlt = a
        }
        val r = e.hud.value.results!!
        println("Mars flight: frames=$frames (~${frames / 60}s) reason=${r.reason} orbit=${r.marsOrbit} landed=${r.marsLanded} pay=${r.total} maxStep=${"%.4f".format(maxFrameAltJump)}")
        println("Mars banners: " + zonesSeen.joinToString(" | "))
        assertTrue("reached Mars: ${r.reason}", r.marsReached)
        assertTrue("landed on Mars: ${r.reason}", r.marsLanded)
        assertTrue("Deep-Space Navigation cut the engines on the way", navCutSeen)
        assertTrue("Mars trip should take 40 s .. 4 min of real time, took ${frames / 60}s", frames in 2_400..14_400)
        assertTrue("no single frame jumps more than 3% of the way", maxFrameAltJump < 0.03)
        assertEquals(1, e.save.value.marsLandings)
        assertTrue(Milestone.MARS in e.save.value.milestones)
        assertEquals(Mission.entries.size, Mission.doneCount(e.save.value))
        // money only from height/distance: the payout is exactly altitude pay + new milestones (+ hidden interest on $0 = 0)
        val st = Economy.settle(0, r.maxAlt, start.milestones)
        assertEquals(st.total, r.total)
    }

    @Test fun singleMissionNoDestinationChoice() {
        // a 1.0 save that had picked the Moon still flies the one Moon->Mars mission: no picker, no Moon stop
        val all = TechTree.nodes.map { it.id }.toSet()
        val h = Harness(SaveData(tech = all).serialize().replace("v=3;", "v=2;") + ";dest=MOON;tilt=true")
        val e = h.engine
        assertTrue(e.save.value.extras.isEmpty())
        val engineApi = GameEngine::class.java.methods.map { it.name }
        assertFalse("no destination picker", engineApi.any { it.contains("Destination", ignoreCase = true) })
        assertTrue(fly(h, false, 120_000))
        val r = e.hud.value.results!!
        assertTrue("did not stop at the Moon: ${r.reason}", r.moonPassed && r.marsReached)
    }

    @Test fun abortPauseAndResume() {
        val h = Harness()
        val e = h.engine
        e.openHangar(); e.launch()
        repeat(400) { h.frame(true) }
        assertEquals(Phase.FLIGHT, e.phase)
        e.onAppResumed()
        assertTrue("resuming the app pauses an active flight", e.paused)
        val alt = e.flight!!.alt
        repeat(120) { h.frame(true) }
        assertEquals("paused flight does not move", alt, e.flight!!.alt, 0.0)
        // surface re-creation (GL context loss) mid-flight must rebuild without crashing
        h.renderer.onSurfaceCreated(null, null); h.renderer.onSurfaceChanged(null, 2400, 1080); h.frame(true)
        h.renderer.onSurfaceChanged(null, 1080, 2400); h.frame(true)
        e.setPaused(false)
        repeat(60) { h.frame(true) }
        e.abortFlight()
        var guard = 0
        while (e.phase != Phase.RESULTS && guard++ < 2000) h.frame(true)
        assertEquals(Phase.RESULTS, e.phase)
        assertEquals(1, e.save.value.flights)
    }

    @Test fun everyTechCombinationBuildsValidGeometry() {
        // grow the tech set node by node in tree order and build the rocket each time
        val owned = LinkedHashSet<String>()
        var prevLen = 0f
        for (n in TechTree.nodes.sortedBy { it.tier * 100 + it.row }) {
            if (!n.prereqs.all { it in owned }) continue
            owned += n.id
            val spec = RocketSpec(owned)
            val m = RocketModel(spec, 24)
            assertTrue(m.parts.isNotEmpty())
            assertTrue(m.totalLength > 3f && m.totalLength < 120f)
            for (i in 1 until m.stageBase.size) assertTrue("stage bases increase", m.stageBase[i] > m.stageBase[i - 1])
            assertEquals(spec.stages.size, m.nozzles.size)
            assertEquals(spec.boosterCount, m.boosterOffsets.size)
            prevLen = m.totalLength
        }
        assertTrue(prevLen > 10f)
        // complex + terrain builders
        val c = LaunchComplex(prevLen, 24)
        assertEquals(3, c.arms.size)
        assertTrue(TerrainGen.height(0f, 0f) < 0f)            // terrain sits below the pad deck: no coplanar z-fight
        assertTrue(TerrainGen.height(9000f, 0f) < -2f)        // ocean to the east
    }

    @Test fun latheNormalsPointOutward() {
        val b = MeshBuilder().lathe(floatArrayOf(1f, 0f, 1f, 2f), 16)
        val (v, _) = b.buildArrays()
        for (i in 0 until v.size / 11) {
            val x = v[i * 11]; val z = v[i * 11 + 2]; val nx = v[i * 11 + 3]; val nz = v[i * 11 + 5]
            assertTrue(x * nx + z * nz > 0.9f)
        }
    }

    @Test fun billboardsSortBackToFrontAndAdditiveLast() {
        val bb = BillboardBatch(64)
        bb.begin()
        val depths = floatArrayOf(5f, 50f, 20f, 1f, 33f)
        for ((i, d) in depths.withIndex()) bb.stage(0f, 0f, -d, 1f, 1f, 1f, 1f, 1f, if (i == 2) Particles.FLAME else Particles.SMOKE, 0f, 0.5f, 0f, d)
        val n = bb.finish()
        assertEquals(5, n)
        assertEquals(4, bb.alphaCount)
        val z = (0 until 4).map { -bb.data[it * 12 + 2] }
        assertEquals(listOf(50f, 33f, 5f, 1f), z)            // far to near
        assertEquals(Particles.FLAME.toFloat(), bb.data[4 * 12 + 8])
        assertFalse(bb.data.any { it.isNaN() })
    }

    // ---------------- new in 1.1

    @Test fun noPlayerFlightControlsAndDeterministicOutcome() {
        // the engine exposes no throttle / steering / tilt inputs any more
        val names = GameEngine::class.java.methods.map { it.name } + GameEngine::class.java.declaredFields.map { it.name }
        for (bad in listOf("inputThrottle", "inputSteer", "setInputThrottle", "setInputSteer", "setTilt", "setDestination"))
            assertFalse("$bad must not exist", names.contains(bad))
        assertFalse(SaveData::class.java.declaredFields.any { it.name.contains("tilt", true) || it.name.contains("destination", true) })
        // same save + same upgrades -> identical flight (no input, deterministic hazards)
        fun run(): FlightResult {
            val h = Harness(SaveData(money = 3000, tech = setOf("P_GRAIN", "S_ALU", "A_FINS", "AV_RADIO", "P_LIQUID", "S_STRETCH"), flights = 3).serialize())
            assertTrue(fly(h, false))
            return h.engine.hud.value.results!!
        }
        val a = run(); val b = run()
        assertEquals(a.maxAlt, b.maxAlt, 0.0); assertEquals(a.hits, b.hits); assertEquals(a.total, b.total)
        println("deterministic flight: alt=${a.maxAlt} hits=${a.hits} total=${a.total}")
    }

    @Test fun autopilotAvoidsMoreHazardsWithUpgrades() {
        // same hazard layouts (flights 0..11) for a bare rocket vs. the same rocket with radar/guidance/fins:
        // the autopilot alone (no input) must take fewer hits with the upgrades
        val engine = setOf("P_GRAIN", "S_ALU", "P_LIQUID", "S_STRETCH", "P_TURBO", "S_ALLI", "ST_2")
        val dodge = engine + setOf("A_FINS", "AV_RADIO", "A_SWEPT", "AV_INERTIAL")
        fun hits(tech: Set<String>): Int {
            var total = 0
            for (n in 0 until 12) {
                val h = Harness(SaveData(tech = tech, flights = n).serialize())
                assertTrue(fly(h, false))
                total += h.engine.hud.value.results!!.hits
            }
            return total
        }
        val bare = hits(engine); val smart = hits(dodge)
        println("hazard hits over 12 flights: no avionics/fins=$bare, radar+guidance+fins=$smart")
        assertTrue("upgrades reduce hits ($bare -> $smart)", smart < bare)
        assertTrue(Autopilot.lookahead(RocketSpec(dodge)) > Autopilot.lookahead(RocketSpec(engine)))
    }

    @Test fun interestOnlyOnMoneyUnspentAtLaunchThroughEngine() {
        val h = Harness(SaveData(money = 5000).serialize())
        val e = h.engine
        e.openHangar(); h.frame(false)
        assertTrue(e.research("P_GRAIN"))                       // spend some before launch
        val unspent = e.save.value.money
        assertTrue(unspent < 5000)
        assertTrue(fly(h, false))
        val r = e.hud.value.results!!
        val (paid, carry) = Economy.interestPayout(unspent)
        assertEquals("interest is on the unspent $unspent, not the starting 5000", Economy.altitudePay(r.maxAlt) + paid, r.performancePay)
        assertEquals(unspent + r.total, e.save.value.money)
        assertEquals(carry, e.save.value.interestCarry)
        // money earned during the flight is not part of this launch's interest base
        assertTrue(r.total < Economy.altitudePay(r.maxAlt) + Economy.interest(unspent + r.total) + r.milestones.sumOf { it.bonus })
        println("engine interest: unspent=$unspent paid=$paid carry=$carry perf=${r.performancePay} total=${r.total}")
    }

    @Test fun interestNeverShownAsSeparateLine() {
        // the result/HUD models carry no interest field, and the UI source never mentions it
        assertFalse(FlightResult::class.java.declaredFields.any { it.name.contains("interest", true) })
        assertFalse(com.brandonwallace.rocketlauncher.game.HudState::class.java.declaredFields.any { it.name.contains("interest", true) })
        val ui = java.io.File("src/main/java/com/brandonwallace/rocketlauncher/ui").listFiles()!!.filter { it.name.endsWith(".kt") }
        assertTrue(ui.isNotEmpty())
        for (f in ui) assertFalse("${f.name} must not show interest", f.readText().contains("interest", ignoreCase = true))
        // visible lines add up exactly to the total
        val h = Harness(SaveData(money = 1000).serialize())
        assertTrue(fly(h, false))
        val r = h.engine.hud.value.results!!
        assertEquals(r.total, r.performancePay + r.milestones.sumOf { it.bonus })
        assertEquals(Economy.altitudePay(r.maxAlt) + 67, r.performancePay)   // 1000 unspent -> 67.69 -> $67 inside "Flight performance"
    }

    @Test fun moneyUnlocksAndInterestCarrySurviveReloadAndBackground() {
        val h = Harness(SaveData(money = 1000).serialize())
        val e = h.engine
        assertTrue(fly(h, false))
        val after = e.save.value
        assertEquals(BigDecimal("0.69").setScale(5), after.interestCarry)
        assertTrue(e.research("P_GRAIN"))
        e.persistNow()                                            // what MainActivity.onPause/onStop call
        assertEquals("on disk = in memory", e.save.value.serialize(), h.store.data)
        val reloaded = GameEngine(h.store)                        // app restart / update: same store
        assertEquals(e.save.value, reloaded.save.value)
        assertEquals(e.save.value.money, reloaded.save.value.money)
        assertTrue(reloaded.save.value.has("P_GRAIN"))
        assertEquals(BigDecimal("0.69").setScale(5), reloaded.save.value.interestCarry)
        assertTrue(h.store.data!!.startsWith("v=3;"))
        // nothing changed -> no extra write; an empty/unreadable store is never overwritten by an untouched default save
        val writes = h.store.syncWrites
        reloaded.persistNow(); assertEquals(writes, h.store.syncWrites)
        val empty = Harness(null)
        empty.engine.persistNow(); assertEquals(0, empty.store.syncWrites); assertEquals(null, empty.store.data)
        // a 1.0 save is rewritten in the new schema by persistNow (what MainActivity does after migrating)
        val legacy = Harness("v=2;money=42;dest=MOON;tilt=true;tech=P_GRAIN")
        legacy.engine.persistNow()
        assertEquals(1, legacy.store.syncWrites); assertTrue(legacy.store.data!!.startsWith("v=3;money=42;"))
    }
}
