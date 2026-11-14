# Third-party notices

The HyperZebra source code is released under the [MIT License](LICENSE.txt).
The desktop application jar builds from this repository's Java sources and
translation catalogs with the JDK alone; no third-party jars are needed for
that build. The notices below cover incorporated work, implementation
references, and optional components used by HyperZebra 3.0.3.

The Web player, its optional TeaVM compiler, Tesseract, the Java runtime, and
classic Mac runtime data are separate from the application jar. Their licenses
are not replaced by HyperZebra's MIT License.

## HyperCardPreview (Pierre Lorenzi) — MIT License

The WOBA (HyperCard card/background picture) decoder in
[`src/hypercard/HCData.java`](src/hypercard/HCData.java)
(`readWOBA`, `applyDh`) is a Java port of `decodeLayer` / `applyDx` in
`HyperCardCommon/BitmapLoading.swift`. The project's `StackFormat.md` is also
a reference for the HyperCard stack format.

https://github.com/PierreLorenzi/HyperCardPreview

    MIT License

    Copyright (c) 2021 Pierre Lorenzi

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

## Unicode Character Database mapping table — Unicode License

[`src/formats/MacJapaneseDecoder.java`](src/formats/MacJapaneseDecoder.java)
embeds the differences between Shift-JIS and Apple's
Mac OS Japanese encoding taken from the Unicode Consortium's vendor mapping
table `APPLE/JAPANESE.TXT`.

