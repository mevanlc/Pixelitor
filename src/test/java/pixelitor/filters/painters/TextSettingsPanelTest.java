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
import pixelitor.TestHelper;
import pixelitor.filters.gui.UserPreset;
import pixelitor.layers.Filterable;
import pixelitor.layers.TextLayer;

import javax.swing.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextSettingsPanelTest {
    @BeforeAll
    static void beforeAll() {
        TestHelper.setUnitTestingMode();
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
