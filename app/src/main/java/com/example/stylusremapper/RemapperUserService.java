package com.example.stylusremapper;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shizuku UserService (shell-level) that turns the pen's side buttons into configured actions.
 *
 * Full-proxy architecture: the real pen device is permanently EVIOCGRAB'd at start() and all
 * pen events are re-synthesised as MotionEvents via injectInputEvent. InputReader never sees
 * the real pen, eliminating the stale-key-state problem that plagued temporary grab/ungrab.
 *
 * Button actions modify WHAT gets synthesised (e.g. inject a tip-down while hovering for
 * left-click, or inject a discrete SOURCE_MOUSE click for right/middle), but the proxy
 * never stops running.
 */
public class RemapperUserService extends IRemapperService.Stub {

    private static final String TAG = "StylusRemapDbg";

    // --- Linux input event constants ---
    private static final int EV_SYN = 0x00;
    private static final int EV_KEY = 0x01;
    private static final int EV_ABS = 0x03;
    private static final int ABS_X = 0x00;
    private static final int ABS_Y = 0x01;
    private static final int ABS_PRESSURE = 0x18;
    private static final int ABS_TILT_X = 0x1a;
    private static final int ABS_TILT_Y = 0x1b;
    private static final int BTN_TOOL_PEN = 0x140;
    private static final int BTN_TOUCH = 0x14a;
    private static final int BTN_SIDE1 = 0x14b;
    private static final int BTN_SIDE2 = 0x14c;

    // --- MovinkPad-specific geometry ---
    private static final int PEN_RAW_MAX_X = 18864;
    private static final int PEN_RAW_MAX_Y = 30182;
    private static final int PANEL_W = 1800;
    private static final int PANEL_H = 2880;
    private volatile int rotationDeg = 270;

    private static final int INPUT_EVENT_SIZE = 24;
    private static final String DEVICE_PATH = "/dev/input/event6";
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    private static final long KEY_POINTER_GAP_MS = 20;

    // --- Proxy state machine ---
    private enum ProxyState { OUT, HOVERING, TOUCHING }

