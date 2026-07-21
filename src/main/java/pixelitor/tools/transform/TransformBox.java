/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
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

package pixelitor.tools.transform;

import pixelitor.Composition;
import pixelitor.compactions.FlipDirection;
import pixelitor.compactions.QuadrantAngle;
import pixelitor.gui.View;
import pixelitor.gui.utils.DDimension;
import pixelitor.history.History;
import pixelitor.tools.ToolWidget;
import pixelitor.tools.Tools;
import pixelitor.tools.pen.SubPath;
import pixelitor.tools.transform.history.TransformBoxChangedEdit;
import pixelitor.tools.util.ArrowKey;
import pixelitor.tools.util.DraggablePoint;
import pixelitor.tools.util.PMouseEvent;
import pixelitor.tools.util.PPoint;
import pixelitor.utils.AngleUnit;
import pixelitor.utils.Cursors;
import pixelitor.utils.Geometry;
import pixelitor.utils.Lazy;
import pixelitor.utils.Shapes;
import pixelitor.utils.debug.DebugNode;
import pixelitor.utils.debug.DebugNodes;
import pixelitor.utils.debug.Debuggable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

import static java.lang.String.format;
import static pixelitor.tools.transform.Direction.*;
import static pixelitor.tools.util.DraggablePoint.activePoint;
import static pixelitor.utils.AngleUnit.RADIANS;
import static pixelitor.utils.Cursors.DEFAULT;
import static pixelitor.utils.Cursors.MOVE;

/**
 * A widget that manipulates a {@link Transformable} by calculating an affine,
 * projective, or mesh mapping from the interactive movement of its handles.
 * Legacy shape/path users remain affine-only; the Move tool enables the full
 * Free Transform interaction model.
 */
