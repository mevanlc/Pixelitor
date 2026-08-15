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

package pixelitor.history;

import pixelitor.Composition;
import pixelitor.selection.Selection;
import pixelitor.selection.SelectionData;

import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import java.util.Objects;

/**
 * Represents the change of a selection
 * (via add, subtract, intersect, invert, modify, move).
 */
public class SelectionChangeEdit extends PixelitorEdit {
    private SelectionData backupData;

    public SelectionChangeEdit(String name, Composition comp, SelectionData backupData) {
        super(name, comp, isMaskBacked(comp, backupData));

        this.backupData = Objects.requireNonNull(backupData);
    }

    /**
     * A selection carrying a coverage mask uses much more
     * memory than a shape, so it's limited like the image edits.
     */
    private static boolean isMaskBacked(Composition comp, SelectionData backupData) {
        if (backupData.isMaskBacked()) {
            return true;
        }
        Selection selection = comp.getSelection();
        return selection != null && selection.getData().isMaskBacked();
    }

    @Override
    public void undo() throws CannotUndoException {
        super.undo();

        swapData();
    }

    @Override
    public void redo() throws CannotRedoException {
        super.redo();

        swapData();
    }

    private void swapData() {
        var selection = comp.getSelection();
        if (selection == null) {
            throw new IllegalStateException("no selection in " + comp.getName());
        }

        // the selection data is immutable, so it can be swapped without copying
        SelectionData tmp = selection.getData();
        selection.setData(backupData);
        backupData = tmp;
    }

    @Override
    public boolean makesDirty() {
        return false; // selections are not saved
    }
}
