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

            MirrorState.status.value = "Capturing…"
            val frame = grabFrame(w, h) ?: throw IllegalStateException("Could not capture the screen")

            if (mode == MODE_CALIBRATE) {
                FileOutputStream(calibrationFrame(this)).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                teardown()
                startActivity(Intent(this, CalibrationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val cal = Calibration.load(this) ?: throw IllegalStateException("Not calibrated yet")
                MirrorState.status.value = "Adding photos…"
                val tiles = TileGrid.extract(frame, cal)
                val added = MirrorStore.add(this, tiles)
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

    /** Grab a stable frame: discard the first couple (mid-render) and keep a later one. */
    private fun grabFrame(w: Int, h: Int): Bitmap? {
        Thread.sleep(SETTLE_MS)
        var result: Bitmap? = null
        repeat(6) {
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
            Thread.sleep(500)
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
