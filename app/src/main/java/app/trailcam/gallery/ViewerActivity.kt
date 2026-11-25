package app.trailcam.gallery

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One photo or video, full screen. Full-size files are fetched from the camera on request. */
class ViewerActivity : Activity() {

    companion object {
        const val EXTRA_KEY = "key"
    }

    private val scope = MainScope()
    private var item: MediaItem? = null
    private lateinit var image: ImageView
    private lateinit var info: TextView
    private lateinit var action: Button
    private var shownFull = false
    private lateinit var swipes: GestureDetector

    /** Swipe left / right for the next (older) / previous (newer) item. */
    private fun step(by: Int) {
        val all = GalleryStore.load(this)
        val next = all.getOrNull(all.indexOfFirst { it.key == item?.key } + by) ?: return
        item = next
        intent.putExtra(EXTRA_KEY, next.key)
        shownFull = false
        image.setImageDrawable(null)
        render()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipes.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        image = findViewById(R.id.image)
        info = findViewById(R.id.info)
        action = findViewById(R.id.action)
        swipes = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (Math.abs(vx) < 2 * Math.abs(vy) || Math.abs(vx) < 800) return false
                step(if (vx < 0) 1 else -1)
                return true
            }
        })
        item = intent.getStringExtra(EXTRA_KEY)?.let { GalleryStore.find(this, it) }
        if (item == null) {
            finish()
            return
        }
        scope.launch { SyncState.galleryVersion.collectLatest { render() } }
        scope.launch { SyncState.status.collectLatest { render() } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val item = item ?: return
        val full = GalleryStore.full(this, item)
        val haveFull = full.exists()
        // A cloud photo's downloaded object is already the full image, so its thumbnail IS the photo.
        val photoReady = !item.video && (haveFull || item.fromCloud)
        if (photoReady && !shownFull) {
            val src = if (haveFull) full else GalleryStore.thumb(this, item)
            GalleryStore.decode(src, 2400)?.let { image.setImageBitmap(it); shownFull = true }
        } else if (image.drawable == null) {
            image.setImageBitmap(GalleryStore.decode(GalleryStore.thumb(this, item), 1024))
        }

        val state = SyncState.status.value
        val sizeMb = if (item.size > 0) " · %.1f MB".format(item.size / 1048576.0) else ""
        val showingFull = haveFull || photoReady
        info.text = when {
            state.running -> state.text
            state.error != null && !showingFull -> state.error
            else -> shortDate(item.date) + sizeMb + if (showingFull) "" else " · preview"
        }
        // What the button does: open an image/ready video; otherwise download a video.
        val canOpen = haveFull || photoReady
        action.isEnabled = canOpen || !state.running
        action.text = when {
            haveFull && item.video -> "Play video"
            canOpen -> "Open / share"
            item.video -> "Download video"
            else -> "Get full size"
        }
        action.setOnClickListener {
            if (canOpen) openExternally(item) else fetch(item)
        }
        action.visibility = View.VISIBLE
    }

    private fun fetch(item: MediaItem) {
        // Mirrored videos are only a still frame here; open the official app to watch the clip.
        Toast.makeText(this, "Open the GardePro app to watch this video", Toast.LENGTH_LONG).show()
    }

    private fun openExternally(item: MediaItem) {
        // Prefer the downloaded full file; for a cloud photo the thumbnail is the full image.
        val full = GalleryStore.full(this, item)
        val src = if (full.exists()) full else GalleryStore.thumb(this, item)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", src)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, if (item.video) "video/mp4" else "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, null))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app installed that can open this file", Toast.LENGTH_LONG).show()
        }
    }
}
