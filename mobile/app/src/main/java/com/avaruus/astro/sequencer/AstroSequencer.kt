package com.avaruus.astro.sequencer

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.avaruus.astro.camera.AstroCameraManager
import com.avaruus.astro.camera.FrameType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "AstroSequencer"

data class SequencerState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val sessionName: String = "",
    val frameType: FrameType = FrameType.LIGHT,
    val currentFrameIndex: Int = 0,
    val targetFrames: Int = 10,
    val delayBetweenShotsSec: Int = 2,
    val statusMessage: String = "Ready",
    val recentFiles: List<File> = emptyList(),
    val totalSecondsElapsed: Long = 0L,
    val estimatedRemainingSeconds: Long = 0L
)

class AstroSequencer(
    private val context: Context,
    private val cameraManager: AstroCameraManager
) {
    private val _state = MutableStateFlow(SequencerState())
    val state: StateFlow<SequencerState> = _state.asStateFlow()

    private var sequencerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AvaruusAstro::SequencerWakeLock"
        )
    }

    fun startSequence(
        targetFrames: Int = 10,
        delayBetweenShotsSec: Int = 2,
        frameType: FrameType = FrameType.LIGHT,
        sessionName: String? = null
    ) {
        if (_state.value.isRunning) return

        val finalSessionName = sessionName?.ifBlank { null }
            ?: "Session_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"

        wakeLock?.acquire(4 * 60 * 60 * 1000L) // up to 4 hours max

        _state.value = SequencerState(
            isRunning = true,
            isPaused = false,
            sessionName = finalSessionName,
            frameType = frameType,
            currentFrameIndex = 0,
            targetFrames = targetFrames,
            delayBetweenShotsSec = delayBetweenShotsSec,
            statusMessage = "Starting sequence..."
        )

        sequencerJob = CoroutineScope(Dispatchers.Default).launch {
            runSequenceLoop()
        }
    }

    private suspend fun runSequenceLoop() {
        val target = _state.value.targetFrames
        val delaySec = _state.value.delayBetweenShotsSec
        val frameType = _state.value.frameType
        val session = _state.value.sessionName

        var index = 0
        val startTime = System.currentTimeMillis()

        try {
            while (currentCoroutineContext().isActive && _state.value.isRunning && (target == 0 || index < target)) {
                index++
                val expSec = cameraManager.settings.value.exposureTimeNs / 1_000_000_000.0

                _state.value = _state.value.copy(
                    currentFrameIndex = index,
                    statusMessage = "Exposing frame $index of ${if (target == 0) "∞" else target} (${"%.1f".format(expSec)}s)..."
                )

                // Trigger long exposure RAW DNG capture
                val captured = cameraManager.captureAstrophotoFrame(
                    sessionName = session,
                    frameIndex = index,
                    frameType = frameType
                )

                if (captured != null) {
                    val updatedList = (_state.value.recentFiles + captured).takeLast(10)
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val perFrame = if (index > 0) elapsed / index else (expSec + delaySec).toLong()
                    val remaining = if (target > 0) (target - index) * perFrame else 0L

                    _state.value = _state.value.copy(
                        recentFiles = updatedList,
                        totalSecondsElapsed = elapsed,
                        estimatedRemainingSeconds = remaining,
                        statusMessage = "Saved frame $index: ${captured.name}"
                    )
                } else {
                    Log.w(TAG, "Frame $index capture failed or timed out")
                    _state.value = _state.value.copy(
                        statusMessage = "Frame $index failed to capture"
                    )
                }

                // Settle delay between exposures (lets sensor cool and I/O buffer drain)
                if (currentCoroutineContext().isActive && _state.value.isRunning && (target == 0 || index < target) && delaySec > 0) {
                    for (sec in delaySec downTo 1) {
                        if (!_state.value.isRunning) break
                        _state.value = _state.value.copy(
                            statusMessage = "Cooling/Settle delay: ${sec}s..."
                        )
                        delay(1000)
                    }
                }
            }

            _state.value = _state.value.copy(
                isRunning = false,
                statusMessage = "Sequence completed: $index frames captured."
            )
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(
                isRunning = false,
                statusMessage = "Sequence stopped by user."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sequencer error", e)
            _state.value = _state.value.copy(
                isRunning = false,
                statusMessage = "Error: ${e.localizedMessage}"
            )
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    fun stopSequence() {
        _state.value = _state.value.copy(
            isRunning = false,
            statusMessage = "Stopping sequence..."
        )
        sequencerJob?.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }
}
