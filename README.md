# Trail Cam Gallery

Android app and home-screen widget for a GardePro Wi-Fi trail camera (E9 3.0 Pro),
built for a wall tablet. It shows your trail-cam photos in a landscape gallery and a
resizable home-screen widget.

## How it gets photos: screen mirror (current approach)

The GardePro E9 3.0 Pro uploads over your home Wi-Fi to GardePro's cloud; there is no
documented way to read that cloud, and the camera offers nothing on the local network
between motion triggers. So this app **mirrors the official GardePro app's gallery
screen**:

1. You tap **Sync**. The app opens the official GardePro app (which is already signed
   in and has the photos).
2. With a one-time Android screen-capture permission, it captures GardePro's gallery
   screen, crops the photo tiles, and adds them here and to the widget.
3. It returns to this app.

This reads only pixels already on the tablet — it never wakes the camera, so it uses
**no camera battery** (the key reason the nearby Bluetooth/Wi-Fi approach was dropped).

### One-time setup (calibration)
The first Sync opens GardePro and asks you to tap the corners of the newest photo and
the one below it, so the app learns the tile layout. Redo it from Settings → "Redo
photo setup" if the layout ever changes.

### Full history (optional)
Enable the app's accessibility service once (Settings → Accessibility → Trail Cam
Gallery). During a Sync the app then scrolls GardePro to the top and pages down through
the whole gallery, capturing every photo, stopping when a page shows nothing new. Without
it, only the first screen is captured.

### What works today
- Scroll to the newest, then page down capturing every photo → crop → de-duplicate (by the
  burned-in timestamp strip, so same-scene photos stay distinct) → gallery grid +
  full-screen viewer + home-screen widget.
- Verified on a Galaxy Tab S7 FE (Android 14): one Sync mirrored 23 photos across the
  whole gallery.

### Known limits / next steps
- **Screen-capture consent is requested on every Sync.** Auto-refresh without re-consent
  needs a persistent capture service — not yet built.
- Mirror photos are the on-screen thumbnails, not full-resolution originals, and carry the
  camera's own burned-in timestamp rather than a parsed date.
- Crop accuracy depends on the one-time tap calibration; redo it from Settings if photos
  look offset.
- The home-screen widget's refresh button still triggers the old nearby sync.

## Other code in this repo (not the active path)
Earlier approaches are still present but not wired to the Sync button:
- `CameraApi` / `CameraLink` / `SyncService`: direct nearby sync over Bluetooth + the
  camera's Wi-Fi. Works, but wakes the camera and drains its battery.

A GardePro cloud sign-in was tried and removed: it is undocumented and could not be made to work.

## Build
Needs JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/`.
