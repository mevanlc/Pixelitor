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

import org.jdesktop.swingx.painter.effects.AbstractAreaEffect;
import org.jdesktop.swingx.painter.effects.GlowPathEffect;
import org.jdesktop.swingx.painter.effects.ShadowPathEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.TestHelper;
import pixelitor.gui.utils.BoxAlignment;
import pixelitor.gui.utils.MlpAlignmentSelector;

import java.awt.Color;
import java.awt.Font;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

class TextDialogPreferencesTest {
    private Preferences prefs;

    @BeforeAll
    static void beforeAll() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEach() {
        prefs = Preferences.userRoot().node("pixelitor_text_dialog_test_" + UUID.randomUUID());
    }

    @AfterEach
    void afterEach() throws BackingStoreException {
        prefs.removeNode();
        Preferences.userRoot().flush();
    }

    @Test
    void startsWithDefaultFormattingAndEmptyHistory() {
        TextSettings settings = TextDialogPreferences.loadSettings(prefs);

        assertThat(settings.getText()).isEqualTo(TextSettings.DEFAULT_TEXT);
        assertThat(settings.getColor()).isEqualTo(Color.WHITE);
        assertThat(settings.getAlignment()).isEqualTo(BoxAlignment.CENTER_CENTER);
        assertThat(settings.getScaleX()).isEqualTo(1.0);
        assertThat(TextDialogPreferences.loadHistory(prefs)).isEmpty();
    }

    @Test
    void remembersAllFormattingWithoutReusingTextOrGuiCallbacks() throws BackingStoreException {
        FontInfo fontInfo = new FontInfo(new Font(Font.SANS_SERIF, Font.BOLD | Font.ITALIC, 73));
        fontInfo.updateAdvancedProperties(true, true, true, true, 12);
        AreaEffects effects = new AreaEffects();
        effects.setGlow(new GlowPathEffect(0.5f));
        effects.setDropShadow(new ShadowPathEffect(0.75f));
        for (var effect : effects.getEnabledEffects()) {
            ((AbstractAreaEffect) effect).setAutoBrushSteps();
        }
        TextSettings original = new TextSettings("First line\nSecond line", fontInfo.createFont(),
            new Color(20, 40, 60, 137), effects,
            BoxAlignment.TOP_RIGHT.getHorizontal(), BoxAlignment.TOP_RIGHT.getVertical(),
            MlpAlignmentSelector.RIGHT, true, 0.25, 1.15, 1.2, 0.8, 0.1, -0.2, _ -> {});

        TextDialogPreferences.remember(original, prefs);
        prefs.flush();
        Preferences reloadedPrefs = Preferences.userRoot().node(prefs.absolutePath());
        reloadedPrefs.sync();
        TextSettings restored = TextDialogPreferences.loadSettings(reloadedPrefs);

        assertThat(restored.getText()).isEqualTo(TextSettings.DEFAULT_TEXT);
        assertThat(restored.getFont()).isEqualTo(original.getFont());
        assertThat(restored.getColor()).isEqualTo(original.getColor());
        assertThat(restored.getEffects()).isEqualTo(effects).isNotSameAs(effects);
        assertThat(restored.getAlignment()).isEqualTo(BoxAlignment.TOP_RIGHT);
        assertThat(restored.getMLPAlignment()).isEqualTo(MlpAlignmentSelector.RIGHT);
        assertThat(restored.hasWatermark()).isTrue();
        assertThat(restored.getRotation()).isEqualTo(0.25);
        assertThat(restored.getRelLineHeight()).isEqualTo(1.15);
        assertThat(restored.getScaleX()).isEqualTo(1.2);
        assertThat(restored.getScaleY()).isEqualTo(0.8);
        assertThat(restored.getShearX()).isEqualTo(0.1);
        assertThat(restored.getShearY()).isEqualTo(-0.2);
        assertThat(restored.getGuiUpdateCallback()).isNull();
        assertThat(TextDialogPreferences.loadHistory(reloadedPrefs)).containsExactly(original.getText());
    }

    @Test
    void fallsBackToCanvasAlignmentWhenNoPathIsAvailable() {
        TextSettings settings = new TextSettings().withAlignment(BoxAlignment.PATH);
        TextDialogPreferences.remember(settings, prefs);

        assertThat(TextDialogPreferences.loadSettings(prefs).getAlignment())
            .isEqualTo(BoxAlignment.CENTER_CENTER);
    }

    @Test
    void keepsTwentyRecentDistinctTextsAndMovesReusedTextToFront() {
        TextSettings settings = new TextSettings();
        for (int i = 0; i < 25; i++) {
            TextDialogPreferences.remember(settings.withText("Text " + i), prefs);
        }
        TextDialogPreferences.remember(settings.withText("Text 10"), prefs);

        assertThat(TextDialogPreferences.loadHistory(prefs))
            .hasSize(20)
            .startsWith("Text 10", "Text 24", "Text 23")
            .endsWith("Text 5")
            .doesNotContain("Text 4", "Text 0")
            .doesNotHaveDuplicates();
    }

    @Test
    void preservesFullLongTextIncludingWhitespaceAndEmoji() {
        String text = " " + "x".repeat(Preferences.MAX_VALUE_LENGTH - 2) + "\uD83D\uDE80\n"
            + "second line = value\t".repeat(1000) + " ";
        TextSettings settings = new TextSettings();
        TextDialogPreferences.remember(settings.withText(text), prefs);
        TextDialogPreferences.remember(settings.withText("short"), prefs);

        assertThat(TextDialogPreferences.loadHistory(prefs)).containsExactly("short", text);
        TextDialogPreferences.remember(settings.withText(text), prefs);
        assertThat(TextDialogPreferences.loadHistory(prefs)).containsExactly(text, "short");
    }

    @Test
    void ignoresBlankTextAndReplacesDisabledEffects() {
        AreaEffects effects = new AreaEffects();
        effects.setDropShadow(new ShadowPathEffect());
        TextSettings first = new TextSettings("remember me", new Font(Font.SANS_SERIF, Font.PLAIN, 30),
            Color.WHITE, effects, BoxAlignment.CENTER_CENTER.getHorizontal(),
            BoxAlignment.CENTER_CENTER.getVertical(), MlpAlignmentSelector.LEFT,
            false, 0, 1, 1, 1, 0, 0, null);
        TextDialogPreferences.remember(first, prefs);
        TextDialogPreferences.remember(new TextSettings().withText(" \n\t "), prefs);

        assertThat(TextDialogPreferences.loadHistory(prefs)).containsExactly("remember me");
        assertThat(TextDialogPreferences.loadSettings(prefs).getEffects().getEnabledEffects()).isEmpty();
    }
}
