package com.sdf.simwheelps4;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Thin wrapper around SharedPreferences. Single source of truth for every tunable
 * value the user can change in the UI or via a saved profile.
 */
public class Settings {
    private static final String PREFS = "simwheel_ps4";
    private final SharedPreferences sp;

    // Network
    public String pcIp = "";
    public int    pcPort = 4567;

    // Steering
    public float steeringRangeDeg = 900f;   // half-range each side mapped to receiver "userRange"
    public float gyroDeadzoneDeg  = 2.0f;
    public float gyroSensitivity  = 1.0f;
    public float steeringCurve    = 1.0f;   // exponent

    // Triggers
    public float triggerMin       = 0.0f;
    public float triggerMax       = 1.0f;
    public float triggerCurve     = 1.0f;

    // Vibration
    public float vibMaster        = 0.6f;
    public float vibCollisionThr  = 0.85f;
    public float vibRoughThr      = 25f;     // deg/s steering jerk threshold
    public float vibRevsScale     = 1.0f;

    // Mapping profile JSON
    public String mappingProfileJson = "";

    public Settings(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        pcIp              = sp.getString("pcIp", pcIp);
        pcPort            = sp.getInt   ("pcPort", pcPort);
        steeringRangeDeg  = sp.getFloat ("steeringRangeDeg", steeringRangeDeg);
        gyroDeadzoneDeg   = sp.getFloat ("gyroDeadzoneDeg", gyroDeadzoneDeg);
        gyroSensitivity   = sp.getFloat ("gyroSensitivity", gyroSensitivity);
        steeringCurve     = sp.getFloat ("steeringCurve", steeringCurve);
        triggerMin        = sp.getFloat ("triggerMin", triggerMin);
        triggerMax        = sp.getFloat ("triggerMax", triggerMax);
        triggerCurve      = sp.getFloat ("triggerCurve", triggerCurve);
        vibMaster         = sp.getFloat ("vibMaster", vibMaster);
        vibCollisionThr   = sp.getFloat ("vibCollisionThr", vibCollisionThr);
        vibRoughThr       = sp.getFloat ("vibRoughThr", vibRoughThr);
        vibRevsScale      = sp.getFloat ("vibRevsScale", vibRevsScale);
        mappingProfileJson= sp.getString("mappingProfileJson", "");
    }

    public void save() {
        sp.edit()
            .putString("pcIp", pcIp)
            .putInt   ("pcPort", pcPort)
            .putFloat ("steeringRangeDeg", steeringRangeDeg)
            .putFloat ("gyroDeadzoneDeg", gyroDeadzoneDeg)
            .putFloat ("gyroSensitivity", gyroSensitivity)
            .putFloat ("steeringCurve", steeringCurve)
            .putFloat ("triggerMin", triggerMin)
            .putFloat ("triggerMax", triggerMax)
            .putFloat ("triggerCurve", triggerCurve)
            .putFloat ("vibMaster", vibMaster)
            .putFloat ("vibCollisionThr", vibCollisionThr)
            .putFloat ("vibRoughThr", vibRoughThr)
            .putFloat ("vibRevsScale", vibRevsScale)
            .putString("mappingProfileJson", mappingProfileJson)
            .apply();
    }

    /** Export the full tunable state as a JSON profile (used for save/load). */
    public JSONObject toProfileJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("steeringRangeDeg", steeringRangeDeg);
            o.put("gyroDeadzoneDeg", gyroDeadzoneDeg);
            o.put("gyroSensitivity", gyroSensitivity);
            o.put("steeringCurve", steeringCurve);
            o.put("triggerMin", triggerMin);
            o.put("triggerMax", triggerMax);
            o.put("triggerCurve", triggerCurve);
            o.put("vibMaster", vibMaster);
            o.put("vibCollisionThr", vibCollisionThr);
            o.put("vibRoughThr", vibRoughThr);
            o.put("vibRevsScale", vibRevsScale);
            o.put("mapping", mappingProfileJson);
        } catch (JSONException ignored) {}
        return o;
    }

    public void fromProfileJson(JSONObject o) {
        steeringRangeDeg = (float) o.optDouble("steeringRangeDeg", steeringRangeDeg);
        gyroDeadzoneDeg  = (float) o.optDouble("gyroDeadzoneDeg", gyroDeadzoneDeg);
        gyroSensitivity  = (float) o.optDouble("gyroSensitivity", gyroSensitivity);
        steeringCurve    = (float) o.optDouble("steeringCurve", steeringCurve);
        triggerMin       = (float) o.optDouble("triggerMin", triggerMin);
        triggerMax       = (float) o.optDouble("triggerMax", triggerMax);
        triggerCurve     = (float) o.optDouble("triggerCurve", triggerCurve);
        vibMaster        = (float) o.optDouble("vibMaster", vibMaster);
        vibCollisionThr  = (float) o.optDouble("vibCollisionThr", vibCollisionThr);
        vibRoughThr      = (float) o.optDouble("vibRoughThr", vibRoughThr);
        vibRevsScale     = (float) o.optDouble("vibRevsScale", vibRevsScale);
        mappingProfileJson = o.optString("mapping", mappingProfileJson);
        save();
    }
}
