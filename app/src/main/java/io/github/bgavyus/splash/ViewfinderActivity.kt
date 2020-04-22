package io.github.bgavyus.splash

import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import io.github.bgavyus.splash.camera.CameraError
import io.github.bgavyus.splash.camera.CameraErrorType
import io.github.bgavyus.splash.camera.CameraListener
import io.github.bgavyus.splash.camera.HighSpeedCamera
import io.github.bgavyus.splash.common.*
import io.github.bgavyus.splash.detection.DetectionListener
import io.github.bgavyus.splash.detection.LightningDetector
import io.github.bgavyus.splash.permissions.PermissionGroup
import io.github.bgavyus.splash.permissions.PermissionsActivity
import io.github.bgavyus.splash.recording.HighSpeedRecorder
import io.github.bgavyus.splash.recording.StatefulMediaRecorder
import io.github.bgavyus.splash.storage.Storage
import io.github.bgavyus.splash.storage.VideoFile
import kotlinx.android.synthetic.main.activity_viewfinder.*
import java.io.IOException


class ViewfinderActivity : PermissionsActivity(), TextureView.SurfaceTextureListener,
    CameraListener, MediaRecorder.OnErrorListener, Thread.UncaughtExceptionHandler,
    DetectionListener {

    companion object {
        private val TAG = ViewfinderActivity::class.simpleName
    }

    private val releaseStack = ReleaseStack()

    private lateinit var recorder: StatefulMediaRecorder
    private lateinit var videoFile: VideoFile
    private lateinit var textureView: TextureView
    private lateinit var surface: Surface
    private lateinit var camera: HighSpeedCamera
    private lateinit var detector: LightningDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "Activity.onCreate(savedInstanceState = $savedInstanceState)")
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        setContentView(R.layout.activity_viewfinder)
        Thread.setDefaultUncaughtExceptionHandler(this)

        logState()
    }

    private fun logState() {
        Log.d(TAG, "Device Orientation: $deviceOrientation")
        Log.d(TAG, "Display FPS: ${windowManager.defaultDisplay.refreshRate}")
        Log.d(TAG, "Running in ${if (Storage.scoped) "scoped" else "legacy"} storage mode")
    }

    override fun onResume() {
        Log.d(TAG, "Activity.onResume")
        super.onResume()
        initPermissions()
    }

    private fun initPermissions() {
        if (!allPermissionsGranted()) {
            Log.i(TAG, "Requesting permissions")
            requestNonGrantedPermissions()
            return
        }

        onPermissionsAvailable()
    }

    override fun onPermissionDenied(group: PermissionGroup) {
        Log.d(TAG, "onPermissionDenied(group = $group)")

        finishWithMessage(
            when (group) {
                PermissionGroup.Camera -> R.string.error_camera_permission_not_granted
                PermissionGroup.Storage -> R.string.error_storage_permission_not_granted
            }
        )
    }

    override fun onAllPermissionsGranted() {
        Log.d(TAG, "onAllPermissionGranted")
        recreate()
    }

    private fun onPermissionsAvailable() {
        initCamera()
    }

    private fun initCamera() {
        try {
            HighSpeedCamera(listener = this).run {
                camera = this
                releaseStack.push(::release)
                onCameraAvailable()
            }
        } catch (error: CameraError) {
            onCameraError(error.type)
        }
    }

    private fun onCameraAvailable() {
        initSurfaceTexture()
    }

    private fun initSurfaceTexture() {
        texture_view.run {
            textureView = this

            if (isAvailable) {
                Log.d(TAG, "TextureView.isAvailable")
                onSurfaceTextureAvailable()
            } else {
                surfaceTextureListener = this@ViewfinderActivity
            }
        }
    }

    override fun onSurfaceTextureAvailable(
        surfaceTexture: SurfaceTexture?,
        width: Int,
        height: Int
    ) {
        Log.d(TAG, "onSurfaceTextureAvailable(width = $width, height = $height)")
        assert(surfaceTexture == textureView.surfaceTexture)
        textureView.surfaceTextureListener = null
        onSurfaceTextureAvailable()
    }

    private fun onSurfaceTextureAvailable() {
        initSurface()
    }

    private fun initSurface() {
        try {
            surface = Surface(textureView.surfaceTexture)
        } catch (_: Surface.OutOfResourcesException) {
            return finishWithMessage(R.string.error_out_of_resources)
        }

        onSurfaceAvailable()
    }

    private fun onSurfaceAvailable() {
        initDetector()
    }

    private fun initDetector() {
        detector = LightningDetector(textureView.bitmap, this).apply {
            releaseStack.push(::release)
        }

        onDetectorAvailable()
    }

    private fun onDetectorAvailable() {
        initVideoFile()
    }

    private fun initVideoFile() {
        try {
            videoFile = VideoFile(getString(R.string.video_folder_name)).apply {
                releaseStack.push(::close)
            }
        } catch (_: IOException) {
            return finishWithMessage(R.string.error_io)
        }

        onVideoFileAvailable()
    }

    private fun onVideoFileAvailable() {
        initRecorder()
    }

    private fun initRecorder() {
        val rotation = camera.sensorOrientation + deviceOrientation
        recorder = HighSpeedRecorder(videoFile, camera.videoSize, camera.fpsRange, rotation).apply {
            setOnErrorListener(this@ViewfinderActivity)
            releaseStack.push(::release)
        }

        onRecorderAvailable()
    }

    private fun onRecorderAvailable() {
        stream()
    }

    private fun stream() {
        try {
            camera.stream()
        } catch (error: CameraError) {
            onCameraError(error.type)
        }
    }

    override fun onSurfacesNeeded(): List<Surface> {
        setSurfaceTextureSize()
        applyTransform()
        return listOf(surface, recorder.surface)
    }

    override fun onStreaming() {
        initSurfaceTextureListener()
    }

    private fun initSurfaceTextureListener() {
        textureView.run {
            surfaceTextureListener = this@ViewfinderActivity
            releaseStack.push {
                Log.d(TAG, "Removing surfaceTextureListener")
                surfaceTextureListener = null
            }
        }
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture?) {
        onFrameAvailable()
    }

    private fun onFrameAvailable() {
        textureView.getBitmap(detector.bitmap)
        detector.process()
    }

    override fun onSubjectEnter() {
        record()
    }

    private fun record() {
        Log.i(TAG, "Recording")

        if (recorder.prepared) {
            Log.d(TAG, "Recorder is starting")
            recorder.start()
        } else {
            recorder.resume()
        }
    }

    override fun onSubjectExit() {
        lose()
    }

    private fun lose() {
        Log.i(TAG, "Losing")
        recorder.pause()
    }

    override fun onSurfaceTextureSizeChanged(
        surfaceTexture: SurfaceTexture?,
        width: Int,
        height: Int
    ) {
        Log.d(TAG, "onSurfaceTextureSizeChanged(width = $width, height = $height)")
        assert(surfaceTexture == textureView.surfaceTexture)
        setSurfaceTextureSize()
        applyTransform()
    }

    private fun setSurfaceTextureSize() {
        if (!textureView.isShown) {
            Log.w(TAG, "Texture view is hidden while setting surface size")
        }

        camera.videoSize.run {
            textureView.surfaceTexture.setDefaultBufferSize(width, height)
        }
    }

    private fun applyTransform() {
        val viewSize = Size(textureView.width, textureView.height)
        val matrix = getTransformMatrix(viewSize, camera.videoSize, deviceOrientation)
        textureView.setTransform(matrix)
    }

    override fun onCameraError(type: CameraErrorType) {
        finishWithMessage(
            when (type) {
                CameraErrorType.HighSpeedNotAvailable -> R.string.error_high_speed_camera_not_available
                CameraErrorType.InUse -> R.string.error_camera_in_use
                CameraErrorType.MaxInUse -> R.string.error_max_cameras_in_use
                CameraErrorType.Disabled -> R.string.error_camera_disabled
                CameraErrorType.Device -> R.string.error_camera_device
                CameraErrorType.Disconnected -> R.string.error_camera_disconnected
                CameraErrorType.ConfigureFailed -> R.string.error_camera_generic
                CameraErrorType.Generic -> R.string.error_camera_generic
            }
        )
    }

    override fun onError(mr: MediaRecorder?, what: Int, extra: Int) {
        Log.d(TAG, "MediaRecorder.onError(what = $what, extra = $extra)")
        finishWithMessage(R.string.error_recorder)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture?): Boolean {
        Log.d(TAG, "onSurfaceTextureDestroyed")
        assert(surfaceTexture == textureView.surfaceTexture)
        return true
    }

    override fun onPause() {
        Log.d(TAG, "Activity.onPause")
        super.onPause()
        release()
    }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        Log.e(TAG, "Uncaught Exception from ${thread.name}", error)
        finishWithMessage(R.string.error_uncaught)
    }

    private fun finishWithMessage(resourceId: Int) {
        Log.d(TAG, "finishWithMessage: ${getDefaultString(resourceId)}")
        showMessage(resourceId)
        finish()
    }

    override fun finish() {
        Log.d(TAG, "finish")
        super.finish()
        release()
    }

    private fun release() {
        releaseStack.release()
    }
}
