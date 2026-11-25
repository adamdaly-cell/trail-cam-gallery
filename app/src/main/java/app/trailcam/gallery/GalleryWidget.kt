package app.trailcam.gallery

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen widget: a scrolling grid of the newest photos, with a sync button. */
class GalleryWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx, id))
        @Suppress("DEPRECATION")
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_grid)
    }

    companion object {
        const val EXTRA_SYNC = "sync"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, GalleryWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) mgr.updateAppWidget(id, build(ctx, id))
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_grid)
        }

        private fun build(ctx: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_gallery)

            val adapter = Intent(ctx, GalleryWidgetService::class.java)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME) + "#$widgetId")
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_grid, adapter)
            views.setEmptyView(R.id.widget_grid, R.id.widget_empty)

            val state = SyncState.status.value
            val last = Prefs.lastSync(ctx)
            views.setTextViewText(
                R.id.widget_sub,
                when {
                    state.running -> state.text.ifBlank { "Syncing…" }
                    state.error != null -> state.error
                    last > 0 -> "Updated " + SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(last))
                    else -> "Not synced yet"
                }
            )
            views.setViewVisibility(R.id.widget_refresh, if (state.running) View.INVISIBLE else View.VISIBLE)

            val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val openApp = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), immutable)
            views.setOnClickPendingIntent(R.id.widget_header, openApp)

            // Mirroring needs the screen-capture consent dialog, which must come from an activity,
            // so the widget's refresh opens the app and starts a sync there.
            val refresh = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SYNC, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), immutable
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh)

            val viewer = PendingIntent.getActivity(
                ctx, 3, Intent(ctx, ViewerActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_grid, viewer)
            return views
        }
    }
}

class GalleryWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val ctx: Context) : RemoteViewsFactory {
        private var items: List<MediaItem> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            items = GalleryStore.load(ctx).filter { GalleryStore.thumb(ctx, it).exists() }.take(60)
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews? {
            val item = items.getOrNull(position) ?: return null
            val views = RemoteViews(ctx.packageName, R.layout.item_media)
            GalleryStore.decode(GalleryStore.thumb(ctx, item), 240)?.let {
                views.setImageViewBitmap(R.id.item_image, it)
            }
            views.setViewVisibility(R.id.item_badge, if (item.video) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.item_date, shortDate(item.date))
            views.setOnClickFillInIntent(R.id.item_root, Intent().putExtra(ViewerActivity.EXTRA_KEY, item.key))
            return views
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = items.getOrNull(position)?.key?.hashCode()?.toLong() ?: 0L
        override fun hasStableIds(): Boolean = true
    }
}

/** "2026-11-24 07:15:02" -> "24 Nov 07:15" */
fun shortDate(date: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(date)
    if (parsed != null) SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(parsed) else date
} catch (e: Exception) {
    date
}
