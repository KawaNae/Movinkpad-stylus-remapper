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
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private TextView btnToggle;

    private boolean suppressListeners = true;

    // Profile UI
    private ProfileManager profileManager;
    private List<ProfileManager.Profile> profiles;
    private Spinner spinnerProfile;
    private ArrayAdapter<String> profileAdapter;
    private List<String> profileNames = new ArrayList<>();

    // Mapping summary
    private TextView tvMappingSummary;

    // Pen indicators
    private View[] penBtns;
    private TextView[] penLabels;
    private View[] penLines;

    // Card expand/collapse
    private int expandedCard = 0;

    // Button config
    private static class ButtonConfig {
        LinearLayout cardContainer;
        LinearLayout cardBody;
        View cardDivider;
        TextView cardNum;
        TextView currentMappingLabel;
        TextView chevron;
        ViewGroup presetGrid;
        int selectedPresetIndex;
        CheckBox cbCtrl, cbAlt, cbShift;
        Spinner keySpinner;
        CheckBox cbMouseLeft, cbMouseMiddle, cbMouseRight;
    }
    private final ButtonConfig[] buttons = new ButtonConfig[3];

    private static final int[][] CARD_IDS = {
        {R.id.card1, R.id.cardHeader1, R.id.cardBody1, R.id.cardDivider1, R.id.cardNum1, R.id.tvCurrentMapping1, R.id.chevron1, R.id.presetGrid1},
        {R.id.card2, R.id.cardHeader2, R.id.cardBody2, R.id.cardDivider2, R.id.cardNum2, R.id.tvCurrentMapping2, R.id.chevron2, R.id.presetGrid2},
        {R.id.card3, R.id.cardHeader3, R.id.cardBody3, R.id.cardDivider3, R.id.cardNum3, R.id.tvCurrentMapping3, R.id.chevron3, R.id.presetGrid3},
    };
    private static final int[][] CONTROL_IDS = {
        {R.id.cbCtrl1, R.id.cbAlt1, R.id.cbShift1, R.id.spinnerKey1, R.id.cbMouseL1, R.id.cbMouseM1, R.id.cbMouseR1},
        {R.id.cbCtrl2, R.id.cbAlt2, R.id.cbShift2, R.id.spinnerKey2, R.id.cbMouseL2, R.id.cbMouseM2, R.id.cbMouseR2},
        {R.id.cbCtrl3, R.id.cbAlt3, R.id.cbShift3, R.id.spinnerKey3, R.id.cbMouseL3, R.id.cbMouseM3, R.id.cbMouseR3},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText("v" + BuildConfig.VERSION_NAME);

        tvShizukuStatus = findViewById(R.id.tvShizukuStatus);
        tvMappingSummary = findViewById(R.id.tvMappingSummary);

        // Start/stop toggle (shows the current run state and toggles on tap)
        btnToggle = findViewById(R.id.btnToggle);
        btnToggle.setOnClickListener(v -> toggleRemapper());

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        profileManager = new ProfileManager(prefs);

        // Request notification permission for API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        // Pen indicators
        penBtns = new View[]{
            findViewById(R.id.penBtn1),
            findViewById(R.id.penBtn2),
            findViewById(R.id.penBtn3)
        };
        penLabels = new TextView[]{
            findViewById(R.id.tvPenLabel1),
            findViewById(R.id.tvPenLabel2),
            findViewById(R.id.tvPenLabel3)
        };
        penLines = new View[]{
            findViewById(R.id.penLine1),
            findViewById(R.id.penLine2),
            findViewById(R.id.penLine3)
        };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            penBtns[i].setOnClickListener(v -> expandCard(idx));
            penLabels[i].setOnClickListener(v -> expandCard(idx));
        }

        setupProfileUI();
        setupButtons();
        loadActiveProfile();

        // Expand the topmost card (Switch 3) by default — matches the physical
        // top-to-bottom order SW3 → SW2 → SW1.
        expandCard(2);

        // Reflect the initial (not-yet-connected) state in the header badge.
        updateUI();

        shizukuHelper = new ShizukuHelper();
        shizukuHelper.setCallback(this);
        shizukuHelper.register();
    }

    // --- Card expand/collapse ---

    private void expandCard(int index) {
        for (int i = 0; i < 3; i++) {
            ButtonConfig bc = buttons[i];
            boolean expanded = (i == index);
            bc.cardBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
            bc.cardDivider.setVisibility(expanded ? View.VISIBLE : View.GONE);
            bc.cardContainer.setBackgroundResource(expanded ? R.drawable.bg_card_expanded : R.drawable.bg_card);
            bc.cardNum.setBackgroundResource(expanded ? R.drawable.bg_number_badge_active : R.drawable.bg_number_badge);
            bc.cardNum.setTextColor(getColor(expanded ? R.color.amber : R.color.text_muted));
            bc.chevron.setText(expanded ? "▴" : "▾");
            bc.chevron.setTextColor(getColor(expanded ? R.color.amber : R.color.text_muted));
        }
        expandedCard = index;
        updatePenIndicators(index);
    }

    private void updatePenIndicators(int activeCard) {
        for (int i = 0; i < 3; i++) {
            boolean active = (i == activeCard);
            penBtns[i].setSelected(active);
            penLabels[i].setTextColor(getColor(active ? R.color.amber : R.color.text_secondary));
            penLines[i].setBackgroundColor(getColor(active ? R.color.amber : R.color.border_light));
        }
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

            ButtonAction a = (presetIndex == MappingPresets.CUSTOM_INDEX)
                    ? profile.actions[i]
                    : MappingPresets.PRESETS[presetIndex];
            setCustomControls(bc, a.keycode, a.meta, a.mouseButtons);
            selectPresetInGrid(bc, presetIndex);
        }
        suppressListeners = false;

        saveAndPushMappings();
    }

    private void showNewProfileDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.dialog_new_profile_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_new_profile_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_new_profile_create, (d, w) -> {
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
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showRenameProfileDialog() {
        String activeName = profileManager.getActiveProfileName();
        if ("Default".equals(activeName)) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.error_cannot_rename_default)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show();
            return;
        }
        EditText input = new EditText(this);
        input.setText(activeName);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_rename_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_rename_confirm, (d, w) -> {
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
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showDeleteProfileDialog() {
        String activeName = profileManager.getActiveProfileName();
        if ("Default".equals(activeName)) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.error_cannot_delete_default)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(getString(R.string.dialog_delete_message, activeName))
                .setPositiveButton(R.string.dialog_delete_confirm, (d, w) -> {
                    profiles.removeIf(p -> p.name.equals(activeName));
                    profileManager.saveProfiles(profiles);
                    profileManager.setActiveProfileName(profiles.get(0).name);
                    refreshProfileSpinner();
                    loadActiveProfile();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private ProfileManager.Profile buildCurrentProfile() {
        String activeName = profileManager.getActiveProfileName();
        ProfileManager.Profile profile = new ProfileManager.Profile();
        profile.name = activeName;
        for (int i = 0; i < 3; i++) {
            profile.presetIndices[i] = buttons[i].selectedPresetIndex;
            profile.actions[i] = computeMapping(i);
        }
        return profile;
    }

    // --- Button setup ---

    private void setupButtons() {
        ArrayAdapter<String> keyAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, KeyDefinitions.getKeyNames(this));
        keyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        for (int i = 0; i < 3; i++) {
            ButtonConfig bc = new ButtonConfig();

            // Card structure
            bc.cardContainer = findViewById(CARD_IDS[i][0]);
            LinearLayout cardHeader = findViewById(CARD_IDS[i][1]);
            bc.cardBody = findViewById(CARD_IDS[i][2]);
            bc.cardDivider = findViewById(CARD_IDS[i][3]);
            bc.cardNum = findViewById(CARD_IDS[i][4]);
            bc.currentMappingLabel = findViewById(CARD_IDS[i][5]);
            bc.chevron = findViewById(CARD_IDS[i][6]);
            bc.presetGrid = findViewById(CARD_IDS[i][7]);

            // Controls
            bc.cbCtrl = findViewById(CONTROL_IDS[i][0]);
            bc.cbAlt = findViewById(CONTROL_IDS[i][1]);
            bc.cbShift = findViewById(CONTROL_IDS[i][2]);
            bc.keySpinner = findViewById(CONTROL_IDS[i][3]);
            bc.cbMouseLeft = findViewById(CONTROL_IDS[i][4]);
            bc.cbMouseMiddle = findViewById(CONTROL_IDS[i][5]);
            bc.cbMouseRight = findViewById(CONTROL_IDS[i][6]);

            bc.selectedPresetIndex = MappingPresets.CUSTOM_INDEX;
            buttons[i] = bc;

            bc.keySpinner.setAdapter(keyAdapter);

            // Build preset grid
            buildPresetGrid(bc, i);

            // Card header click -> expand
            final int btnIndex = i;
            cardHeader.setOnClickListener(v -> expandCard(btnIndex));

            // Modifier checkbox listeners
            CompoundButton.OnCheckedChangeListener checkListener = (buttonView, isChecked) -> {
                buttonView.setTextColor(getColor(isChecked ? R.color.amber : R.color.text_muted));
                if (suppressListeners) return;
                switchToCustom(btnIndex);
                saveAndPushMappings();
            };
            bc.cbCtrl.setOnCheckedChangeListener(checkListener);
            bc.cbAlt.setOnCheckedChangeListener(checkListener);
            bc.cbShift.setOnCheckedChangeListener(checkListener);

            // Mouse checkbox listeners
            CompoundButton.OnCheckedChangeListener mouseListener = (buttonView, isChecked) -> {
                buttonView.setTextColor(getColor(isChecked ? R.color.amber : R.color.text_muted));
                if (suppressListeners) return;
                switchToCustom(btnIndex);
                saveAndPushMappings();
            };
            bc.cbMouseLeft.setOnCheckedChangeListener(mouseListener);
            bc.cbMouseMiddle.setOnCheckedChangeListener(mouseListener);
            bc.cbMouseRight.setOnCheckedChangeListener(mouseListener);

            // Key spinner listener
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

    private void buildPresetGrid(ButtonConfig bc, int btnIndex) {
        bc.presetGrid.removeAllViews();

        // Collect preset indices, skipping CUSTOM_INDEX
        List<Integer> presetIndices = new ArrayList<>();
        for (int p = 0; p < MappingPresets.DESCRIPTION_IDS.length; p++) {
            if (p == MappingPresets.CUSTOM_INDEX) continue;
            presetIndices.add(p);
        }

        // Build rows of 3
        LinearLayout currentRow = null;
        for (int j = 0; j < presetIndices.size(); j++) {
            if (j % 3 == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                if (j > 0) {
                    ((LinearLayout.LayoutParams) currentRow.getLayoutParams()).topMargin =
                            (int) (6 * getResources().getDisplayMetrics().density);
                }
                bc.presetGrid.addView(currentRow);
            }

            final int presetIdx = presetIndices.get(j);
            LinearLayout item = createPresetItem(presetIdx, bc, btnIndex);
            currentRow.addView(item);
        }

        // Pad the last row with invisible spacers if needed
        int remainder = presetIndices.size() % 3;
        if (remainder != 0 && currentRow != null) {
            for (int k = remainder; k < 3; k++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMarginStart((int) (6 * getResources().getDisplayMetrics().density));
                spacer.setLayoutParams(lp);
                currentRow.addView(spacer);
            }
        }
    }

    private LinearLayout createPresetItem(int presetIdx, ButtonConfig bc, int btnIndex) {
        float density = getResources().getDisplayMetrics().density;

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackgroundResource(R.drawable.bg_preset);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (presetIdx != 0) { // Not the very first item in a row — but we handle margins by position
            // We'll set margins uniformly and let the row handle spacing
        }
        int margin = (int) (3 * density);
        lp.setMargins(margin, 0, margin, 0);
        item.setLayoutParams(lp);
        int pad = (int) (10 * density);
        item.setPadding(pad, (int) (8 * density), pad, (int) (8 * density));
        item.setGravity(android.view.Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);

        // Key name (short label from describe)
        ButtonAction action = MappingPresets.PRESETS[presetIdx];
        String keyName = KeyDefinitions.describe(this,action);

        TextView tvKey = new TextView(this);
        tvKey.setText(keyName);
        tvKey.setTextSize(12);
        tvKey.setTextColor(getColor(R.color.text_primary));
        tvKey.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvKey.setGravity(android.view.Gravity.CENTER);
        item.addView(tvKey);

        String desc = "";
        int descId = MappingPresets.DESCRIPTION_IDS[presetIdx];
        if (descId != 0) {
            desc = getString(descId);
        }

        if (!desc.isEmpty()) {
            TextView tvDesc = new TextView(this);
            tvDesc.setText(desc);
            tvDesc.setTextSize(9);
            tvDesc.setTextColor(getColor(R.color.text_muted));
            tvDesc.setGravity(android.view.Gravity.CENTER);
            tvDesc.setPadding(0, (int) (2 * density), 0, 0);
            item.addView(tvDesc);
        }

        // Tag for identification
        item.setTag(presetIdx);

        item.setOnClickListener(v -> {
            if (suppressListeners) return;
            suppressListeners = true;
            selectPresetInGrid(bc, presetIdx);
            ButtonAction a = MappingPresets.PRESETS[presetIdx];
            setCustomControls(bc, a.keycode, a.meta, a.mouseButtons);
            suppressListeners = false;
            saveAndPushMappings();
        });

        return item;
    }

    private void selectPresetInGrid(ButtonConfig bc, int presetIndex) {
        bc.selectedPresetIndex = presetIndex;

        // Update selection state on all preset items in the grid
        for (int r = 0; r < bc.presetGrid.getChildCount(); r++) {
            View rowView = bc.presetGrid.getChildAt(r);
            if (!(rowView instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) rowView;
            for (int c = 0; c < row.getChildCount(); c++) {
                View child = row.getChildAt(c);
                if (child.getTag() instanceof Integer) {
                    int idx = (int) child.getTag();
                    child.setSelected(idx == presetIndex);
                }
            }
        }

        // Update current mapping label
        ButtonAction a;
        if (presetIndex == MappingPresets.CUSTOM_INDEX) {
            a = computeMapping(bc);
        } else {
            a = MappingPresets.PRESETS[presetIndex];
        }
        bc.currentMappingLabel.setText(KeyDefinitions.describe(this,a));
    }

    private void setCustomControls(ButtonConfig bc, int keycode, int metaState, int mouseButtons) {
        bc.cbCtrl.setChecked((metaState & KeyEvent.META_CTRL_ON) != 0);
        bc.cbAlt.setChecked((metaState & KeyEvent.META_ALT_ON) != 0);
        bc.cbShift.setChecked((metaState & KeyEvent.META_SHIFT_ON) != 0);
        bc.keySpinner.setSelection(KeyDefinitions.indexOfKeycode(keycode));
        bc.cbMouseLeft.setChecked((mouseButtons & ButtonAction.MOUSE_LEFT) != 0);
        bc.cbMouseMiddle.setChecked((mouseButtons & ButtonAction.MOUSE_MIDDLE) != 0);
        bc.cbMouseRight.setChecked((mouseButtons & ButtonAction.MOUSE_RIGHT) != 0);
    }

    private void switchToCustom(int btnIndex) {
        ButtonConfig bc = buttons[btnIndex];
        selectPresetInGrid(bc, MappingPresets.CUSTOM_INDEX);
        // Update current mapping label with current custom values
        bc.currentMappingLabel.setText(KeyDefinitions.describe(this,computeMapping(btnIndex)));
    }

    private ButtonAction computeMapping(int btnIndex) {
        return computeMapping(buttons[btnIndex]);
    }

    private ButtonAction computeMapping(ButtonConfig bc) {
        int presetIndex = bc.selectedPresetIndex;

        if (presetIndex != MappingPresets.CUSTOM_INDEX) {
            return MappingPresets.PRESETS[presetIndex];
        }

        // Custom mode: read all values from widgets
        int meta = 0;
        if (bc.cbCtrl.isChecked()) meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (bc.cbAlt.isChecked()) meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        if (bc.cbShift.isChecked()) meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;

        int keycode = KeyDefinitions.KEY_CODES[bc.keySpinner.getSelectedItemPosition()];

        int mouseButtons = 0;
        if (bc.cbMouseLeft.isChecked()) mouseButtons |= ButtonAction.MOUSE_LEFT;
        if (bc.cbMouseMiddle.isChecked()) mouseButtons |= ButtonAction.MOUSE_MIDDLE;
        if (bc.cbMouseRight.isChecked()) mouseButtons |= ButtonAction.MOUSE_RIGHT;

        return new ButtonAction(meta, keycode, mouseButtons);
    }

    private void saveAndPushMappings() {
        ButtonAction[] mappings = new ButtonAction[3];

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
                + "1:" + KeyDefinitions.describe(this,mappings[0])
                + " 2:" + KeyDefinitions.describe(this,mappings[1])
                + " 3:" + KeyDefinitions.describe(this,mappings[2]);
        prefs.edit().putString("notification_summary", summary).apply();

        pushMappingsToService(mappings);
        updateNotification();
        updateMappingSummary();
        updateCurrentMappingLabels();
    }

    private void updateMappingSummary() {
        ButtonAction[] m = new ButtonAction[3];
        for (int i = 0; i < 3; i++) m[i] = computeMapping(i);
        tvMappingSummary.setText("1:" + KeyDefinitions.describe(this,m[0])
                + " · 2:" + KeyDefinitions.describe(this,m[1])
                + " · 3:" + KeyDefinitions.describe(this,m[2]));
    }

    private void updateCurrentMappingLabels() {
        for (int i = 0; i < 3; i++) {
            ButtonConfig bc = buttons[i];
            ButtonAction a = computeMapping(i);
            String desc = KeyDefinitions.describe(this,a);
            bc.currentMappingLabel.setText(desc);
            penLabels[i].setText(desc);
        }
    }

    private void pushMappingsToService(ButtonAction[] mappings) {
        if (remapperService == null) return;
        try {
            remapperService.updateMappings(mappings);
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
                ButtonAction[] mappings = new ButtonAction[3];
                for (int i = 0; i < 3; i++) mappings[i] = computeMapping(i);
                pushMappingsToService(mappings);
                remapperService.start();
                startForegroundService(new Intent(this, RemapperForegroundService.class));
            }
            updateUI();
        } catch (RemoteException e) {
            setRunBadge(getString(R.string.status_error), R.color.red);
        } catch (RuntimeException e) {
            setRunBadge(getString(R.string.status_start_failed), R.color.red);
            android.widget.Toast.makeText(this,
                    getString(R.string.start_failed_detail, e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void updateUI() {
        if (remapperService == null) {
            btnToggle.setEnabled(false);
            btnToggle.setSelected(false);
            setRunBadge(getString(R.string.status_disconnected), R.color.text_muted);
            return;
        }
        try {
            boolean running = remapperService.isRunning();
            btnToggle.setEnabled(true);
            // ON = lit green, OFF = dim grey. (Previously inverted: ON showed red.)
            btnToggle.setSelected(running);
            setRunBadge(getString(running ? R.string.status_running : R.string.status_stopped),
                    running ? R.color.green : R.color.text_secondary);
        } catch (RemoteException e) {
            setRunBadge(getString(R.string.status_error), R.color.red);
        }
    }

    private void setRunBadge(String text, int colorRes) {
        btnToggle.setText(text);
        btnToggle.setTextColor(getColor(colorRes));
    }

    // --- ShizukuHelper.Callback ---

    @Override
    public void onServiceConnected(IRemapperService service) {
        remapperService = service;
        runOnUiThread(() -> {
            tvShizukuStatus.setText(R.string.shizuku_connected);
            tvShizukuStatus.setVisibility(View.VISIBLE);
            try {
                // Give the service the path to load libpengrab.so (EVIOCGRAB helper).
                remapperService.init(getApplicationInfo().nativeLibraryDir);
            } catch (RemoteException e) {
                // non-fatal: key actions still work without the native grab
            }
            ButtonAction[] mappings = new ButtonAction[3];
            for (int i = 0; i < 3; i++) mappings[i] = computeMapping(i);
            pushMappingsToService(mappings);
            pushRotationToService();
            updateUI();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void pushRotationToService() {
        if (remapperService == null) return;
        try {
            android.view.Display display = getWindowManager().getDefaultDisplay();
            android.graphics.Point size = new android.graphics.Point();
            display.getRealSize(size);
            remapperService.setRotation(display.getRotation(), size.x, size.y);
        } catch (RemoteException e) {
            // service may have died
        }
    }

    @Override
    public void onServiceDisconnected() {
        remapperService = null;
        runOnUiThread(() -> {
            tvShizukuStatus.setText(R.string.shizuku_na);
            updateUI();
        });
    }

    @Override
    public void onPermissionResult(boolean granted) {
        runOnUiThread(() -> {
            tvShizukuStatus.setText(getString(granted ? R.string.shizuku_connected : R.string.shizuku_na));
            tvShizukuStatus.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onShizukuNotAvailable() {
        runOnUiThread(() -> {
            tvShizukuStatus.setText(R.string.shizuku_na);
            updateUI();
        });
    }
}
