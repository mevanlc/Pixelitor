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

import pixelitor.Composition;
import pixelitor.gui.View;
import pixelitor.layers.LayerMask;

import javax.swing.*;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ResourceBundle;

/**
 * Samples image pixels through a modal overlay of the visible canvas.
 * The overlay keeps clicks away from editing tools and leaves the parent
 * color dialog's modal event loop running throughout sampling.
 */
final class CanvasColorSampler {
    private static final String HELP = ResourceBundle.getBundle(
        "com.bric.swing.resources.ColorPicker").getString("eyedropperHelp");

    private CanvasColorSampler() {
    }

    public static Color sample(Window owner, View view) {
        if (view == null || !view.isShowing()) {
            return null;
        }
        Rectangle visible = view.getVisibleRect();
        if (visible.isEmpty()) {
            return null;
        }

        BufferedImage preview = new BufferedImage(visible.width, visible.height,
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g = preview.createGraphics();
        try {
            g.setColor(view.getBackground());
            g.fillRect(0, 0, preview.getWidth(), preview.getHeight());
            g.translate(-visible.x, -visible.y);
            view.paint(g);
        } finally {
            g.dispose();
        }

        JDialog dialog = new JDialog(owner, HELP, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setName("canvasEyedropper");
        dialog.setUndecorated(true);
        SamplingPanel panel = new SamplingPanel(view, visible, preview, dialog);
        panel.setName("canvasSamplingPanel");
        dialog.setContentPane(panel);
        dialog.getRootPane().registerKeyboardAction(_ -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        Point location = view.getLocationOnScreen();
        location.translate(visible.x, visible.y);
        dialog.setBounds(location.x, location.y, visible.width, visible.height);

        try {
            dialog.setVisible(true);
            return panel.selectedColor;
        } finally {
            dialog.dispose();
        }
    }

    private static class SamplingPanel extends JPanel {
        private final View view;
        private final Rectangle visible;
        private final BufferedImage preview;
        private final BufferedImage source;
        private final int sourceTx;
        private final int sourceTy;
        private Color hoverColor;
        private Color selectedColor;

        private SamplingPanel(View view, Rectangle visible, BufferedImage preview, JDialog dialog) {
            this.view = view;
            this.visible = visible;
            this.preview = preview;
            Composition comp = view.getComp();
            if (view.getMaskViewMode().isShowingMask()) {
                LayerMask mask = comp.getActiveLayer().getMask();
                source = mask.getImage();
                sourceTx = mask.getTx();
                sourceTy = mask.getTy();
            } else {
                source = comp.getCompositeImage();
                sourceTx = 0;
                sourceTy = 0;
            }
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

            MouseAdapter mouseHandler = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hoverColor = colorAt(e.getPoint());
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        dialog.dispose();
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        Color color = colorAt(e.getPoint());
                        if (color != null) {
                            selectedColor = color;
                            dialog.dispose();
                        }
                    }
                }
            };
            addMouseListener(mouseHandler);
            addMouseMotionListener(mouseHandler);
        }

        private Color colorAt(Point position) {
            Point2D imagePoint = view.getComponentToImageTransform().transform(
                new Point(position.x + visible.x, position.y + visible.y), null);
            int x = (int) Math.floor(imagePoint.getX());
            int y = (int) Math.floor(imagePoint.getY());
            if (x < 0 || y < 0 || x >= view.getComp().getCanvasWidth()
                || y >= view.getComp().getCanvasHeight()) {
                return null;
            }
            x -= sourceTx;
            y -= sourceTy;
            if (x < 0 || y < 0 || x >= source.getWidth() || y >= source.getHeight()) {
                return null;
            }
            return new Color(source.getRGB(x, y), true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.drawImage(preview, 0, 0, null);

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FontMetrics fm = g2.getFontMetrics();
                String text = hoverColor == null ? HELP
                    : HELP + "  #" + Colors.toHtmlHex(hoverColor);
                int width = fm.stringWidth(text) + 24;
                int height = fm.getHeight() + 16;
                int x = Math.max(0, (getWidth() - width) / 2);
                int y = Math.max(0, getHeight() - height - 12);
                g2.setColor(new Color(0, 0, 0, 210));
                g2.fillRoundRect(x, y, width, height, 12, 12);
                g2.setColor(Color.WHITE);
                g2.drawString(text, x + 12, y + 8 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }
}
