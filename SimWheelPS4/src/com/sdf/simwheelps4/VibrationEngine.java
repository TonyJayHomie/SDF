package com.sdf.simwheelps4;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Vibrator;
import java.lang.reflect.Method;
import android.util.Log;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the phone vibrator from two sources:
 *   (a) local heuristics derived from the user's own driving inputs (hard brake,
 *       rapid steering jerk = "rough road", sustained full throttle = "engine revs")
 *   (b) an optional UDP "feedback" channel on local port 4568 — a future patch
 *       to the PC receiver can stream {"rumble":0..1,"durationMs":N,"reason":"..."}
 *       packets, and we already honor them.
 *
 * Intensity is scaled by a single user-controlled master (0..1).
 */
public class VibrationEngine {
    private static final String TAG = "VibEng";

    private final Context ctx;
    private final Settings settings;
    private final Vibrator vibrator;

    private final HandlerThread thread;
    private final Handler handler;

    private float lastThrottle = 0f;
    private float lastBrake    = 0f;
    private long  lastSampleMs = 0;
    private float steerJerkEnv = 0f;
    private float lastSteer    = 0f;
    private long  lastVibrateMs= 0;

    // UDP feedback listener
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private DatagramSocket fbSocket;
    private Thread fbThread;

    public VibrationEngine(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        this.vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        this.thread = new HandlerThread("VibEng");
        this.thread.start();
        this.handler = new Handler(this.thread.getLooper());
    }

    public boolean isAvailable() { return vibrator != null && vibrator.hasVibrator(); }

    public void start() {
        if (listening.compareAndSet(false, true)) {
            fbThread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        fbSocket = new DatagramSocket(4568);
                        fbSocket.setSoTimeout(2000);
                        byte[] buf = new byte[1024];
                        while (listening.get()) {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            try {
                                fbSocket.receive(p);
                                String s = new String(p.getData(), 0, p.getLength(), "UTF-8");
                                JSONObject o = new JSONObject(s);
                                double rumble = o.optDouble("rumble", 0);
                                int durationMs = o.optInt("durationMs", 60);
                                if (rumble > 0) vibrateScaled((float) rumble, durationMs);
                            } catch (java.net.SocketTimeoutException te) {
                                // loop back and re-check listening flag
                            } catch (Exception e) {
                                Log.w(TAG, "feedback packet ignored: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "feedback listener stopped: " + e.getMessage());
                    } finally {
                        if (fbSocket != null) fbSocket.close();
                    }
                }
            }, "VibFb");
            fbThread.setDaemon(true);
            fbThread.start();
        }
    }

    public void stop() {
        listening.set(false);
        if (fbSocket != null) { try { fbSocket.close(); } catch (Exception ignored) {} }
        if (vibrator != null) try { vibrator.cancel(); } catch (Exception ignored) {}
    }

    /** Call with the latest driving state on every send tick. */
    public void onDrivingState(float steeringDeg, float throttle, float brake) {
        long now = SystemClock.elapsedRealtime();
        long dt = (lastSampleMs == 0) ? 16 : Math.max(1, now - lastSampleMs);
        lastSampleMs = now;

        // Hard brake event: brake high + throttle dropped quickly
        boolean hardBrake = (brake > settings.vibCollisionThr) && (lastThrottle - throttle > 0.4f);

        // Steering jerk envelope (deg/s, low-pass)
        float steerRate = Math.abs(steeringDeg - lastSteer) * (1000f / dt);
        steerJerkEnv = steerJerkEnv * 0.85f + steerRate * 0.15f;
        boolean roughRoad = steerJerkEnv > settings.vibRoughThr;

        // Engine revs: sustained high throttle pulses (chatter)
        boolean revs = (throttle > 0.85f) && (Math.random() < 0.04f);

        if (hardBrake)      vibrateScaled(0.95f * settings.vibRevsScale, 110);
        else if (roughRoad) vibrateScaled(0.45f * settings.vibRevsScale, 35);
        else if (revs)      vibrateScaled(0.30f * settings.vibRevsScale, 25);

        lastThrottle = throttle;
        lastBrake    = brake;
        lastSteer    = steeringDeg;
    }

    public void vibrateTest() {
        vibrateScaled(1.0f, 250);
    }

    /** Short tick when a controller button is pressed (Haptic Feedback). */
    public void vibrateButton() {
        vibrateScaled(0.5f, 18);
    }

    private void vibrateScaled(float intensity01, int durationMs) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        float scaled = clamp(intensity01 * settings.vibMaster, 0f, 1f);
        if (scaled <= 0f) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastVibrateMs < 30) return; // throttle to avoid stutter
        lastVibrateMs = now;

        final int dur = Math.max(15, durationMs);
        final int amp = Math.max(1, Math.min(255, Math.round(scaled * 255f)));

        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        // Use reflection: VibrationEffect is API 26+, but the rest
                        // of the app must compile against API 23.
                        Class<?> effCls = Class.forName("android.os.VibrationEffect");
                        Method create   = effCls.getMethod("createOneShot", long.class, int.class);
                        Object eff      = create.invoke(null, (long) dur, amp);
                        Method vMethod  = Vibrator.class.getMethod("vibrate", effCls);
                        vMethod.invoke(vibrator, eff);
                    } else {
                        // API 23–25: no amplitude control. Use duration only.
                        vibrator.vibrate(dur);
                    }
                } catch (Exception e) {
                    try { vibrator.vibrate(dur); } catch (Exception ignored) {}
                }
            }
        });
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
