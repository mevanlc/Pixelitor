/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.filters.painters;

import pixelitor.filters.gui.UserPreset;
import pixelitor.utils.AppPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Formatting defaults and recent text shared by text layer and filter dialogs.
 * Only accepted dialogs update these preferences.
 */
public final class TextDialogPreferences {
    private static final String SETTINGS_KEY = "formatting";
    private static final String HISTORY_SIZE_KEY = "history_size";
    private static final int HISTORY_LIMIT = 20;

    private TextDialogPreferences() {
    }

    private static Preferences prefs() {
        return AppPreferences.mainPrefs.node("text_dialog");
    }

    public static TextSettings loadSettings() {
        return loadSettings(prefs());
    }

    // Overloads allow tests to use a separate preference node.
    static TextSettings loadSettings(Preferences prefs) {
        String savedSettings = prefs.get(SETTINGS_KEY, null);
        if (savedSettings == null) {
            return new TextSettings();
        }

        UserPreset preset = new UserPreset("Last Used Text Settings");
        new TextSettings().saveStateTo(preset);
        preset.loadFromString(savedSettings);
        try {
            return new TextSettings(preset, null);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return new TextSettings();
        }
    }

    public static List<String> loadHistory() {
        return loadHistory(prefs());
    }

    static List<String> loadHistory(Preferences prefs) {
        List<String> history = new ArrayList<>();
        int size = Math.min(prefs.getInt(HISTORY_SIZE_KEY, 0), HISTORY_LIMIT);
        for (int i = 0; i < size; i++) {
            String text = readHistoryEntry(prefs, i);
            if (text != null && !text.isBlank()) {
                history.add(text);
            }
        }
        return history;
    }

    public static void remember(TextSettings settings) {
        remember(settings, prefs());
    }

    static void remember(TextSettings settings, Preferences prefs) {
        UserPreset preset = new UserPreset("Last Used Text Settings");
        settings.withText(TextSettings.DEFAULT_TEXT).saveStateTo(preset);
        prefs.put(SETTINGS_KEY, preset.writeToString());

        String text = settings.getText();
        if (text.isBlank()) {
            return;
        }
        List<String> history = loadHistory(prefs);
        history.remove(text);
        history.addFirst(text);
        if (history.size() > HISTORY_LIMIT) {
            history.removeLast();
        }
        for (int i = 0; i < history.size(); i++) {
            writeHistoryEntry(prefs, i, history.get(i));
        }
        prefs.putInt(HISTORY_SIZE_KEY, history.size());
    }

    private static String readHistoryEntry(Preferences prefs, int index) {
        int parts = prefs.getInt(index + "_parts", 0);
        if (parts == 0) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int part = 0; part < parts; part++) {
            String value = prefs.get(index + "_" + part, null);
            if (value == null) {
                return null;
            }
            text.append(value);
        }
        return text.toString();
    }

    private static void writeHistoryEntry(Preferences prefs, int index, String text) {
        int oldParts = prefs.getInt(index + "_parts", 0);
        int part = 0;
        for (int start = 0; start < text.length(); part++) {
            // Keep complete text even when it exceeds a preference value's size limit.
            int end = Math.min(start + Preferences.MAX_VALUE_LENGTH, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            prefs.put(index + "_" + part, text.substring(start, end));
            start = end;
        }
        for (int i = part; i < oldParts; i++) {
            prefs.remove(index + "_" + i);
        }
        prefs.putInt(index + "_parts", part);
    }
}
