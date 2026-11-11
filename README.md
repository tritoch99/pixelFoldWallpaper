# PixelFoldWallpaper

Shows a different image on the Pixel 10 Pro Fold's cover screen and inner screen.

The app installs a "live" wallpaper, Fold Wallpaper, that is really two still images. When the
phone is folded it draws the cover image; when it's unfolded it draws the inner image. Nothing
animates, so it only redraws when you fold, unfold or change the images.

## Build and install

Works with Android Studio **Otter 3 Feature Drop (2025.2.3)** or newer, the same setup as
IronTrack: AGP 9.0.0, Gradle 9.1.0, Kotlin 2.3.21, compiling against **API 36**. The app runs
normally on Android 17. The minimum is Android 12.

1. In Android Studio, open this folder and let Gradle sync. If Studio still remembers the old
   AGP 9.2 setup, run **Build → Clean Project** once.
2. Pick the phone in the device menu and press **Run**.

To run the crop-maths tests: `./gradlew test`.

## Using it

1. Open the app. Pick an image for each screen. Drag to move, pinch to zoom, double-tap to reset.
   Folded, the two screens are on tabs; unfolded, they're side by side.
2. Tap **Set as wallpaper**. The first time, Android's wallpaper preview opens. Choose
   home screen, lock screen or both.
3. To change the images later, open the app (or **Settings** on the wallpaper's preview screen),
   adjust, and tap **Set as wallpaper** again. It updates right away.

If only one screen has an image, that image is used on both.

**Save stitched image** still makes the original side-by-side image in `Pictures/foldWallpaper`,
in case you want to set it the old way.

## Where things are

| File | What it does |
| --- | --- |
| `CropMath.kt` | Screen sizes and crop maths. No Android code, unit tested in `CropMathTest.kt`. |
| `WallpaperRepository.kt` | Stores the photos, crops and finished wallpapers; renders and exports. |
| `FoldWallpaperService.kt` | The wallpaper itself: picks the image by screen shape and draws it. |
| `MainViewModel.kt`, `MainActivity.kt` | The editor screen. |
| `AppTheme.kt` | Material You colours. (`ui/theme/` is the old template and is no longer used.) |

The images are kept in device-protected storage so the lock screen still shows them after a
reboot, before the first unlock.
