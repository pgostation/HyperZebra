# HyperZebra

HyperZebra is an environment for HyperCard stacks and classic Mac
applications, written in Java for macOS, Windows, and Linux.

The source version in this repository is **3.0.3**.

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
  dictionaries, JSON, regular expressions, web requests, binary data, and
  image processing. Frequently used script blocks can run through the
  HyperTalk JIT compiler.
- Edit a stack’s icons, cursors, sounds, and other resources in the built-in
  resource editor.
- Export a stack for a web server with **File > Export for Web…**. The browser
  player runs the HyperTalk core as WebAssembly and can include 68K XCMD/XFCN
  support. Browser playback does not save changes back to the stack, and its
  compatibility differs from the desktop application.

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
- Play supported QuickTime movies, MACE sounds, and MP3 audio with the built-in
  decoders. An installed FFmpeg can handle additional media conversions.

### General

- Open stacks, applications, and documents from a launcher with a history and
  a dock.
- Use the interface in English, Japanese, German, French, Spanish, Italian,
  Portuguese, Korean, and Simplified or Traditional Chinese.
- Read HyperZebra Help for stack import, editing, the
  HyperTalk extensions, and the Mac compatibility environment.
- Use Tesseract OCR to read text in pictures. Tesseract and its language data
  can be downloaded from the launcher's Translation settings; they are not
  needed to build the application jar.

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

TeaVM, Tesseract, and FFmpeg are optional runtime components, not prerequisites
for this jar build. The browser player and its compiler are built separately
and are not generated by `build.sh` or `build.ps1`.

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
catalogs from `lang/*.po` in the jar. The application version is defined by
`longVersion` in `src/core/HZ.java`.

### Running a source build

To use the new jar with a complete set of matching runtime files:

1. Extract the portable JAR package for the same HyperZebra version from
   [GitHub Releases](https://github.com/pgostation/HyperZebra/releases).
2. Replace the extracted package's `HyperZebra.jar` with
   `build/HyperZebra.jar` from this source build.
3. Keep the package's other files together and start it with `sh ./run.sh`
   on macOS/Linux or `run.bat` on Windows.

The jar build does not create a complete distribution. This repository omits
runtime resources (`resource/`), Home stack data, extractor applications,
samples, tests, release packaging tools, and the optional macOS Swift
translation helper. Use a release package for the complete runtime layout.

### Web export and optional downloads

Web export also needs the matching player files in `Support Files/WebPlayer/`
beside the runtime jar. These files are supplied by the release package, not
by this source build. If the optional script compiler is not included, it can
be downloaded from the export dialog. Its URL and SHA-256 come from the player's
`compiler.properties`; downloads are published under tags such as
`web-compiler-3.0.3` and checked before installation. The prebuilt player can
be used without installing the compiler.

Tesseract OCR runs as a separate program. On macOS, HyperZebra can download
its Tesseract build from a `tesseract-<version>` release; on Windows, it opens
the UB Mannheim installer. On Linux, install the system's Tesseract package.
See [THIRD_PARTY.md](THIRD_PARTY.md) for the components and their notices.

The optional macOS system-translation and local-AI integration also needs the
separate Swift helper supplied by the Mac release; this source repository
does not build that helper.

Settings and personal data are stored outside the application: in
`~/Library/Application Support/HyperZebra` on macOS, `%LOCALAPPDATA%\HyperZebra`
on Windows, and `$XDG_STATE_HOME/HyperZebra` (default
`~/.local/state/HyperZebra`) on Linux.

## Source layout

| Path | Contents |
| --- | --- |
| [`src/`](src/) | Java application sources, organized by area; see the [source guide](src/README.md). |
| [`src/core/`](src/core/) | Shared HyperTalk state, models, rendering, and host interfaces used by the desktop and browser player. |
| [`src/ui/`](src/ui/) | Desktop views, Swing host implementations, and web-export integration. |
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
