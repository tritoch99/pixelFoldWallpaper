package com.example.pixelfoldwallpaper

import android.app.WallpaperColors
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.SurfaceHolder
import kotlin.math.roundToInt

/**
 * A "live" wallpaper that is really two still images: it draws the cover image when the
 * surface is tall and narrow (folded) and the inner image when it's nearly square (unfolded).
 * Nothing animates, so it only draws when the screen size or the images change.
 */
class FoldWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FoldEngine()

    private inner class FoldEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val repo = WallpaperRepository(this@FoldWallpaperService)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private var width = 0
        private var height = 0
        private var colors: WallpaperColors? = null
        private var colorsSource: Bitmap? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // The image doesn't scroll with home-screen pages.
            setOffsetNotificationsEnabled(false)
            repo.prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            repo.prefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            draw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            draw()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            if (key == WallpaperRepository.KEY_VERSION) draw()
        }

        override fun onComputeColors(): WallpaperColors? = colors

        private fun draw() {
            if (width <= 0 || height <= 0) return
            val wanted = if (FoldScreens.isInnerSurface(width, height)) Screen.INNER else Screen.COVER
            val bitmap = ImageCache.get(repo, wanted)

            val canvas: Canvas = try {
                surfaceHolder.lockHardwareCanvas()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't lock the wallpaper surface", e)
                null
            } ?: return
            try {
                if (bitmap != null) drawImage(canvas, bitmap) else drawPlaceholder(canvas)
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }

            // Let the system pick theme colours from whatever is showing now.
            if (bitmap !== colorsSource) {
                colorsSource = bitmap
                colors = bitmap?.let { WallpaperColors.fromBitmap(it) }
                notifyColorsChanged()
            }
        }

        private fun drawImage(canvas: Canvas, bitmap: Bitmap) {
            // The images are rendered at the screen's exact size; this only crops if the
            // surface turns out to be a different shape (for example, a rotated screen).
            val crop = CropMath.centerCrop(bitmap.width, bitmap.height, width, height)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(
                bitmap,
                android.graphics.Rect(
                    crop.left.roundToInt(), crop.top.roundToInt(),
                    crop.right.roundToInt(), crop.bottom.roundToInt(),
                ),
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                paint,
            )
        }

        private fun drawPlaceholder(canvas: Canvas) {
            canvas.drawColor(Color.rgb(24, 24, 28))
            val text = getString(R.string.wallpaper_placeholder)
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics,
                )
            }
            val layoutWidth = (width * 0.8f).roundToInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
            canvas.save()
            canvas.translate((width - layoutWidth) / 2f, (height - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * Shares loaded images between wallpaper instances (home, lock screen, the picker's
     * preview) and reloads them when the app saves new ones. If only one screen has an image,
     * it's used on both.
     */
    private object ImageCache {
        private var loadedVersion = -1L
        private val images = mutableMapOf<Screen, Bitmap?>()

        @Synchronized
        fun get(repo: WallpaperRepository, screen: Screen): Bitmap? {
            val version = repo.version
            if (version != loadedVersion) {
                // Old bitmaps aren't recycled: another instance may still be drawing them.
                images.clear()
                loadedVersion = version
            }
            val own = images.getOrPut(screen) { load(repo, screen) }
            if (own != null) return own
            val other = if (screen == Screen.COVER) Screen.INNER else Screen.COVER
            return images.getOrPut(other) { load(repo, other) }
        }

        private fun load(repo: WallpaperRepository, screen: Screen): Bitmap? = try {
            repo.loadWallpaper(screen)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load the ${screen.key} wallpaper", e)
            null
        }
    }

    private companion object {
        const val TAG = "FoldWallpaper"
    }
}
