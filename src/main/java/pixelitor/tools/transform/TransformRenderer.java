/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import pixelitor.utils.ImageUtils;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** Bounds-checked inverse rasterizer for projective and mesh transforms. */
public final class TransformRenderer {
    public static final long MAX_OUTPUT_PIXELS = 100_000_000L;
    public static final int MAX_OUTPUT_DIMENSION = 100_000;

    private TransformRenderer() {
    }

    public static Result render(BufferedImage source,
                                int sourceTx,
                                int sourceTy,
                                Rectangle2D sourceBounds,
                                Rectangle canvasBounds,
                                TransformMapping mapping) {
        Rectangle2D transformed = mapping.destinationBounds();
        double minXValue = Math.min(transformed.getMinX(), canvasBounds.getMinX());
        double minYValue = Math.min(transformed.getMinY(), canvasBounds.getMinY());
        double maxXValue = Math.max(transformed.getMaxX(), canvasBounds.getMaxX());
        double maxYValue = Math.max(transformed.getMaxY(), canvasBounds.getMaxY());
        if (!Double.isFinite(minXValue) || !Double.isFinite(minYValue)
            || !Double.isFinite(maxXValue) || !Double.isFinite(maxYValue)) {
            throw new IllegalArgumentException("Non-finite transform bounds");
        }

        int minX = checkedFloor(minXValue);
        int minY = checkedFloor(minYValue);
        int maxX = checkedCeil(maxXValue);
        int maxY = checkedCeil(maxYValue);
        int width = checkedDimension((long) maxX - minX);
        int height = checkedDimension((long) maxY - minY);
        if ((long) width * height > MAX_OUTPUT_PIXELS) {
            throw new IllegalArgumentException("The transformed image is too large");
        }

        BufferedImage output = ImageUtils.createSysCompatibleImage(width, height);
        int rasterMinX = Math.max(minX, checkedFloor(transformed.getMinX()));
        int rasterMinY = Math.max(minY, checkedFloor(transformed.getMinY()));
        int rasterMaxX = Math.min(maxX, checkedCeil(transformed.getMaxX()));
        int rasterMaxY = Math.min(maxY, checkedCeil(transformed.getMaxY()));

        for (int canvasY = rasterMinY; canvasY < rasterMaxY; canvasY++) {
            for (int canvasX = rasterMinX; canvasX < rasterMaxX; canvasX++) {
                Point2D srcCanvas = mapping.mapDestinationToSource(
                    new Point2D.Double(canvasX, canvasY));
                double srcCanvasX = srcCanvas.getX();
                double srcCanvasY = srcCanvas.getY();
                if (!Double.isFinite(srcCanvasX) || !Double.isFinite(srcCanvasY)
                    || srcCanvasX < sourceBounds.getMinX() - 1.0e-7
                    || srcCanvasY < sourceBounds.getMinY() - 1.0e-7
                    || srcCanvasX > sourceBounds.getMaxX() - 1.0 + 1.0e-7
                    || srcCanvasY > sourceBounds.getMaxY() - 1.0 + 1.0e-7) {
                    continue;
                }
                int argb = bilinearSample(source,
                    srcCanvasX - sourceTx, srcCanvasY - sourceTy);
                output.setRGB(canvasX - minX, canvasY - minY, argb);
            }
        }
        return new Result(output, minX, minY);
    }

    private static int bilinearSample(BufferedImage image, double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        if (Math.abs(fx) < 1.0e-9 && Math.abs(fy) < 1.0e-9) {
            return pixelOrTransparent(image, x0, y0);
        }

        int c00 = pixelOrTransparent(image, x0, y0);
        int c10 = pixelOrTransparent(image, x0 + 1, y0);
        int c01 = pixelOrTransparent(image, x0, y0 + 1);
        int c11 = pixelOrTransparent(image, x0 + 1, y0 + 1);
        return interpolatePremultiplied(c00, c10, c01, c11, fx, fy);
    }

    private static int pixelOrTransparent(BufferedImage image, int x, int y) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return 0;
        }
        return image.getRGB(x, y);
    }

    private static int interpolatePremultiplied(int c00, int c10, int c01, int c11,
                                                double fx, double fy) {
        double w00 = (1.0 - fx) * (1.0 - fy);
        double w10 = fx * (1.0 - fy);
        double w01 = (1.0 - fx) * fy;
        double w11 = fx * fy;
        double alpha = channel(c00, 24) * w00 + channel(c10, 24) * w10
            + channel(c01, 24) * w01 + channel(c11, 24) * w11;
        if (alpha <= 0.0) {
            return 0;
        }
        double red = premultiplied(c00, 16) * w00 + premultiplied(c10, 16) * w10
            + premultiplied(c01, 16) * w01 + premultiplied(c11, 16) * w11;
        double green = premultiplied(c00, 8) * w00 + premultiplied(c10, 8) * w10
            + premultiplied(c01, 8) * w01 + premultiplied(c11, 8) * w11;
        double blue = premultiplied(c00, 0) * w00 + premultiplied(c10, 0) * w10
            + premultiplied(c01, 0) * w01 + premultiplied(c11, 0) * w11;
        int a = clamp(alpha);
        int r = clamp(red * 255.0 / alpha);
        int g = clamp(green * 255.0 / alpha);
        int b = clamp(blue * 255.0 / alpha);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static double premultiplied(int argb, int shift) {
        return channel(argb, shift) * channel(argb, 24) / 255.0;
    }

    private static int channel(int argb, int shift) {
        return argb >>> shift & 0xFF;
    }

    private static int clamp(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static int checkedFloor(double value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Transform bounds overflow");
        }
        return (int) Math.floor(value);
    }

    private static int checkedCeil(double value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Transform bounds overflow");
        }
        return (int) Math.ceil(value);
    }

    private static int checkedDimension(long value) {
        if (value <= 0 || value > MAX_OUTPUT_DIMENSION) {
            throw new IllegalArgumentException("Invalid transformed image dimension: " + value);
        }
        return (int) value;
    }

    public record Result(BufferedImage image, int tx, int ty) {
    }
}
