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
import pixelitor.history.ContentLayerMoveEdit;
import pixelitor.history.History;
import pixelitor.history.ImageEdit;
import pixelitor.history.MultiEdit;
import pixelitor.history.PixelitorEdit;
import pixelitor.layers.ImageLayer;
import pixelitor.layers.Layer;
import pixelitor.selection.Selection;
import pixelitor.tools.DragTool;
import pixelitor.tools.ToolIcons;
import pixelitor.tools.Tools;
import pixelitor.tools.util.ArrowKey;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Cursors;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.Messages;

import javax.swing.*;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * A move tool variant that leaves a "trail" of pixels along the drag path,
 * built from the moved layer's content (like using the layer as a brush). If
 * there is a selection, only the selected pixels are used, as a temporary
 * virtual layer composited back onto the original image.
 * <p>
 * Three modes:
 * <ul>
 *   <li><b>Live Transform</b> — every drag event rebuilds the trail from the
 *       drag-start snapshot. Backward motion "rewinds" the trail; the final
 *       state only reflects start-to-current delta. Best for nudging UI mockups.</li>
 *   <li><b>Brush</b> — at the start of the drag, snapshots the layer; that
 *       snapshot is the brush and never changes. Each integer sub-step along
 *       the actual drag path stamps the snapshot onto the layer's growing
 *       buffer. Backward motion accumulates rather than rewinding.</li>
 *   <li><b>Self Brush</b> — like Brush, but each step uses the layer's
 *       <i>current</i> image as the source (so the brush smears as you drag).</li>
 * </ul>
 */
public class TrailMoveTool extends DragTool {
    static final String SELECTED_PIXELS_EDIT_NAME = "Trail Move Selected Pixels";

    public enum Mode {
        LIVE_TRANSFORM("Live Transform"),
        BRUSH("Brush"),
        SELF_BRUSH("Self Brush");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final JComboBox<Mode> modeSelector = new JComboBox<>(Mode.values());

    private TrailSession session;
    private boolean temporaryRectangleSelection;
    private boolean temporarySelectionGesture;
    private boolean restoreTrailMoveOnMouseRelease;

    private static final class TrailSession {
        final ImageLayer layer;
        final BufferedImage initialImage;
        final BufferedImage origImage;
        final int origTx;
        final int origTy;
        final Mode mode;
        final Selection selection;
        final SelectedPixels selectedPixels;
        int prevAccumDx;
        int prevAccumDy;
        int currentDx;
        int currentDy;
        boolean accumulatedChange;

        TrailSession(ImageLayer layer, BufferedImage initialImage,
                     BufferedImage origImage, int origTx, int origTy,
                     Mode mode, Selection selection, SelectedPixels selectedPixels) {
            this.layer = layer;
            this.initialImage = initialImage;
            this.origImage = origImage;
            this.origTx = origTx;
            this.origTy = origTy;
            this.mode = mode;
            this.selection = selection;
            this.selectedPixels = selectedPixels;
        }

        boolean hasSelection() {
            return selection != null;
        }

        boolean imageChanged() {
            return mode == Mode.LIVE_TRANSFORM
                ? currentDx != 0 || currentDy != 0
                : accumulatedChange;
        }
    }

    /**
     * A selection-shaped pixel snapshot and its canvas-space origin.
     */
    record SelectedPixels(BufferedImage image, int canvasX, int canvasY) {
    }

    public TrailMoveTool() {
        this(Mode.LIVE_TRANSFORM);
    }

    TrailMoveTool(Mode initialMode) {
        super("Trail Move", 'Y',
            "<b>drag</b> to trail-move the selection, or the active image layer when there is no selection. " +
                "<b>arrow keys</b> nudge with a trail step. " +
                "Hold <b>Ctrl</b> for temporary Rectangle Selection.",
            Cursors.MOVE, true);
        modeSelector.setSelectedItem(initialMode);
    }

    @Override
    public void initSettingsPanel(ResourceBundle resources) {
        settingsPanel.addComboBox("Mode:", modeSelector, "trailModeSelector");
    }

    private Mode getMode() {
        return (Mode) modeSelector.getSelectedItem();
    }

