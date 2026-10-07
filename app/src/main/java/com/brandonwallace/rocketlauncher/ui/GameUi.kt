package com.brandonwallace.rocketlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.brandonwallace.rocketlauncher.game.Achievement
import com.brandonwallace.rocketlauncher.game.FlightResult
import com.brandonwallace.rocketlauncher.game.GameEngine
import com.brandonwallace.rocketlauncher.game.HudState
import com.brandonwallace.rocketlauncher.game.Milestone
import com.brandonwallace.rocketlauncher.game.Mission
import com.brandonwallace.rocketlauncher.game.MissionTarget
import com.brandonwallace.rocketlauncher.game.Phase
import com.brandonwallace.rocketlauncher.game.Physics
import com.brandonwallace.rocketlauncher.game.Program
import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.SaveData
import com.brandonwallace.rocketlauncher.game.Stat
import com.brandonwallace.rocketlauncher.game.TechTree
import com.brandonwallace.rocketlauncher.game.formatAlt
import com.brandonwallace.rocketlauncher.game.formatMoney
import com.brandonwallace.rocketlauncher.game.formatSpeed
import com.brandonwallace.rocketlauncher.game.missionFraction
import com.brandonwallace.rocketlauncher.game.simulateAutopilot
import com.brandonwallace.rocketlauncher.gl.Exhaust
import com.brandonwallace.rocketlauncher.gl.GameView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ln

@Composable
fun GameUi(engine: GameEngine, glView: GameView) {
    val hud by engine.hud.collectAsState()
    val save by engine.save.collectAsState()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(hud.hits) { if (hud.hits > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    BackHandler(enabled = hud.phase != Phase.MENU) {
        when (hud.phase) {
            Phase.HANGAR -> engine.openMenu()
            Phase.TECH -> engine.openHangar()
            Phase.FLIGHT, Phase.COUNTDOWN -> engine.setPaused(!hud.paused)
            Phase.RESULTS -> engine.openHangar()
            else -> {}
        }
    }

    MaterialTheme(colorScheme = AppScheme) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
            // one animated container for all screens: consistent cross-fade + lift transitions
            AnimatedContent(
                targetState = hud.phase,
                transitionSpec = { (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 14 }) togetherWith fadeOut(tween(200)) },
                label = "screens",
            ) { phase ->
                when (phase) {
                    Phase.MENU -> MenuScreen(engine, save)
                    Phase.HANGAR -> HangarScreen(engine, save)
                    Phase.TECH -> TechTreeScreen(engine, save)
                    Phase.COUNTDOWN -> CountdownOverlay(engine, hud)
                    // 1.1: no touch/tilt flight controls — the autopilot flies; the HUD is display-only (+ pause)
                    Phase.FLIGHT -> FlightHud(engine, hud)
                    Phase.RESULTS -> hud.results?.let { ResultsScreen(engine, it, save) } ?: Box(Modifier.fillMaxSize())
                }
            }
            if (hud.phase == Phase.FLIGHT || hud.phase == Phase.COUNTDOWN) Banner(hud)
            AnimatedVisibility(hud.paused, enter = fadeIn(), exit = fadeOut()) { PauseOverlay(engine, save.soundOn) }
        }
    }
}

/** Quick mute: the same round button on every screen. Flips sound on/off and saves it. */
@Composable
fun MuteButton(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButtonRound(if (on) "🔊" else "🔇", modifier, onClick)
}

/** Sim-time duration: seconds near Earth, hours/days for interplanetary flights. */
fun formatSimTime(s: Double): String = when {
    s < 3_600 -> "%.0f s (sim)".format(s)
    s < 172_800 -> "%.1f h (sim)".format(s / 3_600)
    else -> "%,.0f days (sim)".format(s / 86_400)
}

// ---------------------------------------------------------------- menu

