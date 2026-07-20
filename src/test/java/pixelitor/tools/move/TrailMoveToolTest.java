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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.tools.move;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pixelitor.Composition;
import pixelitor.TestHelper;
import pixelitor.gui.View;
import pixelitor.history.History;
import pixelitor.layers.ImageLayer;
import pixelitor.tools.Tools;
import pixelitor.tools.util.ArrowKey;
import pixelitor.tools.util.PMouseEvent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class TrailMoveToolTest {
    private static final int BACKGROUND = new Color(40, 50, 60).getRGB();
    private static final int WIDGET = new Color(220, 30, 40).getRGB();

    private Composition comp;
    private ImageLayer layer;
    private TrailMoveTool tool;
    private BufferedImage original;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        comp = TestHelper.createEmptyComp("TrailMoveToolTest");
        original = createScreenshot(2);
        layer = TestHelper.createImageLayer(comp, original, "screenshot");
        comp.addLayerWithoutUI(layer);
        TestHelper.setSelection(comp, new Rectangle(1, 1, 5, 4));
        tool = new TrailMoveTool();
        Tools.setActiveTool(tool);
        Tools.MouseDispatcher.resetMouseState();
        History.clear();
    }

    @AfterEach
    void afterEachTest() {
        Tools.MouseDispatcher.resetMouseState();
    }

    @Test
    void arrowKeyMovesSelectedPixelsAndSelectionTogether() {
        assertThat(tool.arrowKeyPressed(ArrowKey.RIGHT)).isTrue();

        assertImageEquals(createScreenshot(3), layer.getImage());
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(2, 1, 5, 4));
        assertThat(layer.getTx()).isZero();
        assertThat(layer.getTy()).isZero();
        History.assertNumEditsIs(1);

        History.undo(TrailMoveTool.SELECTED_PIXELS_EDIT_NAME);
        assertImageEquals(original, layer.getImage());
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(1, 1, 5, 4));

        History.redo(TrailMoveTool.SELECTED_PIXELS_EDIT_NAME);
        assertImageEquals(createScreenshot(3), layer.getImage());
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(2, 1, 5, 4));
    }

    @ParameterizedTest
    @EnumSource(TrailMoveTool.Mode.class)
    void dragMovesSelectedPixelsAndSelectionTogether(TrailMoveTool.Mode mode) {
        tool = new TrailMoveTool(mode);
        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0));
        tool.mouseDragged(mouseEvent(MouseEvent.MOUSE_DRAGGED, 1, 0));
        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 1, 0));

        assertImageEquals(createScreenshot(3), layer.getImage());
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(2, 1, 5, 4));
        History.assertNumEditsIs(1);

        History.undo(TrailMoveTool.SELECTED_PIXELS_EDIT_NAME);
        assertImageEquals(original, layer.getImage());
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(1, 1, 5, 4));
    }

    @Test
    void liveTransformClickDoesNotCreateHistory() {
        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0));
        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 0, 0));

        assertImageEquals(original, layer.getImage());
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(1, 1, 5, 4));
        History.assertNumEditsIs(0);
    }

    @Test
    void controlTemporarilyActivatesRectangleSelectionWhileMouseIsUp() {
        tool.controlPressed();

        assertThat(tool.isTemporaryRectangleSelection()).isTrue();
        assertThat(Tools.getActive()).isSameAs(tool);

        tool.controlReleased();

        assertThat(tool.isTemporaryRectangleSelection()).isFalse();
        assertThat(tool.isRestorePending()).isFalse();
        assertThat(Tools.getActive()).isSameAs(tool);
    }

    @Test
    void controlDoesNotActivateRectangleSelectionWhileMouseIsDown() {
        View view = comp.getView();
        Tools.MouseDispatcher.mousePressed(
            rawMouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0), view);

        tool.controlPressed();

        assertThat(tool.isTemporaryRectangleSelection()).isFalse();

        Tools.MouseDispatcher.mouseReleased(
            rawMouseEvent(MouseEvent.MOUSE_RELEASED, 0, 0, 0), view);
        assertThat(tool.isTemporaryRectangleSelection()).isFalse();
    }

    @Test
    void controlReleasedDuringSelectionRestoresTrailMoveAfterMouseRelease() {
        comp.deselect(false);
        View view = comp.getView();
        tool.controlPressed();

        Tools.MouseDispatcher.mousePressed(
            rawMouseEvent(MouseEvent.MOUSE_PRESSED, 2, 2, InputEvent.CTRL_DOWN_MASK), view);
        Tools.MouseDispatcher.mouseDragged(
            rawMouseEvent(MouseEvent.MOUSE_DRAGGED, 6, 5, InputEvent.CTRL_DOWN_MASK), view);

        tool.controlReleased();

        assertThat(tool.isTemporaryRectangleSelection()).isTrue();
        assertThat(tool.isRestorePending()).isTrue();
        assertThat(comp.hasDraftSelection()).isTrue();

        Tools.MouseDispatcher.mouseReleased(
            rawMouseEvent(MouseEvent.MOUSE_RELEASED, 6, 5, 0), view);

        assertThat(tool.isTemporaryRectangleSelection()).isFalse();
        assertThat(tool.isRestorePending()).isFalse();
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(2, 2, 4, 3));
        assertThat(Tools.getActive()).isSameAs(tool);
    }

    @Test
    void mouseReleaseWhileControlIsHeldKeepsRectangleSelectionTemporary() {
        comp.deselect(false);
        View view = comp.getView();
        tool.controlPressed();

        Tools.MouseDispatcher.mousePressed(
            rawMouseEvent(MouseEvent.MOUSE_PRESSED, 2, 2, InputEvent.CTRL_DOWN_MASK), view);
        Tools.MouseDispatcher.mouseDragged(
            rawMouseEvent(MouseEvent.MOUSE_DRAGGED, 6, 5, InputEvent.CTRL_DOWN_MASK), view);
        Tools.MouseDispatcher.mouseReleased(
            rawMouseEvent(MouseEvent.MOUSE_RELEASED, 6, 5, InputEvent.CTRL_DOWN_MASK), view);

        assertThat(tool.isTemporaryRectangleSelection()).isTrue();
        assertThat(tool.isRestorePending()).isFalse();
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(2, 2, 4, 3));

        tool.controlReleased();
        assertThat(tool.isTemporaryRectangleSelection()).isFalse();
    }

    private PMouseEvent mouseEvent(int id, int x, int y) {
        View view = comp.getView();
        return new PMouseEvent(rawMouseEvent(id, x, y, 0), view);
    }

    private MouseEvent rawMouseEvent(int id, int x, int y, int modifiers) {
        return new MouseEvent(comp.getView(), id, 0, modifiers,
            x, y, 1, false, MouseEvent.BUTTON1);
    }

    private static BufferedImage createScreenshot(int widgetX) {
        BufferedImage image = new BufferedImage(
            TestHelper.TEST_WIDTH, TestHelper.TEST_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(BACKGROUND, true));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(new Color(WIDGET, true));
            g.fillRect(widgetX, 2, 2, 2);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void assertImageEquals(BufferedImage expected, BufferedImage actual) {
        int width = expected.getWidth();
        int height = expected.getHeight();
        assertThat(actual.getWidth()).isEqualTo(width);
        assertThat(actual.getHeight()).isEqualTo(height);
        assertThat(actual.getRGB(0, 0, width, height, null, 0, width))
            .containsExactly(expected.getRGB(0, 0, width, height, null, 0, width));
    }
}
