/*
 * Copyright 2026 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 */
package pixelitor.tools.transform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pixelitor.TestHelper;
import pixelitor.gui.View;
import pixelitor.history.PixelitorEdit;
import pixelitor.utils.debug.DebugNode;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TransformOptionsPanelTest {
    private TransformBox box;
    private TransformOptionsPanel panel;
    private List<String> editNames;
    private AtomicInteger commits;
    private AtomicInteger cancels;

    @BeforeAll
    static void beforeAllTests() {
        TestHelper.setUnitTestingMode();
    }

    @BeforeEach
    void beforeEachTest() {
        View view = TestHelper.createEmptyComp("TransformOptionsPanelTest").getView();
        box = new TransformBox(new Rectangle(10, 20, 100, 50), view, new DummyTarget());
        box.setUseLegacyHistory(false);
        editNames = new ArrayList<>();
        commits = new AtomicInteger();
        cancels = new AtomicInteger();
        panel = new TransformOptionsPanel(
            () -> box,
            (name, _) -> editNames.add(name),
            commits::incrementAndGet,
            cancels::incrementAndGet);
        panel.refreshFromModel();
    }

    @Test
    void linkedNumericScaleUpdatesGeometryAndRecordsOneStep() {
        JTextField width = component("widthPercent", JTextField.class);
        width.setText("200");
        width.postActionEvent();

        assertThat(box.getWidthPercent()).isCloseTo(200.0, offset());
        assertThat(box.getHeightPercent()).isCloseTo(200.0, offset());
        assertThat(editNames).containsExactly("Scale Free Transform");
    }

    @Test
    void relativePositionStartsAtZeroAndAppliesImageDeltas() {
        JCheckBox relative = component("relativePositioning", JCheckBox.class);
        relative.doClick();
        JTextField x = component("referenceX", JTextField.class);
        JTextField y = component("referenceY", JTextField.class);

        assertThat(x.getText()).isEqualTo("0");
        assertThat(y.getText()).isEqualTo("0");

        x.setText("12");
        x.postActionEvent();
        assertThat(box.getReferencePointInImageSpace().getX()).isCloseTo(72.0, offset());
        assertThat(editNames).containsExactly("Move Free Transform");
    }

    @Test
    void invalidNumericValueRestoresTheLastValidGeometry() {
        JTextField width = component("widthPercent", JTextField.class);
        width.setText("0");
        width.postActionEvent();

        assertThat(box.getWidthPercent()).isCloseTo(100.0, offset());
        assertThat(width.getText()).isEqualTo("100");
        assertThat(editNames).isEmpty();
    }

    @Test
    void warpModeAndStyleStaySynchronizedWithTheModel() {
        JComboBox<?> mode = component("transformModeSelector", JComboBox.class);
        JComboBox<?> style = component("warpStyleSelector", JComboBox.class);

        mode.setSelectedItem(TransformMode.WARP);
        style.setSelectedItem(WarpStyle.ARC);

        assertThat(box.getTransformMode()).isEqualTo(TransformMode.WARP);
        assertThat(box.getWarpStyle()).isEqualTo(WarpStyle.ARC);
        assertThat(editNames).containsExactly("Change Transform Mode", "Change Warp Style");
    }

    @Test
    void commitAndCancelButtonsDelegateToTheSessionLifecycle() {
        component("commitTransform", JButton.class).doClick();
        component("cancelTransform", JButton.class).doClick();

        assertThat(commits).hasValue(1);
        assertThat(cancels).hasValue(1);
    }

    private <T extends Component> T component(String name, Class<T> type) {
        Component found = find(panel, name);
        assertThat(found).as(name).isInstanceOf(type);
        return type.cast(found);
    }

    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container container) {
                Component nested = find(container, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static org.assertj.core.data.Offset<Double> offset() {
        return org.assertj.core.data.Offset.offset(0.001);
    }

    private static class DummyTarget implements Transformable {
        @Override
        public void prepareForTransform() {
        }

        @Override
        public void imTransform(AffineTransform transform) {
        }

        @Override
        public void imTransform(TransformMapping mapping) {
        }

        @Override
        public EnumSet<TransformCapability> getTransformCapabilities() {
            return EnumSet.allOf(TransformCapability.class);
        }

        @Override
        public PixelitorEdit finalizeTransform() {
            return null;
        }

        @Override
        public void cancelTransform() {
        }

        @Override
        public void updateUI(View view) {
        }

        @Override
        public DebugNode createDebugNode(String key) {
            return new DebugNode(key, this);
        }
    }
}
