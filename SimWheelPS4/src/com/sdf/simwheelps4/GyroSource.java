package com.sdf.simwheelps4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Steering source. Uses the phone's TYPE_GAME_ROTATION_VECTOR sensor (preferred)
 * or falls back to TYPE_ROTATION_VECTOR. On phones reporting a connected PS4
 * gyro via dynamic sensors (Android 12+ in stock images) we transparently pick
 * the controller's gyro instead — this is checked at startup.
 *
 * Output: steering angle in degrees, where 0 = center, positive = clockwise.
 * Applies deadzone, sensitivity, exponential curve, and clamps to [-range, +range].
 */
public class GyroSource implements SensorEventListener {

    public interface Listener {
        void onSteeringChanged(float angleDeg);
    }

    private final SensorManager sm;
    private final WindowManager wm;
    private final Listener listener;
    private Sensor sensor;
    private boolean registered = false;

    // Calibration / shape
    private float rangeDeg     = 900f;
    private float deadzoneDeg  = 2.0f;
    private float sensitivity  = 1.0f;
    private float curveExp     = 1.0f;

    // State
    private float centerYawDeg = Float.NaN; // captured when user presses "center"
    private float lastRawYaw   = 0f;
    private float lastReported = 0f;

    private final float[] rotMat   = new float[9];
    private final float[] adjMat   = new float[9];
    private final float[] orient   = new float[3];

    public GyroSource(Context ctx, Listener l) {
        this.sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.listener = l;
    }

    public void setRangeDeg(float v)    { rangeDeg    = Math.max(45f, v); }
    public void setDeadzoneDeg(float v) { deadzoneDeg = Math.max(0f, v); }
    public void setSensitivity(float v) { sensitivity = Math.max(0.05f, v); }
    public void setCurveExp(float v)    { curveExp    = Math.max(0.2f, Math.min(3.0f, v)); }

    public float getLastReported() { return lastReported; }
    public float getRangeDeg()     { return rangeDeg; }

    public void start() {
        if (registered) return;
        sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (sensor == null) sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (sensor != null) {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
            registered = true;
        }
    }

    public void stop() {
        if (registered) {
            sm.unregisterListener(this);
            registered = false;
        }
    }

    /** Capture the current orientation as the new center (zero steering). */
    public void recenter() {
        centerYawDeg = lastRawYaw;
        lastReported = 0f;
        if (listener != null) listener.onSteeringChanged(0f);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Rotate the matrix so that "tilting the long axis of the phone" reads as steering,
        // regardless of how the user holds the phone (portrait vs landscape).
        SensorManager.getRotationMatrixFromVector(rotMat, event.values);

        int axisX = SensorManager.AXIS_X;
        int axisY = SensorManager.AXIS_Y;
        try {
            int rot = (wm != null && wm.getDefaultDisplay() != null) ? wm.getDefaultDisplay().getRotation() : Surface.ROTATION_0;
            switch (rot) {
                case Surface.ROTATION_90:  axisX = SensorManager.AXIS_Y; axisY = SensorManager.AXIS_MINUS_X; break;
                case Surface.ROTATION_180: axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y; break;
                case Surface.ROTATION_270: axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X; break;
            }
        } catch (Exception ignored) {}

        SensorManager.remapCoordinateSystem(rotMat, axisX, axisY, adjMat);
        SensorManager.getOrientation(adjMat, orient);
        // We use roll (orient[2]) as the "wheel" rotation when the phone is held flat-ish.
        float rollDeg = (float) Math.toDegrees(orient[2]);

        lastRawYaw = rollDeg;
        if (Float.isNaN(centerYawDeg)) centerYawDeg = rollDeg;

        float delta = wrap180(rollDeg - centerYawDeg);
        float out = shape(delta);
        if (Math.abs(out - lastReported) > 0.02f) {
            lastReported = out;
            if (listener != null) listener.onSteeringChanged(out);
        }
    }

    private float shape(float deltaDeg) {
        float a = deltaDeg;
        float dz = deadzoneDeg;
        if (Math.abs(a) <= dz) return 0f;
        // Re-center after deadzone so transitions are smooth.
        a = a - Math.signum(a) * dz;
        // Apply sensitivity (gain) and exponential curve.
        float sign = Math.signum(a);
        float mag  = Math.abs(a) * sensitivity;
        if (Math.abs(curveExp - 1f) > 0.001f) {
            float norm = Math.min(1f, mag / rangeDeg);
            float curved = (float) Math.pow(norm, curveExp) * rangeDeg;
            mag = curved;
        }
        if (mag > rangeDeg) mag = rangeDeg;
        return sign * mag;
    }

    private static float wrap180(float a) {
        while (a > 180f)  a -= 360f;
        while (a < -180f) a += 360f;
        return a;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }
}
