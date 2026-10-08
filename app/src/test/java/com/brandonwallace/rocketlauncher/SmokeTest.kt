package com.brandonwallace.rocketlauncher

import com.brandonwallace.rocketlauncher.audio.SoundEngine
import com.brandonwallace.rocketlauncher.game.Economy
import com.brandonwallace.rocketlauncher.game.Milestone
import com.brandonwallace.rocketlauncher.game.Mission
import com.brandonwallace.rocketlauncher.game.MissionTarget
import com.brandonwallace.rocketlauncher.game.Physics
import com.brandonwallace.rocketlauncher.game.GameEngine
import com.brandonwallace.rocketlauncher.game.Phase
import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.SaveData
import com.brandonwallace.rocketlauncher.game.SaveStore
import com.brandonwallace.rocketlauncher.game.TechTree
import com.brandonwallace.rocketlauncher.game.Autopilot
import com.brandonwallace.rocketlauncher.game.FlightResult
import com.brandonwallace.rocketlauncher.game.forecastFlight
import com.brandonwallace.rocketlauncher.game.missionFraction
import com.brandonwallace.rocketlauncher.game.missionPercent
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
                val n = TechTree.available(e.save.value).filter { it.cost <= e.save.value.money }.minByOrNull { it.cost } ?: break
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
        while (true) { val n = TechTree.available(e.save.value).firstOrNull() ?: break; assertTrue(n.id, e.research(n.id)) }
        // no Mars landing yet: everything but the Jupiter and Saturn programs
        assertEquals(TechTree.nodes.count { it.tier < TechTree.JUPITER_TIER }, e.save.value.tech.size)
        assertFalse(e.research("P_RAPTOR3"))
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
        val legacy = start.serialize().replace("v=5;", "v=2;") + ";dest=MARS;tilt=false"
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
        assertEquals(Mission.MARS_LANDING.ordinal + 1, Mission.doneCount(e.save.value))
        assertEquals(Mission.JUPITER_TRANSFER, Mission.next(e.save.value))
        // money only from height/distance: the payout is exactly altitude pay + new milestones (+ hidden interest on $0 = 0)
        val st = Economy.settle(0, r.maxAlt, start.milestones)
        assertEquals(st.total, r.total)
    }

    @Test fun singleMissionNoDestinationChoice() {
        // a 1.0 save that had picked the Moon still flies the one Moon->Mars mission: no picker, no Moon stop
        val all = TechTree.nodes.map { it.id }.toSet()
        val h = Harness(SaveData(tech = all).serialize().replace("v=5;", "v=2;") + ";dest=MOON;tilt=true")
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
        assertEquals(Economy.altitudePay(r.maxAlt) + 167, r.performancePay)   // 1000 unspent -> 167.69 -> $167 inside "Flight performance"
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
        assertTrue(h.store.data!!.startsWith("v=5;"))
        // nothing changed -> no extra write; an empty/unreadable store is never overwritten by an untouched default save
        val writes = h.store.syncWrites
        reloaded.persistNow(); assertEquals(writes, h.store.syncWrites)
        val empty = Harness(null)
        empty.engine.persistNow(); assertEquals(0, empty.store.syncWrites); assertEquals(null, empty.store.data)
        // a 1.0 save is rewritten in the new schema by persistNow (what MainActivity does after migrating)
        val legacy = Harness("v=2;money=42;dest=MOON;tilt=true;tech=P_GRAIN")
        legacy.engine.persistNow()
        assertEquals(1, legacy.store.syncWrites); assertTrue(legacy.store.data!!.startsWith("v=5;money=42;"))
    }

    // ---------------- new in 1.2

    @Test fun maxedRocketFliesPastMarsAndLandsOnEuropa() {
        val all = TechTree.nodes.filter { it.tier < TechTree.SATURN_TIER }.map { it.id }.toSet()
        // a 1.1-format (schema 3) endgame save that has landed on Mars: the next flight goes on to Jupiter
        val start = SaveData(money = 0, tech = all, moonLandings = 1, marsOrbits = 1, marsLandings = 1,
            milestones = Milestone.entries.filter { it.altitude <= Physics.MARS_ARRIVAL_ALT }.toSet())
        val v11 = start.serialize().replace("v=5;", "v=3;").replace(";jupOrbit=0;europa=0;rings=0;satOrbit=0;encel=0;titanProbe=0;titan=0", "")
        val h = Harness(v11)
        val e = h.engine
        assertEquals(start, e.save.value)
        e.openHangar(); h.frame(true); e.launch()
        assertEquals(MissionTarget.JUPITER, e.target)
        var frames = 0
        var maxFrameAltJump = 0.0
        var lastAlt = 0.0
        val banners = ArrayList<String>()
        var lastBanner = ""
        var navCutSeen = false
        var slowestNearMars = Double.MAX_VALUE
        while (e.phase != Phase.RESULTS && frames < 150_000) {
            h.frame(true); frames++
            if (e.hud.value.navCut) navCutSeen = true
            val hb = e.hud.value.banner
            if (hb != lastBanner) { banners += hb; lastBanner = hb }
            val a = e.flight?.alt ?: 0.0
            if (a > 1e9) maxFrameAltJump = maxOf(maxFrameAltJump, (a - lastAlt) / Physics.JUPITER_ARRIVAL_ALT)
            if (kotlin.math.abs(a - Physics.MARS_ARRIVAL_ALT) < 2e8) slowestNearMars = minOf(slowestNearMars, e.warp)
            lastAlt = a
        }
        val r = e.hud.value.results!!
        println("Jupiter flight: frames=$frames (~${frames / 60}s) reason=${r.reason} orbit=${r.jupiterOrbit} europa=${r.europaLanded} pay=${r.total} hits=${r.hits} maxStep=${"%.4f".format(maxFrameAltJump)} warpNearMars=$slowestNearMars")
        println("Jupiter banners: " + banners.joinToString(" | "))
        assertEquals(MissionTarget.JUPITER, r.target)
        assertTrue("flew past Mars: ${r.reason}", r.marsReached && !r.marsLanded)
        assertTrue("reached Jupiter: ${r.reason}", r.jupiterReached)
        assertTrue("landed on Europa: ${r.reason}", r.europaLanded && r.jupiterOrbit)
        assertTrue("nav computer cut the engines", navCutSeen)
        assertTrue("Mars flyby is slowed down to be visible ($slowestNearMars)", slowestNearMars < 50_000)
        for (b in listOf("MARS FLYBY", "ENTERING THE ASTEROID BELT", "Asteroid Belt  +", "Rock Hopper", "Jupiter Arrival  +", "TOUCHDOWN ON EUROPA"))
            assertTrue("banner '$b' shown", banners.any { it.contains(b) })
        assertTrue("Jupiter trip should take 40 s .. 4.5 min of real time, took ${frames / 60}s", frames in 2_400..16_200)
        assertTrue("no single frame jumps more than 3% of the way", maxFrameAltJump < 0.03)
        val s = e.save.value
        assertEquals(1, s.jupiterOrbits); assertEquals(1, s.europaLandings); assertEquals(1, s.marsLandings)
        assertTrue(Milestone.JUPITER in s.milestones && Milestone.ASTEROID_BELT in s.milestones)
        assertEquals(Mission.EUROPA_LANDING.ordinal + 1, Mission.doneCount(s))
        // 1.3: Europa opens the Saturn chapter
        assertEquals(Mission.JUPITER_SLINGSHOT, Mission.next(s))
        assertEquals(MissionTarget.SATURN, MissionTarget.of(s))
        val st = Economy.settle(0, r.maxAlt, start.milestones)
        assertEquals(st.total, r.total)
        // reload keeps it all
        assertEquals(s, GameEngine(h.store).save.value)
    }

    @Test fun maxedRocketSlingshotsPastJupiterAndFliesOnTitan() {
        val all = TechTree.nodes.map { it.id }.toSet()
        // a 1.2-format (schema 4) endgame save that has landed on Europa, now with the whole 1.3 tree: on to Saturn
        val start = SaveData(money = 0, tech = all, moonLandings = 1, marsOrbits = 1, marsLandings = 1, jupiterOrbits = 1, europaLandings = 1,
            milestones = Milestone.entries.filter { it.altitude <= Physics.JUPITER_ARRIVAL_ALT }.toSet())
        val v12 = start.serialize().replace("v=5;", "v=4;").replace(";rings=0;satOrbit=0;encel=0;titanProbe=0;titan=0", "")
        val h = Harness(v12)
        val e = h.engine
        assertEquals(start, e.save.value)
        e.openHangar(); h.frame(true); e.launch()
        assertEquals(MissionTarget.SATURN, e.target)
        var frames = 0
        var maxFrameAltJump = 0.0
        var lastAlt = 0.0
        val banners = ArrayList<String>()
        var lastBanner = ""
        var navCutSeen = false
        var slowestNearJupiter = Double.MAX_VALUE
        var iceSeen = false
        while (e.phase != Phase.RESULTS && frames < 40_000) {
            h.frame(true); frames++
            if (e.hud.value.navCut) navCutSeen = true
            val hb = e.hud.value.banner
            if (hb != lastBanner) { banners += hb; lastBanner = hb }
            val a = e.flight?.alt ?: 0.0
            if (a > 1e9) maxFrameAltJump = maxOf(maxFrameAltJump, (a - lastAlt) / Physics.SATURN_ARRIVAL_ALT)
            if (kotlin.math.abs(a - Physics.JUPITER_ARRIVAL_ALT) < 2e9) slowestNearJupiter = minOf(slowestNearJupiter, e.warp)
            if (e.world.entities.any { it.kind == com.brandonwallace.rocketlauncher.game.EntityKind.ICE }) iceSeen = true
            lastAlt = a
        }
        val r = e.hud.value.results!!
        println("Saturn flight: frames=$frames (~${frames / 60}s) reason=${r.reason} level=${r.saturnLevel} sling=${r.slingshot} pay=${r.total} hits=${r.hits} maxStep=${"%.4f".format(maxFrameAltJump)} warpNearJupiter=$slowestNearJupiter")
        println("Saturn banners: " + banners.joinToString(" | "))
        assertEquals(MissionTarget.SATURN, r.target)
        assertTrue("flew past Mars and Jupiter: ${r.reason}", r.marsReached && r.jupiterReached && !r.europaLanded)
        assertTrue("reached Saturn: ${r.reason}", r.saturnReached)
        assertTrue("flew on Titan: ${r.reason}", r.titanFlight && r.titanProbe && r.enceladus && r.saturnOrbit && r.ringsCrossed)
        assertTrue("slingshot", r.slingshot)
        assertTrue("nav computer cut the engines", navCutSeen)
        assertTrue("Jupiter flyby is slowed down to be visible ($slowestNearJupiter)", slowestNearJupiter < 50_000)
        assertTrue("ring ice on the way in", iceSeen)
        for (b in listOf("MARS FLYBY", "JUPITER SLINGSHOT", "Jupiter Flyby  +", "Halfway to Saturn  +", "Billion-Kilometer Club  +", "SATURN'S RINGS",
                "Saturn Arrival  +", "Slingshot", "DRAGONFLY FLIES ON TITAN"))
            assertTrue("banner '$b' shown", banners.any { it.contains(b) })
        assertTrue("Saturn trip should take about 5 minutes (3.5-7 min) of real time, took ${frames / 60}s", frames in 12_600..25_200)
        assertTrue("no single frame jumps more than 3% of the way", maxFrameAltJump < 0.03)
        val s = e.save.value
        assertEquals(1, s.ringCrossings); assertEquals(1, s.saturnOrbits); assertEquals(1, s.enceladusPasses); assertEquals(1, s.titanProbes); assertEquals(1, s.titanFlights)
        assertTrue(Milestone.SATURN in s.milestones && Milestone.TITAN_ORBIT in s.milestones && Milestone.LIGHT_HOUR in s.milestones)
        assertEquals("the whole mission is done", Mission.entries.size, Mission.doneCount(s))
        assertTrue(com.brandonwallace.rocketlauncher.game.Achievement.GRAND_TOUR in s.achievements && com.brandonwallace.rocketlauncher.game.Achievement.TITAN_FLYER in s.achievements)
        assertTrue(com.brandonwallace.rocketlauncher.game.Achievement.GRAND_TOUR in r.achievements)
        val st = Economy.settle(0, r.maxAlt, start.milestones)
        assertEquals(st.total, r.total)
        assertEquals(s, GameEngine(h.store).save.value)
    }

    @Test fun upgradesChangeTheExhaustAndTheRocket() {
        // walk the propulsion line: every engine family has its own exhaust look
        val path = listOf(emptySet(), setOf("P_GRAIN"), setOf("P_GRAIN", "P_LIQUID"), setOf("P_GRAIN", "P_LIQUID", "P_TURBO", "P_GG"),
            setOf("P_GRAIN", "P_LIQUID", "P_TURBO", "P_GG", "P_STAGED"), setOf("P_GRAIN", "P_LIQUID", "P_TURBO", "P_GG", "P_STAGED", "P_FFSC"),
            setOf("P_GRAIN", "P_LIQUID", "P_TURBO", "P_GG", "P_STAGED", "P_FFSC", "P_METHALOX"))
        val names = path.map { com.brandonwallace.rocketlauncher.gl.Exhaust.firstStage(RocketSpec(it)).name }
        println("first-stage exhaust along the engine line: $names")
        assertEquals("every engine family looks different", names.size, names.toSet().size)
        val kero = com.brandonwallace.rocketlauncher.gl.Exhaust.firstStage(RocketSpec(path[2]))
        val metha = com.brandonwallace.rocketlauncher.gl.Exhaust.firstStage(RocketSpec(path[6]))
        assertTrue("methalox burns blue", metha.core[2] > metha.core[0] && kero.core[0] > kero.core[2])
        assertTrue("methalox is almost smokeless", metha.smoke < kero.smoke / 2)
        assertTrue("gas-generator soot trail", com.brandonwallace.rocketlauncher.gl.Exhaust.firstStage(RocketSpec(path[3])).soot > 0f)
        assertTrue("…gone with staged combustion", com.brandonwallace.rocketlauncher.gl.Exhaust.firstStage(RocketSpec(path[4])).soot == 0f)
        val all = TechTree.nodes.filter { it.tier < TechTree.SATURN_TIER }.map { it.id }.toSet()   // the 1.2 tree
        val up = listOf(emptySet(), setOf("P_NTR"), setOf("P_NTR", "P_NTR2"), setOf("P_NTR", "P_NTR2", "P_PLASMA"), setOf("P_NTR", "P_NTR2", "P_PLASMA", "P_PLASMA2"))
            .map { com.brandonwallace.rocketlauncher.gl.Exhaust.upperStage(RocketSpec(all.filter { id -> !id.startsWith("P_NTR") && !id.startsWith("P_PLASMA") }.toSet() + it)) }
        println("upper-stage exhaust: ${up.map { it.name }}")
        assertEquals(up.size, up.map { it.name }.toSet().size)
        assertTrue("plasma is violet and longer", up[3].core[2] > up[3].core[1] * 2 && up[3].length > up[0].length)
        assertTrue(up[3].striations)
        // the model grows the new hardware
        val maxed = RocketModel(RocketSpec(all), 24)
        assertEquals(33, maxed.nozzles[0].size)
        assertEquals("three hinged solar wings", 3, maxed.parts.count { it.hinge && it.group == RocketModel.ARRAYS })
        assertTrue("gold foil", maxed.parts.any { it.mat == com.brandonwallace.rocketlauncher.gl.MatType.FOIL })
        assertTrue("glowing parts (AI dome, laser, ion, graphene)", maxed.parts.count { it.emissive != null } >= 4)
        assertEquals(4, maxed.boosterOffsets.size)
        assertEquals(6, maxed.stageBase.size)
        val twenty = RocketModel(RocketSpec(all - "P_SUPER33"), 24)
        assertEquals(20, twenty.nozzles[0].size)
        val gg = RocketModel(RocketSpec(path[3]), 24)
        assertTrue(gg.ggExhaust != null)
        // more parts the further up the tree you go
        val counts = listOf(emptySet(), TechTree.nodes.filter { it.tier <= 4 }.map { it.id }.toSet(), TechTree.nodes.filter { it.tier < TechTree.JUPITER_TIER }.map { it.id }.toSet(), all,
            TechTree.nodes.map { it.id }.toSet())
            .map { RocketModel(RocketSpec(it), 24).parts.size }
        println("rocket part counts along the tree: $counts")
        assertTrue(counts.zipWithNext().all { (x, y) -> y > x })
    }

    @Test fun everySaturnUpgradeChangesTheRocket() {
        fun sig(m: RocketModel) = m.parts.joinToString(";") {
            "${it.group},${it.mat},${it.mesh.vertexCount},${it.mesh.count},${Math.round(it.mesh.checksum * 100)},${it.roughMul},${it.panel.toList()},${it.emissive?.toList()},${it.engine}"
        } + "|${m.totalLength}|${m.nozzles.map { it.size }}|${m.boosterOffsets.size}"
        val owned = LinkedHashSet(TechTree.nodes.filter { !it.saturnProgram }.map { it.id })
        var prev = sig(RocketModel(RocketSpec(owned), 24))
        val unchanged = ArrayList<String>()
        for (n in TechTree.nodes.filter { it.saturnProgram }.sortedBy { it.tier * 100 + it.row }) {
            owned += n.id   // tier order keeps every prerequisite owned
            val now = sig(RocketModel(RocketSpec(owned), 24))
            if (now == prev) unchanged += n.id
            prev = now
        }
        assertTrue("every Saturn upgrade changes the rocket's look, these did not: $unchanged", unchanged.isEmpty())
        // the full 1.3 rocket: 60 detonation engines round an aerospike, 8 stages, 6 side cores, deployables, new hulls
        val all = TechTree.nodes.map { it.id }.toSet()
        val maxed = RocketModel(RocketSpec(all), 24)
        assertEquals(60, maxed.nozzles[0].size)
        assertEquals(8, maxed.stageBase.size)
        assertEquals(6, maxed.boosterOffsets.size)
        assertTrue("sails/tethers/loop open in space", maxed.parts.count { it.group == RocketModel.SPREAD } >= 3)
        assertTrue("nanotube hull", maxed.parts.any { it.mat == com.brandonwallace.rocketlauncher.gl.MatType.NANO })
        assertTrue("lots of glowing hardware", maxed.parts.count { it.emissive != null } >= 15)
        assertTrue(maxed.totalLength < 120f)
        // the Saturn exhaust families all look different
        val jupEra = TechTree.nodes.filter { it.tier < TechTree.SATURN_TIER }.map { it.id }.toSet()
        val firsts = listOf(setOf("P_MK4"), setOf("P_MK4", "P_TRIPROP"), setOf("P_MK4", "P_TRIPROP", "P_RDE"), setOf("P_MK4", "P_TRIPROP", "P_RDE", "P_RDE42"),
            setOf("P_MK4", "P_TRIPROP", "P_RDE", "P_RDE42", "P_SPIKE"), setOf("P_MK4", "P_TRIPROP", "P_RDE", "P_RDE42", "P_SPIKE", "P_RDE60"),
            setOf("P_MK4", "P_TRIPROP", "P_RDE", "P_RDE42", "P_SPIKE", "P_RDE60", "P_BEAMED")).map { com.brandonwallace.rocketlauncher.gl.Exhaust.firstStage(RocketSpec(jupEra + it)) }
        println("Saturn first-stage exhaust: ${firsts.map { it.name }}")
        assertEquals(firsts.size, firsts.map { it.name }.toSet().size)
        assertTrue("the aerospike stays tight in vacuum", firsts[4].vacSpread < 0.5f && firsts[2].vacSpread == 1f)
        assertTrue("detonation rings show in vacuum", firsts[2].striations)
        val uppers = listOf(setOf("P_DFD"), setOf("P_DFD", "P_DFD2"), setOf("P_DFD", "P_DFD2", "P_AMAT"), setOf("P_DFD", "P_DFD2", "P_AMAT", "P_AMAT2"))
            .map { com.brandonwallace.rocketlauncher.gl.Exhaust.upperStage(RocketSpec(jupEra + it)) }
        println("Saturn upper-stage exhaust: ${uppers.map { it.name }}")
        assertEquals(uppers.size, uppers.map { it.name }.toSet().size)
        assertTrue("the antimatter beam is the longest, narrowest plume", uppers[3].length > uppers[0].length && uppers[3].width < uppers[0].width)
    }

    @Test fun muteButtonTogglesAndPersists() {
        val h = Harness(SaveData(money = 10).serialize())
        val e = h.engine
        assertTrue(e.save.value.soundOn)
        val heard = ArrayList<com.brandonwallace.rocketlauncher.game.Sfx>()
        e.sfxListener = { heard += it }
        e.toggleSound()
        assertFalse(e.save.value.soundOn)
        assertFalse(e.hud.value.soundOn)
        assertTrue(h.store.data!!.contains(";sound=false;"))
        e.openHangar()
        assertTrue("muted: no click", heard.isEmpty())
        e.launch(); repeat(600) { h.frame(true) }
        assertTrue("muted: no countdown beeps or liftoff", heard.isEmpty())
        e.toggleSound()
        assertTrue(e.save.value.soundOn && e.hud.value.soundOn)
        assertTrue(GameEngine(h.store).save.value.soundOn)
    }

    @Test fun screenshotSavesStillFlyToTheirTargets() {
        // store-listing/screenshot-saves are loaded on the emulator for Play Store screenshots (SCREENSHOTS.md)
        fun load(name: String): SaveData {
            val xml = java.io.File("../store-listing/screenshot-saves/$name.xml").readText()
            return SaveData.parse(xml.substringAfter("<string name=\"save\">").substringBefore("</string>"))
        }
        fun fly(s: SaveData) = com.brandonwallace.rocketlauncher.game.simulateAutopilot(RocketSpec(s.tech), MissionTarget.of(s))
        assertTrue(load("early").tech.isNotEmpty())
        val mars = fly(load("mars")); assertTrue("mars.xml lands on Mars", mars.marsLanded)
        val jup = fly(load("jupiter")); assertTrue("jupiter.xml lands on Europa", jup.europaLanded)
        val sat = load("saturn")
        assertEquals(TechTree.nodes.size, sat.tech.size)
        val r = fly(sat); assertTrue("saturn.xml flies on Titan", r.titanFlight)
        assertEquals(8, RocketSpec(sat.tech).stages.size)
    }

    private fun screenshotSave(name: String): SaveData {
        val xml = java.io.File("../store-listing/screenshot-saves/$name.xml").readText()
        return SaveData.parse(xml.substringAfter("<string name=\"save\">").substringBefore("</string>"))
    }

    @Test fun hangarForecastIsExactlyTheLaunch() {
        // the forecast flies the coming launch in a throwaway engine: same rocket, same flight number, same hazards
        for (s in listOf(SaveData(money = 500), screenshotSave("early"), screenshotSave("jupiter"), screenshotSave("saturn"))) {
            val fc = forecastFlight(s)!!
            val h = Harness(s.serialize())
            assertTrue(fly(h, false))
            val r = h.engine.hud.value.results!!
            assertEquals(r.reason, fc.reason)
            assertEquals(r.maxAlt, fc.maxAlt, 0.0)
            assertEquals(r.hits, fc.hits)
            assertEquals(r.saturnLevel, fc.saturnLevel)
            assertEquals(r.total, fc.total)
            assertEquals(s, GameEngine(MemStore().also { it.data = s.serialize() }).save.value)   // the forecast never touches the real save
        }
    }

    @Test fun decidedFlightIsBankedWhenTheAppGoesToTheBackground() {
        // arrival decided, end animation running, then Home: the result must be on disk before the process can die
        val h = Harness(screenshotSave("saturn").serialize())
        val e = h.engine
        e.openHangar(); h.frame(false)
        e.launch()
        var frames = 0
        while (!e.hud.value.ending && frames < 40_000) { h.frame(false); frames++ }
        assertTrue("reached the end animation", e.hud.value.ending && e.phase == Phase.FLIGHT)
        e.setPaused(true)
        e.persistNow()
        assertEquals(Phase.RESULTS, e.phase)
        assertFalse("results are not hidden behind the pause menu", e.hud.value.paused)
        val reloaded = GameEngine(h.store).save.value
        assertEquals(121, reloaded.flights)
        assertEquals(1, reloaded.titanFlights)
        assertTrue(com.brandonwallace.rocketlauncher.game.Achievement.LONG_HAUL in reloaded.achievements)   // a Saturn flight lasts over a year
        assertEquals(1, h.store.syncWrites)
    }

    @Test fun missionPercentOnlyReads100OnArrival() {
        val pct = ::missionPercent
        val frac = ::missionFraction
        // Saturn: the log scale up to Jupiter, then real distance (the chapter is no longer squeezed into 97-100%)
        assertEquals(49, pct(Physics.JUPITER_ARRIVAL_ALT, MissionTarget.SATURN, false))
        assertEquals(51, pct(6.15e11, MissionTarget.SATURN, false))
        assertEquals(99, pct(1.199e12, MissionTarget.SATURN, false))
        assertEquals(100, pct(Physics.SATURN_ARRIVAL_ALT, MissionTarget.SATURN, true))
        assertEquals(99, pct(5.85e11, MissionTarget.JUPITER, false))   // was rounded up to a false 100%
        // Mars and Jupiter keep the 1.2 log scale
        assertEquals((kotlin.math.ln(1.0 + 384_400.0) / kotlin.math.ln(1.0 + Physics.MARS_ARRIVAL_ALT / 1000)).toFloat(), frac(3.844e8, MissionTarget.MARS), 1e-6f)
        var last = -1f
        for (i in 0..400) {   // monotonic everywhere, and no jump where the scale switches at Jupiter
            val f = frac(Physics.SATURN_ARRIVAL_ALT * i / 400, MissionTarget.SATURN)
            assertTrue(f >= last)
            if (i >= 100) assertTrue(f - last < 0.01f)
            last = f
        }
    }

    @Test fun moneySaturatesAndLongHaulIsASaturnBadge() {
        // long after the tree is finished the hidden bonus keeps compounding: the balance must cap, not wrap negative
        val rich = screenshotSave("saturn").copy(money = Long.MAX_VALUE - 1_000, totalEarned = Long.MAX_VALUE - 1_000)
        val h = Harness(rich.serialize())
        assertTrue(fly(h, false))
        assertEquals(Long.MAX_VALUE, h.engine.hud.value.results!!.balanceAfter)
        val reloaded = GameEngine(h.store).save.value
        assertEquals(Long.MAX_VALUE, reloaded.money)
        assertEquals(Long.MAX_VALUE, reloaded.totalEarned)
        // Jupiter-chapter flights also last over a sim-year, but Long Haul belongs to the Saturn chapter
        val jup = forecastFlight(screenshotSave("jupiter"))!!
        assertTrue(jup.time > 365.25 * 86_400)
        assertFalse(com.brandonwallace.rocketlauncher.game.Achievement.LONG_HAUL in jup.achievements)
    }
}