https://www.unicode.org/Public/MAPPINGS/VENDORS/APPLE/JAPANESE.TXT

    Copyright © 1991-2024 Unicode, Inc. All rights reserved.
    Distributed under the Terms of Use in https://www.unicode.org/copyright.html
    (the Unicode License, https://www.unicode.org/license.txt).

## python-rsrcfork (dgelessus) — MIT License

[`src/formats/MacResourceDecompressor.java`](src/formats/MacResourceDecompressor.java)
reimplements System 7's `dcmp` 0/1/2 compressed
resource formats following the format description published by the
python-rsrcfork project. No code was copied.

https://github.com/dgelessus/python-rsrcfork

## Executor (ARDI / ctm) — MIT License

Some Toolbox behaviour (offscreen GWorld port coordinates, Apple Event handler
table layouts) was checked against the Executor sources and documentation.
No code was copied.

https://github.com/ctm/executor

## Scale2x / EPX — approach only

The "Scale2x+" choice of the smooth zoom
([`src/vm68k/process/SmoothScaler.java`](src/vm68k/process/SmoothScaler.java)) starts from the
published rule of Eric Johnston's EPX, known as Scale2x (Andrea Mazzoleni): a
corner of a pixel takes its two side neighbours' colour when they are alike, the
pixel is not, and neither matches the neighbour across from it. Everything on
top of that rule (likeness by YUV thresholds, rectangle corners kept sharp, 2:1
slopes, area-weighted anti-aliased cuts at 2x/3x/4x directly) was written for
HyperZebra; no Scale2x code (GPL) was copied.

https://www.scale2x.it/algorithm

## LZHUF (Haruyasu Yoshizaki, Haruhiko Okumura) — public domain

The DART disk image decoder
([`src/formats/DartLzh.java`](src/formats/DartLzh.java)) implements LZHUF for
the format's "best" compression, following the public-domain LZHUF.C
algorithm. The DART layout was checked against
[CiderPress II's DART format notes](https://ciderpress2.com/formatdoc/DART-notes.html)
by Andy McFadden. CiderPress II's code was read as a reference, not copied.

## Tesseract OCR — Apache License 2.0 (separate download)

HyperZebra runs Tesseract as a separate process to read text in pictures
(`PictureOcr.java`, `PictureTranslation.java`, `PictTranslation.java`).
[`src/stack/TesseractInstall.java`](src/stack/TesseractInstall.java) can
download it on request from the launcher's Translation settings, or use an
existing installation. Tesseract is not part of `HyperZebra.jar`.

- **macOS**: HyperZebra's Tesseract 5.5.4 download is built from unmodified
  upstream sources and includes these components and their license texts in
  its `licenses/` directory:
  [Tesseract OCR](https://github.com/tesseract-ocr/tesseract/tree/5.5.4)
  ([Apache License 2.0](https://github.com/tesseract-ocr/tesseract/blob/5.5.4/LICENSE)),
  [Leptonica](https://github.com/DanBloomberg/leptonica)
  (BSD 2-Clause License, Copyright (C) 2001-2020 Leptonica), and
  [libpng](https://github.com/pnggroup/libpng)
  (PNG Reference Library License version 2, Copyright (c) 1995-2026 The PNG
  Reference Library Authors and the other holders named in its license).
  The download uses macOS's zlib and C++ runtime libraries, which it does not
  redistribute.
- **Windows**: HyperZebra downloads and opens the
  [UB Mannheim Tesseract installer](https://github.com/UB-Mannheim/tesseract).
  That installer supplies its own components and notices.
- **Linux and other systems**: use the system's Tesseract package.

Language models are downloaded separately from
[tessdata_fast 4.1.0](https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0)
(Apache License 2.0), or selected from the user's own files. Downloaded models
are stored in the `tessdata/` subfolder of HyperZebra's state directory.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## TeaVM — Apache License 2.0 (Web player and optional compiler)

[TeaVM 0.14.1](https://github.com/konsoletyper/teavm/tree/0.14.1), Copyright
Alexey Andreev and the TeaVM contributors, is used to compile HyperZebra's
shared HyperTalk core to WebAssembly. TeaVM's
[Apache License 2.0](https://github.com/konsoletyper/teavm/blob/0.14.1/LICENSE)
covers its Java class library compiled into `player.wasm` and
`player-68k.wasm`, its `wasm-gc-runtime.min.js`, and the TeaVM compiler jar.

The player files and `TeaVM-LICENSE.txt` are distributed separately in
`Support Files/WebPlayer/`. The optional compiler can be supplied with that
player or downloaded from a matching `web-compiler-<version>` GitHub release;
`WebAotBuild.java` checks the ZIP against the SHA-256 in the player's
`compiler.properties` before installing it. Its `compiler/teavm.jar` also
retains the upstream license information.

The Java sources in this repository include web-export integration, but
`build.sh` and `build.ps1` do not build or bundle TeaVM, the Web player, or
the optional compiler into the application jar.

## Java runtime — separate from the source jar

The source jar uses the JDK and Java standard library provided by the user.
The ready-to-use Mac and Windows releases include their own Java runtime and
its upstream legal notices. Those runtime licenses and notices remain in the
runtime's `legal/` directory.

## Apple HyperCard runtime data — separate from the source jar

The complete release distributions include `Support Files/sampleHomeApp`
and its resource fork (`._sampleHomeApp`), a stand-alone runtime made with
HyperCard 2.4.1 (Copyright © 1987-1998 Apple Computer, Inc.). On first setup,
`HomeStack.java` reads its standard icons, sounds, cursors, patterns, and
pictures into the user's Home stack. The separate `home.hzb` template
contains no HyperCard resources. These Apple runtime files and resources
are not included in this source jar and are not covered by HyperZebra's MIT
License.

## Optional external programs

- **[FFmpeg](https://ffmpeg.org/)**: an optional, separately installed program
  used as a conversion fallback by `TQTMovie.java` and `MaceFfmpeg.java`.
  HyperZebra has its own decoders for supported QuickTime formats, MACE
  3:1/6:1, and MP3, so those supported formats do not require FFmpeg.
  FFmpeg is not bundled or linked into the application jar. Its license
  depends on the selected build components; see
  [FFmpeg's license information](https://ffmpeg.org/legal.html).
- **User-supplied stacks and classic Mac applications** retain their authors'
  licenses and distribution terms. HyperZebra can also read resources from
  HyperCard files supplied by the user; this does not change ownership of
  those files or resources.
