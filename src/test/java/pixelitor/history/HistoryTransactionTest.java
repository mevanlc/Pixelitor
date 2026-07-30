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

package pixelitor.history;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.Composition;
import pixelitor.TestHelper;
import pixelitor.layers.ImageLayer;

import static org.assertj.core.api.Assertions.assertThat;
import static pixelitor.TestHelper.assertHistoryEditsAre;

class HistoryTransactionTest {
    private Composition comp;
    private ImageLayer layer;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        comp = TestHelper.createComp("HistoryTransactionTest", 1, false);
        layer = (ImageLayer) comp.getActiveLayer();
        History.clear();
    }

    @AfterEach
    void afterEachTest() {
        // don't leak an open transaction into the next test
        History.abortTransaction();
        History.clear();
    }

    @Test
    void emptyTransactionAddsNothing() {
        History.startTransaction("Empty", comp);
        assertThat(History.isInTransaction()).isTrue();

        assertThat(History.endTransaction()).isNull();

        assertThat(History.isInTransaction()).isFalse();
        assertThat(History.getNumEdits()).isZero();
    }

    @Test
    void aSingleEditKeepsItsOwnName() {
        History.startTransaction("Group", comp);
        History.add(newEdit("Only Child"));
        History.endTransaction();

        // wrapping one edit into a group with a different name would be misleading
        assertHistoryEditsAre("Only Child");
        PixelitorEdit added = History.getLastEdit();
        assertThat(added).isNotInstanceOf(MultiEdit.class);
        // it's a top-level edit again, so it must notify the menus when undone
        assertThat(added.embedded).isFalse();
    }

    @Test
    void multipleEditsCollapseIntoOne() {
        History.startTransaction("Group", comp);
        History.add(newEdit("First"));
        History.add(newEdit("Second"));
        History.add(newEdit("Third"));
        History.endTransaction();

        assertHistoryEditsAre("Group");

        var combined = (MultiEdit) History.getLastEdit();
        assertThat(combined.getChildren())
            .extracting(PixelitorEdit::getName)
            .containsExactly("First", "Second", "Third");
    }

    @Test
    void collectedEditsAreEmbedded() {
        History.startTransaction("Group", comp);
        PixelitorEdit first = newEdit("First");
        PixelitorEdit second = newEdit("Second");
        History.add(first);
        History.add(second);
        History.endTransaction();

        // embedded edits don't notify the menus and don't
        // manage the dirty flag on their own
        assertThat(first.embedded).isTrue();
        assertThat(second.embedded).isTrue();
    }

    @Test
    void theCombinedEditIsHeavyIfAnyChildIs() {
        History.startTransaction("Group", comp);
        History.add(newEdit("Light"));
        History.add(ImageEdit.createEmbedded(layer)); // heavy
        History.endTransaction();

        assertThat(History.getLastEdit().isHeavy()).isTrue();
    }

    @Test
    void undoAndRedoOfACombinedEditAffectAllChildren() {
        History.startTransaction("Group", comp);
        var first = newEdit("First");
        var second = newEdit("Second");
        History.add(first);
        History.add(second);
        History.endTransaction();

        History.undo("Group");
        assertThat(first.undoCount).isEqualTo(1);
        assertThat(second.undoCount).isEqualTo(1);

        History.redo("Group");
        assertThat(first.redoCount).isEqualTo(1);
        assertThat(second.redoCount).isEqualTo(1);
    }

    @Test
    void abortAddsNothingAndUndoesTheCollectedEdits() {
        History.startTransaction("Group", comp);
        var first = newEdit("First");
        var second = newEdit("Second");
        History.add(first);
        History.add(second);

        History.abortTransaction();

        assertThat(History.isInTransaction()).isFalse();
        assertThat(History.getNumEdits()).isZero();
        // undone in reverse order, so that the state is restored correctly
        assertThat(first.undoCount).isEqualTo(1);
        assertThat(second.undoCount).isEqualTo(1);
    }

    @Test
    void abortWithoutATransactionIsANoOp() {
        History.add(newEdit("Before"));

        History.abortTransaction();

        assertHistoryEditsAre("Before");
    }

    @Test
    void endTransactionIsIdempotent() {
        History.startTransaction("Group", comp);
        History.add(newEdit("First"));
        History.add(newEdit("Second"));
        History.endTransaction();

        assertThat(History.endTransaction()).isNull();

        assertHistoryEditsAre("Group");
    }

    @Test
    void undoAndRedoAreBlockedDuringATransaction() {
        History.add(newEdit("Before"));
        History.undo("Before");

        History.startTransaction("Group", comp);
        History.undo();
        History.redo();

        // both were ignored: the history is still exactly where it was
        assertThat(History.getEditToBeUndone()).isNull();
        assertThat(History.getEditToBeRedoneName()).isEqualTo("Before");

        History.endTransaction();
        assertHistoryEditsAre("Before");
    }

    @Test
    void clearDropsAnOpenTransaction() {
        History.startTransaction("Group", comp);
        History.add(newEdit("First"));

        History.clear();

        assertThat(History.isInTransaction()).isFalse();
        assertThat(History.getNumEdits()).isZero();
    }

    @Test
    void editsOutsideATransactionAreUnaffected() {
        History.add(newEdit("Before"));

        History.startTransaction("Group", comp);
        History.add(newEdit("Inside"));
        History.endTransaction();

        History.add(newEdit("After"));

        assertHistoryEditsAre("Before", "Inside", "After");
    }

    private CountingEdit newEdit(String name) {
        return new CountingEdit(name, comp);
    }

    /**
     * A minimal edit that only records how many times it was undone/redone.
     */
    private static class CountingEdit extends PixelitorEdit {
        int undoCount;
        int redoCount;

        CountingEdit(String name, Composition comp) {
            super(name, comp);
        }

        @Override
        public void undo() {
            super.undo();
            undoCount++;
        }

        @Override
        public void redo() {
            super.redo();
            redoCount++;
        }
    }
}
