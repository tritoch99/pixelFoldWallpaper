package com.example.pixelfoldwallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoldWallpaperTheme {
                MainScreen(viewModel, onOpenPicker = ::openWallpaperPicker)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.saveCrops()
    }

    /** Opens the system screen for setting this app as the wallpaper (home, lock or both). */
    private fun openWallpaperPicker() {
        val component = ComponentName(this, FoldWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(viewModel: MainViewModel, onOpenPicker: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.openPicker.collect { onOpenPicker() } }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Fold Wallpaper", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Drag to move, pinch to zoom, double-tap to reset.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= 600.dp) {
                    // Unfolded: both editors side by side.
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Screen.entries.forEach { screen ->
                            ScreenEditor(viewModel, screen, Modifier.weight(screen.spec.aspect))
                        }
                    }
                } else {
                    // Folded: one editor at a time.
                    var selected by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxSize()) {
                        PrimaryTabRow(selectedTabIndex = selected) {
                            Screen.entries.forEachIndexed { i, screen ->
                                Tab(
                                    selected = selected == i,
                                    onClick = { selected = i },
                                    text = { Text(screen.label) },
                                )
                            }
                        }
                        ScreenEditor(
                            viewModel,
                            Screen.entries[selected],
                            Modifier.weight(1f).padding(top = 12.dp),
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Button(
                    onClick = viewModel::applyWallpaper,
                    enabled = !viewModel.busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (viewModel.busy) "Saving…" else "Set as wallpaper") }
                OutlinedButton(
                    onClick = viewModel::exportStitched,
                    enabled = !viewModel.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Save stitched image") }
            }
        }
    }
}

@Composable
private fun ScreenEditor(viewModel: MainViewModel, screen: Screen, modifier: Modifier = Modifier) {
    val edit = viewModel.edits.getValue(screen)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickImage(screen, uri)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${screen.label} · ${screen.spec.width}×${screen.spec.height}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CropFrame(
                edit = edit,
                aspect = screen.spec.aspect,
                onCropChange = { viewModel.setCrop(screen, it) },
                onReset = { viewModel.resetCrop(screen) },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            FilledTonalButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (edit.preview == null) "Choose image" else "Change image") }
            TextButton(onClick = { viewModel.resetCrop(screen) }, enabled = edit.preview != null) {
                Text("Reset")
            }
        }
    }
}

/** The image as it will look on that screen, with drag, pinch and double-tap. */
@Composable
private fun CropFrame(
    edit: ScreenEdit,
    aspect: Float,
    onCropChange: (CropState) -> Unit,
    onReset: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val image = edit.preview
    val currentEdit by rememberUpdatedState(edit)
    val currentOnChange by rememberUpdatedState(onCropChange)
    val currentOnReset by rememberUpdatedState(onReset)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .pointerInput(image, aspect) {
                if (image == null) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    currentOnChange(
                        CropMath.transform(
                            state = currentEdit.crop,
                            imageWidth = image.width,
                            imageHeight = image.height,
                            aspect = aspect,
                            frameWidth = size.width.toFloat(),
                            panX = pan.x,
                            panY = pan.y,
                            zoomChange = zoom,
                            focusX = centroid.x,
                            focusY = centroid.y,
                        ),
                    )
                }
            }
            .pointerInput(image) {
                if (image == null) return@pointerInput
                detectTapGestures(onDoubleTap = { currentOnReset() })
            },
    ) {
        when {
            edit.loading -> CircularProgressIndicator()
            image == null -> Text(
                "No image yet",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
            else -> Canvas(Modifier.fillMaxSize()) {
                val rect = CropMath.cropRect(edit.crop, image.width, image.height, aspect)
                // Rounding must not push the source rectangle past the image's edge.
                val srcW = rect.width.roundToInt().coerceIn(1, image.width)
                val srcH = rect.height.roundToInt().coerceIn(1, image.height)
                drawImage(
                    image = image,
                    srcOffset = IntOffset(
                        rect.left.roundToInt().coerceIn(0, image.width - srcW),
                        rect.top.roundToInt().coerceIn(0, image.height - srcH),
                    ),
                    srcSize = IntSize(srcW, srcH),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        }
    }
}
