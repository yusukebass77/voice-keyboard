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

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import com.github.liuyueyi.quick.transfer.ChineseUtils
import org.json.JSONObject

class WhisperTranscriber {
    private data class Config(
        val endpoint: String,
        val languageCode: String,
        val speechToTextBackend: String,
        val apiKey: String,
        val model: String,
        val postprocessing: String,
        val addTrailingSpace: Boolean,
        val assistantStyle: String,
        val assistantConvMode: Boolean
    )

    private val TAG = "WhisperTranscriber"
    private var currentTranscriptionJob: Job? = null
    private var currentPlayer: MediaPlayer? = null

    private fun playSpeechFromUrl(url: String) {
        try {
            currentPlayer?.release()
        } catch (_: Exception) { /* ignore */ }
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
                if (currentPlayer === it) currentPlayer = null
            }
            setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                try { mp.release() } catch (_: Exception) {}
                if (currentPlayer === mp) currentPlayer = null
                true
            }
            prepareAsync()
        }
        currentPlayer = player
    }

    /**
     * Phase 5 (2026-05-07): expose the request_id of the most recent
     * /transcribe call so the IME service can pair a later /feedback POST
     * with the original transcription. Single-flight model — overwritten
     * on each new call. Null until the first successful response, or if
     * the proxy did not surface the X-Vk-Request-Id header (e.g. when
     * the user is hitting OpenAI / NIM directly without our proxy).
     */
    @Volatile
    var lastRequestId: String? = null
        private set

    /**
     * Phase 5: the proxy-side text that was returned for [lastRequestId].
     * The IME compares this against the user-committed text at submit /
     * onFinishInput time and posts the diff to /feedback so the offline
     * learner can build training pairs.
     */
    @Volatile
    var lastFinalText: String? = null
        private set

    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String,
        appPackage: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit
    ) {
        suspend fun makeWhisperRequest(): String {
            // Retrieve configs
            val cfg = context.dataStore.data.map { preferences: Preferences ->
                Config(
                    preferences[ENDPOINT] ?: "",
                    preferences[LANGUAGE_CODE] ?: "",
                    preferences[SPEECH_TO_TEXT_BACKEND] ?: context.getString(R.string.settings_option_openai_api),
                    preferences[API_KEY] ?: "",
                    preferences[MODEL] ?: "",
                    preferences[POSTPROCESSING] ?: context.getString(R.string.settings_option_no_conversion),
                    preferences[ADD_TRAILING_SPACE] ?: false,
                    preferences[ASSISTANT_STYLE] ?: "auto",
                    preferences[ASSISTANT_CONV_MODE] ?: false
                )
            }.first()
            val endpoint = cfg.endpoint
            val languageCode = cfg.languageCode
            val speechToTextBackend = cfg.speechToTextBackend
            val apiKey = cfg.apiKey
            val model = cfg.model
            val postprocessing = cfg.postprocessing
            val addTrailingSpace = cfg.addTrailingSpace

            // Foolproof message
            if (endpoint == "") {
                throw Exception(context.getString(R.string.error_endpoint_unset))
            }

            // Make request. Conversation mode runs STT + Claude refine + TTS
            // server-side which can take 8–15s on slower networks; the default
            // 10s read timeout was too tight.
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            val request = buildWhisperRequest(
                context,
                filename,
                mediaType,
                speechToTextBackend,
                endpoint,
                languageCode,
                apiKey,
                model,
                cfg.assistantStyle,
                cfg.assistantConvMode,
                appPackage
            )
            val response = client.newCall(request).execute()

            // If request is not successful, or response code is weird
            if (!response.isSuccessful || response.code / 100 != 2) {
                throw Exception(response.body!!.string().replace('\n', ' '))
            }

            // Phase 5: capture X-Vk-Request-Id so a later /feedback POST can
            // be paired with this transcription. Header is missing when the
            // user has bypassed our proxy (direct OpenAI / NIM endpoint), so
            // null is fine — feedback simply won't fire in that case.
            val requestId = response.header("X-Vk-Request-Id")
            if (!requestId.isNullOrBlank()) {
                lastRequestId = requestId
            } else {
                lastRequestId = null
            }

            // Conversation-mode TTS: when the proxy has synthesized speech for
            // アシスタント's reply it returns a short-lived URL in X-Vk-Speech-Url.
            // Fire playback off the main thread immediately — it runs in
            // parallel with the text being typed into the IME.
            val speechUrl = response.header("X-Vk-Speech-Url")
            if (!speechUrl.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    try { playSpeechFromUrl(speechUrl) } catch (e: Exception) {
                        Log.w(TAG, "playSpeechFromUrl failed: ${e.message}")
                    }
                }
            }

            var rawText = response.body!!.string().trim()
            
            // For NVIDIA NIM, remove quotes if they wrap the text
            // Not sure if this is a bug or a feature...
            if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim) && 
                rawText.startsWith("\"") && rawText.endsWith("\"")) {
                rawText = rawText.substring(1, rawText.length - 1).trim()
            }
            
            val processedText = when (postprocessing) {
                context.getString(R.string.settings_option_to_simplified) -> ChineseUtils.tw2s(rawText)
                context.getString(R.string.settings_option_to_traditional) -> ChineseUtils.s2tw(rawText)
                else -> rawText // No conversion
            }

            // Phase 5: lastFinalText snapshots the proxy-side text that we're
            // about to commit. attachToEnd is reapplied by the IME service
            // when comparing against the user-submitted text, so we keep the
            // raw processedText here without trailing whitespace / newlines.
            lastFinalText = processedText

            if (attachToEnd == "") {
                return processedText + if (addTrailingSpace) " " else ""
            } else {
                // Only used for space key and enter key.
                return processedText + attachToEnd
            }
        }

        // Create a cancellable job in the main thread (for UI updating)
        val job = CoroutineScope(Dispatchers.Main).launch {

            // Within the job, make a suspend call at the I/O thread
            // It suspends before result is obtained.
            // Returns (transcribed string, exception message)
            val (transcribedText, exceptionMessage) = withContext(Dispatchers.IO) {
                try {
                    // Perform transcription here
                    val response = makeWhisperRequest()
                    // Clean up unused audio file after transcription
                    // Ref: https://developer.android.com/reference/android/media/MediaRecorder#setOutputFile(java.io.File)
                    File(filename).delete()
                    return@withContext Pair(response, null)
                } catch (e: CancellationException) {
                    // Task was canceled
                    return@withContext Pair(null, null)
                } catch (e: Exception) {
                    return@withContext Pair(null, e.message)
                }
            }

            // This callback is within the main thread.
            callback.invoke(transcribedText)

            // If exception message is not null
            if (!exceptionMessage.isNullOrEmpty()) {
                Log.e(TAG, exceptionMessage)
                exceptionCallback(exceptionMessage)
            }
        }

        registerTranscriptionJob(job)
    }

    fun stop() {
        registerTranscriptionJob(null)
        try {
            currentPlayer?.release()
        } catch (_: Exception) { /* ignore */ }
        currentPlayer = null
    }

    private fun registerTranscriptionJob(job: Job?) {
        currentTranscriptionJob?.cancel()
        currentTranscriptionJob = job
    }

    private fun buildWhisperRequest(
        context: Context,
        filename: String,
        mediaType: String,
        speechToTextBackend: String,
        endpoint: String,
        languageCode: String,
        apiKey: String,
        model: String,
        assistantStyle: String,
        assistantConvMode: Boolean,
        appPackage: String
    ): Request {
        // Please refer to the following for the endpoint/payload definitions:
        // OpenAI API:
        // - https://platform.openai.com/docs/api-reference/audio/createTranscription
        // - https://platform.openai.com/docs/api-reference/making-requests
        // Whisper ASR WebService:
        // - https://ahmetoner.com/whisper-asr-webservice/run/#usage
        // NVIDIA NIM:
        // - No public documentation for HTTP-style requests.
        // - Source code at `/opt/nim/inference.py` in docker container `nvcr.io/nim/nvidia/riva-asr:1.3.0`.
        /*
            ...
            @HttpNIMApiInterface.route('/v1/audio/transcriptions', methods=["post"])
            async def transcriptions(
                self,
                file: UploadFile = File(...),
                model: Optional[str] = Form(None),
                language: Optional[str] = Form(None),
                prompt: Optional[str] = Form(None),
                response_format: Optional[str] = Form(None),
                temperature: Optional[float] = Form(None),
            ):
            ...
         */
        val file: File = File(filename)
        val fileBody: RequestBody = file.asRequestBody(mediaType.toMediaTypeOrNull())
        val requestBody: RequestBody = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            // Determine filename based on media type
            val formDataFilename = if (mediaType == "audio/ogg") "@audio.ogg" else "@audio.m4a"
            
            // Add file to payload
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api) || 
                speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim)) {
                addFormDataPart("file", formDataFilename, fileBody)
            } else if (speechToTextBackend == context.getString(R.string.settings_option_whisper_asr_webservice)) {
                addFormDataPart("audio_file", formDataFilename, fileBody)
            }
            // Add backend-specific parameters to payload
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api)) {
                addFormDataPart("model", model)
                addFormDataPart("response_format", "text")
            }
            if (speechToTextBackend == context.getString(R.string.settings_option_nvidia_nim)) {
                addFormDataPart("language", languageCode)
                addFormDataPart("response_format", "text")
            }
            // Assistant button state — carried on every request so the proxy can
            // pick the right refine prompt / conversation persona. The upstream
            // OpenAI / NIM endpoints ignore unknown form fields, so it's safe
            // to always attach these regardless of backend.
            addFormDataPart("style", assistantStyle)
            addFormDataPart("conversation_mode", if (assistantConvMode) "1" else "0")
            // Phase 4 (2026-05-10): forward foreground app's package name so the
            // proxy can resolve style="auto" → per-app default (Discord=raw,
            // Gmail=polite, etc.). Empty string is fine; proxy treats it as raw.
            if (appPackage.isNotEmpty()) {
                addFormDataPart("app_package", appPackage)
            }
        }.build()

        val requestHeaders: Headers = Headers.Builder().apply {
            if (speechToTextBackend == context.getString(R.string.settings_option_openai_api)) {
                // Foolproof message
                if (apiKey == "") {
                    throw Exception(context.getString(R.string.error_apikey_unset))
                }
                add("Authorization", "Bearer $apiKey")
            }
            add("Content-Type", "multipart/form-data")
        }.build()

        // Build URL with endpoint-specific parameters
        val url = when (speechToTextBackend) {
            context.getString(R.string.settings_option_openai_api),
            context.getString(R.string.settings_option_whisper_asr_webservice) -> {
                "$endpoint?encode=true&task=transcribe&language=$languageCode&word_timestamps=false&output=txt"
            }
            else -> endpoint
        }

        return Request.Builder()
            .headers(requestHeaders)
            .url(url)
            .post(requestBody)
            .build()
    }

    /**
     * Phase 5 (2026-05-07): fire-and-forget POST to <endpoint host>/feedback.
     *
     * Records the post-edit submission so the proxy can build training
     * pairs (raw → submitted) for the offline learning loop. Failures are
     * logged and swallowed — the user has already submitted their text on
     * the device side, so a feedback miss is non-fatal.
     *
     * The endpoint URL is derived from the configured /transcribe ENDPOINT
     * by replacing its path with `/feedback` (so the same host:port and
     * scheme are reused). This avoids a separate "feedback URL" setting.
     *
     * Threading: must be called from a coroutine — internally hops to
     * Dispatchers.IO. Will not throw.
     */
    fun sendFeedback(
        context: Context,
        requestId: String,
        finalText: String,
        submittedText: String,
        user: String,
    ) {
        if (requestId.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cfg = context.dataStore.data.map { preferences: Preferences ->
                    Triple(
                        preferences[ENDPOINT] ?: "",
                        preferences[API_KEY] ?: "",
                        preferences[FEEDBACK_ENABLED] ?: true,
                    )
                }.first()
                val endpoint = cfg.first
                val apiKey = cfg.second
                val enabled = cfg.third
                if (!enabled) {
                    Log.d(TAG, "feedback disabled by user setting; skipping rid=$requestId")
                    return@launch
                }
                if (endpoint.isBlank()) {
                    Log.d(TAG, "feedback skipped — endpoint unset")
                    return@launch
                }

                val feedbackUrl = deriveFeedbackUrl(endpoint) ?: run {
                    Log.w(TAG, "feedback skipped — could not derive URL from endpoint=$endpoint")
                    return@launch
                }

                // ISO-8601 timestamp with timezone, compatible with API 24+.
                // java.time was only added to Android in API 26 (or via
                // desugaring), so we stick with SimpleDateFormat to keep
                // minSdk=24 working without extra build-config changes.
                val isoFormatter = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    java.util.Locale.US,
                )
                val nowIso = isoFormatter.format(java.util.Date())

                val payload = JSONObject().apply {
                    put("request_id", requestId)
                    put("final", finalText)
                    put("submitted", submittedText)
                    put("ts", nowIso)
                    if (user.isNotBlank()) put("user", user)
                }

                val body: RequestBody = payload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build()

                val builder = Request.Builder().url(feedbackUrl).post(body)
                if (apiKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer $apiKey")
                }
                builder.header("Content-Type", "application/json; charset=utf-8")

                val resp = client.newCall(builder.build()).execute()
                resp.use {
                    if (it.isSuccessful) {
                        Log.d(TAG, "feedback ok rid=$requestId code=${it.code}")
                    } else {
                        Log.w(TAG, "feedback non-2xx rid=$requestId code=${it.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "feedback failed rid=$requestId: ${e.message}")
            }
        }
    }

    /**
     * Phase 7/8 (2026-05-08): hiragana → kanji candidates via proxy /kanji.
     *
     * Sends [hiragana] to the proxy and invokes [onResult] on the *main*
     * thread with up to 4 ranked candidates (best first; the original
     * hiragana is appended as a final fallback so the IME's 変換 cycle can
     * always return the user to their typed text).
     *
     * On any error, returns [listOf(hiragana)] so the UI degrades gracefully.
     *
     * Threading: launches a coroutine internally; safe to call from the
     * UI thread.
     */
    fun convertHiragana(
        context: Context,
        hiragana: String,
        onResult: (List<String>) -> Unit,
    ) {
        if (hiragana.isBlank()) {
            onResult(listOf(hiragana)); return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val candidates: List<String> = try {
                val cfg = context.dataStore.data.map { preferences: Preferences ->
                    Pair(
                        preferences[ENDPOINT] ?: "",
                        preferences[API_KEY] ?: "",
                    )
                }.first()
                val endpoint = cfg.first
                val apiKey = cfg.second
                if (endpoint.isBlank()) {
                    Log.d(TAG, "kanji skipped — endpoint unset")
                    listOf(hiragana)
                } else {
                    val kanjiUrl = deriveSiblingUrl(endpoint, "/kanji")
                    if (kanjiUrl == null) {
                        Log.w(TAG, "kanji skipped — could not derive URL from $endpoint")
                        listOf(hiragana)
                    } else {
                        val payload = JSONObject().apply {
                            put("hiragana", hiragana)
                        }
                        val body: RequestBody = payload.toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                        val client = OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .writeTimeout(10, TimeUnit.SECONDS)
                            .build()
                        val builder = Request.Builder().url(kanjiUrl).post(body)
                        if (apiKey.isNotBlank()) {
                            builder.header("Authorization", "Bearer $apiKey")
                        }
                        builder.header("Content-Type", "application/json; charset=utf-8")
                        val resp = client.newCall(builder.build()).execute()
                        resp.use {
                            if (it.isSuccessful) {
                                val responseBody = it.body?.string() ?: ""
                                try {
                                    val json = JSONObject(responseBody)
                                    val arr = json.optJSONArray("candidates")
                                    if (arr != null && arr.length() > 0) {
                                        val list = mutableListOf<String>()
                                        for (i in 0 until arr.length()) {
                                            val s = arr.optString(i, "").trim()
                                            if (s.isNotEmpty() && s !in list) list.add(s)
                                        }
                                        if (list.isEmpty()) listOf(hiragana) else list
                                    } else {
                                        // Fallback to legacy "primary" field if present.
                                        val primary = json.optString("primary", hiragana).trim()
                                        if (primary.isEmpty() || primary == hiragana) {
                                            listOf(hiragana)
                                        } else {
                                            listOf(primary, hiragana)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "kanji parse failed: ${e.message}")
                                    listOf(hiragana)
                                }
                            } else {
                                Log.w(TAG, "kanji non-2xx code=${it.code}")
                                listOf(hiragana)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "kanji failed: ${e.message}")
                listOf(hiragana)
            }
            withContext(Dispatchers.Main) {
                onResult(candidates)
            }
        }
    }

    /**
     * Replace the path of [endpoint] with [siblingPath], preserving scheme/host/port.
     * Used by /feedback and /kanji derivation. Returns null if the endpoint
     * is not a parseable absolute URL.
     */
    private fun deriveSiblingUrl(endpoint: String, siblingPath: String): String? {
        return try {
            val u = java.net.URI(endpoint.trim())
            if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) return null
            val portPart = if (u.port > 0) ":${u.port}" else ""
            val path = if (siblingPath.startsWith("/")) siblingPath else "/$siblingPath"
            "${u.scheme}://${u.host}$portPart$path"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replace the path of [endpoint] with /feedback, preserving scheme/host/port.
     * Returns null if the endpoint is not a parseable absolute URL.
     */
    private fun deriveFeedbackUrl(endpoint: String): String? {
        return try {
            val u = java.net.URI(endpoint.trim())
            if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) return null
            val portPart = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme}://${u.host}$portPart/feedback"
        } catch (_: Exception) {
            null
        }
    }
}
