package com.utaloom.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.Image
import coil3.toBitmap

/** Android supplies the same hook using Bitmap.asImageBitmap(). */
fun artworkBitmap(image: Image): ImageBitmap = image.toBitmap().asComposeImageBitmap()
