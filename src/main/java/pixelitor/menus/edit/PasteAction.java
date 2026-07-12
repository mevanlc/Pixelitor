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

import pixelitor.Views;
import pixelitor.gui.View;
import pixelitor.gui.utils.NamedAction;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.Messages;
import pixelitor.utils.ViewActivationListener;

import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;

import static pixelitor.utils.Texts.i18n;

/**
 * Action for pasting an image from the system clipboard to a given target.
 */
public class PasteAction extends NamedAction implements ViewActivationListener {
    private final PasteTarget pasteTarget;

    // When true, this action stays enabled even when no view is open;
    // invoking it with no view falls back to {@link PasteTarget#NEW_IMAGE}.
    // Used so the Cmd/Ctrl+V shortcut keeps working with no document open.
    private boolean primary;

    public PasteAction(PasteTarget pasteTarget) {
        super(i18n(pasteTarget.getResourceKey()));

        this.pasteTarget = pasteTarget;

        if (pasteTarget.requiresOpenView()) {
            Views.addActivationListener(this);
            setEnabled(false); // disabled by default until a view is opened
        }
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
        if (primary) {
            setEnabled(true);
        } else if (pasteTarget.requiresOpenView() && Views.getActive() == null) {
            setEnabled(false);
        }
    }

    @Override
    protected void onClick(ActionEvent e) {
        BufferedImage image = retrieveClipboardImage();
        if (image != null) {
            PasteTarget effectiveTarget =
                (primary && pasteTarget.requiresOpenView() && Views.getActive() == null)
                    ? PasteTarget.NEW_IMAGE
                    : pasteTarget;
            effectiveTarget.paste(image);
        }
    }

    private static BufferedImage retrieveClipboardImage() {
        Image clipImage = ImageUtils.getClipboardImage();
        if (clipImage == null) {
            Messages.showInfo("Paste Error",
                "The clipboard doesn't contain an image.");
            return null;
        }
        try {
            return ImageUtils.copyToBufferedImage(clipImage);
        } catch (Exception ex) {
            Messages.showException(ex);
            return null;
        }
    }

    @Override
    public void viewActivated(View oldView, View newView) {
        assert pasteTarget.requiresOpenView();
        setEnabled(true);
    }

    @Override
    public void allViewsClosed() {
        assert pasteTarget.requiresOpenView();
        if (!primary) {
            setEnabled(false);
        }
    }
}
