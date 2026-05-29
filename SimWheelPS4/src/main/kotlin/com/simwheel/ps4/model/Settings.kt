package com.simwheel.ps4.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class CalibrationSettings(
    val steeringRange: Float = 900f,
    val sensitivity: Float = 1.0f,
    val gyroFilter: Int = 80,
    val triggerDeadZone: Float = 0.02f
)

data class ButtonMapping(
    val ps4KeyCode: Int,
    val ps4Name: String,
    val pcCode: Int,
    val pcName: String
)

class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("simwheel_ps4", Context.MODE_PRIVATE)

    // ── Calibration ──────────────────────────────────────────────────────────

    var steeringRange: Float
        get() = prefs.getFloat("steering_range", 900f).coerceIn(90f, 2520f)
        set(v) = prefs.edit().putFloat("steering_range", v.coerceIn(90f, 2520f)).apply()

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 1.0f)
        set(v) = prefs.edit().putFloat("sensitivity", v).apply()

    var gyroFilter: Int
        get() = prefs.getInt("gyro_filter", 80).coerceIn(0, 99)
        set(v) = prefs.edit().putInt("gyro_filter", v.coerceIn(0, 99)).apply()

    var triggerDeadZone: Float
        get() = prefs.getFloat("trigger_deadzone", 0.02f).coerceIn(0f, 0.3f)
        set(v) = prefs.edit().putFloat("trigger_deadzone", v.coerceIn(0f, 0.3f)).apply()

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean("vibration_enabled", true)
        set(v) = prefs.edit().putBoolean("vibration_enabled", v).apply()

    var vibrationIntensity: Int
        get() = prefs.getInt("vibration_intensity", 80).coerceIn(0, 100)
        set(v) = prefs.edit().putInt("vibration_intensity", v.coerceIn(0, 100)).apply()

    var lastIp: String
        get() = prefs.getString("last_ip", "") ?: ""
        set(v) = prefs.edit().putString("last_ip", v).apply()

    fun getCalibration() = CalibrationSettings(steeringRange, sensitivity, gyroFilter, triggerDeadZone)

    // ── Button Mapping ────────────────────────────────────────────────────────

    fun saveMappingProfile(name: String, mappings: List<ButtonMapping>) {
        val arr = org.json.JSONArray()
        mappings.forEach { m ->
            val o = JSONObject()
            o.put("ps4", m.ps4KeyCode)
            o.put("ps4name", m.ps4Name)
            o.put("pc", m.pcCode)
            o.put("pcname", m.pcName)
            arr.put(o)
        }
        prefs.edit().putString("profile_$name", arr.toString()).apply()
        // Save profile names list
        val names = getProfileNames().toMutableSet()
        names.add(name)
        prefs.edit().putStringSet("profile_names", names).apply()
    }

    fun loadMappingProfile(name: String): List<ButtonMapping>? {
        val json = prefs.getString("profile_$name", null) ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ButtonMapping(o.getInt("ps4"), o.getString("ps4name"), o.getInt("pc"), o.getString("pcname"))
            }
        } catch (e: Exception) { null }
    }

    fun getProfileNames(): Set<String> =
        prefs.getStringSet("profile_names", setOf("Default")) ?: setOf("Default")

    fun getActiveProfile(): String =
        prefs.getString("active_profile", "Default") ?: "Default"

    fun setActiveProfile(name: String) =
        prefs.edit().putString("active_profile", name).apply()
}
