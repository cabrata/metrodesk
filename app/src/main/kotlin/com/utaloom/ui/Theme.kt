package com.utaloom.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val DefaultSeed = Color(0xFF0A84FF)

internal fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    val hue = when {
        delta == 0f -> 0f
        max == r -> ((g - b) / delta + 6f) % 6f
        max == g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return floatArrayOf(hue * 60f, if (max == 0f) 0f else delta / max, max)
}

/** ponytail: HSV tones preserve the existing palette, use HCT only if exact M3 tones are required. */
fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val hsb = rgbToHsv(seed.red, seed.green, seed.blue)
    val h = hsb[0]
    val s = hsb[1].coerceIn(0.35f, 0.9f)
    fun tone(sat: Float, bri: Float, hue: Float = h) = Color.hsv(hue, sat.coerceIn(0f, 1f), bri.coerceIn(0f, 1f))
    val h2 = (h + 28.8f) % 360f
    val h3 = (h + 118.8f) % 360f
    return if (dark) darkColorScheme(
        primary = tone(s * 0.55f, 0.92f),
        onPrimary = tone(s, 0.22f),
        primaryContainer = tone(s * 0.8f, 0.38f),
        onPrimaryContainer = tone(s * 0.25f, 0.96f),
        secondary = tone(s * 0.3f, 0.82f, h2),
        onSecondary = tone(s * 0.5f, 0.2f, h2),
        secondaryContainer = tone(s * 0.4f, 0.3f, h2),
        onSecondaryContainer = tone(s * 0.15f, 0.92f, h2),
        tertiary = tone(s * 0.4f, 0.85f, h3),
        tertiaryContainer = tone(s * 0.5f, 0.32f, h3),
        onTertiaryContainer = tone(s * 0.15f, 0.94f, h3),
        background = tone(s * 0.25f, 0.075f),
        onBackground = tone(s * 0.06f, 0.92f),
        surface = tone(s * 0.25f, 0.075f),
        onSurface = tone(s * 0.06f, 0.92f),
        surfaceVariant = tone(s * 0.18f, 0.27f),
        onSurfaceVariant = tone(s * 0.1f, 0.8f),
        surfaceContainerLowest = tone(s * 0.25f, 0.05f),
        surfaceContainerLow = tone(s * 0.22f, 0.10f),
        surfaceContainer = tone(s * 0.2f, 0.12f),
        surfaceContainerHigh = tone(s * 0.18f, 0.155f),
        surfaceContainerHighest = tone(s * 0.16f, 0.2f),
        outline = tone(s * 0.1f, 0.58f),
        outlineVariant = tone(s * 0.15f, 0.3f),
        inverseSurface = tone(s * 0.06f, 0.9f),
        inverseOnSurface = tone(s * 0.2f, 0.18f),
    ) else lightColorScheme(
        primary = tone(s, 0.5f),
        onPrimary = Color.White,
        primaryContainer = tone(s * 0.3f, 0.97f),
        onPrimaryContainer = tone(s, 0.2f),
        secondary = tone(s * 0.35f, 0.45f, h2),
        onSecondary = Color.White,
        secondaryContainer = tone(s * 0.2f, 0.93f, h2),
        onSecondaryContainer = tone(s * 0.5f, 0.18f, h2),
        tertiary = tone(s * 0.5f, 0.45f, h3),
        tertiaryContainer = tone(s * 0.25f, 0.95f, h3),
        onTertiaryContainer = tone(s * 0.6f, 0.2f, h3),
        background = tone(s * 0.04f, 0.99f),
        onBackground = tone(s * 0.2f, 0.11f),
        surface = tone(s * 0.04f, 0.99f),
        onSurface = tone(s * 0.2f, 0.11f),
        surfaceVariant = tone(s * 0.1f, 0.9f),
        onSurfaceVariant = tone(s * 0.15f, 0.3f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = tone(s * 0.05f, 0.97f),
        surfaceContainer = tone(s * 0.07f, 0.95f),
        surfaceContainerHigh = tone(s * 0.08f, 0.93f),
        surfaceContainerHighest = tone(s * 0.09f, 0.9f),
        outline = tone(s * 0.1f, 0.48f),
        outlineVariant = tone(s * 0.1f, 0.8f),
        inverseSurface = tone(s * 0.2f, 0.19f),
        inverseOnSurface = tone(s * 0.05f, 0.95f),
    )
}

/** Picks the most vivid, reasonably common color from an image (cheap palette substitute). */
fun extractSeed(img: ImageBitmap): Color? {
    val px = img.toPixelMap()
    val buckets = HashMap<Int, FloatArray>() // hueBucket -> [count, score, r, g, b]
    val step = maxOf(1, minOf(px.width, px.height) / 48)
    for (y in 0 until px.height step step) for (x in 0 until px.width step step) {
        val c = px[x, y]
        val hsb = rgbToHsv(c.red, c.green, c.blue)
        if (hsb[1] < 0.2f || hsb[2] < 0.2f) continue
        val b = buckets.getOrPut((hsb[0] / 15f).toInt()) { FloatArray(5) }
        b[0] += 1f; b[1] += hsb[1] * hsb[2]; b[2] += c.red; b[3] += c.green; b[4] += c.blue
    }
    val best = buckets.values.maxByOrNull { it[1] } ?: return null
    if (best[0] < 4) return null
    return Color(best[2] / best[0], best[3] / best[0], best[4] / best[0])
}

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun UtaloomTheme(seed: Color, dark: Boolean, content: @Composable () -> Unit) {
    val target = schemeFromSeed(seed, dark)
    @Composable
    fun anim(c: Color) = animateColorAsState(c, tween(600)).value
    val scheme = target.copy(
        primary = anim(target.primary),
        primaryContainer = anim(target.primaryContainer),
        secondaryContainer = anim(target.secondaryContainer),
        background = anim(target.background),
        surface = anim(target.surface),
        surfaceContainer = anim(target.surfaceContainer),
        surfaceContainerLow = anim(target.surfaceContainerLow),
        surfaceContainerHigh = anim(target.surfaceContainerHigh),
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

val LyricStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp)
