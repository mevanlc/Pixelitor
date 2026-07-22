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

import com.bric.util.JVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.Composition;
import pixelitor.TestHelper;
import pixelitor.filters.gui.UserPreset;
import pixelitor.history.History;
import pixelitor.layers.ImageLayer;
import pixelitor.tools.Tools;
import pixelitor.tools.transform.TransformStartSource;
import pixelitor.tools.util.ArrowKey;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Messages;
import pixelitor.utils.TestMessageHandler;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.Rectangle2D;

import static org.assertj.core.api.Assertions.assertThat;
import static pixelitor.TestHelper.assertHistoryEditsAre;
import static pixelitor.utils.TestMessageHandler.MessageType.STATUS;

class MoveToolTest {
    private static final int BACKGROUND = new Color(40, 50, 60).getRGB();
    private static final int WIDGET = new Color(220, 30, 40).getRGB();
    private static final int TOP_BACKGROUND = new Color(30, 80, 120).getRGB();
    private static final int TOP_WIDGET = new Color(240, 180, 20).getRGB();
    private static final Rectangle SELECTION = new Rectangle(4, 3, 4, 3);

    private Composition comp;
    private ImageLayer sourceLayer;
    private MoveTool tool;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        comp = TestHelper.createEmptyComp("MoveToolTest");
        sourceLayer = addLayer(createScreenshot(BACKGROUND, WIDGET), "source");
        TestHelper.setSelection(comp, SELECTION);

