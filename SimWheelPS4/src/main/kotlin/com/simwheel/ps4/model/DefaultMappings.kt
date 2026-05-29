package com.simwheel.ps4.model

import android.view.KeyEvent

object DefaultMappings {

    val ALL: List<ButtonMapping> = listOf(
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_A,      "Cross (X)",    1,  "vJoy Button 1"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_B,      "Circle (O)",   2,  "vJoy Button 2"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_X,      "Square",       3,  "vJoy Button 3"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_Y,      "Triangle",     4,  "vJoy Button 4"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_L1,     "L1",           5,  "vJoy Button 5"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_R1,     "R1",           6,  "vJoy Button 6"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_SELECT, "Share",        7,  "vJoy Button 7"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_START,  "Options",      8,  "vJoy Button 8"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3",           9,  "vJoy Button 9"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3",          10,  "vJoy Button 10"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_MODE,   "PS Button",   11,  "vJoy Button 11"),
        ButtonMapping(KeyEvent.KEYCODE_DPAD_UP,       "D-Pad Up",   352,  "Key ↑"),
        ButtonMapping(KeyEvent.KEYCODE_DPAD_DOWN,     "D-Pad Down", 353,  "Key ↓"),
        ButtonMapping(KeyEvent.KEYCODE_DPAD_LEFT,     "D-Pad Left", 350,  "Key ←"),
        ButtonMapping(KeyEvent.KEYCODE_DPAD_RIGHT,    "D-Pad Right",351,  "Key →"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_L2,     "L2 (digital)",12,  "vJoy Button 12"),
        ButtonMapping(KeyEvent.KEYCODE_BUTTON_R2,     "R2 (digital)",13,  "vJoy Button 13")
    )

    val PC_CODE_NAMES: Map<Int, String> = run {
        val m = mutableMapOf<Int, String>()
        for (i in 1..32) m[i] = "vJoy Button $i"
        for (i in 200..225) m[i] = "Key ${('A' + (i - 200))}"
        m[230] = "Key Space"; m[231] = "Key Enter"; m[232] = "Key Backspace"
        m[233] = "Key Tab";   m[234] = "Key Shift"; m[235] = "Key Ctrl"
        m[236] = "Key Alt";   m[238] = "Key ESC";   m[239] = "Key CapsLock"
        for (i in 300..309) m[i] = "Key ${i - 300}"
        m[350] = "Key Left"; m[351] = "Key Right"; m[352] = "Key Up"; m[353] = "Key Down"
        for (i in 400..411) m[i] = "F${i - 399}"
        m[500] = "Mouse Left"; m[501] = "Mouse Right"; m[503] = "Mouse Middle"
        m
    }

    fun pcCodeName(code: Int): String = PC_CODE_NAMES[code] ?: "Code $code"
}
