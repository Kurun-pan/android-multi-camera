package com.example.multicameraapi.models

data class CameraIdInfo(
    val logicalCameraId: String = "",
    val physicalCameraIds: List<String> = emptyList()
)