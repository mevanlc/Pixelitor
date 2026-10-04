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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pixelitor.TestHelper;
import pixelitor.filters.gui.UserPreset;
import pixelitor.layers.Filterable;
import pixelitor.layers.TextLayer;
import pixelitor.utils.AppPreferences;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextSettingsPanelTest {
    @BeforeAll
    static void beforeAll() {
        TestHelper.setUnitTestingMode();
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, false", "true, true", "false, true"})
    void fontSizePreviewFollowsPreference(boolean liveResize, boolean useFilter) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var comp = TestHelper.createEmptyComp("Font Size Preview");
            TextSettings defaults = new TextSettings();
            try (var textPreferences = mockStatic(TextDialogPreferences.class);
                 var appPreferences = mockStatic(AppPreferences.class)) {
                textPreferences.when(TextDialogPreferences::loadSettings).thenReturn(defaults);
                textPreferences.when(TextDialogPreferences::loadHistory).thenReturn(List.of());
                appPreferences.when(AppPreferences::isLiveTextResizeEnabled).thenReturn(liveResize);

                TextSettingsPanel panel;
                Supplier<TextSettings> currentSettings;
                if (useFilter) {
                    TextFilter filter = new TextFilter();
                    Filterable layer = mock(Filterable.class);
                    when(layer.getComp()).thenReturn(comp);
                    panel = new TextSettingsPanel(filter, layer);
                    currentSettings = filter::getSettings;
                } else {
                    TextLayer layer = TestHelper.createTextLayer(comp, "Text");
                    comp.add(layer);
                    panel = new TextSettingsPanel(layer);
                    currentSettings = layer::getSettings;
                }

                JSlider slider = findFontSizeSlider(panel);
                assertThat(slider).isNotNull();
                int originalSize = currentSettings.get().getFont().getSize();
                slider.setValueIsAdjusting(true);
                slider.setValue(originalSize + 10);
                assertThat(currentSettings.get().getFont().getSize())
                    .isEqualTo(liveResize ? originalSize + 10 : originalSize);
                slider.setValue(originalSize + 20);
                assertThat(currentSettings.get().getFont().getSize())
                    .isEqualTo(liveResize ? originalSize + 20 : originalSize);

                slider.setValueIsAdjusting(false);
                assertThat(currentSettings.get().getFont().getSize()).isEqualTo(originalSize + 20);
                slider.setValue(originalSize + 30); // committed keyboard/click changes always preview
                assertThat(currentSettings.get().getFont().getSize()).isEqualTo(originalSize + 30);
            }
        });
    }

    private static JSlider findFontSizeSlider(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JSlider slider && "fontSize".equals(container.getName())) {
                return slider;
            }
            if (component instanceof Container child) {
                JSlider slider = findFontSizeSlider(child);
                if (slider != null) {
                    return slider;
                }
            }
        }
        return null;
    }

    @Test
    void newFilterUsesRememberedSettingsWhileExistingFilterKeepsItsSettings() {
        TextSettings remembered = new TextSettings().withText(TextSettings.DEFAULT_TEXT);
        TextSettings existing = remembered.withText("existing smart filter text");
        var comp = TestHelper.createEmptyComp("Text Filter");
        Filterable layer = mock(Filterable.class);
        when(layer.getComp()).thenReturn(comp);

        try (var preferences = mockStatic(TextDialogPreferences.class)) {
            preferences.when(TextDialogPreferences::loadSettings).thenReturn(remembered);
            preferences.when(TextDialogPreferences::loadHistory).thenReturn(List.of());
            TextFilter filter = new TextFilter();
            filter.setSettings(existing);
            filter.createGUI(layer, false);
            assertThat(filter.getSettings()).isSameAs(existing);

            filter.createGUI(layer, true);
            assertThat(filter.getSettings()).isSameAs(remembered);
        }
    }

    @Test
    void historySelectionRestoresCompleteTextAndKeepsFormatting() {
        var comp = TestHelper.createEmptyComp("Text History");
        TextLayer layer = TestHelper.createTextLayer(comp, "Text");
        comp.add(layer);
        TextSettings original = layer.getSettings();
        String text = "<html> First line\n" + "\uD83D\uDE80".repeat(80) + "\nLast line ";

        try (var preferences = mockStatic(TextDialogPreferences.class)) {
            preferences.when(TextDialogPreferences::loadHistory).thenReturn(List.of(text, "other text"));
            TextSettingsPanel panel = new TextSettingsPanel(layer);
            JPopupMenu menu = panel.createTextHistoryMenu(List.of(text, "other text"));
            JMenuItem item = (JMenuItem) menu.getComponent(0);

            assertThat(menu.getComponentCount()).isEqualTo(2);
            assertThat(item.getText()).startsWith("<html> First line ").endsWith("...").doesNotContain("\n");
            assertThat(item.getText().codePointCount(0, item.getText().length())).isEqualTo(60);
            assertThat(item.getClientProperty("html")).isNull();
            item.doClick();

            assertThat(layer.getSettings().getText()).isEqualTo(text);
            UserPreset originalFont = new UserPreset("original font");
            UserPreset restoredFont = new UserPreset("restored font");
            new FontInfo(original.getFont()).saveStateTo(originalFont);
            new FontInfo(layer.getSettings().getFont()).saveStateTo(restoredFont);
            assertThat(restoredFont.writeToString()).isEqualTo(originalFont.writeToString());
            assertThat(layer.getSettings().getColor()).isEqualTo(original.getColor());
            preferences.verify(TextDialogPreferences::loadHistory);
            preferences.verifyNoMoreInteractions();
        }
    }
}
