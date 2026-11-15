package fr.nagromc86.iptvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

/** Data and drawing shared by the collection widgets (Ce soir, Nouveaux épisodes, Ma liste). */
final class ListWidgets {
    private ListWidgets() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("tvs_lists", Context.MODE_PRIVATE);
    }

    static void set(Context c, String kind, String json) {
        prefs(c).edit().putString(kind, json == null ? "[]" : json).apply();
        refresh(c, kind);
    }

    static Class<?> providerOf(String kind) {
        if ("tonight".equals(kind)) return TonightWidget.class;
        if ("neweps".equals(kind)) return NewEpisodesWidget.class;
        return MyListWidget.class;
    }

    static void refresh(Context c, String kind) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, providerOf(kind)));
            if (ids.length == 0) return;
            for (int id : ids) draw(c, mgr, id, kind);
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.tvs_l_list);
        } catch (Exception ignored) {
        }
    }

    static void draw(Context c, AppWidgetManager mgr, int id, String kind) {
        boolean flip = "mylist".equals(kind);
        RemoteViews v = new RemoteViews(c.getPackageName(), flip ? R.layout.tvs_flip_widget : R.layout.tvs_list_widget);
        Intent svc = new Intent(c, ListWidgetService.class);
        svc.putExtra("kind", kind);
        svc.setData(Uri.parse("tvs://list/" + kind + "/" + id));
        v.setRemoteAdapter(R.id.tvs_l_list, svc);
        v.setEmptyView(R.id.tvs_l_list, R.id.tvs_l_empty);
        String title = "tonight".equals(kind) ? "Ce soir à la télé" : "neweps".equals(kind) ? "Nouveaux épisodes" : "Ma liste";
        String empty = "tonight".equals(kind) ? "Mets des chaînes en favoris (étoile) dans TV Streamy, puis ouvre l’appli."
                : "neweps".equals(kind) ? "Aucun nouvel épisode dans les séries de Ma liste." : "Ma liste est vide.";
        if (!flip) v.setTextViewText(R.id.tvs_l_title, title);
        v.setTextViewText(R.id.tvs_l_empty, empty);
        Intent tpl = new Intent(c, "tonight".equals(kind) ? ChannelLaunchActivity.class : OpenItemActivity.class);
        tpl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        v.setPendingIntentTemplate(R.id.tvs_l_list, PendingIntent.getActivity(c, 11000 + id, tpl, fl));
        Intent open = new Intent(c, OpenItemActivity.class);
        open.putExtra(OpenItemActivity.EXTRA_OPEN, "tonight".equals(kind) ? "live" : "neweps".equals(kind) ? "series" : "mylist");
        open.setData(Uri.parse("tvs://list/open/" + kind + "/" + id));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent op = PendingIntent.getActivity(c, 12000 + id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!flip) v.setOnClickPendingIntent(R.id.tvs_l_title, op);
        v.setOnClickPendingIntent(R.id.tvs_l_empty, op);
        mgr.updateAppWidget(id, v);
    }
}
