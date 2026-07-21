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
import pixelitor.layers.ImageLayer;
import pixelitor.layers.Layer;
import pixelitor.selection.Selection;
import pixelitor.tools.move.MoveMode;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

/** Resolves the deliberately different command and Move-control bounds. */
public final class TransformBoundsResolver {
    private TransformBoundsResolver() {
    }

    public static Resolution resolveEditCommand(Composition comp) {
        Layer layer = comp.getActiveLayer();
        if (!(layer instanceof ImageLayer imageLayer)) {
            return null;
        }
        Rectangle bounds = imageLayer.getContentBounds(false);
        if (bounds == null || bounds.isEmpty()) {
            return null;
        }
        return new Resolution(imageLayer, imageLayer, bounds,
            TransformBoundsPolicy.OPAQUE_CONTENT, MoveMode.MOVE_LAYER_ONLY, layer);
    }

    public static Resolution resolveMoveControls(Composition comp, View view, MoveMode mode) {
        Selection selection = comp.getSelection();
        Layer activeLayer = comp.getActiveLayer();
        ImageLayer imageLayer = mode.movesLayer() && activeLayer instanceof ImageLayer image
            ? image : null;
        Rectangle2D selectionBounds = mode.movesSelection() && selection != null
            ? selectionBoundsWithHandleClearance(selection, view) : null;
        Rectangle2D layerBounds = imageLayer == null ? null : imageLayer.getContentBounds(true);

        Transformable target;
        Rectangle2D bounds;
        TransformBoundsPolicy policy;
        if (layerBounds != null && selectionBounds != null) {
            CompositeTransformable composite = new CompositeTransformable(comp);
            composite.add(imageLayer);
            composite.add(selection);
            target = composite;
            bounds = layerBounds.createUnion(selectionBounds);
            policy = TransformBoundsPolicy.COMBINED_BOUNDS;
        } else if (layerBounds != null) {
            target = imageLayer;
            bounds = layerBounds;
            policy = TransformBoundsPolicy.LAYER_BUFFER;
        } else if (selectionBounds != null) {
            target = selection;
            bounds = selectionBounds;
            policy = TransformBoundsPolicy.SELECTION_BOUNDS;
        } else {
            return null;
        }

        if (bounds.isEmpty()) {
            return null;
        }
        return new Resolution(target, imageLayer, bounds, policy, mode, activeLayer);
    }

    private static Rectangle2D selectionBoundsWithHandleClearance(Selection selection, View view) {
        Rectangle componentBounds = view.imageToComponentSpace(selection.getShapeBounds2D());
        componentBounds.grow(10, 10);
        return view.componentToImageSpace(componentBounds);
    }

    public record Resolution(
        Transformable target,
        ImageLayer imageLayer,
        Rectangle2D bounds,
        TransformBoundsPolicy policy,
        MoveMode moveMode,
        Layer activeLayerAtStart
    ) {
    }
}
