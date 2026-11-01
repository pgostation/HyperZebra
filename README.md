# HyperZebra

HyperZebra is an environment for HyperCard stacks and classic Mac
applications, written in Java for macOS, Windows, and Linux.

- **HyperCard stacks**: import a stack, browse and edit its cards, and run its
  HyperTalk scripts and XCMD/XFCN externals. HyperZebra keeps HyperCard’s ease
  of use and adds color painting, editable shapes, panels, and modern data
  handling such as arrays, JSON, and regular expressions.
- **Classic Mac applications**: run supported 68K and PowerPC applications made
  for Mac OS 9 and earlier. HyperZebra emulates the 68K and PowerPC CPUs and
  implements the Mac Toolbox services that applications call (windows, menus,
  QuickDraw, sound, files, QuickTime, 3D) on the host, so no Mac ROM or classic
  Mac OS installation is needed.

[Download HyperZebra](https://github.com/pgostation/HyperZebra/releases) ·
[Website](https://pgostation.github.io/HyperZebra-site/en/) ·
[日本語サイト](https://pgostation.github.io/HyperZebra-site/)

HyperZebra is free to download, and its source code is available under the MIT
License. It is an unofficial compatibility environment and is not affiliated
with, endorsed by, or supported by Apple Inc.

HyperZebra began around 2012 as a HyperCard clone. Version 3 continues it as a
complete rewrite, developed entirely by AI coding agents.

## Features

### HyperCard stacks

- Import HyperCard stacks, including Japanese and other non-English stacks, and
  save them as single-file `.hzb` documents. Stacks can also be exported back
  to HyperCard format, without HyperZebra-only features such as color.
- Create and edit cards, backgrounds, buttons, and fields.
- Paint in color with layers, and draw editable shapes (rectangles, ellipses,
  polygons, curves, text) with gradients, opacity, and rotation. SVG files can
  be imported as shapes.
- Group parts into panels that have their own scripts and data, and show
  fields as tables or outlines.
- Write HyperTalk in a script editor with live error checking, a command
  reference, and a debugger. HyperTalk extensions include arrays,
  dictionaries, JSON, regular expressions, and web requests.
- Edit a stack’s icons, cursors, sounds, and other resources in the built-in
  resource editor.

### Classic Mac applications

- Run supported 68K and PowerPC applications, including games that use
  QuickDraw 3D, RAVE, or OpenGL (rendered in software).
- Adjust screen size, color depth, zoom, and speed for each application, and
  change the window appearance with Kaleidoscope schemes.
- Exchange text and pictures with the host through the clipboard.
- Open Disk Copy, HFS, HFS Plus, MFS, and ISO disk images, and keep resource
  forks through MacBinary, BinHex, and AppleDouble (`._` files). StuffIt,
  Compact Pro, and LHA archives are expanded by classic expander applications
  running inside HyperZebra.
- Add extensions, control panels, and fonts to a virtual System Folder.

### General

- Open stacks, applications, and documents from a launcher with a history and
  a dock.
- Use the interface in English, Japanese, German, French, Spanish, Italian,
  Portuguese, Korean, and Simplified or Traditional Chinese.
- Read HyperZebra Help (English and Japanese) for stack import, editing, the
  HyperTalk extensions, and the Mac compatibility environment.

Compatibility varies by stack, script, and application: HyperTalk and the Mac
Toolbox are covered widely, but not completely.

## Downloads

Ready-to-use packages are available from
[GitHub Releases](https://github.com/pgostation/HyperZebra/releases):

- macOS (Apple silicon or Intel) and Windows (x64) packages include a Java
  runtime, so no separate Java installation is needed.
- The cross-platform jar package, for Linux and other systems, needs Java 17
  or later with desktop support.

Use a release package if you want to run HyperZebra with its supporting files.

This repository contains the source files needed to build the application jar.
It does not contain the runtime data or packaging tools used for the complete
release distributions.

## Build from source

### Requirements

- JDK 17 or later, with `java`, `javac`, and `jar` available.
- Bash on macOS or Linux, or PowerShell on Windows.
- No third-party Java libraries or build tools are required.

JDK 22 or later also compiles the optional Foreign Function & Memory (FFM)
helpers for platform integration. These helpers are skipped with older JDKs.
Set `JAVA_HOME` to select the JDK used for the build.

### macOS / Linux

From the repository root:

```sh
./build.sh
```

### Windows

From the repository root in PowerShell:

```powershell
.\build.ps1
```

Both scripts generate `build/HyperZebra.jar` and include the UI translation
catalogs in the jar.

### Running a source build

With the supporting runtime files in place, run:

```sh
java -jar build/HyperZebra.jar
```

The jar build does not create a complete distribution. This repository omits
runtime resources (`resource/`), Home stack data, extractor applications,
samples, tests, release packaging tools, and the optional macOS Swift
translation helper. Use a release package for the complete runtime layout.

## Source layout

| Path | Contents |
| --- | --- |
| [`src/`](src/) | Java application sources, organized by area; see the [source guide](src/README.md). |
| [`src/ffm/`](src/ffm/) | Optional platform helpers requiring JDK 22 or later. |
| [`lang/`](lang/) | UI translation catalogs included in the jar. |
| `build.sh`, `build.ps1` | Build scripts for macOS/Linux and Windows. |
| `tools/jdk.ps1` | JDK discovery and command helpers used by the Windows build. |

## License

The source code in this repository is available under the
[MIT License](LICENSE.txt).
See [THIRD_PARTY.md](THIRD_PARTY.md) for third-party notices.

Stacks and classic Mac applications have their own authors and terms of use
and distribution.

Macintosh, HyperCard, HyperTalk, Mac, macOS, QuickDraw, and QuickTime are
trademarks of Apple Inc., registered in the U.S. and other countries. Other
product names are trademarks of their respective owners.
