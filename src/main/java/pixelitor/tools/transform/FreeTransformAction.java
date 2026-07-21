/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import pixelitor.Composition;
import pixelitor.gui.utils.AbstractViewEnabledAction;
import pixelitor.tools.Tools;

public final class FreeTransformAction extends AbstractViewEnabledAction {
    public static final FreeTransformAction INSTANCE = new FreeTransformAction();

    private FreeTransformAction() {
        super("Free Transform");
    }

    @Override
    protected void onClick(Composition comp) {
        Tools.MOVE.startFreeTransform(comp, TransformStartSource.EDIT_COMMAND);
    }
}
