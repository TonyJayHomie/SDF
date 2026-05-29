package com.simwheel.ps4.controller

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.simwheel.ps4.model.ButtonMapping
import com.simwheel.ps4.model.CalibrationSettings
import kotlin.math.abs

class PS4Controller(private val calib: CalibrationSettings) {

    var steeringDeg: Float = 0f
        private set

    var throttle: Float = 0f
        private set

    var brake: Float = 0f
        private set

    // Active button states: ps4KeyCode → pressed
    private val buttonStates = mutableMapOf<Int, Boolean>()

    // Gyro smoothing state
    private var filteredGyroZ: Float = 0f
    private var lastTimestampNs: Long = 0L

    var calibration: CalibrationSettings = calib

    var mappings: List<ButtonMapping> = emptyList()

    // Called from onGenericMotionEvent in Activity
    fun processMotion(event: MotionEvent): Map<Int, Boolean> {
        if (event.source and InputDevice.SOURCE_JOYSTICK == 0 &&
            event.source and InputDevice.SOURCE_GAMEPAD == 0) return emptyMap()

        val nowNs = System.nanoTime()
        val dt = if (lastTimestampNs == 0L) 0.016f
                 else ((nowNs - lastTimestampNs) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        lastTimestampNs = nowNs

        // PS4 gyro Z-axis (yaw) — raw radians/s via AXIS_RZ
        val rawGyroZ = event.getAxisValue(MotionEvent.AXIS_RZ)

        // Low-pass filter: alpha = (100 - filter) / 100
        val alpha = (100 - calibration.gyroFilter) / 100f
        filteredGyroZ = filteredGyroZ + alpha * (rawGyroZ - filteredGyroZ)

        // Convert rad/s → deg/s, accumulate
        val degPerSec = filteredGyroZ * (180f / Math.PI.toFloat()) * calibration.sensitivity
        steeringDeg += degPerSec * dt
        steeringDeg = steeringDeg.coerceIn(-calibration.steeringRange, calibration.steeringRange)

        // Analog triggers — full precision, apply dead zone
        val rawRt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        val rawLt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        throttle = applyDeadZone(rawRt, calibration.triggerDeadZone)
        brake    = applyDeadZone(rawLt, calibration.triggerDeadZone)

        // Hat/D-pad as button events
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val dpadChanges = mutableMapOf<Int, Boolean>()
        updateDpad(KeyEvent.KEYCODE_DPAD_LEFT,  hatX < -0.5f, dpadChanges)
        updateDpad(KeyEvent.KEYCODE_DPAD_RIGHT, hatX > 0.5f,  dpadChanges)
        updateDpad(KeyEvent.KEYCODE_DPAD_UP,    hatY < -0.5f, dpadChanges)
        updateDpad(KeyEvent.KEYCODE_DPAD_DOWN,  hatY > 0.5f,  dpadChanges)

        return dpadChanges
    }

    // Called from onKeyEvent in Activity — returns pc code and pressed state, or null if unmapped
    fun processKey(event: KeyEvent): Pair<Int, Boolean>? {
        val keyCode = event.keyCode
        val pressed = event.action == KeyEvent.ACTION_DOWN

        // Re-center on PS/Options button
        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            if (pressed) recenter()
        }

        buttonStates[keyCode] = pressed
        val mapping = mappings.firstOrNull { it.ps4KeyCode == keyCode } ?: return null
        return Pair(mapping.pcCode, pressed)
    }

    fun recenter() {
        steeringDeg = 0f
        filteredGyroZ = 0f
    }

    fun isControllerConnected(): Boolean {
        return InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            dev.sources and InputDevice.SOURCE_GAMEPAD != 0
        }
    }

    private fun applyDeadZone(value: Float, deadZone: Float): Float {
        if (abs(value) < deadZone) return 0f
        return ((value - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
    }

    private fun updateDpad(keyCode: Int, pressed: Boolean, out: MutableMap<Int, Boolean>) {
        val prev = buttonStates[keyCode] ?: false
        if (prev != pressed) {
            buttonStates[keyCode] = pressed
            out[keyCode] = pressed
        }
    }
}
