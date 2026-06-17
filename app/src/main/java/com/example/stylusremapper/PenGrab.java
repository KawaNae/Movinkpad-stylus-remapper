package com.example.stylusremapper;

/**
 * Java binding for the native EVIOCGRAB helper (libpengrab.so).
 *
 * The library is loaded by absolute path from the app's native library dir, because the
 * Shizuku user service runs in an app_process (shell uid) context where the default library
 * search path used by {@link System#loadLibrary} is not set up.
 */
public final class PenGrab {

    private static volatile boolean loaded = false;

    /** Load libpengrab.so. Idempotent; returns success. */
    public static synchronized boolean load(String nativeLibDir) {
        if (loaded) return true;
        // Preferred: resolve via the class loader's native library path. This works even with
        // extractNativeLibs=false (the .so stays inside the APK), because the Shizuku user
        // service is loaded with the app's APK class-loader namespace.
        try {
            System.loadLibrary("pengrab");
            loaded = true;
            return true;
        } catch (Throwable t1) {
            // Fallback: explicit path (only works if native libs are extracted to disk).
            if (nativeLibDir != null) {
                try {
                    System.load(nativeLibDir + "/libpengrab.so");
                    loaded = true;
                    return true;
                } catch (Throwable t2) {
                    System.err.println("PenGrab: loadLibrary failed (" + t1
                            + "); load(" + nativeLibDir + ") failed (" + t2 + ")");
                }
            } else {
                System.err.println("PenGrab: loadLibrary failed: " + t1);
            }
        }
        loaded = false;
        return false;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * ioctl(fd, EVIOCGRAB, on ? 1 : 0). Grabs/releases exclusive ownership of the input device.
     * @return ioctl return code (0 = success, <0 = error).
     */
    public static native int nativeGrab(int fd, boolean on);

    /**
     * poll(fd, POLLIN, timeoutMs). Waits for data to be available on the fd.
     * @return >0 if data ready, 0 on timeout, <0 on error.
     */
    public static native int nativePoll(int fd, int timeoutMs);

    /**
     * EVIOCGKEY check. Returns true if the given key code is currently pressed.
     */
    public static native boolean nativeIsKeyPressed(int fd, int keyCode);

    /**
     * EVIOCGABS(axis). Returns [minimum, maximum, resolution] or null on error.
     */
    public static native int[] nativeGetAbsInfo(int fd, int axis);

    private PenGrab() {}
}
