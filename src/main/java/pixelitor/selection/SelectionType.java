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

import pixelitor.tools.selection.MagicWandSelectionTool;
import pixelitor.tools.util.Drag;
import pixelitor.tools.util.PMouseEvent;

import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * The different ways a selection can be created or updated interactively.
 * Depending on the tool's interaction model (drag-based vs. click-based),
 * each constant implements either createFromDrag or createFromEvent,
 * throwing an exception for the unsupported interaction type.
 */
public enum SelectionType {
    RECTANGLE("Rectangle") {
        @Override
        public SelectionData createFromDrag(Drag drag, SelectionData currentData) {
            // ignores currentData, always creates a new rectangle from the drag
            return SelectionData.forShape(drag.toPosImRect());
        }

        @Override
        public SelectionData createFromEvent(PMouseEvent e, SelectionData currentData) {
            throw new UnsupportedOperationException("Rectangle selection uses Drag info");
        }
    }, ELLIPSE("Ellipse") {
        @Override
        public SelectionData createFromDrag(Drag drag, SelectionData currentData) {
            // ignores currentData, always creates a new ellipse from the drag
            Rectangle2D r = drag.toPosImRect();
            return SelectionData.forShape(new Ellipse2D.Double(
                r.getX(), r.getY(), r.getWidth(), r.getHeight()));
        }

        @Override
        public SelectionData createFromEvent(PMouseEvent e, SelectionData currentData) {
            throw new UnsupportedOperationException("Ellipse selection uses Drag info");
        }
    }, FREEHAND("Freehand") {
        @Override
        public SelectionData createFromDrag(Drag drag, SelectionData currentData) {
            if (getOutline(currentData) instanceof Path2D path) {
                // extend the existing path
                path.lineTo(drag.getEndX(), drag.getEndY());
                return SelectionData.forShape(path);
            } else {
                // start a new path
                Path2D p = new Path2D.Double();
                p.moveTo(drag.getStartX(), drag.getStartY());
                p.lineTo(drag.getEndX(), drag.getEndY());
                return SelectionData.forShape(p);
            }
        }

        @Override
        public SelectionData createFromEvent(PMouseEvent e, SelectionData currentData) {
            throw new UnsupportedOperationException("Freehand uses Drag info");
        }
    }, POLYGONAL("Polygonal") {
        @Override
        public SelectionData createFromDrag(Drag drag, SelectionData currentData) {
            throw new UnsupportedOperationException("Polygonal uses PMouseEvent info");
        }

        @Override
        public SelectionData createFromEvent(PMouseEvent e, SelectionData currentData) {
            if (getOutline(currentData) instanceof Path2D path) {
                // extend the existing path
                path.lineTo(e.getImX(), e.getImY());
                return SelectionData.forShape(path);
            } else {
                // start a new path
                Path2D p = new Path2D.Double();
                p.moveTo(e.getImX(), e.getImY());
                // first point only defines the start, no line yet
                return SelectionData.forShape(p);
            }
        }
    }, MAGIC_WAND("Magic Wand") {
        @Override
        public SelectionData createFromDrag(Drag drag, SelectionData currentData) {
            throw new UnsupportedOperationException("Magic Wand uses PMouseEvent info");
        }

        @Override
        public SelectionData createFromEvent(PMouseEvent e, SelectionData currentData) {
            // ignores currentData; the flood-filled pixels are kept
            // as hard coverage instead of being traced into a path
            SelectionMask mask = MagicWandSelectionTool.createSelectionMask(e);
            return mask == null
                ? SelectionData.forShape(new Path2D.Double()) // nothing selected
                : SelectionData.forMask(mask);
        }
    };

    private final String displayName;

    SelectionType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Creates or updates selection data based on drag input.
     * Some tools (like Marquee and Freehand) primarily provide drag
     * information (start/end points) encapsulated in a `Drag` object.
     */
    public abstract SelectionData createFromDrag(Drag drag, SelectionData currentData);

    /**
     * Creates or updates selection data based on mouse event input.
     * Some tools (like Polygonal and Magic Wand) primarily operate
     * based on individual mouse events.
     */
    public abstract SelectionData createFromEvent(PMouseEvent e, SelectionData currentData);

    /**
     * Returns the outline of the draft selection being built, or null
     * if there is none yet. The freehand selection types extend the
     * returned path in place while the drag is in progress.
     */
    private static Shape getOutline(SelectionData currentData) {
        return currentData == null ? null : currentData.getOutline();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
