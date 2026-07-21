/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Point2D;
import java.util.EnumMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Compact two-way-bound settings shown only during a transform session. */
public final class TransformOptionsPanel extends JPanel {
    private final Supplier<TransformBox> boxSupplier;
    private final BiConsumer<String, TransformBox.Memento> editRecorder;
    private final Runnable commitAction;
    private final Runnable cancelAction;

    private final EnumMap<TransformReferencePoint, JToggleButton> referenceButtons
        = new EnumMap<>(TransformReferencePoint.class);
    private final JTextField xField = field("referenceX");
    private final JTextField yField = field("referenceY");
    private final JCheckBox relativeCheckBox = new JCheckBox("Relative");
    private final JTextField widthField = field("widthPercent");
    private final JTextField heightField = field("heightPercent");
    private final JToggleButton linkButton = new JToggleButton("🔗", true);
    private final JTextField rotationField = field("rotationDegrees");
    private final JTextField horizontalSkewField = field("horizontalSkew");
    private final JTextField verticalSkewField = field("verticalSkew");
    private final JComboBox<TransformMode> modeSelector = new JComboBox<>(TransformMode.values());
    private final JComboBox<WarpStyle> warpStyleSelector = new JComboBox<>(WarpStyle.values());

    private boolean refreshing;
    private Point2D relativeOrigin;

    public TransformOptionsPanel(Supplier<TransformBox> boxSupplier,
                                 BiConsumer<String, TransformBox.Memento> editRecorder,
                                 Runnable commitAction,
                                 Runnable cancelAction) {
        super(new FlowLayout(FlowLayout.LEFT, 3, 0));
        this.boxSupplier = boxSupplier;
        this.editRecorder = editRecorder;
        this.commitAction = commitAction;
        this.cancelAction = cancelAction;
        setName("transformOptionsPanel");
        buildUI();
        installListeners();
    }

    private void buildUI() {
        JPanel locator = new JPanel(new GridLayout(3, 3, 0, 0));
        locator.setName("referencePointLocator");
        ButtonGroup group = new ButtonGroup();
        for (TransformReferencePoint point : TransformReferencePoint.values()) {
            JToggleButton button = new JToggleButton();
            button.setMargin(new Insets(0, 0, 0, 0));
            button.setPreferredSize(new Dimension(10, 10));
            button.setToolTipText("Reference point: " + point.name().toLowerCase().replace('_', ' '));
            button.setFocusable(false);
            group.add(button);
            locator.add(button);
            referenceButtons.put(point, button);
        }
        add(locator);

        addLabeled("X:", xField);
        addLabeled("Y:", yField);
        relativeCheckBox.setName("relativePositioning");
        relativeCheckBox.setToolTipText("Enter X/Y relative to the position where this was enabled");
        add(relativeCheckBox);
        addSeparator();
        addLabeled("W:", widthField);
        add(new JLabel("%"));
        addLabeled("H:", heightField);
        add(new JLabel("%"));
        linkButton.setName("linkAspectRatio");
        linkButton.setMargin(new Insets(1, 3, 1, 3));
        linkButton.setToolTipText("Link width and height scaling");
        linkButton.setFocusable(false);
        add(linkButton);
        addSeparator();
        addLabeled("∠:", rotationField);
        add(new JLabel("°"));
        addLabeled("H Skew:", horizontalSkewField);
        addLabeled("V Skew:", verticalSkewField);
        addSeparator();
        modeSelector.setName("transformModeSelector");
        modeSelector.setFocusable(false);
        add(modeSelector);
        warpStyleSelector.setName("warpStyleSelector");
        warpStyleSelector.setFocusable(false);
        add(warpStyleSelector);

        JButton commit = new JButton("✓");
        commit.setName("commitTransform");
        commit.setToolTipText("Commit Free Transform (Enter)");
        commit.setMargin(new Insets(1, 5, 1, 5));
        commit.addActionListener(_ -> commitAction.run());
        add(commit);

        JButton cancel = new JButton("✕");
        cancel.setName("cancelTransform");
        cancel.setToolTipText("Cancel Free Transform (Esc)");
        cancel.setMargin(new Insets(1, 5, 1, 5));
        cancel.addActionListener(_ -> cancelAction.run());
        add(cancel);
    }

    private void installListeners() {
        referenceButtons.forEach((point, button) -> button.addActionListener(_ -> {
            if (refreshing) {
                return;
            }
            TransformBox box = boxSupplier.get();
            if (box == null) {
                return;
            }
            TransformBox.Memento before = box.createMemento();
            box.setReferencePoint(point);
            record("Change Transform Reference Point", before);
        }));

        relativeCheckBox.addActionListener(_ -> {
            TransformBox box = boxSupplier.get();
            if (box != null && relativeCheckBox.isSelected()) {
                relativeOrigin = box.getReferencePointInImageSpace();
            } else {
                relativeOrigin = null;
            }
            refreshFromModel();
        });

        installNumericListener(xField, () -> updatePosition(true));
        installNumericListener(yField, () -> updatePosition(false));
        installNumericListener(widthField, () -> updateScale(true));
        installNumericListener(heightField, () -> updateScale(false));
        installNumericListener(rotationField, this::updateRotation);
        installNumericListener(horizontalSkewField, this::updateSkew);
        installNumericListener(verticalSkewField, this::updateSkew);

        modeSelector.addActionListener(_ -> {
            if (refreshing) {
                return;
            }
            TransformBox box = boxSupplier.get();
            TransformMode mode = (TransformMode) modeSelector.getSelectedItem();
            if (box == null || mode == null) {
                return;
            }
            if (mode == TransformMode.WARP
                && !box.getTarget().getTransformCapabilities().contains(TransformCapability.WARP)) {
                Toolkit.getDefaultToolkit().beep();
                refreshFromModel();
                return;
            }
            TransformBox.Memento before = box.createMemento();
            box.setTransformMode(mode);
            record("Change Transform Mode", before);
        });

        warpStyleSelector.addActionListener(_ -> {
            if (refreshing) {
                return;
            }
            TransformBox box = boxSupplier.get();
            WarpStyle style = (WarpStyle) warpStyleSelector.getSelectedItem();
            if (box == null || style == null) {
                return;
            }
            TransformBox.Memento before = box.createMemento();
            box.setWarpStyle(style);
            record("Change Warp Style", before);
        });
    }

