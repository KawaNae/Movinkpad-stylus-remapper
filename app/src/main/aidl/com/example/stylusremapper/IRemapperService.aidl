package com.example.stylusremapper;

interface IRemapperService {
    void start();
    void stop();
    boolean isRunning();
    void destroy();
    void updateMappings(int sw1Keycode, int sw1Meta,
                        int sw2Keycode, int sw2Meta,
                        int sw3Keycode, int sw3Meta);
}
