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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.history.History;
import pixelitor.layers.ImageLayer;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class LayerViaFillCutTest {
    private static final int SURROUNDING = new Color(40, 50, 60).getRGB();
    private static final int WIDGET = new Color(220, 30, 40).getRGB();
    private static final int DISTANT = new Color(200, 180, 20).getRGB();
    private static final int OUTLIER = new Color(20, 180, 80).getRGB();
    private static final Rectangle SELECTION = new Rectangle(5, 3, 4, 3);

    private Composition comp;
    private ImageLayer sourceLayer;
    private BufferedImage original;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        comp = TestHelper.createEmptyComp("LayerViaFillCutTest");
        original = createScreenshot();
        sourceLayer = TestHelper.createImageLayer(comp, original, "screenshot");
        comp.addLayerWithoutUI(sourceLayer);
        TestHelper.setSelection(comp, SELECTION);
        History.clear();
    }

    @AfterEach
    void afterEachTest() {
        History.clear();
    }

    @Test
    void createsLayerAndFillsCutRegionWithMostCommonOuterColor() {
        comp.layerViaFillCut();

        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertThat(comp.getActiveLayer()).isNotSameAs(sourceLayer);
        assertRegionColor(sourceLayer.getImage(), SELECTION, SURROUNDING);
        assertThat(sourceLayer.getImage().getRGB(0, 0)).isEqualTo(DISTANT);

        ImageLayer extractedLayer = (ImageLayer) comp.getActiveLayer();
        assertRegionColor(extractedLayer.getImage(), SELECTION, WIDGET);
        assertThat(extractedLayer.getImage().getRGB(0, 0)).isZero();
        History.assertNumEditsIs(1);
    }

    @Test
    void fillCutCanBeUndoneAndRedone() {
        comp.layerViaFillCut();

        History.undo("Layer via Fill Cut");
        assertThat(comp.getNumLayers()).isEqualTo(1);
        assertThat(comp.getActiveLayer()).isSameAs(sourceLayer);
        assertRegionColor(sourceLayer.getImage(), SELECTION, WIDGET);

        History.redo("Layer via Fill Cut");
        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertRegionColor(sourceLayer.getImage(), SELECTION, SURROUNDING);
    }

    @Test
    void samplesInCanvasSpaceForTranslatedLayers() {
        Rectangle translatedSelection = new Rectangle(
            SELECTION.x + 10, SELECTION.y + 20, SELECTION.width, SELECTION.height);

        OptionalInt sampled = Composition.findMostCommonOuterColor(
            sourceLayer.getImage(), translatedSelection, 10, 20);

        assertThat(sampled).hasValue(SURROUNDING);
    }

    @Test
    void returnsNoColorWhenSelectionCoversEntireLayer() {
        OptionalInt sampled = Composition.findMostCommonOuterColor(
            sourceLayer.getImage(),
            new Rectangle(0, 0, original.getWidth(), original.getHeight()),
            0, 0);

        assertThat(sampled).isEmpty();
    }

    @Test
    void usesInnerStrokeWhenSelectionHasNoExteriorPixels() {
        replaceSelection(new Rectangle(0, 0, original.getWidth(), original.getHeight()));

        comp.layerViaFillCut();

        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertRegionColor(sourceLayer.getImage(),
            new Rectangle(0, 0, original.getWidth(), original.getHeight()), DISTANT);
        History.assertNumEditsIs(1);
    }

    @Test
    void fallsBackToTransparentCutWhenNeitherStrokeHasLayerPixels() {
        replaceSelection(new Rectangle(
            -10, -10, original.getWidth() + 20, original.getHeight() + 20));

        comp.layerViaFillCut();

        assertThat(comp.getNumLayers()).isEqualTo(2);
        assertRegionColor(sourceLayer.getImage(),
            new Rectangle(0, 0, original.getWidth(), original.getHeight()), 0);
        History.assertNumEditsIs(1);
    }

    private static BufferedImage createScreenshot() {
        BufferedImage image = new BufferedImage(
            TestHelper.TEST_WIDTH, TestHelper.TEST_HEIGHT, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, DISTANT);
            }
        }

        Rectangle outerBounds = new Rectangle(
            SELECTION.x - 1, SELECTION.y - 1,
            SELECTION.width + 2, SELECTION.height + 2);
        for (int y = outerBounds.y; y < outerBounds.y + outerBounds.height; y++) {
            for (int x = outerBounds.x; x < outerBounds.x + outerBounds.width; x++) {
                if (!SELECTION.contains(x + 0.5, y + 0.5)) {
                    image.setRGB(x, y, SURROUNDING);
                }
            }
        }
        image.setRGB(outerBounds.x, outerBounds.y, OUTLIER);

        for (int y = SELECTION.y; y < SELECTION.y + SELECTION.height; y++) {
            for (int x = SELECTION.x; x < SELECTION.x + SELECTION.width; x++) {
                image.setRGB(x, y, WIDGET);
            }
        }
        return image;
    }

    private static void assertRegionColor(BufferedImage image, Rectangle region, int expected) {
        for (int y = region.y; y < region.y + region.height; y++) {
            for (int x = region.x; x < region.x + region.width; x++) {
                assertThat(image.getRGB(x, y))
                    .as("pixel at (%s, %s)", x, y)
                    .isEqualTo(expected);
            }
        }
    }

    private void replaceSelection(Rectangle selection) {
        comp.deselect(false);
        TestHelper.setSelection(comp, selection);
    }
}
