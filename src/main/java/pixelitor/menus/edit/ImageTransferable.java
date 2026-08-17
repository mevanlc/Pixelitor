/*
 * Copyright 2024 Laszlo Balazs-Csiki and Contributors
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

import javax.imageio.ImageIO;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Implements Transferable to enable copying images to the system clipboard.
 */
final class ImageTransferable implements Transferable {
    static final DataFlavor PNG_FLAVOR = new DataFlavor(
        "image/png; class=java.io.InputStream", "PNG Image");

    private static final DataFlavor[] SUPPORTED_FLAVORS = {
        PNG_FLAVOR, DataFlavor.imageFlavor
    };

    static {
        if (JVM.isMac) {
            // The macOS JDK advertises imageFlavor as PNG, JPEG, and TIFF,
            // but writes TIFF bytes for all three native pasteboard types.
            // Supplying encoded PNG data for the native PNG type prevents
            // browsers from receiving TIFF data labeled as image/png.
            var flavorMap = (SystemFlavorMap) SystemFlavorMap.getDefaultFlavorMap();
            flavorMap.setNativesForFlavor(PNG_FLAVOR, new String[]{"PNG"});
        }
    }

    private final BufferedImage image;
    private final byte[] pngData;

    ImageTransferable(BufferedImage image) throws IOException {
        this.image = Objects.requireNonNull(image);

        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG image writer is available");
        }
        pngData = out.toByteArray();
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return SUPPORTED_FLAVORS.clone();
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return flavor.equals(PNG_FLAVOR) || flavor.equals(DataFlavor.imageFlavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (flavor.equals(PNG_FLAVOR)) {
            return new ByteArrayInputStream(pngData);
        }
        if (flavor.equals(DataFlavor.imageFlavor)) {
            return image;
        }
        throw new UnsupportedFlavorException(flavor);
    }
}
