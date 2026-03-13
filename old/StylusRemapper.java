package com.example.stylusremapper;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Stylus button remapper for Wacom MovinkPad Pro 14.
 *
 * Reads raw input events from /dev/input/event6 (Wacom HID 422 Pen),
 * detects BTN_STYLUS / BTN_STYLUS2 presses, and injects configured
 * KeyEvents via InputManager.injectInputEvent().
 *
 * Run via: adb shell CLASSPATH=/data/local/tmp/classes.dex \
 *          app_process / com.example.stylusremapper.StylusRemapper
 */
public class StylusRemapper {

    // --- Linux input event constants ---
    private static final int EV_KEY = 0x01;

    // Measured event codes on MovinkPad Pro 14:
    //   Button 1 (pen-tip side):  0x14b only
    //   Button 2 (middle):        0x14c only
    //   Button 3 (far from tip):  0x14b + 0x14c simultaneous
    //   0x140 = hover (ignore)
    private static final int BTN_SIDE1 = 0x14b;  // button 1 (and part of button 3)
    private static final int BTN_SIDE2 = 0x14c;  // button 2 (and part of button 3)

    // --- input_event struct size on 64-bit Android: 24 bytes ---
    // struct timeval (16) + __u16 type (2) + __u16 code (2) + __s32 value (4)
    private static final int INPUT_EVENT_SIZE = 24;

    // --- Default key mappings ---
    // Switch 1 (BTN_STYLUS): Ctrl+Alt  (for brush size in Clip Studio Paint)
    private static final int SWITCH1_KEYCODE   = KeyEvent.KEYCODE_UNKNOWN;
    private static final int SWITCH1_META      = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON
                                               | KeyEvent.META_ALT_ON  | KeyEvent.META_ALT_LEFT_ON;

    // Switch 2 (BTN_STYLUS2): Space  (for canvas drag in Clip Studio Paint)
    private static final int SWITCH2_KEYCODE   = KeyEvent.KEYCODE_SPACE;
    private static final int SWITCH2_META      = 0;

    // Switch 3 (simultaneous): Ctrl+Z  (undo)
    private static final int SWITCH3_KEYCODE   = KeyEvent.KEYCODE_Z;
    private static final int SWITCH3_META      = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;

    // --- Input device path ---
    private static final String DEVICE_PATH = "/dev/input/event6";

    // --- Injection mode ---
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

    // --- State tracking ---
    private boolean side1Down = false;  // 0x14b
    private boolean side2Down = false;  // 0x14c

    // Which logical switch is currently active (0 = none, 1/2/3)
    private int activeSwitch = 0;

    private Method injectMethod;
    private Object inputManager;

    public static void main(String[] args) {
        System.out.println("StylusRemapper starting...");
        System.out.println("Device: " + DEVICE_PATH);
        System.out.println("Switch 1 (pen-tip side):  Ctrl+Alt (brush size modifier)");
        System.out.println("Switch 2 (middle):        Space (canvas drag)");
        System.out.println("Switch 3 (far from tip):  Ctrl+Z (undo)");
        System.out.println("Press Ctrl+C to stop.");

        new StylusRemapper().run();
    }

