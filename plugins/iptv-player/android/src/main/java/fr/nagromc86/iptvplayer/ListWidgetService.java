package fr.nagromc86.iptvplayer;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ListWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent.getStringExtra("kind"));
    }

    static class Factory implements RemoteViewsFactory {
        private final Context c;
        private final String kind;
        private final List<JSONObject> items = new ArrayList<>();

        Factory(Context c, String kind) {
            this.c = c;
            this.kind = kind == null ? "mylist" : kind;
        }

        @Override public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            items.clear();
            try {
                JSONArray a = new JSONArray(ListWidgets.prefs(c).getString(kind, "[]"));
                for (int i = 0; i < a.length(); i++) {
                    if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
                }
            } catch (Exception ignored) {
            }
        }

        @Override public void onDestroy() { items.clear(); }
        @Override public int getCount() { return items.size(); }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean hasStableIds() { return false; }

        private static String hm(long s) {
            return new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(s * 1000L));
        }

        @Override
        public RemoteViews getViewAt(int i) {
            if (i >= items.size()) return new RemoteViews(c.getPackageName(), R.layout.tvs_row_tonight);
            JSONObject o = items.get(i);
            if ("tonight".equals(kind)) return tonight(o);
            if ("neweps".equals(kind)) return newEp(o);
            return poster(o);
        }

        private RemoteViews tonight(JSONObject o) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_row_tonight);
            v.setTextViewText(R.id.tvs_t_name, o.optString("name"));
            Bitmap logo = WidgetImages.get(c, o.optString("logo"), 120);
            if (logo != null) v.setImageViewBitmap(R.id.tvs_t_logo, logo);
            v.setViewVisibility(R.id.tvs_t_logo, logo != null ? View.VISIBLE : View.INVISIBLE);
            long now = System.currentTimeMillis() / 1000L;
            JSONArray p = o.optJSONArray("progs");
            JSONObject cur = null, prime = null;
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 21);
            cal.set(Calendar.MINUTE, 5);
            cal.set(Calendar.SECOND, 0);
            long t21 = cal.getTimeInMillis() / 1000L;
            if (now > t21) t21 = now + 1800; // after 21:05, "ensuite" = the next programme
            for (int k = 0; p != null && k < p.length(); k++) {
                JSONObject x = p.optJSONObject(k);
                if (x == null) continue;
                long s = x.optLong("s"), e = x.optLong("e");
                if (s <= now && e > now) cur = x;
                if (prime == null && e > t21 && (cur == null || x != cur) && s > now) prime = x;
                if (prime == null && s <= t21 && e > t21 && x != cur) prime = x;
            }
            if (cur != null) {
                long s = cur.optLong("s"), e = cur.optLong("e");
                v.setTextViewText(R.id.tvs_t_now, cur.optString("t"));
                v.setProgressBar(R.id.tvs_t_prog, 100, (int) Math.max(0, Math.min(100, (now - s) * 100 / Math.max(1, e - s))), false);
                v.setViewVisibility(R.id.tvs_t_prog, View.VISIBLE);
            } else {
                v.setTextViewText(R.id.tvs_t_now, "Programme non disponible");
                v.setViewVisibility(R.id.tvs_t_prog, View.GONE);
            }
            v.setTextViewText(R.id.tvs_t_next, prime != null ? hm(prime.optLong("s")) + "  " + prime.optString("t") : "");
            Intent fill = new Intent();
            fill.putExtra(ChannelWidget.EXTRA_CH, o.optString("id"));
            v.setOnClickFillInIntent(R.id.tvs_t_root, fill);
            return v;
        }

        private RemoteViews newEp(JSONObject o) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_row_newep);
            v.setTextViewText(R.id.tvs_n_name, o.optString("name"));
            long lm = o.optLong("lm");
            long days = Math.max(0, (System.currentTimeMillis() / 1000L - lm) / 86400L);
            v.setTextViewText(R.id.tvs_n_sub, (TextUtils.isEmpty(o.optString("label")) ? "Nouvel épisode" : o.optString("label"))
                    + " · " + (days == 0 ? "aujourd'hui" : days == 1 ? "hier" : "il y a " + days + " j"));
            Bitmap b = WidgetImages.get(c, o.optString("image"), 200);
            if (b != null) v.setImageViewBitmap(R.id.tvs_n_img, b);
            v.setViewVisibility(R.id.tvs_n_img, b != null ? View.VISIBLE : View.INVISIBLE);
            Intent fill = new Intent();
            fill.putExtra(OpenItemActivity.EXTRA_OPEN, "newep:" + o.optString("id"));
            v.setOnClickFillInIntent(R.id.tvs_n_root, fill);
            return v;
        }

        private RemoteViews poster(JSONObject o) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_row_poster);
            v.setTextViewText(R.id.tvs_m_title, o.optString("name"));
            v.setTextViewText(R.id.tvs_m_sub, o.optString("sub"));
            Bitmap b = WidgetImages.get(c, o.optString("image"), 420);
            if (b != null) v.setImageViewBitmap(R.id.tvs_m_img, b);
            v.setViewVisibility(R.id.tvs_m_img, b != null ? View.VISIBLE : View.INVISIBLE);
            Intent fill = new Intent();
            fill.putExtra(OpenItemActivity.EXTRA_OPEN, o.optString("key"));
            v.setOnClickFillInIntent(R.id.tvs_m_root, fill);
            return v;
        }
    }
}
