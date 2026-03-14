package com.example.stylusremapper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity implements ShizukuHelper.Callback {

    private static final String PREFS_NAME = "mappings";

    private ShizukuHelper shizukuHelper;
    private IRemapperService remapperService;
    private SharedPreferences prefs;

    private TextView tvShizukuStatus;
    private TextView tvRemapperStatus;
    private Button btnToggle;
    private Spinner spinnerSwitch1;
    private Spinner spinnerSwitch2;
    private Spinner spinnerSwitch3;
    private boolean spinnersInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizukuStatus = findViewById(R.id.tvShizukuStatus);
        tvRemapperStatus = findViewById(R.id.tvRemapperStatus);
        btnToggle = findViewById(R.id.btnToggle);
        spinnerSwitch1 = findViewById(R.id.spinnerSwitch1);
        spinnerSwitch2 = findViewById(R.id.spinnerSwitch2);
        spinnerSwitch3 = findViewById(R.id.spinnerSwitch3);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Request notification permission for API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        setupSpinners();

        shizukuHelper = new ShizukuHelper();
        shizukuHelper.setCallback(this);
        shizukuHelper.register();

        btnToggle.setOnClickListener(v -> toggleRemapper());
    }

    private void setupSpinners() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, MappingPresets.LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerSwitch1.setAdapter(adapter);
        spinnerSwitch2.setAdapter(adapter);
        spinnerSwitch3.setAdapter(adapter);

        // Restore saved selections
        spinnerSwitch1.setSelection(prefs.getInt("switch1_preset", MappingPresets.DEFAULT_SWITCH1));
        spinnerSwitch2.setSelection(prefs.getInt("switch2_preset", MappingPresets.DEFAULT_SWITCH2));
        spinnerSwitch3.setSelection(prefs.getInt("switch3_preset", MappingPresets.DEFAULT_SWITCH3));

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (spinnersInitialized) {
                    saveAndPushMappings();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };

        spinnerSwitch1.setOnItemSelectedListener(listener);
        spinnerSwitch2.setOnItemSelectedListener(listener);
        spinnerSwitch3.setOnItemSelectedListener(listener);

        // Mark initialized after the current message queue clears
        // (Android fires onItemSelected asynchronously after setSelection)
        spinnerSwitch1.post(() -> spinnersInitialized = true);
    }

    private void saveAndPushMappings() {
        int p1 = spinnerSwitch1.getSelectedItemPosition();
        int p2 = spinnerSwitch2.getSelectedItemPosition();
        int p3 = spinnerSwitch3.getSelectedItemPosition();

        prefs.edit()
                .putInt("switch1_preset", p1)
                .putInt("switch2_preset", p2)
                .putInt("switch3_preset", p3)
                .apply();

        pushMappingsToService();
    }

    private void pushMappingsToService() {
        if (remapperService == null) return;
        try {
            int p1 = spinnerSwitch1.getSelectedItemPosition();
            int p2 = spinnerSwitch2.getSelectedItemPosition();
            int p3 = spinnerSwitch3.getSelectedItemPosition();

            remapperService.updateMappings(
                    MappingPresets.PRESETS[p1][0], MappingPresets.PRESETS[p1][1],
                    MappingPresets.PRESETS[p2][0], MappingPresets.PRESETS[p2][1],
                    MappingPresets.PRESETS[p3][0], MappingPresets.PRESETS[p3][1]);
        } catch (RemoteException e) {
            // Service may have died; will re-push on reconnect
        }
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
                pushMappingsToService();
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
            pushMappingsToService();
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
