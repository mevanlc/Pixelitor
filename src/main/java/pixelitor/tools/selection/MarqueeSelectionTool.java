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

package pixelitor.tools.selection;

import pixelitor.AppMode;
import pixelitor.filters.gui.UserPreset;
import pixelitor.gui.GlobalEvents;
import pixelitor.selection.SelectionType;
import pixelitor.tools.ToolIcons;
import pixelitor.tools.Tools;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Cursors;

import javax.swing.JCheckBox;
import java.awt.Graphics2D;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * A tool that creates rectangular or elliptical selections by dragging.
 */
public class MarqueeSelectionTool extends AbstractSelectionTool {
    private static final String PRESET_KEY_AUTO_MERGE = "Auto Merge";

    private final SelectionType selectionType;
    private final JCheckBox autoMergeCheckBox = new JCheckBox("Auto Merge");

    // the rectangle and ellipse selection tools share the 'M' hotkey, with cycling
    public MarqueeSelectionTool(SelectionType selectionType) {
        super(selectionType.toString() + " Selection", 'M',
            "<b>click and drag</b> creates a selection, " +
                "<b>Space-drag</b> moves it. " +
                "Hold <b>Ctrl</b> for the Move Tool.", Cursors.DEFAULT, false);
        repositionOnSpace = true; // allow moving the start point with space down
        pixelSnapping = true;
        this.selectionType = selectionType;
    }

    @Override
    public void initSettingsPanel(ResourceBundle resources) {
        super.initSettingsPanel(resources);

        settingsPanel.addSeparator();
        autoMergeCheckBox.setName("autoMergeCheckBox");
        autoMergeCheckBox.setToolTipText(
            "Merge down a layer created by Command-drag in the temporary Move Tool");
        settingsPanel.add(autoMergeCheckBox);
    }

    @Override
    public void controlPressed() {
        Tools.startTemporaryTool(Tools.MOVE);
    }

    public boolean isAutoMerge() {
        return autoMergeCheckBox.isSelected();
    }

    @Override
    public void saveStateTo(UserPreset preset) {
        super.saveStateTo(preset);
        preset.putBoolean(PRESET_KEY_AUTO_MERGE, isAutoMerge());
    }

    @Override
    public void loadUserPreset(UserPreset preset) {
        super.loadUserPreset(preset);
        autoMergeCheckBox.setSelected(preset.getBoolean(PRESET_KEY_AUTO_MERGE));
    }

    @Override
    protected void dragStarted(PMouseEvent e) {
        initCombinatorAndBuilder(e, selectionType);
    }

    @Override
    protected void ongoingDrag(PMouseEvent e) {
        if (selectionBuilder == null) {
            // can happen if the image changed mid-drag; restart the drag
            dragStarted(e);
        }

        // check if the Alt key was pressed mid-drag
        boolean altDown = e.isAltDown();
        assert altDown == GlobalEvents.isAltDown() || AppMode.isUnitTesting()
            : "altDown = " + altDown + ", GlobalEvents.isAltDown() = " + GlobalEvents.isAltDown();

        // expand from center only if Alt wasn't already down at drag-start
        // (in that case Alt is used for shape combination, not expand-from-center)
        boolean expandFromCenter = !altUsedForCombinator && altDown;

        // if Alt is released mid-drag, it no longer means subtract/intersect for this drag
        if (!altDown) {
            altUsedForCombinator = false;
        }

        drag.setExpandFromCenter(expandFromCenter);
        selectionBuilder.updateDraftSelection(drag);
        e.repaint();
    }

    @Override
    protected void dragFinished(PMouseEvent e) {
        // common logic in the base class
        finalizeDragBasedSelection(e);
    }

    // altPressed() and altReleased() mirror the expand-from-center handling
    // in ongoingDrag(), but fire immediately on the key event since
    // there may be no mouse movement to trigger it otherwise
    @Override
    public void altPressed() {
        if (!altUsedForCombinator && drag != null && drag.isDragging()) {
            drag.setExpandFromCenter(true);
            if (selectionBuilder != null) {
                selectionBuilder.updateDraftSelection(drag);
            }
        }
    }

    @Override
    public void altReleased() {
        boolean wasAltCombinator = altUsedForCombinator;

        super.altReleased(); // clears altUsedForCombinator

        if (!wasAltCombinator && drag != null && drag.isDragging()) {
            drag.setExpandFromCenter(false);
            if (selectionBuilder != null) {
                selectionBuilder.updateDraftSelection(drag);
            }
        }
    }

    @Override
    public Consumer<Graphics2D> createIconPainter() {
        return selectionType == SelectionType.RECTANGLE
            ? ToolIcons::paintRectangleSelectionIcon
            : ToolIcons::paintEllipseSelectionIcon;
    }
}
