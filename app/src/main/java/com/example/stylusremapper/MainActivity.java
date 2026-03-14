package com.example.stylusremapper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements ShizukuHelper.Callback {

    private static final String PREFS_NAME = "mappings";

    private ShizukuHelper shizukuHelper;
    private IRemapperService remapperService;
    private SharedPreferences prefs;

    private TextView tvShizukuStatus;
    private TextView tvRemapperStatus;
    private Button btnToggle;

    private boolean suppressListeners = true;

    // Profile UI
    private ProfileManager profileManager;
    private List<ProfileManager.Profile> profiles;
    private Spinner spinnerProfile;
    private ArrayAdapter<String> profileAdapter;
    private List<String> profileNames = new ArrayList<>();

    // Button config
    private static class ButtonConfig {
        Spinner presetSpinner;
        CheckBox cbCtrl, cbAlt, cbShift;
        Spinner keySpinner;
    }
    private final ButtonConfig[] buttons = new ButtonConfig[3];

    private static final int[][] VIEW_IDS = {
        {R.id.spinnerPreset1, R.id.cbCtrl1, R.id.cbAlt1, R.id.cbShift1, R.id.spinnerKey1},
        {R.id.spinnerPreset2, R.id.cbCtrl2, R.id.cbAlt2, R.id.cbShift2, R.id.spinnerKey2},
        {R.id.spinnerPreset3, R.id.cbCtrl3, R.id.cbAlt3, R.id.cbShift3, R.id.spinnerKey3},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizukuStatus = findViewById(R.id.tvShizukuStatus);
        tvRemapperStatus = findViewById(R.id.tvRemapperStatus);
        btnToggle = findViewById(R.id.btnToggle);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        profileManager = new ProfileManager(prefs);

        // Request notification permission for API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        setupProfileUI();
        setupButtons();
        loadActiveProfile();

        shizukuHelper = new ShizukuHelper();
        shizukuHelper.setCallback(this);
        shizukuHelper.register();

        btnToggle.setOnClickListener(v -> toggleRemapper());
    }

    // --- Profile UI ---

    private void setupProfileUI() {
        spinnerProfile = findViewById(R.id.spinnerProfile);
        profiles = profileManager.loadProfiles();
        refreshProfileSpinner();

        spinnerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressListeners) return;
                String name = profileNames.get(position);
                profileManager.setActiveProfileName(name);
                loadActiveProfile();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        findViewById(R.id.btnProfileNew).setOnClickListener(v -> showNewProfileDialog());
        findViewById(R.id.btnProfileRename).setOnClickListener(v -> showRenameProfileDialog());
        findViewById(R.id.btnProfileDelete).setOnClickListener(v -> showDeleteProfileDialog());
    }

    private void refreshProfileSpinner() {
        profileNames.clear();
        for (ProfileManager.Profile p : profiles) {
            profileNames.add(p.name);
        }
        profileAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, profileNames);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProfile.setAdapter(profileAdapter);

        // Select active profile
        String activeName = profileManager.getActiveProfileName();
        int idx = profileNames.indexOf(activeName);
        if (idx >= 0) {
            suppressListeners = true;
            spinnerProfile.setSelection(idx);
            suppressListeners = false;
        }
    }

    private void loadActiveProfile() {
        String activeName = profileManager.getActiveProfileName();
        ProfileManager.Profile profile = profileManager.findProfile(profiles, activeName);

        suppressListeners = true;
        for (int i = 0; i < 3; i++) {
            ButtonConfig bc = buttons[i];
            int presetIndex = profile.presetIndices[i];
            bc.presetSpinner.setSelection(presetIndex);

            if (presetIndex == MappingPresets.CUSTOM_INDEX) {
                setCustomControls(bc, profile.keycodes[i], profile.metaStates[i]);
                setCustomControlsEnabled(bc, true);
            } else {
                int keycode = MappingPresets.PRESETS[presetIndex][0];
                int meta = MappingPresets.PRESETS[presetIndex][1];
                setCustomControls(bc, keycode, meta);
                setCustomControlsEnabled(bc, false);
            }
        }
        suppressListeners = false;

        saveAndPushMappings();
    }

    private void showNewProfileDialog() {
        EditText input = new EditText(this);
        input.setHint("プロファイル名");
        new AlertDialog.Builder(this)
                .setTitle("新規プロファイル")
                .setView(input)
                .setPositiveButton("作成", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    // Check for duplicate names
                    for (ProfileManager.Profile p : profiles) {
                        if (p.name.equals(name)) return;
                    }
                    // Copy current settings to new profile
                    ProfileManager.Profile current = buildCurrentProfile();
                    ProfileManager.Profile newProfile = current.copy(name);
                    profiles.add(newProfile);
                    profileManager.saveProfiles(profiles);
                    profileManager.setActiveProfileName(name);
                    refreshProfileSpinner();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showRenameProfileDialog() {
        String activeName = profileManager.getActiveProfileName();
        if ("Default".equals(activeName)) {
            new AlertDialog.Builder(this)
                    .setMessage("Defaultプロファイルは名前変更できません")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        EditText input = new EditText(this);
        input.setText(activeName);
        new AlertDialog.Builder(this)
                .setTitle("名前変更")
                .setView(input)
                .setPositiveButton("変更", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(activeName)) return;
                    for (ProfileManager.Profile p : profiles) {
                        if (p.name.equals(newName)) return;
                    }
                    ProfileManager.Profile profile = profileManager.findProfile(profiles, activeName);
                    profile.name = newName;
                    profileManager.saveProfiles(profiles);
                    profileManager.setActiveProfileName(newName);
                    refreshProfileSpinner();
                    saveAndPushMappings(); // Update notification
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showDeleteProfileDialog() {
        String activeName = profileManager.getActiveProfileName();
        if ("Default".equals(activeName)) {
            new AlertDialog.Builder(this)
                    .setMessage("Defaultプロファイルは削除できません")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("プロファイル削除")
                .setMessage("\"" + activeName + "\" を削除しますか？")
                .setPositiveButton("削除", (d, w) -> {
                    profiles.removeIf(p -> p.name.equals(activeName));
                    profileManager.saveProfiles(profiles);
                    profileManager.setActiveProfileName(profiles.get(0).name);
                    refreshProfileSpinner();
                    loadActiveProfile();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private ProfileManager.Profile buildCurrentProfile() {
        String activeName = profileManager.getActiveProfileName();
        ProfileManager.Profile profile = new ProfileManager.Profile();
        profile.name = activeName;
        for (int i = 0; i < 3; i++) {
            int[] mapping = computeMapping(i);
            profile.presetIndices[i] = buttons[i].presetSpinner.getSelectedItemPosition();
            profile.keycodes[i] = mapping[0];
            profile.metaStates[i] = mapping[1];
        }
        return profile;
    }

    // --- Button setup ---

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
        int[][] mappings = new int[3][];

        // Update current profile
        ProfileManager.Profile current = buildCurrentProfile();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name.equals(current.name)) {
                profiles.set(i, current);
                break;
            }
        }
        profileManager.saveProfiles(profiles);

        for (int i = 0; i < 3; i++) {
            mappings[i] = computeMapping(i);
        }

        // Build notification summary with profile name
        String activeName = profileManager.getActiveProfileName();
        String summary = "[" + activeName + "] "
                + "1:" + KeyDefinitions.describeMapping(mappings[0][0], mappings[0][1])
                + " 2:" + KeyDefinitions.describeMapping(mappings[1][0], mappings[1][1])
                + " 3:" + KeyDefinitions.describeMapping(mappings[2][0], mappings[2][1]);
        prefs.edit().putString("notification_summary", summary).apply();

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
