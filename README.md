[![Latest Release](https://img.shields.io/github/v/release/lbalazscs/pixelitor?include_prereleases)](https://github.com/lbalazscs/Pixelitor/releases)
[![Build Status](https://github.com/lbalazscs/Pixelitor/actions/workflows/build.yml/badge.svg)](https://github.com/lbalazscs/Pixelitor/actions/workflows/build.yml)


This is the source code of [Pixelitor](https://pixelitor.sourceforge.io/) - an advanced Java image editor with layers, layer masks, text layers, 110+ image filters and color adjustments, multiple undo etc.

**Select > Select All** (`Cmd+A` on macOS, `Ctrl+A` on Windows/Linux) selects the entire canvas, replacing the current selection. The change supports undo and redo.

**Select > Invert Selection** (`Cmd+Shift+I` on macOS, `Ctrl+Shift+I` on Windows/Linux) selects the entire canvas when nothing is selected, and selects nothing when the entire canvas is selected.

Text dialogs remember the last formatting accepted with **OK**, including font, size, color, alignment, effects, and advanced settings, across dialog openings and app restarts. New text starts with the usual placeholder; editing a text layer uses that layer's settings. Cancel leaves the remembered defaults unchanged.

**Preferences > UI > Live Text Resize** is enabled by default and previews font size changes while dragging the text dialog slider. Turn it off to apply the size only when the slider value commits, such as when you release the mouse. The preference persists across app restarts.

The built-in color picker includes **Eyedropper** to sample a pixel from the active image and 20 **Recent Colors** swatches shared with foreground, background, and filter colors. Click a swatch to preview it, then **OK** to accept. During eyedropper sampling, **Esc** or right-click returns to the picker without changing the color.

The **Text History** dropdown reuses the 20 most recent accepted texts, newest first. Each entry has a preview of up to 60 characters; selecting it restores the complete text, including line breaks. History also persists across app restarts.

Contributions are welcome! See [Contributing](CONTRIBUTING.md). 

## Starting Pixelitor in an IDE

Pixelitor requires Java 25+ to compile. When you start the program from an IDE, use **pixelitor.Pixelitor** as the main class.

## Building the Pixelitor jar file from the command line

1. OpenJDK 25+ has to be installed, and the environment variable JAVA_HOME must point to the OpenJDK installation
   directory.
2. Execute `./mvnw clean package` in the main directory (where the pom.xml file is). The Maven Wrapper downloads the required Maven version automatically and creates an executable jar in the `target` subdirectory. On Windows, use `mvnw.cmd clean package` instead. If you didn't change anything, or if you only changed translations/icons, then you can skip the tests by adding `-Dmaven.test.skip=true`.

## Installing Pixelitor as a macOS app

`./macDeployToApplications` - Installs Pixelitor as a self-contained macOS `.app` bundle in `/Applications` (overwrites an existing installation). JDK 25+ is required.

## Translating the Pixelitor user interface

See [Translating](Translating.md).
