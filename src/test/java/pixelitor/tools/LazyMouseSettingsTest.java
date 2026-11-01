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

package pixelitor.tools;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pixelitor.TestHelper;
import pixelitor.tools.gui.ToolSettingsPanel;
import pixelitor.utils.Texts;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LazyMouseSettingsTest {
    static Stream<Arguments> tools() {
        TestHelper.setUnitTestingMode();
        return Stream.<Supplier<AbstractBrushTool>>of(
                BrushTool::new, EraserTool::new, CloneTool::new, SmudgeTool::new)
            .flatMap(factory -> Stream.of(false, true)
                .map(enabled -> Arguments.of(factory.get(), enabled)));
    }

    @ParameterizedTest(name = "{0}, initially enabled = {1}")
    @MethodSource("tools")
    void opensAndTogglesSmoothing(AbstractBrushTool tool, boolean initiallyEnabled) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tool.setSettingsPanel(new ToolSettingsPanel());
            tool.initSettingsPanel(Texts.getResources());
            var preset = tool.createUserPreset("test");
            preset.putBoolean("Lazy.Enabled", initiallyEnabled);
            tool.loadUserPreset(preset);

            JPanel panel = tool.createLazyMouseConfigPanel();
            JCheckBox enabled = findComponent(panel, JCheckBox.class);
            JSlider distance = findComponent(panel, JSlider.class);

            assertThat(enabled.isSelected()).isEqualTo(initiallyEnabled);
            assertThat(distance.isEnabled()).isEqualTo(initiallyEnabled);
            assertThat(tool.isLazyMouseActive()).isEqualTo(initiallyEnabled);

            enabled.doClick(0);
            assertThat(tool.isLazyMouseActive()).isEqualTo(!initiallyEnabled);
            assertThat(distance.isEnabled()).isEqualTo(!initiallyEnabled);

            enabled.doClick(0);
            assertThat(tool.isLazyMouseActive()).isEqualTo(initiallyEnabled);
            assertThat(distance.isEnabled()).isEqualTo(initiallyEnabled);
        });
    }

    private static <T extends Component> T findComponent(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T found = findComponent(container, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
