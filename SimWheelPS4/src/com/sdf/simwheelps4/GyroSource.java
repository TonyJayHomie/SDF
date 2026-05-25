package com.sdf.simwheelps4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import java.util.List;

/**
 * Verbatim port of your working Kotlin gyro logic:
 *
 *     override fun onSensorChanged(event: SensorEvent) {
 *         if (!running) return
 *         val rotationMatrix = FloatArray(9)
 *         val orientation    = FloatArray(3)
 *         SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
 *         SensorManager.getOrientation(rotationMatrix, orientation)
 *         val rollRad = orientation[2]
 *         val rollDeg = Math.toDegrees(rollRad.toDouble())
 *         steering = max(-900.0, min(900.0, rollDeg * 10.0))
 *     }
 *
 * Sensor: TYPE_GAME_ROTATION_VECTOR (TYPE_ROTATION_VECTOR fallback).
 * Math:   steering = clamp(-rangeDeg, +rangeDeg, rollDeg * 10 * sensitivity).
 * Source pickable: phone / DualShock dynamic gyro / touch wheel handled externally.
 */
public class GyroSource implements SensorEventListener {

    public static final String SRC_PHONE      = "phone";
    public static final String SRC_CONTROLLER = "controller";

    public interface Listener { void onSteeringChanged(float angleDeg); }
    public interface AvailabilityListener {
        void onControllerGyroAvailable(boolean available, String name);
    }

    private final SensorManager sm;
    private final Listener listener;
    private AvailabilityListener availability;
    private Sensor sensor;
    private boolean registered = false;
    private String source = SRC_PHONE;

    // Knobs (kept so the existing Calibration UI still works, but defaults reproduce verbatim behavior)
    private float rangeDeg         = 900f;
    private float sensitivity      = 1f;     // multiplier on the verbatim "* 10"
    private float antiShake        = 0f;     // 0 = no smoothing (verbatim). >0 enables low-pass on the roll output.
    private float centerDuration   = 0f;     // 0 = off (verbatim). >0 = auto-return to center in seconds.
    private boolean invert         = false;
    private float zeroOffsetDeg    = 0f;     // offset applied when user taps "Center steering here"

    // State
    private float lastReported     = 0f;
    private float filteredRoll     = 0f;
    private long  lastEventNs      = 0;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation    = new float[3];

    public GyroSource(Context ctx, Listener l) {
        this.sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.listener = l;
        if (Build.VERSION.SDK_INT >= 24 && sm != null) {
            try {
                sm.registerDynamicSensorCallback(new SensorManager.DynamicSensorCallback() {
                    @Override public void onDynamicSensorConnected(Sensor s)    { reportAvailability(); }
                    @Override public void onDynamicSensorDisconnected(Sensor s) { reportAvailability(); }
                });
            } catch (Throwable ignored) {}
        }
    }

    public void setAvailabilityListener(AvailabilityListener l) { this.availability = l; reportAvailability(); }

    /* ----- knobs ----- */
    public void setRangeDeg(float v)          { rangeDeg       = Math.max(45f, v); }
    public void setSensitivity(float v)       { sensitivity    = Math.max(0f, Math.min(5f, v)); }
    public void setAntiShake(float v)         { antiShake      = Math.max(0f, Math.min(99f, v)); }
    public void setCenterDurationSec(float v) { centerDuration = Math.max(0f, Math.min(10f, v)); }
    public void setInvert(boolean v)          { invert         = v; }

    public void setSource(String src) {
        if (src == null) src = SRC_PHONE;
        if (src.equals(source)) return;
        source = src;
        boolean wasOn = registered;
        if (wasOn) stop();
        recenter();
        if (wasOn) start();
    }

    public String getSource() { return source; }

    public void start() {
        if (registered) return;
        sensor = pickSensor();
        if (sensor != null) {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
            registered = true;
            lastEventNs = 0;
        }
    }

    public void stop() {
        if (registered) {
            sm.unregisterListener(this);
            registered = false;
            sensor = null;
        }
    }

    /** Capture current roll as the new zero. */
    public void recenter() {
        zeroOffsetDeg += lastReported / (sensitivity == 0f ? 1f : (10f * sensitivity));
        lastReported = 0f;
        filteredRoll = 0f;
        if (listener != null) listener.onSteeringChanged(0f);
    }

    public boolean isControllerGyroAvailable() { return findControllerGyro() != null; }

    private Sensor pickSensor() {
        if (SRC_CONTROLLER.equals(source)) {
            Sensor s = findControllerGyro();
            if (s != null) return s;
        }
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        return s;
    }

    private Sensor findControllerGyro() {
        if (Build.VERSION.SDK_INT < 24 || sm == null) return null;
        try {
            List<Sensor> rv = sm.getDynamicSensorList(Sensor.TYPE_GAME_ROTATION_VECTOR);
            for (Sensor s : rv) return s;
            rv = sm.getDynamicSensorList(Sensor.TYPE_ROTATION_VECTOR);
            for (Sensor s : rv) return s;
            List<Sensor> gy = sm.getDynamicSensorList(Sensor.TYPE_GYROSCOPE);
            for (Sensor s : gy) return s;
        } catch (Throwable ignored) {}
        return null;
    }

    private void reportAvailability() {
        if (availability == null) return;
        Sensor s = findControllerGyro();
        availability.onControllerGyroAvailable(s != null, s == null ? "" : s.getName());
    }

    /** Verbatim port of the Kotlin onSensorChanged, with optional smoothing/invert/auto-center hooks. */
    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        float rollRad = orientation[2];
        float rollDeg = (float) Math.toDegrees((double) rollRad);

        // Apply user-tapped center.
        rollDeg -= zeroOffsetDeg;

        // Verbatim formula: steering = clamp(-range, +range, rollDeg * 10 * sensitivity)
        float steering = rollDeg * 10f * sensitivity;
        if (steering >  rangeDeg) steering =  rangeDeg;
        if (steering < -rangeDeg) steering = -rangeDeg;

        // Optional smoothing (Anti-Shake). When antiShake == 0, alpha = 1 → pass-through (verbatim).
        if (antiShake > 0f) {
            float alpha = 1f - (antiShake / 100f);
            if (alpha < 0.01f) alpha = 0.01f;
            filteredRoll = filteredRoll + alpha * (steering - filteredRoll);
            steering = filteredRoll;
        } else {
            filteredRoll = steering;
        }

        // Optional auto-return to center. centerDuration == 0 disables (verbatim).
        if (centerDuration > 0f && lastEventNs != 0) {
            float dt = (event.timestamp - lastEventNs) / 1_000_000_000f;
            if (dt > 0f) {
                float decay = dt / centerDuration;
                if (decay > 1f) decay = 1f;
                steering -= steering * decay;
            }
        }
        lastEventNs = event.timestamp;

        if (invert) steering = -steering;
        lastReported = steering;
        if (listener != null) listener.onSteeringChanged(steering);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }
}
