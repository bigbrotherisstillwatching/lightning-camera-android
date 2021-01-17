package io.github.bgavyus.lightningcamera.extensions

import android.graphics.SurfaceTexture
import android.os.Handler
import android.util.Size
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.callbackFlow

fun SurfaceTexture.setDefaultBufferSize(size: Size) = setDefaultBufferSize(size.width, size.height)

fun SurfaceTexture.updates(handler: Handler) = callbackFlow {
    setOnFrameAvailableListener({ sendBlocking(Unit) }, handler)
    awaitClose { setOnFrameAvailableListener(null) }
}