@Composable
fun MenuScreen(engine: GameEngine, save: SaveData) {
    var showAch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB0050814), Color.Transparent, Color.Transparent, Color(0xD0050814)))).safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ROCKET", style = TextStyle(brush = Brush.verticalGradient(listOf(Color.White, UiColors.Gold, UiColors.Orange))), fontSize = 60.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LAUNCHER", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("Earth → Moon → Mars → Jupiter → Saturn", color = UiColors.Cyan, fontSize = 14.sp, letterSpacing = 1.sp)
                Text("Research · Launch · Explore", color = UiColors.TextDim, fontSize = 12.sp, letterSpacing = 2.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Row { StatCell("Best altitude", formatAlt(save.bestAltitude), Modifier.weight(1f)); StatCell("Flights", "${save.flights}", Modifier.weight(1f)) }
                    Row { StatCell("Top speed", formatSpeed(save.bestSpeed), Modifier.weight(1f)); StatCell("Tech", "${save.tech.size}/${TechTree.nodes.size}", Modifier.weight(1f)) }
                    MissionLine(save, Modifier.padding(top = 4.dp))
                }
                MoneyChip(save.money)
                GameButton(if (save.flights == 0) "START" else "PLAY", Modifier.fillMaxWidth()) { engine.openHangar() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GameButton("🔬 TECH", Modifier.weight(1f), color = UiColors.Cyan, filled = false, height = 48.dp) { engine.openTech() }
                    GameButton("🏆 ${save.achievements.size}/${Achievement.entries.size}", Modifier.weight(1f), color = UiColors.Gold, filled = false, height = 48.dp) { showAch = true }
                    GameButton("⚙️", Modifier.width(64.dp), color = UiColors.Text, filled = false, height = 48.dp) { showSettings = true }
                }
            }
        }
        MuteButton(save.soundOn, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) { engine.toggleSound() }
    }
    if (showAch) AchievementsDialog(save) { showAch = false }
    if (showSettings) SettingsDialog(engine, save) { showSettings = false }
}

@Composable
fun AchievementsDialog(save: SaveData, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF0E1638),
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Badges & milestones", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Zone milestones (first-time bonus)")
                for (m in Milestone.entries) {
                    val got = m in save.milestones
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (got) "⭐" else "☆", fontSize = 18.sp); Spacer(Modifier.width(8.dp))
                        Text(m.title, color = if (got) UiColors.Text else UiColors.TextDim, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        Text(formatMoney(m.bonus), color = if (got) UiColors.Gold else UiColors.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                SectionLabel("Badges")
                for (a in Achievement.entries) {
                    val got = a in save.achievements
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (got) "🏆" else "🔒", fontSize = 18.sp); Spacer(Modifier.width(8.dp))
                        Column { Text(a.title, color = if (got) UiColors.Gold else UiColors.TextDim, fontWeight = FontWeight.Bold); Text(a.desc, fontSize = 12.sp, color = UiColors.TextDim) }
                    }
                }
            }
        }
    )
}

@Composable
fun SettingsDialog(engine: GameEngine, save: SaveData, onDismiss: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF0E1638),
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Sound", Modifier.weight(1f)); Switch(save.soundOn, { engine.setSound(it) }) }
                SectionLabel("Graphics quality")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF))) {
                    for ((i, name) in listOf("Low", "Medium", "High").withIndex()) {
                        val sel = save.quality == i
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) UiColors.Orange else Color.Transparent).clickable { engine.setQuality(i) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(name, color = if (sel) UiColors.Ink else UiColors.Text, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(when (save.quality) {
                    0 -> "70% resolution, no MSAA, simple shadows, no bloom — smoothest."
                    2 -> "Full resolution, 4× MSAA, soft 2K shadows, 5-level bloom, heat shimmer."
                    else -> "85% resolution, 2× MSAA, soft shadows, bloom, heat shimmer."
                }, fontSize = 12.sp, color = UiColors.TextDim)
                if (!confirmReset) TextButton(onClick = { confirmReset = true }) { Text("Reset progress…", color = UiColors.Bad) }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Erase all Rocket Launcher progress?", color = UiColors.Bad, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { engine.resetProgress(); confirmReset = false; onDismiss() }) { Text("Erase", color = UiColors.Bad) }
                }
            }
        }
    )
}

// ---------------------------------------------------------------- hangar

