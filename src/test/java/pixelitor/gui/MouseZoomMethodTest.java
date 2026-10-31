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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pixelitor.TestHelper;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MouseZoomMethodTest {
    @BeforeAll
    static void beforeAll() {
        TestHelper.setUnitTestingMode();
    }

    @AfterEach
    void afterEach() {
        GlobalEvents.setSpaceDown(false);
    }

    @Test
    void fractionalTrackpadZoomKeepsSlowerRateAndResetsOnReversal() {
        View view = mock(View.class);
        MouseWheelListener listener = installOnView(view);
        JPanel source = new JPanel();
        for (int i = 0; i < 7; i++) {
            listener.mouseWheelMoved(wheel(source, 1000 + i, 0, 0.5));
        }
        verify(view, never()).zoomOut(any(Point.class));
        listener.mouseWheelMoved(wheel(source, 1007, 0, 0.5));
        verify(view).zoomOut(new Point(10, 20));

        for (int i = 0; i < 6; i++) {
            listener.mouseWheelMoved(wheel(source, 1010 + i, 0, 0.5));
        }
        for (int i = 0; i < 8; i++) {
            listener.mouseWheelMoved(wheel(source, 1020 + i, 0, -0.5));
        }
        verify(view).zoomIn(new Point(10, 20));
        verify(view, times(1)).zoomOut(any(Point.class));
    }

    @Test
    void trackpadRemaindersDoNotCarryAcrossGestures() {
        View view = mock(View.class);
        MouseWheelListener listener = installOnView(view);
        JPanel source = new JPanel();
        for (int i = 0; i < 7; i++) {
            listener.mouseWheelMoved(wheel(source, 1000 + i, 0, 0.5));
        }
        listener.mouseWheelMoved(wheel(source, 2000, 0, 0.5));
        verify(view, never()).zoomOut(any(Point.class));
    }

    @Test
    void ctrlWheelForwardsUnmodifiedScrollToParent() {
        JPanel parent = new JPanel();
        JPanel child = new JPanel();
        parent.add(child);
        AtomicInteger rotations = new AtomicInteger();
        parent.addMouseWheelListener(e -> rotations.addAndGet(e.getWheelRotation()));
        MouseZoomMethod.CTRL_WHEEL.installOnOther(child);
        MouseWheelEvent event = wheel(child, 1000, 1, 1.0);

        child.getMouseWheelListeners()[0].mouseWheelMoved(event);

        assertThat(rotations.get()).isEqualTo(1);
        assertThat(event.isConsumed()).isTrue();
    }

    @Test
    void spaceWheelPansInsteadOfZooming() {
        View view = mock(View.class);
        MouseWheelListener listener = installOnView(view);
        GlobalEvents.setSpaceDown(true);

        listener.mouseWheelMoved(wheel(new JPanel(), 1000, 1, 1.0));

        verify(view).panViewport(0, 20);
        verify(view, never()).zoomOut(any(Point.class));
    }

    private static MouseWheelListener installOnView(View view) {
        when(view.getMouseWheelListeners()).thenReturn(new MouseWheelListener[0]);
        MouseZoomMethod.WHEEL.installOnView(view);
        ArgumentCaptor<MouseWheelListener> listener = ArgumentCaptor.forClass(MouseWheelListener.class);
        verify(view).addMouseWheelListener(listener.capture());
        return listener.getValue();
    }

    private static MouseWheelEvent wheel(Component source, long when, int rotation, double preciseRotation) {
        return new MouseWheelEvent(source, MouseEvent.MOUSE_WHEEL, when, 0,
            10, 20, 10, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1, rotation, preciseRotation);
    }
}
