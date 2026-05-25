package com.sdf.simwheelps4;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Tabs are flattened into a single scrollable screen — easier on small screens
 * and keeps the gamepad input in one window so KeyEvent dispatch is simple.
 */
public class MainActivity extends Activity
        implements UdpSender.Listener, GyroSource.Listener, GyroSource.AvailabilityListener, ControllerInput.Listener {

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
    private SeekBar  seekVibMaster;
    private TextView lblVibMaster;
    private RadioGroup grpGyro;
    private RadioButton rbGyroPhone, rbGyroController;
    private TextView gyroHint;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private float liveSteer    = 0f;
    private float liveThrottle = 0f;
    private float liveBrake    = 0f;
    private boolean attached   = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new Settings(this);
        map      = new ButtonMap();
        if (!TextUtils.isEmpty(settings.mappingProfileJson)) map.fromJson(settings.mappingProfileJson);

        vib = new VibrationEngine(this, settings);
        vib.start();

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
        editIp.setText(settings.pcIp);

        String phoneName = "Phone";
        try {
            String s = Build.MODEL;
            if (!TextUtils.isEmpty(s)) phoneName = s;
        } catch (Exception ignored) {}

        udp = new UdpSender(phoneName, this);
        gyro = new GyroSource(this, this);
        gyro.setRangeDeg(settings.steeringRangeDeg);
        gyro.setDeadzoneDeg(settings.gyroDeadzoneDeg);
        gyro.setSensitivity(settings.gyroSensitivity);
        gyro.setCurveExp(settings.steeringCurve);
        gyro.setSource(settings.gyroSource);
        gyro.setAvailabilityListener(this);

        ctlIn = new ControllerInput(this, map, settings);

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
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Cannot open Bluetooth settings", Toast.LENGTH_SHORT).show();
                }
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

        grpGyro          = (RadioGroup) findViewById(R.id.grp_gyro_source);
        rbGyroPhone      = (RadioButton) findViewById(R.id.rb_gyro_phone);
        rbGyroController = (RadioButton) findViewById(R.id.rb_gyro_controller);
        gyroHint         = (TextView) findViewById(R.id.gyro_source_hint);
        if (GyroSource.SRC_CONTROLLER.equals(settings.gyroSource)) {
            rbGyroController.setChecked(true);
        } else {
            rbGyroPhone.setChecked(true);
        }
        grpGyro.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int checkedId) {
                String src = (checkedId == R.id.rb_gyro_controller)
                        ? GyroSource.SRC_CONTROLLER : GyroSource.SRC_PHONE;
                settings.gyroSource = src;
                settings.save();
                gyro.setSource(src);
                if (GyroSource.SRC_CONTROLLER.equals(src) && !gyro.isControllerGyroAvailable()) {
                    Toast.makeText(MainActivity.this,
                        "No controller gyro visible to Android yet — falling back to phone gyro. Make sure the PS4 controller is paired and active.",
                        Toast.LENGTH_LONG).show();
                }
            }
        });

        seekVibMaster.setProgress(Math.round(settings.vibMaster * 100f));
        updateVibMasterLabel();
        seekVibMaster.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                settings.vibMaster = p / 100f;
                updateVibMasterLabel();
                settings.save();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        refreshControllerStatus();
    }

    private void updateVibMasterLabel() {
        lblVibMaster.setText("Master vibration intensity: " + Math.round(settings.vibMaster * 100f) + "%");
    }

    @Override
    protected void onResume() {
        super.onResume();
        gyro.start();
        refreshControllerStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        gyro.stop();
    }

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
        if (sb.length() == 0) controllerStatus.setText(R.string.hint_no_controller);
        else                  controllerStatus.setText(sb.toString());
    }

    /** Begin streaming. If discover==true, broadcast first then stream to that PC. */
    private void startStream(boolean discover) {
        String ip = editIp.getText().toString().trim();
        if (discover) {
            String bcast = computeBroadcast();
            statusLine.setText(R.string.status_searching);
            udp.discover(bcast);
            return;
        }
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "Enter PC IP first", Toast.LENGTH_SHORT).show();
            return;
        }
        settings.pcIp = ip;
        settings.save();
        udp.setTarget(ip, settings.pcPort);
        udp.startStreaming();
        statusLine.setText("Streaming → " + ip + ":" + settings.pcPort);
    }

    /** Best-effort: derive the broadcast for the current Wi-Fi subnet. */
    private String computeBroadcast() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && b instanceof java.net.Inet4Address) return b.getHostAddress();
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
                settings.save();
                udp.setTarget(pcIp, settings.pcPort);
                udp.startStreaming();
                statusLine.setText("Streaming → " + pcName + " (" + pcIp + ", " + connection + ")");
            }
        });
    }

    @Override
    public void onError(final String message) {
        ui.post(new Runnable() {
            @Override public void run() {
                statusLine.setText("Error: " + message);
            }
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
        udp.updateAxes(liveSteer, liveThrottle, liveBrake);
        ui.post(new Runnable() {
            @Override public void run() {
                valSteering.setText(String.format("%+.1f°", liveSteer));
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
    }

    @Override public void onLeftStick(float x, float y) {}
    @Override public void onRightStick(float x, float y) {}

    /* ---------- GyroSource.AvailabilityListener ---------- */

    @Override
    public void onControllerGyroAvailable(final boolean available, final String name) {
        ui.post(new Runnable() {
            @Override public void run() {
                rbGyroController.setEnabled(available);
                if (available) {
                    gyroHint.setText("Controller IMU detected: " + name
                            + ". Tap \"Controller gyro\" to steer with the DualShock.");
                } else {
                    gyroHint.setText("Controller gyro: requires PS4/PS5 controller paired and Android 7.0+ (the system must advertise the controller IMU as a dynamic sensor).");
                    if (rbGyroController.isChecked()) {
                        rbGyroPhone.setChecked(true);
                    }
                }
            }
        });
    }

    /* ---------- gamepad input dispatch ---------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (ctlIn != null && ctlIn.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ctlIn != null && ctlIn.handleMotion(ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }
}
