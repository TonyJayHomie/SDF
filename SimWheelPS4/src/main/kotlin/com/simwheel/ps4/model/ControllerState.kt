package com.simwheel.ps4.model

data class ControllerState(
    val steeringDeg: Float = 0f,
    val throttle: Float = 0f,
    val brake: Float = 0f,
    val zaxis: Float = 0f,
    val buttons: Map<Int, Boolean> = emptyMap()
)
