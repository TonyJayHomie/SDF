package com.ik.simwheel.sim;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class SimBridge {

    // State
    public static volatile float state_steering = 0f;
    public static volatile float state_throttle = 0f;
    public static volatile float state_brake = 0f;
    public static volatile float state_clutch = 0f;
    public static volatile boolean dirty = false;

    // Settings
    public static volatile float triggerDeadzone = 0.05f;
    public static volatile float triggerCurve = 1.0f;
    public static volatile int vibrationIntensity = 80;
    public static volatile float steeringSensitivity = 1.0f;
    public static volatile float maxDegrees = 900f;

    // Button states (16 buttons mapped to vJoy codes)
    public static final int[] buttonMap = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    public static final boolean[] buttonState = new boolean[16];

    // Options triple-press tracking
    private static int optionsCount = 0;
    private static long optionsFirst = 0;

    // App context
    private static Context ctx;
    private static Vibrator vibrator;
    private static Handler mainHandler;

    // Debug axis readout (updated by onMotion, displayed in SettingsActivity)
    public static volatile String debugAxes = "";

    // UDP
    private static String phoneName = "SimWheelPS4";
    private static String pcAddress = null;
    private static int pcPort = 4567;
    private static DatagramSocket udpSocket;
    private static volatile boolean udpRunning = false;

    public static void install(Application app) {
        ctx = app.getApplicationContext();
        mainHandler = new Handler(Looper.getMainLooper());
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);

        // Load preferences
        SharedPreferences prefs = ctx.getSharedPreferences("SimWheelPlugin", Context.MODE_PRIVATE);
        triggerDeadzone = prefs.getFloat("triggerDeadzone", 0.05f);
        triggerCurve = prefs.getFloat("triggerCurve", 1.0f);
        vibrationIntensity = prefs.getInt("vibrationIntensity", 80);
        steeringSensitivity = prefs.getFloat("steeringSensitivity", 1.0f);
        maxDegrees = prefs.getFloat("maxDegrees", 900f);

        // Load button map
        SharedPreferences profilePrefs = ctx.getSharedPreferences("SimWheelProfiles", Context.MODE_PRIVATE);
        String activeProfile = profilePrefs.getString("activeProfile", "default");
        for (int i = 0; i < 16; i++) {
            buttonMap[i] = profilePrefs.getInt(activeProfile + "_btn_" + i, i + 1);
        }

        // Start UDP thread
        startUdpThread();

        // Apply promo
        applyPromo(ctx);
    }

    private static void applyPromo(Context appCtx) {
        SharedPreferences p = appCtx.getSharedPreferences("SimWheelPromo", Context.MODE_PRIVATE);
        long t = p.getLong("promo_install_time", 0L);
        if (t == 0L) {
            t = System.currentTimeMillis();
            p.edit().putLong("promo_install_time", t).apply();
        }
        long sixMonths = 6L * 30L * 24L * 3600L * 1000L;
        boolean active = (System.currentTimeMillis() - t) < sixMonths;
        SharedPreferences fp = appCtx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE);
        fp.edit().putBoolean("flutter.is_premium", active).apply();
    }

    private static void startUdpThread() {
        udpRunning = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    udpSocket = new DatagramSocket(pcPort);
                    udpSocket.setSoTimeout(20);
                    byte[] recvBuf = new byte[512];
                    while (udpRunning) {
                        // Send discover broadcast
                        try {
                            JSONObject disc = new JSONObject();
                            disc.put("type", "discover");
                            disc.put("phoneName", phoneName);
                            byte[] discBytes = disc.toString().getBytes("UTF-8");
                            DatagramPacket discPkt = new DatagramPacket(
                                discBytes, discBytes.length,
                                InetAddress.getByName("255.255.255.255"), pcPort);
                            udpSocket.send(discPkt);
                        } catch (Exception e) {
                            // ignore broadcast errors
                        }

                        // Listen for replies and send state
                        long lastSend = System.currentTimeMillis();
                        for (int i = 0; i < 20; i++) {
                            try {
                                DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
                                udpSocket.receive(recvPkt);
                                String msg = new String(recvBuf, 0, recvPkt.getLength(), "UTF-8");
                                handleUdpReceive(msg, recvPkt.getAddress());
                            } catch (SocketTimeoutException e) {
                                // timeout, send state if dirty
                            } catch (Exception e) {
                                // ignore
                            }

                            if (dirty || (System.currentTimeMillis() - lastSend) > 50) {
                                dirty = false;
                                lastSend = System.currentTimeMillis();
                                sendState();
                            }
                        }
                    }
                } catch (Exception e) {
                    // UDP thread died
                }
            }
        });
        t.setDaemon(true);
        t.setName("SimWheel-UDP");
        t.start();
    }

    private static void handleUdpReceive(String msg, InetAddress addr) {
        try {
            JSONObject obj = new JSONObject(msg);
            String type = obj.optString("type", "");
            if ("discover_reply".equals(type)) {
                pcAddress = addr.getHostAddress();
            } else if ("vibration".equals(type)) {
                double intensity = obj.optDouble("intensity", 0.5);
                int duration = obj.optInt("duration", 100);
                vibratePattern(1, (float) intensity, duration);
            }
        } catch (Exception e) {
            // ignore malformed packets
        }
    }

    private static void sendState() {
        if (udpSocket == null || pcAddress == null) return;
        try {
            JSONObject j = new JSONObject();
            j.put("phoneName", phoneName);
            j.put("steering", (double) state_steering);
            j.put("throttle", (double) state_throttle);
            j.put("brake", (double) state_brake);
            j.put("clutch ", (double) state_clutch);
            j.put("dx", 0.0);
            j.put("dy", 0.0);
            for (int i = 0; i < 16; i++) {
                j.put(String.valueOf(buttonMap[i]), buttonState[i]);
            }
            byte[] bytes = j.toString().getBytes("UTF-8");
            DatagramPacket pkt = new DatagramPacket(bytes, bytes.length,
                InetAddress.getByName(pcAddress), pcPort);
            udpSocket.send(pkt);
        } catch (Exception e) {
            // ignore send errors
        }
    }

    public static boolean onKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;

        // Triple Options press to open settings
        if (code == KeyEvent.KEYCODE_BUTTON_START && down) {
            long now = System.currentTimeMillis();
            if (now - optionsFirst > 1000) {
                optionsCount = 0;
                optionsFirst = now;
            }
            optionsCount++;
            if (optionsCount >= 3) {
                optionsCount = 0;
                if (ctx != null) {
                    Intent intent = new Intent();
                    intent.setClassName(ctx.getPackageName(), "com.ik.simwheel.sim.SettingsActivity");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                }
            }
        }

        // Map PS4 buttons to vJoy
        // PS4 via Android HID: X=A, O=B, Square=X, Triangle=Y
        int btnIndex = -1;
        if (code == KeyEvent.KEYCODE_BUTTON_A)            btnIndex = 0;  // X (Cross)
        else if (code == KeyEvent.KEYCODE_BUTTON_B)       btnIndex = 1;  // O (Circle)
        else if (code == KeyEvent.KEYCODE_BUTTON_X)       btnIndex = 2;  // Square
        else if (code == KeyEvent.KEYCODE_BUTTON_Y)       btnIndex = 3;  // Triangle
        else if (code == KeyEvent.KEYCODE_BUTTON_L1)      btnIndex = 4;
        else if (code == KeyEvent.KEYCODE_BUTTON_R1)      btnIndex = 5;
        else if (code == KeyEvent.KEYCODE_BUTTON_L2)      btnIndex = 6;
        else if (code == KeyEvent.KEYCODE_BUTTON_R2)      btnIndex = 7;
        else if (code == KeyEvent.KEYCODE_BUTTON_THUMBL)  btnIndex = 8;
        else if (code == KeyEvent.KEYCODE_BUTTON_THUMBR)  btnIndex = 9;
        else if (code == KeyEvent.KEYCODE_BUTTON_SELECT)  btnIndex = 10; // Share
        else if (code == KeyEvent.KEYCODE_BUTTON_START)   btnIndex = 11; // Options
        else if (code == KeyEvent.KEYCODE_DPAD_UP)        btnIndex = 12;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN)      btnIndex = 13;
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT)      btnIndex = 14;
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT)     btnIndex = 15;

        if (btnIndex >= 0 && btnIndex < 16) {
            buttonState[btnIndex] = down;
            dirty = true;
        }

        // Recenter on PS button
        if (code == KeyEvent.KEYCODE_BUTTON_MODE && down) {
            state_steering = 0f;
            dirty = true;
        }

        return false; // don't consume - let Flutter also receive
    }

    public static boolean onMotion(MotionEvent ev) {
        int src = ev.getSource();
        boolean isGameInput = (src & InputDevice.SOURCE_JOYSTICK) != 0
                           || (src & InputDevice.SOURCE_GAMEPAD) != 0;
        if (isGameInput) {
            float lt = ev.getAxisValue(MotionEvent.AXIS_LTRIGGER); // 17 standard
            float bk = ev.getAxisValue(MotionEvent.AXIS_BRAKE);    // 23 alternate
            // PS4 raw HID: L2 maps to AXIS_RX (12) on some Android/BT HID stacks
            float rx = ev.getAxisValue(MotionEvent.AXIS_RX);
            // RX may be bipolar (-1=rest, +1=full) — normalize to 0..1
            if (rx < 0f) rx = (rx + 1f) / 2f;

            float rt = ev.getAxisValue(MotionEvent.AXIS_RTRIGGER); // 18 standard
            float gs = ev.getAxisValue(MotionEvent.AXIS_GAS);      // 22 alternate
            // PS4 raw HID: R2 maps to AXIS_RY (13) on some Android/BT HID stacks
            float ry = ev.getAxisValue(MotionEvent.AXIS_RY);
            if (ry < 0f) ry = (ry + 1f) / 2f;

            // Record raw values for SettingsActivity diagnostics
            debugAxes = "LT=" + String.format("%.2f", lt)
                + " BK=" + String.format("%.2f", bk)
                + " RX=" + String.format("%.2f", rx)
                + " | RT=" + String.format("%.2f", rt)
                + " GS=" + String.format("%.2f", gs)
                + " RY=" + String.format("%.2f", ry)
                + " | RZ=" + String.format("%.2f", ev.getAxisValue(MotionEvent.AXIS_RZ));

            // Use whichever axis reports the highest value
            float rawBrake    = Math.max(lt, Math.max(bk, rx));
            float rawThrottle = Math.max(rt, Math.max(gs, ry));
            if (rawBrake    < 0f) rawBrake    = 0f;
            if (rawThrottle < 0f) rawThrottle = 0f;

            float brake    = applyDeadzone(rawBrake,    triggerDeadzone);
            float throttle = applyDeadzone(rawThrottle, triggerDeadzone);

            if (brake    > 0f) brake    = (float) Math.pow(brake,    triggerCurve);
            if (throttle > 0f) throttle = (float) Math.pow(throttle, triggerCurve);

            state_brake    = brake;
            state_throttle = throttle;

            // Gyro steering: AXIS_RZ = gyro Z (yaw) from PS4 via BT HID
            float gyroZ = ev.getAxisValue(MotionEvent.AXIS_RZ);
            float dtSeconds = 0.016f;
            state_steering += gyroZ * dtSeconds * (180f / (float) Math.PI) * steeringSensitivity;
            state_steering = Math.max(-maxDegrees, Math.min(maxDegrees, state_steering));

            dirty = true;
        }
        return false;
    }

    private static float applyDeadzone(float val, float deadzone) {
        if (val < deadzone) return 0f;
        return (val - deadzone) / (1f - deadzone);
    }

    public static void vibratePattern(int pattern, float intensity, int duration) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        int ms = (int)(intensity * duration);
        if (ms <= 0) ms = 1;
        try {
            if (pattern == 1) {
                // Single strong pulse (collision)
                vibrator.vibrate(ms);
            } else if (pattern == 2) {
                // Rapid short pulses (rough terrain)
                long[] timings = new long[]{0, ms / 4, ms / 4, ms / 4, ms / 4, ms / 4};
                android.os.VibrationEffect effect = android.os.VibrationEffect.createWaveform(timings, -1);
                vibrator.vibrate(effect);
            } else if (pattern == 3) {
                // Ascending (rev limiter)
                int amp = Math.min(255, (int)(intensity * 255));
                android.os.VibrationEffect effect = android.os.VibrationEffect.createOneShot(ms, amp);
                vibrator.vibrate(effect);
            }
        } catch (Exception e) {
            // Fallback to legacy vibrate
            vibrator.vibrate(ms);
        }
    }

    public static void saveProfile(String name) {
        SharedPreferences profilePrefs = ctx.getSharedPreferences("SimWheelProfiles", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = profilePrefs.edit();
        ed.putString("activeProfile", name);
        for (int i = 0; i < 16; i++) {
            ed.putInt(name + "_btn_" + i, buttonMap[i]);
        }
        ed.apply();
    }

    public static void loadProfile(String name) {
        SharedPreferences profilePrefs = ctx.getSharedPreferences("SimWheelProfiles", Context.MODE_PRIVATE);
        for (int i = 0; i < 16; i++) {
            buttonMap[i] = profilePrefs.getInt(name + "_btn_" + i, i + 1);
        }
        profilePrefs.edit().putString("activeProfile", name).apply();
    }

    public static void saveSettings() {
        SharedPreferences prefs = ctx.getSharedPreferences("SimWheelPlugin", Context.MODE_PRIVATE);
        prefs.edit()
            .putFloat("triggerDeadzone", triggerDeadzone)
            .putFloat("triggerCurve", triggerCurve)
            .putInt("vibrationIntensity", vibrationIntensity)
            .putFloat("steeringSensitivity", steeringSensitivity)
            .putFloat("maxDegrees", maxDegrees)
            .apply();
    }

    public static Context getContext() {
        return ctx;
    }
}
