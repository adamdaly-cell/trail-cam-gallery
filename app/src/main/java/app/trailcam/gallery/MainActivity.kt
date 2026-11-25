package app.trailcam.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        const val GARDEPRO_PKG = "com.zpszjs.gardepro.mobile"
        private const val REQ_MEDIA = 1
    }

    private val loader = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var items: List<MediaItem> = emptyList()
    private val adapter = Adapter()

    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var syncButton: Button

    private val mediaPerms: Array<String> = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasMediaPerms() = mediaPerms.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status)
        emptyText = findViewById(R.id.empty)
        syncButton = findViewById(R.id.sync)
        findViewById<View>(R.id.progress).visibility = View.GONE

        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            findViewById<LinearLayout>(R.id.titles).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            findViewById<TextView>(R.id.title).textSize = 17f
            statusText.maxLines = 1
            statusText.setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
        }
        val columns = (resources.displayMetrics.widthPixels / (110 * resources.displayMetrics.density)).toInt().coerceAtLeast(3)
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columns)
            adapter = this@MainActivity.adapter
        }
        syncButton.text = getString(R.string.get_new)
        syncButton.setOnClickListener { openGardePro() }
        findViewById<ImageButton>(R.id.settings).setOnClickListener { showSettings() }

        if (!hasMediaPerms()) requestPermissions(mediaPerms, REQ_MEDIA)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    /** Open the official GardePro app so the user can Select → Save new media to the device. */
    private fun openGardePro() {
        val intent = packageManager.getLaunchIntentForPackage(GARDEPRO_PKG)
        if (intent == null) {
            Toast.makeText(this, "The official GardePro app isn't installed.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "In GardePro: Select → Select All → the save icon. Then come back.", Toast.LENGTH_LONG).show()
        startActivity(intent)
    }

    private fun reload() {
        if (!hasMediaPerms()) {
            items = emptyList()
            adapter.notifyDataSetChanged()
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(R.string.need_permission)
            statusText.text = getString(R.string.need_permission_short)
            return
        }
        loader.execute {
            val loaded = GalleryStore.load(this)
            runOnUiThread {
                items = loaded
                adapter.notifyDataSetChanged()
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                emptyText.text = getString(R.string.empty_help)
                val videos = items.count { it.video }
                statusText.text = when {
                    items.isEmpty() -> getString(R.string.no_media)
                    else -> "${items.size} items" + if (videos > 0) " · $videos video(s)" else ""
                }
                GalleryWidget.refreshAll(this)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA) reload()
    }

    private fun showSettings() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(TextView(this).apply {
            text = "This gallery shows the full-resolution photos and videos that the official " +
                "GardePro app has saved to this device (DCIM/GardePro Mobile).\n\n" +
                "To add new media: open GardePro, tap Select, then Select All, then the save icon. " +
                "It downloads from GardePro's cloud — it never wakes the camera, so it uses no " +
                "camera battery. Come back here (or tap Refresh) and the new items appear."
            setPadding(0, pad / 2, 0, pad / 2)
        })

        AlertDialog.Builder(this)
            .setTitle("How it works")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .setNeutralButton("Open GardePro") { _, _ -> openGardePro() }
            .setNegativeButton("Refresh") { _, _ -> reload() }
            .show()
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.item_image)
        val badge: TextView = view.findViewById(R.id.item_badge)
        val date: TextView = view.findViewById(R.id.item_date)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
            val span = (parent as RecyclerView).layoutManager.let { (it as GridLayoutManager).spanCount }
            view.layoutParams.height = parent.width / span
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.badge.visibility = if (item.video) View.VISIBLE else View.GONE
            holder.date.text = shortDate(item.date)
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@MainActivity, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_KEY, item.key))
            }
            holder.image.tag = item.key
            val cached = cache.get(item.key)
            holder.image.setImageBitmap(cached)
            if (cached == null) {
                loader.execute {
                    val bmp = GalleryStore.thumbnail(this@MainActivity, item, 320) ?: return@execute
                    cache.put(item.key, bmp)
                    holder.image.post { if (holder.image.tag == item.key) holder.image.setImageBitmap(bmp) }
                }
            }
        }
    }
}
