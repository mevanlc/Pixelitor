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

package pixelitor.tools.move;

import pixelitor.Composition;
import pixelitor.Views;
import pixelitor.filters.gui.UserPreset;
import pixelitor.gui.View;
import pixelitor.history.History;
import pixelitor.history.PixelitorEdit;
import pixelitor.layers.ImageLayer;
import pixelitor.layers.Layer;
import pixelitor.tools.DragTool;
import pixelitor.tools.ToolIcons;
import pixelitor.tools.Tools;
import pixelitor.tools.selection.SelectionChangeListener;
import pixelitor.tools.transform.*;
import pixelitor.tools.transform.history.ApplyTransformEdit;
import pixelitor.tools.transform.history.CancelTransformEdit;
import pixelitor.tools.transform.history.TransformStepEdit;
import pixelitor.tools.transform.history.TransformUISnapshot;
import pixelitor.tools.util.ArrowKey;
import pixelitor.tools.util.OverlayType;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Cursors;
import pixelitor.utils.Geometry;
import pixelitor.utils.Messages;

import javax.swing.*;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * A tool for moving layers and selections, with an optional free-transform mode.
 */
public class MoveTool extends DragTool implements SelectionChangeListener {
    private final JComboBox<MoveMode> modeSelector = new JComboBox<>(MoveMode.values());
    private MoveMode activeMode = getActiveMode();

    private final JCheckBox autoSelectCheckBox = new JCheckBox();
    private final JCheckBox freeTransformCheckBox = new JCheckBox();

    // Usually the selected mode, but Command + Selection Only temporarily
    // includes the newly created layer so that the gesture can move it.
    private MoveMode dragMode = activeMode;

    private FreeTransformSession transformSession;
    private TransformOptionsPanel transformOptionsPanel;

    public MoveTool() {
        super("Move", 'V',
            "<b>drag</b> to move the active layer. " +
                "<b>Alt-drag</b> or <b>right-mouse-drag</b> to duplicate and move the active layer. " +
                "<b>Cmd-click/drag</b> with a selection creates a layer via fill cut. " +
                "<b>Shift-drag</b> to constrain movement.",
            Cursors.DEFAULT, true);
    }

    @Override
    public void initSettingsPanel(ResourceBundle resources) {
        String moveText = resources.getString("mt_move");
        settingsPanel.addComboBox(moveText, modeSelector, "modeSelector");
        modeSelector.addActionListener(_ -> modeChanged());

        settingsPanel.addSeparator();
        settingsPanel.addWithLabel("Auto Select Layer:",
            autoSelectCheckBox, "autoSelectCheckBox");

        settingsPanel.addWithLabel("Free Transform:",
            freeTransformCheckBox, "freeTransformCheckBox");
        freeTransformCheckBox.addActionListener(_ ->
            setFreeTransformMode(freeTransformCheckBox.isSelected()));

        transformOptionsPanel = new TransformOptionsPanel(
            this::getTransformBox,
            this::addTransformStep,
            () -> applyTransform(true),
            () -> cancelTransform(true));
    }

    private void modeChanged() {
        cancelTransform(true);
        activeMode = getActiveMode();
    }

    private void showTransformSettings(boolean transforming) {
        if (transformOptionsPanel == null) {
            return;
        }
        settingsPanel.removeAll();
        if (transforming) {
            settingsPanel.add(transformOptionsPanel);
            transformOptionsPanel.refreshFromModel();
        } else {
            String moveText = pixelitor.utils.Texts.getResources().getString("mt_move");
            settingsPanel.addComboBox(moveText, modeSelector, "modeSelector");
            settingsPanel.addSeparator();
            settingsPanel.addWithLabel("Auto Select Layer:",
                autoSelectCheckBox, "autoSelectCheckBox");
            settingsPanel.addWithLabel("Free Transform:",
                freeTransformCheckBox, "freeTransformCheckBox");
        }
        settingsPanel.revalidate();
        settingsPanel.repaint();
    }

    private MoveMode getActiveMode() {
        return (MoveMode) modeSelector.getSelectedItem();
    }

