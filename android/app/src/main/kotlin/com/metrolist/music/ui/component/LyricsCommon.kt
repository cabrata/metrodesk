/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import kotlin.math.sin
import com.metrolist.music.lyrics.LyricsEntry

sealed class LyricsListItem {
    data class Line(val index: Int, val entry: LyricsEntry) : LyricsListItem()
    data class Indicator(
        val afterLineIndex: Int,
        val gapMs: Long,
        val gapStartMs: Long,
        val gapEndMs: Long,
        val nextAgent: String?
    ) : LyricsListItem()
}


/** Apple Music style interlude: three breathing dots that fill left to right across the gap. */
@Composable
internal fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionMs: Long,
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(visible) { appear.animateTo(if (visible) 1f else 0f, tween(400)) }

    val progress = if (gapEndMs > gapStartMs) {
        ((currentPositionMs - gapStartMs).toFloat() / (gapEndMs - gapStartMs)).coerceIn(0f, 1f)
    } else 0f
    val breathe = 1f + 0.12f * sin(currentPositionMs / 250f)

    Canvas(
        modifier
            .height(56.dp * appear.value)
            .graphicsLayer { alpha = appear.value }
    ) {
        if (appear.value <= 0f) return@Canvas
        val r = 7.dp.toPx() * breathe * appear.value
        val step = 24.dp.toPx()
        // Matches the lyric column's leading edge, like the reference renderer.
        repeat(3) { k ->
            val fill = (progress * 3 - k).coerceIn(0f, 1f)
            drawCircle(
                color = color.copy(alpha = 0.3f + 0.7f * fill),
                radius = r,
                center = Offset(9.dp.toPx() + k * step, size.height / 2),
            )
        }
    }
}
