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
        comp.createSelectionFrom(new Rectangle(0, 3, CANVAS_WIDTH, BAND_HEIGHT));
        History.clear();
    }

    @AfterEach
    void afterEachTest() {
        History.clear();
        Views.setActiveView(null, false);
    }

    @Test
    void worksAfterRepeatedUndoAndSelectionNudge() {
        inverseCropAndCheckResult();
        undoAndCheckRestoredSelection(3);

        inverseCropAndCheckResult();
        undoAndCheckRestoredSelection(3);

        comp.getSelection().nudge(ArrowKey.DOWN);
        assertTrue(comp.getSelection().isRectangular());
        assertThat(comp).selectionBoundsIs(new Rectangle(0, 4, CANVAS_WIDTH, BAND_HEIGHT));

        inverseCropAndCheckResult();
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
