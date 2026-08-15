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

package pixelitor.selection;

import pixelitor.Canvas;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.Objects;
import java.util.Optional;

/**
 * Selection data backed by an authoritative 8-bit coverage mask.
 * <p>
 * The outline is only the derived 50% contour: the coverage is never
 * reconstructed by filling it, because that would lose the partially
 * selected pixels outside the contour.
 */
public final class MaskSelectionData implements SelectionData {
    private final SelectionMask mask;

    MaskSelectionData(SelectionMask mask) {
        this.mask = Objects.requireNonNull(mask);
    }

    public SelectionMask getMask() {
        return mask;
    }

    @Override
    public Shape getOutline() {
        return mask.getContour();
    }

    @Override
    public Rectangle2D getOutlineBounds() {
        return mask.getContour().getBounds2D();
    }

    @Override
    public Rectangle getCoverageBounds() {
        return mask.getBounds();
    }

    @Override
    public Optional<Rectangle2D> getExactHardRectangle() {
        return Optional.empty();
    }

    @Override
    public boolean containsAtLeast(double x, double y, int threshold) {
        return mask.containsAtLeast(x, y, threshold);
    }

    @Override
    public SelectionMask materializeMask(Canvas canvas) {
        return mask;
    }

    @Override
    public SelectionData translated(double dx, double dy) {
        return wrap(mask.translated(dx, dy));
    }

    @Override
    public SelectionData transformed(AffineTransform at) {
        return wrap(mask.transformed(at));
    }

    @Override
    public SelectionData clippedTo(Canvas canvas) {
        return wrap(mask.clippedTo(canvas.getBounds()));
    }

    @Override
    public SelectionData inverted(Canvas canvas) {
        return wrap(mask.inverted(canvas.getBounds()));
    }

    private static SelectionData wrap(SelectionMask mask) {
        return mask == null ? null : new MaskSelectionData(mask);
    }

    @Override
    public boolean isEmpty() {
        return false; // a mask always has at least one nonzero sample
    }

    @Override
    public boolean isMaskBacked() {
        return true;
    }

    @Override
    public String toString() {
        return "MaskSelectionData[" + mask + ']';
    }
}
