package io.github.bgavyus.lightningcamera.extensions

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.view.Surface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun CameraDevice.createCaptureSession(
    isHighSpeed: Boolean,
    surfaces: List<Surface>,
    handler: Handler,
): CameraCaptureSession = suspendCoroutine { continuation ->
    val callback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) =
            continuation.resume(session)

        override fun onConfigureFailed(session: CameraCaptureSession) =
            continuation.resumeWithException(RuntimeException())
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        @Suppress("DEPRECATION")
        if (isHighSpeed) {
            createConstrainedHighSpeedCaptureSession(surfaces, callback, handler)
        } else {
            createCaptureSession(surfaces, callback, handler)
        }
    } else {
        val mode = if (isHighSpeed) {
            SessionConfiguration.SESSION_HIGH_SPEED
        } else {
            SessionConfiguration.SESSION_REGULAR
        }

        val configuration = SessionConfiguration(
            mode,
            surfaces.map(::OutputConfiguration),
            handler::post,
            callback,
        )

        createCaptureSession(configuration)
    }
}
