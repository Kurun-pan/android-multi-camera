package com.example.multicameraapi.views

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/**
 * A [TextureView] that can be adjusted to a specified aspect ratio.
 * refs: https://github.com/android/camera-samples/blob/master/Camera2BasicKotlin/Application/src/main/java/com/example/android/camera2basic/AutoFitTextureView.kt
 **/
class AutoFitTextureView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0)
            throw IllegalArgumentException("Size cannot be negative.")

        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (ratioWidth == 0 || ratioHeight == 0)
            setMeasuredDimension(width, height)
        else {
            if (width < height * ratioWidth / ratioHeight)
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            else
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
        }
    }
}
