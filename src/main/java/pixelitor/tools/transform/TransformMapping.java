/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.Serializable;

/**
 * An immutable mapping from the pristine, session-start geometry to the
 * current Free Transform geometry.
 */
public sealed interface TransformMapping extends Serializable
    permits AffineMapping, ProjectiveMapping, WarpMapping {

    Rectangle2D sourceBounds();

    Rectangle2D destinationBounds();

    Point2D mapSourceToDestination(Point2D source);

    Point2D mapDestinationToSource(Point2D destination);

    boolean isIdentity();
}
