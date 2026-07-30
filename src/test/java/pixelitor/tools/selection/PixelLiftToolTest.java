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

package pixelitor.tools.selection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.Composition;
import pixelitor.TestHelper;
import pixelitor.filters.gui.UserPreset;
import pixelitor.history.History;
import pixelitor.layers.ImageLayer;
import pixelitor.layers.TextLayer;
import pixelitor.selection.ShapeCombinator;
import pixelitor.tools.Tools;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.Messages;
import pixelitor.utils.TestMessageHandler;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static pixelitor.TestHelper.assertHistoryEditsAre;
import static pixelitor.utils.TestMessageHandler.MessageType.INFO;

class PixelLiftToolTest {
    private static final int BACKGROUND = new Color(40, 50, 60).getRGB();
    private static final int WIDGET = new Color(220, 30, 40).getRGB();
    private static final int TOP_BACKGROUND = new Color(30, 80, 120).getRGB();
    private static final int TOP_WIDGET = new Color(240, 180, 20).getRGB();

    private static final Rectangle SELECTION = new Rectangle(4, 3, 4, 3);

    // a point inside SELECTION, and a point clearly outside it
    private static final Point INSIDE = new Point(5, 4);
    private static final Point OUTSIDE = new Point(14, 2);

    private Composition comp;
    private ImageLayer sourceLayer;
    private PixelLiftTool tool;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        comp = TestHelper.createEmptyComp("PixelLiftToolTest");
        sourceLayer = addLayer(createScreenshot(BACKGROUND, WIDGET), "source");
        TestHelper.setSelection(comp, SELECTION);

