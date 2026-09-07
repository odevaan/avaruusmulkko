package com.avaruus.astro.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.avaruus.astro.camera.AstroDefaults
import com.avaruus.astro.camera.AstroMode
import com.avaruus.astro.camera.CameraLens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstroCameraScreen(viewModel: AstroViewModel) {
    val availableLenses by viewModel.availableLenses.collectAsState()
    val currentLens by viewModel.currentLens.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val lastFile by viewModel.lastCapturedFile.collectAsState()
    val seqState by viewModel.sequencerState.collectAsState()
    val capturedFiles by viewModel.capturedFiles.collectAsState()

    var showOledBlanker by remember { mutableStateOf(false) }
    var targetFrames by remember { mutableIntStateOf(20) }
    var delaySec by remember { mutableIntStateOf(2) }
    var modeDropdownExpanded by remember { mutableStateOf(false) }

    // If user selects VIEW mode, show the ViewMode inspection screen
    if (settings.mode == AstroMode.VIEW) {
        AstroViewModeScreen(
            files = capturedFiles,
            onClose = { viewModel.setMode(AstroMode.LIGHT) }
        )
        return
    }

    // Auto-blank OLED screen during Dark frame sequences
    LaunchedEffect(seqState.isRunning, settings.mode) {
        if (seqState.isRunning && settings.mode == AstroMode.DARK) {
            showOledBlanker = true
        } else if (!seqState.isRunning) {
            showOledBlanker = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstroBlack)
    ) {
        // 1. Camera Viewfinder (SurfaceView)
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

        // 2. Astrophotography Controls Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Mode Dropdown & App Header
            Column(modifier = Modifier.fillMaxWidth()) {
                // Main Header Row with Large Font
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AstroBlack.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                        .border(1.dp, AstroRedDark.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AVARUUSMULKKO",
                            color = AstroTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Pixel 9 Pro XL • 50MP RAW",
                            color = AstroTextDim,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Mode Dropdown Selector Button (Large and easily clickable)
                    Box {
                        Button(
                            onClick = { modeDropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AstroRedDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                imageVector = when (settings.mode) {
                                    AstroMode.LIGHT -> Icons.Default.Star
                                    AstroMode.DARK -> Icons.Default.Brightness2
                                    AstroMode.BIAS -> Icons.Default.FlashOn
                                    AstroMode.FLAT -> Icons.Default.CropSquare
                                    AstroMode.FOCUS -> Icons.Default.Search
                                    AstroMode.VIEW -> Icons.Default.PhotoLibrary
                                },
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${settings.mode.name} ▼",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Dropdown Menu for Modes
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
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = mode.name,
                                                    color = if (settings.mode == mode) AstroRed else Color.White,
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (settings.mode == mode) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("✓", color = AstroRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Text(
                                                text = mode.description,
                                                color = AstroTextSecondary,
                                                fontSize = 13.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setMode(mode)
                                        modeDropdownExpanded = false
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                if (mode != AstroMode.values().last()) {
                                    Divider(color = AstroSurface)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Lens selector pills with Large Text: [ 0.5x UW | 1.0x WIDE 50MP | 5.0x TELE 48MP ]
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(availableLenses) { lens ->
                        val isSelected = currentLens?.id == lens.id
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AstroRedDark else AstroSurface.copy(alpha = 0.9f))
                                .border(1.5.dp, if (isSelected) AstroRed else AstroTextDim, RoundedCornerShape(8.dp))
                                .clickable { viewModel.selectLens(lens) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = lens.name,
                                fontSize = 15.sp,
                                color = if (isSelected) Color.White else AstroTextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Digital Zoom Loupe for Focus Assist (1x, 2x, 5x, 10x, 20x)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Loupe:",
                        color = AstroTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    listOf(1.0f, 2.0f, 5.0f, 10.0f, 20.0f).forEach { zoom ->
                        val isSel = settings.zoomFactor == zoom
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) AstroRedDark else AstroSurface.copy(alpha = 0.85f))
                                .border(1.dp, if (isSel) AstroRed else AstroTextDim, RoundedCornerShape(6.dp))
                                .clickable { viewModel.setZoomLoupe(zoom) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${zoom.toInt()}x",
                                color = if (isSel) Color.White else AstroTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Bottom Panel: Astro Controls, Focus Adjuster, and Sequencer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AstroBlack.copy(alpha = 0.92f))
                    .border(1.5.dp, AstroRedDark.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. FOCUS CONTROLS ROW (Large, easily visible in the dark!)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "FOCUS: ${"%.2f".format(settings.focusDistance)} diopters ${if (settings.focusDistance == 0.0f) "(∞ LOCK)" else ""}",
                                color = if (settings.focusDistance == 0.0f) AstroRed else Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Focus Step Buttons & Infinity Lock
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // -0.10 Coarse Step
                            Button(
                                onClick = { viewModel.stepFocus(-0.10f) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("-0.10", color = AstroTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            // -0.02 Fine Step
                            Button(
                                onClick = { viewModel.stepFocus(-0.02f) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("-0.02", color = AstroTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            // Big Infinity Lock Button
                            Button(
                                onClick = { viewModel.lockInfinityFocus() },
                                modifier = Modifier.weight(1.6f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (settings.focusDistance == 0.0f) AstroRedDark else AstroSurfaceElevated
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("∞ LOCK", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            }
                            // +0.02 Fine Step
                            Button(
                                onClick = { viewModel.stepFocus(+0.02f) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("+0.02", color = AstroTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            // +0.10 Coarse Step
                            Button(
                                onClick = { viewModel.stepFocus(+0.10f) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("+0.10", color = AstroTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // 2. EXPOSURE TIME PRESETS ROW (Large Font)
                    Column {
                        Text(
                            text = "Exposure Time: ${"%.1f".format(settings.exposureTimeNs / 1_000_000_000.0)}s",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AstroDefaults.EXPOSURE_PRESETS) { preset ->
                                val isSel = settings.exposureTimeNs == preset.durationNs
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) AstroRedDark else AstroSurfaceElevated)
                                        .border(1.dp, if (isSel) AstroRed else AstroTextDim, RoundedCornerShape(6.dp))
                                        .clickable { viewModel.setExposureTimeNs(preset.durationNs) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = preset.label,
                                        color = if (isSel) Color.White else AstroTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 3. ISO GAIN PRESETS ROW (Large Font)
                    Column {
                        Text(
                            text = "Gain: ISO ${settings.iso}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AstroDefaults.ISO_PRESETS) { iso ->
                                val isSel = settings.iso == iso
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) AstroRedDark else AstroSurfaceElevated)
                                        .border(1.dp, if (isSel) AstroRed else AstroTextDim, RoundedCornerShape(6.dp))
                                        .clickable { viewModel.setIso(iso) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "$iso",
                                        color = if (isSel) Color.White else AstroTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 4. SEQUENCER SETTINGS (Frame Count & Delay)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Frame Count
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Shots: ", color = AstroTextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            listOf(1, 10, 30, 60, 100).forEach { cnt ->
                                val isSel = targetFrames == cnt
                                Text(
                                    text = "$cnt ",
                                    color = if (isSel) AstroRed else AstroTextDim,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSel) FontWeight.Black else FontWeight.Normal,
                                    modifier = Modifier
                                        .clickable { targetFrames = cnt }
                                        .padding(horizontal = 4.dp)
                                )
                            }
                        }

                        // Settle Delay
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Delay: ", color = AstroTextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            listOf(1, 2, 5).forEach { d ->
                                val isSel = delaySec == d
                                Text(
                                    text = "${d}s ",
                                    color = if (isSel) AstroRed else AstroTextDim,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSel) FontWeight.Black else FontWeight.Normal,
                                    modifier = Modifier
                                        .clickable { delaySec = d }
                                        .padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }

                    // 5. SEQUENCER STATUS & PROGRESS BAR
                    if (seqState.isRunning) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AstroSurfaceElevated, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "FRAME ${seqState.currentFrameIndex} / ${if (seqState.targetFrames == 0) "∞" else seqState.targetFrames}",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "ETA: ${seqState.estimatedRemainingSeconds / 60}m ${seqState.estimatedRemainingSeconds % 60}s",
                                    color = AstroTextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = {
                                    if (seqState.targetFrames > 0) {
                                        seqState.currentFrameIndex.toFloat() / seqState.targetFrames.toFloat()
                                    } else 0.5f
                                },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = AstroRed,
                                trackColor = AstroSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = seqState.statusMessage,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                        }
                    }

                    // 6. BIG ACTION BUTTON: START / STOP SEQUENCE
                    Button(
                        onClick = {
                            if (seqState.isRunning) {
                                viewModel.stopSequencer()
                            } else {
                                viewModel.startSequencer(targetFrames, delaySec, null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (seqState.isRunning) AstroSurfaceElevated else AstroRedDark
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = if (seqState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
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

        // 3. Fullscreen OLED Screen Blanker for DARK Frame Mode
        if (showOledBlanker) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showOledBlanker = false },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "DARK FRAME MODE\nScreen OLED Blanked (Zero Light Leak)\nTap screen to reveal controls",
                    color = AstroTextDim,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
