package com.simwheel.ps4.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.provider.Settings
import android.widget.*
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.simwheel.ps4.R
import com.simwheel.ps4.controller.PS4Controller
import com.simwheel.ps4.model.DefaultMappings
import com.simwheel.ps4.network.UdpClient
import com.simwheel.ps4.model.Settings as AppSettings

class MainActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var controller: PS4Controller
    private lateinit var udp: UdpClient
    private lateinit var vibrator: Vibrator

    // Views
    private lateinit var tvStatus: TextView
    private lateinit var tvControllerStatus: TextView
    private lateinit var steeringWheel: SteeringWheelView
    private lateinit var tvSteering: TextView
    private lateinit var barThrottle: BarView
    private lateinit var barBrake: BarView
    private lateinit var tvThrottle: TextView
    private lateinit var tvBrake: TextView
    private lateinit var etIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnRecenter: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnMapping: Button
    private lateinit var swVibration: Switch
    private lateinit var sbVibIntensity: SeekBar
    private lateinit var tvVibIntensity: TextView
    private lateinit var tvButtons: TextView

    // Pending button PC-code changes to send
    private val pendingPcButtons = mutableMapOf<Int, Boolean>()

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 33) // ~30fps
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = AppSettings(this)
        controller = PS4Controller(settings.getCalibration())
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        bindViews()
        setupUdp()
        loadSettings()
        setupListeners()
        uiHandler.post(uiRunnable)

        // Load active button mappings
        val profile = settings.getActiveProfile()
        val mappings = settings.loadMappingProfile(profile) ?: DefaultMappings.ALL
        controller.mappings = mappings
        if (settings.loadMappingProfile(profile) == null) {
            settings.saveMappingProfile("Default", DefaultMappings.ALL)
        }
    }

    private fun bindViews() {
        tvStatus            = findViewById(R.id.tv_status)
        tvControllerStatus  = findViewById(R.id.tv_controller_status)
        steeringWheel       = findViewById(R.id.steering_wheel)
        tvSteering          = findViewById(R.id.tv_steering)
        barThrottle         = findViewById(R.id.bar_throttle)
        barBrake            = findViewById(R.id.bar_brake)
        tvThrottle          = findViewById(R.id.tv_throttle)
        tvBrake             = findViewById(R.id.tv_brake)
        etIp                = findViewById(R.id.et_ip)
        btnConnect          = findViewById(R.id.btn_connect)
        btnRecenter         = findViewById(R.id.btn_recenter)
        btnCalibrate        = findViewById(R.id.btn_calibrate)
        btnMapping          = findViewById(R.id.btn_mapping)
        swVibration         = findViewById(R.id.sw_vibration)
        sbVibIntensity      = findViewById(R.id.sb_vibration_intensity)
        tvVibIntensity      = findViewById(R.id.tv_vib_intensity)
        tvButtons           = findViewById(R.id.tv_buttons)

        barThrottle.barColor = Color.parseColor("#00d9a3")
        barBrake.barColor    = Color.parseColor("#e94560")
    }

    private fun setupUdp() {
        val phoneName = Settings.Global.getString(contentResolver, "device_name")
            ?: android.os.Build.MODEL
        udp = UdpClient(
            phoneName = phoneName,
            onVibration = { intensity, duration -> triggerVibration(intensity, duration) },
            onConnected = { pcName ->
                uiHandler.post {
                    tvStatus.text = "Connected to $pcName"
                    tvStatus.setTextColor(Color.parseColor("#00d9a3"))
                    btnConnect.text = "Disconnect"
                }
            },
            onDisconnected = {
                uiHandler.post {
                    tvStatus.text = getString(R.string.status_idle)
                    tvStatus.setTextColor(Color.parseColor("#aaaacc"))
                    btnConnect.text = getString(R.string.connect)
                }
            }
        )
    }

    private fun loadSettings() {
        swVibration.isChecked = settings.vibrationEnabled
        sbVibIntensity.progress = settings.vibrationIntensity
        tvVibIntensity.text = "${settings.vibrationIntensity}%"
        etIp.setText(settings.lastIp)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (udp.isConnected) {
                udp.disconnect()
            } else {
                val ip = etIp.text.toString().trim()
                if (ip.isEmpty()) {
                    tvStatus.text = getString(R.string.status_searching)
                    udp.discover()
                } else {
                    settings.lastIp = ip
                    tvStatus.text = "Connecting to $ip…"
                    udp.connect(ip)
                }
            }
        }

        btnRecenter.setOnClickListener { controller.recenter() }

        btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        btnMapping.setOnClickListener {
            startActivity(Intent(this, MappingActivity::class.java))
        }

        swVibration.setOnCheckedChangeListener { _, checked ->
            settings.vibrationEnabled = checked
        }

        sbVibIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                settings.vibrationIntensity = progress
                tvVibIntensity.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── UI update loop ────────────────────────────────────────────────────────

    private fun refreshUi() {
        val steering = controller.steeringDeg
        val throttle = controller.throttle
        val brake    = controller.brake

        steeringWheel.angleDeg = steering
        tvSteering.text = "Steering: ${"%.1f".format(steering)}°"

        barThrottle.value = throttle
        tvThrottle.text = "${(throttle * 100).toInt()}%"

        barBrake.value = brake
        tvBrake.text = "${(brake * 100).toInt()}%"

        val connected = controller.isControllerConnected()
        tvControllerStatus.text = if (connected) getString(R.string.controller_connected)
                                  else getString(R.string.no_controller)
        tvControllerStatus.setTextColor(
            if (connected) Color.parseColor("#00d9a3") else Color.parseColor("#e94560"))

        // Send UDP state
        if (udp.isConnected && pendingPcButtons.isNotEmpty()) {
            udp.sendState(steering, throttle, brake, pendingPcButtons.toMap())
            pendingPcButtons.clear()
        } else if (udp.isConnected) {
            udp.sendState(steering, throttle, brake, emptyMap())
        }
    }

    // ── Input events ──────────────────────────────────────────────────────────

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val dpadChanges = controller.processMotion(event)
        // Convert dpad key codes → PC codes via mappings
        dpadChanges.forEach { (ps4Code, pressed) ->
            val mapping = controller.mappings.firstOrNull { it.ps4KeyCode == ps4Code }
            if (mapping != null) pendingPcButtons[mapping.pcCode] = pressed
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val result = controller.processKey(event)
        if (result != null) {
            pendingPcButtons[result.first] = result.second
            updateButtonsDisplay()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val result = controller.processKey(event)
        if (result != null) {
            pendingPcButtons[result.first] = result.second
            updateButtonsDisplay()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun updateButtonsDisplay() {
        val active = pendingPcButtons.filter { it.value }.keys.joinToString(" ")
        tvButtons.text = if (active.isEmpty()) "Buttons: —" else "Buttons: $active"
    }

    // ── Vibration ─────────────────────────────────────────────────────────────

    private fun triggerVibration(intensity: Float, duration: Int) {
        if (!settings.vibrationEnabled) return
        val scaledIntensity = (intensity * settings.vibrationIntensity / 100f)
        val amplitude = (scaledIntensity * 255).toInt().coerceIn(1, 255)
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(duration.toLong(), amplitude))
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // Reload calibration in case it was changed
        controller.calibration = settings.getCalibration()
        val profile = settings.getActiveProfile()
        controller.mappings = settings.loadMappingProfile(profile) ?: DefaultMappings.ALL
        uiHandler.post(uiRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiRunnable)
        udp.disconnect()
    }
}