@Composable
fun HangarScreen(engine: GameEngine, save: SaveData) {
    val spec = remember(save.tech) { RocketSpec(save.tech) }
    val target = MissionTarget.of(save)
    val sim by produceState<com.brandonwallace.rocketlauncher.game.SimResult?>(null, save.tech, target) {
        value = null
        value = withContext(Dispatchers.Default) { simulateAutopilot(spec, target) }
    }
    val affordable = TechTree.available(save).count { it.cost <= save.money }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
            IconButtonRound("←") { engine.openMenu() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("HANGAR", color = UiColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("Flight ${save.flights + 1}", color = UiColors.TextDim, fontSize = 12.sp)
            }
            MuteButton(save.soundOn) { engine.toggleSound() }
            Spacer(Modifier.width(8.dp))
            MoneyChip(save.money)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MissionPanel(save, spec)
            Panel(Modifier.fillMaxWidth()) {
                SectionLabel("Vehicle performance")
                Row {
                    StatCell("Δv", "%,.0f m/s".format(spec.idealDeltaV()), Modifier.weight(1f))
                    val twr = spec.liftoffTwr()
                    StatCell("Liftoff TWR", "%.2f".format(twr), Modifier.weight(1f), if (twr < 1.15) UiColors.Bad else UiColors.Text)
                }
                Row {
                    val s = sim
                    StatCell("Autopilot forecast", when {
                        s == null -> "…"
                        s.titanFlight -> "🚁 Titan flight"
                        s.titanProbe -> "🪂 Titan probe"
                        s.enceladus -> "⛲ Enceladus plumes"
                        s.saturnOrbit -> "🛰️ Saturn orbit"
                        s.ringsCrossed -> "💫 Ring crossing"
                        s.reachedSaturn -> "🪐 Saturn flyby"
                        s.reachedJupiter && s.target == MissionTarget.SATURN -> "🌀 past Jupiter · " + formatAlt(s.maxAlt)
                        s.europaLanded -> "🧊 Europa landing"
                        s.jupiterOrbit -> "🟠 Jupiter orbit"
                        s.reachedJupiter -> "🟠 Jupiter flyby"
                        s.marsLanded -> "🔴 Mars landing"
                        s.marsOrbit -> "🔴 Mars orbit"
                        s.reachedMars && s.target == MissionTarget.MARS -> "🔴 Mars flyby"
                        s.reachedMars -> "☄️ past Mars · " + formatAlt(s.maxAlt)
                        s.passedMoon -> "🌕 past Moon · " + formatAlt(s.maxAlt)
                        else -> formatAlt(s.maxAlt)
                    }, Modifier.weight(1f), UiColors.Cyan)
                    StatCell("Stages", "${spec.stages.size}" + (if (spec.boosterCount > 0) " + ${spec.boosterCount} " + (if (spec.heavySideCores) "cores" else "SRB") else ""), Modifier.weight(1f))
                }
                Row {
                    StatCell("Engines", "${spec.engineCount}× ${(spec.thrust1 / 1000).toInt()} kN", Modifier.weight(1f))
                    StatCell("Hull / heat", "${spec.maxHull.toInt()} / L${spec.level(Stat.HEAT)}", Modifier.weight(1f))
                }
                Text(
                    listOfNotNull(
                        "🤖 Autopilot",
                        "🔥 " + Exhaust.firstStage(spec).name + (if (spec.stages.size > 1) " / " + Exhaust.upperStage(spec).name else ""),
                        if (spec.hazardRadar) "📡 Hazard radar" else null,
                        if (spec.autoThrottle) (if (spec.navAutoCut) "🧠 Nav computer (max-Q throttle, engine cut on course)" else "🧠 Flight computer (max-Q throttle)") else null,
                        if (spec.deepSpacePower) "☀️ Deep-space power" else null,
                        if (spec.jupiterRadiationReady) "☢️ Radiation vault" else null,
                        if (spec.saturnNavPower) "⚛️ Next-gen RTGs" else null,
                        if (spec.slingKick > 0) "🌀 Slingshot" else null,
                        if (spec.cruiseAccel > 0) "⛵ Cruise push" else null,
                        if (spec.ringShield) "💫 Ring shield" else null,
                    ).joinToString("   "),
                    color = UiColors.Good, fontSize = 12.sp)
                // 1.3: the tanks only fill as far as the engines can still lift (liftoff thrust-to-weight 1.1)
                if (spec.fuelLoad < 0.995) Text(
                    "⛽ Tanks %.0f%% full: research engines to lift the rest".format(spec.fuelLoad * 100),
                    color = UiColors.Gold, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GameButton(if (affordable > 0) "🔬 TECH TREE ($affordable)" else "🔬 TECH TREE", Modifier.weight(1f), color = UiColors.Cyan) { engine.openTech() }
                GameButton("🚀 LAUNCH", Modifier.weight(1f)) { engine.launch() }
            }
        }
    }
}

