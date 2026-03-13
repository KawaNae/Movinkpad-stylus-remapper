package com.example.stylusremapper;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

public class ShizukuHelper {

    public interface Callback {
        void onServiceConnected(IRemapperService service);
        void onServiceDisconnected();
        void onPermissionResult(boolean granted);
        void onShizukuNotAvailable();
    }

    private static final int REQUEST_CODE = 1001;
    private IRemapperService remapperService;
    private Callback callback;
    private boolean bound = false;

    private final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(
                    new ComponentName(
                            BuildConfig.APPLICATION_ID,
                            RemapperUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("remapper")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            remapperService = IRemapperService.Stub.asInterface(binder);
            bound = true;
            if (callback != null) callback.onServiceConnected(remapperService);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remapperService = null;
            bound = false;
            if (callback != null) callback.onServiceDisconnected();
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (hasPermission()) {
            bindService();
        } else {
            requestPermission();
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        remapperService = null;
        bound = false;
        if (callback != null) callback.onShizukuNotAvailable();
    };

    private final Shizuku.OnRequestPermissionResultListener permResultListener =
            (requestCode, grantResult) -> {
                boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                if (callback != null) callback.onPermissionResult(granted);
                if (granted) bindService();
            };

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public void requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE);
    }

    public void bindService() {
        if (!bound) {
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        }
    }

    public void unbindService() {
        if (bound) {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
            bound = false;
        }
    }

    public void register() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permResultListener);
    }

    public void unregister() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permResultListener);
    }
}
