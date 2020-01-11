/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * Use android / camera-samples
 * https://github.com/android/camera-samples/blob/master/Camera2BasicKotlin/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.kt
 */
package com.example.multicameraapi.services

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
import android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.SparseIntArray
import android.view.OrientationEventListener
import android.view.Surface

import androidx.core.math.MathUtils.clamp
import com.example.multicameraapi.utils.camera.getCaptureSize
import com.example.multicameraapi.utils.camera.getPreviewSize
import com.example.multicameraapi.utils.camera.isAutoExposureSupported
import com.example.multicameraapi.utils.camera.isContinuousAutoFocusSupported
import com.example.multicameraapi.models.CameraIdInfo
import java.util.concurrent.*
import kotlin.math.floor

private const val TAG = "CAMERA"

private enum class State {
    PREVIEW,
    WAITING_LOCK,
    WAITING_PRE_CAPTURE,
    WAITING_NON_PRE_CAPTURE,
    TAKEN
}

val ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
}

interface ImageHandler {
    fun handleImage(image: Image): Runnable
}

interface OnFocusListener {
    fun onFocusStateChanged(focusState: Int)
}

private const val ZOOM_SCALE = 1.00

class Camera constructor(private val cameraManager: CameraManager) {

    companion object {
        // Make thread-safe Singleton
        @Volatile
        var instance: Camera? = null
            private set

        fun initInstance(cameraManager: CameraManager): Camera {
            val i = instance
            if (i != null)
                return i

            return synchronized(this) {
                val created = Camera(cameraManager)
                instance = created
                created
            }
        }
    }

    private val characteristics: CameraCharacteristics
    private val cameraId: String
    private lateinit var physicalCameraIds: Set<String>
    private val openLock = Semaphore(1)
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var focusListener: OnFocusListener? = null

    private var state = State.PREVIEW
    private var aeMode = CaptureRequest.CONTROL_AE_MODE_ON
    private var preAfState: Int? = null

    private val activeArraySize: Rect
    private var zoomValue: Double = ZOOM_SCALE
    val maxZoom: Double

    private var backgroundHandler: Handler? = null

    private var backgroundThread: HandlerThread? = null
    private var surfaces: List<Surface>? = null
    private var isClosed = true
    private var deviceRotation: Int = 0 // Device rotation is defined by Screen Rotation

    init {
        cameraId = setUpCameraId(manager = cameraManager)
        characteristics = cameraManager.getCameraCharacteristics(cameraId)
        activeArraySize = characteristics.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()

        maxZoom = characteristics.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble() ?: ZOOM_SCALE
        calculateZoomSize(manager = cameraManager)
        calculateActiveArraySize(manager = cameraManager)
        Log.d(TAG, "CameraID($cameraId) -> maxZoom $maxZoom")
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            openLock.release()
            isClosed = false
        }

        override fun onClosed(camera: CameraDevice) {
            isClosed = true
        }

        override fun onDisconnected(camera: CameraDevice) {
            openLock.release()
            camera.close()
            cameraDevice = null
            isClosed = true
        }

