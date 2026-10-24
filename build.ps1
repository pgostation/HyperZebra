# build.sh のWindows版: build\classes にコンパイルして build\HyperZebra.jar を作る。
#
# 使い方 (PowerShellから):
#   .\build.ps1
# (実行ポリシーで止められたら: powershell -ExecutionPolicy Bypass -File .\build.ps1)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ProjectDir "tools\jdk.ps1")

$ClassesDir = Join-Path $ProjectDir "build\classes"
$JarFile = Join-Path $ProjectDir "build\HyperZebra.jar"

if (Test-Path $ClassesDir) { Remove-Item -Recurse -Force $ClassesDir }
New-Item -ItemType Directory -Force $ClassesDir | Out-Null

# ソースの一覧は引数ファイルで渡す (コマンドラインの長さ制限を避け、日本語のパスもUTF-8で読ませる)
$Sources = Join-Path $ProjectDir "build\sources.txt"
# ソースは src\ の下に分野ごとのフォルダで置く (src\README.md)。src\ffm だけはJava 22用なので後で別にコンパイルする
$FfmDir = Join-Path $ProjectDir "src\ffm"
Write-ArgFile $Sources (Get-ChildItem (Join-Path $ProjectDir "src") -Recurse -Filter "*.java" | Where-Object { -not $_.FullName.StartsWith($FfmDir + [System.IO.Path]::DirectorySeparatorChar) }).FullName
Invoke-Native $Javac @("--release", "17", "-encoding", "UTF-8", "-d", $ClassesDir, "@$Sources")

# Optional helpers that need the Foreign Function API (Java 22+): loaded by reflection, skipped on older JDKs
# (compiled against the main classes: the controller sources implement GamepadHost.Source).
$FfmSources = Join-Path $ProjectDir "build\ffm-sources.txt"
Write-ArgFile $FfmSources (Get-ChildItem (Join-Path $FfmDir "*.java")).FullName
$ffmOk = Invoke-Native $Javac @("--release", "22", "-encoding", "UTF-8", "-cp", $ClassesDir, "-d", $ClassesDir, "@$FfmSources") -Quiet -NoThrow
if (-not $ffmOk) { Write-Host "FFM helpers (src/ffm/) skipped: javac does not support --release 22" }

# The user interface languages (International): the lang\*.po catalogs go into the jar as lang/<tag>.po
$LangDir = Join-Path $ClassesDir "lang"
New-Item -ItemType Directory -Force -Path $LangDir | Out-Null
Copy-Item (Join-Path $ProjectDir "lang\*.po") $LangDir

# Enable-Native-Access lets the FFM helpers call libc without the "restricted method" warning (Java 22+ honours it in the manifest).
# Add-Exports lets MidiHost68k open the JDK synthesizer (com.sun.media.sound) with a short latency for MIDI played live.
$Manifest = Join-Path $ProjectDir "build\manifest.txt"
[System.IO.File]::WriteAllText($Manifest, "Enable-Native-Access: ALL-UNNAMED`nAdd-Exports: java.desktop/com.sun.media.sound`n")
Invoke-Native $Jar @("--create", "--file", $JarFile, "--main-class", "HyperZebra", "--manifest", $Manifest, "-C", $ClassesDir, ".")

Write-Host "Built $JarFile"
