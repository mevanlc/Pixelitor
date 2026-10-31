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

package pixelitor.gui;

import pixelitor.Views;
import pixelitor.utils.AppPreferences;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

/**
 * Methods for zooming with the mouse wheel: with or without holding the Ctrl key.
 */
public enum MouseZoomMethod {
    WHEEL("Mouse Wheel", "wheel", false),
    CTRL_WHEEL("Ctrl + Mouse Wheel", "ctrl-wheel", true);

    public static MouseZoomMethod active = WHEEL;

    private final String displayName;
    private final String saveCode;
    private final boolean requiresCtrl;

    MouseZoomMethod(String displayName, String saveCode, boolean requiresCtrl) {
        this.displayName = displayName;
        this.saveCode = saveCode;
        this.requiresCtrl = requiresCtrl;
    }

    public void installOnView(View view) {
        removeExistingListeners(view);
        view.addMouseWheelListener(new ZoomListener(view, requiresCtrl));
    }

    // used on other components, like the Navigator, where the
    // exact mouse position doesn't matter
    public void installOnOther(JComponent component) {
        removeExistingListeners(component);
        component.addMouseWheelListener(new ZoomListener(null, requiresCtrl));
    }

    private static void removeExistingListeners(JComponent c) {
        for (MouseWheelListener listener : c.getMouseWheelListeners()) {
            if (listener instanceof ZoomListener) {
                c.removeMouseWheelListener(listener);
            }
        }
    }

    private static void viewZoomed(View view, MouseWheelEvent e, int steps) {
        Point point = e.getPoint();
        if (steps < 0) { // up, away from the user
            for (int i = 0; i < -steps; i++) {
                view.zoomIn(point);
            }
        } else {  // down, towards the user
            for (int i = 0; i < steps; i++) {
                view.zoomOut(point);
            }
        }
    }

    private static void otherZoomed(int steps) {
        View view = Views.getActive();
        if (view == null) {
            return; // all views are closed
        }
        if (steps < 0) { // up, away from the user
            for (int i = 0; i < -steps; i++) {
                view.zoomIn();
            }
        } else {  // down, towards the user
            for (int i = 0; i < steps; i++) {
                view.zoomOut();
            }
        }
    }

    /**
     * Forwards an unhandled mouse wheel event up the ancestor hierarchy to the
     * nearest container that listens for wheel events (such as an enclosing JScrollPane).
     */
    private static void forwardToParent(Component source, MouseWheelEvent e) {
        if (source == null) {
            return;
        }
        Container parent = source.getParent();
        while (parent != null && parent.getMouseWheelListeners().length == 0) {
            parent = parent.getParent();
        }
        if (parent != null) {
            parent.dispatchEvent(SwingUtilities.convertMouseEvent(source, e, parent));
            e.consume();
        }
    }

    public static void loadFromPreferences() {
        String loadedCode = AppPreferences.loadMouseZoomCode();

        for (MouseZoomMethod method : values()) {
            if (method.saveCode().equals(loadedCode)) {
                active = method;
                break;
            }
        }
    }

    public static void changeTo(MouseZoomMethod newMethod) {
        if (newMethod == active) {
            return;
        }
        active = newMethod;
        Views.forEach(newMethod::installOnView);
        Navigator.setMouseZoomMethod(newMethod);
    }

    public String saveCode() {
        return saveCode;
    }

    @Override
    public String toString() {
        return displayName;
    }

    private static final int SCROLL_PAN_STEP = 20;
    private static final double TRACKPAD_ROTATION_SCALE = 0.25;
    private static final long TRACKPAD_ACCUM_RESET_MS = 250;
    private static final int MAX_TRACKPAD_STEPS_PER_EVENT = 3;

    private static void panWithWheel(View view, MouseWheelEvent e) {
        int rotation = e.getWheelRotation();
        if (rotation == 0) {
            return;
        }
        e.consume();
        int delta = rotation * SCROLL_PAN_STEP;
        if (e.isShiftDown()) {
            view.panViewport(delta, 0);
        } else {
            view.panViewport(0, delta);
        }
    }

    private static class ZoomListener implements MouseWheelListener {
        private final View view;
        private final boolean requiresCtrl;
        private double rotationAccumulator = 0.0;
        private long lastWhen = 0;

        ZoomListener(View view, boolean requiresCtrl) {
            this.view = view;
            this.requiresCtrl = requiresCtrl;
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            if (GlobalEvents.isSpaceDown()) {
                rotationAccumulator = 0.0;
                lastWhen = 0;
                View target = view != null ? view : Views.getActive();
                if (target != null) {
                    panWithWheel(target, e);
                }
                return;
            }
            if (requiresCtrl && !e.isControlDown()) {
                rotationAccumulator = 0.0;
                lastWhen = 0;
                Component source = (view != null) ? view : e.getComponent();
                forwardToParent(source, e);
                return;
            }

            double preciseRotation = e.getPreciseWheelRotation();
            int wheelRotation = e.getWheelRotation();
            double rotation = preciseRotation != 0.0 ? preciseRotation : wheelRotation;
            if (rotation == 0.0) {
                return;
            }

            // Slow down trackpad zoom and discard remainders between gestures.
            boolean looksLikeTrackpad = preciseRotation != 0.0 && (wheelRotation == 0
                || Math.abs(preciseRotation - Math.rint(preciseRotation)) > 1e-6);
            if (looksLikeTrackpad) {
                long when = e.getWhen();
                if (lastWhen != 0 && when - lastWhen > TRACKPAD_ACCUM_RESET_MS) {
                    rotationAccumulator = 0.0;
                }
                lastWhen = when;
                rotation *= TRACKPAD_ROTATION_SCALE;
            }

            // reset partial ticks if the user reversed the scroll direction
            if ((rotationAccumulator > 0 && rotation < 0) || (rotationAccumulator < 0 && rotation > 0)) {
                rotationAccumulator = 0.0;
            }

            rotationAccumulator += rotation;
            int steps = (int) rotationAccumulator;
            if (looksLikeTrackpad) {
                steps = Math.clamp(steps, -MAX_TRACKPAD_STEPS_PER_EVENT, MAX_TRACKPAD_STEPS_PER_EVENT);
            }

            if (steps != 0) {
                rotationAccumulator -= steps;
                if (view != null) {
                    viewZoomed(view, e, steps);
                } else {
                    otherZoomed(steps);
                }
            }

            e.consume();
        }
    }
}