        tool = Tools.PIXEL_LIFT;
        Tools.setActiveTool(tool);
        configureTool(false, false);
        History.clear();
    }

    @AfterEach
    void afterEachTest() {
        configureTool(false, false);
        History.clear();
        Tools.setActiveTool(Tools.BRUSH);
    }

    // -------------------------------------------------------------------
    // ordinary selection behavior
    // -------------------------------------------------------------------

    @Test
    void dragOutsideSelectionCreatesSelection() {
        drag(OUTSIDE, new Point(18, 6));

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(14, 2, 4, 4));
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void dragWithoutSelectionCreatesSelection() {
        comp.deselect(false);
        History.clear();

        drag(INSIDE, new Point(9, 7));

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(comp.hasSelection()).isTrue();
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void shiftDragInsideSelectionAddsToSelection() {
        dragWithModifiers(INSIDE, new Point(14, 8), InputEvent.SHIFT_DOWN_MASK);

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
        // the ADD combinator grew the selection beyond its original bounds
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(4, 3, 10, 5));
    }

    @Test
    void altDragInsideSelectionDoesNotLift() {
        dragWithModifiers(INSIDE, new Point(9, 7), InputEvent.ALT_DOWN_MASK);

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void rightDragInsideSelectionDoesNotLift() {
        PMouseEvent pressed = new PMouseEvent(new MouseEvent(comp.getView(),
            MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON3_DOWN_MASK,
            INSIDE.x, INSIDE.y, 1, false, MouseEvent.BUTTON3), comp.getView());
        tool.mousePressed(pressed);

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
    }

    // -------------------------------------------------------------------
    // lifting
    // -------------------------------------------------------------------

    @Test
    void dragInsideSelectionLiftsAndAutoMerges() {
        configureTool(true, false);

        drag(INSIDE, new Point(7, 5));

        // the extracted layer was merged back down
        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(comp.getActiveLayer()).isSameAs(sourceLayer);

        // the pixels moved by (+2, +1)
        assertThat(sourceLayer.getPixelAtPoint(new Point(6, 4))).isEqualTo(WIDGET);
        // and the vacated area was filled with the surrounding color
        assertThat(sourceLayer.getPixelAtPoint(new Point(4, 3))).isEqualTo(BACKGROUND);

        // the selection travelled with the pixels
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(6, 4, 4, 3));

        assertHistoryEditsAre(PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void dragInsideSelectionWithoutAutoMergeKeepsTheLayer() {
        configureTool(false, false);

        drag(INSIDE, new Point(7, 5));

        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertThat(comp.getActiveLayer()).isNotSameAs(sourceLayer);
        assertThat(comp.hasSelection()).isTrue();

        assertHistoryEditsAre(PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void oneLiftIsOneUndoableEdit() {
        configureTool(true, false);
        BufferedImage before = ImageUtils.copyImage(sourceLayer.getImage());

        drag(INSIDE, new Point(7, 5));
        assertHistoryEditsAre(PixelLiftTool.LIFT_EDIT_NAME);

        History.undo(PixelLiftTool.LIFT_EDIT_NAME);

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(comp.getActiveLayer()).isSameAs(sourceLayer);
        assertImagesAreEqual(sourceLayer.getImage(), before);
        assertThat(comp.getSelection().getShapeBounds()).isEqualTo(SELECTION);

        History.redo(PixelLiftTool.LIFT_EDIT_NAME);

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(sourceLayer.getPixelAtPoint(new Point(6, 4))).isEqualTo(WIDGET);
    }

    @Test
    void repeatedLiftsProduceSeparateEdits() {
        configureTool(true, false);

        drag(INSIDE, new Point(7, 5));
        // the selection is now at (6, 4, 4, 3), so this starts inside it again
        drag(new Point(7, 5), new Point(9, 6));

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertHistoryEditsAre(
            PixelLiftTool.LIFT_EDIT_NAME, PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void clickInsideSelectionDoesNothing() {
        configureTool(true, false);

        // press and release without any drag event
        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, INSIDE, 0));
        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, INSIDE, 0));

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(History.getNumEdits()).isZero();
        // the selection survives, so that it stays reusable
        assertThat(comp.hasSelection()).isTrue();
        assertThat(comp.getSelection().getShapeBounds()).isEqualTo(SELECTION);
    }

    // -------------------------------------------------------------------
    // when lifting isn't possible
    // -------------------------------------------------------------------

    @Test
    void noLiftWhenTheActiveLayerIsNotAnImageLayer() {
        TextLayer textLayer = TestHelper.createTextLayer(comp, "text");
        comp.addLayerWithoutUI(textLayer);
        comp.setActiveLayer(textLayer);
        History.clear();

        var messageHandler = new TestMessageHandler();
        Messages.setHandler(messageHandler);

        drag(INSIDE, new Point(9, 7));

        // no fill cut happened, and no error dialog was shown
        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
        assertThat(messageHandler.getMessagesByType(INFO)).isEmpty();
        // it degraded to an ordinary selection drag
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(5, 4, 4, 3));
    }

    @Test
    void noLiftWhenTheCombinatorIsNotReplace() {
        configureTool(true, false);
        setCombinator(ShapeCombinator.ADD);

        drag(INSIDE, new Point(11, 8));

        // the combo box asked for selection editing, so the drag added
        // to the selection instead of lifting the pixels
        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(4, 3, 7, 5));
    }

    @Test
    void noLiftWhenTheSelectionIsHidden() {
        comp.getSelection().setHidden(true);
        History.clear();

        drag(INSIDE, new Point(9, 7));

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void noLiftWhenTheDragStartsOutsideANonRectangularSelection() {
        // subtract the lower right quarter of SELECTION, so that (6, 4)
        // is inside the bounding box, but outside the shape
        setCombinator(ShapeCombinator.SUBTRACT);
        drag(new Point(6, 4), new Point(10, 8));
        setCombinator(ShapeCombinator.REPLACE);
        History.clear();

        assertThat(comp.getSelection().getShapeBounds()).isEqualTo(SELECTION);

        drag(new Point(6, 4), new Point(9, 7));

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
    }

    // -------------------------------------------------------------------
    // auto select layer
    // -------------------------------------------------------------------

    @Test
    void autoSelectResolvesTheLayerUnderThePoint() {
        ImageLayer topLayer = addLayer(
            createScreenshot(TOP_BACKGROUND, TOP_WIDGET), "top");
        comp.setActiveLayer(sourceLayer);
        configureTool(false, true);
        History.clear();

        drag(INSIDE, new Point(7, 5));

        // the cut came from the top layer, not from the active one
        assertThat(comp.getNumLayers()).isEqualTo(3);
        assertThat(topLayer.getPixelAtPoint(INSIDE)).isEqualTo(TOP_BACKGROUND);
        assertThat(sourceLayer.getPixelAtPoint(INSIDE)).isEqualTo(WIDGET);
        assertHistoryEditsAre(PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void noLiftWhenAutoSelectFindsNoLayer() {
        // a fully transparent layer above, and auto select on:
        // findLayerAtPoint() returns null for every point
        Composition emptyComp = TestHelper.createEmptyComp("emptyComp");
        ImageLayer transparent = TestHelper.createEmptyImageLayer(emptyComp, "transparent");
        emptyComp.addLayerWithoutUI(transparent);
        TestHelper.setSelection(emptyComp, SELECTION);
        Tools.setActiveTool(tool);
        configureTool(false, true);
        History.clear();

        dragOn(emptyComp, INSIDE, new Point(9, 7));

        assertThat(emptyComp.getNumLayers()).isEqualTo(1);
        assertThat(History.getEditNames()).doesNotContain(PixelLiftTool.LIFT_EDIT_NAME);
    }

    // -------------------------------------------------------------------
    // lifecycle
    // -------------------------------------------------------------------

    @Test
    void escDuringLiftUndoesEverything() {
        configureTool(false, false);
        BufferedImage before = ImageUtils.copyImage(sourceLayer.getImage());

        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, INSIDE, 0));
        tool.mouseDragged(mouseEvent(MouseEvent.MOUSE_DRAGGED, new Point(7, 5), 0));
        assertThat(comp.getNumLayers()).isEqualTo(2);

        tool.escPressed();

        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(History.getNumEdits()).isZero();
        assertThat(History.isInTransaction()).isFalse();
        assertImagesAreEqual(sourceLayer.getImage(), before);
        // Esc canceled the lift, not the selection
        assertThat(comp.hasSelection()).isTrue();

        // the drag was canceled, so the release is a no-op
        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, new Point(7, 5), 0));
        assertThat(History.getNumEdits()).isZero();
    }

    @Test
    void escWithoutLiftDeselects() {
        tool.escPressed();

        assertThat(comp.hasSelection()).isFalse();
    }

    @Test
    void toolDeactivationDuringLiftCommits() {
        configureTool(true, false);

        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, INSIDE, 0));
        tool.mouseDragged(mouseEvent(MouseEvent.MOUSE_DRAGGED, new Point(7, 5), 0));

        // switching tools without a synthesized mouse release
        // must not leave the transaction open
        tool.toolDeactivated(comp.getView());

        assertThat(History.isInTransaction()).isFalse();
        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertHistoryEditsAre(PixelLiftTool.LIFT_EDIT_NAME);
    }

    @Test
    void undoIsIgnoredWhileLifting() {
        configureTool(false, false);
        // an edit that could be undone if the guard didn't work
        drag(OUTSIDE, new Point(18, 6));
        int numEditsBefore = History.getNumEdits();

        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, new Point(15, 3), 0));
        tool.mouseDragged(mouseEvent(MouseEvent.MOUSE_DRAGGED, new Point(17, 4), 0));
        assertThat(History.isInTransaction()).isTrue();

        History.undo();

        assertThat(History.getNumEdits()).isEqualTo(numEditsBefore);

        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, new Point(17, 4), 0));
        assertThat(History.isInTransaction()).isFalse();
    }

    // -------------------------------------------------------------------
    // presets
    // -------------------------------------------------------------------

    @Test
    void presetRoundTrip() {
        configureTool(true, true);

        UserPreset saved = tool.createUserPreset("test");
        configureTool(false, false);
        tool.loadUserPreset(saved);

        assertThat(tool.isAutoMerge()).isTrue();
        assertThat(tool.isAutoSelecting()).isTrue();
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private void configureTool(boolean autoMerge, boolean autoSelect) {
        var preset = new UserPreset("pixel lift settings");
        tool.saveStateTo(preset);
        preset.put("New Selection", ShapeCombinator.REPLACE.name());
        preset.putBoolean("Auto Merge", autoMerge);
        preset.putBoolean("Auto Select", autoSelect);
        tool.loadUserPreset(preset);
    }

    private void setCombinator(ShapeCombinator combinator) {
        var preset = new UserPreset("combinator");
        tool.saveStateTo(preset);
        preset.put("New Selection", combinator.name());
        tool.loadUserPreset(preset);
    }

    private void drag(Point from, Point to) {
        dragWithModifiers(from, to, 0);
    }

    private void dragWithModifiers(Point from, Point to, int modifiers) {
        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, from, modifiers));
        tool.mouseDragged(mouseEvent(MouseEvent.MOUSE_DRAGGED, to, modifiers));
        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, to, modifiers));
    }

    private void dragOn(Composition target, Point from, Point to) {
        tool.mousePressed(mouseEvent(target, MouseEvent.MOUSE_PRESSED, from));
        tool.mouseDragged(mouseEvent(target, MouseEvent.MOUSE_DRAGGED, to));
        tool.mouseReleased(mouseEvent(target, MouseEvent.MOUSE_RELEASED, to));
    }

    private PMouseEvent mouseEvent(int id, Point p, int modifiers) {
        var event = new MouseEvent(comp.getView(), id, 0, modifiers,
            p.x, p.y, 1, false, MouseEvent.BUTTON1);
        return new PMouseEvent(event, comp.getView());
    }

    private static PMouseEvent mouseEvent(Composition target, int id, Point p) {
        var event = new MouseEvent(target.getView(), id, 0, 0,
            p.x, p.y, 1, false, MouseEvent.BUTTON1);
        return new PMouseEvent(event, target.getView());
    }

    private ImageLayer addLayer(BufferedImage image, String name) {
        ImageLayer layer = TestHelper.createImageLayer(comp, image, name);
        comp.addLayerWithoutUI(layer);
        return layer;
    }

    private static BufferedImage createScreenshot(int background, int widget) {
        BufferedImage image = new BufferedImage(
            TestHelper.TEST_WIDTH, TestHelper.TEST_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, background);
            }
        }
        for (int y = SELECTION.y; y < SELECTION.y + SELECTION.height; y++) {
            for (int x = SELECTION.x; x < SELECTION.x + SELECTION.width; x++) {
                image.setRGB(x, y, widget);
            }
        }
        return image;
    }

    private static void assertImagesAreEqual(BufferedImage actual, BufferedImage expected) {
        assertThat(actual.getWidth()).isEqualTo(expected.getWidth());
        assertThat(actual.getHeight()).isEqualTo(expected.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertThat(actual.getRGB(x, y))
                    .as("pixel at (%s, %s)", x, y)
                    .isEqualTo(expected.getRGB(x, y));
            }
        }
    }
}
