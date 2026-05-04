package com.TapLinkX3.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.util.ArrayDeque
import kotlin.math.hypot

/**
 * Temple-arm touch only (see [com.TapLinkX3.app.MainActivity.isTempleTouchDevice]):
 *
 * 1. **Hold** past long-press threshold → start voice; **release** → stop.  
 *    OEM firmware may intercept temple **hold** before events reach the app (common on X3 Pro) —
 *    use **triple-tap** for voice instead.
 *
 * 2. **Triple-tap** (three quick taps) → toggle voice (same as Voice button / Groq toggle).
 *
 * 3. **Double-tap** → mouse tap mode, applied after a short delay so a third tap can cancel and
 *    trigger voice instead.
 */
class TempleTouchVoiceHelper(
        context: Context,
        /** Groq / RayNeo voice: arms hold-to-talk and triple-tap voice (no-op when false). */
        private val shouldHandleVoiceHold: () -> Boolean,
        /** Hold past threshold — start recording / routing. */
        private val onHoldThresholdPassed: () -> Unit,
        /** After hold session started — finger lifted; stop if still recording. */
        private val onHoldReleased: () -> Unit,
        /** Two taps, no third within delay — toggle mouse tap mode. */
        private val onTempleDoubleTapMouse: () -> Unit,
        /** Three taps in window — toggle voice (caller may no-op if STT unavailable). */
        private val onTripleTapVoiceToggle: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var fingerDown = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var holdRunnable: Runnable? = null
    private var holdSessionStarted = false

    private val recentUpTimes = ArrayDeque<Long>(4)
    private var mouseDeferRunnable: Runnable? = null

    private val holdThresholdMs: Long
        get() = ViewConfiguration.getLongPressTimeout().toLong()

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDown = true
                holdSessionStarted = false
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                cancelHoldSchedule()
                if (!shouldHandleVoiceHold()) return
                holdRunnable = Runnable {
                    holdRunnable = null
                    if (!fingerDown || !shouldHandleVoiceHold()) return@Runnable
                    holdSessionStarted = true
                    cancelTapSequence()
                    onHoldThresholdPassed()
                }
                handler.postDelayed(holdRunnable!!, holdThresholdMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fingerDown || holdSessionStarted) return
                // Temple strips jitter; cancelling hold too eagerly prevented voice from starting.
                val slop = ViewConfiguration.get(appContext).scaledTouchSlop * 4
                if (hypot(ev.x - downX, ev.y - downY) > slop) {
                    cancelHoldSchedule()
                }
                if (ev.eventTime - downTime > holdThresholdMs + 200L) {
                    cancelHoldSchedule()
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                cancelHoldSchedule()
                fingerDown = false
                val started = holdSessionStarted
                holdSessionStarted = false
                if (started) {
                    onHoldReleased()
                } else {
                    handleTempleTapUp(ev.eventTime)
                }
            }
        }
    }

    private fun handleTempleTapUp(t: Long) {
        while (recentUpTimes.isNotEmpty() && t - recentUpTimes.first() > TRIPLE_MAX_WINDOW_MS) {
            recentUpTimes.removeFirst()
        }

        mouseDeferRunnable?.let { handler.removeCallbacks(it) }
        mouseDeferRunnable = null

        recentUpTimes.addLast(t)
        while (recentUpTimes.size > 3) recentUpTimes.removeFirst()

        if (recentUpTimes.size == 3 && isValidTriple(recentUpTimes)) {
            recentUpTimes.clear()
            onTripleTapVoiceToggle()
            return
        }

        if (recentUpTimes.size == 3) {
            recentUpTimes.removeFirst()
        }

        if (recentUpTimes.size == 2) {
            val first = recentUpTimes.first()
            val second = recentUpTimes.last()
            if (second - first <= MAX_TAP_GAP_MS * 2) {
                scheduleMouseToggleDeferred()
            }
        }
    }

    private fun isValidTriple(q: ArrayDeque<Long>): Boolean {
        if (q.size != 3) return false
        val a = q.toList()
        val t0 = a[0]
        val t1 = a[1]
        val t2 = a[2]
        return t2 - t0 <= TRIPLE_MAX_WINDOW_MS &&
                t1 - t0 <= MAX_TAP_GAP_MS &&
                t2 - t1 <= MAX_TAP_GAP_MS
    }

    private fun scheduleMouseToggleDeferred() {
        mouseDeferRunnable =
                Runnable {
                    mouseDeferRunnable = null
                    if (recentUpTimes.size == 2) {
                        recentUpTimes.clear()
                        onTempleDoubleTapMouse()
                    }
                }
        handler.postDelayed(mouseDeferRunnable!!, DOUBLE_MOUSE_DELAY_MS)
    }

    private fun cancelTapSequence() {
        recentUpTimes.clear()
        mouseDeferRunnable?.let { handler.removeCallbacks(it) }
        mouseDeferRunnable = null
    }

    fun cancelHoldSchedule() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
    }

    fun reset() {
        cancelHoldSchedule()
        cancelTapSequence()
        fingerDown = false
        holdSessionStarted = false
    }

    companion object {
        private const val TRIPLE_MAX_WINDOW_MS = 950L
        private const val MAX_TAP_GAP_MS = 380L
        private const val DOUBLE_MOUSE_DELAY_MS = 520L
    }
}
