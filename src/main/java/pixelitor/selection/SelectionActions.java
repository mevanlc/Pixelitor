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

import pixelitor.AppMode;
import pixelitor.Composition;
import pixelitor.Views;
import pixelitor.compactions.Crop;
import pixelitor.filters.gui.EnumParam;
import pixelitor.filters.gui.ParamAdjustmentListener;
import pixelitor.filters.gui.RangeParam;
import pixelitor.gui.GUIText;
import pixelitor.gui.View;
import pixelitor.gui.utils.DialogBuilder;
import pixelitor.gui.utils.GridBagHelper;
import pixelitor.gui.utils.TaskAction;
import pixelitor.history.History;
import pixelitor.history.SelectionChangeEdit;
import pixelitor.menus.view.ShowHideSelectionAction;
import pixelitor.tools.Tools;
import pixelitor.utils.Messages;
import pixelitor.utils.Threads;
import pixelitor.utils.ViewActivationListener;

import javax.swing.*;
import java.awt.GridBagLayout;
import java.awt.Shape;

import static pixelitor.Views.getActiveComp;
import static pixelitor.utils.Texts.i18n;

/**
 * Static methods related to actions that should be enabled
 * only when there is a selection in the active composition.
 */
public final class SelectionActions {
    // the selection stored for copy/paste; it can be shared with
    // the compositions, because the selection data is immutable
    private static SelectionData clipboardData = null;

    private static final Action crop = new TaskAction(i18n("crop_selection"),
        Crop::cropActiveCompToSelection);

    private static final Action inverseCrop = new TaskAction(i18n("inverse_crop"),
        Crop::inverseCropActiveComp);

    private static final Action deselect = new TaskAction(i18n("deselect"), () ->
        getActiveComp().deselect(true));

    private static final Action invert = new TaskAction(i18n("invert_sel"), () ->
        getActiveComp().invertSelection());

    private static final ShowHideSelectionAction showHide = new ShowHideSelectionAction();

    private static final Action convertToPath = new TaskAction("Convert to Path", () ->
        selectionToPath(getActiveComp(), true));

    private static final Action copySel = new TaskAction(i18n("copy_sel"),
        SelectionActions::copySelection);

    /**
     * @noinspection NonFinalStaticVariableUsedInClassInitialization
     */
    private static final Action pasteSel = new TaskAction(i18n("paste_sel"), () -> {
        SelectionChangeResult result = getActiveComp().updateSelectionInteractively(clipboardData);
        if (result.isSuccess()) {
            History.add(result.getEdit());
            Tools.notifySelectionChanged();
        } else {
            result.showInfoDialog("pasted selection");
        }
    });

    private static final Action modify = new TaskAction(i18n("modify_sel") + "...",
        SelectionActions::showModifySelectionDialog);

    static {
        initPasteSelAction();
        update(null);
    }

    private SelectionActions() {
        // prevent instantiation
    }

    private static void initPasteSelAction() {
        pasteSel.setEnabled(false);
        Views.addActivationListener(new ViewActivationListener() {
            @Override
            public void viewActivated(View oldView, View newView) {
                // enable the paste selection menu only if a selection has been copied
                pasteSel.setEnabled(clipboardData != null);
            }

            @Override
            public void allViewsClosed() {
                // disable the paste selection menu when no views are open
                pasteSel.setEnabled(false);
            }
        });
    }

    /**
     * Copies the active composition's selection to the internal clipboard,
     * preserving its exact coverage.
     */
    private static void copySelection() {
        clipboardData = getActiveComp().getSelectionData();
        pasteSel.setEnabled(true);
    }

    /**
     * Converts the selection into a path along its 50% contour.
     */
    public static void selectionToPath(Composition comp, boolean addToHistory) {
        Shape outline = comp.getSelection().getShape();
        if (outline.getBounds().isEmpty()) {
            // a selection that never reaches 50% coverage has no contour:
            // report it instead of discarding the low-coverage selection
            Messages.showInfo("No Path Created",
                "The selection is too faint to have a 50% contour, " +
                    "so no path can be created from it.",
                comp.getDialogParent());
            return;
        }
        comp.deselect(false);
        comp.createPathFromImShape(outline, addToHistory, true);
    }

