/**
 * Use android / camera-samples
 * https://github.com/android/camera-samples/blob/master/Camera2BasicKotlin/Application/src/main/java/com/example/android/camera2basic/CompareSizesByArea.kt
 */
package com.example.multicameraapi.services

import android.util.Size
import java.lang.Long.signum

import java.util.Comparator

/**
 * Compares two `Size`s based on their areas.
 */
internal class CompareSizesByArea : Comparator<Size> {

    // We cast here to ensure the multiplications won't overflow
    override fun compare(lhs: Size, rhs: Size) =
            signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
}
