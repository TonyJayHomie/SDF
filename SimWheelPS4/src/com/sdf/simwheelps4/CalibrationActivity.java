package com.sdf.simwheelps4;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Live calibration screen. Wraps GyroSource and ControllerInput just like
 * MainActivity, but renders the results into the WheelView and trigger bars
 * so the user can tune sensitivity / curves and see the effect immediately.
 */
public class CalibrationActivity extends Activity
        implements GyroSource.Listener, ControllerInput.Listener {

    private Settings settings;
    private ButtonMap map;
    private GyroSource gyro;
    private ControllerInput ctlIn;

    private WheelView wheel;
    private TextView  valSteer;
    private TextView  lblRange, lblDz, lblTilt, lblCurve, lblTMin, lblTMax, lblTCurve;
    private SeekBar   seekRange, seekDz, seekTilt, seekCurve, seekTMin, seekTMax, seekTCurve;
    private CheckBox  chkInvert;
    private ProgressBar barL2, barR2;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);

        settings = new Settings(this);
        map      = new ButtonMap();
        if (settings.mappingProfileJson != null) map.fromJson(settings.mappingProfileJson);
        gyro     = new GyroSource(this, this);
        gyro.setRangeDeg(settings.steeringRangeDeg);
        gyro.setPhysicalTiltDeg(settings.physicalTiltRangeDeg);
        gyro.setDeadzoneDeg(settings.gyroDeadzoneDeg);
        gyro.setCurveExp(settings.steeringCurve);
        gyro.setInvert(settings.invertSteering);
        gyro.setSource(settings.gyroSource);
        ctlIn    = new ControllerInput(this, map, settings);

        wheel    = (WheelView) findViewById(R.id.wheel);
        valSteer = (TextView) findViewById(R.id.val_steer_deg);
        barL2    = (ProgressBar) findViewById(R.id.bar_l2);
        barR2    = (ProgressBar) findViewById(R.id.bar_r2);

        lblRange   = (TextView) findViewById(R.id.lbl_range_val);
        lblDz      = (TextView) findViewById(R.id.lbl_deadzone_val);
        lblTilt    = (TextView) findViewById(R.id.lbl_tilt_val);
        lblCurve   = (TextView) findViewById(R.id.lbl_curve_val);
        lblTMin    = (TextView) findViewById(R.id.lbl_trig_min_val);
        lblTMax    = (TextView) findViewById(R.id.lbl_trig_max_val);
        lblTCurve  = (TextView) findViewById(R.id.lbl_trig_curve_val);
        seekRange  = (SeekBar)  findViewById(R.id.seek_range);
        seekDz     = (SeekBar)  findViewById(R.id.seek_deadzone);
        seekTilt   = (SeekBar)  findViewById(R.id.seek_tilt);
        seekCurve  = (SeekBar)  findViewById(R.id.seek_curve);
        seekTMin   = (SeekBar)  findViewById(R.id.seek_trig_min);
        seekTMax   = (SeekBar)  findViewById(R.id.seek_trig_max);
        seekTCurve = (SeekBar)  findViewById(R.id.seek_trig_curve);
        chkInvert  = (CheckBox) findViewById(R.id.chk_invert);

        // Initial slider positions from settings.
        seekRange .setProgress(clampInt(Math.round(settings.steeringRangeDeg - 90f), 0, 2430));
        seekDz    .setProgress(clampInt(Math.round(settings.gyroDeadzoneDeg * 10f), 0, 200));
        seekTilt  .setProgress(clampInt(Math.round(settings.physicalTiltRangeDeg) - 5, 0, 175));
        seekCurve .setProgress(clampInt(Math.round(settings.steeringCurve * 100f), 20, 200));
        seekTMin  .setProgress(clampInt(Math.round(settings.triggerMin * 1000f), 0, 500));
        seekTMax  .setProgress(clampInt(Math.round(settings.triggerMax * 1000f), 100, 1000));
        seekTCurve.setProgress(clampInt(Math.round(settings.triggerCurve * 100f), 20, 200));
        chkInvert.setChecked(settings.invertSteering);
        chkInvert.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                settings.invertSteering = v;
                settings.save();
                gyro.setInvert(v);
            }
        });
        updateLabels();

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int id = sb.getId();
                if (id == R.id.seek_range)           settings.steeringRangeDeg     = p + 90f;
                else if (id == R.id.seek_deadzone)   settings.gyroDeadzoneDeg      = p / 10f;
                else if (id == R.id.seek_tilt)       settings.physicalTiltRangeDeg = p + 5f;
                else if (id == R.id.seek_curve)      settings.steeringCurve        = Math.max(0.2f, p / 100f);
                else if (id == R.id.seek_trig_min)   settings.triggerMin           = p / 1000f;
                else if (id == R.id.seek_trig_max)   settings.triggerMax           = p / 1000f;
                else if (id == R.id.seek_trig_curve) settings.triggerCurve         = Math.max(0.2f, p / 100f);
                settings.save();
                gyro.setRangeDeg(settings.steeringRangeDeg);
                gyro.setPhysicalTiltDeg(settings.physicalTiltRangeDeg);
                gyro.setDeadzoneDeg(settings.gyroDeadzoneDeg);
                gyro.setCurveExp(settings.steeringCurve);
                updateLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        seekRange.setOnSeekBarChangeListener(l);
        seekDz.setOnSeekBarChangeListener(l);
        seekTilt.setOnSeekBarChangeListener(l);
        seekCurve.setOnSeekBarChangeListener(l);
        seekTMin.setOnSeekBarChangeListener(l);
        seekTMax.setOnSeekBarChangeListener(l);
        seekTCurve.setOnSeekBarChangeListener(l);

        findViewById(R.id.btn_center).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gyro.recenter(); }
        });
        findViewById(R.id.btn_reset_calib).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                settings.steeringRangeDeg     = 900f;
                settings.physicalTiltRangeDeg = 45f;
                settings.gyroDeadzoneDeg      = 2f;
                settings.steeringCurve        = 1f;
                settings.invertSteering       = false;
                settings.triggerMin = 0f;
                settings.triggerMax = 1f;
                settings.triggerCurve = 1f;
                settings.save();
                seekRange.setProgress(810);
                seekDz.setProgress(20);
                seekTilt.setProgress(40);
                seekCurve.setProgress(100);
                seekTMin.setProgress(0);
                seekTMax.setProgress(1000);
                seekTCurve.setProgress(100);
                chkInvert.setChecked(false);
                gyro.setRangeDeg(900);
                gyro.setPhysicalTiltDeg(45);
                gyro.setDeadzoneDeg(2);
                gyro.setCurveExp(1);
                gyro.setInvert(false);
                updateLabels();
            }
        });
    }

    @Override protected void onResume() { super.onResume(); gyro.start(); }
    @Override protected void onPause()  { super.onPause();  gyro.stop();  }

    private void updateLabels() {
        lblRange .setText(String.format("Steering range: %.0f°  (must match PC receiver's userRange)", settings.steeringRangeDeg));
        lblDz    .setText(String.format("Gyro deadzone: %.1f°", settings.gyroDeadzoneDeg));
        lblTilt  .setText(String.format("Tilt for full lock: %.0f°", settings.physicalTiltRangeDeg));
        lblCurve .setText(String.format("Response curve exponent: %.2f", settings.steeringCurve));
        lblTMin  .setText(String.format("Trigger deadzone (min): %.2f", settings.triggerMin));
        lblTMax  .setText(String.format("Trigger saturation (max): %.2f", settings.triggerMax));
        lblTCurve.setText(String.format("Trigger response curve: %.2f", settings.triggerCurve));
    }

    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    @Override public void onSteeringChanged(final float angleDeg) {
        ui.post(new Runnable() {
            @Override public void run() {
                wheel.set(-angleDeg, settings.steeringRangeDeg); // negate for natural visual rotation
                valSteer.setText(String.format("%+.1f°", angleDeg));
            }
        });
    }

    @Override public void onTriggers(final float throttle, final float brake) {
        ui.post(new Runnable() {
            @Override public void run() {
                barR2.setProgress(Math.round(throttle * 1000f));
                barL2.setProgress(Math.round(brake * 1000f));
            }
        });
    }

    @Override public void onButton(int androidKeyCode, boolean pressed, String deviceName) {}
    @Override public void onLeftStick(float x, float y) {}
    @Override public void onRightStick(float x, float y) {}

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (ctlIn != null && ctlIn.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ctlIn != null && ctlIn.handleMotion(ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }
}
