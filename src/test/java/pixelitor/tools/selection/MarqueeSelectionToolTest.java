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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import pixelitor.Composition;
import pixelitor.TestHelper;
import pixelitor.Views;
import pixelitor.filters.gui.UserPreset;
import pixelitor.gui.View;
import pixelitor.history.History;
import pixelitor.tools.Tools;
import pixelitor.tools.gui.ToolSettingsPanelContainer;
import pixelitor.tools.move.MoveMode;

import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static pixelitor.TestHelper.assertHistoryEditsAre;

class MarqueeSelectionToolTest {
    private Composition comp;
    private View view;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        comp = TestHelper.createComp("MarqueeSelectionToolTest", 1, false);
        view = comp.getView();
        Tools.MouseDispatcher.resetMouseState();

        Tools.MOVE.cancelTransform(false);
        var movePreset = new UserPreset("test");
        movePreset.put(MoveMode.PRESET_KEY, MoveMode.MOVE_LAYER_ONLY.name());
        movePreset.putBoolean("AutoSelect", false);
        movePreset.putBoolean("FreeTransform", false);
        Tools.MOVE.loadUserPreset(movePreset);

        setAutoMerge(Tools.RECTANGLE_SELECTION, false);
        setAutoMerge(Tools.ELLIPSE_SELECTION, false);
        History.clear();
    }

    @AfterEach
    void afterEachTest() {
        Tools.MouseDispatcher.resetMouseState();
        if (Tools.getActive() == Tools.MOVE) {
            Tools.MOVE.controlReleased();
        }
        var movePreset = new UserPreset("reset");
        movePreset.put(MoveMode.PRESET_KEY, MoveMode.MOVE_BOTH.name());
        movePreset.putBoolean("AutoSelect", false);
        movePreset.putBoolean("FreeTransform", false);
        Tools.MOVE.loadUserPreset(movePreset);
        setAutoMerge(Tools.RECTANGLE_SELECTION, false);
        setAutoMerge(Tools.ELLIPSE_SELECTION, false);
        History.clear();
        Tools.setActiveTool(Tools.BRUSH);
        Views.setActiveView(null, false);
    }

    static Stream<MarqueeSelectionTool> marqueeTools() {
        return Stream.of(Tools.RECTANGLE_SELECTION, Tools.ELLIPSE_SELECTION);
    }

    @ParameterizedTest
    @MethodSource("marqueeTools")
    void controlTemporarilySelectsMoveAndItsOptions(MarqueeSelectionTool primaryTool) {
        activate(primaryTool);
        ToolSettingsPanelContainer settings = ToolSettingsPanelContainer.get();
        clearInvocations(settings);

        primaryTool.controlPressed();

        assertThat(Tools.getActive()).isSameAs(Tools.MOVE);
        verify(settings).showSettingsOf(Tools.MOVE);

        Tools.MOVE.controlReleased();

        assertThat(Tools.getActive()).isSameAs(primaryTool);
        verify(settings).showSettingsOf(primaryTool);
    }

    @ParameterizedTest
    @MethodSource("marqueeTools")
    void controlDoesNotSwitchToolsWhileMouseIsDown(MarqueeSelectionTool primaryTool) {
        activate(primaryTool);
        Tools.MouseDispatcher.mousePressed(
            mouseEvent(MouseEvent.MOUSE_PRESSED, 1, 1, 0), view);

        primaryTool.controlPressed();

        assertThat(Tools.getActive()).isSameAs(primaryTool);

        Tools.MouseDispatcher.mouseReleased(
            mouseEvent(MouseEvent.MOUSE_RELEASED, 1, 1, 0), view);
    }

    @ParameterizedTest
    @MethodSource("marqueeTools")
    void controlReleasedDuringMoveRestoresPrimaryAfterMouseRelease(
        MarqueeSelectionTool primaryTool) {

        activate(primaryTool);
        primaryTool.controlPressed();
        Tools.MouseDispatcher.mousePressed(
            mouseEvent(MouseEvent.MOUSE_PRESSED, 1, 1, InputEvent.CTRL_DOWN_MASK), view);
        Tools.MouseDispatcher.mouseDragged(
            mouseEvent(MouseEvent.MOUSE_DRAGGED, 3, 1, InputEvent.CTRL_DOWN_MASK), view);

        Tools.MOVE.controlReleased();

        assertThat(Tools.getActive()).isSameAs(Tools.MOVE);

        Tools.MouseDispatcher.mouseReleased(
            mouseEvent(MouseEvent.MOUSE_RELEASED, 3, 1, 0), view);

        assertThat(Tools.getActive()).isSameAs(primaryTool);
    }

    @ParameterizedTest
    @MethodSource("marqueeTools")
    void pressingControlAgainCancelsPendingRestoration(MarqueeSelectionTool primaryTool) {
        activate(primaryTool);
        primaryTool.controlPressed();
        Tools.MouseDispatcher.mousePressed(
            mouseEvent(MouseEvent.MOUSE_PRESSED, 1, 1, InputEvent.CTRL_DOWN_MASK), view);

        Tools.MOVE.controlReleased();
        Tools.MOVE.controlPressed();
        Tools.MouseDispatcher.mouseReleased(
            mouseEvent(MouseEvent.MOUSE_RELEASED, 1, 1, InputEvent.CTRL_DOWN_MASK), view);

        assertThat(Tools.getActive()).isSameAs(Tools.MOVE);

        Tools.MOVE.controlReleased();
        assertThat(Tools.getActive()).isSameAs(primaryTool);
    }

    @ParameterizedTest
    @MethodSource("marqueeTools")
    void autoMergeMergesTemporaryCommandFillCutOnControlRelease(
        MarqueeSelectionTool primaryTool) {

        prepareAutoMergeGesture(primaryTool);

        Tools.MouseDispatcher.mousePressed(
            mouseEvent(MouseEvent.MOUSE_PRESSED, 2, 2, commandControl()), view);
        Tools.MouseDispatcher.mouseReleased(
            mouseEvent(MouseEvent.MOUSE_RELEASED, 2, 2, commandControl()), view);

        assertThat(comp.getNumLayers()).isEqualTo(2);

        Tools.MOVE.controlReleased();

        assertThat(Tools.getActive()).isSameAs(primaryTool);
        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertHistoryEditsAre("Layer via Fill Cut", "Move Layer", "Merge Down");
    }

    @ParameterizedTest
    @MethodSource("marqueeTools")
    void autoMergeWaitsForMouseReleaseWhenControlIsReleasedMidDrag(
        MarqueeSelectionTool primaryTool) {

        prepareAutoMergeGesture(primaryTool);

        Tools.MouseDispatcher.mousePressed(
            mouseEvent(MouseEvent.MOUSE_PRESSED, 2, 2, commandControl()), view);
        Tools.MouseDispatcher.mouseDragged(
            mouseEvent(MouseEvent.MOUSE_DRAGGED, 4, 3, commandControl()), view);

        Tools.MOVE.controlReleased();

        assertThat(Tools.getActive()).isSameAs(Tools.MOVE);
        assertThat(comp.getNumLayers()).isEqualTo(2);

        Tools.MouseDispatcher.mouseReleased(
            mouseEvent(MouseEvent.MOUSE_RELEASED, 4, 3, InputEvent.META_DOWN_MASK), view);

        assertThat(Tools.getActive()).isSameAs(primaryTool);
        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertHistoryEditsAre("Layer via Fill Cut", "Move Layer", "Merge Down");
    }

    @ParameterizedTest
    @MethodSource("marqueeTools")
    void disabledAutoMergeLeavesTemporaryCommandFillCutAsLayer(
        MarqueeSelectionTool primaryTool) {

        TestHelper.setSelection(comp, new Rectangle(1, 1, 3, 3));
        activate(primaryTool);
        primaryTool.controlPressed();

        Tools.MouseDispatcher.mousePressed(
            mouseEvent(MouseEvent.MOUSE_PRESSED, 2, 2, commandControl()), view);
        Tools.MouseDispatcher.mouseReleased(
            mouseEvent(MouseEvent.MOUSE_RELEASED, 2, 2, commandControl()), view);
        Tools.MOVE.controlReleased();

        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertHistoryEditsAre("Layer via Fill Cut", "Move Layer");
    }

    @ParameterizedTest
    @MethodSource("marqueeTools")
    void autoMergeDoesNotMergeAnOrdinaryTemporaryMove(
        MarqueeSelectionTool primaryTool) {

        var secondLayer = TestHelper.createEmptyImageLayer(comp, "second");
        comp.addLayerWithoutUI(secondLayer);
        setAutoMerge(primaryTool, true);
        activate(primaryTool);
        primaryTool.controlPressed();

        Tools.MouseDispatcher.mousePressed(
            mouseEvent(MouseEvent.MOUSE_PRESSED, 2, 2, InputEvent.CTRL_DOWN_MASK), view);
        Tools.MouseDispatcher.mouseReleased(
            mouseEvent(MouseEvent.MOUSE_RELEASED, 2, 2, InputEvent.CTRL_DOWN_MASK), view);
        Tools.MOVE.controlReleased();

        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertHistoryEditsAre("Move Layer");
    }

    private void prepareAutoMergeGesture(MarqueeSelectionTool primaryTool) {
        TestHelper.setSelection(comp, new Rectangle(1, 1, 3, 3));
        setAutoMerge(primaryTool, true);
        activate(primaryTool);
        primaryTool.controlPressed();
    }

    private static void setAutoMerge(MarqueeSelectionTool tool, boolean enabled) {
        var preset = new UserPreset("auto merge test");
        tool.saveStateTo(preset);
        preset.putBoolean("Auto Merge", enabled);
        tool.loadUserPreset(preset);
    }

    private static int commandControl() {
        return InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK;
    }

    private static void activate(MarqueeSelectionTool primaryTool) {
        Tools.setActiveTool(primaryTool);
    }

    private MouseEvent mouseEvent(int id, int x, int y, int modifiers) {
        return new MouseEvent(view, id, 0, modifiers,
            x, y, 1, false, MouseEvent.BUTTON1);
    }
}
