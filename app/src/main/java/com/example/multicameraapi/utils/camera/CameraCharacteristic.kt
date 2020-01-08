package com.example.multicameraapi.utils.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Size

fun CameraCharacteristics.isAutoExposureSupported(mode: Int): Boolean =
    isSupported(
        CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
        mode
    )

fun CameraCharacteristics.isContinuousAutoFocusSupported(): Boolean =
    isSupported(
        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

fun CameraCharacteristics.isAutoWhiteBalanceSupported(): Boolean =
    isSupported(
        CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        CameraCharacteristics.CONTROL_AWB_MODE_AUTO)

fun CameraCharacteristics.getCaptureSize(comparator: Comparator<Size>): Size {
    val map: StreamConfigurationMap =
        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(0, 0)
    return map.getOutputSizes(ImageFormat.JPEG)
        .asList()
        .maxWith(comparator) ?: Size(0, 0)
}

fun CameraCharacteristics.getVideoSize(aspectRatio: Float): Size {
    val map: StreamConfigurationMap =
        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(0, 0)
    return chooseOutputSize(map.getOutputSizes(MediaRecorder::class.java).asList(), aspectRatio)
}

fun CameraCharacteristics.getPreviewSize(aspectRatio: Float): Size {
    val map: StreamConfigurationMap =
        get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(0, 0)
    return chooseOutputSize(map.getOutputSizes(SurfaceTexture::class.java).asList(), aspectRatio)
}

private fun CameraCharacteristics.isSupported(modes: CameraCharacteristics.Key<IntArray>, mode: Int): Boolean {
    val ints = this.get(modes) ?: return false

    for (value in ints) {
        if (value == mode)
            return true
    }
    return false
}

private fun chooseOutputSize(sizes: List<Size>, aspectRatio: Float): Size {
    // landscape
    if (aspectRatio > 1.0f) {
        val size = sizes.firstOrNull {
            it.height == it.width * 9 / 16 && it.height < 1080
        }
        return size ?: sizes[0]
    }

    // portrait or square
    val potentials = sizes.filter { it.height.toFloat() / it.width.toFloat() == aspectRatio }
    return if(potentials.isNotEmpty()) {
        potentials.firstOrNull { it.height == 1080 || it.height == 720 } ?: potentials[0]
    }
    else {
        sizes[0]
    }
}
