package com.example.stylusremapper;

import android.view.KeyEvent;

public class MappingPresets {

    // Each preset: {keycode, metaState}
    public static final int[][] PRESETS = {
        {KeyEvent.KEYCODE_UNKNOWN,
         KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON
         | KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON},   // Ctrl+Alt
        {KeyEvent.KEYCODE_SPACE, 0},                             // Space
        {KeyEvent.KEYCODE_Z,
         KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON},   // Ctrl+Z
        {KeyEvent.KEYCODE_Z,
         KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON
         | KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON}, // Ctrl+Shift+Z
        {KeyEvent.KEYCODE_E, 0},                                 // E
        {KeyEvent.KEYCODE_S,
         KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON},   // Ctrl+S
    };

    public static final String[] LABELS = {
        "Ctrl+Alt (ブラシサイズ)",
        "Space (キャンバスドラッグ)",
        "Ctrl+Z (元に戻す)",
        "Ctrl+Shift+Z (やり直し)",
        "E (消しゴム切替)",
        "Ctrl+S (保存)",
    };

    // Default preset IDs for each switch
    public static final int DEFAULT_SWITCH1 = 0; // Ctrl+Alt
    public static final int DEFAULT_SWITCH2 = 1; // Space
    public static final int DEFAULT_SWITCH3 = 2; // Ctrl+Z
}
