package com.example.stylusremapper;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Shizuku UserService that runs with shell-level permissions.
 * Reads raw input events from /dev/input/event6 and injects KeyEvents.
 */
public class RemapperUserService extends IRemapperService.Stub {

    // --- Linux input event constants ---
    private static final int EV_SYN = 0x00;
    private static final int EV_KEY = 0x01;

    // Measured event codes on MovinkPad Pro 14:
    //   Button 1 (pen-tip side):  0x14b only
    //   Button 2 (middle):        0x14c only
    //   Button 3 (far from tip):  0x14b + 0x14c simultaneous
    //   0x140 = hover (ignore)
    private static final int BTN_SIDE1 = 0x14b;
    private static final int BTN_SIDE2 = 0x14c;

    // input_event struct size on 64-bit Android: 24 bytes
    private static final int INPUT_EVENT_SIZE = 24;

    // Key mappings (volatile for thread safety: updated from binder thread, read from reader thread)
    private volatile int sw1Keycode = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH1][0];
    private volatile int sw1Meta    = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH1][1];
    private volatile int sw2Keycode = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH2][0];
    private volatile int sw2Meta    = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH2][1];
    private volatile int sw3Keycode = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH3][0];
    private volatile int sw3Meta    = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH3][1];

    private static final String DEVICE_PATH = "/dev/input/event6";
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

    // State
    private volatile boolean running = false;
    private Thread readThread;
    private FileInputStream currentFis;
    private boolean side1Down = false;
    private boolean side2Down = false;
    private int activeSwitch = 0;
    private Method injectMethod;
    private Object inputManager;

    @Override
    public void start() {
        if (running) return;
        try {
            initInputManager();
        } catch (Exception e) {
            throw new RuntimeException("Failed to init InputManager: " + e.getMessage(), e);
        }
        running = true;
        readThread = new Thread(this::eventLoop, "stylus-reader");
        readThread.start();
    }

    @Override
    public void stop() {
        running = false;
        // Close the FileInputStream to unblock the read() call
        FileInputStream fis = currentFis;
        if (fis != null) {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        side1Down = false;
        side2Down = false;
        activeSwitch = 0;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stop();
        System.exit(0);
    }

    @Override
    public void updateMappings(int sw1Keycode, int sw1Meta,
                               int sw2Keycode, int sw2Meta,
                               int sw3Keycode, int sw3Meta) {
        // Release any currently held key with the OLD mapping before switching
        if (activeSwitch != 0) {
            int[] prev = getMapping(activeSwitch);
            injectKey(prev[0], prev[1], false);
            activeSwitch = 0;
            side1Down = false;
            side2Down = false;
        }

        this.sw1Keycode = sw1Keycode;
        this.sw1Meta = sw1Meta;
        this.sw2Keycode = sw2Keycode;
        this.sw2Meta = sw2Meta;
        this.sw3Keycode = sw3Keycode;
        this.sw3Meta = sw3Meta;
    }

    private void eventLoop() {
        try (FileInputStream fis = new FileInputStream(DEVICE_PATH)) {
            currentFis = fis;
            byte[] buf = new byte[INPUT_EVENT_SIZE];
            boolean needsUpdate = false;
            while (running && fis.read(buf) == INPUT_EVENT_SIZE) {
                int type = (buf[16] & 0xFF) | ((buf[17] & 0xFF) << 8);
                int code = (buf[18] & 0xFF) | ((buf[19] & 0xFF) << 8);
                int value = (buf[20] & 0xFF) | ((buf[21] & 0xFF) << 8)
                        | ((buf[22] & 0xFF) << 16) | ((buf[23] & 0xFF) << 24);

                if (type == EV_KEY) {
                    if (code == BTN_SIDE1) {
                        side1Down = (value == 1);
                        needsUpdate = true;
                    } else if (code == BTN_SIDE2) {
                        side2Down = (value == 1);
                        needsUpdate = true;
                    }
                } else if (type == EV_SYN && needsUpdate) {
                    needsUpdate = false;
                    applySwitch();
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Error reading " + DEVICE_PATH + ": " + e);
            }
        } finally {
            currentFis = null;
            running = false;
        }
    }

    private void initInputManager() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
        IBinder inputBinder = (IBinder) getServiceMethod.invoke(null, "input");

        Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
        Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
        inputManager = asInterfaceMethod.invoke(null, inputBinder);

        injectMethod = inputManager.getClass().getMethod(
                "injectInputEvent", InputEvent.class, int.class);
    }

    private void applySwitch() {
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

        if (newSwitch == activeSwitch) return;

        // Button 3 release protection: when releasing from button 3,
        // go directly to switch=0 to avoid intermediate switch=1 or switch=2
        if (activeSwitch == 3 && newSwitch != 0) {
            int[] prev = getMapping(activeSwitch);
            injectKey(prev[0], prev[1], false);
            activeSwitch = 0;
            side1Down = false;
            side2Down = false;
            return;
        }

        if (activeSwitch != 0) {
            int[] prev = getMapping(activeSwitch);
            injectKey(prev[0], prev[1], false);
        }

        if (newSwitch != 0) {
            int[] mapping = getMapping(newSwitch);
            injectKey(mapping[0], mapping[1], true);
        }

        activeSwitch = newSwitch;
    }

    private int[] getMapping(int switchNum) {
        if (switchNum == 1) return new int[]{sw1Keycode, sw1Meta};
        if (switchNum == 2) return new int[]{sw2Keycode, sw2Meta};
        if (switchNum == 3) return new int[]{sw3Keycode, sw3Meta};
        return new int[]{0, 0};
    }

    private void injectKey(int keyCode, int metaState, boolean down) {
        try {
            long now = SystemClock.uptimeMillis();
            int action = down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

            if (keyCode == KeyEvent.KEYCODE_UNKNOWN && metaState != 0) {
                // Modifier-only (e.g. Ctrl+Alt for brush size)
                injectModifierKeys(metaState, down, now);
                return;
            }

            if (metaState != 0) {
                // Modifier + key (e.g. Ctrl+Z, Ctrl+S)
                // Must send explicit modifier DOWN/UP events to avoid stuck keys
                if (down) {
                    injectModifierKeys(metaState, true, now);
                }
                KeyEvent event = new KeyEvent(
                        now, now, action, keyCode, 0, metaState,
                        -1, 0, KeyEvent.FLAG_FROM_SYSTEM, 0x101);
                injectMethod.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC);
                if (!down) {
                    injectModifierKeys(metaState, false, now);
                }
            } else {
                // No modifiers (e.g. Space, E)
                KeyEvent event = new KeyEvent(
                        now, now, action, keyCode, 0, 0,
                        -1, 0, KeyEvent.FLAG_FROM_SYSTEM, 0x101);
                injectMethod.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC);
            }
        } catch (Exception e) {
            System.err.println("Failed to inject key event: " + e);
        }
    }

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
