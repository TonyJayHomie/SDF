package com.sdf.simwheelps4;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Adapts raw KeyEvent / MotionEvent from a paired DualShock 4 gamepad into
 * three things the rest of the app consumes:
 *
 *   1. analog throttle  ← R2 (AXIS_RTRIGGER, fallback to AXIS_GAS or AXIS_BRAKE)
 *   2. analog brake     ← L2 (AXIS_LTRIGGER, fallback to AXIS_BRAKE)
 *   3. button presses   ← passed through ButtonMap to UdpSender
 *
 * Sticks are not used for steering (gyro does that) — but L3/R3 axes are read for
 * an optional "use left stick for steering" calibration mode.
 */
public class ControllerInput {

    public interface Listener {
        void onTriggers(float throttle, float brake);
        void onButton(int androidKeyCode, boolean pressed, String deviceName);
        void onLeftStick(float x, float y);
        void onRightStick(float x, float y);
    }

    private final Listener listener;
    private final ButtonMap map;
    private final Settings settings;

    public ControllerInput(Listener l, ButtonMap map, Settings settings) {
        this.listener = l;
        this.map = map;
        this.settings = settings;
    }

    public static boolean isFromGamepad(InputDevice dev) {
        if (dev == null) return false;
        int s = dev.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    public boolean handleKey(KeyEvent ev) {
        InputDevice dev = ev.getDevice();
        if (!isFromGamepad(dev)) return false;
        if (ev.getRepeatCount() > 0) return true; // ignore key-repeat
        boolean pressed = (ev.getAction() == KeyEvent.ACTION_DOWN);
        if (listener != null) listener.onButton(ev.getKeyCode(), pressed, dev == null ? "?" : dev.getName());
        return true;
    }

    public boolean handleMotion(MotionEvent ev) {
        InputDevice dev = ev.getDevice();
        if (!isFromGamepad(dev)) return false;
        if ((ev.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return false;

        float r = getAxis(ev, MotionEvent.AXIS_RTRIGGER, 0f);
        if (r == 0f) r = getAxis(ev, MotionEvent.AXIS_GAS, 0f);
        float l = getAxis(ev, MotionEvent.AXIS_LTRIGGER, 0f);
        if (l == 0f) {
            float br = getAxis(ev, MotionEvent.AXIS_BRAKE, 0f);
            if (br > 0f) l = br;
        }

        float throttle = shapeTrigger(r);
        float brake    = shapeTrigger(l);
        if (settings.invertThrottle) throttle = 1f - throttle;
        if (settings.invertBrake)    brake    = 1f - brake;
        if (listener != null) listener.onTriggers(throttle, brake);

        float lx = getAxis(ev, MotionEvent.AXIS_X, 0f);
        float ly = getAxis(ev, MotionEvent.AXIS_Y, 0f);
        float rx = getAxis(ev, MotionEvent.AXIS_Z, 0f);
        float ry = getAxis(ev, MotionEvent.AXIS_RZ, 0f);
        if (listener != null) {
            listener.onLeftStick(lx, ly);
            listener.onRightStick(rx, ry);
        }

        // Dpad-as-axis (some DS4 firmwares report HAT_X/HAT_Y instead of dpad keys).
        float hatX = getAxis(ev, MotionEvent.AXIS_HAT_X, 0f);
        float hatY = getAxis(ev, MotionEvent.AXIS_HAT_Y, 0f);
        synthDpad(hatX, hatY);
        return true;
    }

    private float shapeTrigger(float raw) {
        if (raw <= 0f) return 0f;
        float min = settings.triggerMin;
        float max = Math.max(min + 0.01f, settings.triggerMax);
        float t = (raw - min) / (max - min);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        if (Math.abs(settings.triggerCurve - 1f) > 0.001f) {
            t = (float) Math.pow(t, settings.triggerCurve);
        }
        return t;
    }

    private static float getAxis(MotionEvent ev, int axis, float fallback) {
        InputDevice dev = ev.getDevice();
        if (dev == null) return fallback;
        InputDevice.MotionRange r = dev.getMotionRange(axis, ev.getSource());
        if (r == null) return fallback;
        float flat = r.getFlat();
        float v = ev.getAxisValue(axis);
        if (Math.abs(v) < flat) v = 0f;
        return v;
    }

    /* Track previous synthesized dpad state so we emit edges only. */
    private boolean dUp, dDn, dLf, dRt;

    private void synthDpad(float hx, float hy) {
        boolean nUp = hy < -0.5f;
        boolean nDn = hy >  0.5f;
        boolean nLf = hx < -0.5f;
        boolean nRt = hx >  0.5f;
        if (nUp != dUp) { listener.onButton(KeyEvent.KEYCODE_DPAD_UP,    nUp, "hat"); dUp = nUp; }
        if (nDn != dDn) { listener.onButton(KeyEvent.KEYCODE_DPAD_DOWN,  nDn, "hat"); dDn = nDn; }
        if (nLf != dLf) { listener.onButton(KeyEvent.KEYCODE_DPAD_LEFT,  nLf, "hat"); dLf = nLf; }
        if (nRt != dRt) { listener.onButton(KeyEvent.KEYCODE_DPAD_RIGHT, nRt, "hat"); dRt = nRt; }
    }
}
