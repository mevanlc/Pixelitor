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

package pixelitor.tools.gui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pixelitor.TestHelper;
import pixelitor.tools.Tool;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolButtonTest {
    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @Test
    void rebuildsPresetMenuWheneverItOpens() {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("Test Tool");
        when(tool.getHotkey()).thenReturn('T');
        when(tool.createIconPainter()).thenReturn(g -> {
        });
        when(tool.shouldHaveUserPresetsMenu()).thenReturn(true);
        when(tool.getPresetDirName()).thenReturn("ToolButtonTest-" + UUID.randomUUID());

        ToolButton button = new ToolButton(tool);
        JPopupMenu menu = button.getComponentPopupMenu();

        fireWillBecomeVisible(menu);
        JMenuItem staleItem = new JMenuItem("Stale Preset");
        menu.add(staleItem);

        fireWillBecomeVisible(menu);

        assertThat(Arrays.asList(menu.getComponents())).doesNotContain(staleItem);
        assertThat(countMenuItemsNamed(menu, "Save Preset...")).isEqualTo(1);
    }

    private static void fireWillBecomeVisible(JPopupMenu menu) {
        PopupMenuEvent event = new PopupMenuEvent(menu);
        for (var listener : menu.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeVisible(event);
        }
    }

    private static long countMenuItemsNamed(JPopupMenu menu, String name) {
        return Arrays.stream(menu.getComponents())
            .filter(JMenuItem.class::isInstance)
            .map(JMenuItem.class::cast)
            .map(JMenuItem::getText)
            .filter(name::equals)
            .count();
    }
}
