package app.trailcam.gallery

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * One photo or video that the official GardePro app has saved to this device, under
 * DCIM/GardePro Mobile/. These are the real, full-resolution files (and playable videos), read
 * straight from shared storage — not screen captures.
 */
data class MediaItem(
    val uri: Uri,
    val video: Boolean,
    val date: Long,      // capture time, epoch millis
    val size: Long,
    val name: String,    // file name, used as a stable key
) {
    val key: String get() = name
}

object Prefs {
    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("camera", Context.MODE_PRIVATE)
    fun lastScan(ctx: Context): Long = sp(ctx).getLong("last_scan", 0L)
    fun setLastScan(ctx: Context, t: Long) = sp(ctx).edit().putLong("last_scan", t).apply()
}

/**
 * Reads the GardePro-saved media from shared storage. The folder fills when the user runs
 * Select → Save in the official GardePro app; this just lists whatever is there, newest first.
 */
object GalleryStore {

    /** The album the official GardePro app saves downloaded media into. */
    const val ALBUM = "GardePro Mobile"

    private val stampPattern = Pattern.compile("(\\d{14})")

    fun load(ctx: Context): List<MediaItem> {
        val all = ArrayList<MediaItem>()
        query(ctx, video = false, all)
        query(ctx, video = true, all)
        all.sortWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.name })
        return all
    }

    fun find(ctx: Context, key: String): MediaItem? = load(ctx).firstOrNull { it.key == key }

    private fun query(ctx: Context, video: Boolean, out: MutableList<MediaItem>) {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val dateCol = if (video) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            dateCol,
        )
        // Match the GardePro album by its relative path (e.g. "DCIM/GardePro Mobile/").
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$ALBUM%")
        try {
            ctx.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val takenIdx = c.getColumnIndex(dateCol)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx) ?: id.toString()
                    val size = c.getLong(sizeIdx)
                    val date = dateFromName(name)
                        ?: (if (takenIdx >= 0 && !c.isNull(takenIdx)) c.getLong(takenIdx) else 0L)
                            .takeIf { it > 0 }
                        ?: c.getLong(modIdx) * 1000L
                    out.add(MediaItem(ContentUris.withAppendedId(collection, id), video, date, size, name))
                }
            }
        } catch (_: Exception) {
            // Missing permission or provider hiccup: just return what we have.
        }
    }

    /** GardePro names files like MAC_YYYYMMDDHHMMSS_seq_X.ext; pull the capture time out of that. */
    private fun dateFromName(name: String): Long? {
        val m = stampPattern.matcher(name)
        if (!m.find()) return null
        return try {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(m.group(1)!!)?.time
        } catch (_: Exception) {
            null
        }
    }

    /** A grid-sized thumbnail for an item (works for both photos and videos). */
    fun thumbnail(ctx: Context, item: MediaItem, px: Int): Bitmap? = try {
        ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
    } catch (_: Throwable) {
        null
    }

    /** The full image, downsampled to roughly [maxPx] on its longest side. Photos only. */
    fun fullImage(ctx: Context, item: MediaItem, maxPx: Int): Bitmap? {
        if (item.video) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Throwable) {
            null
        }
    }
}

/** epoch millis -> "24 Nov 07:15"; 0 -> "" */
fun shortDate(ms: Long): String {
    if (ms <= 0) return ""
    return SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
}
