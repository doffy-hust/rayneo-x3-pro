package com.TapLinkX3.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.Normalizer

/**
 * Options for a single listen session (retries, UX toasts, wake-word second pass).
 *
 * Phase A uses Android [android.speech.SpeechRecognizer] inside [MainActivity]. Phase B can add an
 * implementation backed by [android.media.AudioRecord] + STT HTTP or a vendor SDK; Phase C may
 * prefer RayNeo OEM APIs when available.
 */
data class VoiceListenOptions(
        val retryPhase: Int = 0,
        val showTriggerToast: Boolean = true,
        val isSecondPassAfterWake: Boolean = false,
        /** When true, Groq skips the long "Listening…" overlay (caller showed a temple-specific hint). */
        val suppressRecordingHintToast: Boolean = false,
)

/** Pluggable voice/STT backend; default wiring lives in [MainActivity]. */
interface VoiceInputController {
    fun startListening(armWakeRouting: Boolean, options: VoiceListenOptions = VoiceListenOptions())

    fun startForegroundWakeListening() {}

    fun stopForegroundWakeListening() {}

    /** True while actively capturing audio (Groq) or native listen session is running. */
    fun isActivelyRecording(): Boolean = false

    /** Temple strip hold-to-talk is supported (RayNeo Groq path). */
    fun supportsTempleHoldToTalk(): Boolean = false

    fun release()
}

/**
 * Groq-backed voice controller:
 * - tap to start recording, tap again to stop and transcribe
 * - optional always-on foreground wake loop ("hey aiz") using short Groq chunks
 * - after wake detection, command recording auto-stops after 1.5s silence.
 */
