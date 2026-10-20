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

package pixelitor.layers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pixelitor.AppMode;
import pixelitor.Composition;
import pixelitor.gui.utils.Theme;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LayerGUITest {
    @ParameterizedTest
    @EnumSource(Theme.class)
    void visibilityTogglesDespitePointerMovement(Theme theme) throws Exception {
        withVisibilityControl(theme, Layer.class, (layer, eye) -> {
            pressAndRelease(eye, MouseEvent.BUTTON1, 0, true);

            assertThat(eye.isSelected()).isFalse();
            verify(layer).setVisible(false, true, true);
            verify(layer, never()).isolate();
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void eachClickTogglesVisibilityOnce(Theme theme) throws Exception {
        withVisibilityControl(theme, Layer.class, (layer, eye) -> {
            for (int i = 0; i < 2; i++) {
                pressAndRelease(eye, MouseEvent.BUTTON1, 0, false);
                sendMouseEvent(eye, MouseEvent.MOUSE_CLICKED, MouseEvent.BUTTON1, 0, 12, 12);
            }

            assertThat(eye.isSelected()).isTrue();
            verify(layer).setVisible(false, true, true);
            verify(layer).setVisible(true, true, true);
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void altClickIsolatesAndKeepsTheEyeInSync(Theme theme) throws Exception {
        withVisibilityControl(theme, Layer.class, (layer, eye) -> {
            // Isolating an already visible layer can leave its visibility unchanged.
            for (int i = 0; i < 2; i++) {
                pressAndRelease(eye, MouseEvent.BUTTON1, InputEvent.ALT_DOWN_MASK, true);
                assertThat(eye.isSelected()).isTrue();
            }

            verify(layer, times(2)).isolate();
            verify(layer, never()).setVisible(anyBoolean(), anyBoolean(), anyBoolean());
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void altClickTogglesSmartFilterVisibility(Theme theme) throws Exception {
        withVisibilityControl(theme, SmartFilter.class, (layer, eye) -> {
            pressAndRelease(eye, MouseEvent.BUTTON1, InputEvent.ALT_DOWN_MASK, true);

            assertThat(eye.isSelected()).isFalse();
            verify(layer).setVisible(false, true, true);
            verify(layer, never()).isolate();
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void ctrlClickTogglesAllLayersAndKeepsTheEyeInSync(Theme theme) throws Exception {
        withVisibilityControl(theme, Layer.class, (layer, eye) -> {
            Composition comp = mock(Composition.class);
            when(layer.getComp()).thenReturn(comp);

            // Ctrl takes priority over Alt. The clicked layer can remain visible
            // when showing all layers, so its automatic checkbox toggle must be reset.
            pressAndRelease(eye, MouseEvent.BUTTON1,
                InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK, true);

            assertThat(eye.isSelected()).isTrue();
            verify(comp).toggleAllLayersVisibility();
            verify(layer, never()).setVisible(anyBoolean(), anyBoolean(), anyBoolean());
            verify(layer, never()).isolate();
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void ctrlClickOnSmartFilterTogglesAllLayers(Theme theme) throws Exception {
        withVisibilityControl(theme, SmartFilter.class, (layer, eye) -> {
            Composition comp = mock(Composition.class);
            when(layer.getComp()).thenReturn(comp);

            pressAndRelease(eye, MouseEvent.BUTTON1, InputEvent.CTRL_DOWN_MASK, true);

            assertThat(eye.isSelected()).isTrue();
            verify(comp).toggleAllLayersVisibility();
            verify(layer, never()).setVisible(anyBoolean(), anyBoolean(), anyBoolean());
            verify(layer, never()).isolate();
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void secondaryClicksDoNotChangeVisibility(Theme theme) throws Exception {
        withVisibilityControl(theme, Layer.class, (layer, eye) -> {
            for (int button : new int[]{MouseEvent.BUTTON2, MouseEvent.BUTTON3}) {
                pressAndRelease(eye, button, 0, false);
                sendMouseEvent(eye, MouseEvent.MOUSE_CLICKED, button, 0, 12, 12);
            }

            assertThat(eye.isSelected()).isTrue();
            verify(layer, never()).setVisible(anyBoolean(), anyBoolean(), anyBoolean());
            verify(layer, never()).isolate();
        });
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    void releasingOutsideCancelsTheClick(Theme theme) throws Exception {
        withVisibilityControl(theme, Layer.class, (layer, eye) -> {
            sendMouseEvent(eye, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1,
                InputEvent.BUTTON1_DOWN_MASK, 12, 12);
            sendMouseEvent(eye, MouseEvent.MOUSE_EXITED, MouseEvent.NOBUTTON,
                InputEvent.BUTTON1_DOWN_MASK, 30, 12);
            sendMouseEvent(eye, MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, 0, 30, 12);

            assertThat(eye.isSelected()).isTrue();
            verify(layer, never()).setVisible(anyBoolean(), anyBoolean(), anyBoolean());
            verify(layer, never()).isolate();
        });
    }

    private static void withVisibilityControl(Theme theme, Class<? extends Layer> layerType,
                                             BiConsumer<Layer, JCheckBox> test) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel original = UIManager.getLookAndFeel();
            try {
                Layer layer = mock(layerType);
                AtomicBoolean visible = new AtomicBoolean(true);
                when(layer.isVisible()).thenAnswer(_ -> visible.get());
                when(layer.getName()).thenReturn("Test Layer");
                LayerGUI gui;
                // Exercise the real Swing control without changing the suite's app mode.
                try (var appMode = mockStatic(AppMode.class)) {
                    gui = new LayerGUI(layer);
                }
                // Replace the delegates on an existing control, just like a theme change.
                UIManager.setLookAndFeel(theme.getLAFClassName());
                SwingUtilities.updateComponentTreeUI(gui);
                gui.setSize(240, gui.getPreferredHeight());
                gui.doLayout();
                JCheckBox eye = Arrays.stream(gui.getComponents())
                    .filter(JCheckBox.class::isInstance)
                    .map(JCheckBox.class::cast)
                    .findFirst().orElseThrow();
                doAnswer(invocation -> {
                    boolean newVisibility = invocation.getArgument(0);
                    visible.set(newVisibility);
                    eye.setSelected(newVisibility);
                    return null;
                }).when(layer).setVisible(anyBoolean(), anyBoolean(), anyBoolean());
                test.accept(layer, eye);
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
                throw new RuntimeException(e);
            } finally {
                try {
                    UIManager.setLookAndFeel(original);
                } catch (UnsupportedLookAndFeelException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private static void pressAndRelease(JCheckBox eye, int button, int modifiers, boolean move) {
        int downModifiers = modifiers | InputEvent.getMaskForButton(button);
        sendMouseEvent(eye, MouseEvent.MOUSE_PRESSED, button, downModifiers, 12, 12);
        int releaseX = move ? 14 : 12;
        if (move) {
            // AWT can omit MOUSE_CLICKED after a drag, even when released inside the eye.
            sendMouseEvent(eye, MouseEvent.MOUSE_DRAGGED, MouseEvent.NOBUTTON, downModifiers, releaseX, 12);
        }
        sendMouseEvent(eye, MouseEvent.MOUSE_RELEASED, button, modifiers, releaseX, 12);
    }

    private static void sendMouseEvent(JCheckBox eye, int id, int button, int modifiers, int x, int y) {
        eye.dispatchEvent(new MouseEvent(eye, id, System.currentTimeMillis(), modifiers,
            x, y, 1, false, button));
    }
}
