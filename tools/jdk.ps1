# build.ps1 / run.ps1 / test.ps1 が読み込む共通部分: JDKの場所と、ネイティブコマンドの呼び出し。

# JDKの場所: JAVA_HOME、なければ PATH の javac
$JavaHome = $env:JAVA_HOME
if ([string]::IsNullOrEmpty($JavaHome)) {
	$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
	if (-not $javacCmd) { throw "javac not found. Install a JDK (17+) and set JAVA_HOME." }
	$JavaHome = Split-Path -Parent (Split-Path -Parent $javacCmd.Source)
}
$Javac = Join-Path $JavaHome "bin\javac.exe"
$Jar = Join-Path $JavaHome "bin\jar.exe"
$Java = Join-Path $JavaHome "bin\java.exe"

# javac の @引数ファイル: 1行に1つ、引用符で囲む (バックスラッシュは \\ にする)。UTF-8 (BOMなし) で書く。
function Write-ArgFile([string]$Path, [string[]]$Items) {
	$lines = $Items | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }
	[System.IO.File]::WriteAllLines($Path, [string[]]$lines, (New-Object System.Text.UTF8Encoding $false))
}

# ネイティブコマンドを実行する。Windows PowerShell 5.1 では stderr への出力 (javac の「ノート:」など) が
# エラー扱いになるので、終了コードだけで成否を見る。
function Invoke-Native([string]$Exe, [string[]]$Arguments, [switch]$Quiet, [switch]$NoThrow) {
	$saved = $ErrorActionPreference
	$ErrorActionPreference = "Continue"
	try {
		if ($Quiet) { & $Exe @Arguments 2>&1 | Out-Null }
		else { & $Exe @Arguments 2>&1 | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { [Console]::Error.WriteLine($_.ToString()) } else { $_ } } }
		$code = $LASTEXITCODE
	} finally { $ErrorActionPreference = $saved }
	if ($code -ne 0) {
		if ($NoThrow) { return $false }
		throw "$Exe exited with $code"
	}
	if ($NoThrow) { return $true }
}
