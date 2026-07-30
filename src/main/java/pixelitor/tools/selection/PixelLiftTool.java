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

import pixelitor.Composition;
import pixelitor.Views;
import pixelitor.filters.gui.UserPreset;
import pixelitor.gui.View;
import pixelitor.history.History;
import pixelitor.layers.ImageLayer;
import pixelitor.layers.Layer;
import pixelitor.layers.LayerHolder;
import pixelitor.selection.Selection;
import pixelitor.selection.SelectionType;
import pixelitor.selection.ShapeCombinator;
import pixelitor.tools.ToolIcons;
import pixelitor.tools.move.MoveMode;
import pixelitor.tools.util.ArrowKey;
import pixelitor.tools.util.OverlayType;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Cursors;
import pixelitor.utils.Geometry;

import javax.swing.JCheckBox;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * A rectangular selection tool that also moves the selected pixels:
 * dragging from inside the selection cuts them into a new layer
 * (see {@link Composition#layerViaFillCut()}) and moves that layer.
 * <p>
 * This turns the "select, hold Ctrl for the Move Tool, hold Command to
 * fill-cut, drag, release to merge" workflow into a single drag, recorded
 * as a single undoable edit.
 */
public class PixelLiftTool extends MarqueeSelectionTool {
    private static final String PRESET_KEY_AUTO_SELECT = "Auto Select";

    /**
     * The name of the combined edit created by one lift gesture.
     */
    public static final String LIFT_EDIT_NAME = "Pixel Lift";

    private enum LiftState {
        /**
         * An ordinary selection drag, or no drag at all.
         */
        NONE,

        /**
         * The mouse was pressed inside the selection, but it hasn't moved yet.
         * The fill cut is delayed until the drag actually starts, so that a
         * click inside the selection doesn't create and merge a layer.
         */
        CANDIDATE,

        /**
         * The pixels have been cut out and are being moved.
         */
        LIFTING
    }

    private final JCheckBox autoSelectCheckBox = new JCheckBox();

    private LiftState liftState = LiftState.NONE;

    // the composition the gesture started on: a synthetic mouse released
    // event could arrive with a different view (see MouseDispatcher)
    private Composition liftComp;

    // the layer created by the fill cut
    private Layer liftedLayer;

    // whether this tool started the currently open history transaction
    private boolean ownsTransaction;

    public PixelLiftTool() {
        super(SelectionType.RECTANGLE, "Pixel Lift", 'J',
            "<b>drag</b> outside the selection creates a selection, " +
                "<b>drag</b> inside it moves the selected pixels. " +
                "<b>Space-drag</b> moves the selection.");
    }

    @Override
    public void initSettingsPanel(ResourceBundle resources) {
        super.initSettingsPanel(resources);

        settingsPanel.addWithLabel("Auto Select Layer:",
            autoSelectCheckBox, "autoSelectCheckBox");
        autoSelectCheckBox.setToolTipText(
            "Cut from the layer under the mouse instead of the active layer");
    }

    @Override
    protected String getAutoMergeToolTip() {
        return "Merge the moved pixels back into their layer when the mouse is released";
    }

    public boolean isAutoSelecting() {
        return autoSelectCheckBox.isSelected();
    }

    // -------------------------------------------------------------------
    // deciding whether a gesture is a lift
    // -------------------------------------------------------------------

    /**
     * Returns whether a press with these modifiers, at this point,
     * should start moving the selected pixels instead of selecting.
     */
    private boolean canLift(PMouseEvent e) {
        if (!e.isLeft()) {
            // right-drag keeps the right-click-deselects behavior
            return false;
        }
        if (e.isShiftDown() || e.isAltDown()) {
            // Shift and Alt are the selection combinator modifiers
            return false;
        }
        if (e.isControlDown() || e.isMetaDown()) {
            // reserved for the temporary Move Tool workflows
            return false;
        }
        if (getCombinator() != ShapeCombinator.REPLACE) {
            // the combo box asks for editing the existing selection
            return false;
        }

        Composition comp = e.getComp();
        Selection selection = comp.getSelection();
        if (selection == null || !selection.isValid() || selection.isHidden()) {
            return false;
        }
        // use the shape and not its bounds, so that a selection
        // built with Add/Subtract has an exact lift area
        if (!selection.getShape().contains(e.getImX(), e.getImY())) {
            return false;
        }

        return findLiftSource(comp, e.toImPoint()) != null;
    }

    /**
     * Returns the layer the pixels should be cut from, or null if
     * a lift isn't possible. Checking this in advance also prevents
     * the error dialogs of {@link Composition#layerViaFillCut()}.
     */
    private Layer findLiftSource(Composition comp, Point imPoint) {
        Layer layer = isAutoSelecting()
            ? comp.findLayerAtPoint(imPoint)
            : comp.getActiveLayer();

        if (layer == null || layer.isMaskEditing()) {
            return null;
        }
        // only image layers can be cut, and merging down
        // also requires an image layer below the new layer
        return layer.getClass() == ImageLayer.class ? layer : null;
    }

    private boolean isLifting() {
        return liftState == LiftState.LIFTING;
    }

    // -------------------------------------------------------------------
    // the drag
    // -------------------------------------------------------------------

    @Override
    protected void dragStarted(PMouseEvent e) {
        if (canLift(e)) {
            // Don't create a SelectionBuilder: its constructor hides or
            // freezes the existing selection, which would have to be undone.
            liftState = LiftState.CANDIDATE;
            liftComp = e.getComp();
            return;
        }

        liftState = LiftState.NONE;
        liftComp = null;
        super.dragStarted(e);
    }

    @Override
    protected void ongoingDrag(PMouseEvent e) {
        if (liftState == LiftState.CANDIDATE) {
            if (drag.isClick()) {
                return; // the mouse hasn't moved yet
            }
            if (!startLift(e)) {
                // the conditions changed since the press: select instead
                liftState = LiftState.NONE;
                liftComp = null;
                super.dragStarted(e);
            }
        }

        if (isLifting()) {
            e.getComp().moveActiveContent(
                MoveMode.MOVE_BOTH, drag.getDX(), drag.getDY());
            return;
        }

        super.ongoingDrag(e);
    }

    @Override
    protected void dragFinished(PMouseEvent e) {
        if (isLifting()) {
            finishLift();
            return;
        }
        if (liftState == LiftState.CANDIDATE) {
            // a click inside the selection: don't lift, but don't
            // deselect either, so that the selection stays reusable
            resetLiftState();
            return;
        }

        super.dragFinished(e);
    }

    /**
     * Cuts the selected pixels into a new layer and prepares to move it.
     * Returns false if the lift couldn't be started.
     */
    private boolean startLift(PMouseEvent e) {
        Composition comp = e.getComp();
        Layer source = findLiftSource(comp, e.toImPoint());
        if (source == null || !comp.hasSelection()) {
            return false;
        }
        if (source != comp.getActiveLayer()) {
            comp.setActiveLayer(source);
        }

        History.startTransaction(LIFT_EDIT_NAME, comp);
        ownsTransaction = true;

        boolean started = false;
        try {
            comp.layerViaFillCut();
            Layer extracted = comp.getActiveLayer();
            if (extracted == source) {
                return false; // the cut didn't happen
            }

            liftedLayer = extracted;
            liftComp = comp;
            comp.prepareMovement(MoveMode.MOVE_BOTH, false);
            liftState = LiftState.LIFTING;

            // Space must not reposition the drag origin while lifting:
            // Drag.pan() would translate both endpoints, freezing the pixels.
            repositionOnSpace = false;

            started = true;
            return true;
        } finally {
            if (!started) {
                cancelLift();
            }
        }
    }

    /**
     * Ends a lift by committing the movement and, if Auto Merge
     * is enabled, merging the lifted layer back down.
     */
    private void finishLift() {
        try {
            if (liftComp != null) {
                liftComp.finalizeMovement(MoveMode.MOVE_BOTH);

                if (isAutoMerge() && liftedLayer != null) {
                    LayerHolder holder = liftedLayer.getHolder();
                    if (holder.canMergeDown(liftedLayer)) {
                        holder.mergeDown(liftedLayer);
                    }
                }
            }
        } finally {
            resetLiftState();
            if (ownsTransaction) {
                ownsTransaction = false;
                History.endTransaction();
            }
        }
    }

    /**
     * Ends a lift by undoing everything it did.
     */
    private void cancelLift() {
        try {
            if (isLifting() && liftComp != null) {
                // convert the drag offset into a real (and undoable)
                // translation, so that aborting can undo all of it
                liftComp.finalizeMovement(MoveMode.MOVE_BOTH);
            }
        } finally {
            resetLiftState();
            if (ownsTransaction) {
                ownsTransaction = false;
                History.abortTransaction();
            }
        }
    }

    /**
     * Forgets the gesture state without touching the history.
     */
    private void resetLiftState() {
        liftState = LiftState.NONE;
        liftComp = null;
        liftedLayer = null;
        repositionOnSpace = true;
    }

    // -------------------------------------------------------------------
    // painting and cursor
    // -------------------------------------------------------------------

    @Override
    public void paintOverCanvas(Graphics2D g2, Composition comp) {
        if (isLifting()) {
            comp.drawMovementContours(g2, MoveMode.MOVE_BOTH);
            if (drag != null && drag.isDragging()) {
                OverlayType.REL_MOUSE_POS.draw(g2, drag);
            }
            return;
        }

        super.paintOverCanvas(g2, comp);
    }

    @Override
    protected OverlayType getOverlayType() {
        return isLifting()
            ? OverlayType.REL_MOUSE_POS
            : super.getOverlayType();
    }

    @Override
    public void mouseMoved(MouseEvent e, View view) {
        super.mouseMoved(e, view);

        view.setCursor(isOverLiftArea(e, view)
            ? Cursors.MOVE
            : getStartingCursor());
    }

    private boolean isOverLiftArea(MouseEvent e, View view) {
        Composition comp = view.getComp();
        Selection selection = comp.getSelection();
        if (selection == null || !selection.isValid() || selection.isHidden()) {
            return false;
        }

        // the cheap shape test first, because finding
        // the layer under the point reads pixels
        double imX = view.componentXToImageSpace(e.getX());
        double imY = view.componentYToImageSpace(e.getY());
        if (!selection.getShape().contains(imX, imY)) {
            return false;
        }

        Point imPoint = Geometry.round(view.componentToImageSpace(e.getPoint()));
        return findLiftSource(comp, imPoint) != null;
    }

    @Override
    public boolean isDirectDrawing() {
        return !isLifting();
    }

    // -------------------------------------------------------------------
    // keyboard and lifecycle
    // -------------------------------------------------------------------

    @Override
    public void altPressed() {
        if (isLifting()) {
            return; // expand-from-center is meaningless while moving pixels
        }
        super.altPressed();
    }

    @Override
    public void altReleased() {
        if (isLifting()) {
            return;
        }
        super.altReleased();
    }

    @Override
    public boolean arrowKeyPressed(ArrowKey key) {
        if (liftState != LiftState.NONE) {
            // nudging only the selection would desynchronize
            // it from the pixels being moved
            return true; // consumed
        }
        return super.arrowKeyPressed(key);
    }

    @Override
    public void escPressed() {
        if (isLifting()) {
            // DragTool.escPressed() cancels the drag, which makes
            // mouseReleased() return early, so dragFinished() would never
            // run and the history transaction would be left open.
            if (drag != null) {
                drag.cancel();
            }
            cancelLift();

            View view = Views.getActive();
            if (view != null) {
                view.repaint();
            }
            return; // don't deselect: the lift was canceled, not the selection
        }

        resetLiftState();
        super.escPressed();
    }

    @Override
    protected void toolDeactivated(View view) {
        // Tools.start() synthesizes a mouse released event before this,
        // so normally the gesture is already finished. This is for the
        // paths that bypass it, such as Tools.setActiveTool().
        if (isLifting()) {
            finishLift();
        } else {
            resetLiftState();
        }

        super.toolDeactivated(view);
    }

    @Override
    public void forceFinish() {
        if (isLifting()) {
            finishLift();
        }
    }

    @Override
    public void reset() {
        // the view is gone or changed: an edit referring to it is useless
        cancelLift();
    }

    @Override
    public void compReplaced(Composition newComp, boolean reloaded) {
        cancelLift();
    }

    // -------------------------------------------------------------------
    // presets and icon
    // -------------------------------------------------------------------

    @Override
    public void saveStateTo(UserPreset preset) {
        super.saveStateTo(preset);
        preset.putBoolean(PRESET_KEY_AUTO_SELECT, isAutoSelecting());
    }

    @Override
    public void loadUserPreset(UserPreset preset) {
        super.loadUserPreset(preset);
        autoSelectCheckBox.setSelected(preset.getBoolean(PRESET_KEY_AUTO_SELECT));
    }

    @Override
    public Consumer<Graphics2D> createIconPainter() {
        return ToolIcons::paintPixelLiftIcon;
    }
}
