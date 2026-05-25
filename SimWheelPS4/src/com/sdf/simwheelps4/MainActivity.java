package com.sdf.simwheelps4;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends Activity
        implements UdpSender.Listener, GyroSource.Listener, GyroSource.AvailabilityListener,
                   ControllerInput.Listener, TouchWheel.Listener {

    private Settings settings;
    private ButtonMap map;
    private UdpSender udp;
    private GyroSource gyro;
    private ControllerInput ctlIn;
    private VibrationEngine vib;

    private TextView statusLine, controllerStatus;
    private TextView valSteering, valThrottle, valBrake, valPackets, valRate;
    private ProgressBar barSteering, barThrottle, barBrake;
    private EditText editIp;
    private SeekBar  seekVibMaster, seekClutch, seekMouseSens;
    private TextView lblVibMaster, lblMouseSens, valClutch;
    private RadioGroup grpGyro;
    private RadioButton rbGyroPhone, rbGyroController, rbGyroTouch;
    private TextView gyroHint;
    private TouchWheel touchWheel;
    private LinearLayout clutchRow;
    private CheckBox chkHapticBtn, chkMouseMode;

    // Live input visualization
    private StickView stickL, stickR;
    private TextView  valStickL, valStickR;
    private LinearLayout clutchLiveRow, buttonsGrid;
    private ProgressBar barClutchLive;
    private TextView  valClutchLive;
    private final java.util.LinkedHashMap<Integer, TextView> btnCells =
            new java.util.LinkedHashMap<Integer, TextView>();
    private int colCellOff, colCellOn, colCellText, colCellTextOn;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private float liveSteer    = 0f;
    private float liveThrottle = 0f;
    private float liveBrake    = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new Settings(this);
        map      = new ButtonMap();
        if (!TextUtils.isEmpty(settings.mappingProfileJson)) map.fromJson(settings.mappingProfileJson);

        vib = new VibrationEngine(this, settings);
        vib.start();

        // Refs
        statusLine       = (TextView) findViewById(R.id.status_line);
        controllerStatus = (TextView) findViewById(R.id.controller_status);
        valSteering      = (TextView) findViewById(R.id.val_steering);
        valThrottle      = (TextView) findViewById(R.id.val_throttle);
        valBrake         = (TextView) findViewById(R.id.val_brake);
        valPackets       = (TextView) findViewById(R.id.val_packets);
        valRate          = (TextView) findViewById(R.id.val_rate);
        barSteering      = (ProgressBar) findViewById(R.id.bar_steering);
        barThrottle      = (ProgressBar) findViewById(R.id.bar_throttle);
        barBrake         = (ProgressBar) findViewById(R.id.bar_brake);
        editIp           = (EditText) findViewById(R.id.edit_ip);
        seekVibMaster    = (SeekBar) findViewById(R.id.seek_vib_master);
        lblVibMaster     = (TextView) findViewById(R.id.lbl_vib_master_val);
        grpGyro          = (RadioGroup) findViewById(R.id.grp_gyro_source);
        rbGyroPhone      = (RadioButton) findViewById(R.id.rb_gyro_phone);
        rbGyroController = (RadioButton) findViewById(R.id.rb_gyro_controller);
        rbGyroTouch      = (RadioButton) findViewById(R.id.rb_gyro_touch);
        gyroHint         = (TextView) findViewById(R.id.gyro_source_hint);
        touchWheel       = (TouchWheel) findViewById(R.id.touch_wheel);
        clutchRow        = (LinearLayout) findViewById(R.id.clutch_row);
        seekClutch       = (SeekBar) findViewById(R.id.seek_clutch);
        valClutch        = (TextView) findViewById(R.id.val_clutch);
        chkHapticBtn     = (CheckBox) findViewById(R.id.chk_haptic_btn);
        chkMouseMode     = (CheckBox) findViewById(R.id.chk_mouse_mode);
        seekMouseSens    = (SeekBar) findViewById(R.id.seek_mouse_sens);
        lblMouseSens     = (TextView) findViewById(R.id.lbl_mouse_sens);

        // Live input visualization
        stickL           = (StickView) findViewById(R.id.stick_l);
        stickR           = (StickView) findViewById(R.id.stick_r);
        valStickL        = (TextView)  findViewById(R.id.val_stick_l);
        valStickR        = (TextView)  findViewById(R.id.val_stick_r);
        clutchLiveRow    = (LinearLayout) findViewById(R.id.clutch_live_row);
        barClutchLive    = (ProgressBar)  findViewById(R.id.bar_clutch_live);
        valClutchLive    = (TextView)     findViewById(R.id.val_clutch_live);
        buttonsGrid      = (LinearLayout) findViewById(R.id.buttons_grid);
        colCellOff       = 0xFF1F2733;
        colCellOn        = 0xFF21D07A;
        colCellText      = 0xFFE6EDF3;
        colCellTextOn    = 0xFF0E1116;
        buildButtonGrid();

        editIp.setText(settings.pcIp);

        String phoneName = (Build.MODEL != null) ? Build.MODEL : "Phone";
        udp = new UdpSender(phoneName, this);

        gyro = new GyroSource(this, this);
        gyro.setRangeDeg(settings.steeringRangeDeg);
        gyro.setSensitivity(settings.gyroSensitivity);
        gyro.setAntiShake(settings.gyroAntiShake);
        gyro.setCenterDurationSec(settings.centerDurationSec);
        gyro.setInvert(settings.invertSteering);
        gyro.setSource(settings.gyroSource);
        gyro.setAvailabilityListener(this);

        touchWheel.setListener(this);
        touchWheel.setRangeDeg(settings.steeringRangeDeg);
        touchWheel.setCenterDurationSec(settings.centerDurationSec);
        touchWheel.setInverted(settings.invertSteering);

        ctlIn = new ControllerInput(this, map, settings);

        wireButtons();
        wireGyroSource();
        wireToggles();

        seekVibMaster.setProgress(Math.round(settings.vibMaster * 100f));
        updateVibMasterLabel();

        // Init clutch slider
        seekClutch.setProgress(Math.round(settings.clutchValue * 1000f));
        refreshClutchVisibility();
        seekClutch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                settings.clutchValue = p / 1000f;
                valClutch.setText(Math.round(settings.clutchValue * 100f) + "%");
                udp.updateClutch(settings.clutchEnabled, settings.clutchValue);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Mouse sensitivity
        seekMouseSens.setProgress(Math.round(settings.mouseSensitivity * 10f));
        updateMouseSensLabel();
        seekMouseSens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                settings.mouseSensitivity = Math.max(0.1f, p / 10f);
                updateMouseSensLabel();
                settings.save();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        chkHapticBtn.setChecked(settings.hapticOnButton);
        chkMouseMode.setChecked(settings.mouseMode);

        refreshControllerStatus();
    }

    private void wireButtons() {
        findViewById(R.id.btn_connect).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startStream(false); }
        });
        findViewById(R.id.btn_discover).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startStream(true); }
        });
        findViewById(R.id.btn_disconnect).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                udp.stopStreaming();
                statusLine.setText(R.string.status_idle);
            }
        });
        findViewById(R.id.btn_open_bt).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "Cannot open Bluetooth settings", Toast.LENGTH_SHORT).show(); }
            }
        });
        findViewById(R.id.btn_calibrate).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, CalibrationActivity.class)); }
        });
        findViewById(R.id.btn_mapping).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, MappingActivity.class)); }
        });
        findViewById(R.id.btn_test_vibrate).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { vib.vibrateTest(); }
        });

        seekVibMaster.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                settings.vibMaster = p / 100f;
                updateVibMasterLabel();
                settings.save();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void wireGyroSource() {
        if (GyroSource.SRC_CONTROLLER.equals(settings.gyroSource))      rbGyroController.setChecked(true);
        else if ("touch".equals(settings.gyroSource))                   rbGyroTouch.setChecked(true);
        else                                                            rbGyroPhone.setChecked(true);
        applySourceVisibility();

        grpGyro.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int checkedId) {
                String src;
                if      (checkedId == R.id.rb_gyro_controller) src = GyroSource.SRC_CONTROLLER;
                else if (checkedId == R.id.rb_gyro_touch)      src = "touch";
                else                                            src = GyroSource.SRC_PHONE;
                settings.gyroSource = src;
                settings.save();
                if ("touch".equals(src)) {
                    gyro.stop();
                    touchWheel.recenter();
                } else {
                    gyro.setSource(src);
                    gyro.start();
                    if (GyroSource.SRC_CONTROLLER.equals(src) && !gyro.isControllerGyroAvailable()) {
                        Toast.makeText(MainActivity.this,
                            "No controller gyro visible to Android — falling back to phone gyro. Make sure the PS4 controller is paired and active.",
                            Toast.LENGTH_LONG).show();
                    }
                }
                applySourceVisibility();
            }
        });
    }

    private void wireToggles() {
        CompoundButton.OnCheckedChangeListener cb = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                int id = b.getId();
                if      (id == R.id.chk_haptic_btn) settings.hapticOnButton = v;
                else if (id == R.id.chk_mouse_mode) settings.mouseMode      = v;
                settings.save();
                if (id == R.id.chk_mouse_mode) {
                    udp.updateMouse(v, 0f, 0f);
                }
            }
        };
        chkHapticBtn.setOnCheckedChangeListener(cb);
        chkMouseMode.setOnCheckedChangeListener(cb);
    }

    private void applySourceVisibility() {
        boolean isTouch = "touch".equals(settings.gyroSource);
        touchWheel.setVisibility(isTouch ? View.VISIBLE : View.GONE);
    }

    private void refreshClutchVisibility() {
        clutchRow.setVisibility(settings.clutchEnabled ? View.VISIBLE : View.GONE);
        clutchLiveRow.setVisibility(settings.clutchEnabled ? View.VISIBLE : View.GONE);
        barClutchLive.setVisibility(settings.clutchEnabled ? View.VISIBLE : View.GONE);
        udp.updateClutch(settings.clutchEnabled, settings.clutchValue);
    }

    /** One TextView per PS4 button, 4 columns × N rows. Lights up green when pressed. */
    private void buildButtonGrid() {
        btnCells.clear();
        buttonsGrid.removeAllViews();
        ButtonMap m = this.map; // already loaded
        LinearLayout row = null;
        int col = 0;
        int idx = 0;
        for (ButtonMap.Entry e : m.all()) {
            if (col == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(4);
                row.setLayoutParams(rp);
                buttonsGrid.addView(row);
            }
            TextView cell = new TextView(this);
            cell.setText(e.name);
            cell.setTextColor(colCellText);
            cell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            cell.setBackgroundColor(colCellOff);
            cell.setGravity(android.view.Gravity.CENTER);
            cell.setPadding(dp(6), dp(8), dp(6), dp(8));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (col > 0) cp.leftMargin = dp(4);
            cell.setLayoutParams(cp);
            row.addView(cell);
            btnCells.put(e.androidKeyCode, cell);
            col++;
            if (col >= 4) col = 0;
            idx++;
        }
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void updateVibMasterLabel() {
        lblVibMaster.setText("Master vibration intensity: " + Math.round(settings.vibMaster * 100f) + "%");
    }

    private void updateMouseSensLabel() {
        lblMouseSens.setText(String.format("Mouse Sensitivity: %.1f", settings.mouseSensitivity));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload settings in case Calibration or Mapping screen changed them.
        settings.load();
        if (!TextUtils.isEmpty(settings.mappingProfileJson)) map.fromJson(settings.mappingProfileJson);
        gyro.setRangeDeg(settings.steeringRangeDeg);
        gyro.setSensitivity(settings.gyroSensitivity);
        gyro.setAntiShake(settings.gyroAntiShake);
        gyro.setCenterDurationSec(settings.centerDurationSec);
        gyro.setInvert(settings.invertSteering);
        touchWheel.setRangeDeg(settings.steeringRangeDeg);
        touchWheel.setCenterDurationSec(settings.centerDurationSec);
        touchWheel.setInverted(settings.invertSteering);
        refreshClutchVisibility();
        applySourceVisibility();
        if (!"touch".equals(settings.gyroSource)) gyro.start();
        refreshControllerStatus();
    }

    @Override protected void onPause() { super.onPause(); gyro.stop(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        udp.shutdown();
        vib.stop();
    }

    private void refreshControllerStatus() {
        int[] ids = InputDevice.getDeviceIds();
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            if (ControllerInput.isFromGamepad(d)) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("Connected: ").append(d.getName());
            }
        }
        controllerStatus.setText(sb.length() == 0 ? getString(R.string.hint_no_controller) : sb.toString());
    }

    private void startStream(boolean discover) {
        String ip = editIp.getText().toString().trim();
        if (discover) {
            statusLine.setText(R.string.status_searching);
            udp.discover(computeBroadcast());
            return;
        }
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "Enter PC IP first", Toast.LENGTH_SHORT).show();
            return;
        }
        settings.pcIp = ip;
        settings.save();
        udp.setTarget(ip, settings.pcPort);
        if (!"touch".equals(settings.gyroSource)) gyro.recenter();
        else                                       touchWheel.recenter();
        udp.startStreaming();
        statusLine.setText("Streaming → " + ip + ":" + settings.pcPort
                + "  (first connect: type 'y' in the PC console to allow this device)");
    }

    private String computeBroadcast() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b instanceof java.net.Inet4Address) return b.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "255.255.255.255";
    }

    /* ---------- UdpSender.Listener ---------- */

    @Override
    public void onDiscovered(final String pcIp, final String pcName, final String connection) {
        ui.post(new Runnable() {
            @Override public void run() {
                editIp.setText(pcIp);
                settings.pcIp = pcIp;
                settings.connectionMode = connection;
                settings.save();
                udp.setTarget(pcIp, settings.pcPort);
                if (!"touch".equals(settings.gyroSource)) gyro.recenter();
                else                                       touchWheel.recenter();
                udp.startStreaming();
                statusLine.setText("Streaming → " + pcName + " (" + pcIp + ", " + connection + ")");
            }
        });
    }

    @Override
    public void onError(final String message) {
        ui.post(new Runnable() {
            @Override public void run() { statusLine.setText("Error: " + message); }
        });
    }

    @Override
    public void onStats(final int packetsSent, final int packetsPerSec) {
        ui.post(new Runnable() {
            @Override public void run() {
                valPackets.setText(String.valueOf(packetsSent));
                valRate.setText(packetsPerSec + " Hz");
            }
        });
    }

    /* ---------- GyroSource.Listener ---------- */

    @Override
    public void onSteeringChanged(float angleDeg) {
        liveSteer = angleDeg;
        // Mouse mode: gyro drives mouse cursor instead of steering.
        if (settings.mouseMode) {
            // Convert change-in-angle to a small delta. Re-zero the steering value sent.
            float dx = angleDeg * settings.mouseSensitivity * 0.05f;
            udp.updateAxes(0f, liveThrottle, liveBrake);
            udp.updateMouse(true, dx, 0f);
        } else {
            udp.updateAxes(liveSteer, liveThrottle, liveBrake);
            udp.updateMouse(false, 0f, 0f);
        }
        ui.post(new Runnable() {
            @Override public void run() {
                valSteering.setText(settings.showSteeringAngle ? String.format("%+.1f°", liveSteer) : "");
                int mid = barSteering.getMax() / 2;
                int delta = Math.round((liveSteer / settings.steeringRangeDeg) * mid);
                barSteering.setProgress(mid + delta);
            }
        });
        vib.onDrivingState(liveSteer, liveThrottle, liveBrake);
    }

    /* ---------- ControllerInput.Listener ---------- */

    @Override
    public void onTriggers(final float throttle, final float brake) {
        liveThrottle = throttle;
        liveBrake    = brake;
        udp.updateAxes(liveSteer, liveThrottle, liveBrake);
        ui.post(new Runnable() {
            @Override public void run() {
                valThrottle.setText(Math.round(throttle * 100f) + "%");
                valBrake.setText(Math.round(brake * 100f) + "%");
                barThrottle.setProgress(Math.round(throttle * 1000f));
                barBrake.setProgress(Math.round(brake * 1000f));
            }
        });
        vib.onDrivingState(liveSteer, liveThrottle, liveBrake);
    }

    @Override
    public void onButton(int androidKeyCode, boolean pressed, String deviceName) {
        ButtonMap.Entry e = map.forKey(androidKeyCode);
        if (e != null && e.receiverCode > 0) udp.setButton(e.receiverCode, pressed);
        if (pressed && settings.hapticOnButton) vib.vibrateButton();
    }

    @Override
    public void onLeftStick(final float x, final float y) {
        ui.post(new Runnable() {
            @Override public void run() {
                stickL.set(x, y);
                valStickL.setText(String.format("%+.2f / %+.2f", x, y));
            }
        });
    }

    @Override
    public void onRightStick(final float x, final float y) {
        ui.post(new Runnable() {
            @Override public void run() {
                stickR.set(x, y);
                valStickR.setText(String.format("%+.2f / %+.2f", x, y));
            }
        });
    }

    /* ---------- GyroSource.AvailabilityListener ---------- */

    @Override
    public void onControllerGyroAvailable(final boolean available, final String name) {
        ui.post(new Runnable() {
            @Override public void run() {
                rbGyroController.setEnabled(available);
                if (available) gyroHint.setText("Controller IMU detected: " + name + ". Pick \"Controller gyro\" to use the DualShock IMU.");
                else if (rbGyroController.isChecked()) rbGyroPhone.setChecked(true);
            }
        });
    }

    // TouchWheel.Listener.onSteeringChanged shares signature with GyroSource.Listener.onSteeringChanged
    // above — same implementation services both interfaces.

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (ctlIn != null && ctlIn.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ctlIn != null && ctlIn.handleMotion(ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }
}
