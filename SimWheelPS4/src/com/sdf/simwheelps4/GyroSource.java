package com.sdf.simwheelps4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import java.util.List;

/**
 * VERBATIM port of your working Kotlin gyro code from the prior session:
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
 * Line-for-line port to Java. Nothing added to the math. The only non-verbatim
 * bits are the scaffolding to start/stop the listener and pick which sensor we
 * listen to (phone vs. controller IMU, source picker on the main screen).
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

    /* Non-verbatim knobs preserved as no-ops for compatibility with Calibration UI.
       They do NOTHING to the math. The math is verbatim. */
    public void setRangeDeg(float v)          { /* no-op — verbatim uses fixed 900 */ }
    public void setSensitivity(float v)       { /* no-op — verbatim uses fixed *10 */ }
    public void setAntiShake(float v)         { /* no-op */ }
    public void setCenterDurationSec(float v) { /* no-op */ }
    public void setInvert(boolean v)          { /* no-op */ }
    public void recenter()                    { /* no-op — verbatim has no recenter */ }

    public void setSource(String src) {
        if (src == null) src = SRC_PHONE;
        if (src.equals(source)) return;
        source = src;
        boolean wasOn = registered;
        if (wasOn) stop();
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

    public boolean isControllerGyroAvailable() { return findControllerGyro() != null; }

    private Sensor pickSensor() {
        if (SRC_CONTROLLER.equals(source)) {
            Sensor s = findControllerGyro();
            if (s != null) return s;
        }
        // Verbatim: TYPE_GAME_ROTATION_VECTOR with TYPE_ROTATION_VECTOR fallback.
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
        } catch (Throwable ignored) {}
        return null;
    }

    private void reportAvailability() {
        if (availability == null) return;
        Sensor s = findControllerGyro();
        availability.onControllerGyroAvailable(s != null, s == null ? "" : s.getName());
    }

    /* ====== VERBATIM onSensorChanged (Kotlin → Java line-for-line) ====== */
    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] rotationMatrix = new float[9];
        float[] orientation    = new float[3];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        float rollRad = orientation[2];
        double rollDeg = Math.toDegrees((double) rollRad);
        double steering = Math.max(-900.0, Math.min(900.0, rollDeg * 10.0));
        if (listener != null) listener.onSteeringChanged((float) steering);
    }
    /* ===================================================================== */

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }
}
