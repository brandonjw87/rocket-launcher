package com.brandonwallace.rocketlauncher.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.brandonwallace.rocketlauncher.game.Sfx
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Tiny real-time synthesizer: engine rumble (filtered noise) + one-shot effects, all generated in code. */
class SoundEngine {
    @Volatile var engineLevel = 0f
    @Volatile var atmosphere = 1f
    @Volatile var enabled = true
    private val queue = ConcurrentLinkedQueue<Sfx>()
    @Volatile private var running = false
    private var thread: Thread? = null

    private class Voice(val type: Sfx) { var t = 0.0 }

    fun play(s: Sfx) { if (enabled) queue.add(s) }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "RocketSynth").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val rate = 22050
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) { running = false; return }
        track.play()
        val buf = ShortArray(512)
        val voices = ArrayList<Voice>()
        val rnd = Random(1)
        var brown = 0.0; var lp1 = 0.0; var lp2 = 0.0; var level = 0.0; var crackle = 0.0
        val dt = 1.0 / rate
        while (running) {
            while (true) { val s = queue.poll() ?: break; if (voices.size < 16) voices.add(Voice(s)) }
            val targetLevel: Double = if (enabled) engineLevel * (0.2 + 0.8 * atmosphere) else 0.0
            for (i in buf.indices) {
                level += (targetLevel - level) * 0.0008
                val white = rnd.nextDouble() * 2 - 1
                brown = (brown + white * 0.08) * 0.996
                lp1 += (brown - lp1) * 0.08
                lp2 += (white - lp2) * 0.25
                if (rnd.nextDouble() < 0.0015 * level) crackle = 1.0
                crackle *= 0.985
                var s = (lp1 * 2.2 + lp2 * 0.18 + crackle * white * 0.5) * level
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val t = v.t
                    val (sample, dur) = synth(v.type, t, rnd)
                    s += sample
                    v.t += dt
                    if (v.t > dur) it.remove()
                }
                buf[i] = (s.coerceIn(-1.0, 1.0) * 26000).toInt().toShort()
            }
            track.write(buf, 0, buf.size)
        }
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }

    private fun tone(f: Double, t: Double) = sin(2 * PI * f * t)

    private fun synth(type: Sfx, t: Double, rnd: Random): Pair<Double, Double> = when (type) {
        Sfx.RESEARCH -> (tone(if (t < 0.07) 659.25 else 987.77, t) * exp(-t * 7) * 0.3 + tone(1975.5, t) * exp(-t * 20) * 0.08) to 0.5
        Sfx.MILESTONE -> {
            val notes = doubleArrayOf(659.25, 880.0, 1318.5)
            val k = (t / 0.08).toInt().coerceAtMost(2)
            (tone(notes[k], t) * exp(-(t - k * 0.08) * 6) * 0.26) to 0.55
        }
        Sfx.WHOOSH -> ((rnd.nextDouble() * 2 - 1) * sin(PI * (t / 0.5).coerceIn(0.0, 1.0)) * 0.25) to 0.5
        Sfx.HIT -> ((rnd.nextDouble() * 2 - 1) * exp(-t * 14) * 0.6 + tone(85.0, t) * exp(-t * 9) * 0.6) to 0.45
        Sfx.EXPLODE -> ((rnd.nextDouble() * 2 - 1) * exp(-t * 2.2) * 0.8 + tone(50.0, t) * exp(-t * 3) * 0.6) to 1.8
        Sfx.STAGE -> ((rnd.nextDouble() * 2 - 1) * exp(-t * 16) * 0.55 + tone(160.0, t) * exp(-t * 25) * 0.4) to 0.4
        Sfx.BEEP -> (tone(880.0, t) * (if (t < 0.13) 0.3 else 0.0)) to 0.15
        Sfx.LIFTOFF -> (tone(1320.0, t) * exp(-t * 4) * 0.3) to 0.6
        Sfx.CLICK -> (tone(1500.0, t) * exp(-t * 60) * 0.25) to 0.06
        Sfx.ACHIEVE -> {
            val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
            val k = (t / 0.09).toInt().coerceAtMost(3)
            (tone(notes[k], t) * exp(-(t - k * 0.09) * 8) * 0.3) to 0.6
        }
        Sfx.WIN -> {
            val notes = doubleArrayOf(392.0, 523.25, 659.25, 783.99, 1046.5, 1318.5)
            val k = (t / 0.14).toInt().coerceAtMost(5)
            ((tone(notes[k], t) + 0.5 * tone(notes[k] * 2, t)) * exp(-(t - k * 0.14) * 4) * 0.22) to 1.6
        }
    }
}
