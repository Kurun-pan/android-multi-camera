package com.example.multicameraapi.models

enum class State {
    ON_TEXTURE_SIZE_CHANGED, ON_TEXTURE_UPDATED, ON_TEXTURE_DESTROYED, ON_TEXTURE_AVAILABLE
}

data class SurfaceTextureInfo(val state: State, val width: Int = 0, val height: Int = 0)
