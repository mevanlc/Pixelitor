/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.Serial;
import java.util.Arrays;

/**
 * A quad-to-quad homography. Destination corners are ordered NW, NE, SE, SW.
 */
public final class ProjectiveMapping implements TransformMapping {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final double EPSILON = 1.0e-9;

    private final Rectangle2D sourceBounds;
    private final double[] destination;
    private final double[] forward;
    private final double[] inverse;
    private final Rectangle2D destinationBounds;

    public ProjectiveMapping(Rectangle2D sourceBounds, Point2D... destinationCorners) {
        if (sourceBounds == null || sourceBounds.isEmpty()) {
            throw new IllegalArgumentException("The source bounds must be non-empty");
        }
        if (destinationCorners.length != 4) {
            throw new IllegalArgumentException("Exactly four destination corners are required");
        }
        this.sourceBounds = (Rectangle2D) sourceBounds.clone();
        destination = flatten(destinationCorners);
        validateQuad(destination);

        double[] source = rectangleCorners(sourceBounds);
        forward = solveHomography(source, destination);
        inverse = solveHomography(destination, source);
        destinationBounds = boundsOf(destination);
    }

    public Point2D[] destinationCorners() {
        return points(destination);
    }

    @Override
    public Rectangle2D sourceBounds() {
        return (Rectangle2D) sourceBounds.clone();
    }

    @Override
    public Rectangle2D destinationBounds() {
        return (Rectangle2D) destinationBounds.clone();
    }

    @Override
    public Point2D mapSourceToDestination(Point2D source) {
        return apply(forward, source);
    }

    @Override
    public Point2D mapDestinationToSource(Point2D destinationPoint) {
        return apply(inverse, destinationPoint);
    }

    @Override
    public boolean isIdentity() {
        return Arrays.equals(destination, rectangleCorners(sourceBounds));
    }

    private static double[] flatten(Point2D[] points) {
        double[] values = new double[8];
        for (int i = 0; i < 4; i++) {
            values[2 * i] = points[i].getX();
            values[2 * i + 1] = points[i].getY();
            if (!Double.isFinite(values[2 * i]) || !Double.isFinite(values[2 * i + 1])) {
                throw new IllegalArgumentException("Corner coordinates must be finite");
            }
        }
        return values;
    }

    private static Point2D[] points(double[] values) {
        Point2D[] result = new Point2D[4];
        for (int i = 0; i < 4; i++) {
            result[i] = new Point2D.Double(values[2 * i], values[2 * i + 1]);
        }
        return result;
    }

    private static double[] rectangleCorners(Rectangle2D r) {
        return new double[]{
            r.getMinX(), r.getMinY(),
            r.getMaxX(), r.getMinY(),
            r.getMaxX(), r.getMaxY(),
            r.getMinX(), r.getMaxY()
        };
    }

    private static void validateQuad(double[] q) {
        Point2D[] p = points(q);
        if (Line2D.linesIntersect(p[0].getX(), p[0].getY(), p[1].getX(), p[1].getY(),
            p[2].getX(), p[2].getY(), p[3].getX(), p[3].getY())
            || Line2D.linesIntersect(p[1].getX(), p[1].getY(), p[2].getX(), p[2].getY(),
            p[3].getX(), p[3].getY(), p[0].getX(), p[0].getY())) {
            throw new IllegalArgumentException("The destination quadrilateral intersects itself");
        }

        double orientation = 0.0;
        for (int i = 0; i < 4; i++) {
            Point2D a = p[i];
            Point2D b = p[(i + 1) % 4];
            Point2D c = p[(i + 2) % 4];
            double cross = (b.getX() - a.getX()) * (c.getY() - b.getY())
                - (b.getY() - a.getY()) * (c.getX() - b.getX());
            if (Math.abs(cross) < EPSILON) {
                throw new IllegalArgumentException("The destination quadrilateral has a flat corner");
            }
            if (orientation == 0.0) {
                orientation = Math.signum(cross);
            } else if (Math.signum(cross) != orientation) {
                throw new IllegalArgumentException("The destination quadrilateral must be convex");
            }
        }

        double twiceArea = 0.0;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            twiceArea += q[2 * i] * q[2 * next + 1] - q[2 * next] * q[2 * i + 1];
        }
        if (Math.abs(twiceArea) < EPSILON) {
            throw new IllegalArgumentException("The destination quadrilateral has zero area");
        }
    }

    private static Rectangle2D boundsOf(double[] q) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < q.length; i += 2) {
            minX = Math.min(minX, q[i]);
            minY = Math.min(minY, q[i + 1]);
            maxX = Math.max(maxX, q[i]);
            maxY = Math.max(maxY, q[i + 1]);
        }
        return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    /** Returns coefficients a..h for (ax+by+c)/(gx+hy+1). */
    private static double[] solveHomography(double[] source, double[] destination) {
        double[][] matrix = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = source[2 * i];
            double y = source[2 * i + 1];
            double u = destination[2 * i];
            double v = destination[2 * i + 1];
            int r = 2 * i;

            matrix[r][0] = x;
            matrix[r][1] = y;
            matrix[r][2] = 1;
            matrix[r][6] = -u * x;
            matrix[r][7] = -u * y;
            matrix[r][8] = u;

            matrix[r + 1][3] = x;
            matrix[r + 1][4] = y;
            matrix[r + 1][5] = 1;
            matrix[r + 1][6] = -v * x;
            matrix[r + 1][7] = -v * y;
            matrix[r + 1][8] = v;
        }

        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int row = col + 1; row < 8; row++) {
                if (Math.abs(matrix[row][col]) > Math.abs(matrix[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(matrix[pivot][col]) < EPSILON) {
                throw new IllegalArgumentException("The projective transform is singular");
            }
            double[] tmp = matrix[col];
            matrix[col] = matrix[pivot];
            matrix[pivot] = tmp;

            double divisor = matrix[col][col];
            for (int j = col; j <= 8; j++) {
                matrix[col][j] /= divisor;
            }
            for (int row = 0; row < 8; row++) {
                if (row == col) {
                    continue;
                }
                double factor = matrix[row][col];
                for (int j = col; j <= 8; j++) {
                    matrix[row][j] -= factor * matrix[col][j];
                }
            }
        }

        double[] result = new double[8];
        for (int i = 0; i < 8; i++) {
            result[i] = matrix[i][8];
        }
        return result;
    }

    private static Point2D apply(double[] h, Point2D point) {
        double x = point.getX();
        double y = point.getY();
        double denominator = h[6] * x + h[7] * y + 1.0;
        if (Math.abs(denominator) < EPSILON) {
            return new Point2D.Double(Double.NaN, Double.NaN);
        }
        return new Point2D.Double(
            (h[0] * x + h[1] * y + h[2]) / denominator,
            (h[3] * x + h[4] * y + h[5]) / denominator);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ProjectiveMapping other
            && sourceBounds.equals(other.sourceBounds)
            && Arrays.equals(destination, other.destination);
    }

    @Override
    public int hashCode() {
        return 31 * sourceBounds.hashCode() + Arrays.hashCode(destination);
    }
}