// ---------------------------------------------------------------- flight

@Composable
fun CountdownOverlay(engine: GameEngine, hud: HudState) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButtonRound("II") { engine.setPaused(true) }
            MuteButton(hud.soundOn) { engine.toggleSound() }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(hud.countdown, transitionSpec = { (fadeIn(tween(150)) + scaleIn(tween(250), initialScale = 1.6f)) togetherWith fadeOut(tween(150)) }, label = "cd") { c ->
                Text(if (c > 0) "$c" else "IGNITION", color = Color.White, fontSize = if (c > 0) 120.sp else 48.sp, fontWeight = FontWeight.Black)
            }
            SectionLabel("T-minus", color = UiColors.Cyan)
        }
        Text("🤖 Autopilot engaged — your upgrades fly this one", color = UiColors.Text, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).glass(RoundedCornerShape(14.dp)).padding(12.dp))
    }
}

@Composable
fun VBar(label: String, value: Float, color: Color, modifier: Modifier = Modifier, flash: Boolean = false) {
    val pulse by rememberInfiniteTransition(label = "bar").animateFloat(0.4f, 1f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "p")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(16.dp).height(150.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x66000000)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(8.dp))) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(color.copy(alpha = if (flash) pulse else 1f)))
        }
        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Avionics radar: chevrons showing where incoming hazards will cross the autopilot's lane. */
