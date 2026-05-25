package com.sdf.simwheelps4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import java.util.List;

/**
 * Steering source matching the original SimWheel Connect (Flutter) gyro logic:
 *   - Sensor: raw TYPE_GYROSCOPE (rad/s angular velocity)
 *   - Integrate ω about the device's long axis (Y) → wheel angle (deg)
 *   - Apply low-pass filter ("Gyro Anti-Shake" 0..99, default 80)
 *   - Apply sensitivity multiplier ("Gyro Sensitivity" 0..5, default 1)
 *   - Optional auto-return to center over N seconds (0 = off)
 *   - Recenter zeroes the integrator (used on Connect / on user tap)
 *
 * Switchable input via setSource():
 *   PHONE      → phone's own TYPE_GYROSCOPE
 *   CONTROLLER → any TYPE_GYROSCOPE published as a dynamic sensor by a paired
 *                DualShock 4 / DualSense (Android 7.0+).
 *
 * Output: steering angle in degrees, 0 = center, positive = clockwise,
 * clamped to ±rangeDeg (matches the receiver's userRange).
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

    // Calibration knobs (UI-mapped, match original app's vocabulary)
    private float rangeDeg            = 900f;
    private float sensitivity         = 1f;     // 0..5, default 1
    private float antiShake           = 80f;    // 0..99, default 80
    private float centerDurationSec   = 0f;     // 0 disables auto-center; >0 = seconds to fully return
    private boolean invert            = false;

    // Integrator state
    private float angleDeg     = 0f;     // current steering output
    private float filteredRate = 0f;     // low-passed rad/s
    private long  lastNs       = 0;

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

    public void setAvailabilityListener(AvailabilityListener l) {
        this.availability = l;
        reportAvailability();
    }

    /* ----- knobs ----- */
    public void setRangeDeg(float v)           { rangeDeg          = Math.max(45f, v); clampAngle(); }
    public void setSensitivity(float v)        { sensitivity       = Math.max(0f, Math.min(5f, v)); }
    public void setAntiShake(float v)          { antiShake         = Math.max(0f, Math.min(99f, v)); }
    public void setCenterDurationSec(float v)  { centerDurationSec = Math.max(0f, Math.min(10f, v)); }
    public void setInvert(boolean v)           { invert            = v; }

    public float getCurrentAngle() { return invert ? -angleDeg : angleDeg; }

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
            lastNs = 0;
        }
    }

    public void stop() {
        if (registered) {
            sm.unregisterListener(this);
            registered = false;
            sensor = null;
        }
    }

    public void recenter() {
        angleDeg     = 0f;
        filteredRate = 0f;
        if (listener != null) listener.onSteeringChanged(0f);
    }

    public boolean isControllerGyroAvailable() { return findControllerGyro() != null; }

    /* ----- sensor selection ----- */

    private Sensor pickSensor() {
        if (SRC_CONTROLLER.equals(source)) {
            Sensor s = findControllerGyro();
            if (s != null) return s;
        }
        return sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    private Sensor findControllerGyro() {
        if (Build.VERSION.SDK_INT < 24 || sm == null) return null;
        try {
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

    /* ----- core loop ----- */

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;

        // Pick the long-axis component. For a phone held in landscape (long axis L-R)
        // tilted as a steering wheel, rotation about the device Y axis is the natural
        // "wheel twist". Sign matches the original app's convention (positive = right).
        float rawRate = event.values[1]; // rad/s about Y

        long now = event.timestamp;
        if (lastNs == 0) { lastNs = now; filteredRate = rawRate; return; }
        float dt = Math.min(0.05f, (now - lastNs) / 1_000_000_000f);
        lastNs = now;

        // "Gyro Anti-Shake": low-pass filter on the angular rate.
        // antiShake = 0  → no smoothing (alpha = 1)
        // antiShake = 99 → very heavy smoothing (alpha ~ 0.01)
        float alpha = 1f - (antiShake / 100f);
        if (alpha < 0.01f) alpha = 0.01f;
        filteredRate = filteredRate + alpha * (rawRate - filteredRate);

        // Integrate rate → angle. Sensitivity scales how much wheel-turn comes out of
        // a given physical twist (matches original: default 1, max 5).
        float deltaDeg = (float) Math.toDegrees(filteredRate * dt) * sensitivity;
        angleDeg += deltaDeg;

        // Optional auto-return to center.
        if (centerDurationSec > 0f) {
            float decay = dt / centerDurationSec;
            if (decay > 1f) decay = 1f;
            angleDeg -= angleDeg * decay;
        }

        clampAngle();
        if (listener != null) listener.onSteeringChanged(invert ? -angleDeg : angleDeg);
    }

    private void clampAngle() {
        if (angleDeg >  rangeDeg) angleDeg =  rangeDeg;
        if (angleDeg < -rangeDeg) angleDeg = -rangeDeg;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }
}
