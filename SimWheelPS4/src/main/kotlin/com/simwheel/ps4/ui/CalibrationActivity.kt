package com.simwheel.ps4.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.*
import com.simwheel.ps4.R
import com.simwheel.ps4.controller.PS4Controller
import com.simwheel.ps4.model.Settings as AppSettings

class CalibrationActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var controller: PS4Controller

    private lateinit var steeringPreview: SteeringWheelView
    private lateinit var tvCalibSteering: TextView
    private lateinit var sbRange: SeekBar
    private lateinit var tvRange: TextView
    private lateinit var sbSensitivity: SeekBar
    private lateinit var tvSensitivity: TextView
    private lateinit var sbFilter: SeekBar
    private lateinit var tvFilter: TextView
    private lateinit var sbDeadzone: SeekBar
    private lateinit var tvDeadzone: TextView
    private lateinit var barCalibThrottle: BarView
    private lateinit var barCalibBrake: BarView
    private lateinit var tvCalibThrottle: TextView
    private lateinit var tvCalibBrake: TextView
    private lateinit var btnSave: Button
    private lateinit var btnBack: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateLiveValues()
            handler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        settings = AppSettings(this)
        controller = PS4Controller(settings.getCalibration())

        bindViews()
        loadCurrentSettings()
        setupListeners()
        handler.post(refreshRunnable)
    }

    private fun bindViews() {
        steeringPreview   = findViewById(R.id.steering_preview)
        tvCalibSteering   = findViewById(R.id.tv_calib_steering)
        sbRange           = findViewById(R.id.sb_range)
        tvRange           = findViewById(R.id.tv_range)
        sbSensitivity     = findViewById(R.id.sb_sensitivity)
        tvSensitivity     = findViewById(R.id.tv_sensitivity)
        sbFilter          = findViewById(R.id.sb_filter)
        tvFilter          = findViewById(R.id.tv_filter)
        sbDeadzone        = findViewById(R.id.sb_deadzone)
        tvDeadzone        = findViewById(R.id.tv_deadzone)
        barCalibThrottle  = findViewById(R.id.bar_calib_throttle)
        barCalibBrake     = findViewById(R.id.bar_calib_brake)
        tvCalibThrottle   = findViewById(R.id.tv_calib_throttle)
        tvCalibBrake      = findViewById(R.id.tv_calib_brake)
        btnSave           = findViewById(R.id.btn_save_calib)
        btnBack           = findViewById(R.id.btn_back_calib)

        barCalibThrottle.barColor = Color.parseColor("#00d9a3")
        barCalibBrake.barColor    = Color.parseColor("#e94560")
    }

    private fun loadCurrentSettings() {
        // Range seekbar: 0..2430 maps to 90..2520
        sbRange.progress      = (settings.steeringRange - 90).toInt()
        tvRange.text          = "${settings.steeringRange.toInt()}°"
        sbSensitivity.progress = (settings.sensitivity * 100).toInt()
        tvSensitivity.text    = "${"%.2f".format(settings.sensitivity)}x"
        sbFilter.progress     = settings.gyroFilter
        tvFilter.text         = "${settings.gyroFilter}"
        sbDeadzone.progress   = (settings.triggerDeadZone * 100).toInt()
        tvDeadzone.text       = "${(settings.triggerDeadZone * 100).toInt()}%"
    }

    private fun setupListeners() {
        sbRange.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val range = (p + 90).toFloat()
                tvRange.text = "${range.toInt()}°"
                rebuildController()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val sens = p / 100f
                tvSensitivity.text = "${"%.2f".format(sens)}x"
                rebuildController()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        sbFilter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvFilter.text = "$p"
                rebuildController()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        sbDeadzone.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvDeadzone.text = "$p%"
                rebuildController()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnSave.setOnClickListener {
            settings.steeringRange  = (sbRange.progress + 90).toFloat()
            settings.sensitivity    = sbSensitivity.progress / 100f
            settings.gyroFilter     = sbFilter.progress
            settings.triggerDeadZone = sbDeadzone.progress / 100f
            Toast.makeText(this, "Calibration saved", Toast.LENGTH_SHORT).show()
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun rebuildController() {
        val range = (sbRange.progress + 90).toFloat()
        val sens  = sbSensitivity.progress / 100f
        val filter = sbFilter.progress
        val dz    = sbDeadzone.progress / 100f
        controller.calibration = com.simwheel.ps4.model.CalibrationSettings(range, sens, filter, dz)
        controller.recenter()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != 0 ||
            event.source and InputDevice.SOURCE_GAMEPAD != 0) {
            controller.processMotion(event)
        }
        return true
    }

    private fun updateLiveValues() {
        val steering = controller.steeringDeg
        steeringPreview.angleDeg  = steering
        tvCalibSteering.text      = "Steering: ${"%.1f".format(steering)}°"

        val throttle = controller.throttle
        val brake    = controller.brake
        barCalibThrottle.value = throttle
        barCalibBrake.value    = brake
        tvCalibThrottle.text   = "${(throttle * 100).toInt()}%"
        tvCalibBrake.text      = "${(brake * 100).toInt()}%"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }
}
