package com.avaruus.astro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.avaruus.astro.ui.AstroCameraScreen
import com.avaruus.astro.ui.AstroRedDark
import com.avaruus.astro.ui.AstroTextPrimary
import com.avaruus.astro.ui.AstroTheme
import com.avaruus.astro.ui.AstroViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AstroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on continuously during night astrophotography sessions
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AstroTheme {
                val requiredPermissions = remember {
                    val list = mutableListOf(Manifest.permission.CAMERA)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        list.add(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                    list.toTypedArray()
                }

                var permissionsGranted by remember {
                    mutableStateOf(
                        requiredPermissions.all {
                            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    permissionsGranted = results.values.all { it }
                }

                LaunchedEffect(Unit) {
                    if (!permissionsGranted) {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }

                val isCapturing by viewModel.isCapturing.collectAsState()

                DisposableEffect(isCapturing) {
                    val lp = window.attributes
                    if (isCapturing) {
                        lp.screenBrightness = 0.01f
                    } else {
                        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                    window.attributes = lp
                    onDispose {
                        val resetLp = window.attributes
                        resetLp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = resetLp
                    }
                }

                if (permissionsGranted) {
                    AstroCameraScreen(viewModel = viewModel)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Camera & Storage Permissions Required",
                                color = AstroTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Avaruus Astro needs direct Camera2 hardware access and storage permission to capture RAW DNG frames.",
                                color = Color.Gray,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { permissionLauncher.launch(requiredPermissions) },
                                colors = ButtonDefaults.buttonColors(containerColor = AstroRedDark)
                            ) {
                                Text("Grant Permissions", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
