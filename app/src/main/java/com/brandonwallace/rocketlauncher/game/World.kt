package com.brandonwallace.rocketlauncher.game

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Obstacles only — v2 has no collectibles. */
enum class EntityKind(val radius: Float, val damage: Double) {
    BIRD(0.9f, 12.0),
    PLANE(3.6f, 38.0),
    BALLOON(2.0f, 18.0),
    SATELLITE(2.8f, 42.0),
    DEBRIS(1.5f, 26.0),
    METEOR(2.2f, 48.0),
}

class Entity(val kind: EntityKind, var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, val seed: Float) {
    var rot = 0f
    var spin = 0f
    var age = 0f
    var alive = true
    var nearMissed = false
    // previous fixed-step state for render interpolation
    var px = x; var py = y; var prot = 0f
}

class Cloud(var x: Float, var y: Float, val z: Float, val size: Float, val seed: Float) {
    var py = y
    var age = 0f
}

sealed class WorldEvent {
    data class Hit(val kind: EntityKind, val damage: Double, val x: Float, val y: Float) : WorldEvent()
    data class NearMiss(val kind: EntityKind) : WorldEvent()
}

/**
 * The gameplay plane around the rocket. Positions are in "visual metres" relative to the rocket base
 * (a floating origin that rides with the rocket); everything scrolls at a compressed visual speed.
 */
class World(seed: Int = 1) {
    val entities = ArrayList<Entity>()
    val clouds = ArrayList<Cloud>()
    var rng = Random(seed)
    private var spawnAcc = 0f
    private var cloudAcc = 0f

    companion object {
        const val LANE = 10f
        const val SPAWN_Y = 110f
        const val DESPAWN_Y = -110f
        /** Altitude of the cloud deck that the sky shader also draws. */
        const val DECK_ALT = 7_000.0

        fun cloudDensity(alt: Double): Float = when {
            alt < 900 || alt > 11_000 -> 0f
            alt < 2_200 -> ((alt - 900) / 1300).toFloat() * 0.6f
            alt < 5_500 -> 0.6f
            alt < 8_500 -> 1.6f    // flying through the deck
            else -> (1 - (alt - 8_500) / 2_500).toFloat() * 0.5f
        }

        fun spawnTable(alt: Double): List<Pair<EntityKind, Float>> = when {
            alt < 2_500 -> listOf(EntityKind.BIRD to 1f)
            alt < 12_000 -> listOf(EntityKind.PLANE to 1.6f, EntityKind.BIRD to (if (alt < 4_000) 1f else 0f))
            alt < 50_000 -> listOf(EntityKind.BALLOON to 1f)
            alt < 100_000 -> listOf(EntityKind.METEOR to 1f)
            alt < 2_000_000 -> listOf(EntityKind.SATELLITE to 1.6f, EntityKind.DEBRIS to 2.2f)
            else -> listOf(EntityKind.DEBRIS to 0.8f, EntityKind.METEOR to 1.6f)
        }

        /** Hazards per second at a given altitude (obstacles only — fewer than v1's pickups+hazards). */
        fun spawnRate(alt: Double): Float = when {
            alt < 2_500 -> 0.35f
            alt < 12_000 -> 0.55f
            alt < 100_000 -> 0.5f
            else -> 0.7f
        }
    }

    fun reset(seed: Int) {
        entities.clear(); clouds.clear(); rng = Random(seed); spawnAcc = 0f; cloudAcc = 0f
    }

    private fun rf(a: Float, b: Float) = a + rng.nextFloat() * (b - a)

    /** Copy current positions into prev* (call once at the start of every fixed step). */
    fun snapshot() {
        for (e in entities) { e.px = e.x; e.py = e.y; e.prot = e.rot }
        for (c in clouds) c.py = c.y
    }

