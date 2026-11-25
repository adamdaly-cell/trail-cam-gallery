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
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

/** One photo or video, full screen. Photos show full-resolution; videos play inline. */
class ViewerActivity : Activity() {

    companion object {
        const val EXTRA_KEY = "key"
    }

    private var item: MediaItem? = null
    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var info: TextView
    private lateinit var action: Button
    private lateinit var swipes: GestureDetector

    /** Swipe left / right for the next (older) / previous (newer) item. */
    private fun step(by: Int) {
        val all = GalleryStore.load(this)
        val next = all.getOrNull(all.indexOfFirst { it.key == item?.key } + by) ?: return
        item = next
        intent.putExtra(EXTRA_KEY, next.key)
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
        video = findViewById(R.id.video)
        info = findViewById(R.id.info)
        action = findViewById(R.id.action)
        video.setMediaController(MediaController(this).apply { setAnchorView(video) })
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
        render()
    }

    override fun onPause() {
        super.onPause()
        if (video.isPlaying) video.pause()
    }

    private fun render() {
        val item = item ?: return
        val sizeMb = if (item.size > 0) " · %.1f MB".format(item.size / 1048576.0) else ""
        info.text = shortDate(item.date) + sizeMb

        if (item.video) {
            image.visibility = View.GONE
            video.visibility = View.VISIBLE
            video.setVideoURI(item.uri)
            video.setOnPreparedListener { it.start() }
            action.text = "Open / share"
            action.visibility = View.VISIBLE
            action.setOnClickListener { openExternally(item) }
        } else {
            video.stopPlayback()
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.setImageBitmap(GalleryStore.fullImage(this, item, 2400))
            action.text = "Open / share"
            action.visibility = View.VISIBLE
            action.setOnClickListener { openExternally(item) }
        }
    }

    private fun openExternally(item: MediaItem) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, if (item.video) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, null))
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "No app can open this file", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
