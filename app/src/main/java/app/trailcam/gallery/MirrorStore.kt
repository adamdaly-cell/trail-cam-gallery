package app.trailcam.gallery

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Holds the photos mirrored from the official GardePro app's gallery screen. Each tile cropped from
 * a screen capture is de-duplicated by a perceptual hash, so re-capturing the same photos does not
 * add duplicates and genuinely new photos are prepended. Reuses [GalleryStore]'s files and index so
 * the gallery grid, viewer and widget display mirror photos with no changes.
 */
object MirrorStore {

    private const val CAP = 400               // keep at most this many tiles
    private const val HAMMING_DUP = 12        // signatures within this distance are the same photo

    /**
     * Identity signature of a tile. Trail-cam photos are the same scene differing only by the
     * moving subject, so a whole-image hash collapses them. The burned-in caption strip (date, time,
     * temperature) is unique per photo, so the signature is a fine-grained difference hash of that
     * bottom strip: re-captures of the same photo match, genuinely different photos do not.
     */
    private fun signature(bmp: Bitmap): java.math.BigInteger {
        val stripTop = (bmp.height * 0.82f).toInt().coerceIn(0, bmp.height - 2)
        val strip = Bitmap.createBitmap(bmp, 0, stripTop, bmp.width, bmp.height - stripTop)
        val w = 40; val h = 8
        val small = Bitmap.createScaledBitmap(strip, w + 1, h, true)
        var bits = java.math.BigInteger.ZERO
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (lum(small.getPixel(x, y)) > lum(small.getPixel(x + 1, y))) bits = bits.setBit(i)
            i++
        }
        small.recycle(); strip.recycle()
        return bits
    }

    private fun lum(p: Int): Int {
        val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
        return (r * 30 + g * 59 + b * 11) / 100
    }

    private fun hamming(a: String, b: String): Int =
        try { java.math.BigInteger(a, 16).xor(java.math.BigInteger(b, 16)).bitCount() } catch (e: Exception) { 999 }

    private fun hashOf(item: MediaItem): String? =
        item.cloudKey?.removePrefix("mirror:")?.takeIf { item.cloudKey.startsWith("mirror:") }

    /**
     * Add freshly captured tiles (newest first). Returns how many were new. Existing photos (by
     * perceptual hash) are skipped; new ones are written and placed ahead of older history.
     */
    fun add(ctx: Context, tilesNewestFirst: List<Bitmap>): Int {
        val existing = GalleryStore.load(ctx).toMutableList()
        val knownHashes = existing.mapNotNull { hashOf(it) }.toMutableList()
        val fresh = mutableListOf<MediaItem>()
        var nextId = (existing.maxOfOrNull { it.id } ?: 1000) + 1

        // Process oldest-first within this batch so the newest ends up at the very front.
        for (bmp in tilesNewestFirst.asReversed()) {
            val hash = signature(bmp).toString(16)
            if (knownHashes.any { hamming(it, hash) <= HAMMING_DUP }) continue
            knownHashes.add(hash)
            // date drives ordering in the grid; newer capture => later stamp.
            val stamp = nowStamp(nextId)
            val item = MediaItem(nextId, video = false, date = stamp, size = 0, cloudKey = "mirror:$hash")
            val full = GalleryStore.full(ctx, item)
            writeJpeg(bmp, full, 90)
            writeJpeg(Bitmap.createScaledBitmap(bmp, 320, (320 * bmp.height / bmp.width).coerceAtLeast(1), true),
                GalleryStore.thumb(ctx, item), 80)
            fresh.add(0, item)   // newest first within the batch
            nextId++
        }
        if (fresh.isEmpty()) return 0

        val all = (fresh + existing).take(CAP)
        writeIndex(ctx, all)
        // Drop files for any item trimmed past the cap.
        val valid = all.flatMap { listOf(GalleryStore.thumb(ctx, it).name, GalleryStore.full(ctx, it).name) }.toSet()
        File(ctx.filesDir, "media").listFiles()?.forEach {
            if (it.name !in valid && it.name != "index.json") it.delete()
        }
        return fresh.size
    }

    private fun nowStamp(seq: Int): String {
        // Encode capture order into the stamp so newer captures sort above older ones.
        val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return d.format(java.util.Date(System.currentTimeMillis() + seq * 1000L))
    }

    private fun writeIndex(ctx: Context, items: List<MediaItem>) {
        val arr = org.json.JSONArray()
        for (it in items) arr.put(
            org.json.JSONObject().put("id", it.id).put("video", it.video)
                .put("date", it.date).put("size", it.size).put("cloudKey", it.cloudKey ?: "")
        )
        val dir = File(ctx.filesDir, "media").apply { mkdirs() }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(dir, "index.json"))
    }

    private fun writeJpeg(bmp: Bitmap, dest: File, quality: Int) {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
