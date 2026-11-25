package app.trailcam.gallery

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One photo or video on the camera. */
data class MediaItem(val id: Int, val video: Boolean, val date: String, val size: Long) {
    /** Stable local name. Ids restart after the SD card is formatted, so the date is part of it. */
    val key: String get() = "${id}_" + date.filter { it.isDigit() }

    /** The type token the camera's file routes expect. */
    val ext: String get() = if (video) "MP4" else "JPG"
}

object Prefs {
    const val DEFAULT_WIFI_PASS = "1234567890"
    const val DEFAULT_NAME_PREFIX = "CAM"

    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("camera", Context.MODE_PRIVATE)

    fun bleMac(ctx: Context): String? = sp(ctx).getString("ble_mac", null)
    fun bleName(ctx: Context): String? = sp(ctx).getString("ble_name", null)
    fun namePrefix(ctx: Context): String =
        sp(ctx).getString("name_prefix", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME_PREFIX
    fun wifiPass(ctx: Context): String =
        sp(ctx).getString("wifi_pass", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_WIFI_PASS
    fun ssid(ctx: Context): String = sp(ctx).getString("ssid", "") ?: ""
    fun directUrl(ctx: Context): String = (sp(ctx).getString("direct_url", "") ?: "").trim().trimEnd('/')
    fun fullPhotos(ctx: Context): Boolean = sp(ctx).getBoolean("full_photos", true)
    fun lastSync(ctx: Context): Long = sp(ctx).getLong("last_sync", 0L)
    fun cameraInfo(ctx: Context): String = sp(ctx).getString("camera_info", "") ?: ""
}

/** Local copy of the camera's gallery: an index plus thumbnail and full-size files. */
object GalleryStore {
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.filesDir, "media").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    fun thumb(ctx: Context, item: MediaItem) = File(dir(ctx), "t_${item.key}.jpg")
    fun full(ctx: Context, item: MediaItem) = File(dir(ctx), "f_${item.key}.${item.ext.lowercase()}")

    fun load(ctx: Context): List<MediaItem> = synchronized(lock) {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                MediaItem(o.getInt("id"), o.getBoolean("video"), o.getString("date"), o.optLong("size"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(ctx: Context, key: String): MediaItem? = load(ctx).firstOrNull { it.key == key }

    /**
     * Replace the index with what the camera now reports. Items that have left the camera
     * (card rotated or formatted) are kept only if their full-size file was downloaded.
     */
    fun merge(ctx: Context, onCamera: List<MediaItem>): List<MediaItem> = synchronized(lock) {
        val keys = onCamera.map { it.key }.toSet()
        val kept = load(ctx).filter { it.key !in keys && full(ctx, it).exists() }
        val all = (onCamera + kept).distinctBy { it.key }
            .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.id })
        val arr = JSONArray()
        for (it in all) {
            arr.put(JSONObject().put("id", it.id).put("video", it.video).put("date", it.date).put("size", it.size))
        }
        val tmp = File(dir(ctx), "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile(ctx))
        // Drop files that no longer belong to any item.
        val valid = all.flatMap { listOf(thumb(ctx, it).name, full(ctx, it).name) }.toSet() + "index.json"
        dir(ctx).listFiles()?.forEach { if (it.name !in valid && !it.name.endsWith(".part")) it.delete() }
        all
    }

    fun clear(ctx: Context) = synchronized(lock) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Decode an image file scaled down to roughly [maxPx] on its longest side. */
    fun decode(file: File, maxPx: Int): Bitmap? {
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) {
            null
        }
    }
}
