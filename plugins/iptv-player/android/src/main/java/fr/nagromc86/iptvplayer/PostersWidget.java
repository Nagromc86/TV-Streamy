package fr.nagromc86.iptvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

/** 2 x 2 widget: the "affiches à ne pas manquer" of the sports hub, one after the other. */
public class PostersWidget extends AppWidgetProvider {
    static final String EXTRA_POSTER = "tvs_poster";
    static final String EXTRA_SPORTS = "tvs_sports";

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("tvs_posters", Context.MODE_PRIVATE);
    }

    static void setPosters(Context c, String json) {
        prefs(c).edit().putString("list", json == null ? "[]" : json).putLong("at", System.currentTimeMillis()).apply();
        refreshAll(c);
    }

    static void refreshAll(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, PostersWidget.class));
            if (ids.length > 0) {
                for (int id : ids) {
                    draw(c, mgr, id);
                }
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.tvs_p_flip);
            }
        } catch (Exception ignored) {
        }
    }

    static void draw(Context c, AppWidgetManager mgr, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_posters_widget);
        Intent svc = new Intent(c, PostersService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(Uri.parse("tvs://posters/" + id));
        v.setRemoteAdapter(R.id.tvs_p_flip, svc);
        v.setEmptyView(R.id.tvs_p_flip, R.id.tvs_p_empty);
        Intent tap = new Intent(c, PosterLaunchActivity.class);
        tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        v.setPendingIntentTemplate(R.id.tvs_p_flip, PendingIntent.getActivity(c, 9000 + id, tap, fl));
        Intent open = new Intent(c, PosterLaunchActivity.class);
        open.setData(Uri.parse("tvs://posters/open/" + id));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.tvs_p_empty, PendingIntent.getActivity(c, 9500 + id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mgr.updateAppWidget(id, v);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            draw(context, mgr, id);
        }
        // relative times ("ce soir", "demain") and finished matches
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.tvs_p_flip);
    }
}
