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

package pixelitor.compactions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.Composition;
import pixelitor.TestHelper;
import pixelitor.Views;
import pixelitor.gui.View;
import pixelitor.history.History;
import pixelitor.tools.util.ArrowKey;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static pixelitor.assertions.PixelitorAssertions.assertThat;

class InverseCropTest {
    private static final int CANVAS_WIDTH = 20;
    private static final int CANVAS_HEIGHT = 10;
    private static final int BAND_HEIGHT = 2;

    private Composition comp;
    private View view;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        comp = TestHelper.createComp("InverseCropTest", 1, false);
        view = comp.getView();
        History.clear();
    }

    @AfterEach
    void afterEachTest() {
        History.clear();
        Views.setActiveView(null, false);
    }

    @Test
    void worksAfterRepeatedUndoAndSelectionNudge() {
        comp.createSelectionFrom(new Rectangle(0, 3, CANVAS_WIDTH, BAND_HEIGHT));
        inverseCropAndCheckResult();
        undoAndCheckRestoredSelection(3);

        inverseCropAndCheckResult();
        undoAndCheckRestoredSelection(3);

        comp.getSelection().nudge(ArrowKey.DOWN);
        assertTrue(comp.getSelection().isRectangular());
        assertThat(comp).selectionBoundsIs(new Rectangle(0, 4, CANVAS_WIDTH, BAND_HEIGHT));

        inverseCropAndCheckResult();
    }

    @Test
    void sloppySelectionWidthGreaterThanHeight() {
        // AABB width (10) > height (4): virtually extends horizontally, cutting horizontal band
        comp.createSelectionFrom(new java.awt.geom.Ellipse2D.Double(3, 2, 10, 4));

        Crop.inverseCropActiveComp();

        assertThat(view.getComp())
            .isNotSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH, CANVAS_HEIGHT - 4)
            .doesNotHaveSelection();

        History.undo("Inverse Crop");
        assertThat(view.getComp())
            .isSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH, CANVAS_HEIGHT);

        History.redo("Inverse Crop");
        assertThat(view.getComp())
            .isNotSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH, CANVAS_HEIGHT - 4);
    }

    @Test
    void edgeToEdgeWidthUsedEvenWhenHeightIsGreater() {
        // Create a 10x40 canvas
        Composition tallComp = TestHelper.createRealComp("TallComp", pixelitor.layers.ImageLayer.class, 10, 40);
        View tallView = tallComp.getView();
        Views.setActiveView(tallView, false);

        // Selection spans full width (w=10), height=20 (height > width)
        tallComp.createSelectionFrom(new Rectangle(0, 5, 10, 20));

        Crop.inverseCropActiveComp();

        // Should use horizontal inverse crop because it's edge-to-edge in width, reducing height by 20
        assertThat(tallView.getComp())
            .isNotSameAs(tallComp)
            .canvasSizeIs(10, 20)
            .doesNotHaveSelection();
    }

    @Test
    void edgeToEdgeHeightUsedEvenWhenWidthIsGreater() {
        // Create a 40x10 canvas
        Composition wideComp = TestHelper.createRealComp("WideComp", pixelitor.layers.ImageLayer.class, 40, 10);
        View wideView = wideComp.getView();
        Views.setActiveView(wideView, false);

        // Selection spans full height (h=10), width=20 (width > height)
        wideComp.createSelectionFrom(new Rectangle(5, 0, 20, 10));

        Crop.inverseCropActiveComp();

        // Should use vertical inverse crop because it's edge-to-edge in height, reducing width by 20
        assertThat(wideView.getComp())
            .isNotSameAs(wideComp)
            .canvasSizeIs(20, 10)
            .doesNotHaveSelection();
    }

    @Test
    void sloppySelectionHeightGreaterThanWidth() {
        // AABB height (6) > width (4): virtually extends vertically, cutting vertical band
        comp.createSelectionFrom(new java.awt.geom.Ellipse2D.Double(4, 2, 4, 6));

        Crop.inverseCropActiveComp();

        assertThat(view.getComp())
            .isNotSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH - 4, CANVAS_HEIGHT)
            .doesNotHaveSelection();

        History.undo("Inverse Crop");
        assertThat(view.getComp())
            .isSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH, CANVAS_HEIGHT);

        History.redo("Inverse Crop");
        assertThat(view.getComp())
            .isNotSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH - 4, CANVAS_HEIGHT);
    }

    @Test
    void squareSelectionCrossInverseCropWithQuadrantPixelVerification() {
        // 20x10 image with 4 quadrants painted with unique colors and cross painted white
        pixelitor.layers.ImageLayer activeLayer = (pixelitor.layers.ImageLayer) comp.getActiveLayer();
        java.awt.image.BufferedImage img = activeLayer.getImage();

        int red = java.awt.Color.RED.getRGB();
        int green = java.awt.Color.GREEN.getRGB();
        int blue = java.awt.Color.BLUE.getRGB();
        int yellow = java.awt.Color.YELLOW.getRGB();
        int white = java.awt.Color.WHITE.getRGB();

        // Square AABB: x=5, y=2, w=4, h=4 (cross spans x in 5..8 and y in 2..5)
        for (int y = 0; y < CANVAS_HEIGHT; y++) {
            for (int x = 0; x < CANVAS_WIDTH; x++) {
                if (x >= 5 && x < 9 || y >= 2 && y < 6) {
                    img.setRGB(x, y, white);
                } else if (x < 5 && y < 2) {
                    img.setRGB(x, y, red);      // Top-Left quadrant (w=5, h=2)
                } else if (x >= 9 && y < 2) {
                    img.setRGB(x, y, green);    // Top-Right quadrant (w=11, h=2)
                } else if (x < 5 && y >= 6) {
                    img.setRGB(x, y, blue);     // Bottom-Left quadrant (w=5, h=4)
                } else {
                    img.setRGB(x, y, yellow);   // Bottom-Right quadrant (w=11, h=4)
                }
            }
        }

        comp.createSelectionFrom(new Rectangle(5, 2, 4, 4));

        Crop.inverseCropActiveComp();

        Composition resultComp = view.getComp();
        int expectedW = CANVAS_WIDTH - 4;   // 16
        int expectedH = CANVAS_HEIGHT - 4; // 6

        assertThat(resultComp)
            .isNotSameAs(comp)
            .canvasSizeIs(expectedW, expectedH)
            .doesNotHaveSelection();

        pixelitor.layers.ImageLayer resultLayer = (pixelitor.layers.ImageLayer) resultComp.getActiveLayer();
        java.awt.image.BufferedImage resultImg = resultLayer.getImage();

        // Verify the 4 quadrants are stitched intact:
        // Top-Left: x in [0..4], y in [0..1] -> Red
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 5; x++) {
                assertThat(resultImg.getRGB(x, y)).isEqualTo(red);
            }
        }
        // Top-Right: x in [5..15], y in [0..1] -> Green
        for (int y = 0; y < 2; y++) {
            for (int x = 5; x < 16; x++) {
                assertThat(resultImg.getRGB(x, y)).isEqualTo(green);
            }
        }
        // Bottom-Left: x in [0..4], y in [2..5] -> Blue
        for (int y = 2; y < 6; y++) {
            for (int x = 0; x < 5; x++) {
                assertThat(resultImg.getRGB(x, y)).isEqualTo(blue);
            }
        }
        // Bottom-Right: x in [5..15], y in [2..5] -> Yellow
        for (int y = 2; y < 6; y++) {
            for (int x = 5; x < 16; x++) {
                assertThat(resultImg.getRGB(x, y)).isEqualTo(yellow);
            }
        }

        // Undo restores original comp and pixel data
        History.undo("Inverse Crop");
        assertThat(view.getComp()).isSameAs(comp);
        assertThat(activeLayer.getImage().getRGB(0, 0)).isEqualTo(red);
        assertThat(activeLayer.getImage().getRGB(5, 2)).isEqualTo(white);

        // Redo re-applies cross crop
        History.redo("Inverse Crop");
        Composition redoComp = view.getComp();
        assertThat(redoComp).canvasSizeIs(expectedW, expectedH);
        pixelitor.layers.ImageLayer redoLayer = (pixelitor.layers.ImageLayer) redoComp.getActiveLayer();
        assertThat(redoLayer.getImage().getRGB(0, 0)).isEqualTo(red);
        assertThat(redoLayer.getImage().getRGB(5, 0)).isEqualTo(green);
    }

    @Test
    void squareNonRectangularSelectionCrossCrop() {
        // Circle selection with equal width and height (4x4)
        comp.createSelectionFrom(new java.awt.geom.Ellipse2D.Double(5, 2, 4, 4));

        Crop.inverseCropActiveComp();

        assertThat(view.getComp())
            .isNotSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH - 4, CANVAS_HEIGHT - 4)
            .doesNotHaveSelection();
    }

    @Test
    void partiallyOffCanvasSelectionClipsToCanvas() {
        // Selection extends outside canvas to the left (-2..6), canvas-clipped AABB is (0, 2, 6, 4)
        comp.createSelectionFrom(new Rectangle(-2, 2, 8, 4));

        Crop.inverseCropActiveComp();

        // Clipped AABB is width=6, height=4 -> width > height -> cuts horizontal band of height 4
        assertThat(view.getComp())
            .isNotSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH, CANVAS_HEIGHT - 4)
            .doesNotHaveSelection();
    }

    @Test
    void guidesTransformedDuringSquareInverseCrop() {
        pixelitor.guides.Guides guides = new pixelitor.guides.Guides();
        // Canvas is 20x10.
        // Horizontal: y=1 (0.1), y=3 (0.3 - inside cross), y=8 (0.8)
        guides.addHorizontal(0.1);
        guides.addHorizontal(0.3);
        guides.addHorizontal(0.8);
        // Vertical: x=2 (0.1), x=6 (0.3 - inside cross), x=16 (0.8)
        guides.addVertical(0.1);
        guides.addVertical(0.3);
        guides.addVertical(0.8);
        comp.setGuides(guides);

        // Square AABB: x=5, y=2, w=4, h=4
        comp.createSelectionFrom(new Rectangle(5, 2, 4, 4));

        Crop.inverseCropActiveComp();

        Composition resultComp = view.getComp();
        pixelitor.guides.Guides resultGuides = resultComp.getGuides();
        assertThat(resultGuides).isNotNull();

        // 0.3 guides inside cross were dropped; remaining guides remapped to new 16x6 canvas
        assertThat(resultGuides.getHorizontals()).containsExactly(1.0 / 6.0, 4.0 / 6.0);
        assertThat(resultGuides.getVerticals()).containsExactly(2.0 / 16.0, 12.0 / 16.0);
    }

    @Test
    void squareSelectionWithTranslatedImageLayerCoveringCanvas() {
        // Create an ImageLayer extending beyond the canvas with negative translation
        int tx = -4;
        int ty = -2;
        int imgW = CANVAS_WIDTH - tx;   // 24
        int imgH = CANVAS_HEIGHT - ty; // 12
        java.awt.image.BufferedImage layerImg = new java.awt.image.BufferedImage(
            imgW, imgH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        pixelitor.layers.ImageLayer translatedLayer = new pixelitor.layers.ImageLayer(
            comp, layerImg, "TranslatedLayer");
        translatedLayer.setTranslation(tx, ty);
        comp.addLayerWithoutUI(translatedLayer);

        comp.createSelectionFrom(new Rectangle(5, 2, 4, 4));

        Crop.inverseCropActiveComp();

        Composition resultComp = view.getComp();
        assertThat(resultComp)
            .canvasSizeIs(16, 6)
            .invariantsAreOK();

        pixelitor.layers.ImageLayer resultTranslatedLayer = (pixelitor.layers.ImageLayer) resultComp.getLayer(1);
        assertThat(resultTranslatedLayer.getTx()).isEqualTo(tx);
        assertThat(resultTranslatedLayer.getTy()).isEqualTo(ty);
        assertThat(resultTranslatedLayer.getImage().getWidth()).isEqualTo(16 - tx); // 20
        assertThat(resultTranslatedLayer.getImage().getHeight()).isEqualTo(6 - ty);  // 8
    }

    @Test
    void selectionCoveringFullCanvasIsSafelyRejected() {
        comp.createSelectionFrom(new Rectangle(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT));

        var handler = new pixelitor.utils.TestMessageHandler();
        handler.setThrowOnErrors(false);
        pixelitor.utils.Messages.setHandler(handler);
        try {
            Crop.inverseCropActiveComp();

            // Comp should remain unchanged
            assertThat(view.getComp()).isSameAs(comp);
            assertThat(handler.getCapturedMessages())
                .anyMatch(m -> m.message().contains("The selection covers the entire"));
        } finally {
            pixelitor.utils.Messages.setHandler(new pixelitor.utils.TestMessageHandler());
        }
    }

    private void inverseCropAndCheckResult() {
        Crop.inverseCropActiveComp();

        assertThat(view.getComp())
            .isNotSameAs(comp)
            .canvasSizeIs(CANVAS_WIDTH, CANVAS_HEIGHT - BAND_HEIGHT)
            .doesNotHaveSelection();
    }

    private void undoAndCheckRestoredSelection(int expectedY) {
        History.undo("Inverse Crop");

        assertThat(view.getComp()).isSameAs(comp);
        assertTrue(comp.getSelection().isRectangular());
        assertThat(comp).selectionBoundsIs(
            new Rectangle(0, expectedY, CANVAS_WIDTH, BAND_HEIGHT));
    }
}
