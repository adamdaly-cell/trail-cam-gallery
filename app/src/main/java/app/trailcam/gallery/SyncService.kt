package app.trailcam.gallery

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/** What the sync is doing, for the app screens to observe. */
object SyncState {
    data class Status(val running: Boolean = false, val text: String = "", val error: String? = null)

    val status = MutableStateFlow(Status())

    /** Bumped whenever local gallery files change. */
    val galleryVersion = MutableStateFlow(0)

    fun changed() {
        galleryVersion.value = galleryVersion.value + 1
    }
}

/**
 * Wakes the camera, copies its gallery to the phone, then lets the camera go back to sleep.
 * Short sessions matter: the camera does not take motion-triggered photos while linked.
 */
class SyncService : Service() {

    companion object {
        const val ACTION_SYNC = "app.trailcam.gallery.SYNC"
        const val ACTION_FETCH = "app.trailcam.gallery.FETCH"
        const val EXTRA_KEY = "key"
        private const val CHANNEL = "sync"
        private const val NOTE_ID = 1
        private const val FULL_PHOTO_LIMIT = 100

        /** Permissions needed before a sync can start; empty when ready. */
        fun missingPermissions(ctx: Context): List<String> {
            if (Prefs.directUrl(ctx).isNotEmpty()) return emptyList()
            val wanted = if (Build.VERSION.SDK_INT >= 31)
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            return wanted.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        }

        fun intent(ctx: Context, fetchKey: String? = null): Intent =
            Intent(ctx, SyncService::class.java).apply {
                action = if (fetchKey == null) ACTION_SYNC else ACTION_FETCH
                if (fetchKey != null) putExtra(EXTRA_KEY, fetchKey)
            }

        fun start(ctx: Context, fetchKey: String? = null) {
            ctx.startForegroundService(intent(ctx, fetchKey))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private val fetchQueue = ConcurrentLinkedQueue<String>()
    @Volatile private var syncWanted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val missing = missingPermissions(this)
        val usesDevice = Prefs.directUrl(this).isEmpty()
        val type = when {
            Build.VERSION.SDK_INT < 34 -> 0
            missing.isEmpty() && usesDevice -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        val note = notification("Connecting to the camera…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, note, type) else startForeground(NOTE_ID, note)

        if (missing.isNotEmpty()) {
            finish("Open the app once to allow Bluetooth access")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_FETCH -> intent.getStringExtra(EXTRA_KEY)?.let { fetchQueue.add(it) }
            else -> syncWanted = true
        }
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    private fun report(text: String) {
        SyncState.status.value = SyncState.Status(running = true, text = text)
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification(text))
    }

    private fun finish(error: String?) {
        SyncState.status.value = SyncState.Status(running = false, text = "", error = error)
        SyncState.changed()
        GalleryWidget.refreshAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun run() {
        var error: String? = null
        try {
            SyncState.status.value = SyncState.Status(running = true, text = "Connecting…")
            CameraLink(this).open(::report).use { session ->
                val api = session.api
                drainFetches(api)
                if (syncWanted) {
                    syncWanted = false
                    api.infoSummary().takeIf { it.isNotBlank() }?.let {
                        Prefs.sp(this).edit().putString("camera_info", it).apply()
                    }
                    report("Reading the gallery…")
                    val items = GalleryStore.merge(this, api.listAll())
                    SyncState.changed()

                    val needThumb = items.filter { !GalleryStore.thumb(this, it).exists() }
                    needThumb.forEachIndexed { i, item ->
                        report("Thumbnails ${i + 1} of ${needThumb.size}")
                        try {
                            api.download("/thumb/${item.id}/${item.ext}", GalleryStore.thumb(this, item), 15_000)
                        } catch (e: CamException) {
                            // One unreadable file should not stop the rest.
                        }
                        if (i % 6 == 5) {
                            SyncState.changed()
                            GalleryWidget.refreshAll(this)
                        }
                        drainFetches(api)
                    }
                    SyncState.changed()
                    GalleryWidget.refreshAll(this)

                    if (Prefs.fullPhotos(this)) {
                        val needFull = items.filter { !it.video }.take(FULL_PHOTO_LIMIT)
                            .filter { !GalleryStore.full(this, it).exists() }
                        needFull.forEachIndexed { i, item ->
                            report("Full-size photos ${i + 1} of ${needFull.size}")
                            try {
                                api.download("/file/${item.id}/${item.ext}", GalleryStore.full(this, item), 60_000)
                            } catch (e: CamException) {
                            }
                            drainFetches(api)
                        }
                    }
                    Prefs.sp(this).edit().putLong("last_sync", System.currentTimeMillis()).apply()
                }
                drainFetches(api)
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        fetchQueue.clear()
        syncWanted = false
        finish(error)
    }

    /** Files the user tapped on jump the queue. */
    private fun drainFetches(api: CameraApi) {
        while (true) {
            val key = fetchQueue.poll() ?: return
            val item = GalleryStore.find(this, key) ?: continue
            val dest = GalleryStore.full(this, item)
            if (dest.exists()) continue
            report(if (item.video) "Downloading video…" else "Downloading photo…")
            api.download("/file/${item.id}/${item.ext}", dest, 120_000)
            SyncState.changed()
        }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Camera sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
