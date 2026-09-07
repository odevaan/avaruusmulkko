package com.avaruus.astro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AstroViewModeScreen(
    files: List<File>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (files.isEmpty()) {
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
                    text = "No Captured Photos Yet",
                    color = AstroTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Capture frames in LIGHT, DARK, or FLAT mode, then return to VIEW to inspect focus and tracking.",
                    color = AstroTextSecondary,
                    fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = AstroRedDark),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text("Return to Capture", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    val currentFile = files.getOrNull(currentIndex) ?: files.first()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var metadata by remember { mutableStateOf<DngMetadata?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Pan & Zoom state
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offsetX by remember { mutableFloatStateOf(0.0f) }
    var offsetY by remember { mutableFloatStateOf(0.0f) }

    val coroutineScope = rememberCoroutineScope()

    // Reload image and metadata whenever currentIndex changes
    LaunchedEffect(currentIndex, currentFile) {
        isLoading = true
        scale = 1.0f
        offsetX = 0.0f
        offsetY = 0.0f

        withContext(Dispatchers.IO) {
            val meta = DngThumbnailLoader.extractMetadata(currentFile)
            val bmp = DngThumbnailLoader.loadBitmap(currentFile, maxDim = 3840)
            withContext(Dispatchers.Main) {
                metadata = meta
                bitmap = bmp
                isLoading = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AstroBlack)
    ) {
        // Main Image Display with Pan & Zoom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.8f, 10.0f)
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
                            // Double-tap toggles between 1.0x (Fit) and 3.0x (100% 1:1 Pixel Inspection)
                            if (scale > 1.5f) {
                                scale = 1.0f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 3.0f
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
                    modifier = Modifier.size(56.dp)
                )
            } else if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Captured Astrophotography Frame",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            } else {
                Text(
                    text = "Could not decode frame",
                    color = AstroTextPrimary,
                    fontSize = 18.sp
                )
            }
        }

        // Overlay: Top Header with Back Button and Quick-Zoom Presets
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(AstroBlack.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back to capture button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .background(AstroSurfaceElevated, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close View Mode",
                        tint = AstroRed,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Title & Frame Index
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "VIEW • PHOTO INSPECT",
                        color = AstroTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${currentIndex + 1} of ${files.size}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Zoom reset / 1:1 indicator
                Text(
                    text = "${"%.1f".format(scale)}x",
                    color = AstroTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick-Zoom Bar: [ FIT ] [ 100% (1:1 Tracking) ] [ 200% Focus ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // FIT Button
                Button(
                    onClick = {
                        scale = 1.0f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scale <= 1.1f) AstroRedDark else AstroSurfaceElevated
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("FIT", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                // 100% (1:1 Tracking & Roundness) Button
                Button(
                    onClick = {
                        scale = 3.0f // 1:1 Pixel Crop equivalent for screen
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scale in 2.8f..3.5f) AstroRedDark else AstroSurfaceElevated
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("1:1 (TRACKING)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                // 200% Focus Sharpness Button
                Button(
                    onClick = {
                        scale = 6.0f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scale > 4.5f) AstroRedDark else AstroSurfaceElevated
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("200% (FOCUS)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Overlay: Bottom Controls with Image Metadata and Prev/Next Navigation
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(AstroBlack.copy(alpha = 0.90f))
                .border(1.dp, AstroRedDark.copy(alpha = 0.4f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Metadata readout: Large, easy to read in the dark!
            metadata?.let { meta ->
                Text(
                    text = meta.file.name,
                    color = AstroTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Exp: ${meta.exposureTimeStr}  |  ${meta.isoStr}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${meta.dimensions}  (${ "%.1f".format(meta.sizeMb)} MB)",
                        color = AstroTextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Navigation Bar: PREV / NEXT Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // PREVIOUS FRAME Button
                Button(
                    onClick = {
                        if (currentIndex > 0) {
                            currentIndex--
                        }
                    },
                    enabled = currentIndex > 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AstroSurfaceElevated,
                        disabledContainerColor = AstroSurface.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null, tint = AstroRed)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PREV",
                        color = if (currentIndex > 0) Color.White else AstroTextDim,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // NEXT FRAME Button
                Button(
                    onClick = {
                        if (currentIndex < files.size - 1) {
                            currentIndex++
                        }
                    },
                    enabled = currentIndex < files.size - 1,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AstroSurfaceElevated,
                        disabledContainerColor = AstroSurface.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "NEXT",
                        color = if (currentIndex < files.size - 1) Color.White else AstroTextDim,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, tint = AstroRed)
                }
            }
        }
    }
}
