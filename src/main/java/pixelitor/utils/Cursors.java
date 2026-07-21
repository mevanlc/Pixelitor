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

package pixelitor.utils;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * A convenience class for keeping track of mouse cursors.
 */
public class Cursors {
    public static final Cursor MOVE = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
    public static final Cursor HAND = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    public static final Cursor DEFAULT = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
    public static final Cursor BUSY = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);
    public static final Cursor CROSSHAIR = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    public static final Cursor ROTATE = createRotateCursor();

    public static final Cursor N = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
    public static final Cursor NW = Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
    public static final Cursor W = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
    public static final Cursor SW = Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
    public static final Cursor S = Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
    public static final Cursor SE = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
    public static final Cursor E = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
    public static final Cursor NE = Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);

    private static Cursor createRotateCursor() {
        if (GraphicsEnvironment.isHeadless()) {
            return CROSSHAIR;
        }

        Toolkit toolkit = Toolkit.getDefaultToolkit();
        Dimension size = toolkit.getBestCursorSize(32, 32);
        if (size.width == 0 || size.height == 0) {
            return CROSSHAIR;
        }

        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double scale = Math.min(size.width, size.height) / 32.0;
        g.scale(scale, scale);

        Arc2D arc = new Arc2D.Double(6, 6, 20, 20, 0, 180, Arc2D.OPEN);
        Path2D arrowHeads = new Path2D.Double();
        arrowHeads.moveTo(6, 16);
        arrowHeads.lineTo(6, 10);
        arrowHeads.moveTo(6, 16);
        arrowHeads.lineTo(11, 14);
        arrowHeads.moveTo(26, 16);
        arrowHeads.lineTo(26, 10);
        arrowHeads.moveTo(26, 16);
        arrowHeads.lineTo(21, 14);

        drawCursorShape(g, arc, arrowHeads, Color.WHITE, 4.0f);
        drawCursorShape(g, arc, arrowHeads, Color.BLACK, 2.0f);
        g.dispose();

        return toolkit.createCustomCursor(image,
            new Point(size.width / 2, size.height / 2), "free-transform-rotate");
    }

    private static void drawCursorShape(Graphics2D g, Arc2D arc, Path2D arrowHeads,
                                        Color color, float width) {
        g.setColor(color);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arc);
        g.draw(arrowHeads);
    }

    private Cursors() {
    }
}
