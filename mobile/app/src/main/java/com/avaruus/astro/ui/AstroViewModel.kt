package com.avaruus.astro.ui

import android.app.Application
import android.os.Environment
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.avaruus.astro.camera.*
import com.avaruus.astro.sequencer.AstroSequencer
import com.avaruus.astro.sequencer.SequencerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AstroViewModel(application: Application) : AndroidViewModel(application) {

    val cameraManager = AstroCameraManager(application)
    val sequencer = AstroSequencer(application, cameraManager)

    val availableLenses: StateFlow<List<CameraLens>> = cameraManager.availableLenses
    val currentLens: StateFlow<CameraLens?> = cameraManager.currentLens
    val settings: StateFlow<CaptureSettings> = cameraManager.settings
    val isCapturing: StateFlow<Boolean> = cameraManager.isCapturing
    val lastCapturedFile: StateFlow<File?> = cameraManager.lastCapturedFile

    private val _capturedFiles = MutableStateFlow<List<File>>(emptyList())
    val capturedFiles: StateFlow<List<File>> = _capturedFiles.asStateFlow()

    val sequencerState: StateFlow<SequencerState> = sequencer.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SequencerState())

    private var activeSurface: Surface? = null

    init {
        refreshCapturedFiles()
    }

    fun onSurfaceReady(surface: Surface) {
        activeSurface = surface
        val lens = currentLens.value
        if (lens != null) {
            cameraManager.openCamera(lens.id, surface)
        }
    }

    fun onSurfaceDestroyed() {
        activeSurface = null
        cameraManager.closeCamera()
    }

    fun selectLens(lens: CameraLens) {
        activeSurface?.let { surface ->
            cameraManager.selectLens(lens, surface)
        }
    }

    fun setMode(mode: AstroMode) {
        val updated = if (mode.frameType != null) {
            settings.value.copy(mode = mode)
        } else if (mode == AstroMode.FOCUS) {
            // In FOCUS mode, ensure zoom loupe is at least 2x by default if at 1x
            settings.value.copy(
                mode = mode,
                zoomFactor = if (settings.value.zoomFactor <= 1.0f) 2.0f else settings.value.zoomFactor
            )
        } else {
            // VIEW mode
            refreshCapturedFiles()
            settings.value.copy(mode = mode)
        }
        cameraManager.updateSettings(updated)
    }

    fun setExposureTimeNs(ns: Long) {
        cameraManager.updateSettings(settings.value.copy(exposureTimeNs = ns))
    }

    fun setIso(iso: Int) {
        cameraManager.updateSettings(settings.value.copy(iso = iso))
    }

    fun setFocusDistance(distance: Float) {
        cameraManager.updateSettings(settings.value.copy(focusDistance = distance.coerceAtLeast(0.0f)))
    }

    fun lockInfinityFocus() {
        setFocusDistance(0.0f)
    }

    fun stepFocus(delta: Float) {
        val current = settings.value.focusDistance
        setFocusDistance(maxOf(0.0f, current + delta))
    }

    fun setZoomLoupe(zoom: Float) {
        cameraManager.updateSettings(settings.value.copy(zoomFactor = zoom))
    }

    fun refreshCapturedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val astroRoot = File(picturesDir, "AvaruusAstro")
            if (astroRoot.exists()) {
                val files = astroRoot.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() == "dng" }
                    .sortedByDescending { it.lastModified() }
                    .toList()
                _capturedFiles.value = files
            } else {
                _capturedFiles.value = emptyList()
            }
        }
    }

    fun startSequencer(targetFrames: Int, delaySec: Int, sessionName: String?) {
        val frameType = settings.value.mode.frameType ?: FrameType.LIGHT
        sequencer.startSequence(
            targetFrames = targetFrames,
            delayBetweenShotsSec = delaySec,
            frameType = frameType,
            sessionName = sessionName
        )
    }

    fun stopSequencer() {
        sequencer.stopSequence()
        refreshCapturedFiles()
    }

    override fun onCleared() {
        super.onCleared()
        sequencer.stopSequence()
        cameraManager.closeCamera()
    }
}
