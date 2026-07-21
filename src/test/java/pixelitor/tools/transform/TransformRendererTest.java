/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pixelitor.TestHelper;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformRendererTest {
    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @Test
    void identityMappingIsPixelExact() {
        BufferedImage source = fixture();
        Rectangle bounds = new Rectangle(0, 0, 4, 4);
        AffineMapping mapping = new AffineMapping(new AffineTransform(), bounds);

        TransformRenderer.Result result = TransformRenderer.render(
            source, 0, 0, bounds, bounds, mapping);

        assertThat(result.tx()).isZero();
        assertThat(result.ty()).isZero();
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertThat(result.image().getRGB(x, y)).isEqualTo(source.getRGB(x, y));
            }
        }
    }

    @Test
    void projectiveMappingKeepsOutsideSamplesTransparent() {
        BufferedImage source = fixture();
        Rectangle bounds = new Rectangle(0, 0, 4, 4);
        ProjectiveMapping mapping = new ProjectiveMapping(bounds,
            point(1, 0), point(4, 1), point(3, 4), point(0, 3));

        TransformRenderer.Result result = TransformRenderer.render(
            source, 0, 0, bounds, new Rectangle(0, 0, 5, 5), mapping);

        assertThat(result.image().getRGB(0, 0)).isZero();
        assertThat(result.image().getRGB(1, 0)).isEqualTo(source.getRGB(0, 0));
        assertThat(result.image().getRGB(4, 4)).isZero();
    }

    @Test
    void oversizedOutputIsRejectedBeforeAllocation() {
        BufferedImage source = fixture();
        Rectangle bounds = new Rectangle(0, 0, 4, 4);
        AffineTransform huge = AffineTransform.getScaleInstance(100_000, 100_000);

        assertThatThrownBy(() -> TransformRenderer.render(
            source, 0, 0, bounds, bounds, new AffineMapping(huge, bounds)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dimension");
    }

    @Test
    void warpRasterHasNoTransparentCracksAtSharedTriangleEdges() {
        Rectangle bounds = new Rectangle(0, 0, 12, 12);
        BufferedImage source = opaqueFixture(bounds.width, bounds.height);
        Point2D[] mesh = regularMesh(bounds);
        mesh[5] = point(5, 5);
        mesh[6] = point(8, 5);
        WarpMapping mapping = new WarpMapping(bounds, WarpStyle.CUSTOM, mesh);

        TransformRenderer.Result result = TransformRenderer.render(
            source, 0, 0, bounds, bounds, mapping);

        for (int y = 0; y < bounds.height; y++) {
            for (int x = 0; x < bounds.width; x++) {
                Point2D mapped = mapping.mapDestinationToSource(point(x, y));
                if (Double.isFinite(mapped.getX())
                    && mapped.getX() >= 0 && mapped.getX() <= bounds.width - 1
                    && mapped.getY() >= 0 && mapped.getY() <= bounds.height - 1) {
                    assertThat(result.image().getRGB(x, y) >>> 24)
                        .as("alpha at destination (%s, %s)", x, y)
                        .isPositive();
                }
            }
        }
    }

    private static BufferedImage fixture() {
        return opaqueFixture(4, 4);
    }

    private static BufferedImage opaqueFixture(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, new Color(
                    Math.min(255, 20 + x * 30),
                    Math.min(255, 40 + y * 20), 80, 255).getRGB());
            }
        }
        return image;
    }

    private static Point2D[] regularMesh(Rectangle bounds) {
        Point2D[] points = new Point2D[WarpMapping.GRID_SIZE * WarpMapping.GRID_SIZE];
        for (int row = 0; row < WarpMapping.GRID_SIZE; row++) {
            for (int col = 0; col < WarpMapping.GRID_SIZE; col++) {
                points[row * WarpMapping.GRID_SIZE + col] = point(
                    bounds.x + col * bounds.width / 3.0,
                    bounds.y + row * bounds.height / 3.0);
            }
        }
        return points;
    }

    private static Point2D point(double x, double y) {
        return new Point2D.Double(x, y);
    }
}
