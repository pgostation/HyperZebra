# src/ — HyperZebra のソース

分野ごとのフォルダに分けて置いている。Java のパッケージは使っておらず、どのファイルも
デフォルトパッケージのまま(パッケージ非公開のメンバーをフォルダをまたいで使っている)。
フォルダは整理のためだけのもので、ファイルを別のフォルダへ移してもコードは変えなくてよい。
`build.sh` / `build.ps1` は `src/` の下の `*.java` を全部
(`src/ffm` を除いて)Java 17向けにコンパイルする。`src/ffm` はJava 22以上のJDKで
別途コンパイルし、対応しないJDKでは省略する。`lang/*.po` もJARへ取り込む。

現在の版は3.0.3。バージョン番号の定義は `core/HZ.java` の `longVersion`。

| フォルダ | 中身 | 主なファイル |
|---|---|---|
| `app/` | 起動、アプリ全体の設定・状態、サンドボックス、UI言語、終了処理、ゲームパッド | HyperZebra, PCARD, Sandbox, International, QuitSequence |
| `core/` | デスクトップとブラウザーで共有するHyperTalkの状態・モデル・描画と、ホスト機能の境界 | HZ, Host, UiHost, Platform, HzPict, JpegDecoder, MacSystemPalette |
| `launcher/` | ランチャーのウインドウと、そこから開く設定・情報ダイアログ | GLauncher, LauncherStore, GPreferencesDialog, GHelpWindow |
| `stack/` | スタックのモデル(スタック/カード/背景/部品)と読み書き・保存 | OStack, OCard, OButton, OField, XMLRead, XMLwrite, StackBundle |
| `hypercard/` | 本物の HyperCard スタック(.stak)の読み込み・書き出しと文字コード | HCData, HCConvert, HCExport, StackEncodingDetector |
| `hypertalk/` | HyperTalk インタプリタ、JIT/AOTコンパイラー、コマンド・関数、配列・バイナリ・画像処理 | TTalk, TUtil, TalkJit, TalkJitAot, TalkBinary, TalkPixels |
| `script/` | スクリプトエディタ、デバッガ、メッセージ/変数ウォッチャー、クイックリファレンス | ScriptEditor, ScriptLint, ScriptDebugger |
| `ui/` | カードウインドウ、Swingのホスト実装、メニュー・ダイアログ、Web書き出し | GUI, GMenu, SwingHost, SwingUiHost, WebExport, WebAotBuild |
| `paint/` | ペイントツール、レイヤー操作、フィルタ、ペイント用パレット(レイヤーのモデルは `stack/PaintLayer.java`) | PaintTool, PaintFilter, PaintLayers, GPaintPalette |
| `shape/` | ベクターシェイプ(SVG)の編集 | ShapeTool, ShapeEditor, ShapeSvg, SvgFilter |
| `resedit/` | リソースエディタ(ResEdit 風)と個々のリソースのエディタ | ResEdit, ResEditIndex, IconEditor, SoundEditor |
| `media/` | MACE・MP3・QuickTimeのデコーダとデスクトップ側の音声・動画処理(JPEGの共有デコーダは `core/`) | MaceDecoder, Mp3Decoder, QuickTimeFile, MovieData, MaceFfmpeg |
| `formats/` | ディスクイメージ・アーカイブ・Mac 固有のファイル形式とエンコーディング | DiskImage, HfsVolume, ZipExpander, BinHex, MacJapaneseDecoder |
| `vm68k/cpu/` | 68K CPU・FPU・メモリ・I/O チップ・SANE | M68kCpu, M68kFpu, M68kMemory |
| `vm68k/process/` | 68K プログラムの起動とホスト(プロセス、トラップの入口、cdev/DA/機能拡張、画面表示) | MacApp68k, MacAppHost, Toolbox68k, Mac68kApi |
| `vm68k/hypercard/` | XCMD/XFCN と、外部コマンドから見た HyperCard | Xcmd68k, XWindows68k, HyperCard68kCallbacks |
| `vm68k/toolbox/` | Toolbox の各マネージャ(ウインドウ、メニュー、ダイアログ、イベント、Apple Event など) | WindowManager68k, MenuManager68k, DialogManager68k |
| `vm68k/quickdraw/` | QuickDraw、フォント、TextEdit | QuickDraw68k, QdRegion, Fonts68k, TextEdit68k |
| `vm68k/files/` | ファイルマネージャ、リソースマネージャ、ホストのファイルの見せ方、ディスクイメージのマウント | FileManager68k, ResourceManager68k, DiskImages |
| `vm68k/sound/` | サウンドマネージャ、ミキサー、MIDI | SoundManager68k, SoundMixer68k, MidiManager68k |
| `vm68k/quicktime/` | QuickTime(Movie Toolbox, Image Compression Manager, グラフィックインポータ) | QuickTime68k, ImageCompression68k |
| `vm68k/net/` | AppleTalk, MacTCP, Open Transport, Communications Toolbox | AppleTalk68k, MacTCP68k, OpenTransport |
| `ppc/runtime/` | PowerPC CPU、PEF/CFM、標準ライブラリ | PpcCpu, Pef, PpcRuntime, PpcStdCLib |
| `ppc/carbon/` | CarbonLib とその周辺のライブラリ | PpcCarbon, PpcCoreFoundation, PpcNavServices |
| `ppc/graphics3d/` | QuickDraw 3D, RAVE, OpenGL, Game Sprockets | PpcQuickDraw3D, Q3View, PpcRave, PpcOpenGL |
| `ffm/` | Java 22 以降の Foreign Function API を使う補助クラス(別にコンパイルし、リフレクションで読む) | NativeGamepadHid, NativeXattr, NativeCoreMidi |

このリポジトリはjarのビルドに必要なソースのみを収録している。テストや開発・配布用の道具は含まない。
`../tools/jdk.ps1` はWindowsビルド用のJDK検出・コマンド実行ヘルパー。

`core/` の `Host` / `UiHost` / `Platform` を介して、共有コードからデスクトップ固有の処理を分離している。
デスクトップでの実装は `ui/SwingHost.java`、`ui/SwingUiHost.java`、`app/JdkPlatform.java` など。
共有側にはブラウザー用の実装も含むが、このリポジトリのビルドスクリプトが作るのはデスクトップ用JAR。
TeaVMによるWebプレーヤーのビルド用ツールや生成物は含まない。
