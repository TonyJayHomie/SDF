package com.sdf.simwheelps4;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Settings — every tunable the user touches. Keys / defaults are deliberately
 * aligned with the original SimWheel Connect (Flutter) app where they exist.
 */
public class Settings {
    private static final String PREFS = "simwheel_ps4";
    private final SharedPreferences sp;

    /* ---------- Connection ---------- */
    public String pcIp           = "";
    public int    pcPort         = 4567;
    public String connectionMode = "wifi";   // "wifi" or "usb" (label only — receiver decides actual)

    /* ---------- Steering / Gyro (matches original Flutter keys) ---------- */
    public boolean gyroSteering        = true;   // "gyro_steering"
    public float   gyroSensitivity     = 1f;     // "gyro_sensitivity" 0..5,  default 1
    public float   gyroAntiShake       = 80f;    // "gyro_filter"      0..99, default 80
    public float   steeringRangeDeg    = 900f;   // "Steering Range"  90..2520, matches receiver userRange
    public float   centerDurationSec   = 0f;     // "Steering center duration" 0..10  (0 = off)
    public boolean invertSteering      = false;  // "Configure Axis Mode" (steering inverted)
    public boolean showSteeringAngle   = true;   // "Show Steering Angle"
    public String  gyroSource          = "phone"; // "phone" | "controller" | "touch"

    /* ---------- Triggers (analog throttle/brake from PS4 L2/R2) ---------- */
    public float   triggerMin          = 0f;
    public float   triggerMax          = 1f;
    public float   triggerCurve        = 1f;
    public boolean invertThrottle      = false;
    public boolean invertBrake         = false;

    /* ---------- Clutch (optional 3rd analog axis — receiver expects "clutch " key) ---------- */
    public boolean clutchEnabled       = false;
    public float   clutchValue         = 0f;     // current value when slider mode is on
    public float   clutchMin           = 0f;
    public float   clutchMax           = 1f;

    /* ---------- Mouse mode (sends dx/dy from gyro instead of steering) ---------- */
    public boolean mouseMode           = false;
    public float   mouseSensitivity    = 1f;     // "mouse_sensitivity"

    /* ---------- Shifter ---------- */
    public String  shifterType         = "sequential"; // "h_shifter" | "sequential" | "none"  ("shifter_type")
    public boolean autoShifter         = false;        // "auto_shifter"

    /* ---------- Haptic feedback ---------- */
    public boolean hapticOnButton      = true;    // "haptic_feedback" / "Enable vibration on button presses"
    public float   vibMaster           = 0.6f;
    public float   vibCollisionThr     = 0.85f;
    public float   vibRoughThr         = 25f;
    public float   vibRevsScale        = 1f;

    /* ---------- Button mapping persistence ---------- */
    public String  mappingProfileJson  = "";

    public Settings(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        pcIp                = sp.getString ("pcIp", pcIp);
        pcPort              = sp.getInt    ("pcPort", pcPort);
        connectionMode      = sp.getString ("connectionMode", connectionMode);

        gyroSteering        = sp.getBoolean("gyro_steering", gyroSteering);
        gyroSensitivity     = sp.getFloat  ("gyro_sensitivity", gyroSensitivity);
        gyroAntiShake       = sp.getFloat  ("gyro_filter", gyroAntiShake);
        steeringRangeDeg    = sp.getFloat  ("steeringRangeDeg", steeringRangeDeg);
        centerDurationSec   = sp.getFloat  ("centerDurationSec", centerDurationSec);
        invertSteering      = sp.getBoolean("invertSteering", invertSteering);
        showSteeringAngle   = sp.getBoolean("showSteeringAngle", showSteeringAngle);
        gyroSource          = sp.getString ("gyroSource", gyroSource);

        triggerMin          = sp.getFloat  ("triggerMin", triggerMin);
        triggerMax          = sp.getFloat  ("triggerMax", triggerMax);
        triggerCurve        = sp.getFloat  ("triggerCurve", triggerCurve);
        invertThrottle      = sp.getBoolean("invertThrottle", invertThrottle);
        invertBrake         = sp.getBoolean("invertBrake", invertBrake);

        clutchEnabled       = sp.getBoolean("clutchEnabled", clutchEnabled);
        clutchMin           = sp.getFloat  ("clutchMin", clutchMin);
        clutchMax           = sp.getFloat  ("clutchMax", clutchMax);

        mouseMode           = sp.getBoolean("mouseMode", mouseMode);
        mouseSensitivity    = sp.getFloat  ("mouse_sensitivity", mouseSensitivity);

        shifterType         = sp.getString ("shifter_type", shifterType);
        autoShifter         = sp.getBoolean("auto_shifter", autoShifter);

        hapticOnButton      = sp.getBoolean("haptic_feedback", hapticOnButton);
        vibMaster           = sp.getFloat  ("vibMaster", vibMaster);
        vibCollisionThr     = sp.getFloat  ("vibCollisionThr", vibCollisionThr);
        vibRoughThr         = sp.getFloat  ("vibRoughThr", vibRoughThr);
        vibRevsScale        = sp.getFloat  ("vibRevsScale", vibRevsScale);

        mappingProfileJson  = sp.getString ("mappingProfileJson", mappingProfileJson);
    }