        override fun onError(camera: CameraDevice, error: Int) {
            openLock.release()
            camera.close()
            cameraDevice = null
            isClosed = true
        }
    }

    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {
            // TODO: handle error
        }

        override fun onConfigured(session: CameraCaptureSession) {
            if (isClosed)
                return

            captureSession = session
            startPreview()
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) {
            when (state) {
                State.PREVIEW -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
                    if (afState == preAfState)
                        return

                    preAfState = afState
                    focusListener?.onFocusStateChanged(afState)
                }

                State.WAITING_LOCK -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    // Auto Focus state is not ready in the first place
                    if (afState == null)
                        runPreCapture()
                    else if (CaptureResult.CONTROL_AF_STATE_INACTIVE == afState ||
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState
                    ) {
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED)
                            captureStillPicture()
                        else
                            runPreCapture()
                    }
                    else
                        captureStillPicture()
                }

                State.WAITING_PRE_CAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null
                        || aeState == CaptureRequest.CONTROL_AE_STATE_PRECAPTURE
                        || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                        || aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED
                    ) {
                        state = State.WAITING_NON_PRE_CAPTURE
                    }
                }

                State.WAITING_NON_PRE_CAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureRequest.CONTROL_AE_STATE_PRECAPTURE)
                        captureStillPicture()
                }
                else -> {
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }
    }

    // Camera interfaces
    /**
     * Open camera and setup background handler
     */
    fun open() {

        try {
            if (!openLock.tryAcquire(3L, TimeUnit.SECONDS)) {
                throw IllegalStateException("Camera launch failed")
            }

            if (cameraDevice != null) {
                openLock.release()
                return
            }

            startBackgroundHandler()

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        }
        catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    /**
     * Start camera. Should be called after open() is successful
     */
    fun start(surfaces: List<Surface>) {
        this.surfaces = surfaces
        if(surfaces.size > 1)
            startDualCamera(surfaces)
        else
            startSingleCamera(surfaces[0])
    }

    fun takePicture(handler: ImageHandler) {
        if (cameraDevice == null) {
            Log.e(TAG, "Camera device not ready")
            return
        }

        if (isClosed)
            return

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            backgroundHandler?.post(handler.handleImage(image = image))
        }, backgroundHandler)

        lockFocus()
    }

    fun close() {
        try {
            if (openLock.tryAcquire(3, TimeUnit.SECONDS))
                isClosed = true
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            surfaces?.forEach {
                it.release()
            }
            surfaces = null

            imageReader?.close()
            imageReader = null
            stopBackgroundHandler()
            zoomValue = ZOOM_SCALE
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error closing camera $e")
        } finally {
            openLock.release()
        }
    }

    fun getCameraIds(): CameraIdInfo = CameraIdInfo(cameraId, physicalCameraIds.toList())

    // Zooming

    /** Sets the digital zoom. Must be called while the preview is active.  */
    fun setZoom(zoomValue: Double) {
        if (zoomValue > maxZoom)
            Log.e(TAG, "out of bounds zoom")
        this.zoomValue = zoomValue

        try {
            captureSession?.stopRepeating()
            setCropRegion(requestBuilder, zoomValue)
            requestBuilder?.build()?.let {
                captureSession?.setRepeatingRequest(it, captureCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.w(TAG, e)
        }
    }

    /**
     * Focus manually
     * @param x touch X coordinate
     * @param y touch Y coordinate
     * @param width screen width
     * @param height screen height
     */
    fun manualFocus(x: Float, y: Float, width: Int, height: Int) {
        // captureSession can be null with Monkey tap
        if (captureSession == null || cameraDevice == null) {
            return
        }
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return

            // TODO: set current Surface by aquiring current cameraID that's been used
            //builder.addTarget(surface)

            val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val areaSize = 200

            if (rect == null) {
                return
            }

            val right = rect.right
            val bottom = rect.bottom
            val centerX = x.toInt()
            val centerY = y.toInt()
            // Adjust the point of focus in the screen
            val ll = (centerX * right - areaSize) / width
            val rr = (centerY * bottom - areaSize) / height

            val focusLeft = clamp(ll, 0, right)
            val focusBottom = clamp(rr, 0, bottom)
            val newRect = Rect(focusLeft, focusBottom, focusLeft + areaSize, focusBottom + areaSize)
            // Adjust focus area with metering weight
            val meteringRectangle = MeteringRectangle(newRect, 500)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRectangle))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

            // Request should be repeated to maintain preview focus
            captureSession?.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)

            // Trigger Focus
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            builder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            captureSession?.capture(builder.build(), captureCallback, backgroundHandler)
        } catch (e: IllegalStateException) {
        } catch (e: CameraAccessException) {
        }
    }

    /**
     * Retrieves the image orientation from the specified screen rotation.
     * Used to calculate bitmap image rotation
     */
    fun getImageOrientation(): Int {
        if (deviceRotation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return 0
        }
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(deviceRotation) + sensorOrientation + 270) % 360
    }

    fun getCaptureSize() = characteristics.getCaptureSize(CompareSizesByArea())

    fun getPreviewSize(aspectRatio: Float) = characteristics.getPreviewSize(aspectRatio)

    private fun startSingleCamera(surface: Surface) {
        val size = characteristics.getCaptureSize(CompareSizesByArea())
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        cameraDevice?.createCaptureSession(
            listOf(surface, imageReader?.surface),
            captureStateCallback,
            backgroundHandler
        )
    }

    private fun startDualCamera(surfaces: List<Surface>) {
        val outputConfigs = surfaces.mapIndexed { index, surface ->
            val physicalCameraId = physicalCameraIds.toList()[index]
            val config = OutputConfiguration(surface)
            config.setPhysicalCameraId(physicalCameraId)
            config
        }

        val executor = Executors.newCachedThreadPool()
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            executor,
            captureStateCallback
        )
        cameraDevice?.createCaptureSession(sessionConfig)
    }

    /**
     * Set up camera Id from id list
     */
    private fun setUpCameraId(manager: CameraManager): String {
        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            // Usually cameraId = 0 is logical camera, so we check that
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val isLogicalCamera = capabilities!!.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )

            if (isLogicalCamera) {
                this.physicalCameraIds = characteristics.physicalCameraIds
                return cameraId
            }
        }

        this.physicalCameraIds = manager.cameraIdList.toSet()
        return "0" // default Camera. Logical Camera is not supported
    }

    private fun calculateZoomSize(manager: CameraManager) {
        physicalCameraIds.forEach {
            val characteristics = manager.getCameraCharacteristics(it)
            Log.d(TAG, "zoom $it ${characteristics.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toDouble()}")
        }
    }

    private fun calculateActiveArraySize(manager: CameraManager) {
        physicalCameraIds.forEach {
            val characteristics = manager.getCameraCharacteristics(it)
            val activeArraySize = characteristics.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()
            Log.d(TAG, "width ${activeArraySize.width()} height ${activeArraySize.height()} $it")
        }
    }

    private fun startBackgroundHandler() {
        if (backgroundThread != null)
            return

        backgroundThread = HandlerThread("Camera-$cameraId").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundHandler() {
        backgroundThread?.quitSafely()
        try {
            // TODO: investigate why thread does not end when join is called
            // backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "stop background error $e")
        }
    }

    private fun startPreview() {
        try {
            if (!openLock.tryAcquire(1L, TimeUnit.SECONDS)) return
            if (isClosed) return

            state = State.PREVIEW
            requestBuilder = createPreviewRequestBuilder()
            surfaces?.forEach {
                requestBuilder?.addTarget(it)
            }
            requestBuilder?.build()?.let {
                captureSession?.setRepeatingRequest(it, captureCallback, backgroundHandler)
            }
        } catch (e1: IllegalStateException) {

        } catch (e2: CameraAccessException) {

        } catch (e3: InterruptedException) {

        } finally {
            openLock.release()
        }
    }

    @Throws(CameraAccessException::class)
    private fun createPreviewRequestBuilder(): CaptureRequest.Builder? {
        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        enableDefaultModes(builder)
        setCropRegion(builder, zoomValue)
        return builder
    }

    private fun enableDefaultModes(builder: CaptureRequest.Builder?) {
        builder?.apply {
            // Auto focus should be continuous for camera preview.
            // Use the same AE and AF modes as the preview.
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

            if (characteristics.isContinuousAutoFocusSupported()) {
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            } else {
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                )
            }

            if (characteristics.isAutoExposureSupported(aeMode)) {
                set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        }
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        try {
            state = State.WAITING_LOCK

            if (!characteristics.isContinuousAutoFocusSupported()) {
                // If continuous AF is not supported , start AF here
                requestBuilder?.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START
                )
            }
            requestBuilder?.build()?.let {
                captureSession?.capture(it, captureCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "lockFocus $e")
        }
    }

    private fun runPreCapture() {
        try {
            state = State.WAITING_PRE_CAPTURE
            requestBuilder?.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            requestBuilder?.build()?.let {
                captureSession?.capture(it, captureCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "runPreCapture $e")
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * [.captureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        state = State.TAKEN
        try {
            // This is the CaptureRequest.Builder that we use to take a picture.
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            enableDefaultModes(builder)
            builder?.addTarget(imageReader!!.surface)
            surfaces?.forEach {
                builder?.addTarget(it)
            }
            captureSession?.stopRepeating()
            captureSession?.capture(
                builder!!.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        // Once still picture is captured, ImageReader.OnImageAvailable gets called
                        // You can do completion task here
                    }
                },
                backgroundHandler
            )

        } catch (e: CameraAccessException) {
            Log.e(TAG, "captureStillPicture $e")
        }
    }

    private fun setCropRegion(builder: CaptureRequest.Builder?, zoom: Double) {
        builder?.let {
            val width = floor(activeArraySize.width() / zoom).toInt()
            val left = (activeArraySize.width() - width) / 2
            val height = floor(activeArraySize.height() / zoom).toInt()
            val top = (activeArraySize.height() - height) / 2
            Log.d(TAG, "crop region(left=$left, top=$top, right=${left + width}, bottom=${top + height}) zoom($zoom)")

            it.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(left, top, left + width, top + height)
            )
        }
    }
}
