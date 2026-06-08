# setup.ps1 - Voice Keyboard PC client setup
# Run from "PowerShell as Administrator"

# Note: keep ErrorActionPreference = Continue, otherwise FFmpeg's stderr banner
# (it dumps version info to stderr even on success) is interpreted as a fatal
# error and stops the script before microphone enumeration completes.
$ErrorActionPreference = "Continue"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path $scriptDir "config.json"

Write-Host "===== Assistant Voice Keyboard PC Setup =====" -ForegroundColor Cyan
Write-Host ""

# 1. Check dependencies
Write-Host "[1/4] Checking dependencies..." -ForegroundColor Yellow

function Test-Command($cmd) {
    return [bool](Get-Command $cmd -ErrorAction SilentlyContinue)
}

if (-not (Test-Command "ffmpeg")) {
    Write-Host "  - FFmpeg not found, installing via winget..."
    winget install -e --id Gyan.FFmpeg --silent --accept-source-agreements --accept-package-agreements
    Write-Host "  - NOTE: open a NEW PowerShell window after this script if FFmpeg PATH is not refreshed" -ForegroundColor Magenta
} else {
    Write-Host "  OK: FFmpeg installed"
}

$ahkExe = Join-Path $env:ProgramFiles "AutoHotkey\v2\AutoHotkey64.exe"
if (-not (Test-Path $ahkExe)) {
    Write-Host "  - AutoHotKey v2 not found, installing via winget..."
    winget install -e --id AutoHotkey.AutoHotkey --silent --accept-source-agreements --accept-package-agreements
} else {
    Write-Host "  OK: AutoHotKey v2 installed"
}

# 2. List microphone devices
Write-Host ""
Write-Host "[2/4] Listing microphone devices (via FFmpeg dshow)..." -ForegroundColor Yellow
# Capture ffmpeg's stderr/stdout to a UTF-8 file then read it back as UTF-8.
# Going via a file (instead of pipe) avoids the PowerShell console encoding
# guessing game entirely - modern ffmpeg writes UTF-8 by default on Windows.
$tmpListFile = Join-Path $env:TEMP "voice-kb-pc-mic-list.txt"
cmd.exe /c "ffmpeg -list_devices true -f dshow -i dummy 1> `"$tmpListFile`" 2>&1" | Out-Null
$ffmpegList = Get-Content -Path $tmpListFile -Encoding UTF8 -Raw -ErrorAction SilentlyContinue
Remove-Item $tmpListFile -ErrorAction SilentlyContinue
$audioLines = $ffmpegList -split "`r?`n" | Where-Object { $_ -match '\(audio\)' }

$devices = @()
foreach ($line in $audioLines) {
    if ($line -match '"([^"]+)"\s*\(audio\)') {
        $devices += $matches[1]
    }
}

if ($devices.Count -eq 0) {
    Write-Host "  WARNING: No microphone devices detected. Check FFmpeg PATH." -ForegroundColor Red
    Write-Host "  (If you just installed FFmpeg, open a NEW PowerShell window and re-run.)"
    exit 1
}

Write-Host "  Detected microphones:"
for ($i = 0; $i -lt $devices.Count; $i++) {
    Write-Host "    [$i] $($devices[$i])"
}
Write-Host ""
$selectedIdx = Read-Host "Select microphone number (0..$($devices.Count - 1))"
$micDevice = $devices[[int]$selectedIdx]
Write-Host "  OK: $micDevice"

# 3. Generate config.json
Write-Host ""
Write-Host "[3/4] Generating config.json..." -ForegroundColor Yellow
$defaultProxy = "http://YOUR_PROXY_HOST:9090"
$proxyUrl = Read-Host "Enter proxy URL (Enter for $defaultProxy)"
if (-not $proxyUrl) { $proxyUrl = $defaultProxy }
$apiKey = Read-Host "Enter API key (PROXY_SHARED_SECRET, Enter to skip)"

$config = @{
    mic_device = $micDevice
    proxy_url = $proxyUrl
    api_key = $apiKey
}
$config | ConvertTo-Json -Depth 3 | Out-File -FilePath $configPath -Encoding utf8
Write-Host "  OK: $configPath"

# 4. Start ahk
Write-Host ""
Write-Host "[4/4] Launching AHK script..." -ForegroundColor Yellow
$ahkScript = Join-Path $scriptDir "voice-kb-pc.ahk"
if (Test-Path $ahkExe) {
    Start-Process -FilePath $ahkExe -ArgumentList ('"{0}"' -f $ahkScript)
    Write-Host "  OK: launched (look for tray icon)"
} else {
    Write-Host "  WARNING: AutoHotKey not found yet. After winget completes, double-click voice-kb-pc.ahk manually." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "===== Setup complete =====" -ForegroundColor Green
Write-Host ""
Write-Host "Usage:"
Write-Host "  - Focus any text field, press Ctrl+Alt+V, speak, ESC or 60s to stop"
Write-Host "  - Result is auto-pasted via clipboard + Ctrl+V"
Write-Host ""
Write-Host "Autostart at boot:"
Write-Host "  Open 'shell:startup' and place a shortcut to voice-kb-pc.ahk"
Write-Host ""
