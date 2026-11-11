package com.example.pixelfoldwallpaper

import android.app.Application
import android.app.WallpaperManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One screen's editor: a preview-sized copy of its photo and where it's positioned. */
data class ScreenEdit(
    val preview: ImageBitmap? = null,
    val crop: CropState = CropState(),
    val loading: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = WallpaperRepository(app)

    var edits by mutableStateOf(Screen.entries.associateWith { ScreenEdit(crop = repo.loadCrop(it)) })
        private set

    /** True while saving or exporting, so the buttons can be disabled. */
    var busy by mutableStateOf(false)
        private set

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Sent when the system wallpaper picker should be opened to switch to this wallpaper. */
    private val _openPicker = Channel<Unit>(Channel.CONFLATED)
    val openPicker = _openPicker.receiveAsFlow()

    init {
        Screen.entries.forEach { loadPreview(it) }
    }

    fun pickImage(screen: Screen, uri: Uri) {
        update(screen) { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.importSource(screen, uri) }
                update(screen) { it.copy(crop = CropState()) }
                loadPreview(screen)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't import image", e)
                update(screen) { it.copy(loading = false) }
                _messages.send("Couldn't open that image.")
            }
        }
    }

    fun setCrop(screen: Screen, crop: CropState) = update(screen) { it.copy(crop = crop) }

    fun resetCrop(screen: Screen) = setCrop(screen, CropState())

    /** Remembers the current positions without changing the wallpaper. */
    fun saveCrops() = edits.forEach { (screen, edit) -> repo.saveCrop(screen, edit.crop) }

    /**
     * Saves both wallpapers. If this app's wallpaper is already in use it updates straight away;
     * otherwise it asks the screen to open the system picker.
     */
    fun applyWallpaper() = runBusy {
        val crops = edits.mapValues { it.value.crop }
        val wrote = withContext(Dispatchers.IO) { repo.applyWallpapers(crops) }
        when {
            !wrote -> _messages.send("Choose at least one image first.")
            isOurWallpaperActive() -> _messages.send("Wallpaper updated.")
            else -> _openPicker.send(Unit)
        }
    }

    /** The original app's stitched image, saved to Pictures/foldWallpaper. */
    fun exportStitched() = runBusy {
        val crops = edits.mapValues { it.value.crop }
        val uri = withContext(Dispatchers.IO) { repo.exportStitched(crops) }
        _messages.send(
            if (uri != null) "Saved to Pictures/foldWallpaper." else "Choose both images first.",
        )
    }

    private fun isOurWallpaperActive(): Boolean {
        val app = getApplication<Application>()
        return WallpaperManager.getInstance(app).wallpaperInfo?.packageName == app.packageName
    }

    private fun loadPreview(screen: Screen) {
        viewModelScope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) { repo.decodeSource(screen, PREVIEW_EDGE) }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't load preview", e)
                null
            }
            update(screen) { it.copy(preview = bitmap?.asImageBitmap(), loading = false) }
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Saving failed", e)
                _messages.send("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    private fun update(screen: Screen, change: (ScreenEdit) -> ScreenEdit) {
        edits = edits + (screen to change(edits.getValue(screen)))
    }

    private companion object {
        const val TAG = "FoldWallpaper"

        /** Long side of the image shown in the editor. */
        const val PREVIEW_EDGE = 2048
    }
}
