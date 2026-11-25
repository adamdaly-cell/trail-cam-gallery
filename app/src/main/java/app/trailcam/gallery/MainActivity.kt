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
        syncButton.setOnClickListener { startMirror() }
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }

        scope.launch { SyncState.galleryVersion.collectLatest { reload() } }
        scope.launch { SyncState.status.collectLatest { showStatus(it) } }
        scope.launch { MirrorState.status.collectLatest { showStatus(SyncState.status.value) } }
        scope.launch {
            MirrorState.error.collectLatest { err ->
                if (err != null) { showStatus(SyncState.status.value) }
            }
        }
        scope.launch {
            MirrorState.lastAdded.collectLatest { n ->
                if (n > 0) android.widget.Toast.makeText(this@MainActivity, "Added $n new photo(s)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        if (intent?.getBooleanExtra(GalleryWidget.EXTRA_SYNC, false) == true) startMirror()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(GalleryWidget.EXTRA_SYNC, false)) startMirror()
    }

    /** Capture the official GardePro app's gallery. First run walks through calibration. */
    private fun startMirror() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Prefs.sp(this).getBoolean("asked_notifications", false)
        ) {
            Prefs.sp(this).edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        val mode = if (TileGrid.isCalibrated(this)) MirrorService.MODE_REFRESH else MirrorService.MODE_CALIBRATE
        if (mode == MirrorService.MODE_CALIBRATE) {
            android.widget.Toast.makeText(this, "First, a quick one-time setup: I'll open GardePro and you'll tap the photo corners.", android.widget.Toast.LENGTH_LONG).show()
        }
        MirrorState.error.value = null
        CaptureActivity.start(this, mode)
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
        emptyText.setText(R.string.empty_help_mirror)
        showStatus(SyncState.status.value)
    }

    private fun showStatus(s: SyncState.Status) {
        val mirror = MirrorState.status.value
        val busy = s.running || mirror.isNotEmpty()
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        syncButton.isEnabled = !busy
        val last = Prefs.lastSync(this)
        statusText.text = when {
            mirror.isNotEmpty() -> mirror
            MirrorState.error.value != null && !s.running -> MirrorState.error.value
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
        when (requestCode) {
            2 -> startMirror()   // notification permission answered; proceed to capture
            else -> if (SyncService.missingPermissions(this).isEmpty()) SyncService.start(this)
            else statusText.text = getString(R.string.need_bluetooth)
        }
    }

    private fun showSettings() {
        val sp = Prefs.sp(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun heading(text: String) = box.addView(TextView(this).apply {
            this.text = text
            setPadding(0, pad, 0, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        fun field(label: String, value: String, hint: String, password: Boolean = false): EditText {
            box.addView(TextView(this).apply { this.text = label; setPadding(0, pad / 2, 0, 0) })
            return EditText(this).apply {
                setText(value)
                this.hint = hint
                inputType = InputType.TYPE_CLASS_TEXT or
                    if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
                box.addView(this)
            }
        }

        heading("How it works")
        box.addView(TextView(this).apply {
            text = "Sync opens the official GardePro app, reads the photos on its gallery screen, " +
                "and shows them here and in the home-screen widget. It never wakes the camera, so it " +
                "uses no camera battery.\n\nMake sure the GardePro app is installed and signed in."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val calibrated = TileGrid.isCalibrated(this)
        box.addView(TextView(this).apply {
            text = if (calibrated) "Photo layout: calibrated ✓" else "Photo layout: not set up yet"
            setPadding(0, pad / 2, 0, 0)
        })

        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setNeutralButton("Redo photo setup") { _, _ ->
                Prefs.sp(this).edit()
                    .remove("cal_x0").remove("cal_y0").remove("cal_tileW").remove("cal_tileH")
                    .remove("cal_colPitch").remove("cal_rowPitch").remove("cal_cols").remove("cal_bottom")
                    .remove("cal_capW").remove("cal_capH").apply()
                android.widget.Toast.makeText(this, "Tap Sync to set up the photo layout again.", android.widget.Toast.LENGTH_LONG).show()
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
