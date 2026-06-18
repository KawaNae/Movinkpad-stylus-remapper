package com.example.stylusremapper;

import android.content.Context;
import android.view.KeyEvent;

public class KeyDefinitions {

    // Each entry: {keycode, displayName}
    public static final int[] KEY_CODES = {
        KeyEvent.KEYCODE_UNKNOWN,  // None (modifier-only)
        // Letters
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C,
        KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_F,
        KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_I,
        KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
        KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_O,
        KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_U,
        KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_X,
        KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_Z,
        // Digits
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
        KeyEvent.KEYCODE_9,
        // Common
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
        // Function keys
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
        KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
        KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9,
        KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
        // Navigation
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
    };

    private static final String[] KEY_NAMES = {
        "",
        // Letters
        "A", "B", "C", "D", "E", "F", "G", "H", "I",
        "J", "K", "L", "M", "N", "O", "P", "Q", "R",
        "S", "T", "U", "V", "W", "X", "Y", "Z",
        // Digits
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        // Common
        "Space", "Tab", "Enter", "Escape", "Backspace", "Delete",
        // Function keys
        "F1", "F2", "F3", "F4", "F5", "F6",
        "F7", "F8", "F9", "F10", "F11", "F12",
        // Navigation
        "Up", "Down", "Left", "Right", "Home", "End", "PageUp", "PageDown",
    };

    public static String[] getKeyNames(Context context) {
        String[] names = KEY_NAMES.clone();
        names[0] = context.getString(R.string.key_none_modifier_only);
        return names;
    }

    /** Get display name for a keycode. */
    public static String getKeyName(int keycode) {
        for (int i = 0; i < KEY_CODES.length; i++) {
            if (KEY_CODES[i] == keycode) {
                // Use short name for known keys (skip the parenthetical for UNKNOWN)
                return i == 0 ? "" : KEY_NAMES[i];
            }
        }
        return "Key(" + keycode + ")";
    }

    /** Find the index of a keycode in KEY_CODES, or 0 if not found. */
    public static int indexOfKeycode(int keycode) {
        for (int i = 0; i < KEY_CODES.length; i++) {
            if (KEY_CODES[i] == keycode) return i;
        }
        return 0;
    }

    public static String mouseButtonsName(Context context, int mouseButtons) {
        StringBuilder sb = new StringBuilder();
        if ((mouseButtons & ButtonAction.MOUSE_LEFT) != 0)
            sb.append(context.getString(R.string.mouse_left_click));
        if ((mouseButtons & ButtonAction.MOUSE_MIDDLE) != 0) {
            if (sb.length() > 0) sb.append("+");
            sb.append(context.getString(R.string.mouse_middle_click));
        }
        if ((mouseButtons & ButtonAction.MOUSE_RIGHT) != 0) {
            if (sb.length() > 0) sb.append("+");
            sb.append(context.getString(R.string.mouse_right_click));
        }
        return sb.toString();
    }

    public static String describe(Context context, ButtonAction action) {
        StringBuilder sb = new StringBuilder();
        if ((action.meta & KeyEvent.META_CTRL_ON) != 0) sb.append("Ctrl+");
        if ((action.meta & KeyEvent.META_ALT_ON) != 0) sb.append("Alt+");
        if ((action.meta & KeyEvent.META_SHIFT_ON) != 0) sb.append("Shift+");

        StringBuilder primary = new StringBuilder();
        if (action.hasKey()) primary.append(getKeyName(action.keycode));
        if (action.hasMouse()) {
            if (primary.length() > 0) primary.append("+");
            primary.append(mouseButtonsName(context, action.mouseButtons));
        }

        if (primary.length() == 0) {
            if (sb.length() > 0) {
                sb.setLength(sb.length() - 1);
            } else {
                sb.append(context.getString(R.string.describe_none));
            }
        } else {
            sb.append(primary);
        }
        return sb.toString();
    }
}
