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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.brandonwallace.rocketlauncher.game.Destination
import com.brandonwallace.rocketlauncher.game.Phase
import com.brandonwallace.rocketlauncher.game.Physics
import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.SaveData
import com.brandonwallace.rocketlauncher.game.Stat
import com.brandonwallace.rocketlauncher.game.TechTree
import com.brandonwallace.rocketlauncher.game.formatAlt
import com.brandonwallace.rocketlauncher.game.formatMoney
import com.brandonwallace.rocketlauncher.game.formatSpeed
import com.brandonwallace.rocketlauncher.game.simulateAutopilot
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
                    Phase.FLIGHT -> Box(Modifier.fillMaxSize()) { FlightTouchLayer(engine, save.tiltControl); FlightHud(engine, hud) }
                    Phase.RESULTS -> hud.results?.let { ResultsScreen(engine, it, save) } ?: Box(Modifier.fillMaxSize())
                }
            }
            if (hud.phase == Phase.FLIGHT || hud.phase == Phase.COUNTDOWN) Banner(hud)
            AnimatedVisibility(hud.paused, enter = fadeIn(), exit = fadeOut()) { PauseOverlay(engine) }
        }
    }
}

fun moonFraction(alt: Double, target: Double = Physics.MOON_ARRIVAL_ALT): Float = (ln(1.0 + alt / 1000.0) / ln(1.0 + target / 1000.0)).toFloat().coerceIn(0f, 1f)

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
            Text("Research · Launch · Moon · Mars", color = UiColors.Cyan, fontSize = 14.sp, letterSpacing = 1.sp)
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
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Tilt to steer", Modifier.weight(1f)); Switch(save.tiltControl, { engine.setTilt(it) }) }
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
    val dest = save.effectiveDestination
    val sim by produceState<com.brandonwallace.rocketlauncher.game.SimResult?>(null, save.tech, dest) {
        value = null
        value = withContext(Dispatchers.Default) { simulateAutopilot(spec, targetAlt = dest.alt) }
    }
    val affordable = TechTree.available(save.tech).count { it.cost <= save.money }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
            IconButtonRound("←") { engine.openMenu() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("HANGAR", color = UiColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("Flight ${save.flights + 1}", color = UiColors.TextDim, fontSize = 12.sp)
            }
            MoneyChip(save.money)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MissionPanel(engine, save, spec)
            Panel(Modifier.fillMaxWidth()) {
                SectionLabel("Vehicle performance")
                Row {
                    StatCell("Δv", "%,.0f m/s".format(spec.idealDeltaV()), Modifier.weight(1f))
                    val twr = spec.liftoffTwr()
                    StatCell("Liftoff TWR", "%.2f".format(twr), Modifier.weight(1f), if (twr < 1.15) UiColors.Bad else UiColors.Text)
                }
                Row {
                    val s = sim
                    StatCell("Predicted apogee", when { s == null -> "…"; s.reachedTarget -> dest.icon + " " + dest.title; else -> formatAlt(s.maxAlt) }, Modifier.weight(1f), UiColors.Cyan)
                    StatCell("Stages", "${spec.stages.size}" + (if (spec.boosterCount > 0) " + ${spec.boosterCount} " + (if (spec.heavySideCores) "cores" else "SRB") else ""), Modifier.weight(1f))
                }
                Row {
                    StatCell("Engines", "${spec.engineCount}× ${(spec.thrust1 / 1000).toInt()} kN", Modifier.weight(1f))
                    StatCell("Hull / heat", "${spec.maxHull.toInt()} / L${spec.level(Stat.HEAT)}", Modifier.weight(1f))
                }
                if (spec.hazardRadar || spec.autoThrottle) Text(
                    listOfNotNull(if (spec.hazardRadar) "📡 Hazard radar" else null, if (spec.autoThrottle) (if (spec.level(Stat.AVIONICS) >= 4) "🧠 Nav computer (auto-throttle, auto-cut Moon & Mars)" else "🧠 Flight computer (auto-throttle, auto-cut on Moon course)") else null).joinToString("   "),
                    color = UiColors.Good, fontSize = 12.sp)
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
        IconButtonRound("II", Modifier.align(Alignment.TopEnd).padding(12.dp)) { engine.setPaused(true) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(hud.countdown, transitionSpec = { (fadeIn(tween(150)) + scaleIn(tween(250), initialScale = 1.6f)) togetherWith fadeOut(tween(150)) }, label = "cd") { c ->
                Text(if (c > 0) "$c" else "IGNITION", color = Color.White, fontSize = if (c > 0) 120.sp else 48.sp, fontWeight = FontWeight.Black)
            }
            SectionLabel("T-minus", color = UiColors.Cyan)
        }
        Text("HOLD anywhere to burn · DRAG left/right to steer", color = UiColors.Text, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).glass(RoundedCornerShape(14.dp)).padding(12.dp))
    }
}

@Composable
fun FlightTouchLayer(engine: GameEngine, tilt: Boolean) {
    Box(
        Modifier.fillMaxSize().pointerInput(tilt) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                engine.inputThrottle = true
                val startX = down.position.x
                do {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!tilt) engine.inputSteer = ((ch.position.x - startX) / (size.width * 0.22f)).coerceIn(-1f, 1f)
                    ch.consume()
                } while (ch.pressed)
                engine.inputThrottle = false
                if (!tilt) engine.inputSteer = 0f
            }
        }
    )
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