    @Override
    public void controlPressed() {
        if (temporaryRectangleSelection) {
            // Ctrl might have been released and pressed again during a selection drag.
            restoreTrailMoveOnMouseRelease = false;
            return;
        }
        if (Tools.MouseDispatcher.isMouseDown()) {
            return;
        }

        temporaryRectangleSelection = true;
        restoreTrailMoveOnMouseRelease = false;
        Views.setCursorForAll(Tools.RECTANGLE_SELECTION.getStartingCursor());
        Messages.showStatusMessage("Temporary "
            + Tools.RECTANGLE_SELECTION.getStatusBarMessage());
    }

    @Override
    public void controlReleased() {
        if (!temporaryRectangleSelection) {
            return;
        }

        if (temporarySelectionGesture && Tools.MouseDispatcher.isMouseDown()) {
            // Let an in-progress selection finish before restoring Trail Move.
            restoreTrailMoveOnMouseRelease = true;
        } else {
            restoreTrailMove();
        }
    }

    @Override
    public void mousePressed(PMouseEvent e) {
        if (temporaryRectangleSelection) {
            temporarySelectionGesture = true;
            Tools.RECTANGLE_SELECTION.mousePressed(e);
        } else {
            super.mousePressed(e);
        }
    }

    @Override
    public void mouseDragged(PMouseEvent e) {
        if (temporarySelectionGesture) {
            Tools.RECTANGLE_SELECTION.mouseDragged(e);
        } else {
            super.mouseDragged(e);
        }
    }

    @Override
    public void mouseReleased(PMouseEvent e) {
        if (temporarySelectionGesture) {
            Tools.RECTANGLE_SELECTION.mouseReleased(e);
            temporarySelectionGesture = false;
            if (restoreTrailMoveOnMouseRelease) {
                restoreTrailMove();
            }
        } else {
            super.mouseReleased(e);
        }
    }

    @Override
    public void paintOverCanvas(Graphics2D g2, Composition comp) {
        if (temporarySelectionGesture) {
            Tools.RECTANGLE_SELECTION.paintOverCanvas(g2, comp);
        } else {
            super.paintOverCanvas(g2, comp);
        }
    }

    @Override
    public void escPressed() {
        if (temporarySelectionGesture) {
            Tools.RECTANGLE_SELECTION.escPressed();
        } else {
            super.escPressed();
        }
    }

    private void restoreTrailMove() {
        temporaryRectangleSelection = false;
        temporarySelectionGesture = false;
        restoreTrailMoveOnMouseRelease = false;
        Views.setCursorForAll(getStartingCursor());
        Messages.showStatusMessage(getStatusBarMessage());
    }

    boolean isTemporaryRectangleSelection() {
        return temporaryRectangleSelection;
    }

    boolean isRestorePending() {
        return restoreTrailMoveOnMouseRelease;
    }

    @Override
    public boolean arrowKeyPressed(ArrowKey key) {
        if (temporaryRectangleSelection) {
            return Tools.RECTANGLE_SELECTION.arrowKeyPressed(key);
        }
        return trailArrowKeyPressed(key);
    }

    @Override
    protected void dragStarted(PMouseEvent e) {
        Layer active = e.getComp().getActiveLayer();
        if (!(active instanceof ImageLayer layer)) {
            drag.cancel();
            return;
        }

        BufferedImage initialImage = layer.getImage();
        BufferedImage snapshot = ImageUtils.copyImage(initialImage);
        Selection selection = e.getComp().getSelection();
        SelectedPixels selectedPixels = null;
        if (selection != null) {
            selectedPixels = snapshotSelectedPixels(
                snapshot, selection.getShape(), layer.getTx(), layer.getTy());
            selection.prepareForTransform();
        }

        session = new TrailSession(layer, initialImage, snapshot,
            layer.getTx(), layer.getTy(), getMode(), selection, selectedPixels);
    }

    @Override
    protected void ongoingDrag(PMouseEvent e) {
        if (session == null) {
            return;
        }
        int totalDx = (int) Math.round(drag.getDX());
        int totalDy = (int) Math.round(drag.getDY());
        if (totalDx == session.currentDx && totalDy == session.currentDy) {
            return;
        }

        if (session.hasSelection()) {
            moveSelectedPixels(totalDx, totalDy);
        } else {
            moveWholeLayer(totalDx, totalDy);
        }

        session.currentDx = totalDx;
        session.currentDy = totalDy;
        session.layer.getComp().update();
    }

