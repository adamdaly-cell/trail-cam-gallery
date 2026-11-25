package app.trailcam.gallery

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Performs scroll gestures on the official GardePro gallery so the mirror can page through the
 * whole history instead of only the first screen. It does nothing on its own — [MirrorService]
 * calls [scroll] between captures. It reads no screen content; it only swipes.
 *
 * The user enables it once under Settings → Accessibility. If it is off, mirroring still works
 * but captures only the photos already on screen.
 */
class TrailAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: TrailAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Swipe upward from (x, y1) to (x, y2) to scroll the gallery toward older photos.
     * Blocks until the gesture finishes (or times out). Returns true on completion.
     */
    fun scroll(x: Float, y1: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        // A slower stroke scrolls as a controlled drag instead of a fling, so it advances a
        // predictable distance (about one photo) without overshooting or snapping back.
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 520))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        var cancelled = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { cancelled = true; latch.countDown() }
        }, null)
        if (!dispatched) { Log.w("TrailMirror", "dispatchGesture returned false (gesture rejected)"); return false }
        val fired = try { latch.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        Log.i("TrailMirror", "gesture dispatched=$dispatched fired=$fired completed=$ok cancelled=$cancelled")
        SystemClock.sleep(200)
        return ok
    }
}
