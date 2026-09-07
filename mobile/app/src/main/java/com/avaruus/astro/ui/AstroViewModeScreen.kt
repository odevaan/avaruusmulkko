package com.avaruus.astro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

@Composable
fun AstroViewModeScreen(
    files: List<File>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var fileList by remember(files) { mutableStateOf(files.toMutableList()) }
    var currentIndex by remember { mutableIntStateOf(0) }

    if (fileList.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(AstroBlack),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "No Captured Photos",
                    color = AstroTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = AstroRedDark),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Return to Capture", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    val currentFile = fileList.getOrNull(currentIndex.coerceIn(0, fileList.size - 1)) ?: fileList.first()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var stretchedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var histogramData by remember { mutableStateOf<HistogramData?>(null) }
    var metadata by remember { mutableStateOf<DngMetadata?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Display adjustments (only affects on-screen display, does not touch saved files)
    var isAutoStretched by remember { mutableStateOf(true) } // default ON for astrophotography inspection!
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var showHistogram by remember { mutableStateOf(true) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Pan & Zoom state
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offsetX by remember { mutableFloatStateOf(0.0f) }
    var offsetY by remember { mutableFloatStateOf(0.0f) }

    // Load file, metadata, and compute auto-stretch & histogram
    LaunchedEffect(currentFile) {
        isLoading = true
        scale = 1.0f
        offsetX = 0.0f
        offsetY = 0.0f
        rotationAngle = 0f

        withContext(Dispatchers.IO) {
            val meta = DngThumbnailLoader.extractMetadata(currentFile)
            val bmp = DngThumbnailLoader.loadBitmap(currentFile, maxDim = 3840)
            val hist = if (bmp != null) DngThumbnailLoader.computeHistogram(bmp) else null
            val stretched = if (bmp != null) DngThumbnailLoader.createAutoStretchedBitmap(bmp) else null

            withContext(Dispatchers.Main) {
                metadata = meta
                originalBitmap = bmp
                stretchedBitmap = stretched
                histogramData = hist
                isLoading = false
            }
        }
    }

    val displayedBitmap = if (isAutoStretched && stretchedBitmap != null) stretchedBitmap else originalBitmap

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AstroBlack)
    ) {
        // 1. Interactive Image Viewport (Pan, Zoom, Rotate)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.7f, 10.0f)
                        if (scale > 1.0f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.5f) {
                                scale = 1.0f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 3.0f // 1:1 pixel inspection
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = AstroRed,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(54.dp)
                )
            } else if (displayedBitmap != null) {
                Image(
                    bitmap = displayedBitmap.asImageBitmap(),
                    contentDescription = "Astrophotography Frame",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            rotationZ = rotationAngle,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }

        // 2. Top Header Bar: Close, Index, Rotate, Delete, AutoStretch, and Quick Zoom
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(AstroBlack.copy(alpha = 0.90f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // Row 1: Back, Index, Rotate, Delete Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Return to Capture Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(42.dp)
                        .background(AstroSurfaceElevated, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = AstroRed)
                }

                // Current index
                Text(
                    text = "${currentIndex + 1} / ${fileList.size}",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                // Image Manipulation Tools: Rotate -90°, Rotate +90°, Delete
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Rotate Counter-Clockwise (-90°)
                    IconButton(
                        onClick = { rotationAngle = (rotationAngle - 90f + 360f) % 360f },
                        modifier = Modifier
                            .size(42.dp)
                            .background(AstroSurfaceElevated, RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.RotateLeft, contentDescription = "Rotate -90°", tint = Color.White)
                    }

                    // Rotate Clockwise (+90°)
                    IconButton(
                        onClick = { rotationAngle = (rotationAngle + 90f) % 360f },
                        modifier = Modifier
                            .size(42.dp)
                            .background(AstroSurfaceElevated, RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate +90°", tint = Color.White)
                    }

                    // Delete Image Button
                    IconButton(
                        onClick = { showDeleteConfirmDialog = true },
                        modifier = Modifier
                            .size(42.dp)
                            .background(AstroRedDark, RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Frame", tint = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Row 2: Quick Zoom Presets & Auto-Stretch Dynamics Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Auto-Stretch Dynamic Range Toggle Button
                Button(
                    onClick = { isAutoStretched = !isAutoStretched },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAutoStretched) AstroRedDark else AstroSurfaceElevated
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isAutoStretched) "STRETCH: ON" else "STRETCH: OFF",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Quick Zoom: FIT
                Button(
                    onClick = {
                        scale = 1.0f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier.weight(0.9f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scale <= 1.1f) AstroRedDark else AstroSurfaceElevated
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("FIT", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                // Quick Zoom: 1:1 Tracking
                Button(
                    onClick = {
                        scale = 3.0f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier.weight(1.2f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scale in 2.8f..3.5f) AstroRedDark else AstroSurfaceElevated
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("1:1 (TRACK)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                // Quick Zoom: 200% Focus
                Button(
                    onClick = {
                        scale = 6.0f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier.weight(1.1f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scale > 4.5f) AstroRedDark else AstroSurfaceElevated
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("200%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. Bottom Controls Panel: Histogram, Metadata, and Prev/Next Navigation
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(AstroBlack.copy(alpha = 0.92f))
                .border(1.dp, AstroRedDark.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Histogram Graph Canvas
            if (showHistogram && histogramData != null) {
                val hist = histogramData!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HISTOGRAM (DYNAMICS)",
                            color = AstroTextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (isAutoStretched) "Display: Stretched" else "Display: Linear",
                            color = AstroTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .background(Color(0xFF0A0A0A), RoundedCornerShape(4.dp))
                            .border(0.5.dp, Color(0xFF222222), RoundedCornerShape(4.dp))
                    ) {
                        val canvasW = size.width
                        val canvasH = size.height
                        val maxVal = sqrt(hist.maxCount.toFloat()).coerceAtLeast(1f)

                        // Draw Luminance / RGB curves
                        val lumPath = Path()
                        val redPath = Path()
                        val greenPath = Path()
                        val bluePath = Path()

                        for (i in 0..255) {
                            val x = (i / 255f) * canvasW
                            // Use square-root scaling so faint stars & shadow details stand out
                            val yLum = canvasH - (sqrt(hist.lum[i].toFloat()) / maxVal) * (canvasH - 4f)
                            val yR = canvasH - (sqrt(hist.red[i].toFloat()) / maxVal) * (canvasH - 4f)
                            val yG = canvasH - (sqrt(hist.green[i].toFloat()) / maxVal) * (canvasH - 4f)
                            val yB = canvasH - (sqrt(hist.blue[i].toFloat()) / maxVal) * (canvasH - 4f)

                            if (i == 0) {
                                lumPath.moveTo(x, yLum)
                                redPath.moveTo(x, yR)
                                greenPath.moveTo(x, yG)
                                bluePath.moveTo(x, yB)
                            } else {
                                lumPath.lineTo(x, yLum)
                                redPath.lineTo(x, yR)
                                greenPath.lineTo(x, yG)
                                bluePath.lineTo(x, yB)
                            }
                        }

                        drawPath(redPath, Color(0xFFFF3333).copy(alpha = 0.5f), style = Stroke(width = 1.2f))
                        drawPath(greenPath, Color(0xFF33FF33).copy(alpha = 0.5f), style = Stroke(width = 1.2f))
                        drawPath(bluePath, Color(0xFF3388FF).copy(alpha = 0.5f), style = Stroke(width = 1.2f))
                        drawPath(lumPath, Color(0xFFEEEEEE), style = Stroke(width = 1.6f))
                    }
                }
            }

            // Metadata readout
            metadata?.let { meta ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = meta.file.name,
                        color = AstroTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${meta.exposureTimeStr} • ${meta.isoStr} • ${meta.dimensions}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Navigation: PREV & NEXT Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (currentIndex > 0) currentIndex--
                    },
                    enabled = currentIndex > 0,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AstroSurfaceElevated,
                        disabledContainerColor = AstroSurface.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = AstroRed)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PREV",
                        color = if (currentIndex > 0) Color.White else AstroTextDim,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        if (currentIndex < fileList.size - 1) currentIndex++
                    },
                    enabled = currentIndex < fileList.size - 1,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AstroSurfaceElevated,
                        disabledContainerColor = AstroSurface.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "NEXT",
                        color = if (currentIndex < fileList.size - 1) Color.White else AstroTextDim,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = AstroRed)
                }
            }
        }

        // 4. Delete Confirmation Dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = {
                    Text("Delete Image?", color = AstroRed, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Text(
                        "Delete ${currentFile.name} from storage?\nThis action cannot be undone.",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirmDialog = false
                            val fileToDelete = currentFile
                            fileToDelete.delete()
                            val updated = fileList.toMutableList()
                            updated.remove(fileToDelete)
                            fileList = updated
                            if (currentIndex >= updated.size && updated.isNotEmpty()) {
                                currentIndex = updated.size - 1
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AstroRedDark)
                    ) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showDeleteConfirmDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = AstroSurfaceElevated)
                    ) {
                        Text("Cancel", color = Color.LightGray)
                    }
                },
                containerColor = AstroSurfaceElevated,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
