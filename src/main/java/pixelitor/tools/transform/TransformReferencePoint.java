/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

public enum TransformReferencePoint {
    NORTH_WEST(0.0, 0.0), NORTH(0.5, 0.0), NORTH_EAST(1.0, 0.0),
    WEST(0.0, 0.5), CENTER(0.5, 0.5), EAST(1.0, 0.5),
    SOUTH_WEST(0.0, 1.0), SOUTH(0.5, 1.0), SOUTH_EAST(1.0, 1.0);

    private final double u;
    private final double v;

    TransformReferencePoint(double u, double v) {
        this.u = u;
        this.v = v;
    }

    public double u() {
        return u;
    }

    public double v() {
        return v;
    }
}
