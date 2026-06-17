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
    // Surface.ROTATION_* (0/1/2/3). Pushed by the UI process which has Display access.
    void setRotation(int rotation);
}