    private void run() {
        try {
            initInputManager();
        } catch (Exception e) {
            System.err.println("Failed to initialize InputManager: " + e);
            e.printStackTrace();
            return;
        }

        try (FileInputStream fis = new FileInputStream(DEVICE_PATH)) {
            byte[] buf = new byte[INPUT_EVENT_SIZE];
            System.out.println("Listening for stylus events...");

            while (fis.read(buf) == INPUT_EVENT_SIZE) {
                int type  = (buf[16] & 0xFF) | ((buf[17] & 0xFF) << 8);
                int code  = (buf[18] & 0xFF) | ((buf[19] & 0xFF) << 8);
                int value = (buf[20] & 0xFF) | ((buf[21] & 0xFF) << 8)
                          | ((buf[22] & 0xFF) << 16) | ((buf[23] & 0xFF) << 24);

                if (type == EV_KEY) {
                    handleKeyEvent(code, value);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading " + DEVICE_PATH + ": " + e);
            e.printStackTrace();
        }
    }

    private void initInputManager() throws Exception {
        // Access InputManager service via ServiceManager binder (no Context needed)
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
        IBinder inputBinder = (IBinder) getServiceMethod.invoke(null, "input");

        // Convert IBinder to IInputManager interface
        Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
        Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
        inputManager = asInterfaceMethod.invoke(null, inputBinder);

        // Get injectInputEvent from the IInputManager instance
        injectMethod = inputManager.getClass().getMethod(
                "injectInputEvent", InputEvent.class, int.class);

        System.out.println("InputManager initialized successfully via ServiceManager.");
    }

    private void handleKeyEvent(int code, int value) {
        boolean isDown = (value == 1);

        // Only track BTN_STYLUS and BTN_STYLUS2; ignore tool-mode codes
        // (0x140 BTN_TOOL_RUBBER, 0x14b, 0x14c etc. fire on hover, not button press)
        if (code == BTN_SIDE1) side1Down = isDown;
        else if (code == BTN_SIDE2) side2Down = isDown;
        else return;

        // Determine logical switch:
        //   Button 3: both 0x14b + 0x14c
        //   Button 2: 0x14c only
        //   Button 1: 0x14b only
        int newSwitch;
        if (side1Down && side2Down) {
            newSwitch = 3;
        } else if (side2Down) {
            newSwitch = 2;
        } else if (side1Down) {
            newSwitch = 1;
        } else {
            newSwitch = 0;
        }

        // Only act on transitions
        if (newSwitch == activeSwitch) return;

        // Release previous switch
        if (activeSwitch != 0) {
            System.out.println("  Switch " + activeSwitch + " UP");
            int[] prev = getMapping(activeSwitch);
            injectKey(prev[0], prev[1], false);
        }

        // Press new switch
        if (newSwitch != 0) {
            int[] mapping = getMapping(newSwitch);
            System.out.println("  Switch " + newSwitch + " DOWN");
            injectKey(mapping[0], mapping[1], true);
        }

        activeSwitch = newSwitch;
    }

    private int[] getMapping(int switchNum) {
        if (switchNum == 1) return new int[]{SWITCH1_KEYCODE, SWITCH1_META};
        if (switchNum == 2) return new int[]{SWITCH2_KEYCODE, SWITCH2_META};
        if (switchNum == 3) return new int[]{SWITCH3_KEYCODE, SWITCH3_META};
        return new int[]{0, 0};
    }

    private void injectKey(int keyCode, int metaState, boolean down) {
        try {
            long now = SystemClock.uptimeMillis();
            int action = down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

            if (keyCode == KeyEvent.KEYCODE_UNKNOWN && metaState != 0) {
                // For modifier-only injection (like Ctrl+Alt with no actual key),
                // we inject individual modifier keys.
                injectModifierKeys(metaState, down, now);
                return;
            }

            KeyEvent event = new KeyEvent(
                    now,        // downTime
                    now,        // eventTime
                    action,
                    keyCode,
                    0,          // repeat
                    metaState,
                    -1,         // deviceId (virtual)
                    0,          // scanCode
                    KeyEvent.FLAG_FROM_SYSTEM,
                    0x101       // source: SOURCE_KEYBOARD
            );

            injectMethod.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Exception e) {
            System.err.println("Failed to inject key event: " + e);
        }
    }

    /**
     * Inject modifier keys individually so that apps see them as held down.
     * For Ctrl+Alt, this sends KEYCODE_CTRL_LEFT DOWN and KEYCODE_ALT_LEFT DOWN.
     */
    private void injectModifierKeys(int metaState, boolean down, long now) {
        try {
            int action = down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

            if ((metaState & KeyEvent.META_CTRL_ON) != 0) {
                KeyEvent ctrlEvent = new KeyEvent(now, now, action,
                        KeyEvent.KEYCODE_CTRL_LEFT, 0,
                        down ? KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON : 0,
                        -1, 0, KeyEvent.FLAG_FROM_SYSTEM, 0x101);
                injectMethod.invoke(inputManager, ctrlEvent, INJECT_INPUT_EVENT_MODE_ASYNC);
            }

            if ((metaState & KeyEvent.META_ALT_ON) != 0) {
                int ctrlMeta = (metaState & KeyEvent.META_CTRL_ON) != 0 && down
                        ? KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON : 0;
                KeyEvent altEvent = new KeyEvent(now, now, action,
                        KeyEvent.KEYCODE_ALT_LEFT, 0,
                        ctrlMeta | (down ? KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON : 0),
                        -1, 0, KeyEvent.FLAG_FROM_SYSTEM, 0x101);
                injectMethod.invoke(inputManager, altEvent, INJECT_INPUT_EVENT_MODE_ASYNC);
            }

            if ((metaState & KeyEvent.META_SHIFT_ON) != 0) {
                KeyEvent shiftEvent = new KeyEvent(now, now, action,
                        KeyEvent.KEYCODE_SHIFT_LEFT, 0,
                        down ? metaState : 0,
                        -1, 0, KeyEvent.FLAG_FROM_SYSTEM, 0x101);
                injectMethod.invoke(inputManager, shiftEvent, INJECT_INPUT_EVENT_MODE_ASYNC);
            }
        } catch (Exception e) {
            System.err.println("Failed to inject modifier keys: " + e);
        }
    }
}