    public void save() {
        sp.edit()
            .putString ("pcIp", pcIp)
            .putInt    ("pcPort", pcPort)
            .putString ("connectionMode", connectionMode)
            .putBoolean("gyro_steering", gyroSteering)
            .putFloat  ("gyro_sensitivity", gyroSensitivity)
            .putFloat  ("gyro_filter", gyroAntiShake)
            .putFloat  ("steeringRangeDeg", steeringRangeDeg)
            .putFloat  ("centerDurationSec", centerDurationSec)
            .putBoolean("invertSteering", invertSteering)
            .putBoolean("showSteeringAngle", showSteeringAngle)
            .putString ("gyroSource", gyroSource)
            .putFloat  ("triggerMin", triggerMin)
            .putFloat  ("triggerMax", triggerMax)
            .putFloat  ("triggerCurve", triggerCurve)
            .putBoolean("invertThrottle", invertThrottle)
            .putBoolean("invertBrake", invertBrake)
            .putBoolean("clutchEnabled", clutchEnabled)
            .putFloat  ("clutchMin", clutchMin)
            .putFloat  ("clutchMax", clutchMax)
            .putBoolean("mouseMode", mouseMode)
            .putFloat  ("mouse_sensitivity", mouseSensitivity)
            .putString ("shifter_type", shifterType)
            .putBoolean("auto_shifter", autoShifter)
            .putBoolean("haptic_feedback", hapticOnButton)
            .putFloat  ("vibMaster", vibMaster)
            .putFloat  ("vibCollisionThr", vibCollisionThr)
            .putFloat  ("vibRoughThr", vibRoughThr)
            .putFloat  ("vibRevsScale", vibRevsScale)
            .putString ("mappingProfileJson", mappingProfileJson)
            .apply();
    }

    public JSONObject toProfileJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("gyroSensitivity",   gyroSensitivity);
            o.put("gyroAntiShake",     gyroAntiShake);
            o.put("steeringRangeDeg",  steeringRangeDeg);
            o.put("centerDurationSec", centerDurationSec);
            o.put("invertSteering",    invertSteering);
            o.put("triggerMin",        triggerMin);
            o.put("triggerMax",        triggerMax);
            o.put("triggerCurve",      triggerCurve);
            o.put("invertThrottle",    invertThrottle);
            o.put("invertBrake",       invertBrake);
            o.put("clutchEnabled",     clutchEnabled);
            o.put("mouseMode",         mouseMode);
            o.put("mouseSensitivity",  mouseSensitivity);
            o.put("shifterType",       shifterType);
            o.put("autoShifter",       autoShifter);
            o.put("hapticOnButton",    hapticOnButton);
            o.put("vibMaster",         vibMaster);
            o.put("mapping",           mappingProfileJson);
        } catch (JSONException ignored) {}
        return o;
    }

    public void fromProfileJson(JSONObject o) {
        gyroSensitivity   = (float) o.optDouble("gyroSensitivity",   gyroSensitivity);
        gyroAntiShake     = (float) o.optDouble("gyroAntiShake",     gyroAntiShake);
        steeringRangeDeg  = (float) o.optDouble("steeringRangeDeg",  steeringRangeDeg);
        centerDurationSec = (float) o.optDouble("centerDurationSec", centerDurationSec);
        invertSteering    = o.optBoolean("invertSteering",   invertSteering);
        triggerMin        = (float) o.optDouble("triggerMin",        triggerMin);
        triggerMax        = (float) o.optDouble("triggerMax",        triggerMax);
        triggerCurve      = (float) o.optDouble("triggerCurve",      triggerCurve);
        invertThrottle    = o.optBoolean("invertThrottle",   invertThrottle);
        invertBrake       = o.optBoolean("invertBrake",      invertBrake);
        clutchEnabled     = o.optBoolean("clutchEnabled",    clutchEnabled);
        mouseMode         = o.optBoolean("mouseMode",        mouseMode);
        mouseSensitivity  = (float) o.optDouble("mouseSensitivity",  mouseSensitivity);
        shifterType       = o.optString("shifterType",       shifterType);
        autoShifter       = o.optBoolean("autoShifter",      autoShifter);
        hapticOnButton    = o.optBoolean("hapticOnButton",   hapticOnButton);
        vibMaster         = (float) o.optDouble("vibMaster",         vibMaster);
        mappingProfileJson = o.optString("mapping",          mappingProfileJson);
        save();
    }
}
