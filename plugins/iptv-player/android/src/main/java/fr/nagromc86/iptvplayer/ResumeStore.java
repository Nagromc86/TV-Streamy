package fr.nagromc86.iptvplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Where the player writes the position of the video being watched, every few seconds.
 * The app reads it back when it comes to the front: the position survives even if Android
 * closed the app while the video was playing (the usual way the "resume" was lost).
 */
final class ResumeStore {
    private static final String PREFS = "tvs_resume";

    private ResumeStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void savePosition(Context c, String key, String meta, long pos, long dur, boolean ended) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            SharedPreferences p = prefs(c);
            JSONObject all = new JSONObject(p.getString("pending", "{}"));
            JSONObject o = new JSONObject();
            o.put("pos", Math.max(0L, pos));
            o.put("dur", Math.max(0L, dur));
            o.put("ended", ended);
            o.put("at", System.currentTimeMillis());
            o.put("meta", meta == null ? "" : meta);
            all.put(key, o);
            p.edit().putString("pending", all.toString()).putLong("p_" + key, ended ? 0L : Math.max(0L, pos)).apply();
        } catch (Exception ignored) {
        }
    }

    /** Last known position of a video (kept, unlike "pending"). */
    static long positionOf(Context c, String key) {
        return prefs(c).getLong("p_" + key, 0L);
    }

    /** The video's player launch (url, title, subtitles mode…), to start it again from the widget. */
    static synchronized void saveLaunch(Context c, String key, android.os.Bundle extras) {
        if (key == null || key.isEmpty() || extras == null) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            for (String k : extras.keySet()) {
                if (k.startsWith("watch") || k.equals(PlayerActivity.EXTRA_START_MS)) {
                    continue;
                }
                Object v = extras.get(k);
                if (v instanceof String || v instanceof Boolean || v instanceof Long || v instanceof Integer) {
                    o.put(k, v);
                }
            }
            SharedPreferences p = prefs(c);
            JSONObject all = new JSONObject(p.getString("launch", "{}"));
            all.put(key, o);
            // keep only the last 30 videos
            if (all.length() > 30) {
                java.util.Iterator<String> it = all.keys();
                if (it.hasNext()) {
                    String first = it.next();
                    if (!first.equals(key)) {
                        all.remove(first);
                    }
                }
            }
            p.edit().putString("launch", all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static JSONObject launchOf(Context c, String key) {
        try {
            JSONObject all = new JSONObject(prefs(c).getString("launch", "{}"));
            return all.optJSONObject(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** Positions not yet taken by the app (and forgets them). */
    static synchronized String takePending(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("pending", "{}");
        p.edit().remove("pending").apply();
        return s;
    }

    /* ---------- what the "Reprendre la lecture" widget shows ---------- */

    static synchronized void setWidget(Context c, String title, String subtitle, String image, int pct, String key) {
        SharedPreferences p = prefs(c);
        String oldImage = p.getString("w_image", "");
        p.edit()
                .putString("w_title", title == null ? "" : title)
                .putString("w_subtitle", subtitle == null ? "" : subtitle)
                .putString("w_image", image == null ? "" : image)
                .putInt("w_pct", Math.max(0, Math.min(100, pct)))
                .putString("w_key", key == null ? "" : key)
                .apply();
        ResumeWidget.refresh(c, image != null && !image.equals(oldImage));
    }

    /** Only the progress bar changes (called by the player while it plays). */
    static synchronized void setWidgetProgress(Context c, String key, String title, String subtitle, String image, int pct) {
        SharedPreferences p = prefs(c);
        if (key != null && key.equals(p.getString("w_key", "")) && p.getInt("w_pct", -1) == pct) {
            return;
        }
        setWidget(c, title, subtitle, image, pct, key);
    }

    static String get(Context c, String k) {
        return prefs(c).getString(k, "");
    }

    static int getPct(Context c) {
        return prefs(c).getInt("w_pct", 0);
    }
}
