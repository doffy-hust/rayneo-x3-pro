package com.TapLinkX3.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class GroqAudioService(private val context: Context) {
    interface TranscriptionListener {
        fun onTranscriptionResult(text: String)
        fun onError(message: String)
        fun onRecordingStart()
        fun onRecordingStop()
        fun onTranscribing()
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var recordingStartedAtMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: TranscriptionListener? = null
    private var maxRecordingAbortRunnable: Runnable? = null

    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()

    fun setListener(listener: TranscriptionListener) {
        this.listener = listener
    }

    fun isRecording(): Boolean = isRecording

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    fun setApiKey(key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_GROQ_API_KEY, key.trim())
                .apply()
    }

    fun getApiKey(): String? {
        val prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_GROQ_API_KEY, null)
                        ?.trim()
        if (!prefs.isNullOrBlank()) return prefs
        val buildConfigKey = BuildConfig.GROQ_API_KEY.trim()
        return buildConfigKey.takeIf { it.isNotBlank() }
    }

    fun startRecording() {
        if (isRecording) return
        try {
            outputFile = File.createTempFile("groq_recording_", ".m4a", context.cacheDir)
            mediaRecorder =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION") MediaRecorder()
                    }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile?.absolutePath)
                prepare()
                start()
            }
            recordingStartedAtMs = SystemClock.elapsedRealtime()
            isRecording = true
            scheduleMaxRecordingAbort()
            mainHandler.post { listener?.onRecordingStart() }
        } catch (e: Exception) {
            releaseRecorder()
            mainHandler.post { listener?.onError("Failed to start recording: ${e.message}") }
        }
    }

    fun currentAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun cancelRecording() {
        if (!isRecording) return
        cancelMaxRecordingAbort()
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        } finally {
            releaseRecorder()
            isRecording = false
            outputFile?.let {
                try {
                    it.delete()
                } catch (_: Exception) {}
            }
            mainHandler.post { listener?.onRecordingStop() }
        }
    }

    fun stopRecordingAndTranscribe(languageHint: String? = null) {
        if (!isRecording) return
        cancelMaxRecordingAbort()
        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
            // Stop may throw if recording is too short; we handle via file checks below.
        } finally {
            releaseRecorder()
            isRecording = false
            mainHandler.post { listener?.onRecordingStop() }
        }

        val file = outputFile ?: run {
            mainHandler.post { listener?.onError("Recording failed: missing output file") }
            return
        }

        val durationMs = SystemClock.elapsedRealtime() - recordingStartedAtMs
        val sizeBytes = file.length()
        if (!file.exists() || sizeBytes <= 512L || durationMs < MIN_DURATION_MS) {
            file.delete()
            mainHandler.post {
                listener?.onError(
                        "Clip was too short — speak for at least a second, then tap Voice again."
                )
            }
            return
        }
        if (sizeBytes > MAX_UPLOAD_BYTES) {
            file.delete()
            mainHandler.post {
                listener?.onError("Recording too large for current STT tier. Please keep it shorter.")
            }
            return
        }

        transcribeAudio(file, languageHint)
    }

    private fun scheduleMaxRecordingAbort() {
        cancelMaxRecordingAbort()
        maxRecordingAbortRunnable = Runnable {
            maxRecordingAbortRunnable = null
            if (isRecording) {
                stopRecordingAndTranscribe()
            }
        }
        mainHandler.postDelayed(maxRecordingAbortRunnable!!, MAX_RECORDING_DURATION_MS)
    }

    private fun cancelMaxRecordingAbort() {
        maxRecordingAbortRunnable?.let { mainHandler.removeCallbacks(it) }
        maxRecordingAbortRunnable = null
    }

    private fun releaseRecorder() {
        mediaRecorder?.release()
        mediaRecorder = null
    }

    private fun transcribeAudio(file: File, languageHint: String?) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            file.delete()
            mainHandler.post { listener?.onError("Missing GROQ API key.") }
            return
        }

        Thread {
            try {
                mainHandler.post { listener?.onTranscribing() }
                val language = languageHint ?: Locale.getDefault().language
                val requestBodyBuilder =
                        MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart(
                                        "file",
                                        file.name,
                                        file.asRequestBody("audio/m4a".toMediaType())
                                )
                                .addFormDataPart("model", MODEL_DEFAULT)
                                .addFormDataPart("response_format", "json")
                                .addFormDataPart("temperature", "0")
                if (language.isNotBlank()) {
                    requestBodyBuilder.addFormDataPart("language", language)
                }

                val request =
                        Request.Builder()
                                .url(TRANSCRIPTIONS_URL)
                                .addHeader("Authorization", "Bearer $apiKey")
                                .post(requestBodyBuilder.build())
                                .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        mainHandler.post {
                            listener?.onError(
                                    "Groq STT API error: ${response.code}. ${responseBody.take(160)}"
                            )
                        }
                        return@use
                    }
                    val text = JSONObject(responseBody).optString("text", "").trim()
                    if (text.isBlank()) {
                        mainHandler.post { listener?.onError("No text transcribed.") }
                    } else {
                        mainHandler.post { listener?.onTranscriptionResult(text) }
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { listener?.onError("Transcription failed: ${e.message}") }
            } finally {
                try {
                    file.delete()
                } catch (_: Exception) {}
            }
        }.start()
    }

    companion object {
        private const val PREFS = "TapLinkPrefs"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL_DEFAULT = "whisper-large-v3-turbo"
        private const val MIN_DURATION_MS = 1000L
        /** Avoid endless recording if the user forgets to tap again to stop. */
        private const val MAX_RECORDING_DURATION_MS = 120_000L
        private const val MAX_UPLOAD_BYTES = 24L * 1024L * 1024L // Leave headroom for free tier.
    }
}
