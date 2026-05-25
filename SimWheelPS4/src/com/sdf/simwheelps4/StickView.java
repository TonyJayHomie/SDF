package com.sdf.simwheelps4;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Small 2D crosshair showing the current position of one analog stick (x,y in [-1,1]). */
public class StickView extends View {

    private final Paint bg     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float x = 0f, y = 0f;

    public StickView(Context c) { super(c); init(); }
    public StickView(Context c, AttributeSet a) { super(c, a); init(); }
    public StickView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        bg.setStyle(Paint.Style.FILL);
        bg.setColor(Color.parseColor("#1F2733"));
        axis.setStyle(Paint.Style.STROKE);
        axis.setStrokeWidth(1.5f);
        axis.setColor(Color.parseColor("#30363D"));
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(Color.parseColor("#21D07A"));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f);
        border.setColor(Color.parseColor("#1F8FFF"));
    }

    /** x,y in [-1,1]. Y is inverted to match screen coords (up = -1). */
    public void set(float x, float y) {
        this.x = clamp(x); this.y = clamp(y); invalidate();
    }
    private static float clamp(float v) { return v < -1f ? -1f : (v > 1f ? 1f : v); }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f - 3f;
        float cx = w / 2f, cy = h / 2f;
        c.drawCircle(cx, cy, r, bg);
        c.drawCircle(cx, cy, r, border);
        c.drawLine(cx - r, cy, cx + r, cy, axis);
        c.drawLine(cx, cy - r, cx, cy + r, axis);
        float px = cx + x * (r - 6);
        float py = cy + y * (r - 6);
        c.drawCircle(px, py, 7f, dot);
    }
}
