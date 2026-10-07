package com.brandonwallace.rocketlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One visual language for every screen: dark glass panels, orange primary, cyan secondary, gold money. */
object UiColors {
    val Orange = Color(0xFFFF8A1F)
    val Gold = Color(0xFFFFD34D)
    val Cyan = Color(0xFF4FD1FF)
    val Good = Color(0xFF4ADE80)
    val Bad = Color(0xFFFF5A5A)
    val Text = Color(0xFFEAF0FF)
    val TextDim = Color(0xFF9AA8D6)
    val Glass = Color(0xD90A1230)
    val GlassLight = Color(0xB3182454)
    val Stroke = Color(0x33FFFFFF)
    val Ink = Color(0xFF1A0A00)
}

val AppScheme = darkColorScheme(primary = UiColors.Orange, onPrimary = UiColors.Ink, secondary = UiColors.Cyan, surface = Color(0xFF0E1638), background = Color(0xFF050814))

val PanelShape = RoundedCornerShape(22.dp)

fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape = PanelShape, stroke: Color = UiColors.Stroke) =
    this.clip(shape).background(Brush.verticalGradient(listOf(Color(0xE6101A42), Color(0xE6070D26)))).border(1.dp, stroke, shape)

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glass().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = UiColors.TextDim) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = modifier)
}

/** Primary/secondary button with the same shape, height and type everywhere. */
@Composable
fun GameButton(text: String, modifier: Modifier = Modifier, color: Color = UiColors.Orange, enabled: Boolean = true, filled: Boolean = true, height: Dp = 56.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val base = modifier.height(height).clip(shape)
    val styled = if (filled) base.background(if (enabled) Brush.verticalGradient(listOf(color, color.copy(alpha = 0.82f))) else Brush.verticalGradient(listOf(Color(0x44FFFFFF), Color(0x33FFFFFF))))
    else base.background(Color(0x22FFFFFF)).border(1.5.dp, if (enabled) color else UiColors.Stroke, shape)
    Box(styled.clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.6f), contentAlignment = Alignment.Center) {
        Text(text, color = if (filled && enabled) UiColors.Ink else if (enabled) color else UiColors.TextDim, fontSize = if (height >= 52.dp) 18.sp else 14.sp,
            fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
fun IconButtonRound(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(46.dp).glass(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = UiColors.Text, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

@Composable
fun MoneyChip(money: Long, modifier: Modifier = Modifier) {
    Row(modifier.glass(RoundedCornerShape(50), UiColors.Gold.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("💰", fontSize = 15.sp)
        Spacer(Modifier.width(6.dp))
        Text(com.brandonwallace.rocketlauncher.game.formatMoney(money), color = UiColors.Gold, fontWeight = FontWeight.Black, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = UiColors.Text) {
    Column(modifier.padding(vertical = 2.dp)) {
        SectionLabel(label)
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, left: String = "🌍", right: String = "🌕") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(left, fontSize = 15.sp)
        Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Brush.horizontalGradient(listOf(UiColors.Cyan, UiColors.Gold))))
        }
        Text(right, fontSize = 15.sp)
    }
}
