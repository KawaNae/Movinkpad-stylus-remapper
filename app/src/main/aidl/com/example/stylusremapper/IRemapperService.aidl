package com.example.stylusremapper;

import com.example.stylusremapper.ButtonAction;

interface IRemapperService {
    // Pass the app's nativeLibraryDir so the service can load libpengrab.so (EVIOCGRAB helper).
    void init(String nativeLibDir);
    void start();
    void stop();
    boolean isRunning();
    void destroy();
    void updateMappings(in ButtonAction[] actions);
    // Display info pushed by the UI process. rotation = Surface.ROTATION_* (0/1/2/3),
    // displayWidth/Height = current display dimensions in pixels (rotation-aware).
    void setRotation(int rotation, int displayWidth, int displayHeight);
}
