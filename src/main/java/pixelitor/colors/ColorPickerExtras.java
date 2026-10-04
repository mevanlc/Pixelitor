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

package pixelitor.colors;

import com.bric.swing.ColorPicker;
import pixelitor.Views;
import pixelitor.gui.View;
import pixelitor.gui.utils.VectorIcon;
import pixelitor.tools.Tools;
import pixelitor.utils.ImageUtils;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * Image sampling and shared recent-color controls for the color picker dialog.
 */
public final class ColorPickerExtras {
    private static final int SWATCH_COUNT = 20;
    private static final int SWATCH_SIZE = 20;
    private static final ResourceBundle STRINGS =
        ResourceBundle.getBundle("com.bric.swing.resources.ColorPicker");

    private ColorPickerExtras() {
    }

    public static JPanel createPanel(Window owner, ColorPicker picker, boolean includeOpacity) {
        Consumer<Color> selectColor = color -> picker.selectColor(includeOpacity
            ? color
            : new Color(color.getRGB()));

        JButton eyedropper = new JButton(STRINGS.getString("eyedropper"),
            VectorIcon.createToolIcon(Tools.COLOR_PICKER.createIconPainter()));
        eyedropper.setName("eyedropperButton");
        View view = Views.getActive();
        boolean canSample = view != null && view.isShowing() && !view.getVisibleRect().isEmpty();
        eyedropper.setEnabled(canSample);
        eyedropper.setToolTipText(STRINGS.getString(canSample
            ? "eyedropperTooltip" : "eyedropperNoImageTooltip"));
        eyedropper.addActionListener(_ -> {
            Color color = CanvasColorSampler.sample(owner, view);
            if (color != null) {
                selectColor.accept(color);
            }
        });

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.add(new JLabel(STRINGS.getString("recentColorsLabel")), BorderLayout.WEST);
        header.add(eyedropper, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(header, BorderLayout.NORTH);
        panel.add(createSwatches(selectColor), BorderLayout.CENTER);
        return panel;
    }

    private static JComponent createSwatches(Consumer<Color> selectColor) {
        List<Color> colors = ColorHistory.INSTANCE.getRecentColors(SWATCH_COUNT);
        if (colors.isEmpty()) {
            return new JLabel(STRINGS.getString("noRecentColors"));
        }

        JPanel swatches = new JPanel(new GridLayout(2, SWATCH_COUNT / 2, 4, 4));
        for (int i = 0; i < SWATCH_COUNT; i++) {
            if (i >= colors.size()) {
                swatches.add(new JPanel());
                continue;
            }
            Color color = colors.get(i);
            JButton button = new JButton(createSwatchIcon(color));
            button.setName("recentColor" + i);
            button.setMargin(new Insets(2, 2, 2, 2));
            button.setPreferredSize(new Dimension(28, 28));
            String hex = "#" + Colors.toHtmlHex(color);
            button.setToolTipText(hex);
            button.getAccessibleContext().setAccessibleName(
                STRINGS.getString("recentColorsLabel") + " " + hex);
            button.addActionListener(_ -> selectColor.accept(color));
            swatches.add(button);
        }
        return swatches;
    }

    private static Icon createSwatchIcon(Color color) {
        var checkerboard = ImageUtils.createCheckerboardPainter();
        return new VectorIcon(color, SWATCH_SIZE, SWATCH_SIZE, g -> {
            if (color.getAlpha() < 255) {
                checkerboard.paint(g, null, SWATCH_SIZE, SWATCH_SIZE);
            }
            g.setColor(color);
            g.fillRect(0, 0, SWATCH_SIZE, SWATCH_SIZE);
            g.setColor(Color.GRAY);
            g.drawRect(0, 0, SWATCH_SIZE - 1, SWATCH_SIZE - 1);
        });
    }
}
