/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.layers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.Composition;
import pixelitor.TestHelper;
import pixelitor.history.PixelitorEdit;
import pixelitor.tools.transform.ProjectiveMapping;
import pixelitor.tools.transform.TransformMapping;
import pixelitor.tools.transform.WarpMapping;
import pixelitor.tools.transform.WarpStyle;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ImageLayerFreeTransformTest {
    private Composition comp;
    private ImageLayer layer;
    private BufferedImage original;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        comp = TestHelper.createEmptyComp("ImageLayerFreeTransformTest");
        original = fixture(TestHelper.TEST_WIDTH, TestHelper.TEST_HEIGHT);
        layer = TestHelper.createImageLayer(comp, original, "source");
        comp.addLayerWithoutUI(layer);
    }

    @Test
    void projectivePreviewAndCommitUseThePristineSessionImage() {
        var sourceBounds = layer.getContentBounds(true);
        TransformMapping first = new ProjectiveMapping(sourceBounds,
            point(0, 0), point(9, 1), point(8, 9), point(1, 8));
        TransformMapping finalMapping = new ProjectiveMapping(sourceBounds,
            point(1, 0), point(10, 1), point(9, 9), point(0, 8));

        layer.prepareForTransform();
        layer.imTransform(first);
        layer.imTransform(finalMapping);
        PixelitorEdit edit = layer.finalizeTransform();

        assertThat(edit).isNotNull();
        assertThat(layer.getImage()).isNotSameAs(original);
        assertThat(layer.getContentBounds(true).contains(comp.getCanvasBounds())).isTrue();

        edit.undo();
        assertThat(layer.getImage()).isSameAs(original);
        edit.redo();
        assertThat(layer.getImage()).isNotSameAs(original);
    }

    @Test
    void customWarpCommitAndCancelAreTargetStable() {
        var sourceBounds = layer.getContentBounds(true);
        Point2D[] quad = {
            point(0, 0), point(10, 0), point(10, 10), point(0, 10)
        };
        WarpMapping wave = WarpMapping.create(sourceBounds, quad, WarpStyle.WAVE);

        layer.prepareForTransform();
        layer.imTransform(wave);
        layer.cancelTransform();
        assertThat(layer.getImage()).isSameAs(original);

        layer.prepareForTransform();
        layer.imTransform(wave);
        assertThat(layer.finalizeTransform()).isNotNull();
        assertThat(layer.getImage()).isNotSameAs(original);
    }

    private static BufferedImage fixture(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, new Color(20 + x, 40 + y, 80, 255).getRGB());
            }
        }
        return image;
    }

    private static Point2D point(double x, double y) {
        return new Point2D.Double(x, y);
    }
}