/** Avionics radar: chevrons showing where incoming hazards will cross your lane. */
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
        IconButtonRound("II", Modifier.align(Alignment.TopEnd)) { engine.setPaused(true) }
        if (hud.radar.isNotEmpty() || hud.autoThrottling) Column(Modifier.align(Alignment.TopCenter).padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (hud.radar.isNotEmpty()) RadarStrip(hud.radar)
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
            if (hud.moonBound && !hud.ending) {
                val d = hud.destination
                val note = if (hud.arrivalNote.isNotEmpty()) " · " + hud.arrivalNote else ""
                val head = when {
                    hud.navCut -> "🧠 Nav computer: engines off — ${d.icon} on course for ${d.title}"
                    hud.throttle > 0.05f -> "${d.icon} On course for ${d.title} — release to save fuel"
                    else -> "${d.icon} Coasting to ${d.title}"
                }
                Text(head + note,
                    textAlign = TextAlign.Center,
                    color = UiColors.Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp).glass(RoundedCornerShape(12.dp)).padding(10.dp))
            }
            if (hud.showHint) {
                val a by rememberInfiniteTransition(label = "hint").animateFloat(0.5f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                Text("HOLD to burn · DRAG to steer", color = Color.White.copy(alpha = a), fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 14.dp).glass(RoundedCornerShape(12.dp)).padding(12.dp))
            }
            Row(Modifier.glass(RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hud.throttle > 0.05f) Text("🔥 ", fontSize = 14.sp)
                for (i in 0 until hud.stageCount) {
                    val active = i == hud.stage
                    val spent = i < hud.stage
                    Box(Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(8.dp))
                        .background(when { active -> UiColors.Orange; spent -> Color(0x33FFFFFF); else -> Color(0x66FFFFFF) })
                        .padding(horizontal = 9.dp, vertical = 3.dp)) {
                        Text("S${i + 1}", color = if (active) UiColors.Ink else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                ProgressLine(hud.moonProgress, Modifier.width(150.dp), right = hud.destination.icon)
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
fun PauseOverlay(engine: GameEngine) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Panel(Modifier.width(290.dp), padding = 22.dp) {
            Text("PAUSED", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            GameButton("RESUME", Modifier.fillMaxWidth()) { engine.setPaused(false) }
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
            Text(when { r.marsLanded -> "🔴"; r.marsOrbit -> "🛰️"; r.marsReached -> "🔴"; r.moonLanded -> "🌕"; r.moonReached -> "🌑"; r.reason.contains("destroyed") -> "💥"; else -> "🚀" }, fontSize = 52.sp)
            Text(r.reason, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            if (r.marsLanded) Text("Humanity's rocket stands on Mars! Keep flying to finish the tech tree and set records.", color = UiColors.Gold, textAlign = TextAlign.Center, fontSize = 13.sp)
            else if (r.moonLanded && save.marsLandings == 0) Text("Moon landed! Mars is now unlocked — pick 🔴 Mars in the hangar.", color = UiColors.Gold, textAlign = TextAlign.Center, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            ResultRow("Max altitude", formatAlt(r.maxAlt), "Altitude" in r.records)
            ResultRow("Top speed", formatSpeed(r.topSpeed), "Speed" in r.records)
            ResultRow("Flight time", formatSimTime(r.time), false)
            ResultRow("Hazards hit", "${r.hits}", false)
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


// ---------------------------------------------------------------- mission line (Earth → Moon → Mars)

/** Seven-step mission line with the next objective; drawn on the menu. */
@Composable
fun MissionLine(save: SaveData, modifier: Modifier = Modifier) {
    val done = Mission.doneCount(save)
    val next = Mission.next(save)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((i, m) in Mission.entries.withIndex()) {
                val ok = m.done(save)
                val icon = when (m) { Mission.SPACE -> "🌌"; Mission.ORBIT -> "🛰️"; Mission.MOON, Mission.MOON_LANDING -> "🌕"; else -> "🔴" }
                Box(Modifier.size(24.dp).clip(CircleShape).background(if (ok) UiColors.Gold else Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    Text(if (ok) "✓" else icon, fontSize = 11.sp, color = if (ok) UiColors.Ink else Color.White, fontWeight = FontWeight.Black)
                }
                if (i < Mission.entries.size - 1) Box(Modifier.weight(1f).height(3.dp).background(if (ok) UiColors.Gold else Color(0x33FFFFFF)))
            }
        }
        Text(if (next == null) "All missions complete — $done/${Mission.entries.size}" else "MISSION ${done + 1}/${Mission.entries.size}: ${next.title}",
            color = UiColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Hangar: next objective + destination picker (Mars unlocks after the first Moon landing). */
@Composable
fun MissionPanel(engine: GameEngine, save: SaveData, spec: RocketSpec) {
    val next = Mission.next(save)
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Mission ${(Mission.doneCount(save) + 1).coerceAtMost(Mission.entries.size)}/${Mission.entries.size}")
                Text(next?.title ?: "Mission line complete", color = UiColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text(next?.desc ?: "Keep setting records.", color = UiColors.TextDim, fontSize = 11.sp)
            }
            if (save.marsUnlocked) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF))) {
                    for (d in Destination.entries) {
                        val sel = save.effectiveDestination == d
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) UiColors.Orange else Color.Transparent)
                            .clickable { engine.setDestination(d) }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("${d.icon} ${d.title}", color = if (sel) UiColors.Ink else UiColors.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            } else Text("🌕 Moon", color = UiColors.Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        if (save.effectiveDestination == Destination.MARS && !spec.marsEntryReady)
            Text("Landing on Mars needs PICA-X Aeroshell + Supersonic Parachute (Thermal branch)", color = UiColors.Gold, fontSize = 11.sp)
    }
}