    fun update(
        dt: Float, scroll: Float, alt: Double, rocketX: Float, rocketLen: Float, rocketRadius: Float,
        active: Boolean, out: MutableList<WorldEvent>,
    ) {
        for (e in entities) {
            e.age += dt
            e.x += e.vx * dt
            e.y += (e.vy - scroll) * dt
            e.rot += e.spin * dt
            if (e.kind == EntityKind.BALLOON) e.x += sin(e.age * 0.8f + e.seed * 6f) * 0.6f * dt
            val cy = e.y.coerceIn(0f, rocketLen)
            val dx = e.x - rocketX
            val dy = e.y - cy
            val d2 = dx * dx + dy * dy
            val rr = rocketRadius + e.kind.radius
            if (active && d2 < rr * rr) {
                e.alive = false
                out.add(WorldEvent.Hit(e.kind, e.kind.damage, e.x, e.y))
            } else if (active && !e.nearMissed && abs(dy) < 0.5f && abs(dx) < rr + 2.5f) {
                e.nearMissed = true
                out.add(WorldEvent.NearMiss(e.kind))
            }
            if (e.y < DESPAWN_Y || e.y > SPAWN_Y + 160f || e.x > 150f || e.x < -150f) e.alive = false
        }
        entities.removeAll { !it.alive }

        if (active && alt > 150 && scroll > 2f) {
            spawnAcc += dt * spawnRate(alt)
            while (spawnAcc >= 1f) { spawnAcc -= 1f; spawnOne(alt, rocketX) }
        }

        // clouds: spawn beyond the fog/fade band and fade in by age, so they never pop
        val cd = cloudDensity(alt)
        if (cd > 0f && scroll > 1f) {
            cloudAcc += dt * cd * (1.2f + scroll / 50f)
            while (cloudAcc >= 1f) {
                cloudAcc -= 1f
                // some clouds spawn between camera and rocket (z > 0) so you fly *through* them
                val z = if (rng.nextFloat() < 0.3f) rf(5f, 40f) else rf(-190f, 0f)
                clouds.add(Cloud(rocketX + rf(-90f, 90f), SPAWN_Y + rf(20f, 90f), z, rf(18f, 42f), rng.nextFloat()))
            }
        }
        for (c in clouds) { c.y -= scroll * dt; c.age += dt }
        clouds.removeAll { it.y < DESPAWN_Y - 90f || it.y > SPAWN_Y + 260f }
        if (clouds.size > 140) clouds.subList(0, clouds.size - 140).clear()
    }

    /** Incoming hazards ahead of the rocket, as lane positions in [-1, 1] (for the avionics radar). */
    fun incoming(maxCount: Int = 4): List<Float> =
        entities.asSequence().filter { it.y in 25f..SPAWN_Y && abs(it.x) < 60f }
            .sortedBy { it.y }.take(maxCount).map { (it.x / 30f).coerceIn(-1f, 1f) }.toList()

    private fun pick(alt: Double): EntityKind {
        val table = spawnTable(alt).filter { it.second > 0f }
        var r = rng.nextFloat() * table.sumOf { it.second.toDouble() }.toFloat()
        for ((k, w) in table) { r -= w; if (r <= 0f) return k }
        return table.first().first
    }

    private fun spawnOne(alt: Double, rocketX: Float) {
        val kind = pick(alt)
        val y = SPAWN_Y + rf(0f, 25f)
        val x = rf(-LANE, LANE)
        val seed = rng.nextFloat()
        when (kind) {
            EntityKind.BIRD -> {
                val dir = if (rng.nextBoolean()) 1f else -1f
                val n = 2 + rng.nextInt(4)
                for (i in 0 until n) entities.add(Entity(kind, x - dir * i * 2.2f, y - i * 1.6f + rf(-0.5f, 0.5f), rf(-1f, 1f), dir * rf(4f, 8f), 0f, rng.nextFloat()))
            }
            EntityKind.PLANE -> {
                val side = if (rng.nextBoolean()) 1f else -1f
                entities.add(Entity(kind, side * 55f, y - 15f, 0f, -side * rf(30f, 42f), 0f, seed))
            }
            EntityKind.BALLOON -> entities.add(Entity(kind, x, y, 0f, 0f, 2.5f, seed))
            EntityKind.SATELLITE -> entities.add(Entity(kind, x, y, 0f, rf(-3f, 3f), 0f, seed).also { it.spin = rf(-0.6f, 0.6f) })
            EntityKind.DEBRIS -> entities.add(Entity(kind, x, y, 0f, rf(-9f, 9f), 0f, seed).also { it.spin = rf(-4f, 4f) })
            EntityKind.METEOR -> {
                val side = if (rng.nextBoolean()) 1f else -1f
                entities.add(Entity(kind, rocketX + side * 28f, y + 10f, 0f, -side * 13f, -22f, seed).also { it.spin = rf(-2f, 2f) })
            }
        }
    }
}
