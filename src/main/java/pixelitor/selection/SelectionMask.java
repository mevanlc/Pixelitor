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

import pixelitor.utils.ImageUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import static java.awt.RenderingHints.KEY_ANTIALIASING;
import static java.awt.RenderingHints.KEY_INTERPOLATION;
import static java.awt.RenderingHints.VALUE_ANTIALIAS_ON;
import static java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR;
import static java.awt.image.BufferedImage.TYPE_BYTE_GRAY;

/**
 * An immutable, tightly bounded 8-bit selection coverage tile.
 * <p>
 * Every stored sample is literal coverage from 0 (unselected) through
 * 255 (fully selected). Samples outside the stored tile are 0.
 * The tile never has zero-only outer rows or columns: a result without
 * any nonzero sample is represented as no mask at all (null), and not
 * as an empty mask object. All operations return new instances.
 */
public final class SelectionMask {
    /**
     * The coverage boundary used by the marching ants and by everything
     * else that needs a vector outline of a mask-backed selection.
     */
    public static final int CONTOUR_THRESHOLD = 128;

    // the coverage samples, row-major, width * height bytes
    private final byte[] coverage;

    private final int width;
    private final int height;

    // the canvas-space position of the tile's upper-left corner
    private final int x;
    private final int y;

    // the lazily traced 50% contour
    private Shape contour;

    /**
     * Takes ownership of the given array, which must be
     * already trimmed and must not be retained by the caller.
     */
    private SelectionMask(byte[] coverage, int width, int height, int x, int y) {
        assert coverage.length == width * height;
        assert width > 0 && height > 0;

        this.coverage = coverage;
        this.width = width;
        this.height = height;
        this.x = x;
        this.y = y;
    }

    /**
     * Creates a mask from a copy of the given coverage samples,
     * or returns null if none of them is nonzero.
     */
    public static SelectionMask fromCoverage(byte[] coverage, int width, int height, int x, int y) {
        if (coverage.length != width * height) {
            throw new IllegalArgumentException(
                "coverage.length = " + coverage.length + ", width = " + width + ", height = " + height);
        }
        return trim(coverage.clone(), width, height, x, y);
    }

    /**
     * Creates a mask from the given grayscale image, which is copied.
     * Returns null if the image is completely black.
     */
    public static SelectionMask fromGrayImage(BufferedImage gray, int x, int y) {
        return trim(ImageUtils.getGrayPixels(gray).clone(),
            gray.getWidth(), gray.getHeight(), x, y);
    }

    /**
     * Creates a mask from a freshly created grayscale image
     * whose raster can be taken over instead of copied.
     */
    private static SelectionMask adoptGrayImage(BufferedImage gray, int x, int y) {
        return trim(ImageUtils.getGrayPixels(gray),
            gray.getWidth(), gray.getHeight(), x, y);
    }

