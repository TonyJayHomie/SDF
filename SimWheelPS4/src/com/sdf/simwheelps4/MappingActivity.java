package com.sdf.simwheelps4;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lets the user remap every PS4 button to one of the receiver codes. Profiles
 * are kept in a single SharedPreferences slot (a JSON dictionary of profile-name
 * → mapping-json). The "live" profile is mirrored into Settings.mappingProfileJson
 * which is what MainActivity loads at startup.
 */
public class MappingActivity extends Activity {

    private static final String PROFILES_PREF = "simwheel_ps4_profiles";

    private Settings settings;
    private ButtonMap map;
    private ButtonMap.Choice[] choices;
    private TextView lastPress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mapping);

        settings = new Settings(this);
        map = new ButtonMap();
        map.fromJson(settings.mappingProfileJson);
        choices = ButtonMap.receiverChoices();

        lastPress = (TextView) findViewById(R.id.last_press);
        LinearLayout container = (LinearLayout) findViewById(R.id.mapping_container);
        LayoutInflater inf = LayoutInflater.from(this);

        for (final ButtonMap.Entry e : map.all()) {
            View row = inf.inflate(R.layout.row_mapping, container, false);
            ((TextView) row.findViewById(R.id.row_btn_name)).setText(e.name);
            Spinner sp = (Spinner) row.findViewById(R.id.row_spinner);
            ArrayAdapter<ButtonMap.Choice> ad = new ArrayAdapter<ButtonMap.Choice>(this,
                    android.R.layout.simple_spinner_item, choices);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(ad);
            // preselect current
            int sel = 0;
            for (int i = 0; i < choices.length; i++) if (choices[i].code == e.receiverCode) { sel = i; break; }
            sp.setSelection(sel);
            sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    e.receiverCode = choices[pos].code;
                    settings.mappingProfileJson = map.toJson();
                    settings.save();
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
            container.addView(row);
        }

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptSaveProfile(); }
        });
        findViewById(R.id.btn_load).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptLoadProfile(); }
        });
        findViewById(R.id.btn_reset_map).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                map = new ButtonMap();
                settings.mappingProfileJson = map.toJson();
                settings.save();
                recreate();
            }
        });
    }

    private SharedPreferences profiles() {
        return getApplicationContext().getSharedPreferences(PROFILES_PREF, Context.MODE_PRIVATE);
    }

    private void promptSaveProfile() {
        final EditText input = new EditText(this);
        input.setHint("Profile name (e.g. ETS2, F1)");
        new AlertDialog.Builder(this)
                .setTitle("Save profile")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) return;
                        profiles().edit().putString(name, map.toJson()).apply();
                        Toast.makeText(MappingActivity.this, "Saved: " + name, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptLoadProfile() {
        Set<String> keys = new LinkedHashSet<String>(profiles().getAll().keySet());
        if (keys.isEmpty()) {
            Toast.makeText(this, "No saved profiles yet", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] arr = keys.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Load profile")
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String s = profiles().getString(arr[which], "");
                        map.fromJson(s);
                        settings.mappingProfileJson = map.toJson();
                        settings.save();
                        recreate();
                    }
                })
                .show();
    }

    /* Show button presses live so the user can identify which physical button maps where. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && ControllerInput.isFromGamepad(event.getDevice())) {
            ButtonMap.Entry e = map.forKey(event.getKeyCode());
            String name = (e != null) ? e.name : ("KeyCode " + event.getKeyCode());
            lastPress.setText("Pressed: " + name);
        }
        return super.dispatchKeyEvent(event);
    }
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        return super.dispatchGenericMotionEvent(ev);
    }
}
