package com.example.multicameraapi.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

import android.hardware.camera2.CameraManager

import com.example.multicameraapi.R
import com.example.multicameraapi.models.CameraIdInfo
import com.example.multicameraapi.models.State
import com.example.multicameraapi.services.Camera
import com.example.multicameraapi.listeners.SurfaceTextureWaiter

import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class MainFragment : Fragment() {

    companion object {
        private val TAG = MainFragment::class.java.toString()
        private const val FRAGMENT_TAG_DIALOG = "tag_dialog"
        private const val REQUEST_CAMERA_PERMISSION = 1000
        fun newInstance() = MainFragment()
    }

    private var camera: Camera? = null
    private lateinit var previewSize: Size

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        zoomBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            var progressValue = 0

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                this.progressValue = progress
                camera?.maxZoom?.let {
                    if(!camera0View.isAvailable || !camera1View.isAvailable)
                        return@let

                    val zoomValue = progressValue.toDouble() / seekBar.max * it
                    camera?.setZoom(zoomValue)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }
        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        camera = Camera.initInstance(manager)
        camera?.maxZoom?.let {
            val actualProgress = (100 / it).roundToInt()
            zoomBar.progress = actualProgress
        }
    }

    override fun onResume() {
        super.onResume()

        if (camera0View.isAvailable && camera1View.isAvailable) {
            openCamera(camera0View.width, camera0View.height)
            return
        }

        // wait for TextureView available
        val waiter0 = SurfaceTextureWaiter(camera0View)
        val waiter1 = SurfaceTextureWaiter(camera1View)
        GlobalScope.launch {
            val result0 = waiter0.textureIsReady()
            val result1 = waiter1.textureIsReady()

            if (result1.state != State.ON_TEXTURE_AVAILABLE)
                Log.e(TAG, "camera1View unexpected state = $result1.state")

            when (result0.state) {
                State.ON_TEXTURE_AVAILABLE -> {
                    withContext(Dispatchers.Main) {
                        openDualCamera(result0.width, result0.height)
                    }
                }
                State.ON_TEXTURE_SIZE_CHANGED -> {
                    withContext(Dispatchers.Main) {
                        val matrix = calculateTransform(result0.width, result0.height)
                        camera0View.setTransform(matrix)
                    }
                }
                else -> {
                    // do nothing.
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        camera?.close()
    }

    private fun requestCameraPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_CAMERA_PERMISSION
            )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray) {

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorMessageDialog.newInstance(getString(R.string.request_permission))
                    .show(childFragmentManager, FRAGMENT_TAG_DIALOG)
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun openCamera(width: Int, height: Int) {
        activity ?: return

        val permission = ContextCompat.checkSelfPermission(activity!!, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }

        try {
            camera?.let {
                // Usually preview size has to be calculated based on the sensor rotation using getImageOrientation()
                // so that the sensor rotation and image rotation aspect matches correctly.
                // In this sample app, we know that Pixel series has the 90 degrees of sensor rotation,
                // so we just consider that width/ height < 1, which means portrait.
                val aspectRatio: Float = width / height.toFloat()
                previewSize = it.getPreviewSize(aspectRatio)

                camera0View.setAspectRatio(previewSize.height, previewSize.width)

                val matrix = calculateTransform(width, height)
                camera0View.setTransform(matrix)
                it.open()

                val texture1 = camera0View.surfaceTexture
                texture1.setDefaultBufferSize(previewSize.width, previewSize.height)
                it.start(listOf(Surface(texture1)))

                updateCameraStatus(it.getCameraIds())
            }
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openDualCamera(width: Int, height: Int) {
        activity ?: return

        val permission = ContextCompat.checkSelfPermission(activity!!, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }

        try {
            camera?.let {
                // Usually preview size has to be calculated based on the sensor rotation using getImageOrientation()
                // so that the sensor rotation and image rotation aspect matches correctly.
                // In this sample app, we know that Pixel series has the 90 degrees of sensor rotation,
                // so we just consider that width/ height < 1, which means portrait.
                val aspectRatio: Float = width / height.toFloat()
                previewSize = it.getPreviewSize(aspectRatio)

                camera0View.setAspectRatio(previewSize.height, previewSize.width)
                camera1View.setAspectRatio(previewSize.height, previewSize.width)

                val matrix = calculateTransform(width, height)
                camera0View.setTransform(matrix)
                camera1View.setTransform(matrix)
                it.open()

                val texture0 = camera0View.surfaceTexture
                val texture1 = camera1View.surfaceTexture
                texture0.setDefaultBufferSize(previewSize.width, previewSize.height)
                texture1.setDefaultBufferSize(previewSize.width, previewSize.height)
                it.start(listOf(Surface(texture0), Surface(texture1)))

                updateCameraStatus(it.getCameraIds())
            }
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateCameraStatus(cameraIdInfo: CameraIdInfo) {
        val (logicalCameraId, physicalCameraIds) = cameraIdInfo

        if (logicalCameraId.isNotEmpty()) {
            tv_multiCameraSupport.text = "Yes"
            tv_logicalCamera.text = logicalCameraId
        }
        else {
            tv_multiCameraSupport.text = "No"
            tv_multiCameraSupport.setTextColor(Color.WHITE)
            tv_logicalCamera.text = "-"
        }

        if (physicalCameraIds.isNotEmpty()) {
            tv_physicalCamera.text = physicalCameraIds
                .asSequence()
                .map { s -> "$s" }
                .reduce { acc, s -> "$acc, $s" }
        }
        else
            tv_physicalCamera.text = "-"
    }

    private fun calculateTransform(viewWidth: Int, viewHeight: Int) : Matrix {
        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        return matrix
    }
}