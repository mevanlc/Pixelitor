/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

public enum WarpStyle {
    CUSTOM("Custom"),
    ARC("Arc"),
    ARCH("Arch"),
    BULGE("Bulge"),
    FLAG("Flag"),
    WAVE("Wave");

    private final String displayName;

    WarpStyle(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
