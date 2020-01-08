package com.example.multicameraapi.views.listeners

import android.graphics.SurfaceTexture
import android.view.TextureView

import com.example.multicameraapi.models.State
import com.example.multicameraapi.models.SurfaceTextureInfo
import com.example.multicameraapi.views.AutoFitTextureView

import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class SurfaceTextureWaiter(private val textureView: AutoFitTextureView) {

    suspend fun textureIsReady(): SurfaceTextureInfo =
        suspendCoroutine { cont ->
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
                    //cont.resume(SurfaceTextureInfo(State.ON_TEXTURE_SIZE_CHANGED, width, height))
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
                    //cont.resume(SurfaceTextureInfo(State.ON_TEXTURE_UPDATED))
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
                    //cont.resume(SurfaceTextureInfo(State.ON_TEXTURE_DESTROYED))
                    return false
                }

                override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                    cont.resume(SurfaceTextureInfo(State.ON_TEXTURE_AVAILABLE, width, height))
                }
            }
        }
}