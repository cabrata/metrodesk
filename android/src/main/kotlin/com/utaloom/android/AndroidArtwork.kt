package com.utaloom.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.Image
import coil3.toBitmap

fun artworkBitmap(image: Image): ImageBitmap {
    val bitmap = image.toBitmap()
    return (if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else bitmap).asImageBitmap()
}
