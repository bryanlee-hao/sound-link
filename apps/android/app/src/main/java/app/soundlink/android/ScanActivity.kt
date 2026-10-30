package app.soundlink.android

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

class ScanActivity : ComponentActivity() {
    private var previewActive by mutableStateOf(false)
    private var displayDegrees by mutableIntStateOf(0)
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit

            override fun onDisplayRemoved(id: Int) = Unit

            override fun onDisplayChanged(id: Int) {
                if (id == display?.displayId) updateDisplayRotation()
            }
        }

    private fun updateDisplayRotation() {
        displayDegrees = (display?.rotation ?: 0) * 90
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SoundLinkTheme(darkOverride = true) {
                var hint by remember {
                    mutableStateOf(
                        getString(R.string.message_scan_the_pairing_code_in_sound_link_on_your)
                    )
                }
                Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    if (previewActive) {
                        val spec = remember {
                            runCatching { QrCamera.spec(this@ScanActivity) }.getOrNull()
                        }
                        if (spec != null)
                            BoxWithConstraints(
                                Modifier.fillMaxSize().clipToBounds(),
                                contentAlignment = Alignment.Center,
                            ) {
                                val geometry =
                                    previewGeometry(
                                        spec.width,
                                        spec.height,
                                        spec.rotation,
                                        displayDegrees,
                                        maxWidth.value,
                                        maxHeight.value,
                                    )
                                AndroidEmbeddedExternalSurface(
                                    Modifier.requiredSize(geometry.width.dp, geometry.height.dp)
                                        .graphicsLayer { rotationZ = geometry.rotation },
                                    surfaceSize = IntSize(spec.width, spec.height),
                                ) {
                                    onSurface { surface, _, _ ->
                                        val camera =
                                            QrCamera(this@ScanActivity, surface, spec) { hint = it }
                                        camera.open()
                                        surface.onDestroyed { camera.close() }
                                    }
                                }
                            }
                        else
                            Text(
                                getString(R.string.message_couldn_t_open_camera),
                                color = Color.White,
                            )
                    }
                    Text(
                        hint,
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = .7f))
                            .padding(24.dp),
                        color = Color.White,
                    )
                    Button(
                        onClick = { finish() },
                        Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth(),
                    ) {
                        Text(getString(R.string.cancel))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDisplayRotation()
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        previewActive = true
    }

    override fun onPause() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        previewActive = false
        super.onPause()
    }
}
