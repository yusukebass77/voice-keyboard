# Contributing

Thanks for your interest in Voice Keyboard. This is an actively maintained
project — issues and pull requests are welcome.

## Ground rules

- **Open an issue first** for anything non-trivial, so we can agree on the
  approach before you spend time on a PR.
- Keep the **refine prompt** changes backed by examples: if you change tone /
  normalization behavior, add an input → expected-output pair to the discussion
  so the change is reviewable.
- The **personal dictionary** (`dictionary/`) is intentionally git-ignored.
  Never commit real names, addresses, or other personal data — use generic
  placeholders in examples.
- Secrets come from **environment variables only**. Nothing key-shaped should
  ever land in a commit.

## Areas that need help

- Refine-prompt regression fixtures (input → expected output) so changes can be
  tested automatically.
- Additional STT backends and language coverage beyond Japanese.
- iOS / desktop-Linux clients (currently Android + Windows).
- Latency and cost tuning for the refine layer.

## Development

- Proxy: `proxy/` — FastAPI, Python 3.11+. `pip install -r requirements.txt`.
- Android IME: `whisper-to-input/android/` — standard Gradle project.
- Windows client: `pc/` — AutoHotkey v2 + PowerShell.

## License

By contributing you agree your contributions are licensed under **GPL-3.0**, the
same license as the project.
