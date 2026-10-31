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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.TestHelper;
import pixelitor.history.History;

import java.awt.Rectangle;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionSessionTest {
    @BeforeAll
    static void beforeAll() {
        TestHelper.setUnitTestingMode();
    }

    @AfterEach
    void afterEach() {
        History.clear();
    }

    @BeforeEach
    void beforeEach() {
        History.clear();
    }

    @Test
    void replacingMaskPreservesSubthresholdCoverageAndSupportsUndo() {
        var comp = TestHelper.createComp("Mask Selection Session", 1, false);
        comp.createSelectionFrom(SelectionData.forMask(
            SelectionMask.fromCoverage(new byte[]{(byte) 192}, 1, 1, 1, 1)));
        var session = new SelectionSession(SelectionType.RECTANGLE, SelectionCombinator.REPLACE, comp);
        comp.setDraftSelection(new Selection(SelectionData.forMask(
            SelectionMask.fromCoverage(new byte[]{64}, 1, 1, 2, 2)), comp.getView()));

        session.commit();
        session.cancelIfNotCommitted();

        assertThat(comp.hasDraftSelection()).isFalse();
        assertThat(comp.getSelectionData().materializeMask(comp.getCanvas()).getCoverage(2, 2))
            .isEqualTo(64);
        assertThat(comp.getSelectionShape().getBounds().isEmpty()).isTrue();
        History.undo(SelectionCombinator.REPLACE.getHistoryName());
        assertThat(comp.getSelectionData().materializeMask(comp.getCanvas()).getCoverage(1, 1))
            .isEqualTo(192);
        History.redo(SelectionCombinator.REPLACE.getHistoryName());
        assertThat(comp.getSelectionData().materializeMask(comp.getCanvas()).getCoverage(2, 2))
            .isEqualTo(64);
    }

    @Test
    void cancellingSessionRestoresOriginalDisplayStateAndCoverage() {
        var comp = TestHelper.createEmptyComp("Cancel Selection Session");
        comp.createSelectionFrom(SelectionData.forMask(
            SelectionMask.fromCoverage(new byte[]{64}, 1, 1, 1, 1)));
        comp.getSelection().setHidden(true);
        comp.getSelection().setFrozen(false);
        var session = new SelectionSession(SelectionType.RECTANGLE, SelectionCombinator.ADD, comp);
        comp.setDraftSelection(new Selection(new Rectangle(2, 2, 3, 3), comp.getView()));
        assertThat(comp.getSelection().isFrozen()).isTrue();

        session.cancelIfNotCommitted();

        assertThat(comp.hasDraftSelection()).isFalse();
        assertThat(comp.getSelection().isHidden()).isTrue();
        assertThat(comp.getSelection().isFrozen()).isFalse();
        assertThat(comp.getSelectionData().materializeMask(comp.getCanvas()).getCoverage(1, 1))
            .isEqualTo(64);
        History.assertNumEditsIs(0);
    }
}
