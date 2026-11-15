package fr.nagromc86.iptvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 1 x 1 widget: one favourite channel, one tap = the channel plays. Several can sit on the home screen. */
public class ChannelWidget extends AppWidgetProvider {

    static final String EXTRA_CH = "tvs_channel";
    private static final String PREFS = "tvs_channels";

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Favourite channels sent by the app: [{id,name,logo,url,fallbackUrl}]. */
    static void setChannels(Context c, String json) {
        prefs(c).edit().putString("list", json == null ? "[]" : json).apply();
        final Context ctx = c.getApplicationContext();
        new Thread(() -> {
            JSONArray a = channels(ctx);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !logoFile(ctx, o.optString("id")).exists()) {
                    fetchLogo(ctx, o.optString("id"), o.optString("logo"));
                }
            }
            drawAll(ctx);
        }).start();
    }

    static JSONArray channels(Context c) {
        try {
            return new JSONArray(prefs(c).getString("list", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static JSONObject channel(Context c, String id) {
        JSONArray a = channels(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id"))) {
                return o;
            }
        }
        // the channel left the favourites: keep what the widget knew about it
        try {
            String s = prefs(c).getString("ch_" + id, "");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static void assign(Context c, int widgetId, JSONObject ch) {
        prefs(c).edit().putString("w_" + widgetId, ch.optString("id")).putString("ch_" + ch.optString("id"), ch.toString()).apply();
    }

    static File logoFile(Context c, String id) {
        return new File(c.getFilesDir(), "chlogo_" + id.replaceAll("[^A-Za-z0-9_-]", "_") + ".png");
    }

    static void fetchLogo(Context c, String id, String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            return;
        }
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(12000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TVStreamy");
            InputStream in = con.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0 && bo.size() < 4 * 1024 * 1024) {
                bo.write(b, 0, n);
            }
            in.close();
            byte[] buf = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(buf, 0, buf.length, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 200) {
                s *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeByteArray(buf, 0, buf.length, o2);
            if (bm != null) {
                try (FileOutputStream out = new FileOutputStream(logoFile(c, id))) {
                    bm.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    static void drawAll(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, ChannelWidget.class));
            for (int id : ids) {
                draw(c, mgr, id);
            }
        } catch (Exception ignored) {
        }
    }

    static void draw(Context c, AppWidgetManager mgr, int widgetId) {
        String chId = prefs(c).getString("w_" + widgetId, "");
        JSONObject ch = chId.isEmpty() ? null : channel(c, chId);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_channel_widget);
        String name = ch != null ? ch.optString("name") : "Choisir";
        v.setTextViewText(R.id.tvs_c_name, name);
        Bitmap b = null;
        if (ch != null) {
            File f = logoFile(c, chId);
            if (f.exists()) {
                b = BitmapFactory.decodeFile(f.getAbsolutePath());
            }
        }
        if (b != null) {
            v.setImageViewBitmap(R.id.tvs_c_logo, b);
            v.setViewVisibility(R.id.tvs_c_logo, View.VISIBLE);
            v.setViewVisibility(R.id.tvs_c_init, View.GONE);
        } else {
            v.setViewVisibility(R.id.tvs_c_logo, View.GONE);
            v.setViewVisibility(R.id.tvs_c_init, View.VISIBLE);
            v.setTextViewText(R.id.tvs_c_init, ch != null ? initials(name) : "+");
        }
        Intent i;
        if (ch != null) {
            i = new Intent(c, ChannelLaunchActivity.class);
            i.putExtra(EXTRA_CH, chId);
        } else {
            i = new Intent(c, ChannelConfigActivity.class);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        }
        i.setData(Uri.parse("tvs://channel/" + widgetId));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 8000 + widgetId, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.tvs_c_root, pi);
        mgr.updateAppWidget(widgetId, v);
    }

    private static String initials(String n) {
        String s = n == null ? "" : n.replaceAll("^[A-Z]{2,3}\\s*[|:-]\\s*", "").trim();
        return s.length() > 3 ? s.substring(0, 3).toUpperCase() : s.toUpperCase();
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            draw(context, mgr, id);
        }
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor e = prefs(context).edit();
        for (int id : ids) {
            e.remove("w_" + id);
        }
        e.apply();
    }
}