    private void moveSelectedPixels(int totalDx, int totalDy) {
        switch (session.mode) {
            case LIVE_TRANSFORM -> {
                if (totalDx == 0 && totalDy == 0) {
                    session.layer.setImage(session.initialImage);
                } else {
                    stampSelectedPixelsAlongSegment(session.layer, session.origImage,
                        session.selectedPixels, 0, 0, totalDx, totalDy);
                }
            }
            case BRUSH -> {
                stampSelectedPixelsAlongSegment(session.layer, session.layer.getImage(),
                    session.selectedPixels,
                    session.prevAccumDx, session.prevAccumDy, totalDx, totalDy);
                session.accumulatedChange = true;
                updatePreviousDelta(totalDx, totalDy);
            }
            case SELF_BRUSH -> {
                int stepDx = totalDx - session.prevAccumDx;
                int stepDy = totalDy - session.prevAccumDy;
                SelectedPixels currentPixels = snapshotSelectedPixels(
                    session.layer.getImage(), session.selection.getShape(),
                    session.origTx, session.origTy);
                stampSelectedPixelsAlongSegment(session.layer, session.layer.getImage(),
                    currentPixels, 0, 0, stepDx, stepDy);
                session.accumulatedChange = true;
                updatePreviousDelta(totalDx, totalDy);
            }
        }

        session.selection.moveWhileDragging(totalDx, totalDy);
    }

    private void moveWholeLayer(int totalDx, int totalDy) {
        switch (session.mode) {
            case LIVE_TRANSFORM -> {
                if (totalDx == 0 && totalDy == 0) {
                    session.layer.setTranslation(session.origTx, session.origTy);
                    session.layer.setImage(session.initialImage);
                } else {
                    // rebuild from snapshot every event (start-to-current straight line);
                    // forward/backward motion both reflected
                    session.layer.applyTrailMove(
                        session.origImage, session.origTx, session.origTy, totalDx, totalDy);
                }
            }
            case BRUSH -> {
                // commit per step: brush is the immutable snapshot, stamped at every
                // integer sub-position along the actual drag path
                session.layer.applyBrushSegment(
                    session.origImage,
                    session.origTx, session.origTy,
                    session.prevAccumDx, session.prevAccumDy,
                    totalDx, totalDy);
                session.accumulatedChange = true;
                updatePreviousDelta(totalDx, totalDy);
            }
            case SELF_BRUSH -> {
                // commit per step: source is the layer's current (smearing) image
                int stepDx = totalDx - session.prevAccumDx;
                int stepDy = totalDy - session.prevAccumDy;
                session.layer.applyTrailMove(
                    session.layer.getImage(),
                    session.layer.getTx(), session.layer.getTy(),
                    stepDx, stepDy);
                session.accumulatedChange = true;
                updatePreviousDelta(totalDx, totalDy);
            }
        }
    }

    private void updatePreviousDelta(int totalDx, int totalDy) {
        session.prevAccumDx = totalDx;
        session.prevAccumDy = totalDy;
    }

    @Override
    public void dragFinished(PMouseEvent e) {
        if (session == null) {
            return;
        }
        // ensure final state reflects the most recent drag delta
        ongoingDrag(e);

        ImageLayer layer = session.layer;
        BufferedImage backup = session.origImage;
        int origTx = session.origTx;
        int origTy = session.origTy;

        boolean imageChanged = session.imageChanged();
        boolean translationChanged = layer.getTx() != origTx || layer.getTy() != origTy;
        boolean selectedMove = session.hasSelection();
        PixelitorEdit selectionEdit = finishSelectionMovement();
        session = null;

        if (!imageChanged && !translationChanged) {
            return;
        }

        PixelitorEdit layerEdit = selectedMove
            ? new ImageEdit(SELECTED_PIXELS_EDIT_NAME, layer.getComp(), layer, backup, true)
            : new ContentLayerMoveEdit(layer,
                imageChanged ? backup : null, origTx, origTy);
        PixelitorEdit edit = MultiEdit.combine(
            layerEdit, selectionEdit, SELECTED_PIXELS_EDIT_NAME);
        History.add(edit);
        layer.updateIconImage();
    }

    private PixelitorEdit finishSelectionMovement() {
        if (!session.hasSelection()) {
            return null;
        }
        if (session.currentDx == 0 && session.currentDy == 0) {
            session.selection.cancelTransform();
            return null;
        }
        return session.selection.finalizeTransform();
    }

