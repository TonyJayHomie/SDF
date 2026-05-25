package com.sdf.simwheelps4;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Touch-wheel input. The user spins a steering wheel with one or two fingers and
 * we report the cumulative rotation as a steering angle in degrees, clamped to
 * ±rangeDeg. Optional auto-return-to-center bleeds the angle back to zero over
 * `centerDurationSec` seconds when the user lifts their fingers.
 *
 * Mirrors the original SimWheel Connect's "Using touch wheel" mode.
 */
public class TouchWheel extends View {

    public interface Listener { void onSteeringChanged(float angleDeg); }

    private final Paint rim   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spoke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot   = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float angleDeg = 0f;
    private float rangeDeg = 900f;
    private float centerDurationSec = 0f;
    private float lastTouchAngle = Float.NaN;
    private boolean inverted = false;
    private Listener listener;
    private long lastUpNs = 0;
    private final long FRAME_NS = 16_000_000L;

    public TouchWheel(Context c) { super(c); init(); }
    public TouchWheel(Context c, AttributeSet a) { super(c, a); init(); }
    public TouchWheel(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(20f);
        rim.setColor(Color.parseColor("#1F8FFF"));
        spoke.setStyle(Paint.Style.STROKE);
        spoke.setStrokeWidth(10f);
        spoke.setColor(Color.parseColor("#1F8FFF"));
        hub.setStyle(Paint.Style.FILL);
        hub.setColor(Color.parseColor("#114B85"));
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(Color.parseColor("#21D07A"));
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setRangeDeg(float r)    { rangeDeg = Math.max(45f, r); clampAngle(); }
    public void setCenterDurationSec(float s) { centerDurationSec = Math.max(0f, Math.min(10f, s)); }
    public void setInverted(boolean v)  { inverted = v; }
    public void recenter() { angleDeg = 0f; invalidate(); if (listener != null) listener.onSteeringChanged(0f); }
    public float getAngleDeg() { return inverted ? -angleDeg : angleDeg; }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float cx = getWidth()  / 2f;
        float cy = getHeight() / 2f;
        float dx = ev.getX() - cx;
        float dy = ev.getY() - cy;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                lastTouchAngle = (float) Math.toDegrees(Math.atan2(dy, dx));
                return true;
            case MotionEvent.ACTION_MOVE: {
                float a = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (!Float.isNaN(lastTouchAngle)) {
                    float d = wrap180(a - lastTouchAngle);
                    angleDeg += d;
                    clampAngle();
                    fire();
                    invalidate();
                }
                lastTouchAngle = a;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                lastTouchAngle = Float.NaN;
                if (centerDurationSec > 0f) postOnAnimationCenter(System.nanoTime());
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private void postOnAnimationCenter(final long startNs) {
        post(new Runnable() {
            long prev = startNs;
            @Override public void run() {
                long now = System.nanoTime();
                float dt = (now - prev) / 1_000_000_000f;
                prev = now;
                if (Math.abs(angleDeg) < 0.5f || centerDurationSec <= 0f) {
                    angleDeg = 0f;
                    fire();
                    invalidate();
                    return;
                }
                float decay = dt / centerDurationSec;
                if (decay > 1f) decay = 1f;
                angleDeg -= angleDeg * decay;
                fire();
                invalidate();
                postDelayed(this, 16);
            }
        });
    }

    private void clampAngle() {
        if (angleDeg >  rangeDeg) angleDeg =  rangeDeg;
        if (angleDeg < -rangeDeg) angleDeg = -rangeDeg;
    }

    private void fire() {
        if (listener != null) listener.onSteeringChanged(inverted ? -angleDeg : angleDeg);
    }

    private static float wrap180(float a) {
        while (a >  180f) a -= 360f;
        while (a < -180f) a += 360f;
        return a;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r  = Math.min(w, h) / 2f - 26f;
        c.save();
        c.rotate(angleDeg, cx, cy);
        c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 0, 360, false, rim);
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(90 + i * 120);
            float x = (float)(cx + Math.cos(a) * (r - 10));
            float y = (float)(cy + Math.sin(a) * (r - 10));
            c.drawLine(cx, cy, x, y, spoke);
        }
        c.drawCircle(cx, cy, r * 0.22f, hub);
        c.drawCircle(cx, cy - r * 0.78f, r * 0.07f, dot);
        c.restore();
    }
}
