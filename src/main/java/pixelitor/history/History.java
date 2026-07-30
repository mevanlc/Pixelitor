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

import pixelitor.AppMode;
import pixelitor.Composition;
import pixelitor.Invariants;
import pixelitor.Views;
import pixelitor.layers.Drawable;
import pixelitor.utils.AppPreferences;
import pixelitor.utils.Messages;
import pixelitor.utils.debug.DebugNode;
import pixelitor.utils.test.RandomGUITest;

import javax.swing.event.UndoableEditListener;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoableEditSupport;
import java.util.List;

import static java.lang.String.format;

/**
 * Static methods for managing the editing history and undo/redo.
 */
public class History {
    private static final UndoableEditSupport editSupport = new UndoableEditSupport();
    private static final PixelitorUndoManager undoManager = new PixelitorUndoManager();
    private static int numUndoneEdits = 0;

    private static HistoryChecker checker;

    // quietly ignores new edits if true
    private static boolean ignoreEdits = false;

    // it's a program error to add edits if true
    private static boolean rejectEdits = false;

    // if not null, new edits are collected into it instead of
    // being added to the history individually (see startTransaction)
    private static MultiEdit transaction = null;

    static {
        setUndoLevels(AppPreferences.loadUndoLevels());
    }

    private History() {
    }

    /**
     * Adds a new edit to the history.
     */
    public static void add(PixelitorEdit edit) {
        assert edit != null;
        if (rejectEdits) {
            // prevent accidentally adding edits during undo/redo
            if (AppMode.isDevelopment()) {
                throw new IllegalStateException();
            } else {
                return;
            }
        }
        if (ignoreEdits) {
            return;
        }
        if (transaction != null && edit.canUndo()) {
            // collect the edit instead of adding it to the history:
            // it will become a child of the transaction's combined edit.
            // This runs before the checker is notified, because only the
            // combined edit will ever reach the undo/redo stacks.
            if (edit.makesDirty()) {
                edit.getComp().setDirty(true);
            }
            edit.setEmbedded(true);
            transaction.add(edit);
            return;
        }
        if (checker != null && edit.canUndo()) {
            checker.registerAdd(edit.getName());
        }

        if (edit.makesDirty()) {
            edit.getComp().setDirty(true);
        }

        if (edit.canUndo()) {
            undoManager.addEdit(edit);
        } else {
            // a non-undoable edit invalidates the whole history,
            // including any edits collected by an open transaction
            transaction = null;
            undoManager.discardAllEdits();
        }

        // reset BEFORE posting, so that the fade menu item can become enabled
        numUndoneEdits = 0;
        notifyMenus(edit);

        if (AppMode.isDevelopment()) {
            Invariants.checkAll(edit.getComp());
        }
    }

    /**
     * Starts collecting the subsequent edits so that they can be undone
     * and redone together, as a single edit with the given name.
     * <p>
     * Every started transaction must be closed by {@link #endTransaction()}
     * or {@link #abortTransaction()}, preferably from a finally block,
     * because a leaked transaction silently swallows all later edits.
     * Transactions can't be nested.
     */
    public static void startTransaction(String name, Composition comp) {
        assert transaction == null : "already in the transaction " + transaction.getName();

        transaction = new MultiEdit(name, comp);
    }

    /**
     * Ends the collecting of edits, and adds the combined edit to the history.
     * Returns the added edit, or null if no undoable edit was collected.
     */
    public static PixelitorEdit endTransaction() {
        MultiEdit collected = transaction;
        transaction = null;
        if (collected == null || collected.isEmpty()) {
            return null;
        }

        List<PixelitorEdit> children = collected.getChildren();
        PixelitorEdit combined;
        if (children.size() == 1) {
            // don't wrap a single edit into a group with a different name
            combined = children.getFirst();
            combined.setEmbedded(false);
        } else {
            combined = collected;
        }

        add(combined);
        return combined;
    }

