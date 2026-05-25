package com.sdf.simwheelps4;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Calibration screen — mirrors the original SimWheel Connect's gyro controls
 * (Gyro Sensitivity 0..5, Gyro Anti-Shake 0..99, Steering center duration 0..10)
 * plus throttle/brake/clutch tuning and inverts.
 */
public class CalibrationActivity extends Activity
        implements GyroSource.Listener, ControllerInput.Listener {

    private Settings settings;
    private ButtonMap map;
    private GyroSource gyro;
    private ControllerInput ctlIn;

    private WheelView wheel;
    private TextView  valSteer;
    private TextView  lblRange, lblSens, lblAntiShake, lblCenter,
                      lblTMin, lblTMax, lblTCurve;
    private SeekBar   seekRange, seekSens, seekAntiShake, seekCenter,
                      seekTMin, seekTMax, seekTCurve;
    private CheckBox  chkInvert, chkShowAngle, chkInvThr, chkInvBrk, chkClutch;
    private ProgressBar barL2, barR2;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);

        settings = new Settings(this);
        map      = new ButtonMap();
        map.fromJson(settings.mappingProfileJson);

        gyro = new GyroSource(this, this);
        applyGyroSettings();
        gyro.setSource(settings.gyroSource);

        ctlIn = new ControllerInput(this, map, settings);

        wheel    = (WheelView) findViewById(R.id.wheel);
        valSteer = (TextView)  findViewById(R.id.val_steer_deg);
        barL2    = (ProgressBar) findViewById(R.id.bar_l2);
        barR2    = (ProgressBar) findViewById(R.id.bar_r2);

        lblRange     = (TextView) findViewById(R.id.lbl_range_val);
        lblSens      = (TextView) findViewById(R.id.lbl_sens_val);
        lblAntiShake = (TextView) findViewById(R.id.lbl_antishake_val);
        lblCenter    = (TextView) findViewById(R.id.lbl_center_val);
        lblTMin      = (TextView) findViewById(R.id.lbl_trig_min_val);
        lblTMax      = (TextView) findViewById(R.id.lbl_trig_max_val);
        lblTCurve    = (TextView) findViewById(R.id.lbl_trig_curve_val);
        seekRange     = (SeekBar) findViewById(R.id.seek_range);
        seekSens      = (SeekBar) findViewById(R.id.seek_sens);
        seekAntiShake = (SeekBar) findViewById(R.id.seek_antishake);
        seekCenter    = (SeekBar) findViewById(R.id.seek_center);
        seekTMin      = (SeekBar) findViewById(R.id.seek_trig_min);
        seekTMax      = (SeekBar) findViewById(R.id.seek_trig_max);
        seekTCurve    = (SeekBar) findViewById(R.id.seek_trig_curve);
        chkInvert     = (CheckBox) findViewById(R.id.chk_invert);
        chkShowAngle  = (CheckBox) findViewById(R.id.chk_show_angle);
        chkInvThr     = (CheckBox) findViewById(R.id.chk_invert_throttle);
        chkInvBrk     = (CheckBox) findViewById(R.id.chk_invert_brake);
        chkClutch     = (CheckBox) findViewById(R.id.chk_clutch);

        seekRange    .setProgress(clampInt(Math.round(settings.steeringRangeDeg - 90f), 0, 2430));
        seekSens     .setProgress(clampInt(Math.round(settings.gyroSensitivity * 10f), 0, 50));
        seekAntiShake.setProgress(clampInt(Math.round(settings.gyroAntiShake), 0, 99));
        seekCenter   .setProgress(clampInt(Math.round(settings.centerDurationSec * 10f), 0, 100));
        seekTMin     .setProgress(clampInt(Math.round(settings.triggerMin * 1000f), 0, 500));
        seekTMax     .setProgress(clampInt(Math.round(settings.triggerMax * 1000f), 100, 1000));
        seekTCurve   .setProgress(clampInt(Math.round(settings.triggerCurve * 100f), 20, 200));
        chkInvert    .setChecked(settings.invertSteering);
        chkShowAngle .setChecked(settings.showSteeringAngle);
        chkInvThr    .setChecked(settings.invertThrottle);
        chkInvBrk    .setChecked(settings.invertBrake);
        chkClutch    .setChecked(settings.clutchEnabled);
        updateLabels();

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int id = sb.getId();
                if      (id == R.id.seek_range)        settings.steeringRangeDeg  = p + 90f;
                else if (id == R.id.seek_sens)         settings.gyroSensitivity   = p / 10f;
                else if (id == R.id.seek_antishake)    settings.gyroAntiShake     = p;
                else if (id == R.id.seek_center)       settings.centerDurationSec = p / 10f;
                else if (id == R.id.seek_trig_min)     settings.triggerMin        = p / 1000f;
                else if (id == R.id.seek_trig_max)     settings.triggerMax        = p / 1000f;
                else if (id == R.id.seek_trig_curve)   settings.triggerCurve      = Math.max(0.2f, p / 100f);
                settings.save();
                applyGyroSettings();
                updateLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        seekRange.setOnSeekBarChangeListener(l);
        seekSens.setOnSeekBarChangeListener(l);
        seekAntiShake.setOnSeekBarChangeListener(l);
        seekCenter.setOnSeekBarChangeListener(l);
        seekTMin.setOnSeekBarChangeListener(l);
        seekTMax.setOnSeekBarChangeListener(l);
        seekTCurve.setOnSeekBarChangeListener(l);

        CompoundButton.OnCheckedChangeListener cb = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                int id = b.getId();
                if      (id == R.id.chk_invert)          { settings.invertSteering    = v; gyro.setInvert(v); }
                else if (id == R.id.chk_show_angle)      { settings.showSteeringAngle = v; valSteer.setVisibility(v ? View.VISIBLE : View.GONE); }
                else if (id == R.id.chk_invert_throttle) { settings.invertThrottle    = v; }
                else if (id == R.id.chk_invert_brake)    { settings.invertBrake       = v; }
                else if (id == R.id.chk_clutch)          { settings.clutchEnabled     = v; }
                settings.save();
            }
        };
        chkInvert    .setOnCheckedChangeListener(cb);
        chkShowAngle .setOnCheckedChangeListener(cb);
        chkInvThr    .setOnCheckedChangeListener(cb);
        chkInvBrk    .setOnCheckedChangeListener(cb);
        chkClutch    .setOnCheckedChangeListener(cb);

        findViewById(R.id.btn_center).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gyro.recenter(); }
        });
        findViewById(R.id.btn_reset_calib).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetDefaults(); }
        });

        valSteer.setVisibility(settings.showSteeringAngle ? View.VISIBLE : View.GONE);
    }

    private void applyGyroSettings() {
        gyro.setRangeDeg(settings.steeringRangeDeg);
        gyro.setSensitivity(settings.gyroSensitivity);
        gyro.setAntiShake(settings.gyroAntiShake);
        gyro.setCenterDurationSec(settings.centerDurationSec);
        gyro.setInvert(settings.invertSteering);
    }

    private void resetDefaults() {
        settings.steeringRangeDeg  = 900f;
        settings.gyroSensitivity   = 1f;
        settings.gyroAntiShake     = 80f;
        settings.centerDurationSec = 0f;
        settings.invertSteering    = false;
        settings.showSteeringAngle = true;
        settings.triggerMin   = 0f;
        settings.triggerMax   = 1f;
        settings.triggerCurve = 1f;
        settings.invertThrottle = false;
        settings.invertBrake    = false;
        settings.clutchEnabled  = false;
        settings.save();
        seekRange.setProgress(810);
        seekSens.setProgress(10);
        seekAntiShake.setProgress(80);
        seekCenter.setProgress(0);
        seekTMin.setProgress(0);
        seekTMax.setProgress(1000);
        seekTCurve.setProgress(100);
        chkInvert.setChecked(false);
        chkShowAngle.setChecked(true);
        chkInvThr.setChecked(false);
        chkInvBrk.setChecked(false);
        chkClutch.setChecked(false);
        applyGyroSettings();
        updateLabels();
    }

    @Override protected void onResume() { super.onResume(); gyro.start(); }
    @Override protected void onPause()  { super.onPause();  gyro.stop();  }

    private void updateLabels() {
        lblRange    .setText(String.format("Steering range: %.0f°  (must match PC receiver's userRange)", settings.steeringRangeDeg));
        lblSens     .setText(String.format("Gyro Sensitivity: %.1f  (Default 1, higher = more sensitive, max 5)", settings.gyroSensitivity));
        lblAntiShake.setText(String.format("Gyro Anti-Shake: %.0f  (0 = no filter, 99 = max smooth)", settings.gyroAntiShake));
        if (settings.centerDurationSec <= 0.01f)
            lblCenter.setText("Steering center duration: off  (auto-return to center in seconds, 0 = off)");
        else
            lblCenter.setText(String.format("Steering center duration: %.1f s  (auto-return)", settings.centerDurationSec));
        lblTMin  .setText(String.format("Trigger deadzone (min): %.2f",   settings.triggerMin));
        lblTMax  .setText(String.format("Trigger saturation (max): %.2f", settings.triggerMax));
        lblTCurve.setText(String.format("Trigger response curve: %.2f",   settings.triggerCurve));
    }

    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    @Override public void onSteeringChanged(final float angleDeg) {
        ui.post(new Runnable() {
            @Override public void run() {
                wheel.set(-angleDeg, settings.steeringRangeDeg);
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
