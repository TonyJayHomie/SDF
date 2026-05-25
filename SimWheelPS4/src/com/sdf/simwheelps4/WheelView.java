package com.sdf.simwheelps4;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom view: draws a steering wheel and shows current rotation. The wheel
 * is rotated by the supplied angle (clamped to ±range°) — easy at-a-glance
 * feedback during calibration.
 */
public class WheelView extends View {

    private final Paint rim   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spoke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float angleDeg = 0f;
    private float rangeDeg = 900f;

    public WheelView(Context c) { super(c); init(); }
    public WheelView(Context c, AttributeSet a) { super(c, a); init(); }
    public WheelView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(14f);
        rim.setColor(Color.parseColor("#1F8FFF"));

        spoke.setStyle(Paint.Style.STROKE);
        spoke.setStrokeWidth(8f);
        spoke.setColor(Color.parseColor("#1F8FFF"));

        hub.setStyle(Paint.Style.FILL);
        hub.setColor(Color.parseColor("#114B85"));

        dot.setStyle(Paint.Style.FILL);
        dot.setColor(Color.parseColor("#21D07A"));

        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(2f);
        tick.setColor(Color.parseColor("#30363D"));
    }

    public void set(float angleDeg, float rangeDeg) {
        this.angleDeg = clamp(angleDeg, -rangeDeg, rangeDeg);
        this.rangeDeg = rangeDeg;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r  = Math.min(w, h) / 2f - 18f;

        // Background range arc (full circle, dim ticks every 45°)
        for (int t = 0; t < 360; t += 30) {
            double a = Math.toRadians(t - 90);
            float x1 = (float)(cx + Math.cos(a) * (r - 4));
            float y1 = (float)(cy + Math.sin(a) * (r - 4));
            float x2 = (float)(cx + Math.cos(a) * (r + 6));
            float y2 = (float)(cy + Math.sin(a) * (r + 6));
            c.drawLine(x1, y1, x2, y2, tick);
        }

        c.save();
        c.rotate(angleDeg, cx, cy);

        // rim
        RectF bb = new RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(bb, 0, 360, false, rim);

        // spokes (3-spoke wheel)
        for (int i = 0; i < 3; i++) {
            double a = Math.toRadians(90 + i * 120);
            float x = (float)(cx + Math.cos(a) * (r - 8));
            float y = (float)(cy + Math.sin(a) * (r - 8));
            c.drawLine(cx, cy, x, y, spoke);
        }

        // hub
        c.drawCircle(cx, cy, r * 0.22f, hub);

        // top indicator dot
        c.drawCircle(cx, cy - r * 0.78f, r * 0.06f, dot);

        c.restore();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
