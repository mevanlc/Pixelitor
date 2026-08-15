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

import java.awt.Shape;
import java.awt.geom.Area;

/**
 * Defines the available operations for combining
 * a new selection with an existing one.
 */
public enum SelectionCombinator {
    REPLACE("Replace") {
        @Override
        Shape combineShapes(Shape existing, Shape incoming) {
            // discards the previously selected area
            return incoming;
        }

        @Override
        SelectionMask combineMasks(SelectionMask existing, SelectionMask incoming) {
            return incoming;
        }
    }, ADD("Add") {
        @Override
        Shape combineShapes(Shape existing, Shape incoming) {
            // adds the new selection area to the existing one
            Area combinedArea = new Area(existing);
            combinedArea.add(new Area(incoming));
            return combinedArea;
        }

        @Override
        SelectionMask combineMasks(SelectionMask existing, SelectionMask incoming) {
            if (existing == null) {
                return incoming;
            }
            return incoming == null ? existing : existing.union(incoming);
        }
    }, SUBTRACT("Subtract") {
        @Override
        Shape combineShapes(Shape existing, Shape incoming) {
            // removes the new selection area from the existing one
            Area remainingArea = new Area(existing);
            remainingArea.subtract(new Area(incoming));
            return remainingArea;
        }

        @Override
        SelectionMask combineMasks(SelectionMask existing, SelectionMask incoming) {
            if (existing == null) {
                return null;
            }
            return incoming == null ? existing : existing.subtract(incoming);
        }
    }, INTERSECT("Intersect") {
        @Override
        Shape combineShapes(Shape existing, Shape incoming) {
            // keeps only the areas common to both selections
            Area commonArea = new Area(existing);
            commonArea.intersect(new Area(incoming));
            return commonArea;
        }

        @Override
        SelectionMask combineMasks(SelectionMask existing, SelectionMask incoming) {
            if (existing == null || incoming == null) {
                return null;
            }
            return existing.intersect(incoming);
        }
    };

    private final String displayName;

    SelectionCombinator(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Combines two selections according to this combination mode,
     * returning null if nothing is selected as a result.
     * <p>
     * Two vector-backed operands are combined exactly, keeping the
     * selection resolution-independent. As soon as either operand is
     * mask-backed, the coverage becomes authoritative and every
     * sample is combined byte by byte.
     */
    public SelectionData combine(SelectionData existing, SelectionData incoming, Canvas canvas) {
        if (this == REPLACE) {
            return incoming;
        }
        if (existing.isMaskBacked() || incoming.isMaskBacked()) {
            return combineAsMasks(existing, incoming, canvas);
        }

        Shape combined = combineShapes(existing.getOutline(), incoming.getOutline());
        if (combined.getBounds().isEmpty()) {
            return null;
        }
        return SelectionData.forShape(combined);
    }

    private SelectionData combineAsMasks(SelectionData existing, SelectionData incoming, Canvas canvas) {
        SelectionMask combined = combineMasks(
            existing.materializeMask(canvas),
            incoming.materializeMask(canvas));
        return combined == null ? null : SelectionData.forMask(combined);
    }

    abstract Shape combineShapes(Shape existing, Shape incoming);

    abstract SelectionMask combineMasks(SelectionMask existing, SelectionMask incoming);

    @Override
    public String toString() {
        return displayName;
    }

    public String getHistoryName() {
        return displayName + " Selection";
    }
}
