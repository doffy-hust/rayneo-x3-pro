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
        /** Called before MediaRecorder starts so device-specific mic routing can settle. */
        private val onPrepareAudioCapture: () -> Unit = {},
        /** Highlight Voice control while the mic session is active (user-visible recording only). */
        private val onRecordingUiActive: (Boolean) -> Unit = {},
) : VoiceInputController {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingArmWakeRouting = true
    private var wakeLoopEnabled = false
    private var wakeChunkInFlight = false
    private var manualCaptureActive = false
    private var manualSpeechStarted = false
    private var manualLastVoiceAt = 0L
    private var manualFirstSpeechAt = 0L
    private var manualCaptureStartedAt = 0L
    private var manualMaxAmplitude = 0
    private var commandCaptureActive = false
    private var commandSpeechStarted = false
    private var commandLastVoiceAt = 0L
    private var commandFirstSpeechAt = 0L
    private var commandCaptureStartedAt = 0L
    private var commandMaxAmplitude = 0
    private var lastListenOptions = VoiceListenOptions()

    override fun isActivelyRecording(): Boolean = groqAudioService.isRecording()

    override fun supportsTempleHoldToTalk(): Boolean = groqAudioService.hasApiKey()

    override fun startListening(armWakeRouting: Boolean, options: VoiceListenOptions) {
        lastListenOptions = options
        pendingArmWakeRouting = armWakeRouting
        if (groqAudioService.isRecording()) {
            if (manualCaptureActive || commandCaptureActive) {
                resetManualCaptureState()
                resetCommandCaptureState()
                toast("Transcribing…", TOAST_SHORT_MS)
                groqAudioService.stopRecordingAndTranscribe()
                return
            }
            toast("Listening...", TOAST_SHORT_MS)
            return
        }
        if (!groqAudioService.hasApiKey()) {
            toast("Add GROQ_API_KEY in local.properties, rebuild, then try Voice again.")
            return
        }
        resetManualCaptureState()
        manualCaptureActive = true
        manualSpeechStarted = false
        manualLastVoiceAt = 0L
        manualFirstSpeechAt = 0L
        manualMaxAmplitude = 0
        startPreparedRecording(shouldStart = { manualCaptureActive }) {
            manualCaptureStartedAt = SystemClock.uptimeMillis()
            monitorManualSilence()
        }
    }

    override fun startForegroundWakeListening() {
        if (wakeLoopEnabled) return
        wakeLoopEnabled = true
        scheduleWakeChunk(0L)
    }

    override fun stopForegroundWakeListening() {
        wakeLoopEnabled = false
        wakeChunkInFlight = false
        resetManualCaptureState()
        resetCommandCaptureState()
        uiHandler.removeCallbacksAndMessages(WAKE_TOKEN)
        uiHandler.removeCallbacksAndMessages(MANUAL_TOKEN)
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
                    startPreparedRecording(
                            shouldStart = {
                                wakeLoopEnabled && wakeChunkInFlight && !commandCaptureActive
                            }
                    ) {
                        uiHandler.postAtTime(
                                {
                                    if (wakeChunkInFlight && groqAudioService.isRecording()) {
                                        groqAudioService.stopRecordingAndTranscribe()
                                    }
                                },
                                WAKE_TOKEN,
                                SystemClock.uptimeMillis() + WAKE_CHUNK_MS
                        )
                    }
                },
                WAKE_TOKEN,
                SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun startCommandCaptureAfterWake() {
        resetCommandCaptureState()
        commandCaptureActive = true
        commandCaptureStartedAt = SystemClock.uptimeMillis()
        commandMaxAmplitude = 0
        pendingArmWakeRouting = true
        toast("Heard \"AIZ\" — say your command, then pause.")
        if (!groqAudioService.isRecording()) {
            startPreparedRecording(shouldStart = { commandCaptureActive }) { monitorCommandSilence() }
        } else {
            monitorCommandSilence()
        }
    }

    private fun startPreparedRecording(
            shouldStart: () -> Boolean = { true },
            afterStart: () -> Unit = {}
    ) {
        onPrepareAudioCapture()
        uiHandler.postDelayed(
                {
                    if (!shouldStart()) return@postDelayed
                    if (!groqAudioService.isRecording()) {
                        groqAudioService.startRecording()
                    }
                    afterStart()
                },
                AUDIO_ROUTE_SETTLE_MS
        )
    }

    private fun monitorManualSilence() {
        uiHandler.postAtTime(
                {
                    if (!manualCaptureActive || !groqAudioService.isRecording()) return@postAtTime
                    val amp = groqAudioService.currentAmplitude()
                    val now = SystemClock.uptimeMillis()
                    if (amp > manualMaxAmplitude) manualMaxAmplitude = amp
                    if (amp >= MANUAL_AMPLITUDE_VOICE_THRESHOLD) {
                        if (!manualSpeechStarted) {
                            manualSpeechStarted = true
                            manualFirstSpeechAt = now
                            DebugLog.i("VoiceRouting", "manual speech detected amp=$amp")
                        }
                        manualLastVoiceAt = now
                    }
                    if (manualSpeechStarted &&
                                    manualLastVoiceAt > 0L &&
                                    now - manualLastVoiceAt >= MANUAL_SILENCE_STOP_MS
                    ) {
                        resetManualCaptureState()
                        toast("Transcribing…", TOAST_SHORT_MS)
                        groqAudioService.stopRecordingAndTranscribe()
                        return@postAtTime
                    }
                    if (manualSpeechStarted &&
                                    manualFirstSpeechAt > 0L &&
                                    now - manualFirstSpeechAt >= MANUAL_MAX_AFTER_SPEECH_MS
                    ) {
                        resetManualCaptureState()
                        toast("Transcribing…", TOAST_SHORT_MS)
                        groqAudioService.stopRecordingAndTranscribe()
                        return@postAtTime
                    }
                    if (!manualSpeechStarted &&
                                    manualCaptureStartedAt > 0L &&
                                    now - manualCaptureStartedAt >= MANUAL_NO_SPEECH_STOP_MS
                    ) {
                        DebugLog.i(
                                "VoiceRouting",
                                "manual amplitude timeout; transcribing fallback maxAmp=$manualMaxAmplitude"
                        )
                        resetManualCaptureState()
                        groqAudioService.reportNoSpeechDetected()
                        toast("Checking audio…", TOAST_SHORT_MS)
                        groqAudioService.stopRecordingAndTranscribe()
                        return@postAtTime
                    }
                    monitorManualSilence()
                },
                MANUAL_TOKEN,
                SystemClock.uptimeMillis() + COMMAND_MONITOR_TICK_MS
        )
    }

    private fun monitorCommandSilence() {
        uiHandler.postAtTime(
                {
                    if (!commandCaptureActive || !groqAudioService.isRecording()) return@postAtTime
                    val amp = groqAudioService.currentAmplitude()
                    val now = SystemClock.uptimeMillis()
                    if (amp > commandMaxAmplitude) commandMaxAmplitude = amp
                    if (amp >= AMPLITUDE_VOICE_THRESHOLD) {
                        if (!commandSpeechStarted) {
                            commandSpeechStarted = true
                            commandFirstSpeechAt = now
                            DebugLog.i("VoiceRouting", "command speech detected amp=$amp")
                        }
                        commandLastVoiceAt = now
                    }
                    if (commandSpeechStarted &&
                                    commandLastVoiceAt > 0L &&
                                    now - commandLastVoiceAt >= COMMAND_SILENCE_STOP_MS
                    ) {
                        resetCommandCaptureState()
                        toast("Transcribing…", TOAST_SHORT_MS)
                        groqAudioService.stopRecordingAndTranscribe()
                        return@postAtTime
                    }
                    if (commandSpeechStarted &&
                                    commandFirstSpeechAt > 0L &&
                                    now - commandFirstSpeechAt >= COMMAND_MAX_AFTER_SPEECH_MS
                    ) {
                        resetCommandCaptureState()
                        toast("Transcribing…", TOAST_SHORT_MS)
                        groqAudioService.stopRecordingAndTranscribe()
                        return@postAtTime
                    }
                    if (!commandSpeechStarted &&
                                    commandCaptureStartedAt > 0L &&
                                    now - commandCaptureStartedAt >= COMMAND_NO_SPEECH_STOP_MS
                    ) {
                        DebugLog.i(
                                "VoiceRouting",
                                "command amplitude timeout; transcribing fallback maxAmp=$commandMaxAmplitude"
                        )
                        resetCommandCaptureState()
                        groqAudioService.reportNoSpeechDetected()
                        toast("Checking audio…", TOAST_SHORT_MS)
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
                        val wasCommandMode = commandCaptureActive || pendingArmWakeRouting
                        resetManualCaptureState()
                        resetCommandCaptureState()
                        val normalized = normalizeVoiceText(text)
                        val wakeDetected =
                                normalized.contains("hey aiz") || normalized.startsWith("aiz")

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
                        resetManualCaptureState()
                        resetCommandCaptureState()
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
                                    "Listening. Stop speaking for 1-2 seconds.",
                                    TOAST_LISTENING_MS
                            )
                        }
                    }

                    override fun onRecordingStop() {
                        resetManualCaptureState()
                        resetCommandCaptureState()
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

    private fun resetManualCaptureState() {
        manualCaptureActive = false
        manualSpeechStarted = false
        manualLastVoiceAt = 0L
        manualFirstSpeechAt = 0L
        manualCaptureStartedAt = 0L
        manualMaxAmplitude = 0
        uiHandler.removeCallbacksAndMessages(MANUAL_TOKEN)
    }

    private fun resetCommandCaptureState() {
        commandCaptureActive = false
        commandSpeechStarted = false
        commandLastVoiceAt = 0L
        commandFirstSpeechAt = 0L
        commandCaptureStartedAt = 0L
        commandMaxAmplitude = 0
        uiHandler.removeCallbacksAndMessages(WAKE_TOKEN)
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
        private val MANUAL_TOKEN = Any()
        private const val WAKE_CHUNK_MS = 1800L
        private const val WAKE_CHUNK_COOLDOWN_MS = 250L
        private const val COMMAND_MONITOR_TICK_MS = 180L
        private const val AUDIO_ROUTE_SETTLE_MS = 150L
        private const val MANUAL_NO_SPEECH_STOP_MS = 6000L
        private const val MANUAL_SILENCE_STOP_MS = 1500L
        private const val MANUAL_MAX_AFTER_SPEECH_MS = 15000L
        private const val MANUAL_AMPLITUDE_VOICE_THRESHOLD = 80
        private const val COMMAND_SILENCE_STOP_MS = 1500L
        private const val COMMAND_NO_SPEECH_STOP_MS = 6000L
        private const val COMMAND_MAX_AFTER_SPEECH_MS = 8000L
        private const val AMPLITUDE_VOICE_THRESHOLD = 120
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
