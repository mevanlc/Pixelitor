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

package pixelitor.tools.transform.history;

import pixelitor.layers.Layer;
import pixelitor.tools.move.MoveMode;
import pixelitor.tools.transform.TransformBoundsPolicy;
import pixelitor.tools.transform.TransformBox;
import pixelitor.tools.transform.TransformStartSource;
import pixelitor.tools.transform.Transformable;

import java.awt.geom.Rectangle2D;

/**
 * A snapshot of the UI state of a free-transform session.
 * This is stored in a history edit to allow the interactive TransformBox
 * to be restored when an action is undone.
 *
 * In addition to complete widget geometry, this captures the exact target and
 * start policy. Undo therefore never has to guess from the layer that happens
 * to be active later.
 */
public record TransformUISnapshot(
    TransformBox.Memento memento,
    MoveMode moveMode,
    Transformable target,
    TransformStartSource source,
    TransformBoundsPolicy boundsPolicy,
    Rectangle2D originalBounds,
    Layer activeLayerAtStart
) {
}