        tool = Tools.MOVE;
        Tools.setActiveTool(tool);
        tool.cancelTransform(false);
        configureMove(MoveMode.MOVE_BOTH, false);
        History.clear();
    }

    @AfterEach
    void afterEachTest() {
        tool.cancelTransform(false);
        configureMove(MoveMode.MOVE_BOTH, false);
        History.clear();
    }

    @Test
    void commandClickCreatesAndSelectsLayerViaFillCut() {
        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 5, 4, true));

        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertThat(comp.getActiveLayer()).isNotSameAs(sourceLayer);
        assertRegionColor(sourceLayer.getImage(), SELECTION, BACKGROUND);

        ImageLayer extractedLayer = (ImageLayer) comp.getActiveLayer();
        assertThat(extractedLayer.getPixelAtPoint(new Point(5, 4))).isEqualTo(WIDGET);

        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 5, 4, true));

        assertThat(comp.getActiveLayer()).isSameAs(extractedLayer);
        assertThat(comp.getSelection().getShapeBounds()).isEqualTo(SELECTION);
        assertHistoryEditsAre("Layer via Fill Cut", "Move");
    }

    @Test
    void commandDragMovesTheExtractedLayer() {
        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 5, 4, true));
        ImageLayer extractedLayer = (ImageLayer) comp.getActiveLayer();

        tool.mouseDragged(mouseEvent(MouseEvent.MOUSE_DRAGGED, 7, 5, true));
        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 7, 5, true));

        assertThat(comp.getActiveLayer()).isSameAs(extractedLayer);
        assertThat(extractedLayer.getPixelAtPoint(new Point(4, 3))).isZero();
        assertThat(extractedLayer.getPixelAtPoint(new Point(6, 4))).isEqualTo(WIDGET);
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(6, 4, 4, 3));
        assertHistoryEditsAre("Layer via Fill Cut", "Move");
    }

    @Test
    void autoSelectResolvesTheFillCutSourceFirst() {
        ImageLayer topLayer = addLayer(
            createScreenshot(TOP_BACKGROUND, TOP_WIDGET), "top");
        comp.setActiveLayer(sourceLayer);
        configureMove(MoveMode.MOVE_SELECTION_ONLY, true);
        History.clear();

        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 5, 4, true));

        assertThat(comp.getNumLayers()).isEqualTo(3);
        assertThat(comp.getActiveLayer()).isNotIn(sourceLayer, topLayer);
        assertRegionColor(topLayer.getImage(), SELECTION, TOP_BACKGROUND);
        assertRegionColor(sourceLayer.getImage(), SELECTION, WIDGET);

        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 5, 4, true));
        assertHistoryEditsAre("Layer via Fill Cut", "Move");
    }

    @Test
    void selectionOnlyModeStillMovesTheExtractedLayerForThisGesture() {
        configureMove(MoveMode.MOVE_SELECTION_ONLY, false);

        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 5, 4, true));
        ImageLayer extractedLayer = (ImageLayer) comp.getActiveLayer();
        tool.mouseDragged(mouseEvent(MouseEvent.MOUSE_DRAGGED, 7, 5, true));
        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 7, 5, true));

        assertThat(extractedLayer.getPixelAtPoint(new Point(4, 3))).isZero();
        assertThat(extractedLayer.getPixelAtPoint(new Point(6, 4))).isEqualTo(WIDGET);
        assertThat(comp.getSelection().getShapeBounds())
            .isEqualTo(new Rectangle(6, 4, 4, 3));

        var savedSettings = new UserPreset("saved");
        tool.saveStateTo(savedSettings);
        assertThat(savedSettings.get(MoveMode.PRESET_KEY))
            .isEqualTo(MoveMode.MOVE_SELECTION_ONLY.name());
    }

    @Test
    void commandClickAlsoWorksWhenFreeTransformIsActive() {
        configureMove(MoveMode.MOVE_BOTH, false, true);
        tool.toolActivated(comp.getView());
        assertThat(tool.isFreeTransforming()).isTrue();

        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 5, 4, true));

        assertThat(tool.isFreeTransforming()).isFalse();
        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertThat(comp.getActiveLayer()).isNotSameAs(sourceLayer);
        assertRegionColor(sourceLayer.getImage(), SELECTION, BACKGROUND);

        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 5, 4, true));
        assertHistoryEditsAre("Layer via Fill Cut", "Move");
    }

    @Test
    void commandAddsNoBehaviorWithoutASelection() {
        ImageLayer topLayer = addLayer(
            createScreenshot(TOP_BACKGROUND, TOP_WIDGET), "top");
        comp.setActiveLayer(sourceLayer);
        comp.deselect(false);
        configureMove(MoveMode.MOVE_SELECTION_ONLY, true);
        History.clear();

        tool.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 5, 4, true));
        tool.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 5, 4, true));

        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertThat(comp.getActiveLayer()).isSameAs(sourceLayer);
        assertRegionColor(sourceLayer.getImage(), SELECTION, WIDGET);
        assertRegionColor(topLayer.getImage(), SELECTION, TOP_WIDGET);
        History.assertNumEditsIs(0);
    }

    @Test
    void editCommandUsesTranslatedNonTransparentPixelBounds() {
        BufferedImage padded = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        padded.setRGB(2, 3, 0x01010203); // partially transparent pixels count
        padded.setRGB(5, 6, 0xFF405060);
        ImageLayer layer = addLayer(padded, "padded");
        layer.setTranslation(-5, -4);

        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);

        assertThat(tool.isFreeTransforming()).isTrue();
        assertThat(tool.getTransformBox().getOrigImRect())
            .isEqualTo(new Rectangle(-3, -1, 4, 4));
    }

    @Test
    void moveControlsUseTranslatedLayerBufferBounds() {
        BufferedImage padded = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        padded.setRGB(2, 3, 0xFF010203);
        ImageLayer layer = addLayer(padded, "padded");
        layer.setTranslation(-5, -4);
        configureMove(MoveMode.MOVE_LAYER_ONLY, false, true);

        tool.toolActivated(comp.getView());

        assertThat(tool.isFreeTransforming()).isTrue();
        assertThat(tool.getTransformBox().getOrigImRect())
            .isEqualTo(new Rectangle(-5, -4, 20, 20));
    }

    @Test
    void fullyTransparentLayerCannotStartEditCommandButMoveControlsCanTransformIt() {
        ImageLayer transparent = addLayer(
            new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB), "transparent");
        Tools.setActiveTool(Tools.BRUSH);

        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);

        assertThat(tool.isFreeTransforming()).isFalse();
        assertThat(Tools.getActive()).isSameAs(Tools.BRUSH);

        Tools.setActiveTool(tool);
        configureMove(MoveMode.MOVE_LAYER_ONLY, false, true);
        tool.toolActivated(comp.getView());
        assertThat(tool.isFreeTransforming()).isTrue();
        assertThat(tool.getTransformBox().getOrigImRect())
            .isEqualTo(transparent.getContentBounds(true));
    }

    @Test
    void repeatingEditCommandKeepsTheExistingSession() {
        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);
        var originalBox = tool.getTransformBox();

        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);

        assertThat(tool.getTransformBox()).isSameAs(originalBox);
        History.assertNumEditsIs(0);
    }

    @Test
    void moveControlsUseTheUnionOfLayerAndSelectionBounds() {
        configureMove(MoveMode.MOVE_BOTH, false, true);
        Rectangle componentSelection = comp.getView().imageToComponentSpace(
            comp.getSelection().getShapeBounds2D());
        componentSelection.grow(10, 10);
        Rectangle2D expandedSelection = comp.getView().componentToImageSpace(componentSelection);
        Rectangle2D expected = sourceLayer.getContentBounds(true).createUnion(expandedSelection);

        tool.toolActivated(comp.getView());

        assertThat(tool.getTransformBox().getOrigImRect()).isEqualTo(expected);
    }

    @Test
    void freeTransformStatusMessageUsesPlatformModifierNames() {
        String macMessage = MoveTool.freeTransformStatusMessage(true);
        assertThat(macMessage)
            .contains("<b>Shift</b> non-proportional scale / 15° rotate",
                "<b>Option</b> scale around pivot",
                "<b>Cmd</b>-corner distort",
                "<b>Cmd+Shift</b>-edge skew",
                "<b>Cmd+Option+Shift</b>-corner perspective")
            .doesNotContain("Ctrl", "Alt");

        String otherMessage = MoveTool.freeTransformStatusMessage(false);
        assertThat(otherMessage)
            .contains("<b>Alt</b> scale around pivot",
                "<b>Ctrl</b>-corner distort",
                "<b>Ctrl+Shift</b>-edge skew",
                "<b>Ctrl+Alt+Shift</b>-corner perspective")
            .doesNotContain("Cmd", "Option");
    }

    @Test
    void startingFreeTransformShowsModifierHintsInStatusBar() {
        var messageHandler = new TestMessageHandler();
        Messages.setHandler(messageHandler);

        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);

        assertThat(messageHandler.getMessagesByType(STATUS))
            .singleElement()
            .extracting(TestMessageHandler.CapturedMessage::message)
            .isEqualTo("<html>" + MoveTool.freeTransformStatusMessage(JVM.isMac));
    }

    @Test
    void enterCommitsOneApplyEditAndUndoRestoresTheInteractiveSession() {
        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);
        tool.arrowKeyPressed(ArrowKey.RIGHT);
        ImageLayer otherLayer = addLayer(createScreenshot(TOP_BACKGROUND, TOP_WIDGET), "other");
        comp.setActiveLayer(sourceLayer);

        tool.otherKeyPressed(new KeyEvent(comp.getView(), KeyEvent.KEY_PRESSED,
            0, 0, KeyEvent.VK_ENTER, '\n'));

        assertThat(tool.isFreeTransforming()).isFalse();
        assertHistoryEditsAre("Nudge", "Apply Free Transform");

        comp.setActiveLayer(otherLayer);
        History.undo();

        assertThat(tool.isFreeTransforming()).isTrue();
        assertThat(tool.getTransformTarget()).isSameAs(sourceLayer);
        assertThat(comp.getActiveLayer()).isSameAs(otherLayer);
        assertThat(tool.getTransformBox().getNW().getImX()).isEqualTo(1.0);

        History.redo();
        assertThat(tool.isFreeTransforming()).isFalse();
        assertThat(comp.getActiveLayer()).isSameAs(otherLayer);
    }

    @Test
    void escapeRestoresPristineLayerAndUndoRestoresCanceledGeometry() {
        BufferedImage originalImage = sourceLayer.getImage();
        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);
        tool.arrowKeyPressed(ArrowKey.SHIFT_RIGHT);

        tool.escPressed();

        assertThat(tool.isFreeTransforming()).isFalse();
        assertThat(sourceLayer.getImage()).isSameAs(originalImage);
        assertHistoryEditsAre("Nudge", "Cancel Free Transform");

        History.undo();
        assertThat(tool.isFreeTransforming()).isTrue();
        assertThat(tool.getTransformBox().getNW().getImX()).isEqualTo(10.0);
    }

    @Test
    void explicitToolChangeCommitsTheCapturedTransformTarget() {
        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);
        tool.arrowKeyPressed(ArrowKey.DOWN);

        Tools.BRUSH.activate();

        assertThat(Tools.getActive()).isSameAs(Tools.BRUSH);
        assertThat(tool.isFreeTransforming()).isFalse();
        assertHistoryEditsAre("Nudge", "Apply Free Transform");
    }

    @Test
    void layerChangeCommitsOldTargetBeforeContinuingWithNewLayer() {
        ImageLayer otherLayer = addLayer(createScreenshot(TOP_BACKGROUND, TOP_WIDGET), "other");
        comp.setActiveLayer(sourceLayer);
        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);
        tool.arrowKeyPressed(ArrowKey.DOWN);

        comp.setActiveLayer(otherLayer);
        // Unit-test mode suppresses the global callback, so invoke the tool hook.
        tool.editingTargetChanged(otherLayer, false);

        assertThat(tool.isFreeTransforming()).isFalse();
        assertThat(comp.getActiveLayer()).isSameAs(otherLayer);
        assertHistoryEditsAre("Nudge", "Apply Free Transform");
    }

    @Test
    void doubleClickInsideTransformBoxCommits() {
        tool.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);
        tool.arrowKeyPressed(ArrowKey.RIGHT);
        MouseEvent event = new MouseEvent(comp.getView(), MouseEvent.MOUSE_CLICKED,
            0, 0, 5, 5, 2, false, MouseEvent.BUTTON1);

        tool.mouseClicked(new PMouseEvent(event, comp.getView()));

        assertThat(tool.isFreeTransforming()).isFalse();
        assertHistoryEditsAre("Nudge", "Apply Free Transform");
    }

    private void configureMove(MoveMode mode, boolean autoSelect) {
        configureMove(mode, autoSelect, false);
    }

    private void configureMove(MoveMode mode, boolean autoSelect, boolean freeTransform) {
        var preset = new UserPreset("move settings");
        preset.put(MoveMode.PRESET_KEY, mode.name());
        preset.putBoolean("AutoSelect", autoSelect);
        preset.putBoolean("FreeTransform", freeTransform);
        tool.loadUserPreset(preset);
    }

    private ImageLayer addLayer(BufferedImage image, String name) {
        ImageLayer layer = TestHelper.createImageLayer(comp, image, name);
        comp.addLayerWithoutUI(layer);
        return layer;
    }

    private PMouseEvent mouseEvent(int id, int x, int y, boolean commandDown) {
        int modifiers = commandDown ? InputEvent.META_DOWN_MASK : 0;
        var event = new MouseEvent(comp.getView(), id, 0, modifiers,
            x, y, 1, false, MouseEvent.BUTTON1);
        return new PMouseEvent(event, comp.getView());
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

    private static void assertRegionColor(BufferedImage image, Rectangle region, int expected) {
        for (int y = region.y; y < region.y + region.height; y++) {
            for (int x = region.x; x < region.x + region.width; x++) {
                assertThat(image.getRGB(x, y))
                    .as("pixel at (%s, %s)", x, y)
                    .isEqualTo(expected);
            }
        }
    }
}
