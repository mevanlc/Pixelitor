/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.Serial;
import java.util.Arrays;

/** A deterministic 4-by-4 mesh warp (3-by-3 cells). */
public final class WarpMapping implements TransformMapping {
    @Serial
    private static final long serialVersionUID = 1L;
    public static final int GRID_SIZE = 4;
    private static final double EPSILON = 1.0e-8;

    private final Rectangle2D sourceBounds;
    private final WarpStyle style;
    private final double[] mesh;
    private final Rectangle2D destinationBounds;

    public WarpMapping(Rectangle2D sourceBounds, WarpStyle style, Point2D[] controlPoints) {
        if (sourceBounds == null || sourceBounds.isEmpty()) {
            throw new IllegalArgumentException("The source bounds must be non-empty");
        }
        if (controlPoints.length != GRID_SIZE * GRID_SIZE) {
            throw new IllegalArgumentException("A warp requires 16 control points");
        }
        this.sourceBounds = (Rectangle2D) sourceBounds.clone();
        this.style = style;
        mesh = new double[controlPoints.length * 2];
        for (int i = 0; i < controlPoints.length; i++) {
            mesh[2 * i] = controlPoints[i].getX();
            mesh[2 * i + 1] = controlPoints[i].getY();
            if (!Double.isFinite(mesh[2 * i]) || !Double.isFinite(mesh[2 * i + 1])) {
                throw new IllegalArgumentException("Warp coordinates must be finite");
            }
        }
        validateTriangles();
        destinationBounds = calculateBounds();
    }

    public static WarpMapping create(Rectangle2D sourceBounds, Point2D[] quad, WarpStyle style) {
        Point2D[] points = new Point2D[GRID_SIZE * GRID_SIZE];
        double width = sourceBounds.getWidth();
        double height = sourceBounds.getHeight();
        for (int row = 0; row < GRID_SIZE; row++) {
            double v = row / 3.0;
            for (int col = 0; col < GRID_SIZE; col++) {
                double u = col / 3.0;
                Point2D p = bilinearQuad(quad, u, v);
                double nx = u * 2.0 - 1.0;
                double ny = v * 2.0 - 1.0;
                double dx = 0.0;
                double dy = 0.0;
                switch (style) {
                    case ARC -> dy = -0.18 * height * (1.0 - nx * nx);
                    case ARCH -> dy = -0.22 * height * (1.0 - Math.abs(nx));
                    case BULGE -> {
                        double factor = (1.0 - nx * nx) * (1.0 - ny * ny);
                        dx = 0.12 * width * nx * factor;
                        dy = 0.12 * height * ny * factor;
                    }
                    case FLAG -> dy = 0.10 * height * Math.sin(Math.PI * nx);
                    case WAVE -> dy = 0.12 * height * Math.sin(2.0 * Math.PI * u);
                    case CUSTOM -> {
                        // regular mesh
                    }
                }
                points[row * GRID_SIZE + col] = new Point2D.Double(p.getX() + dx, p.getY() + dy);
            }
        }
        return new WarpMapping(sourceBounds, style, points);
    }

    public WarpStyle style() {
        return style;
    }

    public Point2D[] controlPoints() {
        Point2D[] points = new Point2D[GRID_SIZE * GRID_SIZE];
        for (int i = 0; i < points.length; i++) {
            points[i] = point(i);
        }
        return points;
    }

    public WarpMapping withControlPoint(int index, Point2D position) {
        Point2D[] points = controlPoints();
        points[index] = new Point2D.Double(position.getX(), position.getY());
        return new WarpMapping(sourceBounds, WarpStyle.CUSTOM, points);
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
        double u = (source.getX() - sourceBounds.getX()) / sourceBounds.getWidth();
        double v = (source.getY() - sourceBounds.getY()) / sourceBounds.getHeight();
        int col = clampedCell(u);
        int row = clampedCell(v);
        double localU = u * 3.0 - col;
        double localV = v * 3.0 - row;
        Point2D p00 = point(row, col);
        Point2D p10 = point(row, col + 1);
        Point2D p11 = point(row + 1, col + 1);
        Point2D p01 = point(row + 1, col);
        if (localV <= localU) {
            // p00=(0,0), p10=(1,0), p11=(1,1)
            double w11 = localV;
            double w10 = localU - localV;
            double w00 = 1.0 - localU;
            return weighted(p00, w00, p10, w10, p11, w11);
        }
        // p00=(0,0), p11=(1,1), p01=(0,1)
        double w11 = localU;
        double w01 = localV - localU;
        double w00 = 1.0 - localV;
        return weighted(p00, w00, p11, w11, p01, w01);
    }

