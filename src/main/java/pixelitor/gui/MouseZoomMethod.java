/*
 * Copyright 2025 Laszlo Balazs-Csiki and Contributors
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
import java.awt.event.MouseWheelEvent;

/**
 * Methods for zooming with the mouse wheel: with or without holding the Ctrl key.
 */
public enum MouseZoomMethod {
    WHEEL("Mouse Wheel", "wheel") {
        @Override
        public void installOnView(View view) {
            removeExistingListeners(view);
            view.addMouseWheelListener(e -> viewZoomed(view, e));
        }

        @Override
        public void installOnOther(JComponent component) {
            component.addMouseWheelListener(MouseZoomMethod::otherZoomed);
        }
    }, CTRL_WHEEL("Ctrl + Mouse Wheel", "ctrl-wheel") {
        @Override
        public void installOnView(View view) {
            removeExistingListeners(view);

            view.addMouseWheelListener(e -> {
                if (e.isControlDown()) {
                    viewZoomed(view, e);
                }
            });
        }

        @Override
        public void installOnOther(JComponent component) {
            component.addMouseWheelListener(e -> {
                if (e.isControlDown()) {
                    otherZoomed(e);
                }
            });
        }
    };

    public static MouseZoomMethod ACTIVE = WHEEL;

    private final String displayName;
    private final String saveCode;

    MouseZoomMethod(String displayName, String saveCode) {
        this.displayName = displayName;
        this.saveCode = saveCode;
    }

    public abstract void installOnView(View view);

    // used on other components, like the Navigator, where the
    // exact mouse position doesn't matter
    public abstract void installOnOther(JComponent component);

    private static void viewZoomed(View view, MouseWheelEvent e) {
        zoom(view, e, e.getPoint());
    }

    private static void otherZoomed(MouseWheelEvent e) {
        View view = Views.getActive();
        if (view == null) {
            return; // all views are closed
        }
        zoom(view, e, null);
    }

    private static final String ACCUM_CLIENT_PROPERTY = "pixelitor.mouseZoomAccum";
    private static final String LAST_WHEN_CLIENT_PROPERTY = "pixelitor.mouseZoomLastWhen";

    // Trackpads generate a lot of small wheel deltas. Scaling them down makes zooming feel
    // less jumpy and gives finer control without changing the discrete zoom levels.
    private static final double TRACKPAD_ROTATION_SCALE = 0.25;
    private static final long TRACKPAD_ACCUM_RESET_MS = 250;
    private static final int MAX_TRACKPAD_STEPS_PER_EVENT = 3;

    /**
     * Performs discrete zoom steps based on the (possibly fractional) wheel delta.
     * <p>
     * Trackpads typically generate many small wheel events. Using
     * {@link MouseWheelEvent#getWheelRotation()} would collapse them to -1/0/1 and
     * make zooming extremely fast and jittery. Instead, use
     * {@link MouseWheelEvent#getPreciseWheelRotation()} and accumulate deltas until
     * they add up to a whole "notch".
     */
    private static void zoom(View view, MouseWheelEvent e, java.awt.Point coFocusPoint) {
        double preciseRotation = e.getPreciseWheelRotation();
        int wheelRotation = e.getWheelRotation();

        boolean hasPreciseRotation = preciseRotation != 0.0;
        double rotation = hasPreciseRotation ? preciseRotation : wheelRotation;
        if (rotation == 0.0) {
            return;
        }

        // prevent JScrollPane from also scrolling (which feels glitchy while zooming)
        e.consume();

        JComponent source = (e.getComponent() instanceof JComponent jc) ? jc : view;
        boolean looksLikeTrackpad = hasPreciseRotation && (wheelRotation == 0
            || Math.abs(preciseRotation - Math.rint(preciseRotation)) > 1e-6);

        if (looksLikeTrackpad) {
            long when = e.getWhen();
            long lastWhen = getLastWhen(source);
            if (lastWhen != 0 && when - lastWhen > TRACKPAD_ACCUM_RESET_MS) {
                // avoid carrying over tiny remainder deltas to a later gesture
                setAccumulatedRotation(source, 0.0);
            }
            setLastWhen(source, when);

            rotation *= TRACKPAD_ROTATION_SCALE;
        }

        double accum = getAccumulatedRotation(source) + rotation;

        int steps = (int) Math.floor(Math.abs(accum));
        if (looksLikeTrackpad) {
            steps = Math.min(steps, MAX_TRACKPAD_STEPS_PER_EVENT);
        }
        if (steps == 0) {
            setAccumulatedRotation(source, accum);
            return;
        }

        if (accum < 0) { // up, away from the user
            for (int i = 0; i < steps; i++) {
                view.zoomIn(coFocusPoint);
            }
            accum += steps;
        } else { // down, towards the user
            for (int i = 0; i < steps; i++) {
                view.zoomOut(coFocusPoint);
            }
            accum -= steps;
        }

        if (Math.abs(accum) < 1e-9) {
            accum = 0.0;
        }
        setAccumulatedRotation(source, accum);
    }

    private static double getAccumulatedRotation(JComponent c) {
        Object value = c.getClientProperty(ACCUM_CLIENT_PROPERTY);
        if (value instanceof Double d) {
            return d;
        }
        return 0.0;
    }

    private static void setAccumulatedRotation(JComponent c, double value) {
        c.putClientProperty(ACCUM_CLIENT_PROPERTY, value);
    }

    private static long getLastWhen(JComponent c) {
        Object value = c.getClientProperty(LAST_WHEN_CLIENT_PROPERTY);
        if (value instanceof Long l) {
            return l;
        }
        return 0L;
    }

    private static void setLastWhen(JComponent c, long when) {
        c.putClientProperty(LAST_WHEN_CLIENT_PROPERTY, when);
    }

    private static void removeExistingListeners(JComponent c) {
        var existingListeners = c.getMouseWheelListeners();
        if (existingListeners.length > 0) {
            assert existingListeners.length == 1;
            c.removeMouseWheelListener(existingListeners[0]);
        }
    }

    public static void loadFromPreferences() {
        String loadedCode = AppPreferences.loadMouseZoom();

        for (MouseZoomMethod method : values()) {
            if (method.saveCode().equals(loadedCode)) {
                ACTIVE = method;
                break;
            }
        }
    }

    public static void changeTo(MouseZoomMethod newMethod) {
        if (newMethod == ACTIVE) {
            return;
        }
        ACTIVE = newMethod;
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
}
