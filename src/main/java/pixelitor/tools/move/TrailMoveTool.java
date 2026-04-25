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

import pixelitor.Views;
import pixelitor.filters.gui.UserPreset;
import pixelitor.gui.View;
import pixelitor.history.ContentLayerMoveEdit;
import pixelitor.history.History;
import pixelitor.layers.ImageLayer;
import pixelitor.layers.Layer;
import pixelitor.tools.DragTool;
import pixelitor.tools.ToolIcons;
import pixelitor.tools.util.ArrowKey;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Cursors;
import pixelitor.utils.ImageUtils;

import javax.swing.*;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * A move tool variant that leaves a "trail" of pixels along the drag path,
 * built from the moved layer's content (like using the layer as a brush).
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

    private static final class TrailSession {
        final ImageLayer layer;
        final BufferedImage origImage;
        final int origTx;
        final int origTy;
        int prevAccumDx;
        int prevAccumDy;

        TrailSession(ImageLayer layer, BufferedImage origImage, int origTx, int origTy) {
            this.layer = layer;
            this.origImage = origImage;
            this.origTx = origTx;
            this.origTy = origTy;
        }
    }

    public TrailMoveTool() {
        super("Trail Move", 'Y',
            "<b>drag</b> to move the active image layer, leaving a trail of pixels along the drag path. " +
                "<b>arrow keys</b> nudge with a trail step.",
            Cursors.MOVE, true);
    }

    @Override
    public void initSettingsPanel(ResourceBundle resources) {
        settingsPanel.addComboBox("Mode:", modeSelector, "trailModeSelector");
    }

    private Mode getMode() {
        return (Mode) modeSelector.getSelectedItem();
    }

    @Override
    protected void dragStarted(PMouseEvent e) {
        Layer active = e.getComp().getActiveLayer();
        if (!(active instanceof ImageLayer layer)) {
            drag.cancel();
            return;
        }
        BufferedImage snapshot = ImageUtils.copyImage(layer.getImage());
        session = new TrailSession(layer, snapshot, layer.getTx(), layer.getTy());
    }

    @Override
    protected void ongoingDrag(PMouseEvent e) {
        if (session == null) {
            return;
        }
        int totalDx = (int) Math.round(drag.getDX());
        int totalDy = (int) Math.round(drag.getDY());

        Mode mode = getMode();
        switch (mode) {
            case LIVE_TRANSFORM ->
                // rebuild from snapshot every event (start-to-current straight line);
                // forward/backward motion both reflected
                session.layer.applyTrailMove(
                    session.origImage, session.origTx, session.origTy, totalDx, totalDy);
            case BRUSH -> {
                // commit per step: brush is the immutable snapshot, stamped at every
                // integer sub-position along the actual drag path
                if (totalDx != session.prevAccumDx || totalDy != session.prevAccumDy) {
                    session.layer.applyBrushSegment(
                        session.origImage,
                        session.origTx, session.origTy,
                        session.prevAccumDx, session.prevAccumDy,
                        totalDx, totalDy);
                    session.prevAccumDx = totalDx;
                    session.prevAccumDy = totalDy;
                }
            }
            case SELF_BRUSH -> {
                // commit per step: source is the layer's current (smearing) image
                int stepDx = totalDx - session.prevAccumDx;
                int stepDy = totalDy - session.prevAccumDy;
                if (stepDx != 0 || stepDy != 0) {
                    session.layer.applyTrailMove(
                        session.layer.getImage(),
                        session.layer.getTx(), session.layer.getTy(),
                        stepDx, stepDy);
                    session.prevAccumDx = totalDx;
                    session.prevAccumDy = totalDy;
                }
            }
        }
        session.layer.getComp().update();
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
        session = null;

        boolean imageChanged = layer.getImage() != backup;
        boolean translationChanged = layer.getTx() != origTx || layer.getTy() != origTy;
        if (!imageChanged && !translationChanged) {
            return;
        }

        History.add(new ContentLayerMoveEdit(layer,
            imageChanged ? backup : null, origTx, origTy));
        layer.updateIconImage();
    }

    @Override
    public boolean arrowKeyPressed(ArrowKey key) {
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

        if (getMode() == Mode.BRUSH) {
            // immutable snapshot brush: backup IS the snapshot
            layer.applyBrushSegment(backup, origTx, origTy, 0, 0, dx, dy);
        } else {
            // both Live Transform and Self Brush behave the same on a single
            // discrete step: extrude the layer's current edge into the trail
            layer.applyTrailMove(layer.getImage(), origTx, origTy, dx, dy);
        }
        view.getComp().update();

        History.add(new ContentLayerMoveEdit(layer, backup, origTx, origTy));
        layer.updateIconImage();
        return true;
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
