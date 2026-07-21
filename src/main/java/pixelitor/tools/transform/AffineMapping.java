/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.Serial;
import java.util.Arrays;

/** An immutable affine Free Transform mapping. */
public final class AffineMapping implements TransformMapping {
    @Serial
    private static final long serialVersionUID = 1L;

    private final AffineTransform transform;
    private final AffineTransform inverse;
    private final Rectangle2D sourceBounds;
    private final Rectangle2D destinationBounds;

    public AffineMapping(AffineTransform transform, Rectangle2D sourceBounds) {
        this.transform = new AffineTransform(transform);
        this.sourceBounds = (Rectangle2D) sourceBounds.clone();
        try {
            inverse = transform.createInverse();
        } catch (NoninvertibleTransformException e) {
            throw new IllegalArgumentException("The affine transform is singular", e);
        }
        Shape transformed = transform.createTransformedShape(sourceBounds);
        destinationBounds = transformed.getBounds2D();
    }

    public AffineTransform affineTransform() {
        return new AffineTransform(transform);
    }

    @Override
    public Rectangle2D sourceBounds() {
        return (Rectangle2D) sourceBounds.clone();
    }

    @Override
    public Rectangle2D destinationBounds() {
        return (Rectangle2D) destinationBounds.clone();
    }

    @Override
    public Point2D mapSourceToDestination(Point2D source) {
        return transform.transform(source, null);
    }

    @Override
    public Point2D mapDestinationToSource(Point2D destination) {
        return inverse.transform(destination, null);
    }

    @Override
    public boolean isIdentity() {
        return transform.isIdentity();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof AffineMapping other
            && transform.equals(other.transform)
            && sourceBounds.equals(other.sourceBounds)
            && destinationBounds.equals(other.destinationBounds);
    }

    @Override
    public int hashCode() {
        double[] matrix = new double[6];
        transform.getMatrix(matrix);
        return 31 * (31 * Arrays.hashCode(matrix) + sourceBounds.hashCode())
            + destinationBounds.hashCode();
    }
}
