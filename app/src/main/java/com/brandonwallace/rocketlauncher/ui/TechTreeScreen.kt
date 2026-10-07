package com.brandonwallace.rocketlauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brandonwallace.rocketlauncher.game.Branch
import com.brandonwallace.rocketlauncher.game.GameEngine
import com.brandonwallace.rocketlauncher.game.Program
import com.brandonwallace.rocketlauncher.game.RocketSpec
import com.brandonwallace.rocketlauncher.game.SaveData
import com.brandonwallace.rocketlauncher.game.Stat
import com.brandonwallace.rocketlauncher.game.TechNode
import com.brandonwallace.rocketlauncher.game.TechTree
import com.brandonwallace.rocketlauncher.game.formatMoney

enum class NodeState { OWNED, AFFORDABLE, AVAILABLE, LOCKED }

fun nodeState(save: SaveData, n: TechNode): NodeState = when {
    n.id in save.tech -> NodeState.OWNED
    TechTree.isAvailable(save, n) && save.money >= n.cost -> NodeState.AFFORDABLE
    TechTree.isAvailable(save, n) -> NodeState.AVAILABLE
    else -> NodeState.LOCKED
}

/** Jupiter- or Saturn-program node that is still closed (the rocket has not landed on Mars / Europa yet). */
fun programClosed(save: SaveData, n: TechNode): Boolean = n.id !in save.tech && !TechTree.programOpen(save, n)

/** Where the rocket has to land first to open this node's program. */
fun programLandingNeeded(n: TechNode): String = if (n.program == Program.SATURN) "Europa" else "Mars"

/** Tier column label: tiers 7-9 are the Mars program, 10-12 Jupiter, 13-24 Saturn. */
fun tierLabel(t: Int): String = "TIER ${t + 1}" + when {
    t >= TechTree.SATURN_TIER -> " · SATURN"
    t >= TechTree.JUPITER_TIER -> " · JUPITER"
    t >= TechTree.MARS_TIER -> " · MARS"
    else -> ""
}

fun statLabel(s: Stat): String = when (s) {
    Stat.ENGINE -> "Engine"; Stat.FUEL -> "Fuel tanks"; Stat.AERO -> "Aerodynamics"; Stat.HULL -> "Hull"
    Stat.STAGES -> "Upper stage"; Stat.BOOSTERS -> "Boosters"; Stat.HEAT -> "Heat shield"; Stat.FINS -> "Fins / control"
    Stat.AVIONICS -> "Avionics"; Stat.VACUUM -> "Vacuum nozzle"; Stat.REGEN -> "Regen cooling"; Stat.SEP -> "Fast separation"
    Stat.NUCLEAR -> "Nuclear / plasma upper stage"; Stat.EDL -> "Mars landing (EDL)"
    Stat.GIMBAL -> "Engine gimbal"; Stat.POWER -> "Deep-space power"; Stat.RADIATION -> "Radiation shielding"; Stat.LANDER -> "Europa lander"
    Stat.SLING -> "Jupiter slingshot"; Stat.CRUISE -> "Cruise propulsion"; Stat.RINGS -> "Ring shield"; Stat.AEROCAP -> "Saturn aerocapture"
    Stat.TITAN -> "Titan landing"; Stat.PLUME -> "Plume spectrometer"
}

/**
 * Pannable / zoomable node graph. Columns are tech tiers (left → right = more advanced and more expensive),
 * rows group the six branches. Edges show prerequisites; colours show state.
 */
