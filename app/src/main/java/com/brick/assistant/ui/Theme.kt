package com.brick.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Colors come from the active palette (Settings -> Colors); the default is Brick's blue and amber.
private val P get() = com.brick.assistant.data.Palettes.current
val DavidColor: Color get() = Color(P.brick)            // Brick's main voice
val RobotColor: Color get() = Color(P.unit)             // the unit
val TermBlue: Color get() = Color(P.unit)               // prompt, active controls
val TermRed: Color get() = Color(P.alert)
val Ember: Color get() = lerp(DavidColor, TermRed, 0.4f)
val Ice: Color get() = lerp(RobotColor, Color.White, 0.55f)
val Navy: Color get() = Color(P.background)
val Ink: Color get() = lerp(Navy, Color.Black, 0.5f)
val Panel: Color get() = Color(P.panel)
val PanelHigh: Color get() = lerp(Panel, TextHigh, 0.04f)
val Hairline: Color get() = Color(P.line)
val TextHigh: Color get() = Color(P.text)
val TextMid: Color get() = Color(P.dim)

/** Accent that blends Brick's two voices. */
val BrickBrush: Brush get() = Brush.linearGradient(listOf(DavidColor, Ember, RobotColor))

/** Terminal panels have barely rounded corners. */
val TermShape = RoundedCornerShape(3.dp)

private fun schemeFor() = darkColorScheme(
    primary = TermBlue,
    onPrimary = Ink,
    primaryContainer = lerp(Navy, TermBlue, 0.25f),
    onPrimaryContainer = lerp(TermBlue, Color.White, 0.6f),
    secondary = DavidColor,
    onSecondary = Ink,
    secondaryContainer = lerp(Navy, DavidColor, 0.22f),
    onSecondaryContainer = lerp(DavidColor, Color.White, 0.6f),
    tertiary = RobotColor,
    onTertiary = Ink,
    background = Navy,
    onBackground = TextHigh,
    surface = Navy,
    onSurface = TextHigh,
    surfaceVariant = Panel,
    onSurfaceVariant = TextMid,
    surfaceContainer = PanelHigh,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    surfaceContainerLow = Panel,
    surfaceContainerLowest = Ink,
    outline = Hairline,
    outlineVariant = Hairline,
    error = TermRed,
)
private fun TextStyle.mono() = copy(fontFamily = FontFamily.Monospace)

private val type = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.mono(), displayMedium = t.displayMedium.mono(), displaySmall = t.displaySmall.mono(),
        headlineLarge = t.headlineLarge.mono(), headlineMedium = t.headlineMedium.mono(), headlineSmall = t.headlineSmall.mono(),
        titleLarge = t.titleLarge.mono().copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = t.titleMedium.mono().copy(fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.mono(),
        bodyLarge = t.bodyLarge.mono().copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.mono().copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = t.bodySmall.mono().copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = t.labelLarge.mono().copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        labelMedium = t.labelMedium.mono(),
        labelSmall = t.labelSmall.mono().copy(letterSpacing = 0.8.sp),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = TermShape,
    large = TermShape,
    extraLarge = TermShape,
)

@Composable
fun BrickTheme(content: @Composable () -> Unit) {
    // Reading the palette here recomposes the whole app when you pick another one.
    val palette = com.brick.assistant.data.Palettes.current
    val scheme = androidx.compose.runtime.remember(palette) { schemeFor() }
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}

/** Brand text: Brick's blue. */
fun TextStyle.brand(): TextStyle = copy(color = TermBlue)

/** Terminal panel: dark fill with a thin border (in [accent] when given). The [shape] is always squared off. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glass(shape: Shape = TermShape, accent: Color? = null, borderWidth: Dp = 1.dp): Modifier =
    this
        .clip(TermShape)
        .background(Panel.copy(alpha = 0.92f), TermShape)
        .border(borderWidth, accent?.copy(alpha = 0.55f) ?: Hairline, TermShape)

/** A terminal panel. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides TextHigh) {
        Column(modifier.glass(TermShape, accent), content = content)
    }
}

/** App background: near-black with faint CRT scanlines, a dim blue glow from the top and amber from the bottom. */
@Composable
fun BrickBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Navy)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(TermBlue.copy(alpha = 0.07f), Color.Transparent), Offset(size.width * 0.5f, 0f), size.height * 0.7f))
            drawRect(Brush.radialGradient(listOf(DavidColor.copy(alpha = 0.05f), Color.Transparent), Offset(size.width * 0.5f, size.height), size.height * 0.6f))
            val step = 3.dp.toPx()
            var y = 0f
            while (y < size.height) {
                drawLine(Color.Black.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
            // Vignette like a tube screen.
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), Offset(size.width / 2, size.height / 2), size.maxDimension * 0.75f))
        }
        content()
    }
}