    /**
     * Ends the collecting of edits by undoing the collected edits and
     * discarding them. Nothing is added to the history.
     */
    public static void abortTransaction() {
        MultiEdit collected = transaction;
        transaction = null;
        if (collected == null || collected.isEmpty()) {
            return;
        }

        collected.undo();
        collected.die();
    }

    public static boolean isInTransaction() {
        return transaction != null;
    }

    public static void undo() {
        if (transaction != null) {
            // an interactive gesture is in progress: undoing now would undo
            // the edit before it while its changes are still uncommitted
            return;
        }

        try {
            // increase it before calling undoManager.undo()
            // so that the result of undo is not fadeable
            numUndoneEdits++;
            undoManager.undo();
        } catch (CannotUndoException e) {
            handleUndoRedoException(e, "undo");
        }
    }

    public static void redo() {
        if (transaction != null) {
            return; // see undo()
        }

        try {
            numUndoneEdits--; // after redo we should be fadeable again
            undoManager.redo();
        } catch (CannotRedoException e) {
            handleUndoRedoException(e, "redo");
        }
    }

    private static void handleUndoRedoException(RuntimeException e, String action) {
        if (RandomGUITest.isRunning()) {
            throw new RuntimeException("No " + action + " available", e);
        }
        Messages.showWarning("Can't " + action,
            "<html>No " + action + " is available. Possible reasons:<ul>" +
                "<li>The edited image was closed" +
                "<li>The " + action + " image was discarded by Pixelitor in order to save memory");
        clear();
    }

    public static void compClosed(Composition closedComp) {
        // Try to minimize the number "no undo/redo is available" dialogs
        // by proactively discarding the edits if the next attempted edit
        // would result in such a message.
        PixelitorEdit nextUndo = undoManager.getEditToBeUndone();
        if (nextUndo != null && nextUndo.getComp() == closedComp) {
            clear();
            return;
        }
        PixelitorEdit nextRedo = undoManager.getEditToBeRedone();
        if (nextRedo != null && nextRedo.getComp() == closedComp) {
            clear();
        }
    }

    public static void notifyMenus(PixelitorEdit edit) {
        editSupport.postEdit(edit);
    }

    public static void notifyMenus() {
        notifyMenus(null);
    }

    public static String getUndoPresentationName() {
        return undoManager.getUndoPresentationName();
    }

    public static String getRedoPresentationName() {
        return undoManager.getRedoPresentationName();
    }

    public static boolean canUndo() {
        return undoManager.canUndo();
    }

    public static boolean canRedo() {
        return undoManager.canRedo();
    }

    public static void addUndoableEditListener(UndoableEditListener listener) {
        editSupport.addUndoableEditListener(listener);
    }

    public static void setUndoLevels(int undoLevels) {
        undoManager.setLimit(undoLevels);
    }

    public static int getUndoLevels() {
        return undoManager.getHeavyEditLimit();
    }

    /**
     * Used for the name of the fade/repeat menu items
     */
    public static String getLastEditName() {
        PixelitorEdit lastEdit = undoManager.getLastEdit();
        if (lastEdit != null) {
            return lastEdit.getName();
        }
        return "";
    }

    public static PixelitorEdit getLastEdit() {
        return undoManager.getLastEdit();
    }

    public static PixelitorEdit getEditToBeUndone() {
        return undoManager.getEditToBeUndone();
    }

    public static int getNumEdits() {
        return undoManager.getSize();
    }

    /**
     * If the last edit in the history is a FadeableEdit for the given
     * {@link Drawable}, return it, otherwise return null.
     */
    public static FadeableEdit getPreviousEditForFade(Drawable dr) {
        if (numUndoneEdits > 0 || dr == null) {
            return null;
        }
        PixelitorEdit lastEdit = undoManager.getLastEdit();
        if (lastEdit instanceof FadeableEdit fadeableEdit) {
            if (!fadeableEdit.isFadeable()) {
                return null;
            }

            Drawable lastLayer = fadeableEdit.getFadingLayer();
            if (dr != lastLayer) {
                // this happens if the active image layer has changed
                // since the last edit, for example by going to mask edit
                return null;
            }
            return fadeableEdit;
        }
        return null;
    }

