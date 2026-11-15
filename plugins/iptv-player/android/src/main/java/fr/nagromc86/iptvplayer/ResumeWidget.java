package fr.nagromc86.iptvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Home-screen widget (4 x 1): the last video started, its progress, and one tap to resume it. */
public class ResumeWidget extends AppWidgetProvider {

    static final String EXTRA_RESUME = "tvs_resume";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        draw(context, mgr, ids);
    }

    /** Redraws every widget; downloads the poster again when it changed. */
    static void refresh(Context c, boolean newImage) {
        final Context ctx = c.getApplicationContext();
        if (newImage) {
            new Thread(() -> {
                fetchPoster(ctx, ResumeStore.get(ctx, "w_image"));
                drawAll(ctx);
            }).start();
        } else {
            drawAll(ctx);
        }
    }

    private static void drawAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ResumeWidget.class));
            if (ids != null && ids.length > 0) {
                draw(ctx, mgr, ids);
            }
        } catch (Exception ignored) {
        }
    }

    private static File posterFile(Context c) {
        return new File(c.getFilesDir(), "widget_poster.jpg");
    }

    private static void fetchPoster(Context c, String url) {
        File f = posterFile(c);
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            f.delete();
            return;
        }
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TVStreamy");
            InputStream in = con.getInputStream();
            byte[] buf = readAll(in);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(buf, 0, buf.length, o);
            int s = 1;
            while (o.outHeight / (s * 2) >= 240) {
                s *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeByteArray(buf, 0, buf.length, o2);
            if (b == null) {
                f.delete();
                return;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.JPEG, 85, out);
            }
        } catch (Exception e) {
            f.delete();
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() > 6 * 1024 * 1024) {
                break;
            }
        }
        in.close();
        return bo.toByteArray();
    }

    private static void draw(Context c, AppWidgetManager mgr, int[] ids) {
        String title = ResumeStore.get(c, "w_title");
        String sub = ResumeStore.get(c, "w_subtitle");
        int pct = ResumeStore.getPct(c);
        boolean has = !TextUtils.isEmpty(title);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_resume_widget);
        v.setTextViewText(R.id.tvs_w_title, has ? title : "Rien à reprendre");
        v.setTextViewText(R.id.tvs_w_sub, has ? (TextUtils.isEmpty(sub) ? "Reprendre la lecture" : sub) : "Ouvrir TV Streamy");
        v.setProgressBar(R.id.tvs_w_prog, 100, pct, false);
        v.setViewVisibility(R.id.tvs_w_prog, has && pct > 0 ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.tvs_w_play, has ? View.VISIBLE : View.GONE);
        Bitmap b = null;
        File f = posterFile(c);
        if (has && f.exists()) {
            b = BitmapFactory.decodeFile(f.getAbsolutePath());
        }
        if (b != null) {
            v.setImageViewBitmap(R.id.tvs_w_img, b);
            v.setViewVisibility(R.id.tvs_w_img, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.tvs_w_img, View.GONE);
        }
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (open == null) {
            open = new Intent();
        }
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (has) {
            open.putExtra(EXTRA_RESUME, true);
        }
        PendingIntent pi = PendingIntent.getActivity(c, has ? 7101 : 7100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.tvs_w_root, pi);
        v.setOnClickPendingIntent(R.id.tvs_w_play, pi);
        mgr.updateAppWidget(ids, v);
    }
}
