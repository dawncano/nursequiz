# Build + install + (re)enable accessibility service for the quiz auto-answer tool.
#
# Usage (run from project root in PowerShell):
#   .\build.ps1                 build Debug -> install -> re-enable accessibility service
#   .\build.ps1 -BuildOnly      build only, no install
#   .\build.ps1 -Logcat         after install, follow DumpTool logcat (Ctrl+C to stop)
#   .\build.ps1 -Serial xxxx    target device serial (default 564e42ec; falls back to first device)
#
# Uses the project's own gradlew.bat (so Android Studio can build/run this project too).
# ASCII-only on purpose: Windows PowerShell 5.1 mis-parses UTF-8-without-BOM scripts.

param(
    [string]$Serial = "564e42ec",
    [switch]$BuildOnly,
    [switch]$Logcat
)

$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot

# --- tool paths (edit if needed) ---
$javaHome = "D:\Program Files\Android\Android Studio\jbr"
$adb      = "D:\software\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }   # fall back to adb on PATH

$apk = Join-Path $proj "app\build\outputs\apk\debug\app-debug.apk"
$pkg = "com.quizhelper.dumptool"
$svc = "$pkg/$pkg.DumpAccessibilityService"

$env:JAVA_HOME = $javaHome

# --- 1) build ---
Write-Host "==> assembleDebug ..." -ForegroundColor Cyan
& (Join-Path $proj "gradlew.bat") -p $proj :app:assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host "!! build failed" -ForegroundColor Red; exit 1 }
Write-Host "OK build done: $apk" -ForegroundColor Green
if ($BuildOnly) { exit 0 }

# --- 2) pick device ---
$devices = @()
foreach ($line in (& $adb devices)) {
    if ($line -match '^(\S+)\s+device\s*$') { $devices += $matches[1] }
}
if ($devices -notcontains $Serial) {
    if ($devices.Count -ge 1) {
        $Serial = $devices[0]
        Write-Host "(default device not found, using $Serial)" -ForegroundColor Yellow
    } else {
        Write-Host "!! no device connected" -ForegroundColor Red; exit 1
    }
}

# --- 3) install ---
Write-Host "==> install to $Serial ..." -ForegroundColor Cyan
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { Write-Host "!! install failed" -ForegroundColor Red; exit 1 }

# --- 4) re-enable accessibility service (reinstall disables it) ---
Write-Host "==> re-enable accessibility service ..." -ForegroundColor Cyan
& $adb -s $Serial shell settings put secure enabled_accessibility_services $svc | Out-Null
& $adb -s $Serial shell settings put secure accessibility_enabled 1 | Out-Null
Write-Host "OK all done." -ForegroundColor Green

# --- 5) optional: follow logcat ---
if ($Logcat) {
    & $adb -s $Serial logcat -c
    Write-Host "==> following DumpTool logcat (Ctrl+C to stop) ..." -ForegroundColor Cyan
    & $adb -s $Serial logcat -v time -s "DumpTool:*"
}
