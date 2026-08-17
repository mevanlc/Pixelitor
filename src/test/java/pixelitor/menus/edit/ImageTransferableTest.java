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

package pixelitor.menus.edit;

import com.bric.util.JVM;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;

import static java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageTransferableTest {
    @Test
    void exposesRealPngDataBeforeTheGenericImageFlavor() throws Exception {
        var image = new BufferedImage(2, 1, TYPE_INT_ARGB_PRE);
        image.setRGB(0, 0, 0xFFFF0000);
        image.setRGB(1, 0, 0x00000000);

        var transferable = new ImageTransferable(image);

        assertThat(transferable.getTransferDataFlavors())
            .containsExactly(ImageTransferable.PNG_FLAVOR, DataFlavor.imageFlavor);
        assertThat(transferable.isDataFlavorSupported(ImageTransferable.PNG_FLAVOR)).isTrue();
        assertThat(transferable.isDataFlavorSupported(DataFlavor.imageFlavor)).isTrue();
        assertThat(transferable.getTransferData(DataFlavor.imageFlavor)).isSameAs(image);

        var input = (InputStream) transferable.getTransferData(ImageTransferable.PNG_FLAVOR);
        byte[] pngData = input.readAllBytes();
        assertThat(Arrays.copyOf(pngData, 8)).containsExactly(
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(pngData));
        assertThat(decoded.getWidth()).isEqualTo(2);
        assertThat(decoded.getHeight()).isEqualTo(1);
        assertThat(decoded.getRGB(0, 0)).isEqualTo(image.getRGB(0, 0));
        assertThat(decoded.getRGB(1, 0)).isEqualTo(image.getRGB(1, 0));
    }

    @Test
    void rejectsUnsupportedFlavors() throws Exception {
        var transferable = new ImageTransferable(
            new BufferedImage(1, 1, TYPE_INT_ARGB_PRE));

        assertThatThrownBy(() -> transferable.getTransferData(DataFlavor.stringFlavor))
            .isInstanceOf(java.awt.datatransfer.UnsupportedFlavorException.class);
    }

    @Test
    void mapsEncodedPngToTheNativeMacPngType() {
        assumeTrue(JVM.isMac);

        var flavorMap = (SystemFlavorMap) SystemFlavorMap.getDefaultFlavorMap();
        assertThat(flavorMap.getNativesForFlavor(ImageTransferable.PNG_FLAVOR))
            .containsExactly("PNG");
    }
}