    public static boolean canFade() {
        Composition comp = Views.getActiveComp();
        if (comp == null) {
            return false;
        }
        Drawable dr = comp.getActiveDrawable();
        if (dr == null) {
            return false;
        }

        return canFade(dr);
    }

    public static boolean canFade(Drawable dr) {
        return getPreviousEditForFade(dr) != null;
    }

    public static void onAllViewsClosed() {
        numUndoneEdits = 0;

        transaction = null;
        undoManager.discardAllEdits();
        notifyMenus();
    }

    public static void showHistoryDialog() {
        undoManager.showHistoryDialog();
    }

    public static void clear() {
        transaction = null;
        undoManager.discardAllEdits();
        assertNumEditsIs(0);

        UndoAction.INSTANCE.setEnabled(false);
        RedoAction.INSTANCE.setEnabled(false);
    }

    public static void assertNumEditsIs(int expected) {
        int numEdits = undoManager.getSize();
        if (numEdits != expected) {
            throw new AssertionError(format(
                "Expected %d edits, but found %d", expected, numEdits));
        }
    }

    public static void assertLastEditNameIs(String expected) {
        String lastEditName = undoManager.getLastEdit().getName();
        if (!lastEditName.equals(expected)) {
            throw new AssertionError(format(
                "Expected '%s' as the last edit name, but found '%s'",
                expected, lastEditName));
        }
    }

    /**
     * Asserts that the name of the next edit to be undone is the given string.
     */
    public static void assertEditToBeUndoneNameIs(String expected) {
        String name = getEditToBeUndoneName();
        if (!name.equals(expected)) {
            throw new AssertionError(format(
                "Expected '%s', found '%s'", expected, name));
        }
    }

    public static String getEditToBeUndoneName() {
        PixelitorEdit editToBeUndone = undoManager.getEditToBeUndone();
        if (editToBeUndone == null) {
            throw new AssertionError("there is no edit to be undone");
        }
        return editToBeUndone.getName();
    }

    public static void assertEditToBeRedoneNameIs(String expected) {
        String name = getEditToBeRedoneName();
        if (!name.equals(expected)) {
            throw new AssertionError(format(
                "Expected '%s', found '%s'", expected, name));
        }
    }

    public static String getEditToBeRedoneName() {
        PixelitorEdit editToBeRedone = undoManager.getEditToBeRedone();
        if (editToBeRedone == null) {
            throw new AssertionError("there is no edit to be redone");
        }
        return editToBeRedone.getName();
    }

    public static void setIgnoreEdits(boolean ignoreEdits) {
        History.ignoreEdits = ignoreEdits;
    }

    public static void setRejectEdits(boolean rejectEdits) {
        History.rejectEdits = rejectEdits;
    }

    public static DebugNode createDebugNode() {
        var node = new DebugNode("history", undoManager);

        node.addInt("num edits", undoManager.getSize());
        if (undoManager.hasEdits()) {
            node.add(undoManager.createDebugNode("edits"));
        }

        node.addInt("num undone edits", numUndoneEdits);
        node.addBoolean("ignore edits", ignoreEdits);
        node.addBoolean("can undo", canUndo());
        node.addBoolean("can redo", canRedo());
        node.addBoolean("can fade", canFade());

        return node;
    }

    /**
     * Returns the names of all edits in the current history.
     */
    public static List<String> getEditNames() {
        return undoManager.getEditNames();
    }

    public static void undo(String editName) {
        if (checker != null) {
            checker.registerUndo(editName);
        }
        assertEditToBeUndoneNameIs(editName);

        undo();
    }

    public static void redo(String editName) {
        if (checker != null) {
            checker.registerRedo(editName);
        }
        assertEditToBeRedoneNameIs(editName);

        redo();
    }

    public static void undoRedo(String editName) {
        undo(editName);
        redo(editName);
    }

    public static void setChecker(HistoryChecker checker) {
        History.checker = checker;
    }
}