public class TransformBox implements ToolWidget, Debuggable, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    // the distance (in component space) between
    // the rotation handle and the NW-NE line
    public static final int ROT_HANDLE_DISTANCE = 28;

    private final CornerHandle nw;
    private final CornerHandle ne;
    private final CornerHandle se;
    private final CornerHandle sw;
    private final RotationHandle rot;

    // collections of handles for convenient iteration
    private DraggablePoint[] handles;
    private CornerHandle[] corners;
    private PositionHandle[] positions;
    private EdgeHandle[] edges;

    private transient Transformable target;

    private transient View view;

    // the starting bounds of the box in image space,
    // corresponding to the initial size of the transformed object
    private final Rectangle2D origImRect;

    // the current width/height of the box in image space, as if it were unrotated
    // (the name is misleading, but it can be changed only after we stop serializing)
    private final DDimension rotatedImSize;

    // the current rotation angle of the box relative to
    // its original, unrotated state
    private double angle = 0.0;
    private int angleDegrees = 0;
    private double sin = 0.0;
    private double cos = 1.0;

    // the angle-dependent cursor offset used to
    // determine the cursor for a given corner
    private int cursorOffset = 0;

    // the box shape in component coordinates
    private Path2D coBoxShape;

    private transient boolean wholeBoxDrag = false;
    private transient double wholeBoxDragStartCoX;
    private transient double wholeBoxDragStartCoY;

    private transient Memento beforeMovement;

    private TransformReferencePoint referencePoint = TransformReferencePoint.CENTER;
    private TransformMode transformMode = TransformMode.FREE_TRANSFORM;
    private WarpStyle warpStyle = WarpStyle.CUSTOM;
    private WarpMapping warpMapping;
    private double horizontalSkewDegrees;
    private double verticalSkewDegrees;

    private transient TransformModifiers dragModifiers = TransformModifiers.NONE;
    private transient double transformDragStartX;
    private transient double transformDragStartY;
    private transient boolean outsideRotationDrag;
    private transient int activeWarpPoint = -1;
    private transient Runnable changeListener;

    // this is always false for the Move Tool, but other tools still use it
    private transient boolean useLegacyHistory = true;

    private transient Lazy<JPopupMenu> contextMenu = Lazy.of(this::createContextMenu);

    public TransformBox(Rectangle2D origImRect, View view, Transformable target) {
        // it must be a positive rectangle
        assert !origImRect.isEmpty();
        this.origImRect = origImRect;
        rotatedImSize = new DDimension(origImRect);

        this.view = view;
        this.target = target;

        double westX = origImRect.getX();
        double eastX = westX + origImRect.getWidth();
        double northY = origImRect.getY();
        double southY = northY + origImRect.getHeight();

        PPoint nwLoc = PPoint.fromIm(westX, northY, view);
        PPoint neLoc = PPoint.fromIm(eastX, northY, view);
        PPoint seLoc = PPoint.fromIm(eastX, southY, view);
        PPoint swLoc = PPoint.fromIm(westX, southY, view);

        // initialize the corner handles
        nw = new CornerHandle("NW", this, true,
            nwLoc, view, NW_OFFSET, NW_OFFSET_IO);
        ne = new CornerHandle("NE", this, true,
            neLoc, view, NE_OFFSET, NE_OFFSET_IO);
        se = new CornerHandle("SE", this, false,
            seLoc, view, SE_OFFSET, SE_OFFSET_IO);
        sw = new CornerHandle("SW", this, false,
            swLoc, view, SW_OFFSET, SW_OFFSET_IO);

        // initialize the rotation handle
        double rotX = (nw.getX() + ne.getX()) / 2;
        double rotY = ne.getY() - ROT_HANDLE_DISTANCE;
        PPoint rotPos = new PPoint(rotX, rotY, view);
        rot = new RotationHandle("rot", this, rotPos, view);

        initBox();
    }

    private void initBox() {
        // initialize the edge handles
        EdgeHandle n = new EdgeHandle("N", this, nw, ne,
            true, N_OFFSET, N_OFFSET_IO);
        EdgeHandle e = new EdgeHandle("E", this, ne, se,
            false, E_OFFSET, E_OFFSET_IO);
        EdgeHandle w = new EdgeHandle("W", this, nw, sw,
            false, W_OFFSET, W_OFFSET_IO);
        EdgeHandle s = new EdgeHandle("S", this, sw, se,
            true, S_OFFSET, S_OFFSET_IO);

        // set up the neighboring relations between the corner handles
        nw.setVerNeighbor(sw, w, true);
        nw.setHorNeighbor(ne, n, true);
        se.setHorNeighbor(sw, s, true);
        se.setVerNeighbor(ne, e, true);

        // define some point sets for convenience
        handles = new DraggablePoint[]{nw, ne, se, sw, rot, n, e, w, s};
        corners = new CornerHandle[]{nw, ne, se, sw};
        edges = new EdgeHandle[]{n, e, w, s};
        positions = new PositionHandle[]{nw, ne, se, sw, n, e, w, s};

        updateBoxShape();
        updateDirections(false);
    }

    @SuppressWarnings("CopyConstructorMissesField")
    public TransformBox(TransformBox other) {
        this.nw = other.nw.copy(this);
        this.ne = other.ne.copy(this);
        this.se = other.se.copy(this);
        this.sw = other.sw.copy(this);
        this.rot = other.rot.copy(this);

        this.origImRect = new Rectangle2D.Double();
        origImRect.setRect(other.origImRect);

        this.rotatedImSize = new DDimension(other.rotatedImSize);
        this.view = other.view;

        initBox();

        this.angle = other.angle;
        this.sin = other.sin;
        this.cos = other.cos;
        this.angleDegrees = other.angleDegrees;
        this.cursorOffset = other.cursorOffset;
        this.wholeBoxDrag = other.wholeBoxDrag;
        this.wholeBoxDragStartCoX = other.wholeBoxDragStartCoX;
        this.wholeBoxDragStartCoY = other.wholeBoxDragStartCoY;
        this.referencePoint = other.referencePoint;
        this.transformMode = other.transformMode;
        this.warpStyle = other.warpStyle;
        this.warpMapping = other.warpMapping;
        this.horizontalSkewDegrees = other.horizontalSkewDegrees;
        this.verticalSkewDegrees = other.verticalSkewDegrees;

        if (other.beforeMovement == null) {
            this.beforeMovement = null;
        } else {
            // sharing the references is OK,
            // because memento objects are immutable
            this.beforeMovement = other.beforeMovement;
        }
    }

    public TransformBox copy(Transformable newTarget) {
        TransformBox box = new TransformBox(this);
        box.setTarget(newTarget);
        return box;
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();

        // initialize transient fields
        wholeBoxDrag = false;
        wholeBoxDragStartCoX = 0;
        wholeBoxDragStartCoY = 0;
        contextMenu = Lazy.of(this::createContextMenu);

        // these transient fields will be set when they are first needed
        target = null;
        view = null;
        beforeMovement = null;
        dragModifiers = TransformModifiers.NONE;
        outsideRotationDrag = false;
        activeWarpPoint = -1;
        changeListener = null;
    }

    public void setTarget(Transformable target) {
        this.target = target;
    }

    /**
     * Initialize transient variables after deserialization.
     */
    public void reInitialize(View view, Transformable target) {
        // a box needs reinitialization if the view is null after deserialization
        // or if it's the old view after the duplication of a composition
        if (this.view == view) {
            return;
        }
        setView(view);
        setTarget(target);
    }

    public void setView(View view) {
        this.view = view;
        for (DraggablePoint handle : handles) {
            handle.setView(view);
        }
    }

    /**
     * Programmatically rotates the box to the given angle.
     */
    public void rotateTo(double angle, AngleUnit unit) {
        saveState(); // so that transform works
        double rad = unit.toRadians(angle);
        double angleBefore = this.angle;
        setAngle(rad);
        Point2D pivot = getPivot();
        coTransform(AffineTransform.getRotateInstance(rad - angleBefore,
            pivot.getX(), pivot.getY()));
    }

    /**
     * Returns the AffineTransform in image space that is needed to map
     * the box from its original position into the current position.
     */
    public AffineTransform calcImTransform() {
        double width = origImRect.getWidth();
        double height = origImRect.getHeight();
        double m00 = (ne.imX - nw.imX) / width;
        double m10 = (ne.imY - nw.imY) / width;
        double m01 = (sw.imX - nw.imX) / height;
        double m11 = (sw.imY - nw.imY) / height;
        double m02 = nw.imX - m00 * origImRect.getX() - m01 * origImRect.getY();
        double m12 = nw.imY - m10 * origImRect.getX() - m11 * origImRect.getY();
        return new AffineTransform(m00, m10, m01, m11, m02, m12);
    }

    public TransformMapping calcMapping() {
        if (transformMode == TransformMode.WARP && warpMapping != null) {
            return warpMapping;
        }
        Point2D[] points = getImCorners();
        if (isParallelogram(points)) {
            return new AffineMapping(calcImTransform(), origImRect);
        }
        return new ProjectiveMapping(origImRect, points);
    }

    private double calcScaleY() {
        return rotatedImSize.getHeight() / origImRect.getHeight();
    }

    private double calcScaleX() {
        return rotatedImSize.getWidth() / origImRect.getWidth();
    }

    /**
     * Recalculates the unrotated width and height from the corner positions.
     */
    private void updateUnrotatedDimensions() {
        double width, height;
        // avoid dividing by a number close to zero to maintain precision
        if (Math.abs(cos) > Math.abs(sin)) {
            width = (ne.imX - nw.imX) / cos;
            height = (sw.imY - nw.imY) / cos;
        } else {
            width = (ne.imY - nw.imY) / sin;
            height = (nw.imX - sw.imX) / sin;
        }

        rotatedImSize.setSize(width, height);
    }

    public Dimension2D getRotatedImSize() {
        return rotatedImSize;
    }

    /**
     * Upates everything else after the corner handles have been moved/updated.
     */
    public void cornerHandlesMoved() {
        updateEdgePositions();

        if (isModernFreeTransform()) {
            updateModernDerivedGeometry();
            updateRotHandleLocation();
            updateBoxShape();
            applyTransform();
            updateDirections(false);
            return;
        }

        boolean wasInsideOut = rotatedImSize.isInsideOut();
        updateUnrotatedDimensions();
        updateRotHandleLocation();
        updateBoxShape();
        applyTransform();

        boolean isInsideOut = rotatedImSize.isInsideOut();
        if (isInsideOut != wasInsideOut) {
            recalcAngle();
            updateDirections(isInsideOut);
        }
    }

    private void updateEdgePositions() {
        for (EdgeHandle edge : edges) {
            edge.updatePosition();
        }
    }

    public void applyTransform() {
        if (isModernFreeTransform()) {
            target.imTransform(calcMapping());
            notifyChanged();
        } else {
            target.imTransform(calcImTransform());
        }
    }

    /**
     * Ensures that the rotation handle is attached correctly
     * above the (possibly rotated) top edge.
     */
    private void updateRotHandleLocation() {
        Point2D northCenter = Geometry.midPoint(nw, ne);

        double rotDistX = ROT_HANDLE_DISTANCE * sin;
        double rotDistY = ROT_HANDLE_DISTANCE * cos;
        if (rotatedImSize.getHeight() < 0) {
            rotDistX *= -1;
            rotDistY *= -1;
        }

        double rotX = northCenter.getX() + rotDistX;
        double rotY = northCenter.getY() - rotDistY;
        rot.setLocation(rotX, rotY);
    }

    @Override
    public void paint(Graphics2D g) {
        if (transformMode == TransformMode.WARP && warpMapping != null) {
            paintWarpGrid(g);
            paintReferencePoint(g);
            return;
        }

        // paint the lines
        Shapes.drawVisibly(g, coBoxShape);
        Shapes.drawVisibly(g, new Line2D.Double(Geometry.midPoint(nw, ne), rot));

        // paint the handles
        for (DraggablePoint handle : handles) {
            handle.paintHandle(g);
        }
        if (isModernFreeTransform()) {
            paintReferencePoint(g);
        }
    }

    private void updateBoxShape() {
        coBoxShape = new Path2D.Double();
        coBoxShape.moveTo(nw.getX(), nw.getY());
        coBoxShape.lineTo(ne.getX(), ne.getY());
        coBoxShape.lineTo(se.getX(), se.getY());
        coBoxShape.lineTo(sw.getX(), sw.getY());
        coBoxShape.lineTo(nw.getX(), nw.getY());
        coBoxShape.closePath();
    }

    @Override
    public DraggablePoint findHandleAt(double coX, double coY) {
        for (DraggablePoint handle : handles) {
            if (handle.contains(coX, coY)) {
                return handle;
            }
        }
        return null;
    }

    /**
     * Returns true if the transform box handles the given mouse pressed event
     */
    public boolean processMousePressed(PMouseEvent e) {
        dragModifiers = TransformModifiers.from(e);
        double x = e.getOrigCoX();
        double y = e.getOrigCoY();

        if (transformMode == TransformMode.WARP && warpMapping != null) {
            int warpPoint = findWarpPointAt(x, y);
            if (warpPoint >= 0) {
                saveState();
                activeWarpPoint = warpPoint;
                return true;
            }
        }

        DraggablePoint hit = findHandleAt(x, y);
        if (hit != null) {
            // a new handle drag action is starting
            mousePressedOn(hit, x, y);
            return true;
        } else {
            DraggablePoint.clearActivePoint();
            if (contains(x, y)) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                } else {
                    // a new whole-box drag is starting
                    prepareWholeBoxDrag(e.getCoX(), e.getCoY());
                }
                return true;
            } else if (isModernFreeTransform() && isInRotationHalo(x, y)) {
                saveState();
                transformDragStartX = x;
                transformDragStartY = y;
                outsideRotationDrag = true;
                return true;
            }
        }

        return false;
    }

    /**
     * Handles a mouse press event specifically on one of the box's handles.
     */
    public void mousePressedOn(DraggablePoint handle, double x, double y) {
        View.toolSnappingChanged(cos == 1.0 && handle.shouldSnap(), false);

        handle.setActive(true);
        saveState();
        transformDragStartX = x;
        transformDragStartY = y;
        handle.mousePressed(x, y);
        view.repaint();
    }

    public void prepareWholeBoxDrag(double coX, double coY) {
        wholeBoxDrag = true;
        wholeBoxDragStartCoX = coX;
        wholeBoxDragStartCoY = coY;
        saveState();
        View.toolSnappingChanged(cos == 1.0, false);
    }

    /**
     * Returns true if the transform box handles the given mouse dragged event
     */
    public boolean processMouseDragged(PMouseEvent e) {
        dragModifiers = TransformModifiers.from(e);
        if (activeWarpPoint >= 0) {
            Point2D im = view.componentToImageSpace(
                new Point2D.Double(e.getCoX(), e.getCoY()));
            try {
                warpMapping = ((WarpMapping) beforeMovement.warpMapping)
                    .withControlPoint(activeWarpPoint, im);
                warpStyle = WarpStyle.CUSTOM;
                target.imTransform(warpMapping);
                notifyChanged();
                target.updateUI(view);
            } catch (IllegalArgumentException ignored) {
                // Keep the last valid mesh.
            }
            return true;
        }
        if (activePoint != null) {
            activePoint.mouseDragged(e.getCoX(), e.getCoY());
            target.updateUI(view);
            return true;
        } else if (outsideRotationDrag) {
            dragRotation(e.getCoX(), e.getCoY());
            target.updateUI(view);
            return true;
        } else if (wholeBoxDrag) {
            dragBox(e.getCoX(), e.getCoY());
            return true;
        }
        return false;
    }

    /**
     * Returns true if the transform box handles the given mouse released event
     */
    public boolean processMouseReleased(PMouseEvent e) {
        dragModifiers = TransformModifiers.from(e);
        if (activeWarpPoint >= 0) {
            activeWarpPoint = -1;
            target.updateUI(view);
            addLegacyEditToHistory(e.getComp(), "Warp Transform");
            return true;
        }
        if (activePoint != null) {
            double x = e.getCoX();
            double y = e.getCoY();
            activePoint.mouseReleased(x, y);
            if (!activePoint.isHitBy(e)) {
                // can happen if the handle has a constrained position
                DraggablePoint.clearActivePoint();
            }
            target.updateUI(view);
            updateDirections(); // necessary if dragged through the opposite corner
            addLegacyEditToHistory(e.getComp(), "Change Transform Box");
            return true;
        } else if (outsideRotationDrag) {
            dragRotation(e.getCoX(), e.getCoY());
            outsideRotationDrag = false;
            updateDirections();
            return true;
        } else if (e.isPopupTrigger()) {
            showPopup(e);
        } else if (wholeBoxDrag) {
            dragBox(e.getCoX(), e.getCoY());
            wholeBoxDrag = false;
            addLegacyEditToHistory(e.getComp(), "Drag Transform Box");
            return true;
        }
        return false;
    }

    private void showPopup(PMouseEvent e) {
        contextMenu.get().show(view, (int) e.getCoX(), (int) e.getCoY());
    }

    private JPopupMenu createContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        menu.add(createRotateAction(QuadrantAngle.ANGLE_90));
        menu.add(createRotateAction(QuadrantAngle.ANGLE_180));
        menu.add(createRotateAction(QuadrantAngle.ANGLE_270));

        menu.addSeparator();

        menu.add(createFlipAction(FlipDirection.HORIZONTAL));
        menu.add(createFlipAction(FlipDirection.VERTICAL));

        return menu;
    }

    private AbstractAction createFlipAction(FlipDirection direction) {
        return new AbstractAction(direction.getDisplayName()) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                flip(direction);
            }
        };
    }

    private AbstractAction createRotateAction(QuadrantAngle angle) {
        return new AbstractAction(angle.getDisplayName()) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                rotate(angle);
            }
        };
    }

    void flip(FlipDirection direction) {
        saveState();

        // get the original image-space locations of the corners
        Point2D nwImLoc = nw.getImLocationCopy();
        Point2D neImLoc = ne.getImLocationCopy();
        Point2D swImLoc = sw.getImLocationCopy();
        Point2D seImLoc = se.getImLocationCopy();

        if (direction == FlipDirection.HORIZONTAL) {
            // swap the east and west corners
            nw.setImLocationOnlyForThis(neImLoc);
            ne.setImLocationOnlyForThis(nwImLoc);
            sw.setImLocationOnlyForThis(seImLoc);
            se.setImLocationOnlyForThis(swImLoc);
        } else {
            // swap the north and south corners
            nw.setImLocationOnlyForThis(swImLoc);
            sw.setImLocationOnlyForThis(nwImLoc);
            ne.setImLocationOnlyForThis(seImLoc);
            se.setImLocationOnlyForThis(neImLoc);
        }

        // update the rest of the box's state and the UI
        cornerHandlesMoved();
        target.updateUI(view);
        addLegacyEditToHistory(view.getComp(), direction.getDisplayName());
    }

    void rotate(QuadrantAngle rotAngle) {
        double delta = Math.toRadians(rotAngle.getAngleDegree());
        double newAngle = angle + delta;
        if (newAngle >= Math.PI * 2) {
            newAngle -= Math.PI * 2;
        }
        rotateTo(newAngle, RADIANS);

        target.updateUI(view);
        addLegacyEditToHistory(view.getComp(), rotAngle.getDisplayName());
    }

    /**
     * Used when there can be only one transform box
     */
    public void mouseMoved(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        DraggablePoint hit = findHandleAt(x, y);
        if (hit != null) {
            hit.setActive(true);
            view.repaint();
            view.setCursor(hit.getCursor());
        } else {
            if (activePoint != null) {
                DraggablePoint.clearActivePoint();
                view.repaint();
            }

            view.setCursor(contains(x, y) ? MOVE
                : isModernFreeTransform() && isInRotationHalo(x, y)
                ? Cursors.ROTATE : DEFAULT);
        }
    }

    /**
     * Used when there can be more than one transform boxes.
     * Returns true if this particular transform box handles
     * the given mouse moved event.
     */
    public boolean processMouseMoved(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        DraggablePoint handle = findHandleAt(x, y);
        if (handle != null) {
            handle.setActive(true);
            view.repaint();
            view.setCursor(handle.getCursor());
            return true;
        }
        return false;
    }

    public boolean contains(double coX, double coY) {
        return coBoxShape.contains(coX, coY);
    }

    void updateDirections() {
        updateDirections(rotatedImSize.isInsideOut());
    }

    private void updateDirections(boolean isInsideOut) {
        for (PositionHandle p : positions) {
            p.recalcDirection(isInsideOut, cursorOffset);
            if (p.isActive()) {
                view.setCursor(p.getCursor());
            }
        }
    }

    private void dragBox(double coX, double coY) {
        double coDX = coX - wholeBoxDragStartCoX;
        double coDY = coY - wholeBoxDragStartCoY;

        moveWholeBox(coDX, coDY);
    }

    private void moveWholeBox(double coDX, double coDY) {
        nw.relTranslate(beforeMovement.nw, coDX, coDY);
        ne.relTranslate(beforeMovement.ne, coDX, coDY);
        se.relTranslate(beforeMovement.se, coDX, coDY);
        sw.relTranslate(beforeMovement.sw, coDX, coDY);

        cornerHandlesMoved();

        target.updateUI(view);
    }

    /**
     * Returns the pivot point for rotations.
     */
    public Point2D getPivot() {
        return bilinearPoint(getCoCorners(), referencePoint.u(), referencePoint.v());
    }

    public boolean isModernFreeTransform() {
        return !useLegacyHistory;
    }

    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    private void notifyChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    /** Handles modifier-sensitive corner drags for the Move tool. */
    void dragCorner(CornerHandle dragged, double x, double y) {
        Point2D[] start = getCoCorners(beforeMovement);
        int index = cornerIndex(dragged);
        Point2D[] candidate;

        if (dragModifiers.menu() && dragModifiers.alt() && dragModifiers.shift()) {
            candidate = perspectiveCandidate(start, index, x, y);
        } else if (dragModifiers.menu()) {
            candidate = copyPoints(start);
            candidate[index] = new Point2D.Double(x, y);
        } else {
            Point2D anchor = dragModifiers.alt()
                ? bilinearPoint(start, referencePoint.u(), referencePoint.v())
                : start[(index + 2) % 4];
            candidate = scaleFromCorner(start, index, anchor, x, y,
                !dragModifiers.shift());
        }
        installCandidate(candidate);
    }

    /** Handles one-axis scale and modifier-sensitive skew drags. */
    void dragEdge(EdgeHandle dragged, double x, double y) {
        Point2D[] start = getCoCorners(beforeMovement);
        Point2D pointerDelta = new Point2D.Double(
            x - transformDragStartX, y - transformDragStartY);

        if (dragModifiers.menu() && dragModifiers.shift()) {
            Point2D[] candidate = skewEdgeCandidate(start, dragged, pointerDelta);
            if (installCandidate(candidate)) {
                updatePointerSkewValues(start, dragged, pointerDelta);
                notifyChanged();
            }
            return;
        }

        Point2D u = unit(vector(start[0], start[1]));
        Point2D v = unit(vector(start[0], start[3]));
        boolean horizontalEdge = dragged.isHorizontal();
        Point2D axis = horizontalEdge ? v : u;
        Point2D draggedStart = midpointOfDraggedEdge(start, dragged);
        Point2D anchor = dragModifiers.alt()
            ? bilinearPoint(start, referencePoint.u(), referencePoint.v())
            : midpointOfOppositeEdge(start, dragged);
        double denominator = dot(vector(anchor, draggedStart), axis);
        double numerator = dot(new Point2D.Double(x - anchor.getX(), y - anchor.getY()), axis);
        if (Math.abs(denominator) < 1.0e-9) {
            return;
        }
        double scale = numerator / denominator;
        installCandidate(scalePoints(start, anchor,
            horizontalEdge ? 1.0 : scale,
            horizontalEdge ? scale : 1.0, u, v));
    }

    /** Rotates from either the external handle or the outside-border halo. */
    void dragRotation(double x, double y) {
        Point2D[] start = getCoCorners(beforeMovement);
        Point2D pivot = bilinearPoint(start, referencePoint.u(), referencePoint.v());
        double startAngle = Math.atan2(
            transformDragStartY - pivot.getY(), transformDragStartX - pivot.getX());
        double currentAngle = Math.atan2(y - pivot.getY(), x - pivot.getX());
        double delta = currentAngle - startAngle;
        if (dragModifiers.shift()) {
            double increment = Math.toRadians(15.0);
            delta = Math.rint(delta / increment) * increment;
        }

        AffineTransform rotation = AffineTransform.getRotateInstance(
            delta, pivot.getX(), pivot.getY());
        Point2D[] candidate = new Point2D[4];
        for (int i = 0; i < 4; i++) {
            candidate[i] = rotation.transform(start[i], null);
        }
        installCandidate(candidate);
    }

    private Point2D[] perspectiveCandidate(Point2D[] start, int index,
                                           double x, double y) {
        Point2D[] result = copyPoints(start);
        Point2D u = unit(vector(start[0], start[1]));
        Point2D v = unit(vector(start[0], start[3]));
        Point2D delta = new Point2D.Double(x - start[index].getX(), y - start[index].getY());
        double du = dot(delta, u);
        double dv = dot(delta, v);
        result[index] = new Point2D.Double(x, y);
        int paired = index ^ 1;
        result[paired] = new Point2D.Double(
            start[paired].getX() - du * u.getX() + dv * v.getX(),
            start[paired].getY() - du * u.getY() + dv * v.getY());
        return result;
    }

    private static Point2D[] scaleFromCorner(Point2D[] start, int index,
                                             Point2D anchor, double x, double y,
                                             boolean proportional) {
        Point2D u = unit(vector(start[0], start[1]));
        Point2D v = unit(vector(start[0], start[3]));
        Point2D original = vector(anchor, start[index]);
        Point2D current = new Point2D.Double(x - anchor.getX(), y - anchor.getY());
        double scaleX;
        double scaleY;
        if (proportional) {
            double denominator = dot(original, original);
            if (denominator < 1.0e-9) {
                return start;
            }
            double scale = dot(current, original) / denominator;
            scaleX = scale;
            scaleY = scale;
        } else {
            double originalX = dot(original, u);
            double originalY = dot(original, v);
            if (Math.abs(originalX) < 1.0e-9 || Math.abs(originalY) < 1.0e-9) {
                return start;
            }
            scaleX = dot(current, u) / originalX;
            scaleY = dot(current, v) / originalY;
        }
        return scalePoints(start, anchor, scaleX, scaleY, u, v);
    }

    private static Point2D[] scalePoints(Point2D[] points, Point2D anchor,
                                         double scaleX, double scaleY,
                                         Point2D u, Point2D v) {
        Point2D[] result = new Point2D[points.length];
        for (int i = 0; i < points.length; i++) {
            Point2D rel = vector(anchor, points[i]);
            double localX = dot(rel, u) * scaleX;
            double localY = dot(rel, v) * scaleY;
            result[i] = new Point2D.Double(
                anchor.getX() + localX * u.getX() + localY * v.getX(),
                anchor.getY() + localX * u.getY() + localY * v.getY());
        }
        return result;
    }

    private Point2D[] skewEdgeCandidate(Point2D[] start, EdgeHandle dragged,
                                        Point2D pointerDelta) {
        Point2D[] result = copyPoints(start);
        Point2D u = unit(vector(start[0], start[1]));
        Point2D v = unit(vector(start[0], start[3]));
        if (dragged.isHorizontal()) {
            double amount = dot(pointerDelta, u);
            int[] indices = dragged == edges[0] ? new int[]{0, 1} : new int[]{3, 2};
            for (int index : indices) {
                result[index] = translate(start[index], amount * u.getX(), amount * u.getY());
            }
        } else {
            double amount = dot(pointerDelta, v);
            int[] indices = dragged == edges[1] ? new int[]{1, 2} : new int[]{0, 3};
            for (int index : indices) {
                result[index] = translate(start[index], amount * v.getX(), amount * v.getY());
            }
        }
        return result;
    }

    private boolean installCandidate(Point2D[] candidate) {
        if (candidate == null || candidate.length != 4) {
            return false;
        }
        try {
            TransformMapping candidateMapping = mappingForComponentCorners(candidate);
            if (candidateMapping instanceof ProjectiveMapping
                && !target.getTransformCapabilities().contains(TransformCapability.PROJECTIVE)) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false; // preserve the last valid geometry
        }
        nw.setLocationOnlyForThis(candidate[0]);
        ne.setLocationOnlyForThis(candidate[1]);
        se.setLocationOnlyForThis(candidate[2]);
        sw.setLocationOnlyForThis(candidate[3]);
        cornerHandlesMoved();
        return true;
    }

    private void updatePointerSkewValues(Point2D[] start, EdgeHandle dragged,
                                         Point2D pointerDelta) {
        Point2D u = unit(vector(start[0], start[1]));
        Point2D v = unit(vector(start[0], start[3]));
        if (dragged.isHorizontal()) {
            double amount = dot(pointerDelta, u);
            double height = Math.max(1.0e-9, start[0].distance(start[3]));
            double signed = dragged == edges[0] ? -amount : amount;
            horizontalSkewDegrees = beforeMovement.horizontalSkewDegrees
                + Math.toDegrees(Math.atan(signed / height));
        } else {
            double amount = dot(pointerDelta, v);
            double width = Math.max(1.0e-9, start[0].distance(start[1]));
            double signed = dragged == edges[1] ? amount : -amount;
            verticalSkewDegrees = beforeMovement.verticalSkewDegrees
                + Math.toDegrees(Math.atan(signed / width));
        }
    }

    private TransformMapping mappingForComponentCorners(Point2D[] componentCorners) {
        Point2D[] imageCorners = new Point2D[4];
        for (int i = 0; i < 4; i++) {
            imageCorners[i] = view.componentToImageSpace(componentCorners[i]);
        }
        if (isParallelogram(imageCorners)) {
            return new AffineMapping(affineForCorners(imageCorners), origImRect);
        }
        return new ProjectiveMapping(origImRect, imageCorners);
    }

    private AffineTransform affineForCorners(Point2D[] p) {
        double width = origImRect.getWidth();
        double height = origImRect.getHeight();
        double m00 = (p[1].getX() - p[0].getX()) / width;
        double m10 = (p[1].getY() - p[0].getY()) / width;
        double m01 = (p[3].getX() - p[0].getX()) / height;
        double m11 = (p[3].getY() - p[0].getY()) / height;
        double determinant = m00 * m11 - m01 * m10;
        if (!Double.isFinite(determinant) || Math.abs(determinant) < 1.0e-9) {
            throw new IllegalArgumentException("The transform has zero area");
        }
        double m02 = p[0].getX() - m00 * origImRect.getX() - m01 * origImRect.getY();
        double m12 = p[0].getY() - m10 * origImRect.getX() - m11 * origImRect.getY();
        return new AffineTransform(m00, m10, m01, m11, m02, m12);
    }

    private static boolean isParallelogram(Point2D[] p) {
        double expectedX = p[1].getX() + p[3].getX() - p[0].getX();
        double expectedY = p[1].getY() + p[3].getY() - p[0].getY();
        return Math.abs(expectedX - p[2].getX()) < 1.0e-6
            && Math.abs(expectedY - p[2].getY()) < 1.0e-6;
    }

    private void updateModernDerivedGeometry() {
        double topX = ne.imX - nw.imX;
        double topY = ne.imY - nw.imY;
        double leftX = sw.imX - nw.imX;
        double leftY = sw.imY - nw.imY;
        double width = Math.hypot(topX, topY);
        double height = width < 1.0e-9
            ? 0.0
            : (topX * leftY - topY * leftX) / width;
        rotatedImSize.setSize(width, height);
        setAngle(Math.atan2(topY, topX));
    }

    private boolean isInRotationHalo(double x, double y) {
        Shape halo = new BasicStroke(20.0f).createStrokedShape(coBoxShape);
        return halo.contains(x, y) && !coBoxShape.contains(x, y);
    }

    private void paintReferencePoint(Graphics2D g) {
        Point2D pivot = getPivot();
        Color oldColor = g.getColor();
        Stroke oldStroke = g.getStroke();
        g.setColor(new Color(255, 150, 20));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Ellipse2D.Double(pivot.getX() - 4, pivot.getY() - 4, 8, 8));
        g.draw(new Line2D.Double(pivot.getX() - 7, pivot.getY(), pivot.getX() + 7, pivot.getY()));
        g.draw(new Line2D.Double(pivot.getX(), pivot.getY() - 7, pivot.getX(), pivot.getY() + 7));
        g.setColor(oldColor);
        g.setStroke(oldStroke);
    }

    private void paintWarpGrid(Graphics2D g) {
        Point2D[] points = warpMapping.controlPoints();
        Path2D grid = new Path2D.Double();
        for (int row = 0; row < WarpMapping.GRID_SIZE; row++) {
            for (int col = 0; col < WarpMapping.GRID_SIZE; col++) {
                Point2D co = view.imageToComponentSpace(points[row * WarpMapping.GRID_SIZE + col]);
                if (col == 0) {
                    grid.moveTo(co.getX(), co.getY());
                } else {
                    grid.lineTo(co.getX(), co.getY());
                }
            }
        }
        for (int col = 0; col < WarpMapping.GRID_SIZE; col++) {
            for (int row = 0; row < WarpMapping.GRID_SIZE; row++) {
                Point2D co = view.imageToComponentSpace(points[row * WarpMapping.GRID_SIZE + col]);
                if (row == 0) {
                    grid.moveTo(co.getX(), co.getY());
                } else {
                    grid.lineTo(co.getX(), co.getY());
                }
            }
        }
        Shapes.drawVisibly(g, grid);
        for (Point2D point : points) {
            Point2D co = view.imageToComponentSpace(point);
            g.fill(new Ellipse2D.Double(co.getX() - 3, co.getY() - 3, 6, 6));
        }
    }

    private int findWarpPointAt(double x, double y) {
        Point2D[] points = warpMapping.controlPoints();
        for (int i = 0; i < points.length; i++) {
            Point2D co = view.imageToComponentSpace(points[i]);
            if (co.distanceSq(x, y) <= 64.0) {
                return i;
            }
        }
        return -1;
    }

    public TransformReferencePoint getReferencePoint() {
        return referencePoint;
    }

    public void setReferencePoint(TransformReferencePoint referencePoint) {
        if (this.referencePoint == referencePoint) {
            return;
        }
        saveState();
        this.referencePoint = referencePoint;
        notifyChanged();
        view.repaint();
    }

    public Point2D getReferencePointInImageSpace() {
        return bilinearPoint(getImCorners(), referencePoint.u(), referencePoint.v());
    }

    public double getWidthPercent() {
        return 100.0 * distance(nw, ne) / origImRect.getWidth();
    }

    public double getHeightPercent() {
        return 100.0 * distance(nw, sw) / origImRect.getHeight();
    }

    public double getRotationDegrees() {
        return Math.toDegrees(Math.atan2(ne.imY - nw.imY, ne.imX - nw.imX));
    }

    public double getHorizontalSkewDegrees() {
        return horizontalSkewDegrees;
    }

    public double getVerticalSkewDegrees() {
        return verticalSkewDegrees;
    }

    public TransformMode getTransformMode() {
        return transformMode;
    }

    public WarpStyle getWarpStyle() {
        return warpStyle;
    }

    public void translateReferenceTo(double imageX, double imageY) {
        Point2D current = getReferencePointInImageSpace();
        double coDx = (imageX - current.getX()) * view.getZoomScale();
        double coDy = (imageY - current.getY()) * view.getZoomScale();
        saveState();
        Point2D[] points = getCoCorners();
        for (int i = 0; i < points.length; i++) {
            points[i] = translate(points[i], coDx, coDy);
        }
        installCandidate(points);
    }

    public void setScalePercent(double widthPercent, double heightPercent) {
        if (!validPercent(widthPercent) || !validPercent(heightPercent)) {
            throw new IllegalArgumentException("Scale values must be finite and nonzero");
        }
        double currentWidth = getWidthPercent();
        double currentHeight = getHeightPercent();
        saveState();
        Point2D[] points = getCoCorners();
        Point2D pivot = getPivot();
        Point2D u = unit(vector(points[0], points[1]));
        Point2D v = unit(vector(points[0], points[3]));
        installCandidate(scalePoints(points, pivot,
            widthPercent / currentWidth, heightPercent / currentHeight, u, v));
    }

    public void setRotationDegrees(double degrees) {
        if (!Double.isFinite(degrees) || Math.abs(degrees) > 100_000.0) {
            throw new IllegalArgumentException("Invalid rotation angle");
        }
        saveState();
        double delta = Math.toRadians(degrees - getRotationDegrees());
        Point2D pivot = getPivot();
        AffineTransform rotation = AffineTransform.getRotateInstance(
            delta, pivot.getX(), pivot.getY());
        Point2D[] points = getCoCorners();
        for (int i = 0; i < points.length; i++) {
            points[i] = rotation.transform(points[i], null);
        }
        installCandidate(points);
    }

    public void setSkewDegrees(double horizontal, double vertical) {
        if (!Double.isFinite(horizontal) || !Double.isFinite(vertical)
            || Math.abs(horizontal) >= 89.0 || Math.abs(vertical) >= 89.0) {
            throw new IllegalArgumentException("Skew angles must be between -89 and 89 degrees");
        }
        saveState();
        Point2D[] points = getCoCorners();
        Point2D pivot = getPivot();
        Point2D u = unit(vector(points[0], points[1]));
        Point2D v = unit(vector(points[0], points[3]));
        double horizontalDelta = Math.tan(Math.toRadians(horizontal))
            - Math.tan(Math.toRadians(horizontalSkewDegrees));
        double verticalDelta = Math.tan(Math.toRadians(vertical))
            - Math.tan(Math.toRadians(verticalSkewDegrees));
        Point2D[] result = new Point2D[4];
        for (int i = 0; i < points.length; i++) {
            Point2D rel = vector(pivot, points[i]);
            double localX = dot(rel, u);
            double localY = dot(rel, v);
            double newX = localX + horizontalDelta * localY;
            double newY = localY + verticalDelta * localX;
            result[i] = new Point2D.Double(
                pivot.getX() + newX * u.getX() + newY * v.getX(),
                pivot.getY() + newX * u.getY() + newY * v.getY());
        }
        if (installCandidate(result)) {
            horizontalSkewDegrees = horizontal;
            verticalSkewDegrees = vertical;
            notifyChanged();
        }
    }

    public void setTransformMode(TransformMode mode) {
        if (mode == transformMode) {
            return;
        }
        saveState();
        if (mode == TransformMode.WARP && warpMapping == null) {
            warpMapping = WarpMapping.create(origImRect, getImCorners(), warpStyle);
        }
        transformMode = mode;
        applyTransform();
        target.updateUI(view);
        view.repaint();
    }

    public void setWarpStyle(WarpStyle style) {
        saveState();
        warpStyle = style;
        warpMapping = WarpMapping.create(origImRect, getImCorners(), style);
        if (transformMode == TransformMode.WARP) {
            applyTransform();
            target.updateUI(view);
        }
        view.repaint();
    }

    private static boolean validPercent(double value) {
        return Double.isFinite(value) && Math.abs(value) >= 0.01 && Math.abs(value) <= 100_000.0;
    }

    private int cornerIndex(CornerHandle corner) {
        for (int i = 0; i < corners.length; i++) {
            if (corners[i] == corner) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown corner");
    }

    private Point2D midpointOfDraggedEdge(Point2D[] p, EdgeHandle edge) {
        if (edge == edges[0]) {
            return Geometry.midPoint(p[0], p[1]);
        } else if (edge == edges[1]) {
            return Geometry.midPoint(p[1], p[2]);
        } else if (edge == edges[2]) {
            return Geometry.midPoint(p[0], p[3]);
        }
        return Geometry.midPoint(p[3], p[2]);
    }

    private Point2D midpointOfOppositeEdge(Point2D[] p, EdgeHandle edge) {
        if (edge == edges[0]) {
            return Geometry.midPoint(p[3], p[2]);
        } else if (edge == edges[1]) {
            return Geometry.midPoint(p[0], p[3]);
        } else if (edge == edges[2]) {
            return Geometry.midPoint(p[1], p[2]);
        }
        return Geometry.midPoint(p[0], p[1]);
    }

    private Point2D[] getCoCorners() {
        return new Point2D[]{
            nw.getLocationCopy().toCoPoint2D(), ne.getLocationCopy().toCoPoint2D(),
            se.getLocationCopy().toCoPoint2D(), sw.getLocationCopy().toCoPoint2D()
        };
    }

    private static Point2D[] getCoCorners(Memento memento) {
        return new Point2D[]{
            memento.nw.toCoPoint2D(), memento.ne.toCoPoint2D(),
            memento.se.toCoPoint2D(), memento.sw.toCoPoint2D()
        };
    }

    private Point2D[] getImCorners() {
        return new Point2D[]{
            nw.getImLocationCopy(), ne.getImLocationCopy(),
            se.getImLocationCopy(), sw.getImLocationCopy()
        };
    }

    private static Point2D bilinearPoint(Point2D[] q, double u, double v) {
        double a = (1.0 - u) * (1.0 - v);
        double b = u * (1.0 - v);
        double c = u * v;
        double d = (1.0 - u) * v;
        return new Point2D.Double(
            a * q[0].getX() + b * q[1].getX() + c * q[2].getX() + d * q[3].getX(),
            a * q[0].getY() + b * q[1].getY() + c * q[2].getY() + d * q[3].getY());
    }

    private static Point2D vector(Point2D from, Point2D to) {
        return new Point2D.Double(to.getX() - from.getX(), to.getY() - from.getY());
    }

    private static Point2D unit(Point2D vector) {
        double length = Math.hypot(vector.getX(), vector.getY());
        if (length < 1.0e-9) {
            throw new IllegalArgumentException("Degenerate transform axis");
        }
        return new Point2D.Double(vector.getX() / length, vector.getY() / length);
    }

    private static double dot(Point2D a, Point2D b) {
        return a.getX() * b.getX() + a.getY() * b.getY();
    }

    private static Point2D translate(Point2D p, double dx, double dy) {
        return new Point2D.Double(p.getX() + dx, p.getY() + dy);
    }

    private static Point2D[] copyPoints(Point2D[] points) {
        Point2D[] copy = new Point2D[points.length];
        for (int i = 0; i < points.length; i++) {
            copy[i] = new Point2D.Double(points[i].getX(), points[i].getY());
        }
        return copy;
    }

    private static double distance(Point2D a, Point2D b) {
        return a.distance(b);
    }

    @Override
    public void coCoordsChanged(View view) {
        for (CornerHandle corner : corners) {
            corner.restoreCoordsFromImSpace(view);
        }
        updateEdgePositions();
        updateRotHandleLocation();
        updateBoxShape();
    }

    @Override
    public void imCoordsChanged(AffineTransform at, View view) {
        // move the corners
        for (CornerHandle corner : corners) {
            corner.imTransformOnlyThis(at, false);
        }

        // move the rotation handle
        rot.imTransformOnlyThis(at, false);
        recalcAngle();

        updateEdgePositions();
        updateUnrotatedDimensions();
        updateBoxShape();
        applyTransform();
        updateDirections();
    }

    /**
     * Transforms the box geometry with the given component-space transformation
     */
    public void coTransform(AffineTransform at) {
        nw.coTransformOnlyThis(at, beforeMovement.nw);
        ne.coTransformOnlyThis(at, beforeMovement.ne);
        se.coTransformOnlyThis(at, beforeMovement.se);
        sw.coTransformOnlyThis(at, beforeMovement.sw);

        cornerHandlesMoved();
    }

    // TODO is this just a simpler version of imCoordsChanged?
    public void imTransform(AffineTransform at) {
        for (CornerHandle corner : corners) {
            corner.imTransform(at, true);
        }

        cornerHandlesMoved();
    }

    @Override
    public void arrowKeyPressed(ArrowKey key, View view) {
        saveState();

        moveWholeBox(key.getDeltaX(), key.getDeltaY());

        String editName = key.isShiftDown()
            ? "Shift-nudge Transform Box"
            : "Nudge Transform Box";
        addLegacyEditToHistory(view.getComp(), editName);
    }

    /**
     * Sets the rotation angle and updates derived trigonometric values.
     */
    public void setAngle(double angle) {
        if (angle == this.angle) {
            return;
        }

        this.angle = angle;
        cos = Math.cos(angle);
        sin = Math.sin(angle);

        // update the cursor offset, which depends on the angle
        angleDegrees = (int) RADIANS.toIntuitiveDegrees(angle);
        cursorOffset = calcCursorOffset(angleDegrees);
    }

    public int getAngleDegrees() {
        return angleDegrees;
    }

    /**
     * Calculates the angle-dependent part of the cursor offset,
     * by dividing the 0-360 range of angles into eight equal parts,
     * corresponding to the eight cursors.
     */
    static int calcCursorOffset(int angleDeg) {
        if (angleDeg > 338) { // 360 - (45/2) = 338
            return 0;
        }
        return (angleDeg + 22) / 45;
    }

    public double getSin() {
        return sin;
    }

    public double getCos() {
        return cos;
    }

    /**
     * Should be called only when the corners
     * and the rotation handle are in sync!
     */
    public void recalcAngle() {
        rot.recalcAngle(rot.x, rot.y, true);
    }

    public CornerHandle getNW() {
        return nw;
    }

    public CornerHandle getNE() {
        return ne;
    }

    public CornerHandle getSE() {
        return se;
    }

    public CornerHandle getSW() {
        return sw;
    }

    public RotationHandle getRot() {
        return rot;
    }

    public Transformable getTarget() {
        return target;
    }

    @Override
    public DebugNode createDebugNode(String key) {
        var node = new DebugNode(key, this);

        node.addNullableDebuggable("target", target);

        node.add(nw.createDebugNode());
        node.add(ne.createDebugNode());
        node.add(se.createDebugNode());
        node.add(sw.createDebugNode());
        node.add(rot.createDebugNode());

        node.addDouble("unrotated width", rotatedImSize.getWidth());
        node.addDouble("unrotated height", rotatedImSize.getHeight());
        node.addInt("angle (degrees)", getAngleDegrees());
        node.addDouble("scale X", calcScaleX());
        node.addDouble("scale Y", calcScaleY());

        AffineTransform at = calcImTransform();
        node.add(DebugNodes.createTransformNode("transform", at));

        return node;
    }

    @Override
    public String toString() {
        return format("Transform Box, corners = (%s, %s, %s, %s)", nw, ne, se, sw);
    }

    private void saveState() {
        beforeMovement = copyState();
    }

    public Memento getBeforeMovementMemento() {
        return beforeMovement;
    }

    public void saveImRefPoints() {
        for (CornerHandle corner : corners) {
            corner.saveImTransformRefPoint();
        }
    }

    private Memento copyState() {
        return new Memento(this);
    }

    public void restoreFrom(Memento m) {
        nw.setLocationOnlyForThis(m.nw);
        ne.setLocationOnlyForThis(m.ne);
        se.setLocationOnlyForThis(m.se);
        sw.setLocationOnlyForThis(m.sw);
        setAngle(m.angle);
        referencePoint = m.referencePoint;
        transformMode = m.transformMode;
        warpStyle = m.warpStyle;
        warpMapping = m.warpMapping;
        horizontalSkewDegrees = m.horizontalSkewDegrees;
        verticalSkewDegrees = m.verticalSkewDegrees;

        cornerHandlesMoved();

        // just to be sure
        updateDirections();

        target.updateUI(view);
        notifyChanged();
    }

    private void addLegacyEditToHistory(Composition comp, String editName) {
        if (useLegacyHistory) {
            if (Tools.MOVE.isActive()) {
                // the move tool never uses the legacy history
                throw new IllegalStateException();
            }
            History.add(createLegacyEdit(comp, editName));
        }
    }

    public void setUseLegacyHistory(boolean useLegacyHistory) {
        this.useLegacyHistory = useLegacyHistory;
    }

    public void prepareMovement() {
        prepareWholeBoxDrag(0, 0);
    }

    public void moveWhileDragging(double relImX, double relImY) {
        // since these are deltas, they can't use the normal
        // image space to component space converting methods
        double scaling = view.getZoomScale();
        dragBox(scaling * relImX, scaling * relImY);
    }

    public void finalizeMovement() {
        // no need to record an undo event here, because
        // createMovementEdit() will take care of that
        wholeBoxDrag = false;
    }

    public TransformBoxChangedEdit createLegacyEdit(Composition comp, String editName) {
        assert editName != null;

        if (target instanceof SubPath) {
            comp.pathChanged();
        }

        Memento afterMovement = copyState();
        return new TransformBoxChangedEdit(editName, comp,
            this, beforeMovement, afterMovement);
    }

    public Rectangle2D getOrigImRect() {
        return origImRect;
    }

    public Memento createMemento() {
        return new Memento(this);
    }

    public static TransformBox fromMemento(Memento memento, View view, Transformable target) {
        TransformBox box = new TransformBox(memento.origImRect, view, target);
        box.restoreFrom(memento);

        return box;
    }

    /**
     * Captures the internal state of a {@link TransformBox}
     * so that it can be returned to this state later.
     */
    public static class Memento {
        private final PPoint nw;
        private final PPoint ne;
        private final PPoint se;
        private final PPoint sw;

        private final Rectangle2D origImRect;
        private final double angle;
        private final TransformReferencePoint referencePoint;
        private final TransformMode transformMode;
        private final WarpStyle warpStyle;
        private final WarpMapping warpMapping;
        private final double horizontalSkewDegrees;
        private final double verticalSkewDegrees;

        public Memento(TransformBox box) {
            this.origImRect = new Rectangle2D.Double();
            this.origImRect.setRect(box.origImRect); // copy the original rect

            this.nw = box.nw.getLocationCopy();
            this.ne = box.ne.getLocationCopy();
            this.se = box.se.getLocationCopy();
            this.sw = box.sw.getLocationCopy();

            this.angle = box.angle;
            this.referencePoint = box.referencePoint;
            this.transformMode = box.transformMode;
            this.warpStyle = box.warpStyle;
            this.warpMapping = box.warpMapping;
            this.horizontalSkewDegrees = box.horizontalSkewDegrees;
            this.verticalSkewDegrees = box.verticalSkewDegrees;
        }

        public Rectangle2D getOrigImRect() {
            return origImRect;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Memento memento = (Memento) o;
            return Double.compare(memento.angle, angle) == 0 &&
                Double.compare(memento.horizontalSkewDegrees, horizontalSkewDegrees) == 0 &&
                Double.compare(memento.verticalSkewDegrees, verticalSkewDegrees) == 0 &&
                Objects.equals(nw, memento.nw) &&
                Objects.equals(ne, memento.ne) &&
                Objects.equals(se, memento.se) &&
                Objects.equals(sw, memento.sw) &&
                referencePoint == memento.referencePoint &&
                transformMode == memento.transformMode &&
                warpStyle == memento.warpStyle &&
                Objects.equals(warpMapping, memento.warpMapping);
        }

        @Override
        public int hashCode() {
            return Objects.hash(nw, ne, se, sw, angle, referencePoint,
                transformMode, warpStyle, warpMapping,
                horizontalSkewDegrees, verticalSkewDegrees);
        }
    }
}
