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

package pixelitor.tools.selection;

import pixelitor.Composition;
import pixelitor.filters.gui.RangeParam;
import pixelitor.filters.gui.UserPreset;
import pixelitor.gui.View;
import pixelitor.gui.utils.SliderSpinner;
import pixelitor.selection.SelectionMask;
import pixelitor.selection.SelectionType;
import pixelitor.tools.ToolIcons;
import pixelitor.tools.Tools;
import pixelitor.tools.util.OverlayType;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.utils.Cursors;
import pixelitor.utils.ImageUtils;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ResourceBundle;
import java.util.function.Consumer;

import static pixelitor.gui.utils.SliderSpinner.LabelPosition.WEST;

/**
 * A tool that creates selections based on color similarity by clicking.
 */
public class MagicWandSelectionTool extends AbstractSelectionTool {
    private static final String PRESET_KEY_TOLERANCE = "Tolerance";
    private static final int DEFAULT_TOLERANCE = 20;

    private final RangeParam toleranceParam = new RangeParam("Tolerance", 0, DEFAULT_TOLERANCE, 255);
    private final SliderSpinner toleranceSlider = new SliderSpinner(toleranceParam, WEST, false);

    public MagicWandSelectionTool() {
        super("Magic Wand Selection", 'W',
            "<b>click</b> on the area you want to select. " +
                "<b>right-click</b> to cancel the selection.",
            Cursors.DEFAULT, false);
        repositionOnSpace = false;
        pixelSnapping = false;
    }

    @Override
    public void initSettingsPanel(ResourceBundle resources) {
        super.initSettingsPanel(resources);

        settingsPanel.add(toleranceSlider);
    }

    @Override
    protected void dragStarted(PMouseEvent e) {
        // ignored, magic wand is click-based
    }

    @Override
    protected void ongoingDrag(PMouseEvent e) {
        // ignored, magic wand is click-based
    }

    @Override
    protected void dragFinished(PMouseEvent e) {
        // ignored, magic wand is click-based
    }

    @Override
    public void mouseClicked(PMouseEvent e) {
        Composition comp = e.getComp();

        if (e.isRight()) {
            // right-click always cancels
            cancelSelection(comp);
        } else if (e.getClickCount() == 1) { // ignore the second click of a double click
            initCombinatorAndBuilder(e, SelectionType.MAGIC_WAND);

            try {
                // calculate the selection shape based on the click event
                selectionBuilder.updateDraftSelection(e);
                // combine the new shape with any existing selection
                selectionBuilder.combineShapes();

                // show the final selection
                View view = comp.getView();
                if (view != null) {
                    view.repaint();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                cancelSelection(comp);
            } finally {
                // clean up the builder and combinator
                cancelSelectionBuilder();
                resetCombinator();
            }
        }
    }

    @Override
    protected OverlayType getOverlayType() {
        // no overlay needed for a click-based tool
        return OverlayType.NONE;
    }

    public int getTolerance() {
        return toleranceParam.getValue();
    }

    @Override
    public void saveStateTo(UserPreset preset) {
        super.saveStateTo(preset);

        preset.putInt(PRESET_KEY_TOLERANCE, getTolerance());
    }

    @Override
    public void loadUserPreset(UserPreset preset) {
        super.loadUserPreset(preset);

        toleranceParam.setValue(preset.getInt(PRESET_KEY_TOLERANCE, DEFAULT_TOLERANCE));
    }

    /**
     * Creates hard selection coverage based on color similarity using a
     * flood-fill algorithm. Returns null if nothing gets selected.
     */
    public static SelectionMask createSelectionMask(PMouseEvent e) {
        // this implementation is based on the algorithm described at
        // https://losingfight.com/blog/2007/08/28/how-to-implement-a-magic-wand-tool/
        Composition comp = e.getComp();
        // the magic wand operates on the composite image
        BufferedImage image = comp.getCompositeImage();

        int width = image.getWidth();
        int height = image.getHeight();

        int x = (int) e.getImX();
        int y = (int) e.getImY();

        // select nothing if the click is outside the image bounds
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return null;
        }

        int[] pixels = ImageUtils.getPixels(image);
        int tolerance = Tools.MAGIC_WAND.getTolerance();

        // select pixels using flood-fill
        boolean[] mask = new boolean[width * height];
        ImageUtils.floodFill(pixels, width, height, x, y, tolerance,
            // mark the pixels in the segment as true in the mask
            (segY, segX1, segX2) -> {
                int offset = segY * width;
                for (int i = segX1; i <= segX2; i++) {
                    mask[offset + i] = true;
                }
            });

        return SelectionMask.fromBooleanMask(mask, width, height);
    }

    @Override
    public Consumer<Graphics2D> createIconPainter() {
        return ToolIcons::paintMagicWandSelectionIcon;
    }
}
