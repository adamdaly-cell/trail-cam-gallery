package app.trailcam.gallery

import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CamException(message: String) : IOException(message)

/**
 * The camera's local HTTP API (port 8080 on its own hotspot).
 *
 * Routes come from community reverse-engineering of GardePro Wi-Fi cameras (E6/E8/E9P):
 *   /cmd/standby/reset                      keep-alive; the hotspot shuts down without it
 *   /cmd/standby/now                        put the camera back to sleep
 *   /cmd/info/{1..5}                        device, power, storage, clock, versions
 *   /list/detail/{type}/{startId}/{count}   newest-first listing below startId
 *   /thumb/{id}/{JPG|MP4}                   thumbnail
 *   /file/{id}/{JPG|MP4}                    full-size file
 *
 * [network] pins requests to the camera's Wi-Fi so the phone's normal connection is untouched.
 */
class CameraApi(private val base: String, private val network: Network?) {

    private fun open(path: String, timeoutMs: Int): HttpURLConnection {
        val url = URL(base + path)
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        // The camera's server corrupts responses on reused sockets.
        conn.setRequestProperty("Connection", "close")
        return conn
    }

    private fun <T> request(path: String, timeoutMs: Int, body: (HttpURLConnection) -> T): T {
        val conn = open(path, timeoutMs)
        try {
            val code = conn.responseCode
            if (code != 200) throw CamException("Camera answered HTTP $code for $path")
            return body(conn)
        } finally {
            conn.disconnect()
        }
    }

    fun getJson(path: String, timeoutMs: Int = 15_000): JSONObject =
        request(path, timeoutMs) { c -> JSONObject(c.inputStream.bufferedReader().use { it.readText() }) }

    /** Download to [dest] through a .part file so a broken transfer never looks complete. */
    fun download(path: String, dest: File, timeoutMs: Int = 30_000) {
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            request(path, timeoutMs) { c ->
                val expected = c.contentLengthLong
                var total = 0L
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
                if (total == 0L) throw CamException("Camera sent an empty file for $path")
                if (expected > 0 && total != expected) throw CamException("Download cut short for $path")
            }
            if (!part.renameTo(dest)) throw CamException("Could not save ${dest.name}")
        } finally {
            part.delete()
        }
    }

    fun keepAlive(): Boolean = try {
        getJson("/cmd/standby/reset", 6_000)
        true
    } catch (e: Exception) {
        false
    }

    fun sleepNow() {
        try {
            getJson("/cmd/standby/now", 4_000)
        } catch (_: Exception) {
        }
    }

    /** Battery / storage summary for the header, built from whatever fields the camera reports. */
    fun infoSummary(): String {
        val parts = mutableListOf<String>()
        fun data(n: Int): JSONObject? = try {
            getJson("/cmd/info/$n", 8_000).optJSONObject("data")
        } catch (e: Exception) {
            null
        }
        data(1)?.let { d ->
            val name = listOf("product", "model", "name").map { d.optString(it) }.firstOrNull { it.isNotBlank() }
            if (name != null) parts += name
        }
        data(2)?.let { d ->
            val pct = listOf("vol_value", "battery", "percent").firstOrNull { d.has(it) }?.let { d.optString(it) }
            if (!pct.isNullOrBlank()) parts += "battery $pct" + if (pct.all { it.isDigit() }) "%" else ""
            if (d.has("temperature")) parts += "${d.optString("temperature")}°"
        }
        data(3)?.let { d ->
            val total = listOf("total", "total_space", "totalSpace").firstOrNull { d.has(it) }?.let { d.optDouble(it) }
            val used = listOf("used", "used_space", "usedSpace").firstOrNull { d.has(it) }?.let { d.optDouble(it) }
            if (total != null && used != null && total > 0) parts += "SD ${(used * 100 / total).toInt()}% full"
        }
        return parts.joinToString(" · ")
    }

    /**
     * Every file on the card, newest first. startId is an exclusive upper bound and the firmware
     * caps each page, so keep asking below the lowest id seen until nothing new comes back.
     * The type token is ignored by the firmware except "MP4", which it rejects — "JPG" lists both.
     */
    fun listAll(): List<MediaItem> {
        val items = LinkedHashMap<Int, MediaItem>()
        var start = 999_999
        while (true) {
            val batch = entries(getJson("/list/detail/JPG/$start/500", 30_000))
            var lowest = start
            var added = 0
            for (i in 0 until batch.length()) {
                val o = batch.optJSONObject(i) ?: continue
                val id = o.optInt("id", -1)
                if (id < 0 || items.containsKey(id)) continue
                val type = o.opt("type")?.toString().orEmpty()
                val video = type == "2" || type.contains("MP4", true) || type.contains("video", true)
                val date = listOf("date", "time", "timestamp").map { o.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
                items[id] = MediaItem(id, video, date, o.optLong("size"))
                if (id < lowest) lowest = id
                added++
            }
            if (added == 0 || lowest >= start) break
            start = lowest
        }
        return items.values.toList()
    }

    private fun entries(listing: JSONObject): JSONArray {
        for (key in listOf("data", "list", "files", "detail")) {
            listing.optJSONArray(key)?.let { return it }
            listing.optJSONObject(key)?.let { inner ->
                for (k2 in listOf("list", "files", "detail", "data")) inner.optJSONArray(k2)?.let { return it }
            }
        }
        return JSONArray()
    }
}