@Composable
fun RadarStrip(radar: List<Float>, modifier: Modifier = Modifier) {
    Box(modifier.width(220.dp).height(26.dp).glass(RoundedCornerShape(13.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawLine(Color(0x44FFFFFF), Offset(w / 2, 4f), Offset(w / 2, h - 4f), 2f)
            for (x in radar) {
                val cx = w / 2 + x * (w / 2 - 14f)
                val p = Path().apply { moveTo(cx - 9f, 6f); lineTo(cx + 9f, 6f); lineTo(cx, h - 6f); close() }
                drawPath(p, Color(0xFFFF5A5A))
            }
        }
    }
}

@Composable
fun FlightHud(engine: GameEngine, hud: HudState) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Column(Modifier.align(Alignment.TopStart).glass(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(formatAlt(hud.altitude), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (hud.vSpeed < 0) "↓ " else "↑ ") + formatSpeed(hud.speed), color = UiColors.Cyan, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                if (hud.mach >= 0.8f && hud.altitude < 60_000) Text("  M%.1f".format(hud.mach), color = UiColors.Gold, fontSize = 13.sp)
            }
            Text(hud.zone, color = UiColors.TextDim, fontSize = 11.sp)
            if (hud.warp > 1.5f) Text("⏩ time warp ×%,d".format(hud.warp.toInt()), color = UiColors.Gold, fontSize = 11.sp)
        }
        Column(Modifier.align(Alignment.TopEnd), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButtonRound("II") { engine.setPaused(true) }
            MuteButton(hud.soundOn) { engine.toggleSound() }
        }
        if (hud.radar.isNotEmpty() || hud.autoThrottling || hud.evading) Column(Modifier.align(Alignment.TopCenter).padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (hud.radar.isNotEmpty()) RadarStrip(hud.radar)
            if (hud.evading) Text("🤖 AUTOPILOT: EVADING HAZARD", color = UiColors.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 6.dp).glass(RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            if (hud.autoThrottling) Text("🧠 FLIGHT COMPUTER: THROTTLING FOR HEAT", color = UiColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 6.dp).glass(RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VBar("FUEL", hud.fuel, if (hud.fuel > 0.25f) UiColors.Orange else UiColors.Bad, flash = hud.fuel in 0.001f..0.15f)
            if (hud.boosterFuel >= 0f) VBar("BOOST", hud.boosterFuel, UiColors.Gold)
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VBar("HEAT", (hud.heat / 1.3f), if (hud.heat > 1f) UiColors.Bad else Color(0xFFFF6A3D), flash = hud.heat > 1f)
            VBar("HULL", hud.hull, if (hud.hull > 0.35f) UiColors.Good else UiColors.Bad, flash = hud.hull < 0.25f)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (hud.bound && !hud.ending) {
                val note = if (hud.arrivalNote.isNotEmpty()) " · " + hud.arrivalNote else ""
                val t = hud.target
                val head = when {
                    hud.navCut -> "🧠 Nav computer: engines off — ${t.icon} on course for ${t.title}"
                    hud.throttle > 0.05f -> "${t.icon} On course for ${t.title} — autopilot still burning"
                    else -> "${t.icon} Coasting to ${t.title}"
                }
                Text(head + note,
                    textAlign = TextAlign.Center,
                    color = UiColors.Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp).glass(RoundedCornerShape(12.dp)).padding(10.dp))
            }
            Row(Modifier.glass(RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hud.throttle > 0.05f) Text("🔥 ", fontSize = 14.sp)
                // 1.3: up to 8 stages, so the pills get compact past 5 to keep the bar on narrow phones
                val compact = hud.stageCount > 5
                for (i in 0 until hud.stageCount) {
                    val active = i == hud.stage
                    val spent = i < hud.stage
                    Box(Modifier.padding(horizontal = if (compact) 1.5.dp else 3.dp).clip(RoundedCornerShape(8.dp))
                        .background(when { active -> UiColors.Orange; spent -> Color(0x33FFFFFF); else -> Color(0x66FFFFFF) })
                        .padding(horizontal = if (compact) 5.dp else 9.dp, vertical = 3.dp)) {
                        Text(if (compact) "${i + 1}" else "S${i + 1}", color = if (active) UiColors.Ink else Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 11.sp else 12.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                ProgressLine(hud.missionProgress, Modifier.width(if (compact) 110.dp else 150.dp), right = hud.target.icon)
            }
        }
    }
}

@Composable
fun Banner(hud: HudState) {
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(hud.bannerId) {
        if (hud.bannerId == 0 || hud.banner.isEmpty()) return@LaunchedEffect
        text = hud.banner; visible = true
        delay(2300)
        visible = false
    }
    Box(Modifier.fillMaxSize().padding(top = 150.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.7f), exit = fadeOut()) {
            Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xDD1B2A6B), Color(0xDD3B1B6B)))).border(1.dp, UiColors.Gold.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}

@Composable
fun PauseOverlay(engine: GameEngine, soundOn: Boolean) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Panel(Modifier.width(290.dp), padding = 22.dp) {
            Text("PAUSED", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            GameButton("RESUME", Modifier.fillMaxWidth()) { engine.setPaused(false) }
            GameButton(if (soundOn) "🔊 SOUND ON" else "🔇 SOUND OFF", Modifier.fillMaxWidth(), color = UiColors.Cyan, filled = false, height = 48.dp) { engine.toggleSound() }
            GameButton("ABORT FLIGHT", Modifier.fillMaxWidth(), color = UiColors.Bad, filled = false, height = 48.dp) { engine.abortFlight() }
        }
    }
}

// ---------------------------------------------------------------- results