    private void updatePosition(boolean xEdited) {
        TransformBox box = boxSupplier.get();
        if (box == null) {
            return;
        }
        Point2D current = box.getReferencePointInImageSpace();
        double x = parse(xEdited ? xField : null, current.getX());
        double y = parse(xEdited ? null : yField, current.getY());
        if (relativeCheckBox.isSelected() && relativeOrigin != null) {
            x = xEdited ? relativeOrigin.getX() + parse(xField, 0) : current.getX();
            y = xEdited ? current.getY() : relativeOrigin.getY() + parse(yField, 0);
        }
        TransformBox.Memento before = box.createMemento();
        box.translateReferenceTo(x, y);
        record("Move Free Transform", before);
    }

    private void updateScale(boolean widthEdited) {
        TransformBox box = boxSupplier.get();
        if (box == null) {
            return;
        }
        double currentWidth = box.getWidthPercent();
        double currentHeight = box.getHeightPercent();
        double width = widthEdited ? parse(widthField, currentWidth) : currentWidth;
        double height = widthEdited ? currentHeight : parse(heightField, currentHeight);
        if (linkButton.isSelected()) {
            if (widthEdited) {
                height = currentHeight * width / currentWidth;
            } else {
                width = currentWidth * height / currentHeight;
            }
        }
        TransformBox.Memento before = box.createMemento();
        box.setScalePercent(width, height);
        record("Scale Free Transform", before);
    }

    private void updateRotation() {
        TransformBox box = boxSupplier.get();
        if (box == null) {
            return;
        }
        TransformBox.Memento before = box.createMemento();
        box.setRotationDegrees(parse(rotationField, box.getRotationDegrees()));
        record("Rotate Free Transform", before);
    }

    private void updateSkew() {
        TransformBox box = boxSupplier.get();
        if (box == null) {
            return;
        }
        TransformBox.Memento before = box.createMemento();
        box.setSkewDegrees(
            parse(horizontalSkewField, box.getHorizontalSkewDegrees()),
            parse(verticalSkewField, box.getVerticalSkewDegrees()));
        record("Skew Free Transform", before);
    }

    private void installNumericListener(JTextField field, Runnable updater) {
        field.addActionListener(_ -> runNumericUpdate(updater));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                runNumericUpdate(updater);
            }
        });
    }

    private void runNumericUpdate(Runnable updater) {
        if (refreshing) {
            return;
        }
        try {
            updater.run();
        } catch (IllegalArgumentException e) {
            Toolkit.getDefaultToolkit().beep();
            refreshFromModel();
        }
    }

    private void record(String name, TransformBox.Memento before) {
        editRecorder.accept(name, before);
        refreshFromModel();
    }

    public void refreshFromModel() {
        TransformBox box = boxSupplier.get();
        if (box == null) {
            return;
        }
        refreshing = true;
        try {
            referenceButtons.get(box.getReferencePoint()).setSelected(true);
            Point2D point = box.getReferencePointInImageSpace();
            if (relativeCheckBox.isSelected() && relativeOrigin != null) {
                xField.setText(format(point.getX() - relativeOrigin.getX()));
                yField.setText(format(point.getY() - relativeOrigin.getY()));
            } else {
                xField.setText(format(point.getX()));
                yField.setText(format(point.getY()));
            }
            widthField.setText(format(box.getWidthPercent()));
            heightField.setText(format(box.getHeightPercent()));
            rotationField.setText(format(box.getRotationDegrees()));
            horizontalSkewField.setText(format(box.getHorizontalSkewDegrees()));
            verticalSkewField.setText(format(box.getVerticalSkewDegrees()));
            modeSelector.setSelectedItem(box.getTransformMode());
            warpStyleSelector.setSelectedItem(box.getWarpStyle());
            boolean warp = box.getTransformMode() == TransformMode.WARP;
            warpStyleSelector.setVisible(warp);
            warpStyleSelector.setEnabled(warp);
        } finally {
            refreshing = false;
        }
    }

    private static JTextField field(String name) {
        JTextField field = new JTextField(5);
        field.setName(name);
        field.setHorizontalAlignment(SwingConstants.RIGHT);
        return field;
    }

    private void addLabeled(String label, JComponent component) {
        add(new JLabel(label));
        add(component);
    }

    private void addSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(2, 22));
        add(separator);
    }

    private static double parse(JTextField field, double fallback) {
        if (field == null) {
            return fallback;
        }
        double value = Double.parseDouble(field.getText().trim());
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Value must be finite");
        }
        return value;
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0e-7) {
            return Long.toString(Math.round(value));
        }
        return String.format("%.2f", value);
    }
}
