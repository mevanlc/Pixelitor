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
import pixelitor.utils.Shapes;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.Objects;
import java.util.Optional;

/**
 * Selection data backed by exact vector geometry.
 * <p>
 * The shape is immutable by convention: every modification creates a new
 * instance. The raster coverage is calculated only when a pixel operation
 * needs it, which keeps ordinary vector selections resolution-independent.
 */
public final class ShapeSelectionData implements SelectionData {
    // in image-space coordinates, relative to the canvas
    private final Shape shape;

    // the lazily rasterized coverage, and the canvas it was rasterized for
    private SelectionMask mask;
    private Rectangle maskCanvasBounds;

    ShapeSelectionData(Shape shape) {
        this.shape = Objects.requireNonNull(shape);
    }

    @Override
    public Shape getOutline() {
        return shape;
    }

    @Override
    public Rectangle2D getOutlineBounds() {
        return shape.getBounds2D();
    }

    @Override
    public Rectangle getCoverageBounds() {
        // an antialiased edge can cover the pixels
        // touched by the shape, therefore the outer bounds
        return shape.getBounds();
    }

    @Override
    public Optional<Rectangle2D> getExactHardRectangle() {
        // the rectangularity is type information, and not a geometric
        // test, so that an area that happens to be rectangular
        // doesn't enable the hard-rectangle-only operations
        return shape instanceof Rectangle2D rect
            ? Optional.of(rect)
            : Optional.empty();
    }

    @Override
    public boolean containsAtLeast(double x, double y, int threshold) {
        return shape.contains(x, y);
    }

    @Override
    public synchronized SelectionMask materializeMask(Canvas canvas) {
        Rectangle canvasBounds = canvas.getBounds();
        if (mask == null || !canvasBounds.equals(maskCanvasBounds)) {
            // hard rectangles must stay hard, everything else gets
            // the antialiased edges that Pixelitor always had
            boolean antialiased = getExactHardRectangle().isEmpty();
            mask = SelectionMask.rasterize(shape, canvasBounds, antialiased);
            maskCanvasBounds = canvasBounds;
        }
        return mask;
    }

    @Override
    public SelectionData translated(double dx, double dy) {
        if (shape instanceof Rectangle2D rect) {
            // the rectangle type information is used to optimize selection
            // operations and to validate selection-based crops
            return new ShapeSelectionData(new Rectangle2D.Double(
                rect.getX() + dx, rect.getY() + dy,
                rect.getWidth(), rect.getHeight()));
        }
        return new ShapeSelectionData(Shapes.translate(shape, dx, dy));
    }

    @Override
    public SelectionData transformed(AffineTransform at) {
        return new ShapeSelectionData(at.createTransformedShape(shape));
    }

    @Override
    public SelectionData clippedTo(Canvas canvas) {
        Shape clipped = canvas.clip(shape);
        if (clipped.getBounds().isEmpty()) {
            return null;
        }
        return new ShapeSelectionData(clipped);
    }

    @Override
    public SelectionData inverted(Canvas canvas) {
        Shape inverted = canvas.invertShape(shape);
        if (inverted.getBounds2D().isEmpty()) {
            return null;
        }
        return new ShapeSelectionData(inverted);
    }

    @Override
    public boolean isEmpty() {
        return shape.getBounds().isEmpty();
    }

    @Override
    public boolean isMaskBacked() {
        return false;
    }

    @Override
    public String toString() {
        return "ShapeSelectionData[" + shape.getClass().getSimpleName()
            + ", bounds = " + shape.getBounds() + ']';
    }
}
