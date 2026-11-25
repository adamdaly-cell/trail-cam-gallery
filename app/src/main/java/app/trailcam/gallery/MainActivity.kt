package app.trailcam.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val scope = MainScope()
    private val loader = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var items: List<MediaItem> = emptyList()
    private val adapter = Adapter()

    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var syncButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status)
        emptyText = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        syncButton = findViewById(R.id.sync)

        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape is short on height: put the title and status on one line.
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
        syncButton.setOnClickListener { startSync() }
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }

        scope.launch { SyncState.galleryVersion.collectLatest { reload() } }
        scope.launch { SyncState.status.collectLatest { showStatus(it) } }
        if (intent?.getBooleanExtra(GalleryWidget.EXTRA_SYNC, false) == true) startSync()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(GalleryWidget.EXTRA_SYNC, false)) startSync()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        scope.cancel()
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        items = GalleryStore.load(this)
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        showStatus(SyncState.status.value)
    }

    private fun showStatus(s: SyncState.Status) {
        progress.visibility = if (s.running) View.VISIBLE else View.INVISIBLE
        syncButton.isEnabled = !s.running
        val last = Prefs.lastSync(this)
        statusText.text = when {
            s.running -> s.text
            s.error != null -> s.error
            last > 0 -> listOf(
                "${items.size} items",
                "synced " + SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(last)),
                Prefs.cameraInfo(this)
            ).filter { it.isNotBlank() }.joinToString(" · ")
            else -> getString(R.string.not_synced)
        }
    }

    private fun startSync() {
        val need = SyncService.missingPermissions(this).toMutableList()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Prefs.sp(this).getBoolean("asked_notifications", false)
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
            Prefs.sp(this).edit().putBoolean("asked_notifications", true).apply()
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        SyncService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        GalleryWidget.refreshAll(this)
        if (SyncService.missingPermissions(this).isEmpty()) {
            SyncService.start(this)
        } else {
            statusText.text = getString(R.string.need_bluetooth)
        }
    }

    private fun showSettings() {
        val sp = Prefs.sp(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(label: String, value: String, hint: String): EditText {
            box.addView(TextView(this).apply { text = label; setPadding(0, pad / 2, 0, 0) })
            return EditText(this).apply {
                setText(value)
                this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
                box.addView(this)
            }
        }
        val pass = field("Camera Wi-Fi password", sp.getString("wifi_pass", "") ?: "", "Factory default")
        val ssid = field("Camera Wi-Fi name (only if it is not found)", Prefs.ssid(this), "Automatic")
        val direct = field("Direct camera address (advanced)", Prefs.directUrl(this), "Off — use Bluetooth + Wi-Fi")
        val full = CheckBox(this).apply {
            text = "Also download full-size photos (newest 100)"
            isChecked = Prefs.fullPhotos(this@MainActivity)
            box.addView(this)
        }
        val paired = Prefs.bleName(this) ?: Prefs.bleMac(this)
        box.addView(TextView(this).apply {
            text = if (paired != null) "Camera: $paired" else "No camera remembered yet"
            setPadding(0, pad / 2, 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                sp.edit()
                    .putString("wifi_pass", pass.text.toString().trim())
                    .putString("ssid", ssid.text.toString().trim())
                    .putString("direct_url", direct.text.toString().trim())
                    .putBoolean("full_photos", full.isChecked)
                    .apply()
                GalleryWidget.refreshAll(this)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Forget camera") { _, _ ->
                sp.edit().remove("ble_mac").remove("ble_name").apply()
            }
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
                val file = GalleryStore.thumb(this@MainActivity, item)
                loader.execute {
                    val bmp = GalleryStore.decode(file, 320) ?: return@execute
                    cache.put(item.key, bmp)
                    holder.image.post { if (holder.image.tag == item.key) holder.image.setImageBitmap(bmp) }
                }
            }
        }
    }
}
