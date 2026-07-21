/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformMappingTest {
    private static final Rectangle2D SOURCE = new Rectangle2D.Double(10, 20, 100, 50);

    @Test
    void projectiveMappingPlacesCornersAndRoundTripsInteriorPoints() {
        ProjectiveMapping mapping = new ProjectiveMapping(SOURCE,
            point(5, 10), point(130, 25), point(115, 90), point(20, 75));

        assertPointClose(mapping.mapSourceToDestination(point(10, 20)), point(5, 10));
        assertPointClose(mapping.mapSourceToDestination(point(110, 70)), point(115, 90));

        Point2D source = point(44.5, 39.25);
        Point2D restored = mapping.mapDestinationToSource(
            mapping.mapSourceToDestination(source));
        assertThat(restored.getX()).isCloseTo(source.getX(), within());
        assertThat(restored.getY()).isCloseTo(source.getY(), within());
    }

    @Test
    void projectiveMappingRejectsDegenerateAndIntersectingQuads() {
        assertThatThrownBy(() -> new ProjectiveMapping(SOURCE,
            point(0, 0), point(100, 0), point(0, 50), point(100, 50)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectiveMapping(SOURCE,
            point(0, 0), point(50, 0), point(100, 0), point(150, 0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectiveMapping(SOURCE,
            point(0, 0), point(100, 0), point(30, 20), point(0, 50)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("convex");
    }

    @Test
    void regularCustomWarpIsIdentityAndRoundTripsAcrossSharedEdges() {
        Point2D[] quad = {
            point(10, 20), point(110, 20), point(110, 70), point(10, 70)
        };
        WarpMapping mapping = WarpMapping.create(SOURCE, quad, WarpStyle.CUSTOM);

        assertThat(mapping.isIdentity()).isTrue();
        for (Point2D source : new Point2D[]{
            point(10, 20), point(43.333333, 36.666667),
            point(60, 45), point(110, 70)}) {
            Point2D destination = mapping.mapSourceToDestination(source);
            Point2D restored = mapping.mapDestinationToSource(destination);
            assertThat(restored.getX()).isCloseTo(source.getX(), within());
            assertThat(restored.getY()).isCloseTo(source.getY(), within());
        }
    }

    @Test
    void namedWarpStylesUseTheSameSerializableMeshMapping() {
        Point2D[] quad = {
            point(10, 20), point(110, 20), point(110, 70), point(10, 70)
        };
        for (WarpStyle style : WarpStyle.values()) {
            WarpMapping mapping = WarpMapping.create(SOURCE, quad, style);
            assertThat(mapping.controlPoints()).hasSize(16);
            assertThat(mapping.style()).isEqualTo(style);
            assertThat(mapping.destinationBounds().isEmpty()).isFalse();
        }
    }

    @Test
    void customWarpRejectsAnInvertedMeshPoint() {
        Point2D[] quad = {
            point(10, 20), point(110, 20), point(110, 70), point(10, 70)
        };
        WarpMapping mapping = WarpMapping.create(SOURCE, quad, WarpStyle.CUSTOM);

        assertThatThrownBy(() -> mapping.withControlPoint(5, point(105, 65)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inverted");
    }

    private static Point2D point(double x, double y) {
        return new Point2D.Double(x, y);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1.0e-5);
    }

    private static void assertPointClose(Point2D actual, Point2D expected) {
        assertThat(actual.getX()).isCloseTo(expected.getX(), within());
        assertThat(actual.getY()).isCloseTo(expected.getY(), within());
    }
}
