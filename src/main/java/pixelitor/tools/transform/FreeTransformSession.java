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
import pixelitor.gui.View;
import pixelitor.layers.Layer;
import pixelitor.tools.move.MoveMode;

import java.awt.geom.Rectangle2D;

/** The exact target, source geometry, and widget for one transform session. */
public final class FreeTransformSession {
    private final Composition comp;
    private final Transformable target;
    private final TransformStartSource source;
    private final TransformBoundsPolicy boundsPolicy;
    private final MoveMode moveMode;
    private final Layer activeLayerAtStart;
    private final Rectangle2D originalBounds;
    private final TransformBox box;
    private final TransformBox.Memento initialMemento;

    public FreeTransformSession(Composition comp,
                                View view,
                                TransformBoundsResolver.Resolution resolution,
                                TransformStartSource source) {
        this(comp, view, resolution.target(), resolution.bounds(), source,
            resolution.policy(), resolution.moveMode(), resolution.activeLayerAtStart(), null);
    }

    public FreeTransformSession(Composition comp,
                                View view,
                                Transformable target,
                                Rectangle2D originalBounds,
                                TransformStartSource source,
                                TransformBoundsPolicy boundsPolicy,
                                MoveMode moveMode,
                                Layer activeLayerAtStart,
                                TransformBox.Memento restoredState) {
        this.comp = comp;
        this.target = target;
        this.source = source;
        this.boundsPolicy = boundsPolicy;
        this.moveMode = moveMode;
        this.activeLayerAtStart = activeLayerAtStart;
        this.originalBounds = (Rectangle2D) originalBounds.clone();

        target.prepareForTransform();
        box = new TransformBox(this.originalBounds, view, target);
        box.setUseLegacyHistory(false);
        initialMemento = box.createMemento();
        if (restoredState != null) {
            box.restoreFrom(restoredState);
        }
    }

    public Composition comp() {
        return comp;
    }

    public Transformable target() {
        return target;
    }

    public TransformStartSource source() {
        return source;
    }

    public TransformBoundsPolicy boundsPolicy() {
        return boundsPolicy;
    }

    public MoveMode moveMode() {
        return moveMode;
    }

    public Layer activeLayerAtStart() {
        return activeLayerAtStart;
    }

    public Rectangle2D originalBounds() {
        return (Rectangle2D) originalBounds.clone();
    }

    public TransformBox box() {
        return box;
    }

    public TransformBox.Memento initialMemento() {
        return initialMemento;
    }
}
