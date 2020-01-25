package io.github.bgavyus.splash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_viewfinder.*
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max


class ViewfinderActivity : Activity(), TextureView.SurfaceTextureListener, MediaRecorder.OnErrorListener {

	companion object {
		private val TAG = ViewfinderActivity::class.simpleName

		private const val REQUEST_PERMISSIONS_CODE = 0
		private const val VIDEO_ENCODER = MediaRecorder.VideoEncoder.H264
		private const val VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
		private const val VIDEO_OUTPUT_FORMAT = MediaRecorder.OutputFormat.MPEG_4
		private const val VIDEO_FILE_EXTENSION = "mp4"
		private const val VIDEO_PLAYBACK_FPS = 5
	}

	private val mOnReleaseCallbacks = ArrayDeque<() -> Unit>()

	private lateinit var mRecorder: StatefulMediaRecorder
	private lateinit var mRecorderSurface: Surface
	private lateinit var mVideoFile: MediaStoreFile
	private lateinit var mTextureView: TextureView
	private lateinit var mViewfinderSurface: Surface
	private lateinit var mFpsRange: Range<Int>
	private lateinit var mVideoSize: Size
	private lateinit var mDetector: LightningDetector

    override fun onCreate(savedInstanceState: Bundle?) {
		Log.d(TAG, "Activity.onCreate(savedInstanceState = $savedInstanceState)")
		super.onCreate(savedInstanceState)
		window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
		setContentView(R.layout.activity_viewfinder)
	}

	override fun onResume() {
		Log.d(TAG, "Activity.onResume")
		super.onResume()
		prepare()
	}

	private fun prepare() {
		if (!cameraPermissionsGranted()) {
			Log.i(TAG, "Requesting camera permission")
			requestCameraPermission()
			return
		}

		onPermissionGranted()
	}

	private fun cameraPermissionsGranted(): Boolean {
		return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
	}

	private fun requestCameraPermission() {
		requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_PERMISSIONS_CODE)
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		Log.d(TAG, "onRequestPermissionsResult(requestCode = $requestCode, permissions = $permissions, grantResults = $grantResults)")
		assert(requestCode == REQUEST_PERMISSIONS_CODE)
		assert(permissions.contentEquals(arrayOf(Manifest.permission.CAMERA)))

		if (grantResults.isEmpty() || grantResults.first() != PackageManager.PERMISSION_GRANTED) {
			return finishWithMessage(R.string.error_camera_permission_not_granted)
		}