    @Override
    protected void dragStarted(PMouseEvent e) {
        Composition comp = e.getComp();
        boolean fillCutRequested = e.isMetaDown() && comp.hasSelection();

        // A fill cut changes the transform target, so cancel any current free
        // transform session before resolving the source and creating the layer.
        if (fillCutRequested && isFreeTransforming()) {
            cancelTransform(true);
        }

        if (isFreeTransforming()) {
            // if a transform is active, the drag event goes to the transform box
            if (!getTransformBox().processMousePressed(e)) {
                // if the press is outside the box, the drag is canceled
                drag.cancel();
            }
        } else { // regular move mode
            dragMode = activeMode;

            // Resolve the source before the fill cut creates and activates a new layer.
            if (isAutoSelecting() && (activeMode.movesLayer() || fillCutRequested)) {
                Layer targetLayer = comp.findLayerAtPoint(e.toImPoint());

                if (targetLayer == null) {
                    drag.cancel();
                    return;
                }
                comp.setActiveLayer(targetLayer);
            }

            if (fillCutRequested) {
                Layer sourceLayer = comp.getActiveLayer();
                comp.layerViaFillCut();

                // In Selection Only mode, include the extracted layer for this
                // gesture while retaining the user's configured mode afterward.
                if (comp.getActiveLayer() != sourceLayer && !dragMode.movesLayer()) {
                    dragMode = MoveMode.MOVE_BOTH;
                }
            }

            comp.prepareMovement(dragMode, e.isAltDown() || e.isRight());
        }
    }

    @Override
    protected void ongoingDrag(PMouseEvent e) {
        if (isFreeTransforming()) {
            getTransformBox().processMouseDragged(e);
        } else {
            e.getComp().moveActiveContent(dragMode, drag.getDX(), drag.getDY());
        }
    }

    @Override
    public void dragFinished(PMouseEvent e) {
        if (isFreeTransforming()) {
            TransformBox box = getTransformBox();
            box.processMouseReleased(e);
            // a drag is a discrete, undoable action
            addTransformStep("Free Transform Step", box.getBeforeMovementMemento());
        } else {
            e.getComp().finalizeMovement(dragMode);
        }
    }

    @Override
    public void mouseMoved(MouseEvent e, View view) {
        super.mouseMoved(e, view);

        if (isFreeTransforming()) {
            getTransformBox().mouseMoved(e);
        } else if (activeMode.movesLayer()) {
            updateMoveCursor(view, e);
        }
    }

    private void updateMoveCursor(View view, MouseEvent e) {
        if (isAutoSelecting()) {
            Point p = Geometry.round(view.componentToImageSpace(e.getPoint()));
            Layer movedLayer = view.getComp().findLayerAtPoint(p);
            if (movedLayer == null) {
                view.setCursor(Cursors.DEFAULT);
                return;
            }
        }
        view.setCursor(Cursors.MOVE);
    }

    public boolean isFreeTransforming() {
        return transformSession != null;
    }

    /**
     * Programmatically moves the active layer and/or the selection.
     */
    public static void move(Composition comp, MoveMode mode, int imDx, int imDy) {
        comp.prepareMovement(mode, false);
        comp.moveActiveContent(mode, imDx, imDy);
        comp.finalizeMovement(mode);
    }

    @Override
    public void paintOverCanvas(Graphics2D g2, Composition comp) {
        if (isFreeTransforming()) {
            getTransformBox().paint(g2);
            return;
        }

        if (drag == null || !drag.isDragging()) {
            return;
        }

        comp.drawMovementContours(g2, dragMode);
        OverlayType.REL_MOUSE_POS.draw(g2, drag);
    }

    @Override
    protected OverlayType getOverlayType() {
        return OverlayType.REL_MOUSE_POS;
    }

    @Override
    public void coCoordsChanged(View view) {
        if (isFreeTransforming()) {
            getTransformBox().coCoordsChanged(view);
        }
    }

    @Override
    public void imCoordsChanged(AffineTransform at, View view) {
        if (isFreeTransforming()) {
            getTransformBox().imCoordsChanged(at, view);
        }
    }

    @Override
    public boolean arrowKeyPressed(ArrowKey key) {
        View view = Views.getActive();
        if (view == null) {
            return false;
        }

        if (isFreeTransforming()) {
            TransformBox box = getTransformBox();
            box.arrowKeyPressed(key, view);
            // a nudge is a discrete, undoable action
            addTransformStep("Nudge", box.getBeforeMovementMemento());
        } else {
            move(view.getComp(), activeMode, key.getDeltaX(), key.getDeltaY());
        }

        return true;
    }

    @Override
    public void controlPressed() {
        Tools.cancelPrimaryToolRestore(this);
    }

    @Override
    public void controlReleased() {
        Tools.restorePrimaryTool(this);
    }

    private void setFreeTransformMode(boolean enabled) {
        if (enabled) {
            startFreeTransform(TransformStartSource.MOVE_CONTROLS);
            if (isFreeTransforming()) {
                Messages.showStatusMessage("Free Transform: drag handles; <b>Enter</b> or <b>Double-click</b> to apply; <b>Esc</b> to cancel.");
            } else {
                handleTransformStartFailure(true);
            }
        } else {
            // if the user unchecks the box, apply the
            // transform and return to regular move mode
            applyTransform(true);
        }
    }

