; voice-kb-pc.ahk — Voice Keyboard PC版 ホットキースクリプト (AutoHotKey v2)
;
; ホットキー:
;   Ctrl+Alt+V  → 録音開始（最大60秒、ESCで停止）
;
; インストール: AutoHotKey v2 が必要 (winget install AutoHotkey.AutoHotkey)
; 実行: 本スクリプトをダブルクリック or スタートアップに登録

#Requires AutoHotkey v2.0
#SingleInstance Force

scriptDir := A_ScriptDir
recordPs1 := scriptDir . "\voice-kb-record.ps1"

; Ctrl+Alt+V でアシスタント録音起動
^!v::
{
    global recordPs1
    Run('powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' . recordPs1 . '"', , "Hide")
}

; トレイメニューに「再読み込み」と「終了」を追加
A_TrayMenu.Delete()
A_TrayMenu.Add("アシスタント録音 (Ctrl+Alt+V)", (*) => Run('powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' . recordPs1 . '"', , "Hide"))
A_TrayMenu.Add()
A_TrayMenu.Add("ログを開く", (*) => Run('notepad.exe "' . A_ScriptDir . '\voice-kb-pc.log"'))
A_TrayMenu.Add("config.json編集", (*) => Run('notepad.exe "' . A_ScriptDir . '\config.json"'))
A_TrayMenu.Add()
A_TrayMenu.Add("再読み込み", (*) => Reload())
A_TrayMenu.Add("終了", (*) => ExitApp())
A_TrayMenu.Default := "アシスタント録音 (Ctrl+Alt+V)"
A_IconTip := "Voice Keyboard PC版 — Ctrl+Alt+V で録音"
