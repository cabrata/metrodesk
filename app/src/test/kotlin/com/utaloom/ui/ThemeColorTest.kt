package com.utaloom.ui

import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.math.abs

class ThemeColorTest {
    @Test
    fun hsvHandlesPrimariesGreysAndHueWrap() {
        listOf(
            Color.Black to floatArrayOf(0f, 0f, 0f),
            Color.White to floatArrayOf(0f, 0f, 1f),
            Color(0.5f, 0.5f, 0.5f) to floatArrayOf(0f, 0f, 0.5f),
            Color.Red to floatArrayOf(0f, 1f, 1f),
            Color.Green to floatArrayOf(120f, 1f, 1f),
            Color.Blue to floatArrayOf(240f, 1f, 1f),
            Color.Magenta to floatArrayOf(300f, 1f, 1f),
        ).forEach { (color, expected) ->
            val actual = rgbToHsv(color.red, color.green, color.blue)
            check(actual.zip(expected.toList()).all { (a, b) -> abs(a - b) < 0.003f })
        }
        for (r in 0..8) for (g in 0..8) for (b in 0..8) {
            val hsv = rgbToHsv(r / 8f, g / 8f, b / 8f)
            val color = Color.hsv(hsv[0], hsv[1], hsv[2])
            check(abs(color.red - r / 8f) < 0.005f && abs(color.green - g / 8f) < 0.005f && abs(color.blue - b / 8f) < 0.005f)
        }
        listOf(false, true).forEach { dark ->
            val scheme = schemeFromSeed(DefaultSeed, dark)
            check(scheme.primary.alpha == 1f && scheme.background.alpha == 1f)
        }
    }
}
