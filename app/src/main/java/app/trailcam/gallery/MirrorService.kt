package app.trailcam.gallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.File
import java.io.FileOutputStream

/**
 * Captures the official GardePro app's gallery screen and turns it into mirror photos.
 *
 * It holds a MediaProjection (granted once by the user), opens the GardePro app so its gallery is
 * on screen, grabs one frame of the display, then either saves it for calibration or crops it into
 * tiles and adds them to the gallery. Nothing touches GardePro's servers — it only reads pixels the
 * official app has already drawn on this device.
 */
class MirrorService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODE = "mode"
        const val MODE_REFRESH = "refresh"
        const val MODE_CALIBRATE = "calibrate"
        const val GARDEPRO_PKG = "com.zpszjs.gardepro.mobile"
        private const val CHANNEL = "mirror"
        private const val NOTE_ID = 2
        private const val SETTLE_MS = 5_000L     // time for the GardePro gallery to render
        private const val MAX_PAGES = 20         // most screens to scroll through in one sync

        fun calibrationFrame(ctx: Context) = File(ctx.cacheDir, "calibration.png")
    }

    private val thread = HandlerThread("mirror").apply { start() }
    private val bg = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = notification("Reading the GardePro gallery…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTE_ID, note)
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_REFRESH
        if (code == 0 || data == null) {
            finish("Screen capture was not granted"); return START_NOT_STICKY
        }
        bg.post { runCapture(code, data, mode) }
        return START_NOT_STICKY
    }

    private fun runCapture(code: Int, data: Intent, mode: String) {
        var error: String? = null
        try {
            MirrorState.status.value = "Opening GardePro…"
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(code, data) ?: throw IllegalStateException("No projection")
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {}, bg)

            val metrics = screenSize()
            val (w, h, dpi) = metrics
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            display = proj.createVirtualDisplay(
                "trailcam-mirror", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, bg
            )

            // Bring GardePro to the front so its gallery is what the display shows.
            packageManager.getLaunchIntentForPackage(GARDEPRO_PKG)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(it)
            } ?: throw IllegalStateException("The GardePro app is not installed")

            Thread.sleep(SETTLE_MS)   // let the GardePro gallery finish rendering
            MirrorState.status.value = "Capturing…"

            if (mode == MODE_CALIBRATE) {
                val frame = grabFrame(w, h) ?: throw IllegalStateException("Could not capture the screen")
                FileOutputStream(calibrationFrame(this)).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                teardown()
                startActivity(Intent(this, CalibrationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val cal = Calibration.load(this) ?: throw IllegalStateException("Not calibrated yet")
                val added = capturePages(w, h, cal)
                Prefs.sp(this).edit().putLong("last_sync", System.currentTimeMillis()).apply()
                teardown()
                SyncState.changed()
                GalleryWidget.refreshAll(this)
                MirrorState.lastAdded.value = added
                // Return to our app so the user sees the result.
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            teardown()
        }
        finish(error)
    }

    /**
     * Capture the gallery page by page: grab a screen, add its new tiles, then (if the accessibility
     * service is enabled) scroll to the next screen and repeat until a page yields nothing new or the
     * page limit is reached. Returns the total number of new photos added.
     */
    private fun capturePages(w: Int, h: Int, cal: Calibration): Int {
        val a11y = TrailAccessibilityService.instance
        // GardePro resumes wherever it was last scrolled, so rewind to the newest photos first.
        if (a11y != null) scrollToTop(w, h, cal, a11y)
        var total = 0
        for (page in 0 until MAX_PAGES) {
            val frame = grabFrame(w, h) ?: break
            val tiles = TileGrid.extract(frame, cal)
            frame.recycle()
            val added = MirrorStore.add(this, tiles)
            tiles.forEach { it.recycle() }
            total += added
            MirrorState.status.value = "Mirrored $total photo(s)…"
            SyncState.changed()
            GalleryWidget.refreshAll(this)
            // Stop when this page brought nothing new (caught up / end of list) after the first.
            if (added == 0 && page > 0) break
            if (a11y == null) break   // can't scroll without the accessibility service
            // Swipe up to reveal older photos. Both ends stay well inside the photo area (never on
            // the bottom nav bar), or the gesture lands on a non-scrolling element and does nothing.
            val sx = (if (cal.capW > 0) w.toFloat() / cal.capW else 1f)
            val sy = (if (cal.capH > 0) h.toFloat() / cal.capH else 1f)
            val x = (cal.x0 + cal.tileW / 2f) * sx
            val top = cal.y0 * sy
            val gh = ((if (cal.bottomLimit > 0) cal.bottomLimit else cal.capH) * sy) - top
            if (gh < 80f) break
            val yStart = top + gh * 0.62f
            val yEnd = top + gh * 0.08f
            a11y.scroll(x, yStart, yEnd)
            Thread.sleep(1_400)       // let the scroll settle before the next capture
        }
        return total
    }

    /** Scroll GardePro up to the newest photos: swipe down until the screen stops changing. */
    private fun scrollToTop(w: Int, h: Int, cal: Calibration, a11y: TrailAccessibilityService) {
        val sx = if (cal.capW > 0) w.toFloat() / cal.capW else 1f
        val sy = if (cal.capH > 0) h.toFloat() / cal.capH else 1f
        val x = (cal.x0 + cal.tileW / 2f) * sx
        val top = cal.y0 * sy
        val gh = ((if (cal.bottomLimit > 0) cal.bottomLimit else cal.capH) * sy) - top
        if (gh < 80f) return
        val yHigh = top + gh * 0.10f
        val yLow = top + gh * 0.64f
        var prev = 0L
        for (i in 0 until 30) {
            val f = grabFrame(w, h) ?: return
            val hsh = frameHash(f)
            f.recycle()
            if (i > 0 && hsh == prev) return    // no change -> reached the top
            prev = hsh
            a11y.scroll(x, yHigh, yLow)          // swipe downward to reveal newer photos
            Thread.sleep(1_000)
        }
    }

    /** Coarse 64-bit average hash of a frame, for detecting whether the screen changed. */
    private fun frameHash(bmp: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(bmp, 8, 8, true)
        var sum = 0; val v = IntArray(64)
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = s.getPixel(x, y)
            val l = ((p shr 16 and 0xff) * 30 + (p shr 8 and 0xff) * 59 + (p and 0xff) * 11) / 100
            v[y * 8 + x] = l; sum += l
        }
        s.recycle()
        val avg = sum / 64
        var bits = 0L
        for (i in 0 until 64) if (v[i] >= avg) bits = bits or (1L shl i)
        return bits
    }

    /**
     * Grab the current display frame. Reads several times over ~1.5s and keeps the LAST frame, so a
     * stale image buffered before a scroll is never returned.
     */
    private fun grabFrame(w: Int, h: Int): Bitmap? {
        var result: Bitmap? = null
        repeat(8) {
            val image = reader?.acquireLatestImage()
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    result?.recycle()
                    result = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h).also { bmp.recycle() }
                } finally {
                    image.close()
                }
            }
            Thread.sleep(200)
        }
        return result
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dm.densityDpi)
        } else {
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun teardown() {
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; reader = null; projection = null
    }

    private fun finish(error: String?) {
        MirrorState.status.value = ""
        MirrorState.error.value = error
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mirror", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        teardown()
        thread.quitSafely()
        super.onDestroy()
    }
}

/** Live status of a mirror capture, observed by the UI. */
object MirrorState {
    val status = kotlinx.coroutines.flow.MutableStateFlow("")
    val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lastAdded = kotlinx.coroutines.flow.MutableStateFlow(-1)
}
