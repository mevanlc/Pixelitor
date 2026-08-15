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

import pixelitor.Composition;
import pixelitor.history.History;
import pixelitor.history.NewSelectionEdit;
import pixelitor.history.PixelitorEdit;
import pixelitor.history.SelectionChangeEdit;
import pixelitor.tools.util.Drag;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Messages;

import java.util.Locale;

/**
 * Manages the interactive creation and modification of a selection.
 */
public class SelectionBuilder {
    private final SelectionType selectionType;
    private final SelectionCombinator combinator;
    private Composition comp;

    private SelectionData prevSelData;

    private boolean finalized = false;

    // the initial state of the selection
    private boolean wasHidden = false;
    private boolean wasFrozen = false;

    public SelectionBuilder(SelectionType selectionType, SelectionCombinator combinator, Composition comp) {
        this.combinator = combinator;
        this.selectionType = selectionType;
        this.comp = comp;

        Selection existingSelection = comp.getSelection();
        if (existingSelection == null) {
            // nothing to hide or freeze if there's no existing selection to combine with
            return;
        }

        assert existingSelection.isValid() : "disposed selection";

        // remember the original state
        wasHidden = existingSelection.isHidden();
        wasFrozen = existingSelection.isFrozen();

        if (combinator == SelectionCombinator.REPLACE) {
            prevSelData = existingSelection.getData();
            // At this point the mouse was pressed, and it's clear that the
            // existing selection should go away, but we don't know yet whether the
            // mouse will be released at the same point (Deselect) or another
            // point (Replace Selection).
            // Therefore, we don't deselect yet (the selection information
            // will be needed when the mouse is released), only hide.
            existingSelection.setHidden(true);
        } else {
            existingSelection.setFrozen(true);
        }
    }

    /**
     * Updates the draft selection based on drag information.
     */
    public void updateDraftSelection(Drag drag) {
        Selection draftSelection = comp.getDraftSelection();

        if (draftSelection == null) {
            comp.setDraftSelection(new Selection(
                selectionType.createFromDrag(drag, null), comp.getView()));
        } else {
            assert draftSelection.isValid() : "disposed draft selection";
            applyToDraft(draftSelection,
                selectionType.createFromDrag(drag, draftSelection.getData()));
        }
    }

    /**
     * Updates the draft selection based on a mouse event.
     */
    public void updateDraftSelection(PMouseEvent e) {
        // update the composition reference, because in a polygonal lasso
        // selection session an undo of a previous CompAction could change it
        // (possibly it would be better to store the view in this class)
        comp = e.getComp();

        Selection draftSelection = comp.getDraftSelection();

        if (draftSelection == null) {
            comp.setDraftSelection(new Selection(
                selectionType.createFromEvent(e, null), comp.getView()));
        } else {
            assert draftSelection.isValid() : "disposed draft selection";
            applyToDraft(draftSelection,
                selectionType.createFromEvent(e, draftSelection.getData()));
        }
    }

    private static void applyToDraft(Selection draftSelection, SelectionData newData) {
        draftSelection.setData(newData);

        if (!draftSelection.isMarching()) {
            draftSelection.startMarching();
        }
    }

    /**
     * Finalizes the selection by combining the draft with
     * any existing selection according to the combination mode.
     */
    public void combineShapes() {
        Selection draftSelection = comp.getDraftSelection();

        SelectionData newData = draftSelection.getData().clippedTo(comp.getCanvas());
        if (SelectionData.selectsNothing(newData)) {
            // leave finalized false so cancelIfNotFinalized()
            // cleans up and restores the prior selection state
            return;
        }

        if (comp.hasSelection()) {
            combineWithExistingSelection(draftSelection, newData);
        } else {
            finalizeNewSelection(draftSelection, newData);
        }

        finalized = true;
    }

    private void combineWithExistingSelection(Selection draftSelection,
                                              SelectionData newData) {
        SelectionData origData = comp.getSelection().getData();
        SelectionData combined = combinator.combine(origData, newData, comp.getCanvas());

        if (SelectionData.selectsNothing(combined)) {
            handleEmptyCombination(draftSelection, origData);
        } else {
            finalizeCombination(draftSelection, combined, origData);
        }
    }

    private void handleEmptyCombination(Selection draftSelection,
                                        SelectionData origData) {
        // restore the original selection here so that the undo edit
        // in deselect(true) captures the correct backup
        draftSelection.setData(origData);

        comp.promoteSelection();
        comp.deselect(true);

        Messages.showInfo("Nothing Selected",
            "As a result of the "
                + combinator.toString().toLowerCase(Locale.ENGLISH)
                + " operation, nothing is selected now.",
            comp.getDialogParent());
    }

    private void finalizeCombination(Selection draftSelection,
                                     SelectionData combined,
                                     SelectionData origData) {
        draftSelection.setData(combined);
        comp.promoteSelection();

        History.add(new SelectionChangeEdit(
            combinator.getHistoryName(), comp, origData));
    }

    private void finalizeNewSelection(Selection draftSelection,
                                      SelectionData newData) {
        // we can get here if either (1) a new selection
        // was created or (2) a selection was replaced
        draftSelection.setData(newData);
        comp.promoteSelection();

        PixelitorEdit edit = (prevSelData != null)
            ? new SelectionChangeEdit(combinator.getHistoryName(), comp, prevSelData)
            : new NewSelectionEdit(comp, newData);
        History.add(edit);
    }

    /**
     * Cancels the selection building process if it hasn't been finalized.
     */
    public void cancelIfNotFinalized() {
        if (finalized) {
            return;
        }

        Selection draftSelection = comp.getDraftSelection();
        if (draftSelection != null) {
            draftSelection.dispose();
            comp.setDraftSelection(null);
        }

        // restore original selection state
        var selection = comp.getSelection();
        if (selection != null) {
            selection.setFrozen(wasFrozen);
            selection.setHidden(wasHidden);
        }
    }
}
