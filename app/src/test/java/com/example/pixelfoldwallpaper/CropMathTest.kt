package com.example.pixelfoldwallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {

    private val cover = FoldScreens.COVER.aspect
    private val inner = FoldScreens.INNER.aspect
    private val eps = 0.5f

    @Test
    fun zoomOneFillsHeightOfWidePhoto() {
        // 4000x3000 landscape photo on the narrow cover screen: full height, centred.
        val r = CropMath.cropRect(CropState(), 4000, 3000, cover)
        assertEquals(3000f, r.height, eps)
        assertEquals(3000f * cover, r.width, eps)
        assertEquals((4000f - r.width) / 2f, r.left, eps)
        assertEquals(0f, r.top, eps)
    }

    @Test
    fun zoomOneFillsWidthOfTallPhoto() {
        // 3000x4000 portrait photo on the nearly square inner screen: full width.
        val r = CropMath.cropRect(CropState(), 3000, 4000, inner)
        assertEquals(3000f, r.width, eps)
        assertEquals(3000f / inner, r.height, eps)
    }

    @Test
    fun cropAlwaysKeepsScreenShape() {
        val r = CropMath.cropRect(CropState(0.1f, 0.9f, 3.3f), 5000, 2000, cover)
        assertEquals(cover, r.width / r.height, 0.001f)
    }

    @Test
    fun clampKeepsCropInsideImage() {
        val r = CropMath.cropRect(CropState(centerX = 0f, centerY = 1f, zoom = 2f), 4000, 3000, cover)
        assertEquals(0f, r.left, eps)
        assertEquals(3000f, r.bottom, eps)
    }

    @Test
    fun zoomIsLimited() {
        assertEquals(CropState.MAX_ZOOM, CropMath.clamp(CropState(zoom = 100f), 100, 100, 1f).zoom, 0f)
        assertEquals(CropState.MIN_ZOOM, CropMath.clamp(CropState(zoom = 0.2f), 100, 100, 1f).zoom, 0f)
    }

    @Test
    fun dragMovesImageWithFinger() {
        // Zoom in so there's room to move, then drag right: the crop should move left.
        val start = CropState(zoom = 2f)
        val before = CropMath.cropRect(start, 4000, 3000, inner)
        val frameWidth = 1000f
        val moved = CropMath.transform(start, 4000, 3000, inner, frameWidth, 100f, 0f, 1f, 0f, 0f)
        val after = CropMath.cropRect(moved, 4000, 3000, inner)
        val sourcePxPerScreenPx = before.width / frameWidth
        assertEquals(before.left - 100f * sourcePxPerScreenPx, after.left, eps)
        assertEquals(before.top, after.top, eps)
    }

    @Test
    fun pinchKeepsPointUnderFingers() {
        val start = CropState()
        val frameWidth = 800f
        val focusX = 200f
        val focusY = 300f
        val before = CropMath.cropRect(start, 4000, 3000, inner)
        val anchorX = before.left + focusX * before.width / frameWidth
        val anchorY = before.top + focusY * before.width / frameWidth

        val zoomed = CropMath.transform(start, 4000, 3000, inner, frameWidth, 0f, 0f, 2f, focusX, focusY)
        val after = CropMath.cropRect(zoomed, 4000, 3000, inner)
        assertEquals(2f, zoomed.zoom, 0.001f)
        assertEquals(anchorX, after.left + focusX * after.width / frameWidth, eps)
        assertEquals(anchorY, after.top + focusY * after.width / frameWidth, eps)
    }

    @Test
    fun surfaceShapeDecidesWhichImage() {
        assertFalse(FoldScreens.isInnerSurface(1080, 2364)) // cover, portrait
        assertFalse(FoldScreens.isInnerSurface(2364, 1080)) // cover, landscape
        assertTrue(FoldScreens.isInnerSurface(2076, 2152)) // inner, portrait
        assertTrue(FoldScreens.isInnerSurface(2152, 2076)) // inner, landscape
    }

    @Test
    fun centerCropMatchesSurfaceShape() {
        val r = CropMath.centerCrop(2076, 2152, 2152, 2076)
        assertEquals(2152f / 2076f, r.width / r.height, 0.001f)
        assertEquals(0f, r.left, eps)
    }
}
