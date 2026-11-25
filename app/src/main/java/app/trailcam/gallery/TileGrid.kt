package app.trailcam.gallery

import android.content.Context
import android.graphics.Bitmap

/**
 * Where the official app's photo tiles sit on a captured screen. Learned once by the 3-tap
 * calibration (top-left tile corner, the tile to its right, the tile below) and stored as pixel
 * geometry against the capture size, so every later capture can be cropped into individual photos
 * without image analysis.
 */
data class Calibration(
    val x0: Int, val y0: Int,        // top-left corner of the first (newest) tile
    val tileW: Int, val tileH: Int,  // size of one photo tile
    val colPitch: Int, val rowPitch: Int,
    val cols: Int,
    val bottomLimit: Int,            // y below which tiles are the nav bar / cut off
    val capW: Int, val capH: Int,    // capture size this was measured at
) {
    companion object {
        fun load(ctx: Context): Calibration? {
            val sp = Prefs.sp(ctx)
            if (!sp.contains("cal_x0")) return null
            return Calibration(
                sp.getInt("cal_x0", 0), sp.getInt("cal_y0", 0),
                sp.getInt("cal_tileW", 0), sp.getInt("cal_tileH", 0),
                sp.getInt("cal_colPitch", 0), sp.getInt("cal_rowPitch", 0),
                sp.getInt("cal_cols", 1), sp.getInt("cal_bottom", 0),
                sp.getInt("cal_capW", 0), sp.getInt("cal_capH", 0)
            ).takeIf { it.tileW > 0 && it.rowPitch > 0 }
        }
    }

    fun save(ctx: Context) {
        Prefs.sp(ctx).edit()
            .putInt("cal_x0", x0).putInt("cal_y0", y0)
            .putInt("cal_tileW", tileW).putInt("cal_tileH", tileH)
            .putInt("cal_colPitch", colPitch).putInt("cal_rowPitch", rowPitch)
            .putInt("cal_cols", cols).putInt("cal_bottom", bottomLimit)
            .putInt("cal_capW", capW).putInt("cal_capH", capH)
            .apply()
    }
}

object TileGrid {

    fun isCalibrated(ctx: Context) = Calibration.load(ctx) != null

    /** Crop a captured screen into tile bitmaps, row-major from the top-left (newest first). */
    fun extract(bmp: Bitmap, cal: Calibration): List<Bitmap> {
        // Scale the calibration if the capture size differs from when it was measured.
        val sx = if (cal.capW > 0) bmp.width.toFloat() / cal.capW else 1f
        val sy = if (cal.capH > 0) bmp.height.toFloat() / cal.capH else 1f
        val x0 = (cal.x0 * sx).toInt()
        val y0 = (cal.y0 * sy).toInt()
        val tileW = (cal.tileW * sx).toInt()
        val tileH = (cal.tileH * sy).toInt()
        val colPitch = (if (cal.colPitch > 0) cal.colPitch else cal.tileW) * sx
        val rowPitch = (cal.rowPitch * sy)
        val bottom = (if (cal.bottomLimit > 0) cal.bottomLimit * sy else bmp.height.toFloat()).toInt()
        if (tileW <= 2 || tileH <= 2 || rowPitch <= 2) return emptyList()

        val cols = cal.cols.coerceAtLeast(1)
        val out = mutableListOf<Bitmap>()
        var r = 0
        while (r <= 60) {
            val top = y0 + (r * rowPitch).toInt()
            if (top + tileH > bottom || top + tileH > bmp.height) break
            for (c in 0 until cols) {
                val left = x0 + (c * colPitch).toInt()
                if (left + tileW > bmp.width || left < 0) continue
                try {
                    out.add(Bitmap.createBitmap(bmp, left, top, tileW, tileH))
                } catch (e: Exception) {
                }
            }
            r++
        }
        return out
    }
}
