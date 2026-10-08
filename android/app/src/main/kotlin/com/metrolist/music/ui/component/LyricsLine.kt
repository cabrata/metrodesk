/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.screens.settings.LyricsPosition
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.PI


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LyricsLine(
    index: Int,
    item: LyricsEntry,
    isSynced: Boolean,
    isActiveLine: Boolean,
    bgVisible: Boolean,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    currentPositionState: Long,
    lyricsOffset: Long,
    playerConnection: PlayerConnection,
    lyricsTextSize: Float,
    lyricsLineSpacing: Float,
    expressiveAccent: Color,
    lyricsTextPosition: LyricsPosition,
    respectAgentPositioning: Boolean,
    isAutoScrollEnabled: Boolean,
    displayedCurrentLineIndex: Int,
    romanizeAsMain: Boolean,
    enabledLanguages: List<String>,
    romanizeLyrics: Boolean,
    onSizeChanged: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    
    val itemModifier = modifier
        .fillMaxWidth()
        .onSizeChanged { onSizeChanged(it.height) }
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
        .background(if (isSelected && isSelectionModeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
        .padding(
            start = when (lyricsTextPosition) { LyricsPosition.LEFT, LyricsPosition.RIGHT -> 11.dp; LyricsPosition.CENTER -> 24.dp },
            end = when (lyricsTextPosition) { LyricsPosition.LEFT, LyricsPosition.RIGHT -> 11.dp; LyricsPosition.CENTER -> 24.dp },
            top = if (item.isBackground) 0.dp else 12.dp,
            bottom = if (item.isBackground) 2.dp else 12.dp // simplified gap logic
        )

    val agentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.Start
        respectAgentPositioning && item.agent == "v2" -> Alignment.End
        respectAgentPositioning && item.agent == "v1000" -> Alignment.CenterHorizontally
        item.isBackground -> Alignment.CenterHorizontally
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.Start
            LyricsPosition.CENTER -> Alignment.CenterHorizontally
            LyricsPosition.RIGHT -> Alignment.End
        }
    }
    
    val agentTextAlign = when {
        respectAgentPositioning && item.agent == "v1" -> TextAlign.Left
        respectAgentPositioning && item.agent == "v2" -> TextAlign.Right
        respectAgentPositioning && item.agent == "v1000" -> TextAlign.Center
        item.isBackground -> TextAlign.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> TextAlign.Left
            LyricsPosition.CENTER -> TextAlign.Center
            LyricsPosition.RIGHT -> TextAlign.Right
        }
    }

    Box(modifier = itemModifier, contentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.CenterStart
        respectAgentPositioning && item.agent == "v2" -> Alignment.CenterEnd
        item.isBackground -> Alignment.Center
        respectAgentPositioning && item.agent == "v1000" -> Alignment.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.CenterStart
            LyricsPosition.RIGHT -> Alignment.CenterEnd
            LyricsPosition.CENTER -> Alignment.Center
        }
    }) {
        @Composable
        fun LyricContent() {
            // render.js depth: inactive lines shrink to 0.97 and blur by distance (0.9px per line, max 4).
            val distance = if (isSynced && isAutoScrollEnabled && displayedCurrentLineIndex >= 0) abs(index - displayedCurrentLineIndex) else 0
            val lineScale by animateFloatAsState(
                if (isActiveLine || !isSynced) 1f else 0.97f,
                tween(550, easing = FastOutSlowInEasing),
                label = "lyricsLineScale",
            )
            val lineBlur by animateFloatAsState(
                // ponytail: lines >6 away are offscreen, so skip their blur layer to keep the GPU idle.
                if (isActiveLine || distance > 6) 0f else distance.coerceAtMost(4) * 0.9f,
                tween(350),
                label = "lyricsLineBlur",
            )
            Column(
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = lineScale
                    scaleY = lineScale
                    transformOrigin = when (agentTextAlign) {
                        TextAlign.Right -> TransformOrigin(1f, 0.5f)
                        TextAlign.Center -> TransformOrigin.Center
                        else -> TransformOrigin(0f, 0.5f)
                    }
                    // RenderEffect is API 31+. Older devices just skip the blur.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lineBlur > 0.1f) {
                        val px = lineBlur.dp.toPx()
                        renderEffect = BlurEffect(px, px, TileMode.Decal)
                    } else {
                        renderEffect = null
                    }
                },
                horizontalAlignment = agentAlignment,
            ) {
                // render.js: lit line is white, every other line a flat 0.32 white. Released lines fade out over 350ms.
                val focusedAlpha = PENDING_ALPHA
                val targetAlpha = if (!isSynced || isActiveLine) 1f else INACTIVE_ALPHA
                val animatedAlpha by animateFloatAsState(targetAlpha, tween(350), label = "lyricsLineAlpha")
                val lineColor = expressiveAccent.copy(alpha = animatedAlpha)
                
                val romanizedTextState by item.romanizedTextFlow.collectAsStateWithLifecycle()
                val isRomanizedAvailable = romanizedTextState != null
                val mainTextRaw = if (romanizeAsMain && isRomanizedAvailable) romanizedTextState else item.text
                val subTextRaw = if (romanizeAsMain && isRomanizedAvailable) item.text else romanizedTextState
                val mainText = if (item.isBackground) mainTextRaw?.removePrefix("(")?.removeSuffix(")") else mainTextRaw
                val subText = if (item.isBackground) subTextRaw?.removePrefix("(")?.removeSuffix(")") else subTextRaw

                val lyricStyle = TextStyle(
                    fontSize = if (item.isBackground) (lyricsTextSize * 0.7f).sp else lyricsTextSize.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = if (item.isBackground) FontStyle.Italic else FontStyle.Normal,
                    lineHeight = if (item.isBackground) (lyricsTextSize * 0.7f * lyricsLineSpacing).sp else (lyricsTextSize * lyricsLineSpacing).sp,
                    letterSpacing = (-0.5).sp,
                    textAlign = agentTextAlign,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )

                val effectiveWords = if (item.words?.isNotEmpty() == true) {
                    item.words
                } else if (mainText != null) {
                    remember(mainText, item.time) {
                        val words = mainText.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val wordDurationSec = 0.18
                        val wordStaggerSec = 0.03
                        val startTimeSec = item.time / 1000.0
                        words.mapIndexed { idx, wordText ->
                            WordTimestamp(
                                text = wordText,
                                startTime = startTimeSec + (idx * wordStaggerSec),
                                endTime = startTimeSec + (idx * wordStaggerSec) + wordDurationSec,
                                hasTrailingSpace = idx < words.size - 1
                            )
                        }
                    }
                } else null

                if (isSynced && effectiveWords != null && (isActiveLine || abs(index - displayedCurrentLineIndex) <= 3) && mainText != null) {
                    WordLevelLyrics(
                        mainText = mainText,
                        words = effectiveWords,
                        isActiveLine = isActiveLine,
                        currentPositionState = currentPositionState,
                        lyricsOffset = lyricsOffset,
                        playerConnection = playerConnection,
                        lyricStyle = lyricStyle,
                        lineColor = lineColor,
                        expressiveAccent = expressiveAccent,
                        isBackground = item.isBackground,
                        focusedAlpha = focusedAlpha,
                        alignment = agentTextAlign
                    )
                } else {
                    Text(
                        text = mainText ?: "",
                        style = lyricStyle.copy(color = if (isActiveLine) expressiveAccent else lineColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                if (romanizeLyrics && enabledLanguages.isNotEmpty()) {
                    subText?.let { 
                        Text(
                            text = it,
                            fontSize = 18.sp,
                            color = expressiveAccent.copy(alpha = 0.6f),
                            textAlign = agentTextAlign,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                
                val transText by item.translatedTextFlow.collectAsStateWithLifecycle()
                transText?.let { 
                    Text(
                        text = it,
                        fontSize = 16.sp,
                        color = expressiveAccent.copy(alpha = 0.5f),
                        textAlign = agentTextAlign,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (item.isBackground) {
            AnimatedVisibility(
                visible = bgVisible,
                enter = fadeIn(tween(durationMillis = 250, delayMillis = 100)),
                exit = fadeOut(tween(250))
            ) {
                LyricContent()
            }
        } else {
            LyricContent()
        }
    }
}

@Composable
private fun WordLevelLyrics(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    currentPositionState: Long,
    lyricsOffset: Long,
    playerConnection: PlayerConnection,
    lyricStyle: TextStyle,
    lineColor: Color,
    expressiveAccent: Color,
    isBackground: Boolean,
    focusedAlpha: Float,
    alignment: TextAlign
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val glowPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }

    var smoothPosition by remember { mutableLongStateOf(currentPositionState + lyricsOffset) }

    LaunchedEffect(isActiveLine) {
        if (isActiveLine) {
            var lastPlayerPos = playerConnection.player.currentPosition
            var lastUpdateTime = System.currentTimeMillis()
            while (isActive) {
                withFrameMillis {
                    val now = System.currentTimeMillis()
                    val playerPos = playerConnection.player.currentPosition
                    if (playerPos != lastPlayerPos) {
                        lastPlayerPos = playerPos
                        lastUpdateTime = now
                    }
                    val elapsed = now - lastUpdateTime
                    smoothPosition = lastPlayerPos + lyricsOffset + (if (playerConnection.player.isPlaying) elapsed else 0)
                }
            }
        }
    }

    LaunchedEffect(isActiveLine, currentPositionState) {
        if (!isActiveLine) smoothPosition = currentPositionState + lyricsOffset
    }

    // Character range of each word inside mainText (background lines have their parentheses stripped).
    val wordRanges = remember(mainText, words, isBackground) {
        var cursor = 0
        words.mapIndexed { idx, word ->
            var text = word.text
            if (isBackground) {
                if (idx == 0) text = text.removePrefix("(")
                if (idx == words.size - 1) text = text.removeSuffix(")")
            }
            val start = mainText.indexOf(text, cursor)
            if (start == -1 || text.isEmpty()) null else (start until start + text.length).also { cursor = it.last + 1 }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val layoutResult = remember(mainText, maxWidthPx, lyricStyle) {
            textMeasurer.measure(
                text = mainText,
                style = lyricStyle,
                constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx),
                softWrap = true
            )
        }
        // Bounding boxes per word, split per visual row so wrapped words wipe row by row.
        val wordBoxes = remember(layoutResult, wordRanges) {
            wordRanges.map { range ->
                if (range == null) return@map emptyList()
                range.groupBy { layoutResult.getLineForOffset(it) }.map { (line, offs) ->
                    val l = offs.minOf { layoutResult.getBoundingBox(it).left }
                    val r = offs.maxOf { layoutResult.getBoundingBox(it).right }
                    Rect(l, layoutResult.getLineTop(line), r, layoutResult.getLineBottom(line))
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { layoutResult.size.height.toDp() })
                .graphicsLayer(clip = false, compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            if (mainText.isEmpty()) return@Canvas
            if (!isActiveLine) {
                drawText(layoutResult, color = lineColor)
                return@Canvas
            }
            val pending = expressiveAccent.copy(alpha = PENDING_ALPHA)
            // Text outside any timed word (punctuation spacing etc.) stays at the pending level.
            drawText(layoutResult, color = pending)

            val liftPx = 3.dp.toPx()
            words.forEachIndexed { idx, word ->
                val boxes = wordBoxes[idx]
                if (boxes.isEmpty()) return@forEachIndexed
                val t0 = word.startTime * 1000
                val dur = ((word.endTime - word.startTime) * 1000).coerceAtLeast(50.0)
                val p = ((smoothPosition - t0) / dur).coerceIn(0.0, 1.0).toFloat()
                if (p <= 0f) return@forEachIndexed
                // render.js: words rise 3px as they are sung, eased out over max(0.25s, duration).
                val liftT = ((smoothPosition - t0) / maxOf(250.0, dur)).coerceIn(0.0, 1.0).toFloat()
                val lift = -liftPx * easeOut(liftT)
                // Long held syllables glow like Apple Music.
                val glow = if (dur > 1000 && p < 1f) sin(p * PI.toFloat()) else 0f

                val total = boxes.sumOf { it.width.toDouble() }.toFloat().coerceAtLeast(1f)
                var before = 0f
                boxes.forEach { box ->
                    // Erase the pending text under this box, then redraw it lifted and lit.
                    clipRect(box.left, box.top, box.right, box.bottom) {
                        drawRect(Color.Black, blendMode = BlendMode.Clear)
                    }
                    val local = ((p * total - before) / box.width).coerceIn(0f, 1f)
                    before += box.width
                    val edge = maxOf(10.dp.toPx(), box.width * 0.25f)
                    val gx = box.left - edge + (box.width + edge * 2) * local
                    val brush = when {
                        local >= 1f -> SolidColor(expressiveAccent)
                        local <= 0f -> SolidColor(pending)
                        else -> Brush.horizontalGradient(
                            0f to expressiveAccent, 1f to pending,
                            startX = gx - edge, endX = gx + edge,
                        )
                    }
                    if (glow > 0.01f) {
                        // Native blur-mask glow of just this word's slice, so neighbours never glow.
                        val line = layoutResult.getLineForVerticalPosition(box.center.y)
                        val start = layoutResult.getOffsetForPosition(Offset(box.left + 1f, box.center.y))
                        val end = layoutResult.getOffsetForPosition(Offset(box.right - 1f, box.center.y)) + 1
                        glowPaint.textSize = lyricStyle.fontSize.toPx()
                        glowPaint.maskFilter = BlurMaskFilter(18.dp.toPx() * glow, BlurMaskFilter.Blur.NORMAL)
                        glowPaint.color = expressiveAccent.copy(alpha = 0.55f * glow).toArgb()
                        drawIntoCanvas {
                            it.nativeCanvas.drawText(
                                mainText, start, end.coerceAtMost(mainText.length),
                                box.left, layoutResult.getLineBaseline(line) + lift, glowPaint,
                            )
                        }
                    }
                    clipRect(box.left, box.top - liftPx, box.right, box.bottom) {
                        translate(top = lift) {
                            drawText(layoutResult, brush = brush)
                        }
                    }
                }
            }
        }
    }
}

private const val PENDING_ALPHA = 0.38f
private const val INACTIVE_ALPHA = 0.32f

private fun easeOut(x: Float) = 1f - (1f - x).let { it * it * it }
