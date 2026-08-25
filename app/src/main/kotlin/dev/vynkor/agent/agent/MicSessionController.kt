package dev.vynkor.agent.agent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.vynkor.agent.Agent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * R-01 gate: host-streamed microphone audio exists ONLY inside an explicit,
 * user-initiated session. Nothing is captured or pushed until [startSession]
 * succeeds; a live session kills itself when the capture dies, after the idle
 * timeout without pushed chunks, or at the absolute duration cap.
 *
 * All methods are thread-safe, but stopping performs a bounded join — callers
 * should stay off the main thread (the chat UI dispatches to Dispatchers.Default).
 */
class MicSessionController(private val capture: MicCapture) {

    private val mutex = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mic-session-watchdog").apply { isDaemon = true }
    }

    @Volatile
    private var lastActivityMs = 0L
    private var sessionStartedAtMs = 0L
    private var watchdog: ScheduledFuture<*>? = null

    fun isActive(): Boolean = AgentHolder.micStreaming.value

    /**
     * Opens a mic→host streaming session for the currently connected agent.
     * Returns false (nothing started) when there is no live agent, no RECORD_AUDIO
     * permission, or the capture failed to initialize.
     */
    fun startSession(context: Context, source: String): Boolean {
        synchronized(mutex) {
            if (AgentHolder.micStreaming.value) return true
            val agent = AgentHolder.agent ?: return false
            lastActivityMs = SystemClock.elapsedRealtime()
            capture.onChunkPushed = { lastActivityMs = SystemClock.elapsedRealtime() }
            capture.start(agent, context)
            if (!capture.isRunning()) {
                capture.onChunkPushed = null
                return false
            }
            sessionStartedAtMs = SystemClock.elapsedRealtime()
            AgentHolder.micStreaming.value = true
            watchdog = scheduler.scheduleWithFixedDelay(
                ::enforceLimits, WATCHDOG_PERIOD_S, WATCHDOG_PERIOD_S, TimeUnit.SECONDS
            )
            Log.i(TAG, "mic session started (source=$source)")
            return true
        }
    }

    fun stopSession(reason: String) {
        synchronized(mutex) { stopLocked(reason) }
    }

    private fun enforceLimits() {
        synchronized(mutex) {
            if (!AgentHolder.micStreaming.value) return
            val now = SystemClock.elapsedRealtime()
            when {
                !capture.isRunning() -> stopLocked("capture exited unexpectedly")
                now - lastActivityMs >= IDLE_TIMEOUT_MS -> stopLocked("idle timeout")
                now - sessionStartedAtMs >= MAX_SESSION_MS -> stopLocked("duration cap")
            }
        }
    }

    /** Caller must hold [mutex]. */
    private fun stopLocked(reason: String) {
        capture.stop()
        capture.onChunkPushed = null
        watchdog?.cancel(false)
        watchdog = null
        AgentHolder.micStreaming.value = false
        Log.i(TAG, "mic session stopped ($reason)")
    }

    companion object {
        private const val TAG = "MicSession"
        private const val WATCHDOG_PERIOD_S = 15L

        /** Fuse 1: no chunk pushed for this long → close the session. */
        private const val IDLE_TIMEOUT_MS = 120_000L

        /** Fuse 2: hard cap on a single session. */
        private const val MAX_SESSION_MS = 5 * 60_000L
    }
}
