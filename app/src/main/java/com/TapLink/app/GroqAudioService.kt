package com.TapLinkX3.app

import android.content.Context
import android.media.MediaMetadataRetriever
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
    private var preferredAudioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private var consecutiveNoSpeechDetections: Int = 0
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
        outputFile = File.createTempFile("groq_recording_", ".m4a", context.cacheDir)
        val targetPath = outputFile?.absolutePath
        var lastError: Exception? = null
        for (source in candidateAudioSources()) {
            val recorder =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION") MediaRecorder()
                    }
            try {
                recorder.apply {
                    setAudioSource(source)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(targetPath)
                    prepare()
                    start()
                }
                mediaRecorder = recorder
                preferredAudioSource = source
                DebugLog.i(
                        "GroqAudioService",
                        "startRecording source=${audioSourceName(source)} noSpeechStreak=$consecutiveNoSpeechDetections"
                )
                recordingStartedAtMs = SystemClock.elapsedRealtime()
                isRecording = true
                scheduleMaxRecordingAbort()
                mainHandler.post { listener?.onRecordingStart() }
                return
            } catch (e: Exception) {
                lastError = e
                DebugLog.w(
                        "GroqAudioService",
                        "startRecording source=${audioSourceName(source)} failed: ${e.message}"
                )
                try {
                    recorder.release()
                } catch (_: Exception) {}
            }
        }
        releaseRecorder()
        outputFile?.let {
            try {
                it.delete()
            } catch (_: Exception) {}
        }
        outputFile = null
        mainHandler.post { listener?.onError("Failed to start recording: ${lastError?.message}") }
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
        var stopFailed = false
        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            // On many devices this means clip is too short/corrupt; do not upload.
            stopFailed = true
            DebugLog.w("GroqAudioService", "mediaRecorder.stop() failed: ${e.message}")
        } finally {
            releaseRecorder()
            isRecording = false
            mainHandler.post { listener?.onRecordingStop() }
        }

        val file = outputFile ?: run {
            mainHandler.post { listener?.onError("Recording failed: missing output file") }
            return
        }

        val elapsedMs = SystemClock.elapsedRealtime() - recordingStartedAtMs
        val mediaDurationMs = readMediaDurationMs(file)
        val durationMs = mediaDurationMs?.takeIf { it > 0 } ?: elapsedMs
        val sizeBytes = file.length()
        DebugLog.i(
                "GroqAudioService",
                "stopRecordingAndTranscribe file=${file.name} sizeBytes=$sizeBytes elapsedMs=$elapsedMs mediaDurationMs=${mediaDurationMs ?: -1} stopFailed=$stopFailed"
        )
        if (stopFailed ||
                !file.exists() ||
                sizeBytes < MIN_UPLOAD_BYTES ||
                durationMs < MIN_DURATION_MS
        ) {
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
                val fileSize = file.length()
                val fileDurationMs = readMediaDurationMs(file) ?: -1L
                DebugLog.i(
                        "GroqAudioService",
                        "stt_request file=${file.name} sizeBytes=$fileSize mediaDurationMs=$fileDurationMs language=$language source=${audioSourceName(preferredAudioSource)} noSpeechStreak=$consecutiveNoSpeechDetections"
                )
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
                        DebugLog.w(
                                "GroqAudioService",
                                "stt_response errorCode=${response.code} body=${responseBody.take(220)}"
                        )
                        mainHandler.post {
                            listener?.onError(
                                    "Groq STT API error: ${response.code}. ${responseBody.take(160)}"
                            )
                        }
                        return@use
                    }
                    val text = JSONObject(responseBody).optString("text", "").trim()
                    DebugLog.i(
                            "GroqAudioService",
                            "stt_response success text=\"${text.take(220)}\" raw=${responseBody.take(220)}"
                    )
                    if (text.isBlank()) {
                        mainHandler.post { listener?.onError("No text transcribed.") }
                    } else {
                        consecutiveNoSpeechDetections = 0
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

    private fun readMediaDurationMs(file: File): Long? {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            mmr.release()
            raw?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun candidateAudioSources(): List<Int> {
        val ordered =
                listOf(
                        preferredAudioSource,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.DEFAULT
                )
        return ordered.distinct()
    }

    private fun audioSourceName(source: Int): String =
            when (source) {
                MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                MediaRecorder.AudioSource.MIC -> "MIC"
                MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
                else -> source.toString()
            }

    fun reportNoSpeechDetected() {
        consecutiveNoSpeechDetections += 1
        if (preferredAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION &&
                consecutiveNoSpeechDetections >= 2
        ) {
            preferredAudioSource = MediaRecorder.AudioSource.MIC
            DebugLog.w(
                    "GroqAudioService",
                    "No-speech streak=$consecutiveNoSpeechDetections, switching preferred source to MIC"
            )
        } else {
            DebugLog.i(
                    "GroqAudioService",
                    "No-speech streak=$consecutiveNoSpeechDetections source=${audioSourceName(preferredAudioSource)}"
            )
        }
    }

    companion object {
        private const val PREFS = "TapLinkPrefs"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL_DEFAULT = "whisper-large-v3-turbo"
        private const val MIN_DURATION_MS = 1000L
        private const val MIN_UPLOAD_BYTES = 8L * 1024L
        /** Avoid endless recording if the user forgets to tap again to stop. */
        private const val MAX_RECORDING_DURATION_MS = 120_000L
        private const val MAX_UPLOAD_BYTES = 24L * 1024L * 1024L // Leave headroom for free tier.
    }
}
