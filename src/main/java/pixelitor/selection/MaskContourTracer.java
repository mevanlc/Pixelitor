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

import java.awt.Shape;
import java.awt.geom.Path2D;

/**
 * Traces the pixel-edge boundary of the samples of an 8-bit coverage
 * tile that reach a given threshold.
 * <p>
 * Outer boundaries are emitted clockwise and the boundaries of holes
 * counterclockwise, so that the non-zero winding rule of the returned
 * path preserves the holes. Disconnected components are emitted as
 * separate subpaths. Pixels touching only diagonally are treated as
 * belonging to different components, which keeps the contours simple.
 * <p>
 * The traversal visits the pixels in row-major order and always prefers
 * a clockwise turn, so the result is deterministic.
 */
final class MaskContourTracer {
    // the four sides of a pixel, stored as bit flags per pixel
    private static final int TOP = 1;
    private static final int RIGHT = 1 << 1;
    private static final int BOTTOM = 1 << 2;
    private static final int LEFT = 1 << 3;

    // the travel directions, in clockwise order
    private static final int DIR_RIGHT = 0;
    private static final int DIR_DOWN = 1;
    private static final int DIR_LEFT = 2;
    private static final int DIR_UP = 3;

    private static final int[] DIR_DX = {1, 0, -1, 0};
    private static final int[] DIR_DY = {0, 1, 0, -1};

    private MaskContourTracer() {
        // utility class, prevent instantiation
    }

    /**
     * Traces the boundary of the samples whose coverage is at least the
     * threshold, returning pixel-edge coordinates in canvas space.
     * The returned path is empty if no sample reaches the threshold.
     */
    static Shape trace(byte[] coverage, int width, int height,
                       int originX, int originY, int threshold) {
        int[] edges = findBoundaryEdges(coverage, width, height, threshold);

        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (int pixel = 0; pixel < edges.length; pixel++) {
            if (edges[pixel] == 0) {
                continue;
            }
            int px = pixel % width;
            int py = pixel / width;
            // tracing consumes edges, so the flags are re-read after every loop
            if ((edges[pixel] & TOP) != 0) {
                traceLoop(edges, width, height, px, py, DIR_RIGHT, originX, originY, path);
            }
            if ((edges[pixel] & RIGHT) != 0) {
                traceLoop(edges, width, height, px + 1, py, DIR_DOWN, originX, originY, path);
            }
            if ((edges[pixel] & BOTTOM) != 0) {
                traceLoop(edges, width, height, px + 1, py + 1, DIR_LEFT, originX, originY, path);
            }
            if ((edges[pixel] & LEFT) != 0) {
                traceLoop(edges, width, height, px, py + 1, DIR_UP, originX, originY, path);
            }
        }
        return path;
    }

    /**
     * Marks the sides of the inside pixels that face an outside pixel.
     */
    private static int[] findBoundaryEdges(byte[] coverage, int width, int height, int threshold) {
        int[] edges = new int[width * height];
        for (int py = 0; py < height; py++) {
            int offset = py * width;
            for (int px = 0; px < width; px++) {
                if (!isInside(coverage, width, height, px, py, threshold)) {
                    continue;
                }
                int sides = 0;
                if (!isInside(coverage, width, height, px, py - 1, threshold)) {
                    sides |= TOP;
                }
                if (!isInside(coverage, width, height, px + 1, py, threshold)) {
                    sides |= RIGHT;
                }
                if (!isInside(coverage, width, height, px, py + 1, threshold)) {
                    sides |= BOTTOM;
                }
                if (!isInside(coverage, width, height, px - 1, py, threshold)) {
                    sides |= LEFT;
                }
                edges[offset + px] = sides;
            }
        }
        return edges;
    }

    private static boolean isInside(byte[] coverage, int width, int height,
                                    int px, int py, int threshold) {
        if (px < 0 || px >= width || py < 0 || py >= height) {
            return false;
        }
        return (coverage[py * width + px] & 0xFF) >= threshold;
    }

    /**
     * Follows the connected boundary edges from the given start
     * vertex until the loop closes, consuming the used edges.
     */
    private static void traceLoop(int[] edges, int width, int height,
                                  int startX, int startY, int startDir,
                                  int originX, int originY, Path2D path) {
        path.moveTo(originX + startX, originY + startY);

        int vx = startX;
        int vy = startY;
        int dir = startDir;
        while (true) {
            consumeEdge(edges, width, height, vx, vy, dir);
            vx += DIR_DX[dir];
            vy += DIR_DY[dir];
            if (vx == startX && vy == startY) {
                break; // the loop is closed
            }
            path.lineTo(originX + vx, originY + vy);

            int nextDir = findNextDir(edges, width, height, vx, vy, dir);
            if (nextDir < 0) {
                // can't happen for a consistently oriented boundary
                assert false : "open contour at " + vx + ", " + vy;
                break;
            }
            dir = nextDir;
        }
        path.closePath();
    }

    /**
     * Returns the direction of the next unused edge leaving the given
     * vertex, preferring a clockwise turn so that pixels touching only
     * diagonally end up on separate contours.
     */
    private static int findNextDir(int[] edges, int width, int height,
                                   int vx, int vy, int incomingDir) {
        // right turn, then straight ahead, then left turn
        int[] preferences = {(incomingDir + 1) % 4, incomingDir, (incomingDir + 3) % 4};
        for (int dir : preferences) {
            if (hasEdge(edges, width, height, vx, vy, dir)) {
                return dir;
            }
        }
        return -1;
    }

    /**
     * Returns the index of the pixel owning the boundary edge that
     * leaves the given vertex in the given direction, or -1 if the
     * edge would be outside the tile.
     */
    private static int edgePixel(int width, int height, int vx, int vy, int dir) {
        int px = switch (dir) {
            case DIR_RIGHT, DIR_UP -> vx;
            default -> vx - 1;
        };
        int py = switch (dir) {
            case DIR_RIGHT, DIR_DOWN -> vy;
            default -> vy - 1;
        };
        if (px < 0 || px >= width || py < 0 || py >= height) {
            return -1;
        }
        return py * width + px;
    }

    private static int edgeSide(int dir) {
        return switch (dir) {
            case DIR_RIGHT -> TOP;
            case DIR_DOWN -> RIGHT;
            case DIR_LEFT -> BOTTOM;
            default -> LEFT;
        };
    }

    private static boolean hasEdge(int[] edges, int width, int height, int vx, int vy, int dir) {
        int pixel = edgePixel(width, height, vx, vy, dir);
        return pixel >= 0 && (edges[pixel] & edgeSide(dir)) != 0;
    }

    private static void consumeEdge(int[] edges, int width, int height, int vx, int vy, int dir) {
        int pixel = edgePixel(width, height, vx, vy, dir);
        if (pixel >= 0) {
            edges[pixel] &= ~edgeSide(dir);
        }
    }
}
