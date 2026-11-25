package app.trailcam.gallery

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Does the three taps in the official GardePro app that save its media to this device:
 * Select, Select All, then the save icon. It runs only when the user asks for new media, taps only
 * while GardePro is the app in front, and never answers Android's own prompts: the "Allow GardePro
 * Mobile to modify N videos?" question that follows a save is left for the user.
 *
 * GardePro is a Flutter app with no readable controls, so the taps are positions inside its window.
 */
class TrailAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TrailSave"
        const val GARDEPRO_PKG = "com.zpszjs.gardepro.mobile"

        // Where the controls sit, as fractions of the GardePro window (measured on its gallery).
        private const val SELECT_X = 0.92f;     private const val SELECT_Y = 0.069f
        private const val SELECT_ALL_X = 0.09f; private const val SELECT_ALL_Y = 0.204f
        private const val SAVE_X = 0.165f;      private const val SAVE_Y = 0.888f

        @Volatile var instance: TrailAccessibilityService? = null
            private set
    }

    private val busy = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Open GardePro, save everything to the device, then come back to the gallery. */
    fun getNew() {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                run()
            } catch (e: Exception) {
                Log.w(TAG, "save run failed", e)
            } finally {
                busy.set(false)
                returnToGallery()
            }
        }.start()
    }

    private fun run() {
        val launch = packageManager.getLaunchIntentForPackage(GARDEPRO_PKG) ?: return
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val window = waitForGardePro(12_000) ?: run { Log.w(TAG, "GardePro did not come to the front"); return }
        SystemClock.sleep(4_000)   // let its gallery load
        Log.i(TAG, "GardePro window $window")

        val before = GalleryStore.load(this).size
        if (!tapIn(window, SELECT_X, SELECT_Y)) return
        SystemClock.sleep(1_500)
        if (!tapIn(window, SELECT_ALL_X, SELECT_ALL_Y)) return
        SystemClock.sleep(1_500)
        if (!tapIn(window, SAVE_X, SAVE_Y)) return
        Log.i(TAG, "save tapped; waiting for the download (and the user's Allow)")

        // Done when GardePro is back in front (Android's prompt answered or never shown) and the
        // saved files have stopped changing. Give up after two minutes either way.
        val deadline = SystemClock.uptimeMillis() + 120_000
        var last = -1L
        var stableSince = SystemClock.uptimeMillis()
        SystemClock.sleep(5_000)
        while (SystemClock.uptimeMillis() < deadline) {
            val items = GalleryStore.load(this)
            val signature = items.size * 1_000_003L + items.sumOf { it.size }
            val now = SystemClock.uptimeMillis()
            if (signature != last) { last = signature; stableSince = now }
            val settled = now - stableSince >= 5_000 && items.none { it.size <= 0 }
            if (settled && frontPackage() == GARDEPRO_PKG) break
            SystemClock.sleep(1_000)
        }
        Log.i(TAG, "done: ${GalleryStore.load(this).size - before} new item(s)")
    }

    private fun returnToGallery() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {
        }
    }

    private fun frontPackage(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

    private fun waitForGardePro(timeoutMs: Long): Rect? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = try { rootInActiveWindow } catch (_: Exception) { null }
            if (root != null && root.packageName?.toString() == GARDEPRO_PKG) {
                val r = Rect()
                root.getBoundsInScreen(r)
                if (r.width() > 100 && r.height() > 100) return r
            }
            SystemClock.sleep(300)
        }
        return null
    }

    /** Tap a point inside the GardePro window, but only if GardePro is still the app in front. */
    private fun tapIn(window: Rect, fx: Float, fy: Float): Boolean {
        if (frontPackage() != GARDEPRO_PKG) {
            Log.w(TAG, "GardePro is no longer in front; stopping")
            return false
        }
        val x = window.left + window.width() * fx
        val y = window.top + window.height() * fy
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (sent) try { latch.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        Log.i(TAG, "tap ${x.toInt()},${y.toInt()} ok=$ok")
        return ok
    }
}
