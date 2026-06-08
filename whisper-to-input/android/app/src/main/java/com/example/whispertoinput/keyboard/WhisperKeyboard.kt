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

package com.example.whispertoinput.keyboard

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.math.MathUtils
import com.example.whispertoinput.R
import kotlin.math.log10
import kotlin.math.pow

private fun View.hapticTap() {
    // LONG_PRESS is noticeably stronger than KEYBOARD_TAP. User requested a firmer tick
    // than the default soft-keyboard feel. LONG_PRESS has been available since API 3, so
    // it works on every device we target.
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

private const val AMPLITUDE_CLAMP_MIN: Int = 10
private const val AMPLITUDE_CLAMP_MAX: Int = 25000
private const val LOG_10_10: Float = 1.0F
private const val LOG_10_25000: Float = 4.398F
private const val AMPLITUDE_ANIMATION_DURATION: Long = 500
private val amplitudePowers: Array<Float> = arrayOf(0.5f, 1.0f, 2f, 3f)

// Assistant button cycle. Keys match the proxy's STYLE_MODIFIERS keys;
// the mapped strings are the short Japanese labels shown on the button.
// Phase 4 (2026-05-10): "auto" prepended — proxy resolves to "smart" (intent-aware
// natural integration). bullets/140 removed — smart absorbs both behaviors.
private val ASSISTANT_STYLES = listOf("auto", "raw", "polite", "casual")
private val ASSISTANT_STYLE_LABELS = mapOf(
    "auto" to "🤖 スマート",
    "raw" to "📝 そのまま",
    "polite" to "🎩 丁寧",
    "casual" to "😎 カジュアル",
)

class WhisperKeyboard {
    private enum class KeyboardStatus {
        Idle,             // Ready to start recording
        Recording,       // Currently recording
        Transcribing,    // Waiting for transcription results
    }

    // Keyboard event listeners. Assignable custom behaviors upon certain UI events (user-operated).
    private var onStartRecording: () -> Unit = { }
    private var onCancelRecording: () -> Unit = { }
    private var onStartTranscribing: (attachToEnd: String) -> Unit = { }
    private var onCancelTranscribing: () -> Unit = { }
    private var onButtonBackspace: () -> Unit = { }
    private var onSwitchIme: () -> Unit = { }
    private var onOpenSettings: () -> Unit = { }
    private var onEnter: () -> Unit = { }
    private var onSpaceBar: () -> Unit = { }
    private var onAtSymbol: () -> Unit = { }
    private var onNewline: () -> Unit = { }
    // Phase 8 (2026-05-08): "?" 常駐ボタン用コールバック。
    private var onQuestionMark: () -> Unit = { }
    private var onDigit: (String) -> Unit = { }
    private var onCursorLeft: () -> Unit = { }
    private var onCursorRight: () -> Unit = { }
    private var onSend: () -> Unit = { }
    private var onAssistantStyleChange: (String) -> Unit = { }
    private var onAssistantConvModeChange: (Boolean) -> Unit = { }
    private var onClearChunk: () -> Unit = { }
    private var onClearAll: () -> Unit = { }
    // Phase 5b (2026-05-07): show the system IME picker so the user can flip
    // to Gboard for Japanese input mid-edit. Distinct from onSwitchIme
    // (previous-IME), which silently swaps to whatever was last used.
    private var onShowImePicker: () -> Unit = { }
    // Phase 6 #1 (2026-05-07): conversation-window callbacks. The IME service
    // hands these to the keyboard so the user can:
    //   - 最終応答送信: commit just アシスタントの最後の応答 to the host app.
    //   - 丸ごと破棄: clear conv_history without committing anything.
    //   - 対話終了: close the conv window, reset to voice mode (no commit).
    private var onConvSendFinal: () -> Unit = { }
    private var onConvDiscard: () -> Unit = { }
    private var onConvClose: () -> Unit = { }
    // Phase 6 #2 (2026-05-07): rōmaji buffer feedback. The IME service owns
    // the actual InputConnection — the keyboard just notifies "user pressed
    // a letter cell while in kana mode" and lets the service manage the
    // composing text and commits.
    private var onKanaLetter: (String) -> Unit = { }
    private var onKanaSpace: () -> Unit = { }
    private var onKanaBackspace: () -> Unit = { }
    private var onKanaModeChange: (Boolean) -> Unit = { }
    // Phase 7 (2026-05-07): hiragana→kanji 変換 button.
    private var onKanaConvert: () -> Unit = { }
    // Phase 8 (2026-05-08): 確定 button — commit the conversion buffer
    // (selected kanji candidate, kana, or pending romaji) without sending.
    private var onKanaCommit: () -> Unit = { }
    // Phase 8 (2026-05-08): hiragana→katakana 変換 button.
    private var onKanaToKatakana: () -> Unit = { }
    private var shouldShowRetry: () -> Boolean = { false }

    // Assistant mode state. Defaults match the proxy's defaults ("raw"/off).
    // Service refreshes these via setAssistantState() once DataStore is read.
    private var currentAssistantStyle: String = "raw"
    private var currentAssistantConvMode: Boolean = false

    // Keyboard Status
    private var keyboardStatus: KeyboardStatus = KeyboardStatus.Idle

    // Views & Keyboard Layout
    private var keyboardView: ConstraintLayout? = null
    private var buttonMic: ImageButton? = null
    private var buttonEnter: ImageButton? = null
    private var buttonCancel: ImageButton? = null
    private var buttonRetry: ImageButton? = null
    private var labelStatus: TextView? = null
    private var buttonSpaceBar: ImageButton? = null
    private var waitingIcon: ProgressBar? = null
    private var buttonBackspace: BackspaceButton? = null
    private var buttonPreviousIme: ImageButton? = null
    // Phase 8 (2026-05-08): btn_settings was converted from ImageButton to
    // TextView with a 🛠 emoji label, so the field type loosens to View?.
    private var buttonSettings: View? = null
    private var buttonAtSymbol: TextView? = null
    private var buttonNewline: TextView? = null
    private var buttonQuestionMark: TextView? = null
    // Phase 9 (2026-05-11): 句読点ボタン と Top Sub-row B の inline スペース
    private var buttonPunctPeriod: TextView? = null
    private var buttonPunctComma: TextView? = null
    private var buttonSpaceInline: TextView? = null
    private var buttonNumberToggle: TextView? = null
    private var buttonCursorLeft: TextView? = null
    private var buttonCursorRight: TextView? = null
    private var buttonSend: View? = null
    private var buttonAssistant: TextView? = null
    private var buttonConvToggle: TextView? = null
    private var buttonClearAll: View? = null
    private var numberPad: View? = null
    private var padPage1: View? = null
    private var padPage2: View? = null
    // Phase 5 — edit-mode toggle + QWERTY pad views
    private var buttonEditMode: TextView? = null
    // Phase 5b — IME picker button (visible only in edit mode).
    private var buttonImePicker: TextView? = null
    private var qwertyPad: View? = null
    private var qwertyLetterCells: Array<TextView> = emptyArray()
    private var qwertyShiftButton: TextView? = null
    // Tracks whether the QWERTY pad is currently in shift / caps state.
    // Caps lock is intentionally not implemented — single-tap shift only,
    // matching the minimal-edit-fix scope.
    private var qwertyShiftOn: Boolean = false
    private var digitButtons: Array<TextView> = emptyArray()
    // All pad cells that just commit their displayed text (digits + symbols on both pages).
    // Filled in setup() and wired to onDigit() uniformly.
    private var padTextCells: Array<TextView> = emptyArray()
    private var micRippleContainer: ConstraintLayout? = null
    private var micRipples: Array<ImageView> = emptyArray()

    // Phase 6 #1 — conversation window views.
    private var convWindow: View? = null
    private var convScroll: ScrollView? = null
    private var convHistory: LinearLayout? = null
    private var convStatus: TextView? = null
    private var btnConvSendFinal: TextView? = null
    private var btnConvDiscard: TextView? = null
    private var btnConvClose: TextView? = null

    // Phase 6 #2 — kana mode toggle.
    private var qwKanaButton: TextView? = null
    private var kanaModeOn: Boolean = false

    // Phase 7 (2026-05-07) — kanji 変換 button.
    private var qwHenkanButton: TextView? = null
    // Phase 8 (2026-05-08) — katakana 変換 button.
    private var qwKatakanaButton: TextView? = null

    fun setup(
        layoutInflater: LayoutInflater,
        shouldOfferImeSwitch: Boolean,
        onStartRecording: () -> Unit,
        onCancelRecording: () -> Unit,
        onStartTranscribing: (attachToEnd: String) -> Unit,
        onCancelTranscribing: () -> Unit,
        onButtonBackspace: () -> Unit,
        onEnter: () -> Unit,
        onSpaceBar: () -> Unit,
        onSwitchIme: () -> Unit,
        onOpenSettings: () -> Unit,
        onAtSymbol: () -> Unit,
        onNewline: () -> Unit,
        // Phase 8 — "?" insert callback
        onQuestionMark: () -> Unit,
        onDigit: (String) -> Unit,
        onCursorLeft: () -> Unit,
        onCursorRight: () -> Unit,
        onSend: () -> Unit,
        onAssistantStyleChange: (String) -> Unit,
        onAssistantConvModeChange: (Boolean) -> Unit,
        onClearChunk: () -> Unit,
        onClearAll: () -> Unit,
        onShowImePicker: () -> Unit,
        // Phase 6 #1 — conversation-window callbacks
        onConvSendFinal: () -> Unit,
        onConvDiscard: () -> Unit,
        onConvClose: () -> Unit,
        // Phase 6 #2 — kana-mode callbacks
        onKanaLetter: (String) -> Unit,
        onKanaSpace: () -> Unit,
        onKanaBackspace: () -> Unit,
        onKanaModeChange: (Boolean) -> Unit,
        // Phase 7 — kanji 変換 callback
        onKanaConvert: () -> Unit,
        // Phase 8 — 確定 callback (commit conversion buffer, no send)
        onKanaCommit: () -> Unit,
        // Phase 8 — katakana 変換 callback
        onKanaToKatakana: () -> Unit,
        shouldShowRetry: () -> Boolean,
    ): View {
        // Inflate the keyboard layout & assign views
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null) as ConstraintLayout
        buttonMic = keyboardView!!.findViewById(R.id.btn_mic) as ImageButton
        buttonEnter = keyboardView!!.findViewById(R.id.btn_enter) as ImageButton
        buttonCancel = keyboardView!!.findViewById(R.id.btn_cancel) as ImageButton
        buttonRetry = keyboardView!!.findViewById(R.id.btn_retry) as ImageButton
        labelStatus = keyboardView!!.findViewById(R.id.label_status) as TextView
        buttonSpaceBar = keyboardView!!.findViewById(R.id.btn_space_bar) as ImageButton
        waitingIcon = keyboardView!!.findViewById(R.id.pb_waiting_icon) as ProgressBar
        buttonBackspace = keyboardView!!.findViewById(R.id.btn_backspace) as BackspaceButton
        buttonPreviousIme = keyboardView!!.findViewById(R.id.btn_previous_ime) as ImageButton
        buttonSettings = keyboardView!!.findViewById(R.id.btn_settings)
        buttonAtSymbol = keyboardView!!.findViewById(R.id.btn_at_symbol) as TextView
        buttonNewline = keyboardView!!.findViewById(R.id.btn_newline) as TextView
        buttonQuestionMark = keyboardView!!.findViewById(R.id.btn_question_mark) as TextView
        buttonPunctPeriod = keyboardView!!.findViewById(R.id.btn_punct_period) as TextView
        buttonPunctComma = keyboardView!!.findViewById(R.id.btn_punct_comma) as TextView
        buttonSpaceInline = keyboardView!!.findViewById(R.id.btn_space_inline) as TextView
        buttonNumberToggle = keyboardView!!.findViewById(R.id.btn_number_toggle) as TextView
        buttonCursorLeft = keyboardView!!.findViewById(R.id.btn_cursor_left) as TextView
        buttonCursorRight = keyboardView!!.findViewById(R.id.btn_cursor_right) as TextView
        buttonSend = keyboardView!!.findViewById(R.id.btn_send)
        buttonAssistant = keyboardView!!.findViewById(R.id.btn_assistant) as TextView
        buttonConvToggle = keyboardView!!.findViewById(R.id.btn_conv_toggle) as TextView
        buttonClearAll = keyboardView!!.findViewById(R.id.btn_clear_all)
        numberPad = keyboardView!!.findViewById(R.id.number_pad)
        padPage1 = keyboardView!!.findViewById(R.id.pad_page_1)
        padPage2 = keyboardView!!.findViewById(R.id.pad_page_2)
        // Phase 5 — edit-mode toggle + QWERTY pad
        buttonEditMode = keyboardView!!.findViewById(R.id.btn_edit_mode) as TextView
        // Phase 5b — IME picker button (🌐). Visible only while in edit mode.
        buttonImePicker = keyboardView!!.findViewById(R.id.btn_ime_picker) as TextView
        qwertyPad = keyboardView!!.findViewById(R.id.qwerty_pad)
        qwertyShiftButton = keyboardView!!.findViewById(R.id.qw_shift) as TextView
        // Letter cells in row order so applyShiftLabels() can re-paint in
        // bulk. Order doesn't matter for click handling (each cell's listener
        // reads its own .text), but we keep it stable for clarity.
        qwertyLetterCells = arrayOf(
            keyboardView!!.findViewById(R.id.qw_q) as TextView,
            keyboardView!!.findViewById(R.id.qw_w) as TextView,
            keyboardView!!.findViewById(R.id.qw_e) as TextView,
            keyboardView!!.findViewById(R.id.qw_r) as TextView,
            keyboardView!!.findViewById(R.id.qw_t) as TextView,
            keyboardView!!.findViewById(R.id.qw_y) as TextView,
            keyboardView!!.findViewById(R.id.qw_u) as TextView,
            keyboardView!!.findViewById(R.id.qw_i) as TextView,
            keyboardView!!.findViewById(R.id.qw_o) as TextView,
            keyboardView!!.findViewById(R.id.qw_p) as TextView,
            keyboardView!!.findViewById(R.id.qw_a) as TextView,
            keyboardView!!.findViewById(R.id.qw_s) as TextView,
            keyboardView!!.findViewById(R.id.qw_d) as TextView,
            keyboardView!!.findViewById(R.id.qw_f) as TextView,
            keyboardView!!.findViewById(R.id.qw_g) as TextView,
            keyboardView!!.findViewById(R.id.qw_h) as TextView,
            keyboardView!!.findViewById(R.id.qw_j) as TextView,
            keyboardView!!.findViewById(R.id.qw_k) as TextView,
            keyboardView!!.findViewById(R.id.qw_l) as TextView,
            keyboardView!!.findViewById(R.id.qw_z) as TextView,
            keyboardView!!.findViewById(R.id.qw_x) as TextView,
            keyboardView!!.findViewById(R.id.qw_c) as TextView,
            keyboardView!!.findViewById(R.id.qw_v) as TextView,
            keyboardView!!.findViewById(R.id.qw_b) as TextView,
            keyboardView!!.findViewById(R.id.qw_n) as TextView,
            keyboardView!!.findViewById(R.id.qw_m) as TextView,
        )
        digitButtons = arrayOf(
            keyboardView!!.findViewById(R.id.btn_digit_0) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_1) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_2) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_3) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_4) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_5) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_6) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_7) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_8) as TextView,
            keyboardView!!.findViewById(R.id.btn_digit_9) as TextView,
        )
        // Symbol cells from both pages — treated identically to digit cells.
        val symbolCells: Array<TextView> = arrayOf(
            keyboardView!!.findViewById(R.id.btn_sym_p1_period) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p1_comma) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p1_colon) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p1_slash) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p1_minus) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_yen) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_percent) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_amp) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_hash) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_at) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_excl) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_q) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_star) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_paren_open) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_paren_close) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_dquote) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_squote) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_plus) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_eq) as TextView,
            keyboardView!!.findViewById(R.id.btn_sym_p2_tilde) as TextView,
        )
        padTextCells = digitButtons + symbolCells
        val padPageSwitch1 = keyboardView!!.findViewById(R.id.btn_pad_page_switch) as TextView
        val padPageSwitch2 = keyboardView!!.findViewById(R.id.btn_pad_page_switch_2) as TextView
        val pageSwitchListener = View.OnClickListener {
            it.hapticTap()
            val onPage1 = padPage1!!.visibility == View.VISIBLE
            padPage1!!.visibility = if (onPage1) View.GONE else View.VISIBLE
            padPage2!!.visibility = if (onPage1) View.VISIBLE else View.GONE
        }
        padPageSwitch1.setOnClickListener(pageSwitchListener)
        padPageSwitch2.setOnClickListener(pageSwitchListener)
        // Phase 6 #1 — conversation window views.
        convWindow = keyboardView!!.findViewById(R.id.conv_window)
        convScroll = keyboardView!!.findViewById(R.id.conv_scroll) as ScrollView
        convHistory = keyboardView!!.findViewById(R.id.conv_history) as LinearLayout
        convStatus = keyboardView!!.findViewById(R.id.conv_status) as TextView
        btnConvSendFinal = keyboardView!!.findViewById(R.id.btn_conv_send_final) as TextView
        btnConvDiscard = keyboardView!!.findViewById(R.id.btn_conv_discard) as TextView
        btnConvClose = keyboardView!!.findViewById(R.id.btn_conv_close) as TextView

        // Phase 6 #2 — kana toggle button (lives on QWERTY row 4).
        qwKanaButton = keyboardView!!.findViewById(R.id.qw_kana) as TextView

        // Phase 7 — 変換 button (lives on QWERTY row 4 next to かな).
        qwHenkanButton = keyboardView!!.findViewById(R.id.qw_henkan) as TextView
        qwKatakanaButton = keyboardView!!.findViewById(R.id.qw_katakana) as TextView

        micRippleContainer = keyboardView!!.findViewById(R.id.mic_ripples) as ConstraintLayout
        micRipples = arrayOf(
            keyboardView!!.findViewById(R.id.mic_ripple_0) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_1) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_2) as ImageView,
            keyboardView!!.findViewById(R.id.mic_ripple_3) as ImageView
        )

        // Hide buttonPreviousIme if necessary
        if (!shouldOfferImeSwitch) {
            buttonPreviousIme!!.visibility = View.GONE
        }

        // Set onClick listeners (each tap also fires a short haptic tick)
        buttonMic!!.setOnClickListener { it.hapticTap(); onButtonMicClick() }
        buttonEnter!!.setOnClickListener { it.hapticTap(); onButtonEnterClick() }
        buttonCancel!!.setOnClickListener { it.hapticTap(); onButtonCancelClick() }
        buttonRetry!!.setOnClickListener { it.hapticTap(); onButtonRetryClick() }
        buttonSettings!!.setOnClickListener { it.hapticTap(); onButtonSettingsClick() }
        // BackspaceButton has its own custom callback wiring — haptic fires inside its class
        buttonBackspace!!.setBackspaceCallback { onButtonBackspaceClick() }
        buttonSpaceBar!!.setOnClickListener { it.hapticTap(); onButtonSpaceBarClick() }
        buttonAtSymbol!!.setOnClickListener { it.hapticTap(); onAtSymbol() }
        buttonNewline!!.setOnClickListener { it.hapticTap(); onNewline() }
        buttonQuestionMark!!.setOnClickListener { it.hapticTap(); onQuestionMark() }
        buttonPunctPeriod!!.setOnClickListener { it.hapticTap(); onDigit("。") }
        buttonPunctComma!!.setOnClickListener { it.hapticTap(); onDigit("、") }
        buttonSpaceInline!!.setOnClickListener { it.hapticTap(); onSpaceBar() }
        buttonCursorLeft!!.setOnClickListener { it.hapticTap(); onCursorLeft() }
        buttonCursorRight!!.setOnClickListener { it.hapticTap(); onCursorRight() }
        buttonSend!!.setOnClickListener { it.hapticTap(); onSend() }
        // Assistant style button — short tap cycles through refine styles.
        // Conversation mode now lives on a dedicated btn_conv_toggle (see below)
        // after user feedback that long-press on one dual-function button was
        // non-discoverable.
        buttonAssistant!!.setOnClickListener {
            it.hapticTap()
            val idx = ASSISTANT_STYLES.indexOf(currentAssistantStyle).coerceAtLeast(0)
            val next = ASSISTANT_STYLES[(idx + 1) % ASSISTANT_STYLES.size]
            currentAssistantStyle = next
            updateAssistantLabel()
            this.onAssistantStyleChange(next)
        }
        buttonConvToggle!!.setOnClickListener {
            it.hapticTap()
            currentAssistantConvMode = !currentAssistantConvMode
            updateAssistantLabel()
            this.onAssistantConvModeChange(currentAssistantConvMode)
        }
        // Clear button — short tap deletes a chunk (5 chars back), long press
        // wipes the field. Distinct from the small ⌫ which does 1 char.
        buttonClearAll!!.setOnClickListener {
            it.hapticTap()
            this.onClearChunk()
        }
        buttonClearAll!!.setOnLongClickListener {
            it.hapticTap()
            this.onClearAll()
            true
        }

        buttonNumberToggle!!.setOnClickListener {
            it.hapticTap()
            toggleNumberPad()
        }
        // All digit + symbol cells on both pad pages share the same behavior:
        // commit the displayed glyph to the current field.
        for (btn in padTextCells) {
            btn.setOnClickListener { v ->
                v.hapticTap()
                onDigit((v as TextView).text.toString())
            }
        }

        // Phase 5 — edit-mode toggle. Short-tap flips between voice and QWERTY
        // edit mode. The toggle's own label updates so the user can see the
        // *target* state at a glance ("ABC" while in voice mode, "音声" while
        // in edit mode). NOTE: the QWERTY cell click handlers that depend on
        // the onSend / onButtonBackspace / cursor / space callbacks are wired
        // up further down, AFTER the event-listener assignments, so they can
        // capture the actual lambdas (not the empty defaults).
        buttonEditMode!!.setOnClickListener {
            it.hapticTap()
            toggleEditMode()
        }

        // Phase 5b — IME picker (🌐): opens system picker so the user can
        // jump to Gboard for Japanese input. The lambda is captured by
        // reference so the assignment to this.onShowImePicker further
        // below is the one that fires.
        buttonImePicker!!.setOnClickListener {
            it.hapticTap()
            this.onShowImePicker()
        }

        if (shouldOfferImeSwitch) {
            buttonPreviousIme!!.setOnClickListener { it.hapticTap(); onButtonPreviousImeClick() }
        }

        // Set event listeners
        this.onStartRecording = onStartRecording
        this.onCancelRecording = onCancelRecording
        this.onStartTranscribing = onStartTranscribing
        this.onCancelTranscribing = onCancelTranscribing
        this.onButtonBackspace = onButtonBackspace
        this.onSwitchIme = onSwitchIme
        this.onOpenSettings = onOpenSettings
        this.onEnter = onEnter
        this.onSpaceBar = onSpaceBar
        this.onAtSymbol = onAtSymbol
        this.onNewline = onNewline
        this.onQuestionMark = onQuestionMark
        this.onDigit = onDigit
        this.onCursorLeft = onCursorLeft
        this.onCursorRight = onCursorRight
        this.onSend = onSend
        this.onAssistantStyleChange = onAssistantStyleChange
        this.onAssistantConvModeChange = onAssistantConvModeChange
        this.onClearChunk = onClearChunk
        this.onClearAll = onClearAll
        this.onShowImePicker = onShowImePicker
        this.onConvSendFinal = onConvSendFinal
        this.onConvDiscard = onConvDiscard
        this.onConvClose = onConvClose
        this.onKanaLetter = onKanaLetter
        this.onKanaSpace = onKanaSpace
        this.onKanaBackspace = onKanaBackspace
        this.onKanaModeChange = onKanaModeChange
        this.onKanaConvert = onKanaConvert
        this.onKanaCommit = onKanaCommit
        this.onKanaToKatakana = onKanaToKatakana
        this.shouldShowRetry = shouldShowRetry

        // Phase 5 — wire up the QWERTY edit-pad listeners NOW, after the
        // event lambdas (this.onSend, this.onButtonBackspace, etc.) have
        // been assigned. If we wired them up earlier the listeners would
        // capture the empty-default lambdas and silently no-op when the
        // user taps a QWERTY cell.
        for (cell in qwertyLetterCells) {
            cell.setOnClickListener { v ->
                v.hapticTap()
                val ch = (v as TextView).text.toString()
                if (kanaModeOn) {
                    // Route through the kana buffer. Letters are only
                    // appended in lowercase form to keep the romaji table
                    // simple — shift+letter still commits an uppercase
                    // glyph as a fallback (rare in Japanese flow).
                    if (qwertyShiftOn) {
                        this.onDigit(ch)
                    } else {
                        this.onKanaLetter(ch.lowercase())
                    }
                } else {
                    this.onDigit(ch)
                }
                // Auto-release single-tap shift after one keystroke (matches
                // stock Android keyboard behavior).
                if (qwertyShiftOn) {
                    qwertyShiftOn = false
                    applyShiftLabels()
                }
            }
        }
        qwertyShiftButton!!.setOnClickListener {
            it.hapticTap()
            qwertyShiftOn = !qwertyShiftOn
            applyShiftLabels()
        }
        keyboardView!!.findViewById<TextView>(R.id.qw_bs).setOnClickListener {
            it.hapticTap()
            if (kanaModeOn) this.onKanaBackspace() else this.onButtonBackspace()
        }
        keyboardView!!.findViewById<TextView>(R.id.qw_left).setOnClickListener {
            it.hapticTap()
            this.onCursorLeft()
        }
        keyboardView!!.findViewById<TextView>(R.id.qw_right).setOnClickListener {
            it.hapticTap()
            this.onCursorRight()
        }
        keyboardView!!.findViewById<TextView>(R.id.qw_space).setOnClickListener {
            it.hapticTap()
            if (kanaModeOn) this.onKanaSpace() else this.onSpaceBar()
        }
        // Phase 9 (2026-05-09): dedicated 「ー」 long-vowel key. Routes through
        // onDigit("-") which finalizes kana composing first and emits "ー" in
        // kana mode (or literal "-" if the user happens to be in non-kana mode).
        keyboardView!!.findViewById<TextView>(R.id.qw_long_vowel).setOnClickListener {
            it.hapticTap()
            this.onDigit("-")
        }
        keyboardView!!.findViewById<TextView>(R.id.qw_send).setOnClickListener {
            // Phase 8 — qw_send is now the 確定 button: commit the conversion
            // buffer to the field but do NOT send (the airplane btn_send is
            // the dedicated send action).
            it.hapticTap()
            this.onKanaCommit()
        }
        // Phase 6 #2 — kana toggle. Flips kanaModeOn and re-paints button
        // alpha so the user can see whether they're in romaji-direct or
        // kana-conversion mode.
        qwKanaButton!!.setOnClickListener {
            it.hapticTap()
            kanaModeOn = !kanaModeOn
            updateKanaLabel()
            this.onKanaModeChange(kanaModeOn)
        }

        // Phase 7 (2026-05-07) — 変換 button. Always wired; the IME service
        // is the one that knows whether there's anything in the kana
        // buffer to convert. Tapping it with no buffer is a harmless
        // no-op on the service side.
        qwHenkanButton!!.setOnClickListener {
            it.hapticTap()
            this.onKanaConvert()
        }

        // Phase 8 (2026-05-08) — カナ button. Converts the current composing
        // hiragana buffer to katakana via Unicode shift, no remote call.
        // Wiring mirrors qwHenkanButton; the service decides what's
        // available to convert.
        qwKatakanaButton!!.setOnClickListener {
            it.hapticTap()
            this.onKanaToKatakana()
        }

        // Phase 6 #1 — conversation window footer buttons.
        btnConvSendFinal!!.setOnClickListener {
            it.hapticTap()
            this.onConvSendFinal()
        }
        btnConvDiscard!!.setOnClickListener {
            it.hapticTap()
            this.onConvDiscard()
        }
        btnConvClose!!.setOnClickListener {
            it.hapticTap()
            this.onConvClose()
        }

        // Render the initial Assistant label using defaults; service will
        // refresh via setAssistantState() once DataStore is read.
        updateAssistantLabel()

        // Resets keyboard upon setup
        reset()

        // Returns the keyboard view (non-nullable)
        return keyboardView!!
    }

    fun reset() {
        setKeyboardStatus(KeyboardStatus.Idle)
    }

    /**
     * Called by the service once DataStore has been read, to seed the
     * current Assistant button state with persisted values.
     */
    fun setAssistantState(style: String, convMode: Boolean) {
        currentAssistantStyle = if (style in ASSISTANT_STYLES) style else "auto"
        currentAssistantConvMode = convMode
        updateAssistantLabel()
    }

    /**
     * Toggle the 123 / symbol pad with a springy pop-up animation.
     * Show:  scale 0.85 → 1.0 (overshoot) + fade in + slide up from +32dp.
     * Hide:  scale 1.0 → 0.9 + fade out + slide down, then GONE.
     * Pivots from the top-center so the reveal feels like it drops from the
     * 123 button above.
     */
    private fun toggleNumberPad() {
        val pad = numberPad ?: return
        if (pad.visibility != View.VISIBLE) {
            // Phase 5b fix (2026-05-07): the QWERTY edit pad and the 123 pad
            // both anchor below btn_send and were stacking on top of each
            // other when the user opened 123 from edit mode (digits 2/3/7/8
            // visibly overlapped QWERTY cells). Force-hide the QWERTY pad
            // *and* its sibling IME-picker button so only one keyboard pad
            // is on screen at a time. Also reset btn_edit_mode's label so
            // the user's affordance ("ABC" = "tap to enter edit mode") is
            // restored.
            qwertyPad?.visibility = View.GONE
            buttonImePicker?.visibility = View.GONE
            buttonEditMode?.text = "✏️ 修正"

            pad.pivotX = pad.width / 2f
            pad.pivotY = 0f
            pad.alpha = 0f
            pad.scaleX = 0.85f
            pad.scaleY = 0.85f
            pad.translationY = 32f * pad.resources.displayMetrics.density
            pad.visibility = View.VISIBLE
            pad.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        } else {
            pad.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .translationY(24f * pad.resources.displayMetrics.density)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { pad.visibility = View.GONE }
                .start()
        }
    }

    /**
     * Phase 5 (2026-05-07): flip between voice mode (default) and the
     * QWERTY edit pad. Voice mode hides qwerty_pad and shows the mic
     * frame normally; edit mode hides the mic-area widgets so the
     * keypad has the full bottom half of the IME view.
     *
     * Implementation note: rather than enumerating every voice-mode
     * widget to toggle visibility, we just toggle the QWERTY pad's
     * visibility on/off. The mic frame and friends still render but
     * the user's attention is drawn to the pad below them. A full
     * "hide voice UI" pass can come in a follow-up if it feels noisy
     * in practice — kept minimal for the first ship.
     */
    private fun toggleEditMode() {
        val pad = qwertyPad ?: return
        val toggle = buttonEditMode ?: return
        val showing = pad.visibility == View.VISIBLE
        if (showing) {
            pad.visibility = View.GONE
            toggle.text = "✏️ 修正"
            // Phase 5b: IME picker is only useful while QWERTY is on screen.
            buttonImePicker?.visibility = View.GONE
        } else {
            pad.visibility = View.VISIBLE
            toggle.text = "音声"
            // Phase 5b: surface the 🌐 IME picker so the user can flip to
            // Gboard for Japanese input (rōmaji→kana IME is out of scope
            // for this app — we lean on the system picker instead).
            buttonImePicker?.visibility = View.VISIBLE
            // Hide the 123 pad if it's currently open so we don't stack
            // two pads on top of each other.
            numberPad?.visibility = View.GONE
            // Phase 8 (2026-05-08): default to kana mode ON when entering
            // edit mode. The keyboard is primarily for Japanese cleanup
            // after a voice transcription, so romaji-to-kana should be
            // the immediate path. User can still flip to direct ABC via
            // the かな toggle.
            if (!kanaModeOn) {
                kanaModeOn = true
                updateKanaLabel()
                this.onKanaModeChange(true)
            }
        }
    }

    /**
     * Phase 5: re-paint each QWERTY letter cell's label according to
     * the current shift state. Done in bulk on every shift toggle.
     */
    private fun applyShiftLabels() {
        for (cell in qwertyLetterCells) {
            val current = cell.text?.toString() ?: continue
            cell.text = if (qwertyShiftOn) current.uppercase() else current.lowercase()
        }
        qwertyShiftButton?.let {
            it.alpha = if (qwertyShiftOn) 1.0f else 0.7f
            it.text = if (qwertyShiftOn) "⇧" else "⇧"
        }
    }

    private fun updateAssistantLabel() {
        buttonAssistant?.let { btn ->
            val styleLabel = ASSISTANT_STYLE_LABELS[currentAssistantStyle] ?: "🤖 スマート"
            btn.text = styleLabel
        }
        buttonConvToggle?.let { btn ->
            btn.text = if (currentAssistantConvMode) "💬 アシスタント●" else "💬 アシスタント"
            btn.alpha = if (currentAssistantConvMode) 1.0f else 0.7f
        }
    }

    /** Phase 6 #2 — repaint the kana toggle so its visual state matches the
     *  internal flag. Bold + full alpha = ON, dim + regular = OFF. */
    private fun updateKanaLabel() {
        qwKanaButton?.let { btn ->
            btn.text = if (kanaModeOn) "● かな" else "かな"
            btn.alpha = if (kanaModeOn) 1.0f else 0.7f
        }
    }

    // ─── Phase 6 #1: conversation-window helpers (called from IME service)

    /** True while the conv window is on screen. */
    fun isConvWindowVisible(): Boolean = convWindow?.visibility == View.VISIBLE

    /**
     * Show the conversation window. Hides the QWERTY/123 pads to avoid
     * stacking but leaves the mic frame visible above so the user can
     * keep dictating into the conversation.
     */
    fun showConvWindow() {
        convWindow?.visibility = View.VISIBLE
        qwertyPad?.visibility = View.GONE
        numberPad?.visibility = View.GONE
        buttonImePicker?.visibility = View.GONE
        // Restore edit-mode label in case we were in QWERTY before.
        buttonEditMode?.text = "✏️ 修正"
    }

    fun hideConvWindow() {
        convWindow?.visibility = View.GONE
    }

    /**
     * Append a message bubble to conv_history. role="user" or "assistant".
     * Each message is a TextView with a label prefix and tinted background
     * so the conversation reads as a chat log.
     */
    fun appendConvMessage(role: String, text: String) {
        val history = convHistory ?: return
        val ctx = history.context
        val label = when (role) {
            "user" -> ctx.getString(R.string.conv_user_label)
            else -> ctx.getString(R.string.conv_assistant_label)
        }
        val tv = TextView(ctx).apply {
            this.text = "${label}: ${text}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(ctx, 4)
            layoutParams = lp
            // Assistant replies get a subtly different tint so the eye can
            // separate user vs assistant turns at a glance.
            setBackgroundResource(R.drawable.carbon_button_bg)
            alpha = if (role == "user") 0.85f else 1.0f
        }
        history.addView(tv)
        // Auto-scroll to bottom so newest message is visible.
        convScroll?.post { convScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    /** Wipe all bubbles from conv_history (called by 丸ごと破棄 / 対話終了). */
    fun clearConvHistory() {
        convHistory?.removeAllViews()
    }

    /** Update the small status text below the history (idle / 録音中 / 考え中). */
    fun setConvStatus(text: String) {
        convStatus?.text = text
    }

    private fun dp(ctx: Context, n: Int): Int =
        (n * ctx.resources.displayMetrics.density).toInt()

    fun updateMicrophoneAmplitude(amplitude: Int) {
        if (keyboardStatus != KeyboardStatus.Recording) {
            return
        }

        val clampedAmplitude = MathUtils.clamp(
            amplitude,
            AMPLITUDE_CLAMP_MIN,
            AMPLITUDE_CLAMP_MAX
        )

        // decibel-like calculation
        val normalizedPower =
            (log10(clampedAmplitude * 1f) - LOG_10_10) / (LOG_10_25000 - LOG_10_10)

        // normalizedPower ranges from 0 to 1.
        // The inner-most ripple should be the most sensitive to audio,
        // represented by a gamma-correction-like curve.
        for (micRippleIdx in micRipples.indices) {
            micRipples[micRippleIdx].clearAnimation()
            micRipples[micRippleIdx].alpha = normalizedPower.pow(amplitudePowers[micRippleIdx])
            micRipples[micRippleIdx].animate().alpha(0f).setDuration(AMPLITUDE_ANIMATION_DURATION)
                .start()
        }
    }

    fun tryStartRecording() {
        if (keyboardStatus == KeyboardStatus.Idle) {
            setKeyboardStatus(KeyboardStatus.Recording)
            onStartRecording()
        }
    }

    fun tryCancelRecording() {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        }
    }

    fun tryStartTranscribing(attachToEnd: String) {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(attachToEnd)
        }
    }

    private fun onButtonSpaceBarClick() {
        // Upon button space bar click.
        // Recording -> Start transcribing (with a whitespace included)
        // else -> invokes onSpaceBar
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(" ")
        } else {
            onSpaceBar()
        }
    }

    private fun onButtonBackspaceClick() {
        // Currently, this onClick only makes a call to onButtonBackspace()
        this.onButtonBackspace()
    }

    private fun onButtonPreviousImeClick() {
        // Currently, this onClick only makes a call to onSwitchIme()
        this.onSwitchIme()
    }

    private fun onButtonSettingsClick() {
        // Currently, this onClick only makes a call to onOpenSettings()
        this.onOpenSettings()
    }

    private fun onButtonMicClick() {
        // Upon button mic click...
        // Idle -> Start Recording
        // Recording -> Finish Recording (without a newline)
        // Transcribing -> Nothing (to avoid double-clicking by mistake, which starts transcribing and then immediately cancels it)
        when (keyboardStatus) {
            KeyboardStatus.Idle -> {
                setKeyboardStatus(KeyboardStatus.Recording)
                onStartRecording()
            }

            KeyboardStatus.Recording -> {
                setKeyboardStatus(KeyboardStatus.Transcribing)
                onStartTranscribing("")
            }

            KeyboardStatus.Transcribing -> {
                return
            }
        }
    }

    private fun onButtonEnterClick() {
        // Upon button enter click.
        // Recording -> Start transcribing (with a newline included)
        // else -> invokes onEnter
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("\r\n")
        } else {
            onEnter()
        }
    }

    private fun onButtonCancelClick() {
        // Upon button cancel click.
        // Recording -> Cancel
        // Transcribing -> Cancel
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        } else if (keyboardStatus == KeyboardStatus.Transcribing) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelTranscribing()
        }
    }

    private fun onButtonRetryClick() {
        // Upon button retry click.
        // Idle -> Retry
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Idle) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing("")
        }
    }

    private fun setKeyboardStatus(newStatus: KeyboardStatus) {
        when (newStatus) {
            KeyboardStatus.Idle -> {
                // Phase 8 (2026-05-08): brand-logo style for the idle label.
                // 「アシスタント」を太字で大きく、改行して「K E Y B O A R D」を
                // 小ぶりで字間広く・グレー色で添える二段ロゴ表現。Html.fromHtml
                // は <font color> + <small> + <br/> のミニマルセットを解釈する。
                labelStatus!!.text = android.text.Html.fromHtml(
                    "<b>アシスタント</b><br/>" +
                    "<small><font color='#888888'>K E Y B O A R D</font></small>",
                    android.text.Html.FROM_HTML_MODE_LEGACY
                )
                labelStatus!!.setLineSpacing(0f, 0.95f)
                buttonMic!!.setImageResource(R.drawable.mic_idle)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.INVISIBLE
                // Retry button was removed from the visible UI per user request;
                // force it gone so the layout can never re-show it.
                buttonRetry!!.visibility = View.GONE
                micRippleContainer!!.visibility = View.GONE
                keyboardView!!.keepScreenOn = false
            }

            KeyboardStatus.Recording -> {
                labelStatus!!.setText(R.string.recording)
                buttonMic!!.setImageResource(R.drawable.mic_pressed)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonRetry!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.VISIBLE
                keyboardView!!.keepScreenOn = true
            }

            KeyboardStatus.Transcribing -> {
                labelStatus!!.setText(R.string.transcribing)
                buttonMic!!.setImageResource(R.drawable.mic_transcribing)
                waitingIcon!!.visibility = View.VISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                buttonRetry!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.GONE
                keyboardView!!.keepScreenOn = true
            }
        }

        keyboardStatus = newStatus
    }
}
