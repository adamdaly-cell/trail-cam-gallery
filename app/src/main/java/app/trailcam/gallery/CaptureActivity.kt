package app.trailcam.gallery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Asks for the one-time screen-capture permission, then hands the token to [MirrorService].
 * Transparent: it shows the system consent dialog and nothing else.
 */
class CaptureActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        private const val REQ = 7001

        fun start(ctx: Context, mode: String) {
            ctx.startActivity(
                Intent(ctx, CaptureActivity::class.java)
                    .putExtra(EXTRA_MODE, mode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private var mode = MirrorService.MODE_REFRESH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MirrorService.MODE_REFRESH
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data)
                .putExtra(MirrorService.EXTRA_MODE, mode)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } else {
            Toast.makeText(this, "Screen capture permission is needed to mirror the gallery", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
