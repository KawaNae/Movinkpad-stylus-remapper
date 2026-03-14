package com.example.stylusremapper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
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

    private boolean suppressListeners = true;

    private static class ButtonConfig {
        Spinner presetSpinner;
        CheckBox cbCtrl, cbAlt, cbShift;
        Spinner keySpinner;
    }
    private final ButtonConfig[] buttons = new ButtonConfig[3];

    // View IDs for each button config
    private static final int[][] VIEW_IDS = {
        {R.id.spinnerPreset1, R.id.cbCtrl1, R.id.cbAlt1, R.id.cbShift1, R.id.spinnerKey1},
        {R.id.spinnerPreset2, R.id.cbCtrl2, R.id.cbAlt2, R.id.cbShift2, R.id.spinnerKey2},
        {R.id.spinnerPreset3, R.id.cbCtrl3, R.id.cbAlt3, R.id.cbShift3, R.id.spinnerKey3},
    };

    private static final int[] DEFAULT_PRESETS = {
        MappingPresets.DEFAULT_SWITCH1,
        MappingPresets.DEFAULT_SWITCH2,
        MappingPresets.DEFAULT_SWITCH3,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizukuStatus = findViewById(R.id.tvShizukuStatus);
        tvRemapperStatus = findViewById(R.id.tvRemapperStatus);
        btnToggle = findViewById(R.id.btnToggle);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Request notification permission for API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        setupButtons();

        shizukuHelper = new ShizukuHelper();
        shizukuHelper.setCallback(this);
        shizukuHelper.register();

        btnToggle.setOnClickListener(v -> toggleRemapper());
    }

    private void setupButtons() {
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, MappingPresets.LABELS);
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        ArrayAdapter<String> keyAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, KeyDefinitions.KEY_NAMES);
        keyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        for (int i = 0; i < 3; i++) {
            ButtonConfig bc = new ButtonConfig();
            bc.presetSpinner = findViewById(VIEW_IDS[i][0]);
            bc.cbCtrl = findViewById(VIEW_IDS[i][1]);
            bc.cbAlt = findViewById(VIEW_IDS[i][2]);
            bc.cbShift = findViewById(VIEW_IDS[i][3]);
            bc.keySpinner = findViewById(VIEW_IDS[i][4]);
            buttons[i] = bc;

            bc.presetSpinner.setAdapter(presetAdapter);
            bc.keySpinner.setAdapter(keyAdapter);

            // Restore saved state
            int presetIndex = prefs.getInt("switch" + (i + 1) + "_preset", DEFAULT_PRESETS[i]);
            bc.presetSpinner.setSelection(presetIndex);

            if (presetIndex == MappingPresets.CUSTOM_INDEX) {
                int keycode = prefs.getInt("switch" + (i + 1) + "_keycode", KeyEvent.KEYCODE_UNKNOWN);
                int meta = prefs.getInt("switch" + (i + 1) + "_meta", 0);
                setCustomControls(bc, keycode, meta);
                setCustomControlsEnabled(bc, true);
            } else {
                int keycode = MappingPresets.PRESETS[presetIndex][0];
                int meta = MappingPresets.PRESETS[presetIndex][1];
                setCustomControls(bc, keycode, meta);
                setCustomControlsEnabled(bc, false);
            }

            // Attach listeners
            final int btnIndex = i;

            bc.presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (suppressListeners) return;
                    suppressListeners = true;
                    if (position == MappingPresets.CUSTOM_INDEX) {
                        setCustomControlsEnabled(buttons[btnIndex], true);
                    } else {
                        int keycode = MappingPresets.PRESETS[position][0];
                        int meta = MappingPresets.PRESETS[position][1];
                        setCustomControls(buttons[btnIndex], keycode, meta);
                        setCustomControlsEnabled(buttons[btnIndex], false);
                    }
                    suppressListeners = false;
                    saveAndPushMappings();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            CompoundButton.OnCheckedChangeListener checkListener = (buttonView, isChecked) -> {
                if (suppressListeners) return;
                switchToCustom(btnIndex);
                saveAndPushMappings();
            };
            bc.cbCtrl.setOnCheckedChangeListener(checkListener);
            bc.cbAlt.setOnCheckedChangeListener(checkListener);
            bc.cbShift.setOnCheckedChangeListener(checkListener);

            bc.keySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (suppressListeners) return;
                    switchToCustom(btnIndex);
                    saveAndPushMappings();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // Enable listeners after initial setup
        buttons[0].presetSpinner.post(() -> suppressListeners = false);
    }

    private void setCustomControls(ButtonConfig bc, int keycode, int metaState) {
        bc.cbCtrl.setChecked((metaState & KeyEvent.META_CTRL_ON) != 0);
        bc.cbAlt.setChecked((metaState & KeyEvent.META_ALT_ON) != 0);
        bc.cbShift.setChecked((metaState & KeyEvent.META_SHIFT_ON) != 0);
        bc.keySpinner.setSelection(KeyDefinitions.indexOfKeycode(keycode));
    }

    private void setCustomControlsEnabled(ButtonConfig bc, boolean enabled) {
        bc.cbCtrl.setEnabled(enabled);
        bc.cbAlt.setEnabled(enabled);
        bc.cbShift.setEnabled(enabled);
        bc.keySpinner.setEnabled(enabled);
    }

    private void switchToCustom(int btnIndex) {
        suppressListeners = true;
        buttons[btnIndex].presetSpinner.setSelection(MappingPresets.CUSTOM_INDEX);
        setCustomControlsEnabled(buttons[btnIndex], true);
        suppressListeners = false;
    }

    private int[] computeMapping(int btnIndex) {
        ButtonConfig bc = buttons[btnIndex];
        int presetIndex = bc.presetSpinner.getSelectedItemPosition();

        if (presetIndex != MappingPresets.CUSTOM_INDEX) {
            return MappingPresets.PRESETS[presetIndex];
        }

        int meta = 0;
        if (bc.cbCtrl.isChecked()) meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (bc.cbAlt.isChecked()) meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        if (bc.cbShift.isChecked()) meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;

        int keycode = KeyDefinitions.KEY_CODES[bc.keySpinner.getSelectedItemPosition()];
        return new int[]{keycode, meta};
    }

    private void saveAndPushMappings() {
        SharedPreferences.Editor editor = prefs.edit();
        int[][] mappings = new int[3][];

        for (int i = 0; i < 3; i++) {
            int presetIndex = buttons[i].presetSpinner.getSelectedItemPosition();
            mappings[i] = computeMapping(i);
            editor.putInt("switch" + (i + 1) + "_preset", presetIndex);
            editor.putInt("switch" + (i + 1) + "_keycode", mappings[i][0]);
            editor.putInt("switch" + (i + 1) + "_meta", mappings[i][1]);
        }

        // Build notification summary
        String summary = "1:" + KeyDefinitions.describeMapping(mappings[0][0], mappings[0][1])
                + " 2:" + KeyDefinitions.describeMapping(mappings[1][0], mappings[1][1])
                + " 3:" + KeyDefinitions.describeMapping(mappings[2][0], mappings[2][1]);
        editor.putString("notification_summary", summary);
        editor.apply();

        pushMappingsToService(mappings);
        updateNotification();
    }

    private void pushMappingsToService(int[][] mappings) {
        if (remapperService == null) return;
        try {
            remapperService.updateMappings(
                    mappings[0][0], mappings[0][1],
                    mappings[1][0], mappings[1][1],
                    mappings[2][0], mappings[2][1]);
        } catch (RemoteException e) {
            // Service may have died
        }
    }

    private void updateNotification() {
        try {
            if (remapperService != null && remapperService.isRunning()) {
                startForegroundService(new Intent(this, RemapperForegroundService.class));
            }
        } catch (RemoteException e) {
            // ignore
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
                int[][] mappings = new int[3][];
                for (int i = 0; i < 3; i++) mappings[i] = computeMapping(i);
                pushMappingsToService(mappings);
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
            int[][] mappings = new int[3][];
            for (int i = 0; i < 3; i++) mappings[i] = computeMapping(i);
            pushMappingsToService(mappings);
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
