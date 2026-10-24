# Third-party notices

HyperZebra itself is released under the MIT License (see `LICENSE.txt`). It
links against no third-party libraries: the program is built from the Java
sources in this folder with the JDK alone. The items below are the external
works whose code, data or documentation HyperZebra incorporates or relies on.

## HyperCardPreview (Pierre Lorenzi) — MIT License

The WOBA (HyperCard card/background picture) decoder in `HCData.java`
(`readWOBA`, `applyDh`) is a Java port of `decodeLayer` / `applyDx` in
`HyperCardCommon/BitmapLoading.swift`, and `HYPERCARD_STACK_FORMAT.md` cites
the project's `StackFormat.md`.

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

`MacJapaneseDecoder.java` embeds the differences between Shift-JIS and Apple's
Mac OS Japanese encoding taken from the Unicode Consortium's vendor mapping
table `APPLE/JAPANESE.TXT`.

https://www.unicode.org/Public/MAPPINGS/VENDORS/APPLE/JAPANESE.TXT

    Copyright © 1991-2024 Unicode, Inc. All rights reserved.
    Distributed under the Terms of Use in https://www.unicode.org/copyright.html
    (the Unicode License, https://www.unicode.org/license.txt).

## python-rsrcfork (dgelessus) — MIT License

`MacResourceDecompressor.java` reimplements System 7's `dcmp` 0/1/2 compressed
resource formats following the format description published by the
python-rsrcfork project. No code was copied.

https://github.com/dgelessus/python-rsrcfork

## Executor (ARDI / ctm) — MIT License

Some Toolbox behaviour (offscreen GWorld port coordinates, Apple Event handler
table layouts) was checked against the Executor sources and documentation.
No code was copied.

https://github.com/ctm/executor

## Scale2x / EPX — approach only

The "Scale2x+" choice of the smooth zoom (`SmoothScaler.java`) starts from the
published rule of Eric Johnston's EPX, known as Scale2x (Andrea Mazzoleni): a
corner of a pixel takes its two side neighbours' colour when they are alike, the
pixel is not, and neither matches the neighbour across from it. Everything on
top of that rule (likeness by YUV thresholds, rectangle corners kept sharp, 2:1
slopes, area-weighted anti-aliased cuts at 2x/3x/4x directly) was written for
HyperZebra; no Scale2x code (GPL) was copied.

https://www.scale2x.it/algorithm

## Optional external programs

- **ffmpeg** (https://ffmpeg.org, LGPL/GPL): not bundled. If installed on the
  user's machine, HyperZebra runs it as a separate process to convert QuickTime
  movies for the `QTMovie`/`Movie` XCMDs (`TQTMovie.java`) and MACE 3:1/6:1
  compressed `snd` resources (`MaceConverter.java`). Without it those formats
  are reported as unsupported.
- **Apple HyperCard Player / stacks**: HyperZebra reads stacks, icons, patterns
  and sounds from HyperCard files the user supplies. No Apple resources are
  shipped with the program.
