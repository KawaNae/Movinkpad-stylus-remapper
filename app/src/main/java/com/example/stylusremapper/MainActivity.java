package com.example.stylusremapper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity implements ShizukuHelper.Callback {

    private ShizukuHelper shizukuHelper;
    private IRemapperService remapperService;

    private TextView tvShizukuStatus;
    private TextView tvRemapperStatus;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizukuStatus = findViewById(R.id.tvShizukuStatus);
        tvRemapperStatus = findViewById(R.id.tvRemapperStatus);
        btnToggle = findViewById(R.id.btnToggle);

        // Request notification permission for API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        shizukuHelper = new ShizukuHelper();
        shizukuHelper.setCallback(this);
        shizukuHelper.register();

        btnToggle.setOnClickListener(v -> toggleRemapper());
    }

    @Override
    protected void onDestroy() {
        shizukuHelper.unregister();
        super.onDestroy();
    }

    private void toggleRemapper() {
        if (remapperService == null) return;
        try {
            if (remapperService.isRunning()) {
                remapperService.stop();
                stopService(new Intent(this, RemapperForegroundService.class));
            } else {
                remapperService.start();
                startForegroundService(new Intent(this, RemapperForegroundService.class));
            }
            updateUI();
        } catch (RemoteException e) {
            tvRemapperStatus.setText("Remapper: Error");
        }
    }

    private void updateUI() {
        if (remapperService == null) {
            tvRemapperStatus.setText("Remapper: Disconnected");
            btnToggle.setEnabled(false);
            btnToggle.setText("Start");
            return;
        }
        try {
            boolean running = remapperService.isRunning();
            tvRemapperStatus.setText(running ? "Remapper: Running" : "Remapper: Stopped");
            btnToggle.setText(running ? "Stop" : "Start");
            btnToggle.setEnabled(true);
        } catch (RemoteException e) {
            tvRemapperStatus.setText("Remapper: Error");
        }
    }

    // --- ShizukuHelper.Callback ---

    @Override
    public void onServiceConnected(IRemapperService service) {
        remapperService = service;
        runOnUiThread(() -> {
            tvShizukuStatus.setText("Shizuku: Connected");
            updateUI();
        });
    }

    @Override
    public void onServiceDisconnected() {
        remapperService = null;
        runOnUiThread(() -> {
            tvShizukuStatus.setText("Shizuku: Disconnected");
            updateUI();
        });
    }

    @Override
    public void onPermissionResult(boolean granted) {
        runOnUiThread(() -> {
            tvShizukuStatus.setText(granted ? "Shizuku: Permission granted" : "Shizuku: Permission denied");
        });
    }

    @Override
    public void onShizukuNotAvailable() {
        runOnUiThread(() -> {
            tvShizukuStatus.setText("Shizuku: Not available");
            updateUI();
        });
    }
}
