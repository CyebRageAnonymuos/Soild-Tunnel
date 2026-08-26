package com.soildtunnel.desktop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap

private val logoBitmap: ImageBitmap by lazy {
    val loader = Thread.currentThread().contextClassLoader
        ?: ResourcesKt::class.java.classLoader
    val stream = requireNotNull(loader.getResourceAsStream("drawable/ic_logo.png")) {
        "drawable/ic_logo.png not found on classpath"
    }
    stream.use { loadImageBitmap(it) }
}

@Composable
fun rememberLogoPainter(): Painter = BitmapPainter(logoBitmap)
