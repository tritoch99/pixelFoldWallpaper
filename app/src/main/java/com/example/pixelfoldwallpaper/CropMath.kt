package com.example.pixelfoldwallpaper

import kotlin.math.max
import kotlin.math.min

/** Pure crop maths with no Android dependencies, so it can be unit tested. */

/** A screen's size in pixels (portrait). */
data class ScreenSpec(val width: Int, val height: Int) {
    val aspect: Float get() = width.toFloat() / height
}

object FoldScreens {
    /** Pixel 10 Pro Fold outer (cover) screen. */
    val COVER = ScreenSpec(1080, 2364)

    /** Pixel 10 Pro Fold inner (unfolded) screen. */
    val INNER = ScreenSpec(2076, 2152)

    /**
     * Stitched-image export layout (the original app's): cover panel on the left at 984 px wide,
     * inner panel on the right at 2076 px wide, both 2152 px tall.
     */
    const val STITCH_HEIGHT = 2152
    const val STITCH_COVER_WIDTH = 984

    /**
     * Picks which image a surface should show. The cover screen is tall and narrow
     * (about 0.46 wide per unit of height); the inner screen is nearly square (about 0.96).
     * Works the same in landscape because it compares the short side to the long side.
     */
    fun isInnerSurface(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = min(width, height).toFloat() / max(width, height)
        return ratio > 0.75f
    }
}

/** A rectangle in source-image pixels. */
data class CropRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Where one image sits inside one screen.
 *
 * [centerX] and [centerY] are the centre of the visible area as a fraction of the image's width
 * and height (0.5, 0.5 is the middle). [zoom] is 1 when the image just fills the screen and
 * goes up from there. Storing fractions keeps the crop the same whatever resolution the
 * image is decoded at.
 */
data class CropState(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = 1f,
) {
    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
    }
}

object CropMath {

    /** Size of the visible crop at zoom 1: the largest rectangle with the screen's shape. */
    private fun baseCropSize(imageWidth: Float, imageHeight: Float, aspect: Float): Pair<Float, Float> =
        if (imageWidth / imageHeight > aspect) {
            Pair(imageHeight * aspect, imageHeight)
        } else {
            Pair(imageWidth, imageWidth / aspect)
        }

    /** Keeps the zoom in range and the crop fully inside the image. */
    fun clamp(state: CropState, imageWidth: Int, imageHeight: Int, aspect: Float): CropState {
        val iw = imageWidth.toFloat()
        val ih = imageHeight.toFloat()
        val zoom = state.zoom.coerceIn(CropState.MIN_ZOOM, CropState.MAX_ZOOM)
        val (baseW, baseH) = baseCropSize(iw, ih, aspect)
        val halfW = baseW / zoom / 2f
        val halfH = baseH / zoom / 2f
        val cx = (state.centerX * iw).coerceIn(halfW, iw - halfW)
        val cy = (state.centerY * ih).coerceIn(halfH, ih - halfH)
        return CropState(cx / iw, cy / ih, zoom)
    }

    /** The part of the image to show, in source pixels. */
    fun cropRect(state: CropState, imageWidth: Int, imageHeight: Int, aspect: Float): CropRect {
        val s = clamp(state, imageWidth, imageHeight, aspect)
        val (baseW, baseH) = baseCropSize(imageWidth.toFloat(), imageHeight.toFloat(), aspect)
        val w = baseW / s.zoom
        val h = baseH / s.zoom
        return CropRect(s.centerX * imageWidth - w / 2f, s.centerY * imageHeight - h / 2f, w, h)
    }

    /**
     * Applies a drag and pinch from the on-screen preview.
     *
     * @param frameWidth width of the preview frame in screen pixels (its shape matches [aspect])
     * @param panX, panY how far the fingers moved, in screen pixels
     * @param zoomChange pinch factor (1 = no change)
     * @param focusX, focusY the pinch centre within the frame, in screen pixels; the image point
     *   under it stays put while zooming
     */
    fun transform(
        state: CropState,
        imageWidth: Int,
        imageHeight: Int,
        aspect: Float,
        frameWidth: Float,
        panX: Float,
        panY: Float,
        zoomChange: Float,
        focusX: Float,
        focusY: Float,
    ): CropState {
        if (frameWidth <= 0f) return state
        val before = cropRect(state, imageWidth, imageHeight, aspect)
        val scaleBefore = frameWidth / before.width // screen px per source px

        // Image point currently under the pinch centre.
        val anchorX = before.left + focusX / scaleBefore
        val anchorY = before.top + focusY / scaleBefore

        val newZoom = (state.zoom * zoomChange).coerceIn(CropState.MIN_ZOOM, CropState.MAX_ZOOM)
        val (baseW, baseH) = baseCropSize(imageWidth.toFloat(), imageHeight.toFloat(), aspect)
        val newW = baseW / newZoom
        val newH = baseH / newZoom
        val scaleAfter = frameWidth / newW

        // Keep the anchor under the pinch centre, then move by the drag.
        val left = anchorX - focusX / scaleAfter - panX / scaleAfter
        val top = anchorY - focusY / scaleAfter - panY / scaleAfter

        val moved = CropState(
            centerX = (left + newW / 2f) / imageWidth,
            centerY = (top + newH / 2f) / imageHeight,
            zoom = newZoom,
        )
        return clamp(moved, imageWidth, imageHeight, aspect)
    }

    /**
     * Source rectangle that fills a surface of the given size from an image, centre-cropped.
     * Used by the wallpaper service in case the surface isn't exactly the size we rendered.
     */
    fun centerCrop(imageWidth: Int, imageHeight: Int, surfaceWidth: Int, surfaceHeight: Int): CropRect {
        val aspect = surfaceWidth.toFloat() / surfaceHeight
        val (w, h) = baseCropSize(imageWidth.toFloat(), imageHeight.toFloat(), aspect)
        return CropRect((imageWidth - w) / 2f, (imageHeight - h) / 2f, w, h)
    }
}
