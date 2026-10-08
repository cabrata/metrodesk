/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val Saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) })

/**
 * Apple Music style animated cover background.
 * A 12px cover keeps only its colors, and bilinear upscaling makes it soft without a blur pass.
 * One stretched base copy plus three orbiting, rotating copies give the slow flowing motion.
 * The clock only ticks while [animate] is true, so a paused or hidden player costs nothing.
 */
@Composable
fun AppleMeshBackground(
    thumbnailUrl: String?,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                // Motion is slow, so 30 fps is indistinguishable and halves the redraw cost.
                if (now - last >= 33_000_000L) {
                    t += (now - last) / 1_000_000_000f
                    last = now
                }
            }
        }
    }

    AnimatedContent(
        targetState = thumbnailUrl,
        transitionSpec = { fadeIn(tween(900)).togetherWith(fadeOut(tween(900))) },
        modifier = modifier.fillMaxSize().background(Color.Black),
        label = "appleMeshBackground",
    ) { url ->
        Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().graphicsLayer {
                // Smooths bilinear creases like render.js's blur pass. API < 31 just skips it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    renderEffect = BlurEffect(40.dp.toPx(), 40.dp.toPx(), TileMode.Clamp)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                val request = remember(url) { ImageRequest.Builder(context).data(url).size(12, 12).build() }
                val blob = max(maxWidth.value, maxHeight.value) * 2.6f
                val w = maxWidth.value
                val h = maxHeight.value
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.High,
                    colorFilter = Saturate,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.1f },
                )
                repeat(3) { k ->
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        colorFilter = Saturate,
                        modifier = Modifier
                            .requiredSize(blob.dp)
                            .graphicsLayer {
                                val a = t * (0.07f + k * 0.03f) + k * 2.1f
                                translationX = cos(a) * w * 0.28f * density
                                translationY = sin(a * 1.3f) * h * 0.28f * density
                                rotationZ = Math.toDegrees((a * if (k % 2 == 1) -1f else 1f).toDouble()).toFloat()
                                alpha = 0.75f
                            },
                    )
                }
            }
        }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        }
    }
}
