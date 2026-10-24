# src/ — HyperZebra のソース

分野ごとのフォルダに分けて置いている。Java のパッケージは使っておらず、どのファイルも
デフォルトパッケージのまま(パッケージ非公開のメンバーをフォルダをまたいで使っている)。
フォルダは整理のためだけのもので、ファイルを別のフォルダへ移してもコードは変えなくてよい。
`build.sh` / `build.ps1` / `jpackage.ps1` は `src/` の下の `*.java` を全部
(`src/ffm` を除いて)コンパイルする。

| フォルダ | 中身 | 主なファイル |
|---|---|---|
| `app/` | 起動、アプリ全体の設定・状態、サンドボックス、UI言語、終了処理、ゲームパッド | HyperZebra, PCARD, Sandbox, International, QuitSequence |
| `launcher/` | ランチャーのウインドウと、そこから開く設定・情報ダイアログ | GLauncher, LauncherStore, GPreferencesDialog, GHelpWindow |
| `stack/` | スタックのモデル(スタック/カード/背景/部品)と読み書き・保存 | OStack, OCard, OButton, OField, XMLRead, XMLwrite, StackBundle |
| `hypercard/` | 本物の HyperCard スタック(.stak)の読み込み・書き出しと文字コード | HCData, HCConvert, HCExport, StackEncodingDetector |
| `hypertalk/` | HyperTalk インタプリタと、そのコマンド・関数の実装 | TTalk, TUtil, TXcmd, TSound, TalkRegex |
| `script/` | スクリプトエディタ、デバッガ、メッセージ/変数ウォッチャー、クイックリファレンス | ScriptEditor, ScriptLint, ScriptDebugger |
| `ui/` | カードウインドウまわりの画面:メニュー、メッセージボックス、ツールパレット、ダイアログ、視覚効果 | GUI, GMenu, GMsg, GToolBar, VEffect |
| `paint/` | ペイントツール、レイヤー、フィルタ、ペイント用パレット | PaintTool, PaintFilter, PaintLayer, GPaintPalette |
| `shape/` | ベクターシェイプ(SVG)の編集 | ShapeTool, ShapeEditor, ShapeSvg, SvgFilter |
| `resedit/` | リソースエディタ(ResEdit 風)と個々のリソースのエディタ | ResEdit, ResEditIndex, IconEditor, SoundEditor |
| `media/` | 音声・動画・画像の自前デコーダ(MACE, MP3, QuickTime ファイル, JPEG) | MaceDecoder, Mp3Decoder, QuickTimeFile, JpegDecoder |
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

テストは `../tests/`、開発用の道具は `../tools/` にある。
