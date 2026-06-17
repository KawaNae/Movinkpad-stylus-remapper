package com.example.stylusremapper;

import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ProfileManager {

    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE_PROFILE = "active_profile";
    private static final String DEFAULT_PROFILE_NAME = "Default";

    private final SharedPreferences prefs;

    public ProfileManager(SharedPreferences prefs) {
        this.prefs = prefs;
        migrateIfNeeded();
    }

    /** Migrate legacy per-switch prefs into a Default profile. */
    private void migrateIfNeeded() {
        if (prefs.contains(KEY_PROFILES)) return;

        Profile def = new Profile();
        def.name = DEFAULT_PROFILE_NAME;
        int[] defaultPresets = {
                MappingPresets.DEFAULT_SWITCH1,
                MappingPresets.DEFAULT_SWITCH2,
                MappingPresets.DEFAULT_SWITCH3
        };
        for (int i = 0; i < 3; i++) {
            int sw = i + 1;
            def.presetIndices[i] = prefs.getInt("switch" + sw + "_preset", defaultPresets[i]);
            if (def.presetIndices[i] == MappingPresets.CUSTOM_INDEX) {
                int keycode = prefs.getInt("switch" + sw + "_keycode", KeyEvent.KEYCODE_UNKNOWN);
                int meta = prefs.getInt("switch" + sw + "_meta", 0);
                def.actions[i] = new ButtonAction(meta, keycode, 0);
            } else {
                def.actions[i] = MappingPresets.PRESETS[def.presetIndices[i]];
            }
        }

        List<Profile> profiles = new ArrayList<>();
        profiles.add(def);
        saveProfiles(profiles);
        setActiveProfileName(DEFAULT_PROFILE_NAME);
    }

    public List<Profile> loadProfiles() {
        String json = prefs.getString(KEY_PROFILES, "[]");
        List<Profile> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(Profile.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            // Corrupted data; return default
            Profile def = new Profile();
            def.name = DEFAULT_PROFILE_NAME;
            result.add(def);
        }
        if (result.isEmpty()) {
            Profile def = new Profile();
            def.name = DEFAULT_PROFILE_NAME;
            result.add(def);
        }
        return result;
    }

    public void saveProfiles(List<Profile> profiles) {
        JSONArray arr = new JSONArray();
        for (Profile p : profiles) {
            arr.put(p.toJson());
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply();
    }

    public String getActiveProfileName() {
        return prefs.getString(KEY_ACTIVE_PROFILE, DEFAULT_PROFILE_NAME);
    }

    public void setActiveProfileName(String name) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE, name).apply();
    }

    public Profile findProfile(List<Profile> profiles, String name) {
        for (Profile p : profiles) {
            if (p.name.equals(name)) return p;
        }
        return profiles.get(0);
    }

    public static class Profile {
        public String name;
        public int[] presetIndices = new int[]{
                MappingPresets.DEFAULT_SWITCH1,
                MappingPresets.DEFAULT_SWITCH2,
                MappingPresets.DEFAULT_SWITCH3
        };
        public ButtonAction[] actions = new ButtonAction[3];

        public Profile() {
            // Initialize actions from default presets
            for (int i = 0; i < 3; i++) {
                actions[i] = MappingPresets.PRESETS[presetIndices[i]];
            }
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("name", name);
                for (int i = 0; i < 3; i++) {
                    int sw = i + 1;
                    obj.put("sw" + sw + "_preset", presetIndices[i]);
                    obj.put("sw" + sw + "_keycode", actions[i].keycode);
                    obj.put("sw" + sw + "_meta", actions[i].meta);
                    obj.put("sw" + sw + "_mouse", actions[i].mouseButtons);
                }
            } catch (JSONException ignored) {}
            return obj;
        }

        public static Profile fromJson(JSONObject obj) throws JSONException {
            Profile p = new Profile();
            p.name = obj.getString("name");
            for (int i = 0; i < 3; i++) {
                int sw = i + 1;
                p.presetIndices[i] = obj.optInt("sw" + sw + "_preset", 0);
                int keycode = obj.optInt("sw" + sw + "_keycode", KeyEvent.KEYCODE_UNKNOWN);
                int meta = obj.optInt("sw" + sw + "_meta", 0);
                // mouse defaults to 0 -> backward compatible with profiles saved before mouse support
                int mouse = obj.optInt("sw" + sw + "_mouse", 0);
                p.actions[i] = new ButtonAction(meta, keycode, mouse);
            }
            return p;
        }

        /** Create a deep copy with a new name. */
        public Profile copy(String newName) {
            Profile c = new Profile();
            c.name = newName;
            System.arraycopy(presetIndices, 0, c.presetIndices, 0, 3);
            System.arraycopy(actions, 0, c.actions, 0, 3);
            return c;
        }
    }
}
