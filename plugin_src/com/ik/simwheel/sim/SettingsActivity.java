package com.ik.simwheel.sim;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private Handler handler;
    private Runnable refreshRunnable;

    private ProgressBar barL2;
    private ProgressBar barR2;
    private ProgressBar barGyro;

    private boolean refreshing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Title
        TextView title = new TextView(this);
        title.setText("SimWheel PS4 Settings");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        // --- Live Input Bars ---
        root.addView(makeSectionHeader("Live Input Monitor"));

        root.addView(makeLabel("L2 (Brake):"));
        barL2 = makeBar();
        root.addView(barL2);

        root.addView(makeLabel("R2 (Throttle):"));
        barR2 = makeBar();
        root.addView(barR2);

        root.addView(makeLabel("Gyro Steering:"));
        barGyro = makeBar();
        root.addView(barGyro);

        // --- Calibration Section ---
        root.addView(makeSectionHeader("Calibration"));

        root.addView(makeLabel("Trigger Deadzone: " + (int)(SimBridge.triggerDeadzone * 100) + "%"));
        final TextView deadzoneLabel = makeLabel("Trigger Deadzone: " + (int)(SimBridge.triggerDeadzone * 100) + "%");
        root.addView(deadzoneLabel);
        SeekBar deadzoneBar = makeSeekBar(100, (int)(SimBridge.triggerDeadzone * 100));
        deadzoneBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                SimBridge.triggerDeadzone = progress / 100f;
                deadzoneLabel.setText("Trigger Deadzone: " + progress + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) { SimBridge.saveSettings(); }
        });
        root.addView(deadzoneBar);

        final TextView curveLabel = makeLabel("Trigger Curve (Gamma): " + SimBridge.triggerCurve);
        root.addView(curveLabel);
        SeekBar curveBar = makeSeekBar(30, (int)(SimBridge.triggerCurve * 10));
        curveBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float val = progress / 10f;
                SimBridge.triggerCurve = val;
                curveLabel.setText("Trigger Curve (Gamma): " + val);
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) { SimBridge.saveSettings(); }
        });
        root.addView(curveBar);

        final TextView vibLabel = makeLabel("Vibration Intensity: " + SimBridge.vibrationIntensity + "%");
        root.addView(vibLabel);
        SeekBar vibBar = makeSeekBar(100, SimBridge.vibrationIntensity);
        vibBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                SimBridge.vibrationIntensity = progress;
                vibLabel.setText("Vibration Intensity: " + progress + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) { SimBridge.saveSettings(); }
        });
        root.addView(vibBar);

        // Vibration test buttons
        LinearLayout vibRow = new LinearLayout(this);
        vibRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnVib1 = new Button(this);
        btnVib1.setText("Test Collision");
        btnVib1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SimBridge.vibratePattern(1, SimBridge.vibrationIntensity / 100f, 200);
            }
        });
        vibRow.addView(btnVib1);

        Button btnVib2 = new Button(this);
        btnVib2.setText("Test Rough");
        btnVib2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SimBridge.vibratePattern(2, SimBridge.vibrationIntensity / 100f, 300);
            }
        });
        vibRow.addView(btnVib2);

        Button btnVib3 = new Button(this);
        btnVib3.setText("Test Rev");
        btnVib3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SimBridge.vibratePattern(3, SimBridge.vibrationIntensity / 100f, 150);
            }
        });
        vibRow.addView(btnVib3);

        root.addView(vibRow);

        // --- Button Remap Section ---
        root.addView(makeSectionHeader("Button Remap"));

        final String[] btnNames = {
            "X (Cross)", "O (Circle)", "Square", "Triangle",
            "L1", "R1", "L2 btn", "R2 btn",
            "L3 (LStick)", "R3 (RStick)", "Share", "Options",
            "DPad Up", "DPad Down", "DPad Left", "DPad Right"
        };

        final Integer[] targets = new Integer[128];
        String[] targetNames = new String[128];
        for (int i = 0; i < 128; i++) {
            targets[i] = i + 1;
            targetNames[i] = "vJoy " + (i + 1);
        }

        for (int i = 0; i < 16; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView lbl = makeLabel(btnNames[i] + " → ");
            lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(lbl);

            Spinner spin = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, targetNames);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spin.setAdapter(adapter);
            int currentVal = SimBridge.buttonMap[i] - 1;
            if (currentVal >= 0 && currentVal < 128) spin.setSelection(currentVal);

            final int spinIdx = i;
            spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    SimBridge.buttonMap[spinIdx] = position + 1;
                }
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            row.addView(spin);
            root.addView(row);
        }

        // --- Profiles Section ---
        root.addView(makeSectionHeader("Profiles"));

        final EditText profileName = new EditText(this);
        profileName.setHint("Profile name");
        profileName.setText("default");
        root.addView(profileName);

        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);

        Button saveBtn = new Button(this);
        saveBtn.setText("Save Profile");
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String name = profileName.getText().toString().trim();
                if (name.length() == 0) name = "default";
                SimBridge.saveProfile(name);
            }
        });
        profileRow.addView(saveBtn);

        Button loadBtn = new Button(this);
        loadBtn.setText("Load Profile");
        loadBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String name = profileName.getText().toString().trim();
                if (name.length() == 0) name = "default";
                SimBridge.loadProfile(name);
            }
        });
        profileRow.addView(loadBtn);

        root.addView(profileRow);

        // --- Back button ---
        Button backBtn = new Button(this);
        backBtn.setText("Back to Main App");
        backBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(backBtn);

        scroll.addView(root);
        setContentView(scroll);

        // Start refresh runnable
        refreshRunnable = new Runnable() {
            public void run() {
                if (!refreshing) return;
                // Update live bars
                float brake = SimBridge.state_brake;
                float throttle = SimBridge.state_throttle;
                float steering = SimBridge.state_steering;
                float maxDeg = SimBridge.maxDegrees;

                barL2.setProgress((int)(brake * 100));
                barR2.setProgress((int)(throttle * 100));
                // Map steering to 0-100 range
                int steeringPct = (int)((steering / maxDeg + 1f) * 50f);
                if (steeringPct < 0) steeringPct = 0;
                if (steeringPct > 100) steeringPct = 100;
                barGyro.setProgress(steeringPct);

                handler.postDelayed(this, 100);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshing = true;
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshing = false;
        handler.removeCallbacks(refreshRunnable);
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(Color.DKGRAY);
        tv.setPadding(0, dp(4), 0, 0);
        return tv;
    }

    private TextView makeSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTextColor(Color.BLACK);
        tv.setPadding(0, dp(16), 0, dp(8));
        return tv;
    }

    private SeekBar makeSeekBar(int max, int progress) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(progress);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(8));
        sb.setLayoutParams(lp);
        return sb;
    }

    private ProgressBar makeBar() {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(16));
        lp.setMargins(0, dp(2), 0, dp(8));
        pb.setLayoutParams(lp);
        return pb;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            getResources().getDisplayMetrics());
    }
}
