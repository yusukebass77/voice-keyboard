# voice-kb-record.ps1 - record audio, send to proxy, paste result
# Invoked via AutoHotKey hotkey (Ctrl+Alt+V)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path $scriptDir "config.json"
$logPath = Join-Path $scriptDir "voice-kb-pc.log"
$tempWav = Join-Path $env:TEMP "voice-kb-pc.wav"

function Write-Log($msg) {
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg" | Out-File -FilePath $logPath -Append -Encoding utf8
}

Add-Type -AssemblyName System.Windows.Forms

if (-not (Test-Path $configPath)) {
    Write-Log "ERROR: config.json not found at $configPath. Run setup.ps1 first."
    [System.Windows.Forms.MessageBox]::Show("config.json not found. Run setup.ps1 first.", "voice-kb-pc")
    exit 1
}

$config = Get-Content $configPath -Raw | ConvertFrom-Json
$micDevice = $config.mic_device
$proxyUrl = $config.proxy_url
$apiKey = $config.api_key

if (-not $micDevice -or -not $proxyUrl) {
    Write-Log "ERROR: config.json missing mic_device or proxy_url"
    exit 1
}

Write-Log "Recording start: device='$micDevice'"

# Tray balloon notification
$tray = New-Object System.Windows.Forms.NotifyIcon
$tray.Icon = [System.Drawing.SystemIcons]::Information
$tray.Visible = $true
$tray.ShowBalloonTip(1500, "Assistant recording", "ESC to stop, 60s auto-stop", [System.Windows.Forms.ToolTipIcon]::Info)

# Start ffmpeg recording (max 60s, 16kHz mono).
# Use ProcessStartInfo + StandardInput so we can send "q" to ffmpeg to make it
# finalize the WAV (re-write header) cleanly. If we Stop-Process -Force instead,
# ffmpeg is killed before flushing → output WAV ends up 0 bytes and we falsely
# report "Recording too short".
# Quote the device name (it contains spaces, parens, ®) so Win32 CommandLineToArgvW
# keeps it as one token.
$ffmpegLog = Join-Path $env:TEMP "voice-kb-pc-ffmpeg.log"
$ffmpegArgString = '-y -f dshow -i "audio={0}" -t 60 -ar 16000 -ac 1 "{1}"' -f $micDevice, $tempWav

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "ffmpeg"
$psi.Arguments = $ffmpegArgString
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true

$ffmpegProc = New-Object System.Diagnostics.Process
$ffmpegProc.StartInfo = $psi
# Buffer stderr asynchronously so the pipe doesn't fill up and block ffmpeg
$stderrBuf = New-Object System.Text.StringBuilder
$stderrAction = {
    if ($EventArgs.Data) { $Event.MessageData.AppendLine($EventArgs.Data) | Out-Null }
}
Register-ObjectEvent -InputObject $ffmpegProc -EventName ErrorDataReceived -Action $stderrAction -MessageData $stderrBuf | Out-Null
$ffmpegProc.Start() | Out-Null
$ffmpegProc.BeginErrorReadLine()

# ESC key polling
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Keyboard {
    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int vKey);
}
"@

$startTime = Get-Date
while (-not $ffmpegProc.HasExited) {
    if (([Keyboard]::GetAsyncKeyState(0x1B) -band 0x8000) -ne 0) {
        Write-Log "ESC pressed, sending 'q' to ffmpeg for clean stop"
        try {
            $ffmpegProc.StandardInput.WriteLine("q")
            $ffmpegProc.StandardInput.Flush()
        } catch {
            Write-Log "WARN: failed to send 'q', falling back to kill: $_"
            Stop-Process -Id $ffmpegProc.Id -Force
        }
        $ffmpegProc.WaitForExit(3000) | Out-Null
        if (-not $ffmpegProc.HasExited) {
            Write-Log "ffmpeg did not exit after 3s, force kill"
            Stop-Process -Id $ffmpegProc.Id -Force
        }
        break
    }
    if (((Get-Date) - $startTime).TotalSeconds -gt 61) {
        Write-Log "Hard timeout 61s, sending 'q'"
        try { $ffmpegProc.StandardInput.WriteLine("q"); $ffmpegProc.StandardInput.Flush() } catch {}
        $ffmpegProc.WaitForExit(3000) | Out-Null
        if (-not $ffmpegProc.HasExited) { Stop-Process -Id $ffmpegProc.Id -Force }
        break
    }
    Start-Sleep -Milliseconds 50
}

# Persist stderr to log for diagnostics
try {
    $stderrBuf.ToString() | Out-File -FilePath $ffmpegLog -Encoding utf8 -Force
} catch {}

$tray.Dispose()
$wavSize = (Get-Item $tempWav -ErrorAction SilentlyContinue).Length
Write-Log "Recording finished, size=$wavSize bytes"

if (-not (Test-Path $tempWav) -or $wavSize -lt 1000) {
    Write-Log "ERROR: Recording too short or missing"
    [System.Windows.Forms.MessageBox]::Show("Recording failed. Check microphone.", "voice-kb-pc")
    exit 1
}

# Send to proxy.
# Use curl.exe (bundled with Windows 10/11) because Windows PowerShell 5.1's
# Invoke-RestMethod has no -Form parameter (that arrived in PowerShell 7).
# Proxy returns plain UTF-8 text (PlainTextResponse), NOT JSON. PowerShell's
# default Console.OutputEncoding on JP Windows is CP932, so curl's stdout
# bytes get mojibake'd before we ever see them. Force UTF-8 for this read.
Write-Log "Sending to proxy: $proxyUrl"
$prevConsoleEnc = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
    $curlArgs = New-Object System.Collections.Generic.List[string]
    $curlArgs.Add("-s"); $curlArgs.Add("--max-time"); $curlArgs.Add("30")
    $curlArgs.Add("-X"); $curlArgs.Add("POST")
    $curlArgs.Add("-F"); $curlArgs.Add("file=@`"$tempWav`"")
    $curlArgs.Add("-F"); $curlArgs.Add("language=ja")
    $curlArgs.Add("-F"); $curlArgs.Add("style=auto")
    if ($apiKey) {
        $curlArgs.Add("-H"); $curlArgs.Add("Authorization: Bearer $apiKey")
    }
    $curlArgs.Add("$proxyUrl/v1/audio/transcriptions")

    $rawText = & curl.exe @curlArgs 2>&1 | Out-String
    Write-Log "Raw proxy response: $rawText"
    $text = $rawText.Trim()
    Write-Log "Got response: '$text'"
} catch {
    Write-Log "ERROR sending to proxy: $_"
    [System.Windows.Forms.MessageBox]::Show("Proxy send failed: $_", "voice-kb-pc")
    exit 1
} finally {
    [Console]::OutputEncoding = $prevConsoleEnc
}

if (-not $text) {
    Write-Log "Empty response, nothing to paste"
    exit 0
}

# Copy to clipboard, then Ctrl+V
Set-Clipboard -Value $text
Start-Sleep -Milliseconds 80
[System.Windows.Forms.SendKeys]::SendWait("^v")
Write-Log "Pasted successfully"
