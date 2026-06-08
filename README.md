# Voice Keyboard

A self-hosted voice-input toolchain: speak, get clean text. Audio is transcribed
(Whisper / OpenAI / Groq) and optionally refined by an LLM (Claude) into tidy,
ready-to-send Japanese while preserving your original tone.

## Components

| Dir | What it is |
|-----|------------|
| `proxy/` | FastAPI server. Receives audio, runs STT, optional LLM refine/conversation, returns text. All keys via environment variables. |
| `whisper-to-input/` | Android keyboard (IME) — a fork of [Whisper To Input](https://github.com/j3soon/whisper-to-input) with a custom keypad, refine button, and conversation mode. |
| `pc/` | Windows client (AutoHotkey + PowerShell) — hotkey to record and paste refined text. |

## Configuration

The proxy reads everything from environment variables — no secrets are committed:

| Var | Purpose |
|-----|---------|
| `PROXY_SHARED_SECRET` | required — bearer shared between clients and proxy |
| `OPENAI_API_KEY` | OpenAI STT + TTS |
| `GROQ_API_KEY` | Groq STT (fallback) |
| `ANTHROPIC_API_KEY` | Claude refine / conversation (optional) |
| `STT_PROVIDER` | `openai` (default) or `groq` |
| `BIND_HOST` / `BIND_PORT` | default `0.0.0.0:9090` |

```bash
cd proxy
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
PROXY_SHARED_SECRET=... OPENAI_API_KEY=... uvicorn main:app --host 0.0.0.0 --port 9090
```

## Personal dictionary

The refiner can take a proper-noun hint dictionary at `dictionary/proper_nouns.json`
to improve name/term recognition. **That directory is git-ignored** — it holds
personal names and terms, so each user supplies their own. The prompts fall back
gracefully when it is absent.

## License

The Android app under `whisper-to-input/` retains its upstream license (see
`whisper-to-input/LICENSE`). The proxy and PC client are provided as-is.
