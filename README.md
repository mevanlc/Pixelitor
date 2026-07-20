[![Latest Release](https://img.shields.io/github/v/release/lbalazscs/pixelitor?include_prereleases)](https://github.com/lbalazscs/Pixelitor/releases)
[![Build Status](https://github.com/lbalazscs/Pixelitor/actions/workflows/build.yml/badge.svg)](https://github.com/lbalazscs/Pixelitor/actions/workflows/build.yml)


This is the source code of [Pixelitor](https://pixelitor.sourceforge.io/) - an advanced Java image editor with layers, layer masks, text layers, 110+ image filters and color adjustments, multiple undo etc.

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
