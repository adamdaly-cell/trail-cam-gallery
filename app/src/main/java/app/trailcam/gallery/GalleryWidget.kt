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

/** Home-screen widget: a scrolling grid of the saved GardePro photos and videos. */
class GalleryWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx, id))
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_grid)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) refreshAll(ctx)
    }

    companion object {
        const val ACTION_REFRESH = "app.trailcam.gallery.REFRESH_WIDGET"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, GalleryWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) mgr.updateAppWidget(id, build(ctx, id))
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_grid)
        }

        private fun build(ctx: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_gallery)

            val adapter = Intent(ctx, GalleryWidgetService::class.java)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME) + "#$widgetId")
            views.setRemoteAdapter(R.id.widget_grid, adapter)
            views.setEmptyView(R.id.widget_grid, R.id.widget_empty)

            val last = Prefs.lastScan(ctx)
            views.setTextViewText(
                R.id.widget_sub,
                if (last > 0) "Updated " + SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(last))
                else "Tap ↻ after saving in GardePro"
            )
            views.setViewVisibility(R.id.widget_refresh, View.VISIBLE)

            val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val openApp = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), immutable)
            views.setOnClickPendingIntent(R.id.widget_header, openApp)

            // Refresh re-scans the saved-media folder in place (no screen capture needed any more).
            val refresh = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, GalleryWidget::class.java).setAction(ACTION_REFRESH), immutable
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
            items = GalleryStore.load(ctx).take(80)
            Prefs.setLastScan(ctx, System.currentTimeMillis())
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews? {
            val item = items.getOrNull(position) ?: return null
            val views = RemoteViews(ctx.packageName, R.layout.item_media)
            GalleryStore.thumbnail(ctx, item, 240)?.let {
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
