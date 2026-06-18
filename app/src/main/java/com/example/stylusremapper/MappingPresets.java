package com.example.stylusremapper;

import android.view.KeyEvent;

public class MappingPresets {

    private static final int CTRL  = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
    private static final int ALT   = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
    private static final int SHIFT = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
    private static final int NONE_KEY = KeyEvent.KEYCODE_UNKNOWN;

    // Each preset is a full ButtonAction. New presets MUST be appended after CUSTOM_INDEX
    // so existing profiles (which persist preset *index*) keep pointing at the same entry.
    public static final ButtonAction[] PRESETS = {
        /* 0 */ new ButtonAction(CTRL | ALT, NONE_KEY, 0),                       // Ctrl+Alt
        /* 1 */ new ButtonAction(0, KeyEvent.KEYCODE_SPACE, 0),                  // Space
        /* 2 */ new ButtonAction(CTRL, KeyEvent.KEYCODE_Z, 0),                   // Ctrl+Z
        /* 3 */ new ButtonAction(CTRL | SHIFT, KeyEvent.KEYCODE_Z, 0),           // Ctrl+Shift+Z
        /* 4 */ new ButtonAction(0, KeyEvent.KEYCODE_E, 0),                      // E
        /* 5 */ new ButtonAction(CTRL, KeyEvent.KEYCODE_S, 0),                   // Ctrl+S
        /* 6 */ new ButtonAction(0, NONE_KEY, 0),                               // Custom (placeholder)
        /* 7 */ new ButtonAction(0, NONE_KEY, ButtonAction.MOUSE_LEFT),         // Left click
        /* 8 */ new ButtonAction(0, NONE_KEY, ButtonAction.MOUSE_MIDDLE),       // Middle click
        /* 9 */ new ButtonAction(0, NONE_KEY, ButtonAction.MOUSE_RIGHT),        // Right click
        /*10 */ new ButtonAction(0, KeyEvent.KEYCODE_SPACE, ButtonAction.MOUSE_LEFT), // Left click + Space
    };

    public static final int[] DESCRIPTION_IDS = {
        /* 0  Ctrl+Alt         */ R.string.preset_desc_brush_size,
        /* 1  Space            */ R.string.preset_desc_canvas_drag,
        /* 2  Ctrl+Z           */ R.string.preset_desc_undo,
        /* 3  Ctrl+Shift+Z     */ R.string.preset_desc_redo,
        /* 4  E                */ R.string.preset_desc_eraser_toggle,
        /* 5  Ctrl+S           */ R.string.preset_desc_save,
        /* 6  Custom           */ R.string.preset_desc_custom,
        /* 7  Left Click       */ 0,
        /* 8  Middle Click     */ 0,
        /* 9  Right Click      */ 0,
        /* 10 LClick+Space     */ R.string.preset_desc_canvas_pan,
    };

    // Custom remains at its historical index (6). It is no longer the last entry, so this
    // is a fixed constant rather than LABELS.length - 1.
    public static final int CUSTOM_INDEX = 6;

    // Default preset IDs for each switch
    public static final int DEFAULT_SWITCH1 = 0; // Ctrl+Alt
    public static final int DEFAULT_SWITCH2 = 1; // Space
    public static final int DEFAULT_SWITCH3 = 2; // Ctrl+Z
}