    // Per-switch actions (volatile: updated from binder thread, read from reader thread).
    private volatile ButtonAction action1 = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH1];
    private volatile ButtonAction action2 = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH2];
    private volatile ButtonAction action3 = MappingPresets.PRESETS[MappingPresets.DEFAULT_SWITCH3];

    // Pen raw state (reader thread owned; penRawX/Y are volatile for cross-thread logging)
    private volatile int penRawX = PEN_RAW_MAX_X / 2;
    private volatile int penRawY = PEN_RAW_MAX_Y / 2;
    private boolean penInRange = false;
    private boolean penTouching = false;
    private int penRawPressure = 0;
    private int penRawTiltX = 0;
    private int penRawTiltY = 0;

    // Button state (reader thread)
    private boolean side1Down = false;
    private boolean side2Down = false;

    // Proxy injection state (reader thread)
    private ProxyState proxyState = ProxyState.OUT;
    private long proxyDownTime = 0;
    private int activeSwitch = 0;
    private ButtonAction activeAction = null;
    private boolean sessionIsPointer = false;
    private int sessionButtonState = 0;

    // Axis range (queried once at startup via EVIOCGABS)
    private int pressureMax = 8191;

    // Service plumbing
    private volatile boolean running = false;
    private Thread readThread;
    private FileInputStream currentFis;
    private volatile int eventFd = -1;
    private Method injectMethod;
    private Method setActionButtonMethod;
    private Object inputManager;
    private String nativeLibDir;

    // Pre-allocated injection objects to reduce GC at 250 Hz
    private final float[] logicalXY = new float[2];
    private final MotionEvent.PointerProperties stylusPP = new MotionEvent.PointerProperties();
    private final MotionEvent.PointerProperties[] stylusPPArr = {stylusPP};
    private final MotionEvent.PointerCoords stylusPC = new MotionEvent.PointerCoords();
    private final MotionEvent.PointerCoords[] stylusPCArr = {stylusPC};
    {
        stylusPP.id = 0;
        stylusPP.toolType = MotionEvent.TOOL_TYPE_STYLUS;
    }

    // ========================== AIDL interface ==========================

    @Override
    public void init(String nativeLibDir) {
        this.nativeLibDir = nativeLibDir;
        boolean ok = PenGrab.load(nativeLibDir);
        Log.i(TAG, "init nativeLibDir=" + nativeLibDir + " penGrabLoaded=" + ok);
    }

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
        FileInputStream fis = currentFis;
        if (fis != null) {
            try { fis.close(); } catch (IOException ignored) {}
        }
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
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
    public void setRotation(int rotation) {
        int deg;
        switch (rotation) {
            case 0: deg = 0; break;
            case 1: deg = 90; break;
            case 2: deg = 180; break;
            case 3: deg = 270; break;
            default: return;
        }
        if (deg != rotationDeg) {
            Log.i(TAG, "setRotation " + rotationDeg + " -> " + deg);
            rotationDeg = deg;
        }
    }

    @Override
    public void updateMappings(ButtonAction[] actions) {
        if (activeSwitch != 0) {
            endSession();
            activeSwitch = 0;
            side1Down = false;
            side2Down = false;
        }
        if (actions != null && actions.length >= 3) {
            this.action1 = actions[0];
            this.action2 = actions[1];
            this.action3 = actions[2];
        }
    }

    // ========================== Event loop ==========================

    private void eventLoop() {
        side1Down = false;
        side2Down = false;
        penInRange = false;
        penTouching = false;
        penRawPressure = 0;
        penRawTiltX = 0;
        penRawTiltY = 0;
        activeSwitch = 0;
        activeAction = null;
        sessionIsPointer = false;
        sessionButtonState = 0;
        proxyState = ProxyState.OUT;
        proxyDownTime = 0;

        try (FileInputStream fis = new FileInputStream(DEVICE_PATH)) {
            currentFis = fis;
            eventFd = extractFd(fis);
            queryAxisRanges(eventFd);

            boolean grabbed = grabPen(true);
            if (!grabbed) {
                Log.w(TAG, "EVIOCGRAB failed, retrying after 500ms (old process may still hold grab)");
                sleepQuiet(500);
                grabbed = grabPen(true);
            }
            if (!grabbed) {
                Log.w(TAG, "EVIOCGRAB retry failed; proxy will run but duplicate events may occur");
            }

            byte[] buf = new byte[INPUT_EVENT_SIZE];
            boolean frameDirty = false;
            boolean usePoll = PenGrab.isLoaded();

            while (running) {
                if (usePoll) {
                    int ready = PenGrab.nativePoll(eventFd, -1);
                    if (ready < 0) break;
                }
                if (fis.read(buf) != INPUT_EVENT_SIZE) break;

                int type = (buf[16] & 0xFF) | ((buf[17] & 0xFF) << 8);
                int code = (buf[18] & 0xFF) | ((buf[19] & 0xFF) << 8);
                int value = (buf[20] & 0xFF) | ((buf[21] & 0xFF) << 8)
                        | ((buf[22] & 0xFF) << 16) | ((buf[23] & 0xFF) << 24);

                if (type == EV_KEY) {
                    switch (code) {
                        case BTN_TOOL_PEN: penInRange = (value == 1); frameDirty = true; break;
                        case BTN_TOUCH:    penTouching = (value == 1); frameDirty = true; break;
                        case BTN_SIDE1:    side1Down = (value == 1);   frameDirty = true; break;
                        case BTN_SIDE2:    side2Down = (value == 1);   frameDirty = true; break;
                    }
                } else if (type == EV_ABS) {
                    switch (code) {
                        case ABS_X:        penRawX = value;        frameDirty = true; break;
                        case ABS_Y:        penRawY = value;        frameDirty = true; break;
                        case ABS_PRESSURE: penRawPressure = value; frameDirty = true; break;
                        case ABS_TILT_X:   penRawTiltX = value;    break;
                        case ABS_TILT_Y:   penRawTiltY = value;    break;
                    }
                } else if (type == EV_SYN && frameDirty) {
                    frameDirty = false;
                    processFrame();
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Error reading " + DEVICE_PATH + ": " + e);
            }
        } finally {
            endSession();
            if (proxyState != ProxyState.OUT) {
                long now = SystemClock.uptimeMillis();
                float[] xy = currentPenLogical();
                if (proxyState == ProxyState.TOUCHING) {
                    injectStylus(MotionEvent.ACTION_UP, proxyDownTime, now, xy, 0f, 0, 0);
                }
                injectStylus(MotionEvent.ACTION_HOVER_EXIT, now, now, xy, 0f, 0, 0);
                proxyState = ProxyState.OUT;
            }
            grabPen(false);
            currentFis = null;
            eventFd = -1;
            running = false;
        }
    }

    // ========================== Core proxy ==========================

    private void processFrame() {
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
        if (newSwitch != activeSwitch) {
            handleSwitchChange(newSwitch);
        }

        ProxyState desired;
        if (!penInRange) {
            desired = ProxyState.OUT;
        } else if (sessionIsPointer) {
            desired = ProxyState.TOUCHING;
        } else if (penTouching) {
            desired = ProxyState.TOUCHING;
        } else {
            desired = ProxyState.HOVERING;
        }

        emitTransition(desired);
    }

    private void emitTransition(ProxyState desired) {
        long now = SystemClock.uptimeMillis();
        float[] xy = currentPenLogical();
        float pressure = sessionIsPointer ? 1f : normalizedPressure();
        int btnState = sessionButtonState;
        int meta = (activeAction != null) ? activeAction.meta : 0;

        if (proxyState != desired) {
            Log.i(TAG, "proxy " + proxyState + " -> " + desired
                    + " xy=(" + xy[0] + "," + xy[1] + ") p=" + pressure);
        }

        if (proxyState == desired) {
            switch (desired) {
                case TOUCHING:
                    injectStylus(MotionEvent.ACTION_MOVE, proxyDownTime, now,
                            xy, pressure, btnState, meta);
                    break;
                case HOVERING:
                    injectStylus(MotionEvent.ACTION_HOVER_MOVE, proxyDownTime, now,
                            xy, 0f, 0, meta);
                    break;
            }
            return;
        }

        // --- Exit current state ---
        switch (proxyState) {
            case TOUCHING:
                injectStylus(MotionEvent.ACTION_UP, proxyDownTime, now, xy, 0f, 0, 0);
                if (desired == ProxyState.OUT) {
                    injectStylus(MotionEvent.ACTION_HOVER_EXIT, now, now, xy, 0f, 0, 0);
                }
                break;
            case HOVERING:
                if (desired == ProxyState.OUT) {
                    injectStylus(MotionEvent.ACTION_HOVER_EXIT, proxyDownTime, now, xy, 0f, 0, 0);
                }
                break;
        }

        // --- Enter new state ---
        switch (desired) {
            case HOVERING:
                if (proxyState == ProxyState.OUT) {
                    proxyDownTime = now;
                    injectStylus(MotionEvent.ACTION_HOVER_ENTER, proxyDownTime, now, xy, 0f, 0, 0);
                }
                injectStylus(MotionEvent.ACTION_HOVER_MOVE, proxyDownTime, now, xy, 0f, 0, meta);
                break;
            case TOUCHING:
                if (proxyState == ProxyState.OUT) {
                    proxyDownTime = now;
                    injectStylus(MotionEvent.ACTION_HOVER_ENTER, proxyDownTime, now, xy, 0f, 0, 0);
                }
                proxyDownTime = now;
                injectStylus(MotionEvent.ACTION_DOWN, proxyDownTime, now,
                        xy, pressure, btnState, meta);
                break;
        }

        proxyState = desired;
    }

    // ========================== Session management ==========================

    private void handleSwitchChange(int newSwitch) {
        Log.i(TAG, "switch " + activeSwitch + " -> " + newSwitch
                + " (s1=" + side1Down + " s2=" + side2Down + ")");

        // Button 3 release protection: go directly to 0 to avoid a spurious
        // intermediate switch=1/2 as the two physical buttons release one at a time.
        if (activeSwitch == 3 && newSwitch != 0) {
            endSession();
            activeSwitch = 0;
            side1Down = false;
            side2Down = false;
            return;
        }

        endSession();
        activeSwitch = newSwitch;
        if (newSwitch != 0) {
            beginSession(getMapping(newSwitch));
        }
    }

    private void beginSession(ButtonAction action) {
        if (action == null || action.isEmpty()) return;
        activeAction = action;

        boolean isLeftClick = action.hasMouse()
                && (action.mouseButtons & ButtonAction.MOUSE_LEFT) != 0;

        // Key/modifier injection first, with gap before pointer if needed
        if (action.hasKey() || action.meta != 0) {
            injectKey(action.keycode, action.meta, true);
            if (action.hasMouse()) sleepQuiet(KEY_POINTER_GAP_MS);
        }

        if (isLeftClick) {
            sessionIsPointer = true;
            sessionButtonState = pointerButtonState(action.mouseButtons);
            // emitTransition() in processFrame() will inject the ACTION_DOWN
        } else if (action.hasMouse()) {
            // Right/middle: fire a discrete click now; proxy continues normal operation
            float[] xy = currentPenLogical();
            injectDiscreteMouseClick(xy[0], xy[1],
                    pointerButtonState(action.mouseButtons), action.meta);
        }
    }

    private void endSession() {
        if (activeAction == null) return;
        ButtonAction action = activeAction;

        // End synthetic touch before key release (correct ordering for apps that
        // check modifier state at pointer UP time, e.g. Space+drag for pan).
        if (sessionIsPointer && proxyState == ProxyState.TOUCHING) {
            long now = SystemClock.uptimeMillis();
            float[] xy = currentPenLogical();
            injectStylus(MotionEvent.ACTION_UP, proxyDownTime, now, xy, 0f, 0, 0);
            if (penInRange) {
                proxyState = ProxyState.HOVERING;
            } else {
                injectStylus(MotionEvent.ACTION_HOVER_EXIT, now, now, xy, 0f, 0, 0);
                proxyState = ProxyState.OUT;
            }
        }
        sessionIsPointer = false;
        sessionButtonState = 0;

        if (action.hasKey() || action.meta != 0) {
            if (action.hasMouse()) sleepQuiet(KEY_POINTER_GAP_MS);
            injectKey(action.keycode, action.meta, false);
        }

        activeAction = null;
    }

    private ButtonAction getMapping(int switchNum) {
        if (switchNum == 1) return action1;
        if (switchNum == 2) return action2;
        if (switchNum == 3) return action3;
        return null;
    }

    // ========================== Injection ==========================

    private void injectStylus(int action, long downTime, long eventTime,
                              float[] xy, float pressure, int buttonState, int metaState) {
        try {
            stylusPC.x = xy[0];
            stylusPC.y = xy[1];
            stylusPC.pressure = pressure;
            stylusPC.size = 1f;
            MotionEvent ev = MotionEvent.obtain(
                    downTime, eventTime, action,
                    1, stylusPPArr, stylusPCArr,
                    metaState, buttonState,
                    1f, 1f, 0, 0,
                    InputDevice.SOURCE_STYLUS, 0);
            Object result = injectMethod.invoke(inputManager, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
            ev.recycle();
            if (Boolean.FALSE.equals(result)) {
                Log.w(TAG, "injectStylus rejected action=" + action
                        + " xy=(" + xy[0] + "," + xy[1] + ") p=" + pressure);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject stylus event: " + e);
        }
    }

    private void injectDiscreteMouseClick(float x, float y, int buttonState, int metaState) {
        long t = SystemClock.uptimeMillis();
        int ab = buttonState;
        injectMousePointer(MotionEvent.ACTION_HOVER_MOVE, t, t, x, y, 0, metaState, 0);
        injectMousePointer(MotionEvent.ACTION_DOWN, t, t, x, y, buttonState, metaState, 0);
        injectMousePointer(MotionEvent.ACTION_BUTTON_PRESS, t, t, x, y, buttonState, metaState, ab);
        injectMousePointer(MotionEvent.ACTION_BUTTON_RELEASE, t, t + 1, x, y, 0, metaState, ab);
        injectMousePointer(MotionEvent.ACTION_UP, t, t + 1, x, y, 0, metaState, 0);
        injectMousePointer(MotionEvent.ACTION_HOVER_MOVE, t, t + 1, x, y, 0, metaState, 0);
    }

    private void injectMousePointer(int action, long downTime, long eventTime,
                                    float x, float y, int buttonState, int metaState,
                                    int actionButton) {
        try {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = 0;
            pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            pc.x = x;
            pc.y = y;
            boolean inContact = action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_HOVER_MOVE
                    && action != MotionEvent.ACTION_HOVER_ENTER
                    && action != MotionEvent.ACTION_HOVER_EXIT;
            pc.pressure = inContact ? 1f : 0f;
            pc.size = 1f;
            MotionEvent ev = MotionEvent.obtain(
                    downTime, eventTime, action,
                    1, new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                    metaState, buttonState,
                    1f, 1f, 0, 0,
                    InputDevice.SOURCE_MOUSE, 0);
            if (actionButton != 0 && setActionButtonMethod != null) {
                try { setActionButtonMethod.invoke(ev, actionButton); } catch (Exception ignored) {}
            }
            injectMethod.invoke(inputManager, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
            ev.recycle();
        } catch (Exception e) {
            System.err.println("Failed to inject mouse event: " + e);
        }
    }

    private void injectKey(int keyCode, int metaState, boolean down) {
        try {
            long now = SystemClock.uptimeMillis();
            int action = down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

            if (keyCode == KeyEvent.KEYCODE_UNKNOWN && metaState != 0) {
                injectModifierKeys(metaState, down, now);
                return;
            }

            if (metaState != 0) {
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

    // ========================== Utilities ==========================

    private void initInputManager() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
        IBinder inputBinder = (IBinder) getServiceMethod.invoke(null, "input");

        Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
        Method asInterfaceMethod = stubClass.getMethod("asInterface", IBinder.class);
        inputManager = asInterfaceMethod.invoke(null, inputBinder);

        injectMethod = inputManager.getClass().getMethod(
                "injectInputEvent", InputEvent.class, int.class);
        try {
            setActionButtonMethod = MotionEvent.class.getMethod("setActionButton", int.class);
        } catch (NoSuchMethodException e) {
            setActionButtonMethod = null;
        }
    }

    private int pointerButtonState(int mouseButtons) {
        int state = 0;
        if ((mouseButtons & ButtonAction.MOUSE_LEFT) != 0) state |= MotionEvent.BUTTON_PRIMARY;
        if ((mouseButtons & ButtonAction.MOUSE_RIGHT) != 0) state |= MotionEvent.BUTTON_SECONDARY;
        if ((mouseButtons & ButtonAction.MOUSE_MIDDLE) != 0) state |= MotionEvent.BUTTON_TERTIARY;
        return state;
    }

    private float[] currentPenLogical() {
        float rx = (penRawX / (float) PEN_RAW_MAX_X) * PANEL_W;
        float ry = (penRawY / (float) PEN_RAW_MAX_Y) * PANEL_H;
        switch (rotationDeg) {
            case 270:
                logicalXY[0] = PANEL_H - ry;
                logicalXY[1] = rx;
                break;
            case 90:
                logicalXY[0] = ry;
                logicalXY[1] = PANEL_W - rx;
                break;
            case 180:
                logicalXY[0] = PANEL_W - rx;
                logicalXY[1] = PANEL_H - ry;
                break;
            default:
                logicalXY[0] = rx;
                logicalXY[1] = ry;
                break;
        }
        return logicalXY;
    }

    private float normalizedPressure() {
        if (pressureMax <= 0) return penTouching ? 1f : 0f;
        return Math.max(0f, Math.min(1f, penRawPressure / (float) pressureMax));
    }

    private void queryAxisRanges(int fd) {
        if (!PenGrab.isLoaded()) return;
        int[] info = PenGrab.nativeGetAbsInfo(fd, ABS_PRESSURE);
        if (info != null && info[1] > 0) {
            pressureMax = info[1];
            Log.i(TAG, "ABS_PRESSURE range: 0.." + pressureMax);
        }
    }

    private boolean grabPen(boolean on) {
        int fd = eventFd;
        if (fd < 0 || !PenGrab.isLoaded()) {
            Log.i(TAG, "grabPen(" + on + ") skipped fd=" + fd + " loaded=" + PenGrab.isLoaded());
            return false;
        }
        int rc = PenGrab.nativeGrab(fd, on);
        Log.i(TAG, "grabPen(" + on + ") fd=" + fd + " rc=" + rc);
        return rc == 0;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static int extractFd(FileInputStream fis) {
        try {
            FileDescriptor fdObj = fis.getFD();
            Field f = FileDescriptor.class.getDeclaredField("descriptor");
            f.setAccessible(true);
            return f.getInt(fdObj);
        } catch (Exception e) {
            Log.i(TAG, "extractFd failed: " + e);
            return -1;
        }
    }
}
