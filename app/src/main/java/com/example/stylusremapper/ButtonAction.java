package com.example.stylusremapper;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * One physical side-button's action: a set of input atoms emitted together and held
 * for as long as the button is pressed.
 *
 * An action is composed of three orthogonal parts:
 *   - {@link #meta}:         modifier keys (Ctrl/Alt/Shift) as a META_* bitmask
 *   - {@link #keycode}:      a single primary key, or {@link KeyEvent#KEYCODE_UNKNOWN} for none
 *   - {@link #mouseButtons}: mouse buttons as a {@code MOUSE_*} bitmask, or 0 for none
 *
 * This single type expresses everything from a bare modifier chord (Ctrl+Alt) to a
 * key (Space), a mouse click (left), or a combination (left click + Space = canvas pan).
 */
public final class ButtonAction implements Parcelable {

    // Mouse button bit flags. Independent of MotionEvent's BUTTON_* values so the wire
    // format stays stable even if platform constants change; see toMotionButton().
    public static final int MOUSE_LEFT   = 1;
    public static final int MOUSE_MIDDLE = 2;
    public static final int MOUSE_RIGHT  = 4;

    public final int meta;
    public final int keycode;
    public final int mouseButtons;

    public ButtonAction(int meta, int keycode, int mouseButtons) {
        this.meta = meta;
        this.keycode = keycode;
        this.mouseButtons = mouseButtons;
    }

    /** Nothing to emit. */
    public boolean isEmpty() {
        return meta == 0 && keycode == KeyEvent.KEYCODE_UNKNOWN && mouseButtons == 0;
    }

    public boolean hasKey() {
        return keycode != KeyEvent.KEYCODE_UNKNOWN;
    }

    public boolean hasMouse() {
        return mouseButtons != 0;
    }

    /** Map a single {@code MOUSE_*} bit to its {@link MotionEvent} BUTTON_* constant. */
    public static int toMotionButton(int mouseBit) {
        switch (mouseBit) {
            case MOUSE_LEFT:   return MotionEvent.BUTTON_PRIMARY;
            case MOUSE_RIGHT:  return MotionEvent.BUTTON_SECONDARY;
            case MOUSE_MIDDLE: return MotionEvent.BUTTON_TERTIARY;
            default:           return 0;
        }
    }

    // --- Parcelable ---

    protected ButtonAction(Parcel in) {
        meta = in.readInt();
        keycode = in.readInt();
        mouseButtons = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(meta);
        dest.writeInt(keycode);
        dest.writeInt(mouseButtons);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ButtonAction> CREATOR = new Creator<ButtonAction>() {
        @Override
        public ButtonAction createFromParcel(Parcel in) {
            return new ButtonAction(in);
        }

        @Override
        public ButtonAction[] newArray(int size) {
            return new ButtonAction[size];
        }
    };
}
