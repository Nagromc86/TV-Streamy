package fr.nagromc86.iptvplayer;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.File;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Opens the native full-screen player and reports where playback stopped,
 * so the web side can save "resume" positions.
 */
@CapacitorPlugin(name = "IptvPlayer")
public class PlayerPlugin extends Plugin {

    @PluginMethod
    public void play(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("Adresse du flux manquante");
            return;
        }
        Intent intent = new Intent(getContext(), PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URL, url);
        intent.putExtra(PlayerActivity.EXTRA_FALLBACK_URL, call.getString("fallbackUrl", ""));
        intent.putExtra(PlayerActivity.EXTRA_TITLE, call.getString("title", ""));
        intent.putExtra(PlayerActivity.EXTRA_SUBTITLE, call.getString("subtitle", ""));
        intent.putExtra(PlayerActivity.EXTRA_START_MS, call.getData().optLong("startMs", 0L));
        intent.putExtra(PlayerActivity.EXTRA_LIVE, call.getBoolean("live", false));
        intent.putExtra(PlayerActivity.EXTRA_USER_AGENT, call.getString("userAgent", ""));
        intent.putExtra(PlayerActivity.EXTRA_SUB_MODE, call.getString("subMode", "auto"));
        JSArray subs = call.getArray("subtitles");
        if (subs != null && subs.length() > 0) {
            String[] urls = new String[subs.length()];
            String[] labels = new String[subs.length()];
            String[] langs = new String[subs.length()];
            for (int i = 0; i < subs.length(); i++) {
                org.json.JSONObject o = subs.optJSONObject(i);
                urls[i] = o == null ? "" : o.optString("url", "");
                labels[i] = o == null ? "" : o.optString("label", "Sous-titres");
                langs[i] = o == null ? "" : o.optString("lang", "fr");
            }
            intent.putExtra(PlayerActivity.EXTRA_SUB_URLS, urls);
            intent.putExtra(PlayerActivity.EXTRA_SUB_LABELS, labels);
            intent.putExtra(PlayerActivity.EXTRA_SUB_LANGS, langs);
        }
        startActivityForResult(call, intent, "onPlayerClosed");
    }

    /* ---------- offline downloads (films and episodes) ---------- */

    private DownloadManager dm() {
        return (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @PluginMethod
    public void download(PluginCall call) {
        String url = call.getString("url");
        String fileName = call.getString("fileName");
        if (url == null || fileName == null) {
            call.reject("Adresse ou nom de fichier manquant");
            return;
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(call.getString("title", fileName));
            r.setDescription("TV Streamy · téléchargement pour regarder hors connexion");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(getContext(), Environment.DIRECTORY_MOVIES, fileName);
            String ua = call.getString("userAgent", "");
            r.addRequestHeader("User-Agent", TextUtils.isEmpty(ua) ? "Mozilla/5.0 (Linux; Android) LecteurIPTV/1.0" : ua);
            boolean mobile = call.getBoolean("allowMobile", false);
            r.setAllowedOverMetered(mobile);
            r.setAllowedOverRoaming(false);
            long id = dm().enqueue(r);
            JSObject ret = new JSObject();
            ret.put("id", String.valueOf(id));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Téléchargement impossible : " + e.getMessage());
        }
    }

    @PluginMethod
    public void downloadStatus(PluginCall call) {
        JSArray out = new JSArray();
        try {
            JSArray ids = call.getArray("ids");
            if (ids != null && ids.length() > 0) {
                long[] list = new long[ids.length()];
                for (int i = 0; i < ids.length(); i++) {
                    list[i] = Long.parseLong(ids.getString(i));
                }
                Cursor c = dm().query(new DownloadManager.Query().setFilterById(list));
                if (c != null) {
                    while (c.moveToNext()) {
                        JSObject o = new JSObject();
                        o.put("id", String.valueOf(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))));
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        o.put("status", st == DownloadManager.STATUS_SUCCESSFUL ? "done" : st == DownloadManager.STATUS_FAILED ? "failed"
                                : st == DownloadManager.STATUS_PAUSED ? "paused" : st == DownloadManager.STATUS_RUNNING ? "running" : "pending");
                        o.put("reason", c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
                        o.put("bytes", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                        o.put("total", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                        String local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                        if (local != null) {
                            o.put("localUri", local);
                        }
                        out.put(o);
                    }
                    c.close();
                }
            }
        } catch (Exception e) {
            call.reject("Suivi des téléchargements impossible : " + e.getMessage());
            return;
        }
        JSObject ret = new JSObject();
        ret.put("items", out);
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir != null) {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            ret.put("freeBytes", fs.getAvailableBytes());
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void removeDownload(PluginCall call) {
        String id = call.getString("id");
        try {
            if (id != null) {
                dm().remove(Long.parseLong(id));
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Suppression impossible : " + e.getMessage());
        }
    }

    @ActivityCallback
    private void onPlayerClosed(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        JSObject ret = new JSObject();
        Intent data = result.getData();
        if (data != null) {
            ret.put("positionMs", data.getLongExtra(PlayerActivity.RESULT_POSITION, 0L));
            ret.put("durationMs", data.getLongExtra(PlayerActivity.RESULT_DURATION, 0L));
            ret.put("ended", data.getBooleanExtra(PlayerActivity.RESULT_ENDED, false));
            String error = data.getStringExtra(PlayerActivity.RESULT_ERROR);
            if (error != null) {
                ret.put("error", error);
            }
        }
        call.resolve(ret);
    }
}
