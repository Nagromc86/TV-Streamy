package fr.nagromc86.iptvplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Small image cache for the widgets (posters, logos), called from background threads. */
final class WidgetImages {
    private WidgetImages() {}

    static Bitmap get(Context c, String url, int maxSide) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            return null;
        }
        File f = new File(c.getCacheDir(), "wimg_" + Integer.toHexString(url.hashCode()) + "_" + maxSide + ".png");
        if (f.exists()) {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
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
            while ((n = in.read(b)) > 0 && bo.size() < 6 * 1024 * 1024) {
                bo.write(b, 0, n);
            }
            in.close();
            byte[] buf = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(buf, 0, buf.length, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) {
                s *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeByteArray(buf, 0, buf.length, o2);
            if (bm == null) {
                return null;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return bm;
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }
}
