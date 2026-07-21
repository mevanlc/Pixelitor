/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pixelitor.TestHelper;
import pixelitor.gui.View;
import pixelitor.tools.util.PMouseEvent;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;

import static org.assertj.core.api.Assertions.assertThat;

class TransformModifiersTest {
    private static View view;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
        view = TestHelper.createEmptyComp("TransformModifiersTest").getView();
    }

    @Test
    void macUsesCommandAsTheMenuModifier() {
        PMouseEvent command = event(InputEvent.META_DOWN_MASK);
        PMouseEvent control = event(InputEvent.CTRL_DOWN_MASK);

        assertThat(TransformModifiers.from(command, true).menu()).isTrue();
        assertThat(TransformModifiers.from(control, true).menu()).isFalse();
    }

    @Test
    void windowsAndLinuxUseControlAsTheMenuModifier() {
        PMouseEvent control = event(InputEvent.CTRL_DOWN_MASK);
        PMouseEvent command = event(InputEvent.META_DOWN_MASK);

        assertThat(TransformModifiers.from(control, false).menu()).isTrue();
        assertThat(TransformModifiers.from(command, false).menu()).isFalse();
    }

    @Test
    void shiftAndAltAreNormalizedIndependently() {
        PMouseEvent event = event(InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK);

        TransformModifiers modifiers = TransformModifiers.from(event, true);
        assertThat(modifiers.shift()).isTrue();
        assertThat(modifiers.alt()).isTrue();
        assertThat(modifiers.menu()).isFalse();
    }

    private static PMouseEvent event(int modifiers) {
        MouseEvent event = new MouseEvent(view, MouseEvent.MOUSE_DRAGGED, 0,
            modifiers, 1, 1, 1, false, MouseEvent.BUTTON1);
        return new PMouseEvent(event, view);
    }
}