@Composable
fun ResultsScreen(engine: GameEngine, r: FlightResult, save: SaveData) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000)).safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp), UiColors.Gold.copy(alpha = 0.35f)).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(when {
                r.titanFlight -> "🚁"; r.titanProbe -> "🪂"; r.enceladus -> "⛲"; r.saturnOrbit -> "🛰️"; r.ringsCrossed -> "💫"; r.saturnReached -> "🪐"
                r.europaLanded -> "🧊"; r.jupiterReached -> "🟠"; r.marsLanded -> "🔴"; r.marsOrbit -> "🛰️"
                r.reason.contains("destroyed") -> "💥"; r.marsReached -> "🔴"; r.moonPassed -> "🌕"; else -> "🚀"
            }, fontSize = 52.sp)
            Text(r.reason, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            val note = when {
                r.titanFlight && save.titanFlights == 1 -> "A rotorcraft is flying through Titan's orange skies, 1.2 billion km from home! The whole mission is complete. Keep flying to finish the tech tree and set records."
                r.titanFlight -> "Another flight on Titan! Keep flying to finish the tech tree and set records."
                r.titanProbe -> "A probe rests on Titan's frozen shore! Next: fly on Titan with a Dragonfly rotorcraft."
                r.enceladus -> "You flew through the ice geysers of Enceladus! Next: land a probe on Titan."
                r.saturnOrbit -> "In orbit around Saturn, the jewel of the solar system! Enceladus is next."
                r.ringsCrossed -> "Safely through the ring plane! Arrive with capture fuel (or a spare stage) to stay in Saturn orbit."
                r.saturnReached -> "You reached Saturn, 1.2 billion km from home! A Ring-Plane Dust Shield lets you cross the rings."
                r.target == MissionTarget.SATURN && r.jupiterReached -> "Past Jupiter and on toward Saturn! Keep upgrading to make it all the way."
                r.target == MissionTarget.SATURN && r.marsReached -> "Saturn is twice as far as Jupiter. Keep upgrading to push on!"
                r.europaLanded -> "A lander stands on Europa's ice, 588 million km from home! 🪐 The Saturn program is now open in the tech tree."
                r.jupiterOrbit -> "In orbit around the king of the planets! Europa is next."
                r.marsLanded && save.marsLandings == 1 -> "Humanity's rocket stands on Mars! 🪐 The Jupiter program is now open in the tech tree."
                r.marsLanded -> "Humanity's rocket stands on Mars! Keep flying to finish the tech tree and set records."
                r.target == MissionTarget.JUPITER && r.marsReached && !r.jupiterReached -> "Past Mars and on toward Jupiter! Keep upgrading to make it all the way."
                r.moonPassed && !r.marsReached -> "Past the Moon! Keep upgrading to push on to Mars."
                else -> ""
            }
            if (note.isNotEmpty()) Text(note, color = UiColors.Gold, textAlign = TextAlign.Center, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            ResultRow("Max altitude", formatAlt(r.maxAlt), "Altitude" in r.records)
            ResultRow("Top speed", formatSpeed(r.topSpeed), "Speed" in r.records)
            ResultRow("Flight time", formatSimTime(r.time), false)
            ResultRow("Hazards hit", "${r.hits}", false)
            ResultRow("Mission", "%.0f%% of the way to %s".format(missionFraction(r.maxAlt, r.target) * 100, r.target.title), false)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
            ResultRow("Flight performance", "+" + formatMoney(r.performancePay), false, UiColors.Gold)
            for (m in r.milestones) ResultRow("⭐ ${m.title}", "+" + formatMoney(m.bonus), false, UiColors.Gold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("TOTAL", color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text("+" + formatMoney(r.total), color = UiColors.Gold, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            r.achievements.forEach { a ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x332B6DE0)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏆", fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { Text(a.title, color = UiColors.Gold, fontWeight = FontWeight.Bold); Text(a.desc, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp) }
                }
            }
            Text("Balance: " + formatMoney(r.balanceAfter), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GameButton("🔬 TECH", Modifier.weight(1f), color = UiColors.Cyan) { engine.openTech() }
                GameButton("🚀 AGAIN", Modifier.weight(1f)) { engine.launch() }
            }
            GameButton("HANGAR", Modifier.fillMaxWidth(), color = UiColors.Text, filled = false, height = 44.dp) { engine.openHangar() }
        }
        MuteButton(save.soundOn, Modifier.align(Alignment.TopEnd)) { engine.toggleSound() }
    }
}

@Composable
fun ResultRow(label: String, value: String, record: Boolean, color: Color = Color.White) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFB8C4EE), fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (record) Text("NEW RECORD ", color = UiColors.Gold, fontSize = 10.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x44FFD34D)).padding(horizontal = 6.dp, vertical = 2.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}


