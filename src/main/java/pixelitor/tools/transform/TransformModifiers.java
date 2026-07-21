/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import com.bric.util.JVM;
import pixelitor.tools.util.PMouseEvent;

/** Platform-normalized modifier state sampled continuously during a drag. */
public record TransformModifiers(boolean shift, boolean alt, boolean menu) {
    public static final TransformModifiers NONE = new TransformModifiers(false, false, false);

    public static TransformModifiers from(PMouseEvent event) {
        boolean menuDown = JVM.isMac ? event.isMetaDown() : event.isControlDown();
        return new TransformModifiers(event.isShiftDown(), event.isAltDown(), menuDown);
    }
}
