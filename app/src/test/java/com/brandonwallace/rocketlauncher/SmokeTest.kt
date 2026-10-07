package com.brandonwallace.rocketlauncher

import com.brandonwallace.rocketlauncher.audio.SoundEngine
import com.brandonwallace.rocketlauncher.game.Destination
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
    private class MemStore : SaveStore { var data: String? = null; override fun load() = data; override fun save(data: String) { this.data = data } }

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

    /** Fly one launch with full throttle (simple dodge steering). Returns the result phase reached. */
    private fun fly(h: Harness, render: Boolean, maxFrames: Int = 40_000): Boolean {
        val e = h.engine
        e.openHangar(); h.frame(render)
        e.launch()
        var frames = 0
        while (e.phase != Phase.RESULTS && frames < maxFrames) {
            // like a player: burn until Moon-bound, dodge only hazards on a collision course, drift back to centre
            e.inputThrottle = !e.hud.value.moonBound
            val threat = e.world.entities.filter { it.y in 4f..60f && kotlin.math.abs(it.x - e.laneX) < it.kind.radius + 3f }.minByOrNull { it.y }
            e.inputSteer = if (threat != null) (if (threat.x > e.laneX) -1f else 1f) else (-e.laneX / 25f).coerceIn(-0.3f, 0.3f)
            h.frame(render); frames++
        }
        e.inputThrottle = false
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
            val render = flights % 6 == 0 || flights > 24
            assertTrue("flight $flights finished", fly(h, render))
            flights++
            val r = e.hud.value.results ?: error("no results after flight $flights")
            val after = e.save.value
            println("flight $flights: alt=${r.maxAlt.toInt()} t=${r.time.toInt()} reason=${r.reason} hits=${r.hits} total=${r.total} techs=${after.tech.size}")
            // visible lines add up exactly to the total; the hidden interest is inside "Flight performance"
            assertEquals(r.total, r.performancePay + r.milestones.sumOf { it.bonus })
            assertEquals(Economy.altitudePay(r.maxAlt) + Economy.interest(balanceAtLaunch), r.performancePay)
            assertEquals(balanceAtLaunch + r.total, after.money)
            assertEquals(after.money, r.balanceAfter)
            assertTrue(after.money >= 0)
            lastMoney = after.money
            if (r.moonReached && moonFlight < 0) moonFlight = flights
            if (r.moonReached) break
            // research: cheapest affordable available node first (renderer rebuilds the rocket each time)
            while (true) {
                val n = TechTree.available(e.save.value.tech).filter { it.cost <= e.save.value.money }.minByOrNull { it.cost } ?: break
                assertTrue(e.research(n.id))
                h.frame(true)
            }
            e.openHangar(); repeat(3) { h.frame(true) }
            e.openTech(); h.frame(true)
        }
        println("SmokeTest campaign: Moon reached on flight $moonFlight with ${e.save.value.tech.size} techs, balance $lastMoney")
        assertTrue("reached the Moon through the real engine (flights=$flights)", moonFlight in 20..50)

        // save reload: a fresh engine on the same store sees identical progress
        val reloaded = GameEngine(h.store)
        assertEquals(e.save.value, reloaded.save.value)
        assertEquals(SaveData.parse(e.save.value.serialize()), e.save.value)
    }

    @Test fun maxedRocketLandsOnMoonWithRendererRunning() {
        val h = Harness()
        val e = h.engine
        e.grantMoney(TechTree.totalCost + 10)
        while (true) { val n = TechTree.available(e.save.value.tech).firstOrNull() ?: break; assertTrue(n.id, e.research(n.id)) }
        assertEquals(TechTree.nodes.size, e.save.value.tech.size)
        for (q in listOf(0, 2, 1)) { e.setQuality(q); h.frame(true) }
        // fly with throttle released once Moon-bound (to keep landing fuel)
        e.openHangar(); h.frame(true); e.launch()
        var frames = 0
        var boundAt = -1.0
        while (e.phase != Phase.RESULTS && frames < 60_000) {
            if (e.hud.value.moonBound && boundAt < 0) boundAt = e.flight!!.alt
            e.inputThrottle = !e.hud.value.moonBound
            val threat = e.world.entities.filter { it.y in 4f..60f && kotlin.math.abs(it.x - e.laneX) < it.kind.radius + 3f }.minByOrNull { it.y }
            e.inputSteer = if (threat != null) (if (threat.x > e.laneX) -1f else 1f) else (-e.laneX / 25f).coerceIn(-0.3f, 0.3f)
            h.frame(true); frames++
        }
        val r = e.hud.value.results!!
        val fl = e.flight!!
        println("Maxed arrival: stage=${fl.stage} fuelFrac=${fl.remainingFuel() / e.spec.stages.last().fuelMass} boundSeenAt=$boundAt")
        println("Maxed flight: reached=${r.moonReached} landed=${r.moonLanded} hits=${r.hits} frames=$frames reason=${r.reason}")
        assertTrue("maxed rocket reaches the Moon: ${r.reason}", r.moonReached)
    }

    @Test fun maxedRocketLandsOnMarsWithRendererRunning() {
        val all = TechTree.nodes.map { it.id }.toSet()
        val start = SaveData(money = 0, tech = all, moonLandings = 1, destination = Destination.MARS,
            milestones = Milestone.entries.filter { it.altitude <= Physics.MOON_ARRIVAL_ALT }.toSet())
        val h = Harness(start.serialize())
        val e = h.engine
        assertEquals(Destination.MARS, e.save.value.effectiveDestination)
        e.openHangar(); h.frame(true); e.launch()
        var frames = 0
        var maxFrameAltJump = 0.0
        var lastAlt = 0.0
        val zonesSeen = ArrayList<String>()
        var lastBanner = ""
        while (e.phase != Phase.RESULTS && frames < 120_000) {
            e.inputThrottle = !e.hud.value.moonBound
            val threat = e.world.entities.filter { it.y in 4f..60f && kotlin.math.abs(it.x - e.laneX) < it.kind.radius + 3f }.minByOrNull { it.y }
            e.inputSteer = if (threat != null) (if (threat.x > e.laneX) -1f else 1f) else (-e.laneX / 25f).coerceIn(-0.3f, 0.3f)
            h.frame(true); frames++
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
        assertTrue("Mars trip should take 40 s .. 4 min of real time, took ${frames / 60}s", frames in 2_400..14_400)
        assertTrue("no single frame jumps more than 3% of the way", maxFrameAltJump < 0.03)
        assertEquals(1, e.save.value.marsLandings)
        assertTrue(Milestone.MARS in e.save.value.milestones)
        assertEquals(Mission.entries.size, Mission.doneCount(e.save.value))
        // money only from height: the payout is exactly altitude pay + new height milestones (+ hidden interest on $0 = 0)
        val st = Economy.settle(0, r.maxAlt, start.milestones)
        assertEquals(st.total, r.total)
    }

    @Test fun marsLockedUntilMoonLanding() {
        val h = Harness(SaveData(destination = Destination.MARS).serialize())
        val e = h.engine
        assertEquals(Destination.MOON, e.save.value.effectiveDestination)
        e.setDestination(Destination.MARS)
        assertEquals(Destination.MOON, e.save.value.effectiveDestination)
        e.openHangar(); e.launch(); h.frame(true)
        assertEquals(Destination.MOON, e.destination)
    }

    @Test fun abortPauseAndResume() {
        val h = Harness()
        val e = h.engine
        e.openHangar(); e.launch()
        repeat(400) { e.inputThrottle = true; h.frame(true) }
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
}