    /**
     * Creates a hard (0 or 255) mask from a boolean pixel mask
     * covering the canvas. Returns null if nothing is set.
     */
    public static SelectionMask fromBooleanMask(boolean[] selected, int width, int height) {
        assert selected.length == width * height;

        byte[] coverage = new byte[selected.length];
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) {
                coverage[i] = (byte) 255;
            }
        }
        return trim(coverage, width, height, 0, 0);
    }

    /**
     * Rasterizes a shape into canvas-space coverage, clipped to the given bounds.
     * Hard rasterization keeps every covered pixel at 255, while antialiased
     * rasterization produces the fractional coverage of the shape's edges.
     * Returns null if nothing is covered.
     */
    public static SelectionMask rasterize(Shape shape, Rectangle clipBounds, boolean antialiased) {
        Rectangle bounds = shape.getBounds();
        if (clipBounds != null) {
            bounds = bounds.intersection(clipBounds);
        }
        if (bounds.isEmpty()) {
            return null;
        }

        BufferedImage img = new BufferedImage(bounds.width, bounds.height, TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        if (antialiased) {
            g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        }
        g.setColor(Color.WHITE);
        // the shape is in canvas space, the image starts at the tile origin
        g.translate(-bounds.x, -bounds.y);
        g.fill(shape);
        g.dispose();

        return adoptGrayImage(img, bounds.x, bounds.y);
    }

    /**
     * Removes the zero-only outer rows and columns, taking over the given array.
     * Returns null if there is no nonzero sample at all.
     */
    private static SelectionMask trim(byte[] coverage, int width, int height, int x, int y) {
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int row = 0; row < height; row++) {
            int offset = row * width;
            for (int col = 0; col < width; col++) {
                if (coverage[offset + col] != 0) {
                    if (col < minX) {
                        minX = col;
                    }
                    if (col > maxX) {
                        maxX = col;
                    }
                    if (row < minY) {
                        minY = row;
                    }
                    maxY = row; // rows are visited in increasing order
                }
            }
        }

        if (maxX < 0) { // no nonzero sample
            return null;
        }
        if (minX == 0 && minY == 0 && maxX == width - 1 && maxY == height - 1) {
            return new SelectionMask(coverage, width, height, x, y); // nothing to trim
        }

        int trimmedWidth = maxX - minX + 1;
        int trimmedHeight = maxY - minY + 1;
        byte[] trimmed = new byte[trimmedWidth * trimmedHeight];
        for (int row = 0; row < trimmedHeight; row++) {
            System.arraycopy(coverage, (minY + row) * width + minX,
                trimmed, row * trimmedWidth, trimmedWidth);
        }
        return new SelectionMask(trimmed, trimmedWidth, trimmedHeight, x + minX, y + minY);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Returns the canvas-space X coordinate of the tile's upper-left corner.
     */
    public int getX() {
        return x;
    }

    /**
     * Returns the canvas-space Y coordinate of the tile's upper-left corner.
     */
    public int getY() {
        return y;
    }

    /**
     * Returns the canvas-space bounds of the nonzero coverage.
     */
    public Rectangle getBounds() {
        return new Rectangle(x, y, width, height);
    }

    /**
     * Returns the coverage (0-255) at the given canvas-space pixel.
     */
    public int getCoverage(int canvasX, int canvasY) {
        int col = canvasX - x;
        int row = canvasY - y;
        if (col < 0 || col >= width || row < 0 || row >= height) {
            return 0;
        }
        return coverage[row * width + col] & 0xFF;
    }

    /**
     * Returns true if the pixel containing the given canvas-space
     * point has at least the given coverage.
     */
    public boolean containsAtLeast(double canvasX, double canvasY, int threshold) {
        return getCoverage(
            (int) Math.floor(canvasX),
            (int) Math.floor(canvasY)) >= threshold;
    }

    /**
     * Returns a copy of the raw coverage samples, row-major.
     */
    public byte[] copyCoverage() {
        return coverage.clone();
    }

    /**
     * Returns a new grayscale image containing a copy of the coverage samples.
     */
    public BufferedImage toGrayImage() {
        BufferedImage img = new BufferedImage(width, height, TYPE_BYTE_GRAY);
        System.arraycopy(coverage, 0, ImageUtils.getGrayPixels(img), 0, coverage.length);
        return img;
    }

    /**
     * Returns the 50% contour: the boundary between coverage
     * below 128 and coverage of at least 128. It's empty if
     * no sample reaches 128.
     */
    public Shape getContour() {
        if (contour == null) {
            contour = traceContour(CONTOUR_THRESHOLD);
        }
        return contour;
    }

    /**
     * Traces the boundary of the samples whose coverage
     * is at least the given threshold.
     */
    public Shape traceContour(int threshold) {
        return MaskContourTracer.trace(coverage, width, height, x, y, threshold);
    }

    /**
     * Returns true if at least one sample reaches the given threshold.
     */
    public boolean hasCoverageAtLeast(int threshold) {
        for (byte b : coverage) {
            if ((b & 0xFF) >= threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns max(this, other) over the union of the tiles.
     */
    public SelectionMask union(SelectionMask other) {
        return combine(other, getBounds().union(other.getBounds()), CombineOp.MAX);
    }

    /**
     * Returns min(this, other) over the intersection of the tiles.
     */
    public SelectionMask intersect(SelectionMask other) {
        Rectangle bounds = getBounds().intersection(other.getBounds());
        if (bounds.isEmpty()) {
            return null;
        }
        return combine(other, bounds, CombineOp.MIN);
    }

    /**
     * Returns max(0, this - other) over this mask's tile.
     */
    public SelectionMask subtract(SelectionMask other) {
        return combine(other, getBounds(), CombineOp.DIFF);
    }

    /**
     * Returns |this - other| over the union of the tiles.
     */
    public SelectionMask absDifference(SelectionMask other) {
        return combine(other, getBounds().union(other.getBounds()), CombineOp.ABS_DIFF);
    }

    private enum CombineOp {
        MAX, MIN, DIFF, ABS_DIFF
    }

    private SelectionMask combine(SelectionMask other, Rectangle bounds, CombineOp op) {
        byte[] result = new byte[bounds.width * bounds.height];
        for (int row = 0; row < bounds.height; row++) {
            int canvasY = bounds.y + row;
            int offset = row * bounds.width;
            for (int col = 0; col < bounds.width; col++) {
                int canvasX = bounds.x + col;
                int a = getCoverage(canvasX, canvasY);
                int b = other.getCoverage(canvasX, canvasY);
                int value = switch (op) {
                    case MAX -> Math.max(a, b);
                    case MIN -> Math.min(a, b);
                    case DIFF -> Math.max(0, a - b);
                    case ABS_DIFF -> Math.abs(a - b);
                };
                result[offset + col] = (byte) value;
            }
        }
        return trim(result, bounds.width, bounds.height, bounds.x, bounds.y);
    }

    /**
     * Returns 255 - coverage over the entire canvas.
     * Inversion is the one operation whose processing bounds
     * are always the canvas, and not the current tile.
     */
    public SelectionMask inverted(Rectangle canvasBounds) {
        byte[] result = new byte[canvasBounds.width * canvasBounds.height];
        for (int row = 0; row < canvasBounds.height; row++) {
            int canvasY = canvasBounds.y + row;
            int offset = row * canvasBounds.width;
            for (int col = 0; col < canvasBounds.width; col++) {
                result[offset + col] = (byte) (255 - getCoverage(canvasBounds.x + col, canvasY));
            }
        }
        return trim(result, canvasBounds.width, canvasBounds.height, canvasBounds.x, canvasBounds.y);
    }

    /**
     * Returns the part of this mask inside the given canvas-space bounds.
     */
    public SelectionMask clippedTo(Rectangle bounds) {
        Rectangle clipped = getBounds().intersection(bounds);
        if (clipped.isEmpty()) {
            return null;
        }
        if (clipped.equals(getBounds())) {
            return this; // immutable, so it can be shared
        }

        byte[] result = new byte[clipped.width * clipped.height];
        for (int row = 0; row < clipped.height; row++) {
            System.arraycopy(coverage,
                (clipped.y - y + row) * width + (clipped.x - x),
                result, row * clipped.width, clipped.width);
        }
        return trim(result, clipped.width, clipped.height, clipped.x, clipped.y);
    }

    /**
     * Translates the coverage. Integral offsets preserve the samples exactly
     * by moving the tile origin; fractional offsets resample.
     */
    public SelectionMask translated(double dx, double dy) {
        if (dx == Math.rint(dx) && dy == Math.rint(dy)) {
            return new SelectionMask(coverage, width, height,
                x + (int) dx, y + (int) dy);
        }
        return transformed(AffineTransform.getTranslateInstance(dx, dy));
    }

    /**
     * Renders the coverage through the given canvas-space transform.
     */
    public SelectionMask transformed(AffineTransform at) {
        if (at.isIdentity()) {
            return this;
        }
        Rectangle destBounds = at.createTransformedShape(getBounds()).getBounds();
        if (destBounds.isEmpty()) {
            return null;
        }

        BufferedImage dest = new BufferedImage(
            destBounds.width, destBounds.height, TYPE_BYTE_GRAY);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BILINEAR);
        g.translate(-destBounds.x, -destBounds.y);
        g.transform(at);
        g.drawImage(toGrayImage(), x, y, null);
        g.dispose();

        return adoptGrayImage(dest, destBounds.x, destBounds.y);
    }

    /**
     * Grayscale dilation with a square structuring element of the given radius,
     * clipped to the canvas. A square (and not a disk) matches the miter joins
     * of the {@link java.awt.BasicStroke} used by the vector implementation.
     */
    public SelectionMask dilated(int radius, Rectangle canvasBounds) {
        if (radius <= 0) {
            return this;
        }
        Rectangle bounds = new Rectangle(getBounds());
        bounds.grow(radius, radius);
        bounds = bounds.intersection(canvasBounds);
        if (bounds.isEmpty()) {
            return null;
        }

        byte[] padded = copyInto(bounds);
        return trim(rankFilter(padded, bounds.width, bounds.height, radius, true),
            bounds.width, bounds.height, bounds.x, bounds.y);
    }

    /**
     * Grayscale erosion with a square structuring element of the given radius.
     * The samples outside the tile count as unselected.
     */
    public SelectionMask eroded(int radius) {
        if (radius <= 0) {
            return this;
        }
        return trim(rankFilter(coverage, width, height, radius, false),
            width, height, x, y);
    }

    /**
     * Copies this mask's coverage into a new array covering the given bounds.
     */
    private byte[] copyInto(Rectangle bounds) {
        byte[] result = new byte[bounds.width * bounds.height];
        Rectangle common = getBounds().intersection(bounds);
        for (int row = 0; row < common.height; row++) {
            System.arraycopy(coverage,
                (common.y - y + row) * width + (common.x - x),
                result, (common.y - bounds.y + row) * bounds.width + (common.x - bounds.x),
                common.width);
        }
        return result;
    }

    /**
     * A separable min/max filter over a (2 * radius + 1) square window.
     * The samples outside the array count as 0, therefore a maximum
     * filter dilates and a minimum filter erodes the coverage.
     */
    private static byte[] rankFilter(byte[] src, int width, int height, int radius, boolean max) {
        int windowSize = 2 * radius + 1;
        int[] padded = new int[Math.max(width, height) + 2 * radius];
        int[] deque = new int[padded.length];
        int[] filtered = new int[Math.max(width, height)];

        byte[] horizontal = new byte[src.length];
        for (int row = 0; row < height; row++) {
            int offset = row * width;
            pad(padded, radius, width);
            for (int col = 0; col < width; col++) {
                padded[radius + col] = src[offset + col] & 0xFF;
            }
            slide(padded, width, windowSize, max, filtered, deque);
            for (int col = 0; col < width; col++) {
                horizontal[offset + col] = (byte) filtered[col];
            }
        }

        byte[] result = new byte[src.length];
        for (int col = 0; col < width; col++) {
            pad(padded, radius, height);
            for (int row = 0; row < height; row++) {
                padded[radius + row] = horizontal[row * width + col] & 0xFF;
            }
            slide(padded, height, windowSize, max, filtered, deque);
            for (int row = 0; row < height; row++) {
                result[row * width + col] = (byte) filtered[row];
            }
        }
        return result;
    }

    /**
     * Zeroes the padding cells surrounding the given number of samples.
     */
    private static void pad(int[] padded, int radius, int sampleCount) {
        for (int i = 0; i < radius; i++) {
            padded[i] = 0;
            padded[radius + sampleCount + i] = 0;
        }
    }

    /**
     * Runs a sliding-window minimum or maximum over the padded samples,
     * using a monotonic deque of indices for constant amortized cost.
     */
    private static void slide(int[] padded, int outLength, int windowSize,
                              boolean max, int[] dest, int[] deque) {
        int head = 0;
        int tail = 0;
        int paddedLength = outLength + windowSize - 1;
        for (int i = 0; i < paddedLength; i++) {
            int value = padded[i];
            while (tail > head && (max
                ? padded[deque[tail - 1]] <= value
                : padded[deque[tail - 1]] >= value)) {
                tail--;
            }
            deque[tail++] = i;

            int destIndex = i - windowSize + 1;
            if (destIndex >= 0) {
                while (deque[head] < destIndex) {
                    head++;
                }
                dest[destIndex] = padded[deque[head]];
            }
        }
    }

    @Override
    public String toString() {
        return "SelectionMask[" + width + "x" + height + " at " + x + ", " + y + ']';
    }
}
