/*
 * $Id: Star2D.java 3863 2010-10-26 02:53:32Z kschaefe $
 *
 * Dual-licensed under LGPL (Sun and Romain Guy) and BSD (Romain Guy).
 *
 * Copyright 2005 Sun Microsystems, Inc., 4150 Network Circle,
 * Santa Clara, California 95054, U.S.A. All rights reserved.
 *
 * Copyright (c) 2006 Romain Guy <romain.guy@mac.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jdesktop.swingx.geom;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.*;

/**
 * <p>This class provides a star shape. A star is defined by two radii and a
 * number of branches. Each branch spans between the two radii. The inner
 * radius is the distance between the center of the star and the origin of the
 * branches. The outer radius is the distance between the center of the star
 * and the tips of the branches.</p>
 *
 * Modified in Pixelitor to remove the restriction that the inner radius
 * must be smaller than the outer radius (this enables polygons)
 *
 * @author Romain Guy <romain.guy@mac.com>
 */

public class Star2D implements Shape {
    private final Shape starShape;

    /**
     * <p>Creates a new star whose center is located at the specified
     * <code>x</code> and <code>y</code> coordinates. The number of branches
     * and their length can be specified.</p>
     *
     * @param x             the location of the star center
     * @param y             the location of the star center
     * @param innerRadius   the distance between the center of the star and the
     *                      origin of the branches
     * @param outerRadius   the distance between the center of the star and the
     *                      tip of the branches
     * @param branchesCount the number of branches in this star; must be &gt;= 3
     * @throws IllegalArgumentException if {@code branchesCount < 3}
     */
    public Star2D(double x, double y,
                  double innerRadius, double outerRadius,
                  int branchesCount) {
        if (branchesCount < 3) {
            throw new IllegalArgumentException("The number of branches must be >= 3.");
        }

        starShape = generateStar(x, y, innerRadius, outerRadius, branchesCount);
    }

    private static Shape generateStar(double x, double y,
                                      double innerRadius, double outerRadius,
                                      int branchesCount) {
        int pointCount = branchesCount * 2;
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO, pointCount + 1);

        double startAngle = branchesCount % 2 == 0 ? 0.0 : -(Math.PI / 2.0);
        double angleStep = Math.PI / branchesCount;

        for (int i = 0; i < pointCount; i++) {
            double angle = startAngle + i * angleStep;
            double radius = (i % 2 == 0) ? outerRadius : innerRadius;

            double px = Math.cos(angle) * radius + x;
            double py = Math.sin(angle) * radius + y;

            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }

        path.closePath();
        return path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Rectangle getBounds() {
        return starShape.getBounds();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Rectangle2D getBounds2D() {
        return starShape.getBounds2D();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(double x, double y) {
        return starShape.contains(x, y);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(Point2D p) {
        return starShape.contains(p);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean intersects(double x, double y, double w, double h) {
        return starShape.intersects(x, y, w, h);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean intersects(Rectangle2D r) {
        return starShape.intersects(r);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(double x, double y, double w, double h) {
        return starShape.contains(x, y, w, h);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(Rectangle2D r) {
        return starShape.contains(r);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PathIterator getPathIterator(AffineTransform at) {
        return starShape.getPathIterator(at);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PathIterator getPathIterator(AffineTransform at, double flatness) {
        return starShape.getPathIterator(at, flatness);
    }
}
