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

import org.junit.jupiter.api.Test;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MaskContourTracerTest {
    @Test
    void straightEdgesAreReducedToCorners() {
        byte[] coverage = new byte[24];
        Arrays.fill(coverage, (byte) 255);
        Shape contour = MaskContourTracer.trace(coverage, 6, 4, 0, 0, 128);
        int segments = 0;
        for (PathIterator it = contour.getPathIterator(null); !it.isDone(); it.next()) {
            segments++;
        }

        // One start, three remaining corners, and a close operation.
        assertThat(segments).isEqualTo(5);
        assertThat(contour.getBounds().width).isEqualTo(6);
        assertThat(contour.getBounds().height).isEqualTo(4);
    }

    @Test
    void simplifiedContoursPreserveHolesAndDisconnectedPixels() {
        byte[] coverage = new byte[35];
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                if (x == 0 || x == 4 || y == 0 || y == 4) {
                    coverage[y * 7 + x] = (byte) 255;
                }
            }
        }
        coverage[2 * 7 + 6] = (byte) 255;
        Shape contour = MaskContourTracer.trace(coverage, 7, 5, 0, 0, 128);

        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 7; x++) {
                assertThat(contour.contains(x + 0.5, y + 0.5))
                    .as("pixel (%d, %d)", x, y)
                    .isEqualTo(coverage[y * 7 + x] != 0);
            }
        }
    }
}