class GroqVoiceInputController(
        private val groqAudioService: GroqAudioService,
        /** Message and duration (ms) for in-app overlay toast. */
        private val onToast: (String, Long) -> Unit,
        private val onTranscript: (String, Boolean) -> Unit,
        /** Highlight Voice control while the mic session is active (user-visible recording only). */
        private val onRecordingUiActive: (Boolean) -> Unit = {},
) : VoiceInputController {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingArmWakeRouting = true
    private var wakeLoopEnabled = false
    private var wakeChunkInFlight = false
    private var commandCaptureActive = false
    private var commandSpeechStarted = false
    private var commandLastVoiceAt = 0L
    private var lastListenOptions = VoiceListenOptions()

    override fun isActivelyRecording(): Boolean = groqAudioService.isRecording()

    override fun supportsTempleHoldToTalk(): Boolean = groqAudioService.hasApiKey()

    override fun startListening(armWakeRouting: Boolean, options: VoiceListenOptions) {
        lastListenOptions = options
        pendingArmWakeRouting = armWakeRouting
        if (groqAudioService.isRecording()) {
            groqAudioService.stopRecordingAndTranscribe()
            return
        }
        if (!groqAudioService.hasApiKey()) {
            toast("Add GROQ_API_KEY in local.properties, rebuild, then try Voice again.")
            return
        }
        groqAudioService.startRecording()
    }

    override fun startForegroundWakeListening() {
        if (wakeLoopEnabled) return
        wakeLoopEnabled = true
        scheduleWakeChunk(0L)
    }

    override fun stopForegroundWakeListening() {
        wakeLoopEnabled = false
        wakeChunkInFlight = false
        commandCaptureActive = false
        commandSpeechStarted = false
        uiHandler.removeCallbacksAndMessages(WAKE_TOKEN)
        if (groqAudioService.isRecording()) {
            groqAudioService.cancelRecording()
        }
    }

    private fun scheduleWakeChunk(delayMs: Long) {
        if (!wakeLoopEnabled || commandCaptureActive || wakeChunkInFlight) return
        uiHandler.postAtTime(
                {
                    if (!wakeLoopEnabled || commandCaptureActive || wakeChunkInFlight) return@postAtTime
                    wakeChunkInFlight = true
                    pendingArmWakeRouting = true
                    groqAudioService.startRecording()
                    uiHandler.postAtTime(
                            {
                                if (wakeChunkInFlight && groqAudioService.isRecording()) {
                                    groqAudioService.stopRecordingAndTranscribe()
                                }
                            },
                            WAKE_TOKEN,
                            SystemClock.uptimeMillis() + WAKE_CHUNK_MS
                    )
                },
                WAKE_TOKEN,
                SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun startCommandCaptureAfterWake() {
        commandCaptureActive = true
        commandSpeechStarted = false
        commandLastVoiceAt = 0L
        pendingArmWakeRouting = true
        toast("Heard \"AIZ\" — say your command, then pause.")
        if (!groqAudioService.isRecording()) {
            groqAudioService.startRecording()
        }
        monitorCommandSilence()
    }

    private fun monitorCommandSilence() {
        uiHandler.postAtTime(
                {
                    if (!commandCaptureActive || !groqAudioService.isRecording()) return@postAtTime
                    val amp = groqAudioService.currentAmplitude()
                    val now = SystemClock.uptimeMillis()
                    if (amp >= AMPLITUDE_VOICE_THRESHOLD) {
                        commandSpeechStarted = true
                        commandLastVoiceAt = now
                    }
                    if (commandSpeechStarted &&
                                    commandLastVoiceAt > 0L &&
                                    now - commandLastVoiceAt >= COMMAND_SILENCE_STOP_MS
                    ) {
                        commandCaptureActive = false
                        toast("Transcribing…", TOAST_SHORT_MS)
                        groqAudioService.stopRecordingAndTranscribe()
                        return@postAtTime
                    }
                    if (commandSpeechStarted && now - commandLastVoiceAt >= COMMAND_MAX_AFTER_SPEECH_MS) {
                        commandCaptureActive = false
                        toast("Transcribing…", TOAST_SHORT_MS)
                        groqAudioService.stopRecordingAndTranscribe()
                        return@postAtTime
                    }
                    monitorCommandSilence()
                },
                WAKE_TOKEN,
                SystemClock.uptimeMillis() + COMMAND_MONITOR_TICK_MS
        )
    }

    init {
        groqAudioService.setListener(
                object : GroqAudioService.TranscriptionListener {
                    override fun onTranscriptionResult(text: String) {
                        val normalized = normalizeVoiceText(text)
                        val wakeDetected =
                                normalized.contains("hey aiz") || normalized.startsWith("aiz")
                        val wasCommandMode = commandCaptureActive || pendingArmWakeRouting

                        if (wakeLoopEnabled && !commandCaptureActive && wakeDetected) {
                            wakeChunkInFlight = false
                            startCommandCaptureAfterWake()
                            return
                        }

                        wakeChunkInFlight = false
                        if (text.isNotBlank()) {
                            onTranscript(text, wasCommandMode)
                        }
                        if (wakeLoopEnabled && !commandCaptureActive) {
                            scheduleWakeChunk(WAKE_CHUNK_COOLDOWN_MS)
                        }
                    }

                    override fun onError(message: String) {
                        wakeChunkInFlight = false
                        commandCaptureActive = false
                        onRecordingUiActive(false)
                        toast(message)
                        if (wakeLoopEnabled) {
                            scheduleWakeChunk(WAKE_CHUNK_COOLDOWN_MS)
                        }
                    }

                    override fun onRecordingStart() {
                        if (!wakeChunkInFlight) {
                            onRecordingUiActive(true)
                        }
                        if (!wakeChunkInFlight &&
                                        !commandCaptureActive &&
                                        !lastListenOptions.suppressRecordingHintToast
                        ) {
                            toast(
                                    "Listening… Tap Voice or temple again when you're done.",
                                    TOAST_LISTENING_MS
                            )
                        }
                    }

                    override fun onRecordingStop() {
                        onRecordingUiActive(false)
                    }

                    override fun onTranscribing() {
                        if (!wakeChunkInFlight) {
                            toast("Transcribing…", TOAST_SHORT_MS)
                        }
                    }
                }
        )
    }

    override fun release() {
        stopForegroundWakeListening()
        onRecordingUiActive(false)
    }

    private fun toast(message: String, durationMs: Long = TOAST_DEFAULT_MS) {
        onToast(message, durationMs)
    }

    private fun normalizeVoiceText(text: String): String {
        val lower = text.lowercase().trim()
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return decomposed
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }

    companion object {
        private val WAKE_TOKEN = Any()
        private const val WAKE_CHUNK_MS = 1800L
        private const val WAKE_CHUNK_COOLDOWN_MS = 250L
        private const val COMMAND_MONITOR_TICK_MS = 180L
        private const val COMMAND_SILENCE_STOP_MS = 1500L
        private const val COMMAND_MAX_AFTER_SPEECH_MS = 8000L
        private const val AMPLITUDE_VOICE_THRESHOLD = 1200
        private const val TOAST_DEFAULT_MS = 2200L
        private const val TOAST_SHORT_MS = 1600L
        private const val TOAST_LISTENING_MS = 4500L
    }
}

/** Placeholder for a future direct PCM/AudioRecord implementation. */
@Suppress("unused")
class StubAudioRecordVoiceInputController : VoiceInputController {
    override fun startListening(armWakeRouting: Boolean, options: VoiceListenOptions) {
        // Intentionally empty — replace body when AudioRecord + STT or OEM ASR is wired.
    }

    override fun release() {}

    override fun isActivelyRecording(): Boolean = false

    override fun supportsTempleHoldToTalk(): Boolean = false
}

/**
 * Fallback backend for platforms where native SpeechRecognizer is known to fail for third-party
 * apps. This keeps UX deterministic while cloud/vendor STT is being integrated.
 */
class UnavailableVoiceInputController(
        private val onUnavailable: (String) -> Unit
) : VoiceInputController {
    override fun startListening(armWakeRouting: Boolean, options: VoiceListenOptions) {
        onUnavailable(
                "Voice input is unavailable on this device firmware. Use keyboard now, or configure cloud/vendor STT."
        )
    }

    override fun release() {}

    override fun isActivelyRecording(): Boolean = false

    override fun supportsTempleHoldToTalk(): Boolean = false
}