    private boolean trailArrowKeyPressed(ArrowKey key) {
        // arrow nudges always commit per keypress; mode picks the source
        View view = Views.getActive();
        if (view == null) {
            return false;
        }
        Layer active = view.getComp().getActiveLayer();
        if (!(active instanceof ImageLayer layer)) {
            return false;
        }

        BufferedImage backup = ImageUtils.copyImage(layer.getImage());
        int origTx = layer.getTx();
        int origTy = layer.getTy();
        int dx = key.getDeltaX();
        int dy = key.getDeltaY();

        Selection selection = view.getComp().getSelection();
        PixelitorEdit selectionEdit = null;
        if (selection != null) {
            SelectedPixels selectedPixels = snapshotSelectedPixels(
                backup, selection.getShape(), origTx, origTy);
            selection.prepareForTransform();
            stampSelectedPixelsAlongSegment(layer, layer.getImage(),
                selectedPixels, 0, 0, dx, dy);
            selection.moveWhileDragging(dx, dy);
            selectionEdit = selection.finalizeTransform();
        } else if (getMode() == Mode.BRUSH) {
            // immutable snapshot brush: backup IS the snapshot
            layer.applyBrushSegment(backup, origTx, origTy, 0, 0, dx, dy);
        } else {
            // both Live Transform and Self Brush behave the same on a single
            // discrete step: extrude the layer's current edge into the trail
            layer.applyTrailMove(layer.getImage(), origTx, origTy, dx, dy);
        }
        view.getComp().update();

        PixelitorEdit layerEdit = selection == null
            ? new ContentLayerMoveEdit(layer, backup, origTx, origTy)
            : new ImageEdit(SELECTED_PIXELS_EDIT_NAME,
                view.getComp(), layer, backup, true);
        PixelitorEdit edit = MultiEdit.combine(
            layerEdit, selectionEdit, SELECTED_PIXELS_EDIT_NAME);
        History.add(edit);
        layer.updateIconImage();
        return true;
    }

    static SelectedPixels snapshotSelectedPixels(BufferedImage source, Shape selectionShape,
                                                 int layerTx, int layerTy) {
        Rectangle selectionBounds = selectionShape.getBounds();
        Rectangle layerBounds = new Rectangle(
            layerTx, layerTy, source.getWidth(), source.getHeight());
        Rectangle snapshotBounds = selectionBounds.intersection(layerBounds);
        if (snapshotBounds.isEmpty()) {
            throw new IllegalStateException("selection does not intersect the active image layer");
        }

        BufferedImage snapshot = ImageUtils.createSysCompatibleImage(snapshotBounds);
        Graphics2D g = ImageUtils.createSoftSelectionMask(
            snapshot, selectionShape, snapshotBounds.x, snapshotBounds.y);
        try {
            g.drawImage(source,
                layerTx - snapshotBounds.x,
                layerTy - snapshotBounds.y,
                null);
        } finally {
            g.dispose();
        }
        return new SelectedPixels(snapshot, snapshotBounds.x, snapshotBounds.y);
    }

    static void stampSelectedPixelsAlongSegment(ImageLayer layer, BufferedImage baseImage,
                                                SelectedPixels selectedPixels,
                                                int prevDx, int prevDy,
                                                int curDx, int curDy) {
        int stepDx = curDx - prevDx;
        int stepDy = curDy - prevDy;
        if (stepDx == 0 && stepDy == 0) {
            return;
        }

        BufferedImage result = ImageUtils.copyImage(baseImage);
        Graphics2D g = result.createGraphics();
        try {
            int subSteps = Math.max(Math.abs(stepDx), Math.abs(stepDy));
            for (int i = 1; i <= subSteps; i++) {
                int dx = prevDx + (int) Math.round((double) stepDx * i / subSteps);
                int dy = prevDy + (int) Math.round((double) stepDy * i / subSteps);
                int drawX = selectedPixels.canvasX() + dx - layer.getTx();
                int drawY = selectedPixels.canvasY() + dy - layer.getTy();
                g.drawImage(selectedPixels.image(), drawX, drawY, null);
            }
        } finally {
            g.dispose();
        }
        layer.setImage(result);
    }

    @Override
    public boolean supportsUserPresets() {
        return false;
    }

    @Override
    public void saveStateTo(UserPreset preset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadUserPreset(UserPreset preset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Consumer<Graphics2D> createIconPainter() {
        // reuse the move icon for now; can be customized later
        return ToolIcons::paintMoveIcon;
    }
}
