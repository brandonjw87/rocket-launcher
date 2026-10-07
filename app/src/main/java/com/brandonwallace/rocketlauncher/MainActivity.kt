package com.brandonwallace.rocketlauncher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brandonwallace.rocketlauncher.audio.SoundEngine
import com.brandonwallace.rocketlauncher.game.GameEngine
import com.brandonwallace.rocketlauncher.game.SaveStore
import com.brandonwallace.rocketlauncher.gl.GameView
import com.brandonwallace.rocketlauncher.ui.GameUi

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var engine: GameEngine
    private lateinit var sound: SoundEngine
    private lateinit var glView: GameView
    private var sensors: SensorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // own save file, separate from v1 ("rocket_ascent"), v2 ("rocket_ascent_v2") and Calins Rocket
        val prefs = getSharedPreferences("rocket_launcher", Context.MODE_PRIVATE)
        engine = GameEngine(object : SaveStore {
            override fun load(): String? = prefs.getString("save", null)
            override fun save(data: String) { prefs.edit().putString("save", data).apply() }
        })
        sound = SoundEngine()
        engine.sfxListener = { sound.play(it) }
        glView = GameView(this, engine, sound)
        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        setContent { GameUi(engine, glView) }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        // coming back from the background never drops you straight into a running flight
        engine.onAppResumed()
        sound.start()
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        engine.setPaused(true)
        engine.inputThrottle = false
        glView.onPause()
        sound.stop()
        sensors?.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!engine.save.value.tiltControl) return
        engine.inputSteer = (-event.values[0] / 4.5f).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
