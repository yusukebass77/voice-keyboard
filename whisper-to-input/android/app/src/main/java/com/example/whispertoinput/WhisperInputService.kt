/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.whispertoinput.keyboard.RomajiKanaConverter
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = AUDIO_MEDIA_TYPE_M4A
    private var useOggFormat: Boolean = false
    private var isFirstTime: Boolean = true

    // Phase 5 (2026-05-07): post-edit feedback bookkeeping.
    //   currentRequestId / currentFinalText snapshot the last successful
    //   transcription. When the user later "submits" (自前Send button or
    //   onFinishInput), we read the visible text out of the IME's
    //   InputConnection and post a /feedback row pairing the original
    //   raw text with what the user actually committed.
    //
    //   Cleared on send so a single transcription can only emit one
    //   feedback row — repeated sends without a new transcription are
    //   no-ops (avoids duplicate rows when a multi-paragraph reply is
    //   sent across several Enter presses).
    private var currentRequestId: String? = null
    private var currentFinalText: String? = null
    // Cached cursor position from the last onUpdateSelection callback.
    // -1 means "unknown" (some host apps don't report selections). Reserved
    // for future debug logging — currently we re-read the visible text at
    // submit time rather than relying on a tracked cursor offset.
    @Suppress("unused") private var lastSelectionStart: Int = -1
    @Suppress("unused") private var lastSelectionEnd: Int = -1

    // ─── Phase 6 #1 (2026-05-07): conversation-window state ──────────
    // When assistant conversation mode is ON, transcribed user input + the
    // proxy's reply are appended to a private chat log instead of being
    // committed to the host app. The user explicitly chooses whether to
    // surface アシスタントの最後の応答 to the host. Privacy-friendly and avoids
    // polluting Discord/LINE history with assistant chatter.
    private data class ConvMsg(val role: String, val text: String)
    private val convLog: MutableList<ConvMsg> = mutableListOf()
    /** Last user-spoken phrase, set on transcription complete and reset on
     *  conv-window close. Held so we can pair the next assistant reply
     *  with its prompt in the bubble UI. */
    private var pendingUserInput: String? = null

    // ─── Phase 6 #2 / Phase 8 (2026-05-08): kana-mode + kanji conversion ─
    // The IME owns the romaji buffer (not the keyboard view) because we
    // need access to the InputConnection's setComposingText/commitText.
    //
    // Phase 8 redesign — *everything* the user is mid-composing lives as
    // composing text (underlined in the host app) until an explicit
    // boundary commits it: space, send, enter, kana-mode-off, or a new
    // word boundary. This matches Mozc / Google日本語入力 behaviour.
    //
    // State invariants (only one of two modes at any time):
    //   ● raw mode (kanjiCandidateIndex == -1): composing text shows
    //     `kanaComposingBuffer + romajiBuffer`. The user is still typing
    //     hiragana. 変換 fires the conversion request.
    //   ● converting mode (kanjiCandidateIndex >= 0): composing text shows
    //     `kanjiCandidates[kanjiCandidateIndex]`. 変換 cycles candidates,
    //     ⌫ reverts to the original hiragana, typing a new letter
    //     finalizes the current candidate and starts a fresh raw buffer.
    private var kanaModeOn: Boolean = false
    private var romajiBuffer: String = ""
    private var kanaComposingBuffer: String = ""
    private var kanjiCandidates: List<String> = emptyList()
    private var kanjiCandidateIndex: Int = -1
    private var originalHiraganaForConversion: String = ""
    private var kanjiInFlight: Boolean = false

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            // Phase 6 #1: when assistant conversation mode is ON, route the
            // transcription into the conversation window instead of
            // committing to the host app. The user must explicitly tap
            // 最終応答送信 to surface text to the target app.
            if (whisperKeyboard.isConvWindowVisible()) {
                // The "user input" was the previous transcription; the
                // proxy's reply (current text) is アシスタントからの返答.
                // We don't actually have the user's pre-refine text here —
                // the proxy returns only the assistant reply when in
                // conversation mode. Best-effort: add a single bubble
                // representing the assistant turn. The user-side bubble was
                // appended at recording start (see appendUserBubblePlaceholder).
                convLog.add(ConvMsg("assistant", text))
                whisperKeyboard.appendConvMessage("assistant", text)
                whisperKeyboard.setConvStatus(getString(R.string.conv_status_idle))
                // Clear feedback bookkeeping — feedback rows are not useful
                // from inside the conv window since nothing was committed.
                currentRequestId = null
                currentFinalText = null
            } else {
                currentInputConnection?.commitText(text, 1)
                // Phase 8 (2026-05-08): voice transcription path commits via
                // commitText(), which clears the system-side composing region
                // but NOT our local kanaComposingBuffer / romajiBuffer / kanji
                // candidate state. Reset those so the next backspace tap doesn't
                // resurface a stale composition (the "ぶんたい" reappear bug).
                clearKanaState()
                // Phase 5: snapshot the request_id + final text the proxy returned
                // so a later submit can pair them in /feedback. Both come from
                // the transcriber's @Volatile fields, populated inside the same
                // makeWhisperRequest call that produced [text]. They may be
                // null if the user is bypassing the proxy (direct OpenAI/NIM),
                // in which case feedback is skipped.
                currentRequestId = whisperTranscriber.lastRequestId
                currentFinalText = whisperTranscriber.lastFinalText

                // Check if auto-switch-back is enabled and switch if so
                CoroutineScope(Dispatchers.Main).launch {
                    val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                        preferences[AUTO_SWITCH_BACK] ?: false
                    }.first()
                    if (autoSwitchBack) {
                        onSwitchIme()
                    }
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    private suspend fun updateAudioFormat() {
        val backend = dataStore.data.map { preferences: Preferences ->
            preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
        }.first()
        
        useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        if (useOggFormat) {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}"
            audioMediaType = AUDIO_MEDIA_TYPE_OGG
        } else {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}"
            audioMediaType = AUDIO_MEDIA_TYPE_M4A
        }
    }

    override fun onCreateInputView(): View {
        // Initialize members with regard to this context
        recorderManager = RecorderManager(this)

        // Preload conversion table
        ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
        ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)

        // Initialize audio format based on backend setting
        CoroutineScope(Dispatchers.Main).launch {
            updateAudioFormat()
        }

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager
        recorderManager!!.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }

        // Returns the keyboard after setting it up and inflating its layout
        val view = whisperKeyboard.setup(layoutInflater,
            shouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { onAtSymbol() },
            { onNewline() },
            // Phase 8 — "?" insert callback
            { onQuestionMark() },
            { digit -> onDigit(digit) },
            { onCursorLeft() },
            { onCursorRight() },
            { onSend() },
            { style -> onAssistantStyleChange(style) },
            { convMode -> onAssistantConvModeChange(convMode) },
            { onClearChunk() },
            { onClearAll() },
            { onShowImePicker() },
            // Phase 6 #1 — conversation-window callbacks
            { onConvSendFinal() },
            { onConvDiscard() },
            { onConvClose() },
            // Phase 6 #2 — kana-mode callbacks
            { letter -> onKanaLetter(letter) },
            { onKanaSpace() },
            { onKanaBackspace() },
            { on -> onKanaModeChange(on) },
            // Phase 7 — kanji 変換 callback
            { onKanaConvert() },
            // Phase 8 — 確定 callback: commit conversion buffer (no send)
            { finalizeKanaComposing() },
            // Phase 8 — カナ callback: convert composing hiragana → katakana
            { onKanaToKatakana() },
            { shouldShowRetry() },
        )

        // Seed Assistant button state from persisted preferences.
        CoroutineScope(Dispatchers.Main).launch {
            val (style, convMode) = dataStore.data.map { prefs: Preferences ->
                Pair(prefs[ASSISTANT_STYLE] ?: "raw", prefs[ASSISTANT_CONV_MODE] ?: false)
            }.first()
            whisperKeyboard.setAssistantState(style, convMode)
            // Phase 6 #1: if the user previously had conv-mode ON, surface
            // the conversation window immediately on IME open. The history
            // is in-memory only (cleared on previous IME stop), so the
            // panel starts blank.
            if (convMode) {
                whisperKeyboard.showConvWindow()
                whisperKeyboard.setConvStatus(getString(R.string.conv_status_idle))
            }
        }

        return view
    }

    /** Persist the new Assistant style selection (fire-and-forget from the UI). */
    private fun onAssistantStyleChange(style: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { it[ASSISTANT_STYLE] = style }
        }
    }

    /** Persist the new Assistant conversation-mode selection.
     *  Phase 6 #1: also opens/closes the conversation window so the user
     *  immediately sees the new mode reflected in the UI. */
    private fun onAssistantConvModeChange(convMode: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { it[ASSISTANT_CONV_MODE] = convMode }
        }
        if (convMode) {
            whisperKeyboard.showConvWindow()
            whisperKeyboard.setConvStatus(getString(R.string.conv_status_idle))
        } else {
            whisperKeyboard.hideConvWindow()
            convLog.clear()
            whisperKeyboard.clearConvHistory()
        }
    }

    // ─── Phase 6 #1: conversation window button handlers ──────────────

    /** 最終応答送信: commit the most recent assistant reply (only) to the host app. */
    private fun onConvSendFinal() {
        val lastAssistant = convLog.lastOrNull { it.role == "assistant" } ?: return
        currentInputConnection?.commitText(lastAssistant.text, 1)
        // After committing, clear the log + close the window so the user
        // returns to a clean state. They can re-toggle conv mode to start
        // a new exchange.
        convLog.clear()
        whisperKeyboard.clearConvHistory()
        whisperKeyboard.hideConvWindow()
        // Also flip the assistant conv mode off so the next dictation goes
        // straight to the host app (matches the "I'm done with assistant"
        // user mental model).
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { it[ASSISTANT_CONV_MODE] = false }
        }
        whisperKeyboard.setAssistantState(
            currentAssistantStyleSnapshot(),
            false,
        )
    }

    /** 丸ごと破棄: clear the conversation log without committing anything. */
    private fun onConvDiscard() {
        convLog.clear()
        whisperKeyboard.clearConvHistory()
        whisperKeyboard.setConvStatus(getString(R.string.conv_status_idle))
    }

    /** 対話終了: close the window and turn off conv mode. No commit. */
    private fun onConvClose() {
        convLog.clear()
        whisperKeyboard.clearConvHistory()
        whisperKeyboard.hideConvWindow()
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { it[ASSISTANT_CONV_MODE] = false }
        }
        whisperKeyboard.setAssistantState(currentAssistantStyleSnapshot(), false)
    }

    /** Read-only helper: snapshot current style so we can rebroadcast it
     *  with a new conv-mode flag without losing the style selection. */
    private fun currentAssistantStyleSnapshot(): String {
        // Best-effort: read DataStore synchronously is non-trivial in
        // suspend-only API. We accept "raw" as the safe default — the
        // keyboard view will refresh from DataStore on the next
        // onCreateInputView anyway.
        return "raw"
    }

    // ─── Phase 6 #2: kana-mode handlers ──────────────────────────────

    private fun onKanaModeChange(on: Boolean) {
        kanaModeOn = on
        if (!on) {
            // Mode off is a hard boundary — finalize anything in flight so
            // the user doesn't lose what they typed.
            finalizeKanaComposing()
        }
    }

    /** A QWERTY letter cell tap while かな mode is ON.
     *
     *  Phase 8: typing extends the composing text (underlined). If the
     *  user is currently looking at a kanji candidate (converting mode),
     *  typing more text is treated as acceptance — the candidate is
     *  committed and a fresh raw composing buffer starts with the new
     *  letter.
     */
    private fun onKanaLetter(letter: String) {
        // Accept the current candidate by typing past it.
        if (kanjiCandidateIndex >= 0) {
            finalizeKanaComposing()
        }
        romajiBuffer += letter
        val res = RomajiKanaConverter.convert(romajiBuffer)
        if (res.committed.isNotEmpty()) {
            kanaComposingBuffer += res.committed
        }
        romajiBuffer = res.remaining
        renderComposing()
    }

    /** Space while in かな mode — finalize any composing text first
     *  (whether kana or kanji candidate), then insert a literal space. */
    private fun onKanaSpace() {
        finalizeKanaComposing()
        currentInputConnection?.commitText(" ", 1)
    }

    /** Backspace while in かな mode.
     *
     *  Behavior depends on current state:
     *   ● converting mode → revert to the original hiragana so the user
     *     can re-think the conversion. No host-app delete happens.
     *   ● raw mode + romaji tail → drop one romaji char.
     *   ● raw mode + kana buffer only → drop one kana char.
     *   ● empty composing → host-app delete (existing behavior).
     */
    private fun onKanaBackspace() {
        // Revert kanji candidate to the original hiragana on backspace.
        if (kanjiCandidateIndex >= 0) {
            kanjiCandidates = emptyList()
            kanjiCandidateIndex = -1
            kanaComposingBuffer = originalHiraganaForConversion
            originalHiraganaForConversion = ""
            renderComposing()
            return
        }
        if (romajiBuffer.isNotEmpty()) {
            romajiBuffer = romajiBuffer.dropLast(1)
            renderComposing()
        } else if (kanaComposingBuffer.isNotEmpty()) {
            kanaComposingBuffer = kanaComposingBuffer.dropLast(1)
            renderComposing()
        } else {
            onDeleteText()
        }
    }

    /** Resolve any pending romaji tail into its kana form and append to
     *  the composing buffer. Called by 変換 / finalize paths so the
     *  conversion / commit sees the full kana, not a stranded "k". */
    private fun resolveKanaTail() {
        if (romajiBuffer.isEmpty()) return
        val flushed = RomajiKanaConverter.flush(romajiBuffer)
        if (flushed.isNotEmpty()) {
            kanaComposingBuffer += flushed
        }
        romajiBuffer = ""
    }

    /** Re-render the composing text in the host app from current state.
     *  Call after every state mutation in raw mode; converting mode
     *  paints separately because the candidate string is the composing. */
    private fun renderComposing() {
        val ic = currentInputConnection ?: return
        if (kanjiCandidateIndex >= 0 && kanjiCandidateIndex < kanjiCandidates.size) {
            ic.setComposingText(kanjiCandidates[kanjiCandidateIndex], 1)
            return
        }
        val composing = kanaComposingBuffer + romajiBuffer
        if (composing.isEmpty()) {
            ic.finishComposingText()
        } else {
            ic.setComposingText(composing, 1)
        }
    }

    /** Promote whatever's currently composing (kana, romaji-resolved-to-
     *  kana, or selected kanji candidate) to committed text and clear
     *  all composing state. Idempotent — safe to call multiple times. */
    private fun finalizeKanaComposing() {
        val ic = currentInputConnection
        if (ic == null) {
            kanaComposingBuffer = ""; romajiBuffer = ""
            kanjiCandidates = emptyList(); kanjiCandidateIndex = -1
            originalHiraganaForConversion = ""
            return
        }
        if (kanjiCandidateIndex >= 0) {
            // Composing already shows the chosen kanji candidate; just
            // commit it to the field.
            ic.finishComposingText()
        } else if (kanaComposingBuffer.isNotEmpty() || romajiBuffer.isNotEmpty()) {
            // Resolve pending romaji and ensure composing matches the
            // final kana before finishing.
            resolveKanaTail()
            if (kanaComposingBuffer.isNotEmpty()) {
                ic.setComposingText(kanaComposingBuffer, 1)
            }
            ic.finishComposingText()
        } else {
            ic.finishComposingText()
        }
        kanaComposingBuffer = ""
        romajiBuffer = ""
        kanjiCandidates = emptyList()
        kanjiCandidateIndex = -1
        originalHiraganaForConversion = ""
    }

    /** Legacy entry point retained for the assistant conversation flow,
     *  which calls flushRomajiBuffer() before sending text. In Phase 8
     *  this is just an alias for finalizeKanaComposing(). */
    private fun flushRomajiBuffer() {
        finalizeKanaComposing()
    }

    /** Phase 7/8 (2026-05-08): hiragana → kanji conversion with candidate
     *  cycling.
     *
     *  Behavior:
     *   ● First tap (raw mode): resolve any romaji tail, send the kana
     *     buffer to /kanji, paint the top candidate as composing text.
     *   ● Subsequent taps (converting mode): cycle to the next candidate
     *     in the list (wraps around the end).
     *   ● Empty composing → no-op.
     *   ● Already in flight → ignore second tap to prevent race.
     */
    private fun onKanaConvert() {
        if (kanjiInFlight) return
        // Cycle candidates if we're already showing one.
        if (kanjiCandidateIndex >= 0 && kanjiCandidates.isNotEmpty()) {
            kanjiCandidateIndex = (kanjiCandidateIndex + 1) % kanjiCandidates.size
            renderComposing()
            return
        }
        // First-time conversion — flush romaji tail, then send.
        resolveKanaTail()
        val toConvert = kanaComposingBuffer
        if (toConvert.isBlank()) return
        kanjiInFlight = true
        val ctx = applicationContext
        whisperTranscriber.convertHiragana(ctx, toConvert) { candidates ->
            kanjiInFlight = false
            if (candidates.isEmpty() ||
                (candidates.size == 1 && candidates[0] == toConvert)) {
                Toast.makeText(this, "変換候補なし", Toast.LENGTH_SHORT).show()
                return@convertHiragana
            }
            kanjiCandidates = candidates
            kanjiCandidateIndex = 0
            originalHiraganaForConversion = toConvert
            renderComposing()
        }
    }

    /** Phase 8 (2026-05-08) — clear all kana / romaji / kanji-candidate state.
     *  Used at hard boundaries (recording start, voice commit) to prevent a
     *  pre-existing buffer from resurfacing into the next session. Note: also
     *  calls finishComposingText() so any system-side composing region drops
     *  in lockstep. Safe to call when buffers are already empty (idempotent). */
    private fun clearKanaState() {
        kanaComposingBuffer = ""
        romajiBuffer = ""
        kanjiCandidates = emptyList()
        kanjiCandidateIndex = -1
        originalHiraganaForConversion = ""
        currentInputConnection?.finishComposingText()
    }

    /** Phase 8 (2026-05-08) — hiragana → katakana 変換.
     *  カナ button entrypoint. Behavior:
     *   ● If kanji candidates are showing, reset to the original hiragana
     *     first (so カナ converts the *user-entered* kana, not the kanji).
     *   ● Resolve any pending romaji tail to kana.
     *   ● Map each hiragana char to its katakana counterpart (Unicode +0x60).
     *   ● Re-render composing — user can then tap 確定 to commit.
     *  No remote call; conversion is deterministic and offline. */
    private fun onKanaToKatakana() {
        // If kanji candidate is on screen, snap back to the original hiragana
        // so we convert the user's intended kana, not the chosen kanji.
        if (kanjiCandidateIndex >= 0 && originalHiraganaForConversion.isNotEmpty()) {
            kanaComposingBuffer = originalHiraganaForConversion
            kanjiCandidates = emptyList()
            kanjiCandidateIndex = -1
            originalHiraganaForConversion = ""
        }
        resolveKanaTail()
        if (kanaComposingBuffer.isEmpty()) return
        val katakana = kanaComposingBuffer.map { c ->
            if (c in 'ぁ'..'ゖ') c + 0x60 else c
        }.joinToString("")
        if (katakana == kanaComposingBuffer) return  // already katakana, no-op
        kanaComposingBuffer = katakana
        renderComposing()
    }

    /** 全消去ボタン short-tap: delete a chunk (5 chars back) for fast rollback
     *  of the last inserted phrase (especially アシスタント's conversation reply). */
    private fun onClearChunk() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!TextUtils.isEmpty(selectedText)) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(5, 0)
        }
    }

    /** 全消去ボタン long-press: wipe the entire visible field.
     *  deleteSurroundingText(large, large) is the simplest portable way to
     *  clear — it reaches up to the input buffer boundary in either
     *  direction. Not all apps expose the full buffer, so on very long
     *  fields this may only clear what's visible; that's acceptable for
     *  the intended "erase assistant's last reply" use case. */
    private fun onClearAll() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(10000, 10000)
    }

    private fun onAtSymbol() {
        if (kanaModeOn) finalizeKanaComposing()
        currentInputConnection?.commitText("@", 1)
    }

    private fun onQuestionMark() {
        if (kanaModeOn) finalizeKanaComposing()
        currentInputConnection?.commitText("?", 1)
    }

    private fun onNewline() {
        if (kanaModeOn) finalizeKanaComposing()
        currentInputConnection?.commitText("\n", 1)
    }

    private fun onDigit(digit: String) {
        // Bug fix (2026-05-09): in kana mode, finalize composing first so the
        // hiragana being typed isn't dropped when a digit/symbol is tapped.
        // Additionally, in kana mode "-" maps to the long-vowel mark "ー" so
        // the user can finish words like "アシスタント" without leaving the pad.
        if (kanaModeOn) {
            finalizeKanaComposing()
            if (digit == "-") {
                currentInputConnection?.commitText("ー", 1)
                return
            }
        }
        currentInputConnection?.commitText(digit, 1)
    }

    private fun onCursorLeft() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    private fun onCursorRight() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    private fun onSend() {
        // Phase 8: finalize any in-flight composing (kana or kanji
        // candidate) before submitting so the user's text isn't dropped.
        finalizeKanaComposing()
        // Phase 5: capture the user-edited text BEFORE submission, then fire
        // /feedback fire-and-forget. The actual submit (performEditorAction)
        // happens immediately afterwards — feedback POST runs async on IO,
        // so this does not introduce visible latency.
        flushFeedbackIfPending()

        // Force IME_ACTION_SEND regardless of the field's declared action.
        // Falls back to KEYCODE_ENTER if the field doesn't accept the editor action.
        val ic = currentInputConnection ?: return
        val accepted = ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        if (!accepted) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /**
     * Phase 5: read the entire visible text out of the current input
     * connection. Composes getTextBeforeCursor + selection + getTextAfterCursor
     * with a generous 10000-char window — enough for any realistic chat
     * field, and Android caps internally if the buffer is shorter.
     *
     * Returns null if no input connection is attached (keyboard hidden).
     */
    private fun readVisibleInputText(): String? {
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(10000, 0) ?: ""
        val selected = ic.getSelectedText(0) ?: ""
        val after = ic.getTextAfterCursor(10000, 0) ?: ""
        return "$before$selected$after"
    }

    /**
     * Phase 5: if a pending request_id is on file, snapshot the visible
     * input text and POST a /feedback row pairing the original final text
     * with what the user is about to submit. Best-effort — clears the
     * pending state regardless of success so we never double-post for the
     * same transcription.
     *
     * Called from:
     *   - onSend() (自前Sendボタン)
     *   - onFinishInput() (focus moves away from the field)
     */
    private fun flushFeedbackIfPending() {
        val rid = currentRequestId
        val fin = currentFinalText
        if (rid.isNullOrBlank() || fin == null) return

        val submitted = readVisibleInputText() ?: return
        // Clear pending state immediately — even if the POST fails we don't
        // want to retry on the next submit (the second submit's "current
        // text" would no longer correspond to this request_id).
        currentRequestId = null
        currentFinalText = null

        // user is read fresh from DataStore so changes in the settings UI
        // take effect on the next submit without restarting the IME.
        CoroutineScope(Dispatchers.IO).launch {
            val user = try {
                dataStore.data.map { prefs: Preferences ->
                    prefs[USER_NAME] ?: ""
                }.first()
            } catch (_: Exception) { "" }
            whisperTranscriber.sendFeedback(
                this@WhisperInputService,
                rid,
                fin,
                submitted,
                user,
            )
        }
    }

    private fun onStartRecording() {
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        // Phase 8 (2026-05-08): clear any stale kana / romaji / kanji-candidate
        // state before voice recording begins. Without this, a previously
        // typed-but-uncommitted kana buffer (e.g. user typed "buntai" via
        // QWERTY then switched to voice without 確定) survives the voice
        // session and re-emerges as composing text on the next backspace.
        clearKanaState()

        recorderManager!!.start(this, recordedAudioFilename, useOggFormat)
        // Phase 6 #1: surface "録音中…" inside the conv window so the user
        // gets visual feedback even when target app isn't being typed into.
        if (whisperKeyboard.isConvWindowVisible()) {
            whisperKeyboard.setConvStatus(getString(R.string.conv_status_recording))
        }
    }

    // when mic amplitude is updated, notify the keyboard
    // this callback is registered to the recorder manager
    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        whisperKeyboard.updateMicrophoneAmplitude(amplitude)
    }

    private fun onCancelRecording() {
        recorderManager!!.stop()
    }

    private fun onStartTranscription(attachToEnd: String) {
        recorderManager!!.stop()
        // Phase 6 #1: switch the conv-window status label to "考え中…" so
        // the user knows we're waiting on the proxy. The reply will land
        // in transcriptionCallback() and append a assistant bubble.
        if (whisperKeyboard.isConvWindowVisible()) {
            whisperKeyboard.setConvStatus(getString(R.string.conv_status_thinking))
        }
        // Phase 4 (2026-05-10): forward the foreground app's packageName so the
        // proxy can resolve style="auto" → per-app default. currentInputEditorInfo
        // is the standard IME source for which app is requesting input.
        val foregroundPkg = currentInputEditorInfo?.packageName ?: ""
        whisperTranscriber.startAsync(this,
            recordedAudioFilename,
            audioMediaType,
            attachToEnd,
            foregroundPkg,
            { transcriptionCallback(it) },
            { transcriptionExceptionCallback(it) })
    }

    private fun onCancelTranscription() {
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    /**
     * Phase 5b (2026-05-07): show the system IME picker so the user can
     * pick Gboard (or any other installed IME) — used for Japanese input
     * since this app's QWERTY pad commits raw English characters and has
     * no rōmaji→kana conversion of its own. The picker is the same dialog
     * Android shows when the user long-presses the system's "switch
     * keyboard" button, so it works on all API levels we care about and
     * doesn't depend on previous-IME state.
     */
    private fun onShowImePicker() {
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showInputMethodPicker()
    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        // Phase 8: in kana mode, finalize composing so the underlined text
        // becomes part of the field before the Enter action fires.
        if (kanaModeOn) finalizeKanaComposing()
        val inputConnection = currentInputConnection ?: return
        // Decide newline-vs-submit based on the destination field's IME hints.
        // - Fields that explicitly disable the Enter action (IME_FLAG_NO_ENTER_ACTION) or
        //   don't set one (IME_ACTION_NONE / UNSPECIFIED) are multi-line — insert a literal
        //   newline so the user can compose multi-paragraph text.
        // - Fields with a real action (Send, Done, Search, …) get a proper DOWN+UP key
        //   stroke so the app triggers its action naturally. (Single DOWN was silently
        //   dropped by modern editors before, which is why Enter appeared inert.)
        // Note: Discord's compose field sets IME_ACTION_SEND by default, so Enter will
        // submit there — use the explicit "改行" button (Phase 2) to insert a newline.
        val imeOptions = currentInputEditorInfo?.imeOptions ?: 0
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val hasRealAction = !noEnterAction &&
                action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED

        if (hasRealAction) {
            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        } else {
            inputConnection.commitText("\n", 1)
        }
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    /**
     * Phase 5 (2026-05-07): track cursor movements so we have a fresh
     * cursor position when /feedback fires. We don't actually use the
     * value to decide *what* to send (readVisibleInputText() is run at
     * submit time) — this is just so debug logging / future enhancements
     * have access to the most recent selection.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd,
        )
        lastSelectionStart = newSelStart
        lastSelectionEnd = newSelEnd
    }

    /**
     * Phase 5: when the user navigates away from the field (taps outside,
     * hits "Done", switches IME, etc.) flush any pending feedback row.
     * This is the safety net for cases where the user finalizes the text
     * via the host app's own send button rather than our 自前Send.
     */
    override fun onFinishInput() {
        try {
            // Phase 6 #2: flush kana buffer so it doesn't persist into the
            // next field — users have been surprised by orphan romaji.
            flushRomajiBuffer()
            flushFeedbackIfPending()
        } finally {
            super.onFinishInput()
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to.
        // Automatically starts recording after switching to Whisper Input. (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            // Update audio format based on current backend setting
            updateAudioFormat()
            if (!isFirstTime) return@launch
            isFirstTime = false
            val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_RECORDING_START] ?: true
            }.first()
            if (isAutoStartRecording) {
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }
}
