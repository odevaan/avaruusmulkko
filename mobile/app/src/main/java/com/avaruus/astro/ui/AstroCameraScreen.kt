package com.avaruus.astro.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.avaruus.astro.camera.AstroMode

private val EXPOSURE_VALUES_SEC = listOf(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 8.0, 10.0, 15.0, 20.0, 25.0, 30.0)
private val ISO_VALUES = listOf(200, 700, 1600, 3200)
private val SHOT_COUNT_VALUES = listOf(1, 2, 5, 10, 15, 20, 25, 30, 40, 50, 60, 75, 100, 150, 200, 0) // 0 = unlimited

@Composable
fun AstroCameraScreen(viewModel: AstroViewModel) {
    val availableLenses by viewModel.availableLenses.collectAsState()
    val currentLens by viewModel.currentLens.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val seqState by viewModel.sequencerState.collectAsState()
    val capturedFiles by viewModel.capturedFiles.collectAsState()

    var isUiMinimized by remember { mutableStateOf(false) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }
    var cameraDropdownExpanded by remember { mutableStateOf(false) }
    var userTemporarilyWokeDimmer by remember { mutableStateOf(false) }

    // Sliders state
    var selectedShotsIndex by remember { mutableIntStateOf(5) } // default 20
    var delaySec by remember { mutableIntStateOf(2) }

    // Reset temporary dimmer wake when exposure finishes
    LaunchedEffect(isCapturing) {
        if (!isCapturing) {
            userTemporarilyWokeDimmer = false
        }
    }

    // If user selects VIEW mode, show the full-screen photo inspection screen
    if (settings.mode == AstroMode.VIEW) {
        AstroViewModeScreen(
            files = capturedFiles,
            onClose = { viewModel.setMode(AstroMode.LIGHT) }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstroBlack)
    ) {
        // 1. Full-screen Camera Viewfinder
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.onSurfaceReady(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.onSurfaceDestroyed()
                        }
                    })
                }
            }
        )

        // 2. Tap anywhere outside buttons/sliders to toggle UI visibility on/off
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    isUiMinimized = !isUiMinimized
                }
        )

        val shouldDimScreen = isCapturing && !userTemporarilyWokeDimmer

        // 3. Compact Sequence Status Pill when UI is hidden during an active sequence
        if (isUiMinimized && seqState.isRunning && !shouldDimScreen) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .background(AstroBlack.copy(alpha = 0.85f), RoundedCornerShape(24.dp))
                    .border(1.5.dp, AstroRed, RoundedCornerShape(24.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${settings.mode.name} ${seqState.currentFrameIndex}/${if (seqState.targetFrames == 0) "∞" else seqState.targetFrames}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { viewModel.stopSequencer() },
                    colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("STOP", color = AstroRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 4. Main Controls Overlay (when UI is visible)
        AnimatedVisibility(
            visible = !isUiMinimized,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // TOP BAR: Mode Selector Dropdown, Camera Dropdown, and Focus Loupe
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AstroBlack.copy(alpha = 0.88f), RoundedCornerShape(12.dp))
                        .border(1.dp, AstroRedDark.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mode Dropdown
                    Box {
                        Button(
                            onClick = { modeDropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AstroRedDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Text(
                                text = "${settings.mode.name} ▼",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        DropdownMenu(
                            expanded = modeDropdownExpanded,
                            onDismissRequest = { modeDropdownExpanded = false },
                            modifier = Modifier
                                .background(AstroSurfaceElevated)
                                .border(1.dp, AstroRed, RoundedCornerShape(8.dp))
                        ) {
                            AstroMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = mode.name,
                                                color = if (settings.mode == mode) AstroRed else Color.White,
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (settings.mode == mode) {
                                                Text("✓", color = AstroRed, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setMode(mode)
                                        modeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Camera / Optical Lens Dropdown Menu
                    Box {
                        val activeLens = currentLens
                        val label = activeLens?.name?.let { "$it ▼" } ?: "CAMERA ▼"
                        Button(
                            onClick = { cameraDropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = AstroRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = label,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        DropdownMenu(
                            expanded = cameraDropdownExpanded,
                            onDismissRequest = { cameraDropdownExpanded = false },
                            modifier = Modifier
                                .background(AstroSurfaceElevated)
                                .border(1.dp, AstroRed, RoundedCornerShape(8.dp))
                        ) {
                            availableLenses.forEach { lens ->
                                val isSelected = currentLens?.opticalZoomRatio == lens.opticalZoomRatio
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp, horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = lens.name,
                                                color = if (isSelected) AstroRed else Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Text("✓", color = AstroRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.selectLens(lens)
                                        cameraDropdownExpanded = false
                                    }
                                )
                                if (lens != availableLenses.last()) {
                                    HorizontalDivider(color = AstroSurface)
                                }
                            }
                        }
                    }

                    // In FOCUS mode only, show Loupe for manual star focus assist
                    if (settings.mode == AstroMode.FOCUS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1.0f, 5.0f, 10.0f).forEach { zoom ->
                                val isSel = settings.zoomFactor == zoom
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) AstroRedDark else AstroSurface)
                                        .border(1.dp, if (isSel) AstroRed else AstroTextDim, RoundedCornerShape(6.dp))
                                        .clickable { viewModel.setZoomLoupe(zoom) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "${zoom.toInt()}x",
                                        color = if (isSel) Color.White else AstroTextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // BOTTOM CONTROLS PANEL: Sliders & Actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AstroBlack.copy(alpha = 0.94f))
                        .border(1.5.dp, AstroRedDark.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. FOCUS ROW: Stepping buttons and Infinity Lock
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "FOCUS: ${"%.2f".format(settings.focusDistance)} ${if (settings.focusDistance == 0.0f) "(∞ LOCK)" else ""}",
                                color = if (settings.focusDistance == 0.0f) AstroRed else Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { viewModel.stepFocus(-0.10f) },
                                    modifier = Modifier.height(40.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("-0.10", color = AstroTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { viewModel.stepFocus(-0.02f) },
                                    modifier = Modifier.height(40.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("-0.02", color = AstroTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { viewModel.lockInfinityFocus() },
                                    modifier = Modifier.height(40.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (settings.focusDistance == 0.0f) AstroRedDark else AstroSurfaceElevated
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text("∞ LOCK", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                }
                                Button(
                                    onClick = { viewModel.stepFocus(+0.02f) },
                                    modifier = Modifier.height(40.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("+0.02", color = AstroTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { viewModel.stepFocus(+0.10f) },
                                    modifier = Modifier.height(40.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("+0.10", color = AstroTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // 2. EXPOSURE TIME SLIDER
                        val currentExpSec = settings.exposureTimeNs / 1_000_000_000.0
                        val currentExpIdx = EXPOSURE_VALUES_SEC.indexOfFirst { kotlin.math.abs(it - currentExpSec) < 0.05 }.coerceAtLeast(0)

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("EXPOSURE", color = AstroTextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("${"%.1f".format(currentExpSec)}s", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = currentExpIdx.toFloat(),
                                onValueChange = { idx ->
                                    val targetVal = EXPOSURE_VALUES_SEC[kotlin.math.round(idx).toInt().coerceIn(0, EXPOSURE_VALUES_SEC.size - 1)]
                                    viewModel.setExposureTimeNs((targetVal * 1_000_000_000L).toLong())
                                },
                                valueRange = 0f..(EXPOSURE_VALUES_SEC.size - 1).toFloat(),
                                steps = EXPOSURE_VALUES_SEC.size - 2,
                                colors = SliderDefaults.colors(
                                    thumbColor = AstroRed,
                                    activeTrackColor = AstroRedDark,
                                    inactiveTrackColor = AstroSurface
                                )
                            )
                        }

                        // 3. GAIN (ISO) SLIDER
                        val currentIsoIdx = ISO_VALUES.indexOf(settings.iso).let { if (it >= 0) it else 1 }

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("GAIN", color = AstroTextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                val isoLabel = when (settings.iso) {
                                    200 -> "ISO 200 (LCG / Wide DR)"
                                    700 -> "ISO 700 (HCG Sweet Spot)"
                                    1600 -> "ISO 1600 (Max Native)"
                                    3200 -> "ISO 3200 (Digital Boost)"
                                    else -> "ISO ${settings.iso}"
                                }
                                Text(isoLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = currentIsoIdx.toFloat(),
                                onValueChange = { idx ->
                                    val snappedIdx = kotlin.math.round(idx).toInt().coerceIn(0, ISO_VALUES.size - 1)
                                    viewModel.setIso(ISO_VALUES[snappedIdx])
                                },
                                valueRange = 0f..(ISO_VALUES.size - 1).toFloat(),
                                steps = ISO_VALUES.size - 2,
                                colors = SliderDefaults.colors(
                                    thumbColor = AstroRed,
                                    activeTrackColor = AstroRedDark,
                                    inactiveTrackColor = AstroSurface
                                )
                            )
                        }

                        // 4. SHOTS & DELAY SLIDERS ROW
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // SHOTS SLIDER
                            val targetCount = SHOT_COUNT_VALUES[selectedShotsIndex.coerceIn(0, SHOT_COUNT_VALUES.size - 1)]
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("SHOTS", color = AstroTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(if (targetCount == 0) "∞" else "$targetCount", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = selectedShotsIndex.toFloat(),
                                    onValueChange = { selectedShotsIndex = kotlin.math.round(it).toInt() },
                                    valueRange = 0f..(SHOT_COUNT_VALUES.size - 1).toFloat(),
                                    steps = SHOT_COUNT_VALUES.size - 2,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AstroRed,
                                        activeTrackColor = AstroRedDark,
                                        inactiveTrackColor = AstroSurface
                                    )
                                )
                            }

                            // DELAY SLIDER
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("DELAY", color = AstroTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("${delaySec}s", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = delaySec.toFloat(),
                                    onValueChange = { delaySec = kotlin.math.round(it).toInt() },
                                    valueRange = 0f..10f,
                                    steps = 9,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AstroRed,
                                        activeTrackColor = AstroRedDark,
                                        inactiveTrackColor = AstroSurface
                                    )
                                )
                            }
                        }

                        // 5. STATUS / PROGRESS BAR
                        if (seqState.isRunning) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AstroSurfaceElevated, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${seqState.currentFrameIndex} / ${if (seqState.targetFrames == 0) "∞" else seqState.targetFrames}",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "ETA: ${seqState.estimatedRemainingSeconds / 60}m ${seqState.estimatedRemainingSeconds % 60}s",
                                        color = AstroTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        if (seqState.targetFrames > 0) {
                                            seqState.currentFrameIndex.toFloat() / seqState.targetFrames.toFloat()
                                        } else 0.5f
                                    },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = AstroRed,
                                    trackColor = AstroSurface
                                )
                            }
                        }

                        // 6. BIG START / STOP BUTTON
                        val targetShots = SHOT_COUNT_VALUES[selectedShotsIndex.coerceIn(0, SHOT_COUNT_VALUES.size - 1)]
                        Button(
                            onClick = {
                                if (seqState.isRunning) {
                                    viewModel.stopSequencer()
                                } else {
                                    viewModel.startSequencer(targetShots, delaySec, null)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (seqState.isRunning) AstroSurfaceElevated else AstroRedDark
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = if (seqState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (seqState.isRunning) "STOP SEQUENCE" else "START ${settings.mode.name} SEQUENCE",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }

        // 5. Power-Saving Screen Dimmer while Camera is Exposing (top-most layer)
        if (shouldDimScreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AstroBlack.copy(alpha = 0.92f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        userTemporarilyWokeDimmer = true
                        isUiMinimized = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(AstroRed)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "EXPOSING FRAME",
                        color = AstroTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Screen dimmed for power saving",
                        color = AstroTextDim,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap to view controls",
                        color = AstroTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