// ---------------------------------------------------------------- the one mission (Earth → Moon → Mars → Jupiter → Saturn)

/** Step line of the one mission (two rows: to Europa, then the Saturn chapter) with the next objective; drawn on the menu. */
@Composable
fun MissionLine(save: SaveData, modifier: Modifier = Modifier) {
    val done = Mission.doneCount(save)
    val next = Mission.next(save)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (saturn in listOf(false, true)) {
            val steps = Mission.entries.filter { it.saturnChapter == saturn }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (saturn) "🪐" else "🌍", fontSize = 12.sp, modifier = Modifier.width(20.dp))
                for ((i, m) in steps.withIndex()) {
                    val ok = m.done(save)
                    Box(Modifier.size(22.dp).clip(CircleShape).background(if (ok) UiColors.Gold else Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                        Text(if (ok) "✓" else m.icon, fontSize = 10.sp, color = if (ok) UiColors.Ink else Color.White, fontWeight = FontWeight.Black)
                    }
                    if (i < steps.size - 1) Box(Modifier.weight(1f).height(3.dp).background(if (ok) UiColors.Gold else Color(0x33FFFFFF)))
                }
            }
        }
        Text(if (next == null) "Mission complete — flew on Titan!" else "MISSION STEP ${done + 1}/${Mission.entries.size}: ${next.title}",
            color = UiColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Hangar: the one mission (no destination picker) and its next step. */
@Composable
fun MissionPanel(save: SaveData, spec: RocketSpec) {
    val next = Mission.next(save)
    val target = MissionTarget.of(save)
    val jupiter = target == MissionTarget.JUPITER
    val saturn = target == MissionTarget.SATURN
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Mission: Earth → Moon → Mars → Jupiter → Saturn · step ${(Mission.doneCount(save) + 1).coerceAtMost(Mission.entries.size)}/${Mission.entries.size}")
                Text(next?.title ?: "Mission complete", color = UiColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text(next?.desc ?: "Keep flying to finish the tech tree and set records.", color = UiColors.TextDim, fontSize = 11.sp)
            }
            Text(next?.icon ?: "🚁", fontSize = 22.sp)
        }
        val hint = when {
            !jupiter && !saturn && save.milestones.contains(Milestone.MOON) && !spec.marsEntryReady ->
                "Landing on Mars needs PICA-X Aeroshell + Supersonic Parachute (Thermal branch)"
            jupiter && save.tech.none { TechTree.byId[it]?.program == Program.JUPITER } ->
                "🪐 Jupiter program unlocked: new upgrades in tech tiers 10–12"
            jupiter && !spec.deepSpacePower ->
                "Past Mars the nav computer needs Juno-Class Solar Arrays (Avionics) to save fuel"
            jupiter && !spec.jupiterRadiationReady ->
                "Jupiter orbit needs the Titanium Radiation Vault (Thermal branch)"
            jupiter && !spec.europaLandingReady ->
                "Landing on Europa needs the Europa Lander + Radiation-Hardened Electronics"
            saturn && save.tech.none { TechTree.byId[it]?.program == Program.SATURN } ->
                "🪐 Saturn program unlocked: new upgrades in tech tiers 13–24"
            saturn && !spec.saturnNavPower ->
                "Past Jupiter the nav computer needs Next-Gen RTGs (Avionics) to save fuel"
            saturn && !spec.ringShield ->
                "Crossing Saturn's rings needs the Ring-Plane Dust Shield (Structures)"
            saturn && !spec.plumeReady ->
                "Flying through Enceladus' plumes needs the Plume Mass Spectrometer (Avionics)"
            saturn && !spec.titanProbeReady ->
                "A Titan probe needs the Titan Entry Aeroshell + Huygens-Class Probe (Thermal)"
            saturn && !spec.titanFlightReady ->
                "Flying on Titan needs the Dragonfly Rotorcraft (Aerodynamics)"
            else -> ""
        }
        if (hint.isNotEmpty()) Text(hint, color = UiColors.Gold, fontSize = 11.sp)
    }
}
