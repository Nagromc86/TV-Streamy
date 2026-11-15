package fr.nagromc86.iptvplayer;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PostersService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static class Factory implements RemoteViewsFactory {
        private final Context c;
        private final List<JSONObject> items = new ArrayList<>();

        Factory(Context c) {
            this.c = c;
        }

        @Override
        public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            items.clear();
            try {
                JSONArray a = new JSONArray(PostersWidget.prefs(c).getString("list", "[]"));
                long now = System.currentTimeMillis() / 1000L;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && o.optLong("e") > now) {
                        items.add(o);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onDestroy() {
            items.clear();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        private static String when(long s) {
            long now = System.currentTimeMillis() / 1000L;
            if (s <= now) {
                return "EN DIRECT";
            }
            Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
            b.setTimeInMillis(s * 1000L);
            String hm = new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(s * 1000L));
            int d = b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR) + 365 * (b.get(Calendar.YEAR) - a.get(Calendar.YEAR));
            if (d == 0) {
                return (b.get(Calendar.HOUR_OF_DAY) >= 18 ? "Ce soir " : "Aujourd'hui ") + hm;
            }
            if (d == 1) {
                return "Demain " + hm;
            }
            String day = new SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(new Date(s * 1000L));
            return day + " · " + hm;
        }

        private Bitmap crest(String url) {
            if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
                return null;
            }
            File f = new File(c.getCacheDir(), "crest_" + Integer.toHexString(url.hashCode()) + ".png");
            if (!f.exists()) {
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(10000);
                    con.setInstanceFollowRedirects(true);
                    InputStream in = con.getInputStream();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    Bitmap b = BitmapFactory.decodeStream(in, null, o);
                    in.close();
                    if (b == null) {
                        return null;
                    }
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        b.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    return b;
                } catch (Exception e) {
                    return null;
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        }

        @Override
        public RemoteViews getViewAt(int i) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_poster_item);
            if (i >= items.size()) {
                return v;
            }
            JSONObject o = items.get(i);
            String tag = o.optString("why");
            String comp = o.optString("comp");
            v.setTextViewText(R.id.tvs_pi_tag, (TextUtils.isEmpty(tag) ? "" : "🔥 " + tag + "  ·  ") + comp);
            JSONArray t = o.optJSONArray("teams");
            boolean duel = t != null && t.length() == 2;
            if (duel) {
                v.setViewVisibility(R.id.tvs_pi_crests, View.VISIBLE);
                Bitmap a = crest(o.optString("logoA")), b = crest(o.optString("logoB"));
                if (a != null) v.setImageViewBitmap(R.id.tvs_pi_a, a);
                if (b != null) v.setImageViewBitmap(R.id.tvs_pi_b, b);
                v.setViewVisibility(R.id.tvs_pi_a, a != null ? View.VISIBLE : View.INVISIBLE);
                v.setViewVisibility(R.id.tvs_pi_b, b != null ? View.VISIBLE : View.INVISIBLE);
                v.setTextViewText(R.id.tvs_pi_title, t.optString(0) + " – " + t.optString(1));
            } else {
                v.setViewVisibility(R.id.tvs_pi_crests, View.GONE);
                v.setTextViewText(R.id.tvs_pi_title, o.optString("title"));
            }
            long s = o.optLong("s");
            String ch = o.optString("chName");
            v.setTextViewText(R.id.tvs_pi_when, when(s));
            v.setTextViewText(R.id.tvs_pi_ch, TextUtils.isEmpty(ch) ? "Chaîne non trouvée dans ton guide" : "▶ " + ch);
            Intent fill = new Intent();
            fill.putExtra(PostersWidget.EXTRA_POSTER, o.optString("id"));
            v.setOnClickFillInIntent(R.id.tvs_pi_root, fill);
            return v;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
