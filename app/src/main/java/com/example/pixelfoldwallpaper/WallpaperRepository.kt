package com.example.pixelfoldwallpaper

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

enum class Screen(val key: String, val spec: ScreenSpec, val label: String) {
    COVER("cover", FoldScreens.COVER, "Cover screen"),
    INNER("inner", FoldScreens.INNER, "Inner screen"),
}

/**
 * Stores the chosen images, their crops and the finished wallpapers.
 *
 * Everything lives in device-protected storage so the wallpaper can still draw on the lock
 * screen after a reboot, before the phone is first unlocked.
 */
class WallpaperRepository(context: Context) {

    private val storage: Context = context.applicationContext.createDeviceProtectedStorageContext()
    private val dir: File get() = storage.filesDir

    val prefs: SharedPreferences = storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Goes up by one each time new wallpapers are written, so the service knows to reload. */
    val version: Long get() = prefs.getLong(KEY_VERSION, 0L)

    private fun sourceFile(screen: Screen) = File(dir, "${screen.key}_source.jpg")
    fun wallpaperFile(screen: Screen) = File(dir, "${screen.key}_wallpaper.png")

    fun hasSource(screen: Screen) = sourceFile(screen).exists()
    fun hasWallpaper(screen: Screen) = wallpaperFile(screen).exists()

    fun loadCrop(screen: Screen) = CropState(
        centerX = prefs.getFloat("${screen.key}_cx", 0.5f),
        centerY = prefs.getFloat("${screen.key}_cy", 0.5f),
        zoom = prefs.getFloat("${screen.key}_zoom", 1f),
    )

    fun saveCrop(screen: Screen, crop: CropState) = prefs.edit {
        putFloat("${screen.key}_cx", crop.centerX)
        putFloat("${screen.key}_cy", crop.centerY)
        putFloat("${screen.key}_zoom", crop.zoom)
    }

    /**
     * Copies a picked photo into the app, shrunk to at most [MAX_SOURCE_EDGE] px on its long
     * side. Photo picker links stop working after a while, so we keep our own copy for re-editing.
     * Resets that screen's crop.
     */
    fun importSource(screen: Screen, uri: Uri) {
        val source = ImageDecoder.createSource(storage.contentResolver, uri)
        val bitmap = decode(source, MAX_SOURCE_EDGE)
        try {
            writeAtomically(sourceFile(screen)) { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        } finally {
            bitmap.recycle()
        }
        saveCrop(screen, CropState())
    }

    /** Decodes this screen's stored photo, shrunk to [maxEdge] px on its long side. */
    fun decodeSource(screen: Screen, maxEdge: Int = MAX_SOURCE_EDGE): Bitmap? {
        val file = sourceFile(screen)
        if (!file.exists()) return null
        return decode(ImageDecoder.createSource(file), maxEdge)
    }

    /** Renders one screen's wallpaper at that screen's exact resolution. */
    fun render(screen: Screen, crop: CropState): Bitmap? {
        val source = decodeSource(screen) ?: return null
        try {
            val spec = screen.spec
            val rect = CropMath.cropRect(crop, source.width, source.height, spec.aspect)
            val out = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(
                source,
                Rect(
                    rect.left.roundToInt(), rect.top.roundToInt(),
                    rect.right.roundToInt(), rect.bottom.roundToInt(),
                ),
                RectF(0f, 0f, spec.width.toFloat(), spec.height.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
            return out
        } finally {
            source.recycle()
        }
    }

    /**
     * Writes the finished wallpaper for every screen that has a photo, then bumps [version]
     * so a running wallpaper redraws. Returns false if no photos have been chosen.
     */
    fun applyWallpapers(crops: Map<Screen, CropState>): Boolean {
        var wroteAny = false
        for (screen in Screen.entries) {
            val crop = crops[screen] ?: loadCrop(screen)
            saveCrop(screen, crop)
            val bitmap = render(screen, crop) ?: continue
            try {
                writeAtomically(wallpaperFile(screen)) { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                wroteAny = true
            } finally {
                bitmap.recycle()
            }
        }
        if (wroteAny) prefs.edit { putLong(KEY_VERSION, version + 1) }
        return wroteAny
    }

    /** Loads a finished wallpaper (used by the wallpaper service). */
    fun loadWallpaper(screen: Screen): Bitmap? {
        val file = wallpaperFile(screen)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.path)
    }

    /**
     * The original app's output: both crops side by side in one image, saved to
     * Pictures/foldWallpaper. Needs both photos. Returns the saved image's link, or null.
     */
    fun exportStitched(crops: Map<Screen, CropState>): Uri? {
        val cover = render(Screen.COVER, crops[Screen.COVER] ?: loadCrop(Screen.COVER)) ?: return null
        val inner = render(Screen.INNER, crops[Screen.INNER] ?: loadCrop(Screen.INNER))
        if (inner == null) {
            cover.recycle()
            return null
        }
        val height = FoldScreens.STITCH_HEIGHT
        val coverWidth = FoldScreens.STITCH_COVER_WIDTH
        val innerWidth = FoldScreens.INNER.width
        val stitched = Bitmap.createBitmap(coverWidth + innerWidth, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        Canvas(stitched).apply {
            drawBitmap(cover, null, RectF(0f, 0f, coverWidth.toFloat(), height.toFloat()), paint)
            drawBitmap(
                inner, null,
                RectF(coverWidth.toFloat(), 0f, (coverWidth + innerWidth).toFloat(), height.toFloat()),
                paint,
            )
        }
        cover.recycle()
        inner.recycle()

        val resolver = storage.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "stitched_fold_wallpaper_${System.currentTimeMillis()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/foldWallpaper")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            stitched.recycle()
            return null
        }
        return try {
            val ok = resolver.openOutputStream(uri)?.use { out ->
                stitched.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            if (!ok) error("Couldn't write the image")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave an empty file in the gallery
            throw e
        } finally {
            stitched.recycle()
        }
    }

    private fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longEdge = max(w, h)
            if (longEdge > maxEdge) {
                val scale = maxEdge.toFloat() / longEdge
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }

    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Boolean) {
        val temp = File(target.parentFile, target.name + ".tmp")
        val ok = temp.outputStream().use(write)
        if (!ok || !temp.renameTo(target)) {
            temp.delete()
            error("Couldn't save ${target.name}")
        }
    }

    companion object {
        const val PREFS_NAME = "fold_wallpaper"
        const val KEY_VERSION = "wallpaper_version"

        /** Long side of the stored copy of each photo. Plenty for zooming into the inner screen. */
        const val MAX_SOURCE_EDGE = 4096
    }
}
