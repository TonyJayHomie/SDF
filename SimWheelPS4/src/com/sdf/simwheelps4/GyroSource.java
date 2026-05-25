package com.sdf.simwheelps4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Steering source. Switchable between:
 *   - PHONE: the phone's TYPE_GAME_ROTATION_VECTOR (or TYPE_ROTATION_VECTOR fallback)
 *   - CONTROLLER: the paired PS4 controller's own gyroscope, exposed on Android 7.0+
 *     (API 24) as a "dynamic sensor". Falls back to phone if no controller gyro
 *     is currently advertised by the system.
 *
 * Output: steering angle in degrees, 0 = center, positive = clockwise.
 * Applies deadzone, sensitivity, exponential curve, and clamps to [-range, +range].
 */
public class GyroSource implements SensorEventListener {

    public static final String SRC_PHONE      = "phone";
    public static final String SRC_CONTROLLER = "controller";

    public interface Listener {
        void onSteeringChanged(float angleDeg);
    }

    public interface AvailabilityListener {
        /** Fires when a controller-side gyro appears or disappears. */
        void onControllerGyroAvailable(boolean available, String name);
    }

    private final SensorManager sm;
    private final WindowManager wm;
    private final Listener listener;
    private AvailabilityListener availability;
    private Sensor sensor;
    private boolean registered = false;
    private String source = SRC_PHONE;

    // Calibration / shape
    private float rangeDeg          = 900f;   // steering output range (matches receiver "userRange")
    private float physicalTiltDeg   = 45f;    // physical tilt that maps to full lock
    private float deadzoneDeg       = 2.0f;
    private float curveExp          = 1.0f;
    private boolean invert          = false;

    // State for rotation-vector path
    private float centerYawDeg = Float.NaN;
    private float lastRawYaw   = 0f;
    private float lastReported = 0f;

    // State for raw-gyroscope integration path (used when controller exposes a TYPE_GYROSCOPE)
    private long  lastGyroNs   = 0;
    private float integratedDeg= 0f;

    private final float[] rotMat = new float[9];
    private final float[] adjMat = new float[9];
    private final float[] orient = new float[3];

    public GyroSource(Context ctx, Listener l) {
        this.sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.listener = l;

        // Watch for controllers being plugged/unplugged so the UI can enable/disable the toggle.
        if (Build.VERSION.SDK_INT >= 24 && sm != null) {
            try {
                sm.registerDynamicSensorCallback(new SensorManager.DynamicSensorCallback() {
                    @Override public void onDynamicSensorConnected(Sensor s)    { reportAvailability(); }
                    @Override public void onDynamicSensorDisconnected(Sensor s) { reportAvailability(); }
                });
            } catch (Throwable ignored) {}
        }
    }

    public void setAvailabilityListener(AvailabilityListener l) {
        this.availability = l;
        reportAvailability();
    }

    public void setRangeDeg(float v)         { rangeDeg        = Math.max(45f, v); }
    public void setPhysicalTiltDeg(float v)  { physicalTiltDeg = Math.max(5f, Math.min(180f, v)); }
    public void setDeadzoneDeg(float v)      { deadzoneDeg     = Math.max(0f, v); }
    public void setCurveExp(float v)         { curveExp        = Math.max(0.2f, Math.min(3.0f, v)); }
    public void setInvert(boolean v)         { invert          = v; }

    public float getLastReported() { return lastReported; }
    public float getRangeDeg()     { return rangeDeg; }

    /** Switch which gyro feeds the steering. Restarts the sensor listener if running. */
    public void setSource(String src) {
        if (src == null) src = SRC_PHONE;
        if (src.equals(source)) return;
        source = src;
        boolean wasOn = registered;
        if (wasOn) stop();
        // reset integration / center
        integratedDeg = 0f;
        lastGyroNs    = 0;
        centerYawDeg  = Float.NaN;
        lastReported  = 0f;
        if (wasOn) start();
    }

    public String getSource() { return source; }

    public void start() {
        if (registered) return;
        sensor = pickSensor();
        if (sensor != null) {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
            registered = true;
        }
    }

    public void stop() {
        if (registered) {
            sm.unregisterListener(this);
            registered = false;
            sensor = null;
        }
    }

    /** Capture the current orientation as the new center (zero steering). */
    public void recenter() {
        centerYawDeg  = lastRawYaw;
        integratedDeg = 0f;
        lastReported  = 0f;
        if (listener != null) listener.onSteeringChanged(0f);
    }

