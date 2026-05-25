package com.sdf.simwheelps4;

import android.view.KeyEvent;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Android KeyEvent codes for a paired DualShock 4 to "receiver codes" — the
 * integers SimWheel PC Receiver v3.0 expects on the wire:
 *   1..32        → vJoy buttons
 *   200..225     → A..Z keyboard
 *   230..239     → Space/Enter/Backspace/Tab/Shift/Ctrl/Alt/Win/Esc/CapsLock
 *   250..259     → symbol keys
 *   300..309     → 0..9
 *   350..353     → Left/Right/Up/Down
 *   400..411     → F1..F12
 *   500/501/503  → Mouse L/R/Middle
 */
public class ButtonMap {

    /** Human label for a logical PS4 button. */
    public static class Entry {
        public final String name;
        public final int    androidKeyCode;
        public int          receiverCode; // mutable: edited in UI
        public Entry(String name, int androidKeyCode, int receiverCode) {
            this.name = name;
            this.androidKeyCode = androidKeyCode;
            this.receiverCode = receiverCode;
        }
    }

    /** Ordered map keyed by the Android KeyEvent code. */
    private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<Integer, Entry>();

    public ButtonMap() {
        // Default mapping: PS4 face buttons → vJoy 1..4, bumpers → 5/6, etc.
        add("Cross (X)",      KeyEvent.KEYCODE_BUTTON_A, 1);
        add("Circle (O)",     KeyEvent.KEYCODE_BUTTON_B, 2);
        add("Square",         KeyEvent.KEYCODE_BUTTON_X, 3);
        add("Triangle",       KeyEvent.KEYCODE_BUTTON_Y, 4);
        add("L1",             KeyEvent.KEYCODE_BUTTON_L1, 5);
        add("R1",             KeyEvent.KEYCODE_BUTTON_R1, 6);
        add("L3 (stick click)", KeyEvent.KEYCODE_BUTTON_THUMBL, 7);
        add("R3 (stick click)", KeyEvent.KEYCODE_BUTTON_THUMBR, 8);
        add("Share",          KeyEvent.KEYCODE_BUTTON_SELECT, 9);
        add("Options",        KeyEvent.KEYCODE_BUTTON_START, 10);
        add("PS",              KeyEvent.KEYCODE_BUTTON_MODE, 11);
        add("Touchpad click", KeyEvent.KEYCODE_BUTTON_C, 12);
        add("D-Pad Up",       KeyEvent.KEYCODE_DPAD_UP, 13);
        add("D-Pad Down",     KeyEvent.KEYCODE_DPAD_DOWN, 14);
        add("D-Pad Left",     KeyEvent.KEYCODE_DPAD_LEFT, 15);
        add("D-Pad Right",    KeyEvent.KEYCODE_DPAD_RIGHT, 16);
    }

    private void add(String name, int keyCode, int defaultReceiver) {
        entries.put(keyCode, new Entry(name, keyCode, defaultReceiver));
    }

    public java.util.Collection<Entry> all() { return entries.values(); }

    public Entry forKey(int androidKeyCode) { return entries.get(androidKeyCode); }

    public void setReceiverCode(int androidKeyCode, int receiverCode) {
        Entry e = entries.get(androidKeyCode);
        if (e != null) e.receiverCode = receiverCode;
    }

    /** Choices the user can pick for each PS4 button. */
    public static class Choice {
        public final String label;
        public final int    code;
        public Choice(String label, int code) { this.label = label; this.code = code; }
        @Override public String toString() { return label; }
    }

    public static Choice[] receiverChoices() {
        java.util.ArrayList<Choice> a = new java.util.ArrayList<Choice>();
        a.add(new Choice("— none —", 0));
        for (int i = 1; i <= 32; i++) a.add(new Choice("vJoy Btn " + i, i));
        for (int i = 0; i < 26; i++)   a.add(new Choice("Key " + (char)('A' + i), 200 + i));
        for (int i = 0; i < 10; i++)   a.add(new Choice("Key " + i, 300 + i));
        a.add(new Choice("Space",  230));
        a.add(new Choice("Enter",  231));
        a.add(new Choice("Backspace", 232));
        a.add(new Choice("Tab",    233));
        a.add(new Choice("Shift",  234));
        a.add(new Choice("Ctrl",   235));
        a.add(new Choice("Alt",    236));
        a.add(new Choice("Win",    237));
        a.add(new Choice("Esc",    238));
        a.add(new Choice("CapsLock", 239));
        a.add(new Choice("Arrow ←", 350));
        a.add(new Choice("Arrow →", 351));
        a.add(new Choice("Arrow ↑", 352));
        a.add(new Choice("Arrow ↓", 353));
        for (int i = 1; i <= 12; i++) a.add(new Choice("F" + i, 400 + (i - 1)));
        a.add(new Choice("Mouse Left", 500));
        a.add(new Choice("Mouse Right", 501));
        a.add(new Choice("Mouse Middle", 503));
        Choice[] arr = new Choice[a.size()];
        return a.toArray(arr);
    }

    /* ---------- profile persistence ---------- */

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            for (Entry e : entries.values()) o.put(Integer.toString(e.androidKeyCode), e.receiverCode);
        } catch (JSONException ignored) {}
        return o.toString();
    }

    public void fromJson(String s) {
        if (s == null || s.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(s);
            for (Map.Entry<Integer, Entry> me : entries.entrySet()) {
                String key = Integer.toString(me.getKey());
                if (o.has(key)) me.getValue().receiverCode = o.optInt(key, me.getValue().receiverCode);
            }
        } catch (JSONException ignored) {}
    }
}
