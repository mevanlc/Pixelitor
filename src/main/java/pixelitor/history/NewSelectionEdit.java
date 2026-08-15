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
import pixelitor.selection.SelectionData;

import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import java.util.Objects;

/**
 * Represents the creation of a new selection.
 */
public class NewSelectionEdit extends PixelitorEdit {
    private final SelectionData newData;

    public NewSelectionEdit(Composition comp, SelectionData newData) {
        super("Create Selection", comp, newData.isMaskBacked());

        assert comp.isOpen();

        this.newData = Objects.requireNonNull(newData);
    }

    @Override
    public void undo() throws CannotUndoException {
        super.undo();

        assert comp.isOpen();

        comp.deselect(false);
    }

    @Override
    public void redo() throws CannotRedoException {
        super.redo();

        assert comp.isOpen();

        comp.createSelectionFrom(newData);
    }

    @Override
    public boolean makesDirty() {
        return false; // selections are not saved
    }
}
