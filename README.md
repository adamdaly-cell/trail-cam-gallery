# Trail Cam Gallery

Android app and home-screen widget for a GardePro Wi-Fi trail camera (E9 family).
It copies the camera's photos to the phone and shows them in a gallery and in a
resizable widget, so they stay visible after you walk out of range.

## How it reaches the camera

The camera is not on your home network. It sleeps with only Bluetooth LE on.

1. **Bluetooth wake** – the app writes `AT+WAKEPULSE=10\r\n` to characteristic
   `6e400004-…` of the camera's UART service and keeps pulsing it.
2. **Wi-Fi** – the camera raises its own hotspot (`CAM…_<MAC>`, WPA2, factory
   password `1234567890`). The app joins it as an app-only network, so the phone
   keeps its normal internet connection.
3. **HTTP** on `192.168.8.1:8080` – listing, thumbnails and files
   (see `CameraApi.kt`). A keep-alive runs during the session and the camera is
   told to sleep again afterwards.

The protocol is not published by GardePro. It comes from community notes on the
E6, E8 and E9P: [vondruska/gardepro-fetcher](https://github.com/vondruska/gardepro-fetcher),
[CrimsonMartin/gardepro-e8-linux](https://github.com/CrimsonMartin/gardepro-e8-linux),
[khaney64/gardepro](https://github.com/khaney64/gardepro).

While the app is linked, the camera does not take motion-triggered photos, so
syncs are kept short and only happen when you ask (Sync button or the widget's ↻).

## Build

Needs JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. It is signed with the local
debug key, which is fine for installing on your own phone.

## Test without the camera

```bash
python3 tools/mock_camera.py <folder-of-jpgs>
```

Then in the app: Settings → Direct camera address → `http://10.0.2.2:8080`
(emulator) or `http://<computer-ip>:8080` (phone on the same Wi-Fi).