    @Override
    protected void toolActivated(View view) {
        super.toolActivated(view);

        if (freeTransformCheckBox.isSelected()) {
            startFreeTransform(TransformStartSource.MOVE_CONTROLS);
            if (!isFreeTransforming()) {
                handleTransformStartFailure(false);
            }
        }
    }

    private void handleTransformStartFailure(boolean showMessage) {
        freeTransformCheckBox.setSelected(false);

        if (!showMessage) {
            return;
        }

        Composition comp = Views.getActiveComp();

        String message;
        if (activeMode == MoveMode.MOVE_SELECTION_ONLY && comp.getSelection() == null) {
            message = "Free Transform in 'Selection Only' mode requires a selection.";
        } else if (activeMode.movesLayer() && !(comp.getActiveLayer() instanceof ImageLayer)) {
            message = "The active layer must be an image layer to use Free Transform.";
        } else {
            message = "A transformable layer or an active selection is required to start Free Transform.";
        }
        Messages.showInfo("Cannot Start Free Transform", message);
    }

    @Override
    protected void toolDeactivated(View view) {
        // switching to another tool implicitly applies the transform
        applyTransform(true);
        super.toolDeactivated(view);
    }

    @Override
    public void escPressed() {
        cancelTransform(true);
    }

    @Override
    public void otherKeyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER && isFreeTransforming()) {
            applyTransform(true);
            e.consume();
        }
    }

    @Override
    public void editingTargetChanged(Layer activeLayer, boolean toolActivation) {
        if (!toolActivation && isFreeTransforming()
            && activeLayer != transformSession.activeLayerAtStart()) {
            // The target is captured by the session, so this safely commits the
            // old layer after the Layers panel has selected the new one.
            applyTransform(true);
        }
    }

    @Override
    public void compReplaced(Composition newComp, boolean reloaded) {
        cancelTransform(false);
    }

    @Override
    public void selectionChanged() {
        if (isFreeTransforming() && transformSession.moveMode().movesSelection()) {
            cancelTransform(true);
        }
    }

    @Override
    public void selectionDeleted() {
        if (isFreeTransforming() && transformSession.moveMode().movesSelection()) {
            cancelTransform(true);
        }
    }

    @Override
    public void mouseClicked(PMouseEvent e) {
        if (e.getClickCount() == 2 && !e.isConsumed() && isFreeTransforming()
            && getTransformBox().contains(e.getCoX(), e.getCoY())) {
            // a double-click applies the transform
            applyTransform(true);
            e.consume();
        }
    }

    public void startFreeTransform(TransformStartSource source) {
        Composition comp = Views.getActiveComp();
        if (comp != null) {
            startFreeTransform(comp, source);
        }
    }

    public void startFreeTransform(Composition requestedComp, TransformStartSource source) {
        View view = Views.getActive();
        if (view == null || view.getComp() != requestedComp) {
            return;
        }

        if (isFreeTransforming()) {
            return;
        }

        TransformBoundsResolver.Resolution resolution = source == TransformStartSource.EDIT_COMMAND
            ? TransformBoundsResolver.resolveEditCommand(requestedComp)
            : TransformBoundsResolver.resolveMoveControls(requestedComp, view, activeMode);
        if (resolution == null) {
            if (source == TransformStartSource.EDIT_COMMAND) {
                showEditCommandStartFailure(requestedComp);
            }
            return;
        }

        if (source == TransformStartSource.EDIT_COMMAND && !isActive()) {
            // Validation deliberately happens before activation, so an invalid
            // command does not change tools or history.
            freeTransformCheckBox.setSelected(false);
            activate();
            view = Views.getActive();
            if (view == null || view.getComp() != requestedComp) {
                return;
            }
        }

        transformSession = new FreeTransformSession(requestedComp, view, resolution, source);
        configureActiveSession();
    }

    private void configureActiveSession() {
        TransformBox box = getTransformBox();
        box.setChangeListener(() -> {
            if (transformOptionsPanel != null) {
                transformOptionsPanel.refreshFromModel();
            }
        });
        freeTransformCheckBox.setSelected(true);
        showTransformSettings(true);
        Views.getActive().repaint();
        Messages.showStatusMessage("Free Transform: drag handles; <b>Enter</b> or "
            + "<b>Double-click</b> to apply; <b>Esc</b> to cancel.");
    }

    private static void showEditCommandStartFailure(Composition comp) {
        if (!(comp.getActiveLayer() instanceof ImageLayer)) {
            Messages.showInfo("Cannot Start Free Transform",
                "The active layer must be an image layer to use Free Transform.");
        } else {
            Messages.showInfo("Cannot Start Free Transform",
                "The active image layer is fully transparent.");
        }
    }

    private void applyTransform(boolean addToHistory) {
        if (!isFreeTransforming()) {
            return;
        }

        // create a snapshot of the UI state before finalizing, for undo support
        FreeTransformSession session = transformSession;
        TransformUISnapshot snapshot = createSnapshot(session);

        // create the data edit by finalizing the transform on the target
        PixelitorEdit contentEdit = session.target().finalizeTransform();

        // if there was a significant change, create a history entry that bundles the
        // data edit with the UI snapshot, allowing the session to be restored on undo
        if (addToHistory && contentEdit != null) {
            History.add(new ApplyTransformEdit(
                "Apply Free Transform",
                session.comp(),
                contentEdit,
                snapshot
            ));
        }

        // clean up the tool's state
        endTransformSession();

        Messages.showStatusMessage("Free transform applied.");
    }

    public void cancelTransform(boolean addToHistory) {
        if (!isFreeTransforming()) {
            return;
        }

        // create a snapshot of the UI state before canceling, for undo support
        FreeTransformSession session = transformSession;
        TransformUISnapshot snapshot = createSnapshot(session);

        // revert the target object to its original state
        session.target().cancelTransform();

        // create a history entry for the cancellation action, but only if the box state has changed
        if (addToHistory && !snapshot.memento().equals(session.initialMemento())) {
            History.add(new CancelTransformEdit(
                "Cancel Free Transform",
                session.comp(),
                snapshot
            ));
        }

        // clean up the tool's state
        endTransformSession();

        Messages.showStatusMessage("Free transform canceled.");
    }

    public void endTransformSession() {
        FreeTransformSession oldSession = transformSession;
        transformSession = null;
        if (oldSession != null) {
            // Normally finalize/cancel has already cleared transient preview
            // state. This also handles redo after an apply edit restored the UI.
            oldSession.target().cancelTransform();
        }
        freeTransformCheckBox.setSelected(false);
        showTransformSettings(false);

        if (oldSession != null && oldSession.comp().getView() != null) {
            oldSession.comp().getView().repaint();
        }
    }

    /**
     * Restores an interactive transform session from a history snapshot.
     */
    public void restoreTransformSession(TransformUISnapshot snapshot) {
        // if a transform is already active, cancel it first
        cancelTransform(false);

        View view = Views.getActive();
        if (view == null) {
            return;
        }

        // set up tool state from the snapshot
        modeSelector.setSelectedItem(snapshot.moveMode());
        activeMode = snapshot.moveMode();

        Composition comp = view.getComp();
        Transformable target = snapshot.target();
        if (target == null) {
            freeTransformCheckBox.setSelected(false);
            return;
        }

        transformSession = new FreeTransformSession(
            comp, view, target, snapshot.originalBounds(), snapshot.source(),
            snapshot.boundsPolicy(), snapshot.moveMode(), snapshot.activeLayerAtStart(),
            snapshot.memento());
        configureActiveSession();
    }

    public TransformBox getTransformBox() {
        return transformSession == null ? null : transformSession.box();
    }

    public Transformable getTransformTarget() {
        return transformSession == null ? null : transformSession.target();
    }

    private static TransformUISnapshot createSnapshot(FreeTransformSession session) {
        return new TransformUISnapshot(
            session.box().createMemento(), session.moveMode(), session.target(),
            session.source(), session.boundsPolicy(), session.originalBounds(),
            session.activeLayerAtStart());
    }

    private void addTransformStep(String name, TransformBox.Memento before) {
        if (!isFreeTransforming() || before == null
            || before.equals(getTransformBox().createMemento())) {
            return;
        }
        History.add(new TransformStepEdit(name, transformSession.comp(), before));
    }

    private boolean isAutoSelecting() {
        return autoSelectCheckBox.isSelected();
    }

    @Override
    public boolean isDirectDrawing() {
        return false;
    }

    @Override
    public void saveStateTo(UserPreset preset) {
        preset.put(MoveMode.PRESET_KEY, getActiveMode().name());
        preset.putBoolean("AutoSelect", isAutoSelecting());
        preset.putBoolean("FreeTransform", isFreeTransforming());
    }

    @Override
    public void loadUserPreset(UserPreset preset) {
        MoveMode mode = preset.getEnum(MoveMode.PRESET_KEY, MoveMode.class);
        modeSelector.setSelectedItem(mode);

        autoSelectCheckBox.setSelected(preset.getBoolean("AutoSelect"));
        freeTransformCheckBox.setSelected(preset.getBoolean("FreeTransform"));
    }

    @Override
    public Consumer<Graphics2D> createIconPainter() {
        return ToolIcons::paintMoveIcon;
    }
}