    /** Returns true if at least one controller-side gyro is advertised right now. */
    public boolean isControllerGyroAvailable() {
        return findControllerGyro() != null;
    }

    private Sensor pickSensor() {
        if (SRC_CONTROLLER.equals(source)) {
            Sensor s = findControllerGyro();
            if (s != null) return s;
            // Fall back to phone if controller gyro isn't visible.
        }
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        return s;
    }

    /**
     * Pick a dynamic gyroscope/rotation-vector advertised by a paired controller.
     * Android 7.0+ (API 24) exposes the DualShock 4 / DualSense IMUs through
     * SensorManager.getDynamicSensorList.
     */
    private Sensor findControllerGyro() {
        if (Build.VERSION.SDK_INT < 24 || sm == null) return null;
        try {
            // Prefer a dynamic rotation-vector (already integrated, no drift).
            List<Sensor> rv = sm.getDynamicSensorList(Sensor.TYPE_GAME_ROTATION_VECTOR);
            for (Sensor s : rv) { return s; }
            rv = sm.getDynamicSensorList(Sensor.TYPE_ROTATION_VECTOR);
            for (Sensor s : rv) { return s; }
            // Fallback: a raw gyroscope from the controller — we integrate it ourselves.
            List<Sensor> gy = sm.getDynamicSensorList(Sensor.TYPE_GYROSCOPE);
            for (Sensor s : gy) { return s; }
        } catch (Throwable ignored) {}
        return null;
    }

    private void reportAvailability() {
        if (availability == null) return;
        Sensor s = findControllerGyro();
        availability.onControllerGyroAvailable(s != null, s == null ? "" : s.getName());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int t = event.sensor.getType();
        float delta;
        if (t == Sensor.TYPE_GYROSCOPE) {
            // Raw rad/s on three axes — integrate Z (or whichever axis is "twist" on the controller).
            // PS4 controller: rotating it about its long axis (yaw when held wheel-style) shows up
            // strongest on AXIS_Y. We pick the axis with the largest variance over a short window
            // — but a simple choice that works for both PS4 and phone is to use Y.
            long now = event.timestamp;
            if (lastGyroNs == 0) { lastGyroNs = now; return; }
            float dt = Math.min(0.05f, (now - lastGyroNs) / 1_000_000_000f);
            lastGyroNs = now;
            float wY = event.values[1];                     // rad/s about device Y
            integratedDeg += (float) Math.toDegrees(wY * dt);
            lastRawYaw = integratedDeg;
            if (Float.isNaN(centerYawDeg)) centerYawDeg = lastRawYaw;
            delta = integratedDeg - centerYawDeg;
        } else {
            // ROTATION_VECTOR / GAME_ROTATION_VECTOR — convert to roll, accounting for display rotation.
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
            float rollDeg = (float) Math.toDegrees(orient[2]);
            lastRawYaw = rollDeg;
            if (Float.isNaN(centerYawDeg)) centerYawDeg = rollDeg;
            delta = wrap180(rollDeg - centerYawDeg);
        }
        float out = shape(delta);
        if (Math.abs(out - lastReported) > 0.02f) {
            lastReported = out;
            if (listener != null) listener.onSteeringChanged(out);
        }
    }

    /**
     * Map a measured physical tilt (deg) to a steering value in deg.
     *
     *   physicalTiltDeg is the tilt at which steering should hit full lock.
     *   rangeDeg        is the steering value at full lock (e.g. 900 for 900° wheel).
     *
     * So if physicalTiltDeg=45 and rangeDeg=900:
     *   tilt = 0   →  steering = 0
     *   tilt = 22.5 → steering = 450 (half lock)
     *   tilt = 45  →  steering = 900 (full lock)
     */
    private float shape(float deltaDeg) {
        float a = deltaDeg;
        float dz = deadzoneDeg;
        if (Math.abs(a) <= dz) return 0f;
        // re-center past the deadzone so the response is smooth at the edge
        a = a - Math.signum(a) * dz;
        float effRange = Math.max(1f, physicalTiltDeg - dz);
        float norm = a / effRange;
        if (norm >  1f) norm =  1f;
        if (norm < -1f) norm = -1f;
        if (Math.abs(curveExp - 1f) > 0.001f) {
            norm = Math.signum(norm) * (float) Math.pow(Math.abs(norm), curveExp);
        }
        if (invert) norm = -norm;
        return norm * rangeDeg;
    }

    private static float wrap180(float a) {
        while (a > 180f)  a -= 360f;
        while (a < -180f) a += 360f;
        return a;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }
}
