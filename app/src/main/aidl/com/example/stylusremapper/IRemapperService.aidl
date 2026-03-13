package com.example.stylusremapper;

interface IRemapperService {
    void start();
    void stop();
    boolean isRunning();
    void destroy();
}