		finish()
	}

	private fun onPermissionGranted() {
		mRecorder = StatefulMediaRecorder().apply { registerOnReleaseCallback(::release) }
		mRecorderSurface = MediaCodec.createPersistentInputSurface().apply { registerOnReleaseCallback(::release) }
		prepareTextureView()
		prepareCamera()
	}

	@SuppressLint("Recycle")
	private fun prepareTextureView() {
		mTextureView = viewfinder_texture_view

		try {
			mTextureView.surfaceTexture = SurfaceTexture(false).apply {
				registerOnReleaseCallback(::release)
				mViewfinderSurface = Surface(this).apply {
					registerOnReleaseCallback(::release)
				}
			}
		}

		catch (_: Surface.OutOfResourcesException) {
			return finishWithMessage(R.string.error_out_of_resources)
		}
	}

	@SuppressLint("MissingPermission")
	private fun prepareCamera() {
		val cameraManager = getSystemService(CameraManager::class.java)
			?: return finishWithMessage(R.string.error_camera_generic)

		try {
			val cameraId = getHighSpeedCameraId(cameraManager)
				?: return finishWithMessage(R.string.error_high_speed_camera_not_available)
			val characteristics = cameraManager.getCameraCharacteristics(cameraId)
			val configs = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
				?: return finishWithMessage(R.string.error_camera_generic)

			mFpsRange = configs.highSpeedVideoFpsRanges.maxBy { it.lower + it.upper }
				?: return finishWithMessage(R.string.error_high_speed_camera_not_available)

			Log.i(TAG, "FPS Range: $mFpsRange")

			mVideoSize = configs.getHighSpeedVideoSizesFor(mFpsRange).maxBy { it.width * it.height }
				?: return finishWithMessage(R.string.error_high_speed_camera_not_available)

			Log.i(TAG, "Video Size: $mVideoSize")

			cameraManager.openCamera(cameraId, cameraDeviceStateCallback, null)
		}

		catch (error: CameraAccessException) {
			return finishWithMessage(cameraAccessExceptionToResourceId(error))
		}
	}

	private fun getHighSpeedCameraId(cameraManager: CameraManager): String? {
		return cameraManager.cameraIdList.firstOrNull {
			val characteristics = cameraManager.getCameraCharacteristics(it)
			val capabilities = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
				?: return@firstOrNull false

			return@firstOrNull CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in capabilities
		}
	}

	private val cameraDeviceStateCallback = object : CameraDevice.StateCallback() {
		override fun onOpened(camera: CameraDevice) {
			Log.d(TAG, "CameraDevice.onOpened")
			registerOnReleaseCallback(camera::close)

			if (!mTextureView.isShown) {
				Log.d(TAG, "Not shown while in onOpened")
				return
			}

			mDetector = LightningDetector(this@ViewfinderActivity, mTextureView, mVideoSize).apply { registerOnReleaseCallback(::release) }

			mTextureView.surfaceTextureListener = this@ViewfinderActivity
			registerOnReleaseCallback {
				Log.d(TAG, "Removing surfaceTextureListener")
				mTextureView.surfaceTextureListener = null
			}

			setupRecorder()
			setViewfinderSize()

			try {
				camera.createConstrainedHighSpeedCaptureSession(listOf(mViewfinderSurface, mRecorderSurface), cameraCaptureSessionStateCallback, null)
			}

			catch (error: CameraAccessException) {
				return finishWithMessage(cameraAccessExceptionToResourceId(error))
			}
		}

		override fun onDisconnected(camera: CameraDevice) {
			Log.d(TAG, "CameraDevice.onDisconnected")
			camera.close()
		}

		override fun onError(camera: CameraDevice, error: Int) {
			Log.d(TAG, "CameraDevice.onError(error = $error)")

			val resourceId = when (error) {
				ERROR_CAMERA_IN_USE      -> R.string.error_camera_in_use
				ERROR_MAX_CAMERAS_IN_USE -> R.string.error_max_cameras_in_use
				ERROR_CAMERA_DISABLED    -> R.string.error_camera_disabled
				ERROR_CAMERA_DEVICE      -> R.string.error_camera_device
				else                     -> R.string.error_camera_generic
			}

			finishWithMessage(resourceId)
		}
	}

	private fun setupRecorder() {
		val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

		mVideoFile = MediaStoreFile(contentResolver,
			mode = "w",
			mimeType = VIDEO_MIME_TYPE,
			baseUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
			relativePath = Paths.get(Environment.DIRECTORY_MOVIES, getString(R.string.video_folder_name)).toString(),
			name = "${getString(R.string.video_file_prefix)}_$timestamp.$VIDEO_FILE_EXTENSION")

		mRecorder.apply {
			setOnErrorListener(this@ViewfinderActivity)
			setVideoSource(MediaRecorder.VideoSource.SURFACE)
			setOutputFormat(VIDEO_OUTPUT_FORMAT)
			setInputSurface(mRecorderSurface)
			setVideoEncoder(VIDEO_ENCODER)
			setVideoSize(mVideoSize.width, mVideoSize.height)
			setOutputFile(mVideoFile.fileDescriptor)
			setCaptureRate(mFpsRange.upper.toDouble())
			setVideoEncodingBitRate(Int.MAX_VALUE)
			setVideoFrameRate(VIDEO_PLAYBACK_FPS)
			setOrientationHint(displayToVideoRotation(windowManager.defaultDisplay.rotation))
			prepare()
			registerOnReleaseCallback(::release)
		}

		registerOnReleaseCallback {
			Log.d(TAG, "Releasing video file")

			if (mRecorder.state == RecorderState.Prepared) {
				Log.d(TAG, "Recorder state is Prepared")
				mVideoFile.close()

				Log.i(TAG, "Deleting empty capture file")
				mVideoFile.delete()

				return@registerOnReleaseCallback
			}

			try {
				mRecorder.stop()
				mVideoFile.close()
			}

			catch (_: RuntimeException) {
				Log.d(TAG, "MediaRecorder.stop RuntimeException")
				mVideoFile.close()

				Log.i(TAG, "Deleting corrupted capture file")
				mVideoFile.delete()
			}
		}
	}

	private fun displayToVideoRotation(displayRotation: Int): Int {
		return when (displayRotation) {
			Surface.ROTATION_0   -> 90
			Surface.ROTATION_90  -> 0
			Surface.ROTATION_180 -> 270
			Surface.ROTATION_270 -> 180
			else -> throw RuntimeException("Invalid display rotation")
		}
	}

	private fun cameraAccessExceptionToResourceId(error: CameraAccessException): Int {
		return when (error.reason) {
			CameraAccessException.CAMERA_IN_USE      -> R.string.error_camera_in_use
			CameraAccessException.MAX_CAMERAS_IN_USE -> R.string.error_max_cameras_in_use
			CameraAccessException.CAMERA_DISABLED    -> R.string.error_camera_disabled
			CameraAccessException.CAMERA_ERROR       -> R.string.error_camera_device
			else                                     -> R.string.error_camera_generic
		}
	}
	private val cameraCaptureSessionStateCallback = object : CameraCaptureSession.StateCallback() {
		override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
			Log.d(TAG, "CameraCaptureSession.onConfigured")

			if (!mTextureView.isShown) {
				Log.d(TAG, "Not shown while in onConfigured")
				return
			}

			setViewfinderSize()
			applyTransform()
			startCaptureSession(cameraCaptureSession as CameraConstrainedHighSpeedCaptureSession)
		}

		override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
			Log.d(TAG, "CameraCaptureSession.onConfigureFailed")
		}
	}

	private fun startCaptureSession(captureSession: CameraConstrainedHighSpeedCaptureSession) {
		captureSession.apply {
			setRepeatingBurst(createHighSpeedRequestList(
				device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
					set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mFpsRange)
					addTarget(mViewfinderSurface)
					addTarget(mRecorderSurface)
				}.build()), null, null)

			registerOnReleaseCallback(::close)
		}
	}

	private fun registerOnReleaseCallback(cb: () -> Unit) {
		mOnReleaseCallbacks.push(cb)
	}

	override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture?) {
		handleFrame()
	}

	private fun handleFrame() {
		if (mDetector.hasLightning()) {
			if (mRecorder.state != RecorderState.Recording) {
				Log.i(TAG, "Starting recording")

				if (mRecorder.state == RecorderState.Prepared) {
					Log.d(TAG, "Recorder state is Prepared")
					mRecorder.start()
				}

				else {
					mRecorder.resume()
				}
			}
		}

		else if (mRecorder.state == RecorderState.Recording) {
			Log.i(TAG, "Pausing recording")
			mRecorder.pause()
		}
	}

	override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture?, width: Int, height: Int) {
		Log.d(TAG, "onSurfaceTextureSizeChanged(width = $width, height = $height)")
		assert(surfaceTexture == mTextureView.surfaceTexture)
		setViewfinderSize()
		applyTransform()
	}

	private fun setViewfinderSize() {
		mTextureView.surfaceTexture.setDefaultBufferSize(mVideoSize.width, mVideoSize.height)
	}

	private fun applyTransform() {
		val viewSize = Size(mTextureView.width, mTextureView.height)
		val matrix = getTransformMatrix(viewSize, mVideoSize, windowManager.defaultDisplay.rotation)
		mTextureView.setTransform(matrix)
	}

	private fun getTransformMatrix(viewSize: Size, bufferSize: Size, rotation: Int): Matrix {
		val matrix = Matrix()
		val viewRect = RectF(0f, 0f, viewSize.width.toFloat(), viewSize.height.toFloat())
		val bufferRect = RectF(0f, 0f, bufferSize.height.toFloat(), bufferSize.width.toFloat())
		val centerX = viewRect.centerX()
		val centerY = viewRect.centerY()

		if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
			matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
			val scale: Float = max(viewSize.height.toFloat() / bufferSize.height, viewSize.width.toFloat() / bufferSize.width)
			matrix.postScale(scale, scale, centerX, centerY)
			matrix.postRotate(90f * (rotation - 2), centerX, centerY)
		}

		else if (Surface.ROTATION_180 == rotation) {
			matrix.postRotate(180f, centerX, centerY)
		}

		return matrix
	}

	override fun onError(mr: MediaRecorder?, what: Int, extra: Int) {
		Log.d(TAG, "MediaRecorder.onError(what = $what, extra = $extra)")
		finishWithMessage(R.string.error_recorder)
	}

	override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
		Log.d(TAG, "onSurfaceTextureSizeChanged(width = $width, height = $height)")
	}

	override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
		Log.d(TAG, "onSurfaceTextureDestroyed")
		return true
	}

	private fun finishWithMessage(resourceId: Int) {
		Log.d(TAG, "finishWithMessage: ${getDefaultString(resourceId)}")

		Toast.makeText(this, resourceId, Toast.LENGTH_LONG).apply {
			setGravity(Gravity.CENTER, 0, 0)
			show()
		}

		finish()
	}

	private fun getDefaultString(resourceId: Int): String {
		return createConfigurationContext(Configuration().apply { setLocale(Locale.ROOT) }).getString(resourceId)
	}

	override fun finish() {
		Log.d(TAG, "Activity.finish")
		super.finish()
		release()
	}

	override fun onPause() {
		Log.d(TAG, "Activity.onPause")
		super.onPause()
		release()
	}

	private fun release() {
		while (!mOnReleaseCallbacks.isEmpty()) {
			val callback = mOnReleaseCallbacks.pop()
			Log.d(TAG, "ReleaseCallback: $callback")

			try {
				callback.invoke()
			}

			catch (error: Throwable) {
				Log.w(TAG, "Exception while destroying view", error)
			}
		}
	}
}
