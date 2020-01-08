package com.example.multicameraapi.services

/* Use googlearchive / android-Camera2Basic source code
 * https://github.com/googlearchive/android-Camera2Basic/blob/master/kotlinApp/Application/src/main/java/com/example/android/camera2basic/CompareSizesByArea.kt
 */
import android.util.Size
import java.lang.Long.signum

import java.util.Comparator

internal class CompareSizesByArea : Comparator<Size> {

    // We cast here to ensure the multiplications won't overflow
    override fun compare(lhs: Size, rhs: Size) =
            signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
}
