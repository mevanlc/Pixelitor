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

    private static BufferedImage fixture() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                image.setRGB(x, y, new Color(20 + x * 30, 40 + y * 20, 80, 255).getRGB());
            }
        }
        return image;
    }

    private static Point2D point(double x, double y) {
        return new Point2D.Double(x, y);
    }
}