    private static void showModifySelectionDialog() {
        View view = Views.getActive();
        Composition comp = view.getComp();
        Selection selection = comp.getSelection();
        // captured once, so that every preview starts from the original
        SelectionData originalData = selection.getData();

        JPanel panel = new JPanel(new GridBagLayout());
        var gbh = new GridBagHelper(panel);

        RangeParam amount = new RangeParam("Amount (pixels)", 0, 0, 100);
        gbh.addFullRow(amount.createGUI("amount"));

        EnumParam<SelectionModifyType> type = SelectionModifyType.asParam();
        gbh.addLabelAndControl(GUIText.TYPE + ":", type.createGUI("type"));

        DialogBuilder builder = new DialogBuilder()
            .content(panel)
            .title(i18n("modify_sel"));

        // listener for live preview
        ParamAdjustmentListener previewUpdater = () -> {
            SelectionData preview = modifySelection(originalData, type, amount, comp);

            // update the selection for preview (no history)
            selection.setData(preview != null ? preview : originalData);
            view.repaint();
        };

        amount.setAdjustmentListener(previewUpdater);
        type.setAdjustmentListener(previewUpdater);

        builder.okAction(() -> {
            SelectionData finalData = modifySelection(originalData, type, amount, comp);

            if (finalData == null) {
                // nothing is selected anymore, deselect and add history
                selection.setData(originalData); // ensure correct undo
                comp.deselect(true);
            } else if (!amount.isZero()) {
                // the result is valid and different, update it and add history
                selection.setData(finalData);
                var edit = new SelectionChangeEdit(
                    "Modify Selection", comp, originalData);
                History.add(edit);
                Tools.notifySelectionChanged();
                view.repaint();
            }
            // else: the result is valid but the same as the original, do nothing
        });

        builder.cancelAction(() -> {
            // restore the exact original representation on cancel
            selection.setData(originalData);
            view.repaint();
        });

        // initial preview update
        previewUpdater.paramAdjusted();

        builder.show();
    }

    /**
     * Applies the dialog settings to the original selection, always
     * starting from it and never from the previous preview.
     */
    private static SelectionData modifySelection(SelectionData originalData,
                                                 EnumParam<SelectionModifyType> type,
                                                 RangeParam amount,
                                                 Composition comp) {
        SelectionData modified = type.getSelected().modify(
            originalData, amount.getValue(), comp.getCanvas());
        return modified == null ? null : modified.clippedTo(comp.getCanvas());
    }

    /**
     * Enables or disables selection-related actions
     * based on the active composition's selection state.
     */
    public static void update(Composition comp) {
        assert Threads.calledOnEDT() || AppMode.isUnitTesting();

        Selection selection = comp == null ? null : comp.getSelection();
        boolean hasSelection = selection != null;

        crop.setEnabled(hasSelection);
        inverseCrop.setEnabled(hasSelection);
        deselect.setEnabled(hasSelection);
        invert.setEnabled(hasSelection);
        showHide.setEnabled(hasSelection);
        modify.setEnabled(hasSelection);
        convertToPath.setEnabled(hasSelection);
        copySel.setEnabled(hasSelection);
        // pasteSel is handled separately by its ViewActivationListener

        if (comp != null) {
            if (comp.isActive()) {
                showHide.updateTextFrom(selection);
            } else {
                // this method should only be called with the active
                // composition (or null if all views are closed)
                throw new IllegalStateException();
            }
        }
    }

    /**
     * Returns true if selection-dependent actions (excluding paste) are currently enabled.
     */
    public static boolean areEnabled() {
        // check any action updated by the update method
        return crop.isEnabled();
    }

    public static Action getCrop() {
        return crop;
    }

    public static Action getInverseCrop() {
        return inverseCrop;
    }

    public static Action getDeselect() {
        return deselect;
    }

    public static Action getInvert() {
        return invert;
    }

    public static ShowHideSelectionAction getShowHide() {
        return showHide;
    }

    public static Action getConvertToPath() {
        return convertToPath;
    }

    public static Action getModify() {
        return modify;
    }

    public static Action getCopy() {
        return copySel;
    }

    public static Action getPaste() {
        return pasteSel;
    }
}