@Composable
fun TechTreeScreen(engine: GameEngine, save: SaveData) {
    val density = LocalDensity.current
    val colW = with(density) { 196.dp.toPx() }
    val rowH = with(density) { 76.dp.toPx() }
    val nodeW = with(density) { 172.dp.toPx() }
    val nodeH = with(density) { 60.dp.toPx() }
    val pad = with(density) { 24.dp.toPx() }
    var scale by remember { mutableFloatStateOf(0.62f) }
    var ox by remember { mutableFloatStateOf(with(density) { 12.dp.toPx() }) }
    var oy by remember { mutableFloatStateOf(with(density) { 190.dp.toPx() }) }
    var selected by remember { mutableStateOf<String?>(null) }
    val tm = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "tt").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")

    fun nodeX(n: TechNode) = pad + n.tier * colW
    fun nodeY(n: TechNode) = pad + n.row * rowH

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xF2050A1E), Color(0xF20A1238))))) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(0.35f, 1.8f)
                        val f = ns / scale
                        ox = (ox - centroid.x) * f + centroid.x + pan.x
                        oy = (oy - centroid.y) * f + centroid.y + pan.y
                        scale = ns
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val gx = (p.x - ox) / scale; val gy = (p.y - oy) / scale
                        selected = TechTree.nodes.firstOrNull { n -> gx in nodeX(n)..(nodeX(n) + nodeW) && gy in nodeY(n)..(nodeY(n) + nodeH) }?.id
                    }
                }
        ) {
            withTransform({ translate(ox, oy); scale(scale, scale, pivot = Offset.Zero) }) {
                // tier column guides
                for (t in 0 until TechTree.tiers) {
                    drawRoundRect(Color(0x0FFFFFFF), Offset(pad + t * colW - 10f, pad - 50f), Size(nodeW + 20f, TechTree.rows * rowH + 60f), CornerRadius(30f))
                    drawText(tm, tierLabel(t), Offset(pad + t * colW, pad - 44f), TextStyle(color = Color(0x88FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                        maxLines = 1, size = Size(nodeW, 40f))
                }
                // edges
                for (n in TechTree.nodes) for (pid in n.prereqs) {
                    val p = TechTree.byId[pid] ?: continue
                    val sx = nodeX(p) + nodeW; val sy = nodeY(p) + nodeH / 2
                    val ex = nodeX(n); val ey = nodeY(n) + nodeH / 2
                    val path = Path().apply { moveTo(sx, sy); cubicTo(sx + colW * 0.3f, sy, ex - colW * 0.3f, ey, ex, ey) }
                    val owned = pid in save.tech
                    val col = when {
                        owned && n.id in save.tech -> Color(n.branch.color)
                        owned -> Color.White.copy(alpha = 0.75f)
                        else -> Color.White.copy(alpha = 0.16f)
                    }
                    drawPath(path, col, style = Stroke(width = if (owned) 5f else 3f))
                    drawCircle(col, 6f, Offset(ex, ey))
                }
                // nodes
                for (n in TechTree.nodes) {
                    val st = nodeState(save, n)
                    val x = nodeX(n); val y = nodeY(n)
                    val bc = Color(n.branch.color)
                    val fill = when (st) {
                        NodeState.OWNED -> bc.copy(alpha = 0.85f)
                        NodeState.AFFORDABLE -> Color(0xFF14204C)
                        NodeState.AVAILABLE -> Color(0xFF111A3C)
                        NodeState.LOCKED -> Color(0xFF0B1028)
                    }
                    if (st == NodeState.AFFORDABLE) drawRoundRect(bc.copy(alpha = 0.35f * pulse), Offset(x - 8f, y - 8f), Size(nodeW + 16f, nodeH + 16f), CornerRadius(26f))
                    drawRoundRect(fill, Offset(x, y), Size(nodeW, nodeH), CornerRadius(20f))
                    val border = when (st) { NodeState.OWNED -> Color.White.copy(alpha = 0.7f); NodeState.AFFORDABLE -> bc; NodeState.AVAILABLE -> bc.copy(alpha = 0.55f); NodeState.LOCKED -> Color(0x22FFFFFF) }
                    drawRoundRect(border, Offset(x, y), Size(nodeW, nodeH), CornerRadius(20f), style = Stroke(width = if (selected == n.id) 7f else 3f))
                    val dark = st == NodeState.OWNED
                    val titleCol = when { dark -> Color(0xFF0A0F24); st == NodeState.LOCKED -> Color(0x77FFFFFF); else -> Color.White }
                    drawText(tm, "${n.branch.icon} ${n.title}", Offset(x + 18f, y + 12f), TextStyle(color = titleCol, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        overflow = TextOverflow.Ellipsis, maxLines = 1, size = Size(nodeW - 30f, nodeH / 2))
                    val sub = when {
                        st == NodeState.OWNED -> "✓ researched"
                        programClosed(save, n) -> "🔒 land on ${programLandingNeeded(n)}"
                        st == NodeState.LOCKED -> "🔒 ${formatMoney(n.cost)}"
                        else -> formatMoney(n.cost)
                    }
                    val subCol = when (st) { NodeState.OWNED -> Color(0xCC0A0F24); NodeState.AFFORDABLE -> Color(0xFFFFD34D); NodeState.AVAILABLE -> Color(0xFFFF9A7A); NodeState.LOCKED -> Color(0x55FFFFFF) }
                    // explicit size: without it the text is measured against (canvas width - untransformed x), which is ~0
                    // for tier 3+ columns and made prices wrap one character per line
                    drawText(tm, sub, Offset(x + 18f, y + nodeH * 0.55f), TextStyle(color = subCol, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        overflow = TextOverflow.Ellipsis, maxLines = 1, size = Size(nodeW - 30f, nodeH * 0.42f))
                }
            }
        }

        // header
        Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButtonRound("←") { engine.openHangar() }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("TECH TREE", color = UiColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Text("${save.tech.size}/${TechTree.nodes.size} researched · pinch to zoom, drag to pan", color = UiColors.TextDim, fontSize = 11.sp)
                }
                MuteButton(save.soundOn) { engine.toggleSound() }
                Spacer(Modifier.width(8.dp))
                MoneyChip(save.money)
            }
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in Branch.entries) {
                    val owned = TechTree.nodes.count { it.branch == b && it.id in save.tech }
                    val total = TechTree.nodes.count { it.branch == b }
                    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(b.color).copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(b.color)))
                        Spacer(Modifier.width(5.dp))
                        Text("${b.title} $owned/$total", color = UiColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // detail panel
        val sel = selected?.let { TechTree.byId[it] }
        AnimatedVisibility(sel != null, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            if (sel != null) NodeDetail(engine, save, sel) { selected = null }
        }
        if (sel == null) {
            Text("Tap a node for details", color = UiColors.TextDim, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(20.dp).glass(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun NodeDetail(engine: GameEngine, save: SaveData, n: TechNode, onClose: () -> Unit) {
    val st = nodeState(save, n)
    val before = remember(save.tech) { RocketSpec(save.tech) }
    val after = remember(save.tech, n.id) { RocketSpec(save.tech + n.id) }
    Panel(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(n.branch.color).copy(alpha = 0.25f)), contentAlignment = Alignment.Center) { Text(n.branch.icon, fontSize = 20.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title, color = UiColors.Text, fontSize = 19.sp, fontWeight = FontWeight.Black)
                SectionLabel("${n.branch.title} · tier ${n.tier + 1}", color = Color(n.branch.color))
            }
            IconButtonRound("✕", onClick = onClose)
        }
        Text(n.desc, color = UiColors.TextDim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Effect")
                for ((s, v) in n.effects) Text("+$v ${statLabel(s)}", color = UiColors.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("On the rocket")
                Text(n.visual, color = UiColors.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (st != NodeState.OWNED) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatDelta("Δv", "%,.0f".format(before.idealDeltaV()), "%,.0f m/s".format(after.idealDeltaV()), after.idealDeltaV() > before.idealDeltaV() + 1, Modifier.weight(1f))
                StatDelta("TWR", "%.2f".format(before.liftoffTwr()), "%.2f".format(after.liftoffTwr()), after.liftoffTwr() > before.liftoffTwr() + 0.005, Modifier.weight(1f))
            }
        }
        if (n.prereqs.isNotEmpty()) {
            SectionLabel("Requires")
            Text(n.prereqs.joinToString("   ") { id -> (if (id in save.tech) "✓ " else "✗ ") + (TechTree.byId[id]?.title ?: id) }, color = UiColors.Text, fontSize = 12.sp)
        }
        when (st) {
            NodeState.OWNED -> Text("✓ Researched", color = UiColors.Good, fontWeight = FontWeight.Black)
            NodeState.AFFORDABLE -> GameButton("RESEARCH · ${formatMoney(n.cost)}", Modifier.fillMaxWidth()) { engine.research(n.id) }
            NodeState.AVAILABLE -> GameButton("NEED ${formatMoney(n.cost - save.money)} MORE", Modifier.fillMaxWidth(), enabled = false) {}
            NodeState.LOCKED -> GameButton(if (programClosed(save, n)) "🔒 LAND ON ${programLandingNeeded(n).uppercase()} FIRST" else "LOCKED · RESEARCH PREREQUISITES", Modifier.fillMaxWidth(), enabled = false) {}
        }
    }
}

@Composable
private fun StatDelta(label: String, from: String, to: String, better: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(from, color = UiColors.TextDim, fontSize = 13.sp)
            Text("  →  ", color = UiColors.TextDim, fontSize = 13.sp)
            Text(to, color = if (better) UiColors.Good else UiColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
