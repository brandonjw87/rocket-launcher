package com.brandonwallace.rocketlauncher

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brandonwallace.rocketlauncher.audio.SoundEngine
import com.brandonwallace.rocketlauncher.game.GameEngine
import com.brandonwallace.rocketlauncher.game.SaveData
import com.brandonwallace.rocketlauncher.game.SaveStore
import com.brandonwallace.rocketlauncher.gl.GameView
import com.brandonwallace.rocketlauncher.ui.GameUi

class MainActivity : ComponentActivity() {
    private lateinit var engine: GameEngine
    private lateinit var sound: SoundEngine
    private lateinit var glView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // own save file, separate from v1 ("rocket_ascent"), v2 ("rocket_ascent_v2") and Calins Rocket.
        // File "rocket_launcher" / key "save" must never change: that is what carries progress across updates.
        val prefs = getSharedPreferences("rocket_launcher", Context.MODE_PRIVATE)
        val raw = prefs.getString("save", null)
        val schema = SaveData.schemaOf(raw)
        if (raw != null && schema < SaveData.SAVE_SCHEMA_VERSION && !prefs.contains("save_backup_v$schema")) {
            // keep the untouched pre-migration save once, so an upgrade can never destroy it
            prefs.edit().putString("save_backup_v$schema", raw).commit()
        }
        engine = GameEngine(object : SaveStore {
            override fun load(): String? = prefs.getString("save", null)
            override fun save(data: String) { prefs.edit().putString("save", data).apply() }
            override fun saveNow(data: String) { prefs.edit().putString("save", data).commit() }
        })
        // write the migrated save straight away (schema 3), so the new format is on disk even before the first flight
        if (raw != null && schema < SaveData.SAVE_SCHEMA_VERSION) engine.persistNow()
        sound = SoundEngine()
        engine.sfxListener = { sound.play(it) }
        glView = GameView(this, engine, sound)
        setContent { GameUi(engine, glView) }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        // coming back from the background never drops you straight into a running flight
        engine.onAppResumed()
        sound.start()
    }

    override fun onPause() {
        engine.setPaused(true)
        engine.persistNow()          // money, tech and progress are on disk before we go to the background
        glView.onPause()
        sound.stop()
        super.onPause()
    }

    override fun onStop() {
        engine.persistNow()
        super.onStop()
    }
}