    @Override
    public Point2D mapDestinationToSource(Point2D destination) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                Point2D p00 = point(row, col);
                Point2D p10 = point(row, col + 1);
                Point2D p11 = point(row + 1, col + 1);
                Point2D p01 = point(row + 1, col);

                double[] bary = barycentric(destination, p00, p10, p11);
                if (inside(bary)) {
                    return sourcePoint(row, col,
                        bary[1] + bary[2], bary[2]);
                }
                bary = barycentric(destination, p00, p11, p01);
                if (inside(bary)) {
                    return sourcePoint(row, col,
                        bary[1], bary[1] + bary[2]);
                }
            }
        }
        return new Point2D.Double(Double.NaN, Double.NaN);
    }

    @Override
    public boolean isIdentity() {
        if (style != WarpStyle.CUSTOM) {
            return false;
        }
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                Point2D expected = sourcePoint(row, col, 0, 0);
                Point2D actual = point(row, col);
                if (expected.distanceSq(actual) > EPSILON) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int clampedCell(double value) {
        return Math.max(0, Math.min(2, (int) Math.floor(value * 3.0)));
    }

    private Point2D sourcePoint(int row, int col, double localU, double localV) {
        double u = (col + localU) / 3.0;
        double v = (row + localV) / 3.0;
        return new Point2D.Double(
            sourceBounds.getX() + u * sourceBounds.getWidth(),
            sourceBounds.getY() + v * sourceBounds.getHeight());
    }

    private Point2D point(int row, int col) {
        return point(row * GRID_SIZE + col);
    }

    private Point2D point(int index) {
        return new Point2D.Double(mesh[2 * index], mesh[2 * index + 1]);
    }

    private Rectangle2D calculateBounds() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < mesh.length; i += 2) {
            minX = Math.min(minX, mesh[i]);
            minY = Math.min(minY, mesh[i + 1]);
            maxX = Math.max(maxX, mesh[i]);
            maxY = Math.max(maxY, mesh[i + 1]);
        }
        return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    private void validateTriangles() {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                Point2D p00 = point(row, col);
                Point2D p10 = point(row, col + 1);
                Point2D p11 = point(row + 1, col + 1);
                Point2D p01 = point(row + 1, col);
                if (Math.abs(cross(p00, p10, p11)) < EPSILON
                    || Math.abs(cross(p00, p11, p01)) < EPSILON) {
                    throw new IllegalArgumentException("The warp mesh contains a degenerate triangle");
                }
            }
        }
    }

    private static Point2D bilinearQuad(Point2D[] q, double u, double v) {
        double a = (1.0 - u) * (1.0 - v);
        double b = u * (1.0 - v);
        double c = u * v;
        double d = (1.0 - u) * v;
        return new Point2D.Double(
            a * q[0].getX() + b * q[1].getX() + c * q[2].getX() + d * q[3].getX(),
            a * q[0].getY() + b * q[1].getY() + c * q[2].getY() + d * q[3].getY());
    }

    private static Point2D weighted(Point2D a, double wa,
                                    Point2D b, double wb,
                                    Point2D c, double wc) {
        return new Point2D.Double(
            wa * a.getX() + wb * b.getX() + wc * c.getX(),
            wa * a.getY() + wb * b.getY() + wc * c.getY());
    }

    private static double[] barycentric(Point2D p, Point2D a, Point2D b, Point2D c) {
        double denominator = cross(a, b, c);
        if (Math.abs(denominator) < EPSILON) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }
        double wa = cross(p, b, c) / denominator;
        double wb = cross(a, p, c) / denominator;
        return new double[]{wa, wb, 1.0 - wa - wb};
    }

    private static boolean inside(double[] bary) {
        return bary[0] >= -EPSILON && bary[1] >= -EPSILON && bary[2] >= -EPSILON;
    }

    private static double cross(Point2D a, Point2D b, Point2D c) {
        return (b.getX() - a.getX()) * (c.getY() - a.getY())
            - (b.getY() - a.getY()) * (c.getX() - a.getX());
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof WarpMapping other
            && sourceBounds.equals(other.sourceBounds)
            && style == other.style
            && Arrays.equals(mesh, other.mesh);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * sourceBounds.hashCode() + style.hashCode()) + Arrays.hashCode(mesh);
    }
}
