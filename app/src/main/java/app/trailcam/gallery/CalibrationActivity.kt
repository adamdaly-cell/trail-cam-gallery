package app.trailcam.gallery

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * One-time setup: the user taps four points on a real capture so the app learns where the photo
 * tiles sit. Tap 1 = top-left corner of the newest (top-left) photo, tap 2 = the tile to its right,
 * tap 3 = the tile directly below, tap 4 = just under the lowest fully-visible photo.
 */
class CalibrationActivity : Activity() {

    private val taps = mutableListOf<PointF>()   // in bitmap pixels
    private lateinit var view: CalView
    private lateinit var prompt: TextView
    private val prompts = listOf(
        "Tap the TOP-LEFT corner of the newest (top) photo",
        "Tap the BOTTOM-RIGHT corner of that same photo",
        "Tap the TOP-LEFT corner of the photo directly BELOW it",
        "Tap just BELOW the lowest photo that is fully visible"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = MirrorService.calibrationFrame(this)
        val bmp = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        if (bmp == null) {
            Toast.makeText(this, "No capture to calibrate from", Toast.LENGTH_LONG).show(); finish(); return
        }
        val root = FrameLayout(this)
        view = CalView(bmp)
        root.addView(view)
        prompt = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 48, 32, 48)
            text = prompts[0]
        }
        root.addView(prompt, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
    }

    private fun onTap(bx: Float, by: Float) {
        taps.add(PointF(bx, by))
        view.invalidate()
        if (taps.size < prompts.size) {
            prompt.text = prompts[taps.size]
        } else {
            save()
        }
    }

    private fun save() {
        val tl = taps[0]; val br = taps[1]; val below = taps[2]; val bottom = taps[3]
        val x0 = tl.x.roundToInt()
        val y0 = tl.y.roundToInt()
        val tileW = (br.x - tl.x).roundToInt()
        val tileH = (br.y - tl.y).roundToInt()
        val rowPitch = (below.y - tl.y).roundToInt()
        if (tileW < 40 || tileH < 40 || rowPitch < 40) {
            Toast.makeText(this, "Those taps were too close together — try again", Toast.LENGTH_LONG).show()
            taps.clear(); prompt.text = prompts[0]; view.invalidate(); return
        }
        Calibration(
            x0 = x0, y0 = y0, tileW = tileW, tileH = tileH,
            colPitch = tileW, rowPitch = rowPitch, cols = 1,
            bottomLimit = bottom.y.roundToInt(),
            capW = view.bmp.width, capH = view.bmp.height
        ).save(this)
        Toast.makeText(this, "Calibrated. Tap Sync to mirror your photos.", Toast.LENGTH_LONG).show()
        finish()
    }

    /** Shows the capture fit-to-screen and records taps mapped back to bitmap pixels. */
    private inner class CalView(val bmp: android.graphics.Bitmap) : View(this) {
        private val dot = Paint().apply { color = Color.rgb(0x3F, 0x8F, 0x5F); isAntiAlias = true }
        private val ring = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
        }
        private var scale = 1f
        private var offX = 0f
        private var offY = 0f

        private fun recomputeFit() {
            scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            offX = (width - bmp.width * scale) / 2
            offY = (height - bmp.height * scale) / 2
        }

        override fun onDraw(canvas: Canvas) {
            recomputeFit()
            canvas.drawColor(Color.BLACK)
            canvas.save()
            canvas.translate(offX, offY)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            canvas.restore()
            for (p in taps) {
                val vx = offX + p.x * scale; val vy = offY + p.y * scale
                canvas.drawCircle(vx, vy, 14f, dot)
                canvas.drawCircle(vx, vy, 14f, ring)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                recomputeFit()
                val bx = ((event.x - offX) / scale).coerceIn(0f, bmp.width.toFloat())
                val by = ((event.y - offY) / scale).coerceIn(0f, bmp.height.toFloat())
                onTap(bx, by)
                return true
            }
            return super.onTouchEvent(event)
        }
    }
}
