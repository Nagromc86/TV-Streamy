package fr.nagromc86.iptvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

/** 2 x 1 widget: the download in progress, or how many videos are ready offline. */
public class DownloadsWidget extends AppWidgetProvider {
    private static long last;

    static void refresh(Context c, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - last < 4000) return;
        last = now;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, DownloadsWidget.class));
            if (ids.length > 0) draw(c, mgr, ids);
        } catch (Exception ignored) {
        }
    }

    static void draw(Context c, AppWidgetManager mgr, int[] ids) {
        DownloadEngine e = DownloadEngine.get(c);
        Object[] w = e.widgetInfo();
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_downloads_widget);
        boolean active = (Boolean) w[0];
        v.setTextViewText(R.id.tvs_d_title, (String) w[1]);
        v.setTextViewText(R.id.tvs_d_text, (String) w[2]);
        int pct = (Integer) w[3];
        v.setProgressBar(R.id.tvs_d_prog, 100, Math.max(0, pct), active && pct < 0);
        v.setViewVisibility(R.id.tvs_d_prog, active ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.tvs_d_icon, active ? "⬇" : "✓");
        Intent open = new Intent(c, OpenItemActivity.class);
        open.putExtra(OpenItemActivity.EXTRA_OPEN, "downloads");
        open.setData(Uri.parse("tvs://downloads"));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.tvs_d_root, PendingIntent.getActivity(c, 13000, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mgr.updateAppWidget(ids, v);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        draw(c, mgr, ids);
    }
}
