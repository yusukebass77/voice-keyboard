# Voice Keyboard PC版

Windows PC で AutoHotKey ホットキーから音声入力して、Pi proxy 経由でテキスト化＆自動ペーストするツール。

## 構成

- `voice-kb-pc.ahk` — AutoHotKey v2 ホットキースクリプト（Ctrl+Alt+V で起動）
- `voice-kb-record.ps1` — 録音 → proxy送信 → クリップボード貼付
- `setup.ps1` — 初回セットアップ（依存導入、マイク選択、config生成）
- `config.json` — 生成される設定ファイル

## セットアップ手順

1. zip を任意のフォルダに展開（例: `C:\Tools\voice-kb-pc\`）
2. PowerShell を「**管理者として実行**」で開く
3. ```powershell
   cd C:\Tools\voice-kb-pc
   Set-ExecutionPolicy -Scope CurrentUser RemoteSigned   # 初回のみ
   .\setup.ps1
   ```
4. 案内に従って：
   - FFmpeg / AutoHotKey 自動導入
   - マイクデバイス選択（数字入力）
   - proxy URL（デフォルト `http://YOUR_PROXY_HOST:9090`）
   - API key（PROXY_SHARED_SECRET、Pi の `~/.config/voice-kb/secrets.env` を参照）

5. 完了後、タスクトレイにアイコンが出れば起動成功

## 使い方

- 任意のテキストフィールドにフォーカス
- **Ctrl+Alt+V** を押す
- マイクON、トレイ通知「アシスタント録音中」
- 喋る
- **ESC** または 60秒経過で録音停止
- proxy 経由で整文 → クリップボード → 自動 Ctrl+V でペースト

## 自動起動

PC起動時に常駐させる場合：
1. `shell:startup` フォルダを開く（Win+R → `shell:startup`）
2. `voice-kb-pc.ahk` のショートカットを置く

## トラブルシュート

- **マイクが検出されない**: FFmpeg導入直後はPATH更新のためPowerShellを開き直す
- **proxy応答なし**: proxyホストへの接続を確認、`curl http://<PROXY_HOST>:9090/health` で疎通
- **ペーストされない**: ログ `voice-kb-pc.log` を確認、空テキスト or 401（APIキー誤り）等
- **マイクデバイス名変更**: `config.json` の `mic_device` を直編集 or `setup.ps1` 再実行

## ログ

`voice-kb-pc.log` （スクリプトと同じフォルダ）に毎回追記。
