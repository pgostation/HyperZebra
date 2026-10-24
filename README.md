# HyperZebra

HyperZebra is a Java application for opening and editing HyperCard stacks,
running supported HyperTalk scripts, and running classic Macintosh applications
through built-in 68K and PowerPC compatibility environments.

[Download HyperZebra](https://github.com/pgostation/HyperZebra/releases)

## Features

- Import HyperCard stacks and save them in HyperZebra’s `.hzb` format.
- Create and edit cards, backgrounds, buttons, and fields.
- Author with color painting tools, vector shapes, gradients, and transparency.
- Write and debug HyperTalk scripts, with extensions for arrays, dictionaries,
  JSON, regular expressions, and web requests.
- Group parts into panels with their own scripts and data, and display fields
  as tables or outlines.
- Run supported classic Macintosh 68K and PowerPC applications.
- Use a multilingual interface, including English and Japanese.

Compatibility varies by stack, script, and application.

## Downloads

Ready-to-use packages are available from
[GitHub Releases](https://github.com/pgostation/HyperZebra/releases).
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

HyperZebra is available under the [MIT License](LICENSE.txt).
See [THIRD_PARTY.md](THIRD_PARTY.md) for third-party notices.
