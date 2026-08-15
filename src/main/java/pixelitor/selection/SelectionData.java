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
import java.util.Optional;

/**
 * The immutable geometry of a selection: either exact vector
 * geometry or an authoritative 8-bit coverage mask.
 * <p>
 * Because instances are immutable, they can be shared freely between
 * a live selection, the history and the selection clipboard.
 */
public sealed interface SelectionData permits ShapeSelectionData, MaskSelectionData {
    /**
     * Creates vector-backed selection data from an image-space shape.
     */
    static SelectionData forShape(Shape shape) {
        return new ShapeSelectionData(shape);
    }

    /**
     * Creates mask-backed selection data from canvas-space coverage.
     */
    static SelectionData forMask(SelectionMask mask) {
        return new MaskSelectionData(mask);
    }

    /**
     * Returns true if the given (possibly null) selection
     * data doesn't select anything.
     */
    static boolean selectsNothing(SelectionData data) {
        return data == null || data.isEmpty();
    }

    /**
     * Returns the canvas-space bounds of the nonzero coverage.
     * This is the region that pixel operations must process.
     */
    Rectangle getCoverageBounds();

    /**
     * Returns the vector outline in image-space coordinates: the exact
     * geometry of a vector selection, or the 50% contour of a mask.
     * This is what the marching ants follow, and what geometric
     * interactions (movement, transform handles, hit testing) use.
     */
    Shape getOutline();

    /**
     * Returns the bounds of the outline, which can be smaller than the
     * coverage bounds if the coverage fades out below 50%.
     */
    Rectangle2D getOutlineBounds();

    /**
     * Returns the rectangle of an exact hard rectangular selection,
     * or an empty optional for everything else. A mask is never an
     * exact hard rectangle, even if its contour happens to be one.
     */
    Optional<Rectangle2D> getExactHardRectangle();

    /**
     * Returns true if the coverage at the given image-space
     * point reaches the given threshold.
     */
    boolean containsAtLeast(double x, double y, int threshold);

    /**
     * Returns the 8-bit canvas-space coverage of this selection,
     * or null if it doesn't cover a single pixel.
     */
    SelectionMask materializeMask(Canvas canvas);

    /**
     * Returns this selection data moved by the given image-space offset.
     */
    SelectionData translated(double dx, double dy);

    /**
     * Returns this selection data transformed into new image-space
     * coordinates. Mask-backed data returns null if the transform
     * degenerates it to nothing, while vector data can return an
     * empty result, because it isn't clipped to the canvas yet.
     */
    SelectionData transformed(AffineTransform at);

    /**
     * Returns the part of this selection data inside the
     * canvas, or null if nothing is left.
     */
    SelectionData clippedTo(Canvas canvas);

    /**
     * Returns the inverse of this selection relative to the canvas
     * bounds, or null if everything was selected before.
     */
    SelectionData inverted(Canvas canvas);

    /**
     * Returns true if this selection data doesn't select anything.
     * Only transient vector data (such as a zero-sized drag) can be empty.
     */
    boolean isEmpty();

    /**
     * Returns true if the coverage is authoritative,
     * and therefore the outline is only a derived contour.
     */
    boolean isMaskBacked();
}
