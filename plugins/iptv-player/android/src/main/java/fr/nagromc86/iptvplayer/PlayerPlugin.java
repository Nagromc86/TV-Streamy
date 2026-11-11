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
import java.util.ArrayList;
import java.util.List;
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

    private static PlayerPlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    /** The player asks the app (which holds the OpenSubtitles account) to fetch French subtitles. */
    static void requestOpenSub(boolean byUser) {
        PlayerPlugin p = instance;
        if (p != null) {
            JSObject o = new JSObject();
            o.put("byUser", byUser);
            p.notifyListeners("openSubRequest", o);
        }
    }

    /* ---------- small live video inside the "Direct" page ---------- */

    private float[] rectOf(PluginCall call) {
        return new float[] {call.getFloat("x", 0f), call.getFloat("y", 0f), call.getFloat("w", 1f), call.getFloat("h", 1f), call.getBoolean("visible", true) ? 1f : 0f};
    }

    @PluginMethod
    public void miniPlay(PluginCall call) {
        String url = call.getString("url", "");
        if (TextUtils.isEmpty(url)) {
            call.reject("Adresse manquante");
            return;
        }
        float[] r = rectOf(call);
        getActivity().runOnUiThread(() -> {
            MiniPlayer.play(getActivity(), getBridge().getWebView(), url, call.getString("fallbackUrl", ""), call.getString("userAgent", ""), r, call.getBoolean("muted", false),
                    (st, detail) -> {
                        JSObject o = new JSObject();
                        o.put("state", st);
                        o.put("detail", detail);
                        notifyListeners("miniState", o);
                    });
            call.resolve();
        });
    }

    @PluginMethod
    public void miniRect(PluginCall call) {
        float[] r = rectOf(call);
        getActivity().runOnUiThread(() -> {
            MiniPlayer.place(getActivity(), getBridge().getWebView(), r);
            call.resolve();
        });
    }

    @PluginMethod
    public void miniMute(PluginCall call) {
        boolean m = call.getBoolean("muted", false);
        getActivity().runOnUiThread(() -> {
            MiniPlayer.mute(m);
            call.resolve();
        });
    }

    @PluginMethod
    public void miniStop(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            MiniPlayer.stop();
            call.resolve();
        });
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        android.app.Activity a = getActivity();
        // leaving the app with the small video playing: it goes on in a floating window (picture in picture)
        if (MiniPlayer.active() && a != null && android.os.Build.VERSION.SDK_INT >= 26
                && (a.isInPictureInPictureMode() || (android.os.Build.VERSION.SDK_INT < 31 && MiniPlayer.enterPipNow()))) {
            return;
        }
        stopMiniBg();
    }

    @Override
    protected void handleOnStop() {
        super.handleOnStop();
        // the floating window was closed
        if (MiniPlayer.active() && MiniPlayer.inPip) {
            stopMiniBg();
        }
    }

    private void stopMiniBg() {
        // the app goes to the background (or the full-screen player opens): free the provider's screen
        if (MiniPlayer.active() && getActivity() != null) {
            getActivity().runOnUiThread(MiniPlayer::stop);
            JSObject o = new JSObject();
            o.put("state", "stopped");
            o.put("detail", "pause");
            notifyListeners("miniState", o);
        }
    }

    @PluginMethod
    public void playerMessage(PluginCall call) {
        PlayerActivity open = PlayerActivity.current;
        if (open != null && !open.isFinishing()) {
            open.showMessage(call.getString("text", ""));
        }
        call.resolve();
    }

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
        intent.putExtra(PlayerActivity.EXTRA_SUB_OFFSET, call.getData().optLong("subOffset", 0L));
        intent.putExtra(PlayerActivity.EXTRA_OS_ON, call.getBoolean("osOn", false));
        intent.putExtra(PlayerActivity.EXTRA_BG_AUDIO, call.getBoolean("bgAudio", true));
        intent.putExtra(PlayerActivity.EXTRA_MUTED, call.getBoolean("startMuted", false));
        intent.putExtra(PlayerActivity.EXTRA_TS_TPL, call.getString("tsTpl", ""));
        intent.putExtra(PlayerActivity.EXTRA_TS_TZ, call.getData().optInt("tsTz", 0));
        intent.putExtra(PlayerActivity.EXTRA_CHANNELS, call.getString("channels", ""));
        intent.putExtra(PlayerActivity.EXTRA_CH_INDEX, call.getData().optInt("chIndex", -1));
        intent.putExtra(PlayerActivity.EXTRA_WATCH_URL, call.getString("watchUrl", ""));
        intent.putExtra(PlayerActivity.EXTRA_WATCH_TOKEN, call.getString("watchToken", ""));
        intent.putExtra(PlayerActivity.EXTRA_WATCH_REFRESH, call.getString("watchRefresh", ""));
        intent.putExtra(PlayerActivity.EXTRA_WATCH_KEY, call.getString("watchKey", ""));
        intent.putExtra(PlayerActivity.EXTRA_WATCH_DEV, call.getString("watchDev", ""));
        intent.putExtra(PlayerActivity.EXTRA_WATCH_SINCE, call.getData().optLong("watchSince", 0L));
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
        if (MiniPlayer.active()) {
            getActivity().runOnUiThread(MiniPlayer::stop);
        }
        PlayerActivity open = PlayerActivity.current;
        if (open != null && !open.isFinishing()) {
            // a video is still playing in its floating window: close it first (its position is saved),
            // then start this one once the first result has come back
            pendingCall = call;
            pendingIntent = intent;
            open.closeForNewVideo();
            return;
        }
        startActivityForResult(call, intent, "onPlayerClosed");
    }

    /** Subtitles found after the video started: shown in the open player. */
    @PluginMethod
    public void addSubtitles(PluginCall call) {
        PlayerActivity open = PlayerActivity.current;
        JSObject ret = new JSObject();
        if (open != null && !open.isFinishing()) {
            open.addExternalSubs(call.getString("url", ""), call.getData().optLong("offset", 0L));
            ret.put("added", true);
        } else {
            ret.put("added", false);
        }
        call.resolve(ret);
    }

    private PluginCall pendingCall;
    private Intent pendingIntent;

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
        askNotificationPermission();
        String id = DownloadEngine.get(getContext()).add(url, fileName, call.getString("title", fileName), call.getString("userAgent", ""),
                call.getBoolean("allowMobile", false), call.getInt("conns", 1));
        JSObject ret = new JSObject();
        ret.put("id", id);
        call.resolve(ret);
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && getActivity() != null
                && androidx.core.content.ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(getActivity(), new String[] {"android.permission.POST_NOTIFICATIONS"}, 4107);
        }
    }

    /** Poids réel d'une vidéo (sans la télécharger) : on demande juste le premier octet. */
    @PluginMethod
    public void probe(PluginCall call) {
        final String url = call.getString("url");
        if (url == null) { call.reject("adresse manquante"); return; }
        new Thread(() -> {
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LecteurIPTV/1.0");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Range", "bytes=0-0");
                int code = c.getResponseCode();
                long size = -1;
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d+)").matcher(cr);
                    if (m.find()) size = Long.parseLong(m.group(1));
                }
                if (size < 0 && code == 200) {
                    try { size = Long.parseLong(c.getHeaderField("Content-Length")); } catch (Exception e) { size = -1; }
                }
                JSObject r = new JSObject();
                r.put("code", code);
                r.put("size", size);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("indisponible");
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    @PluginMethod
    public void pauseDownload(PluginCall call) {
        DownloadEngine.get(getContext()).pause(call.getString("id", ""));
        call.resolve();
    }

    @PluginMethod
    public void resumeDownload(PluginCall call) {
        DownloadEngine.get(getContext()).resume(call.getString("id", ""));
        call.resolve();
    }

    /** Pause the queue while a video is streamed (one connection allowed), resume afterwards. */
    @PluginMethod
    public void holdDownloads(PluginCall call) {
        DownloadEngine.get(getContext()).setHold(call.getBoolean("hold", false));
        call.resolve();
    }

    @PluginMethod
    public void downloadStatus(PluginCall call) {
        JSArray out = new JSArray();
        DownloadEngine eng = DownloadEngine.get(getContext());
        try {
            JSArray ids = call.getArray("ids");
            List<Long> legacy = new ArrayList<>();
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);
                    if (eng.has(id)) {
                        org.json.JSONObject st = eng.status(id);
                        if (st != null) {
                            out.put(st);
                        }
                    } else {
                        try {
                            legacy.add(Long.parseLong(id));
                        } catch (NumberFormatException ignored) {
                            // unknown id
                        }
                    }
                }
            }
            if (!legacy.isEmpty()) {
                long[] list = new long[legacy.size()];
                for (int i = 0; i < list.length; i++) {
                    list[i] = legacy.get(i);
                }
                Cursor c = dm().query(new DownloadManager.Query().setFilterById(list));
                if (c != null) {
                    while (c.moveToNext()) {
                        JSObject o = new JSObject();
                        o.put("id", String.valueOf(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))));
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        o.put("status", st == DownloadManager.STATUS_SUCCESSFUL ? "done" : st == DownloadManager.STATUS_FAILED ? "failed"
                                : st == DownloadManager.STATUS_PAUSED ? "paused" : st == DownloadManager.STATUS_RUNNING ? "running" : "queued");
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
        File dir = DownloadEngine.get(getContext()).dir();
        if (dir != null) {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            ret.put("freeBytes", fs.getAvailableBytes());
        }
        call.resolve(ret);
    }

    /** Phone memory and SD card (when inserted): free space, and which one receives new downloads. */
    @PluginMethod
    public void storageInfo(PluginCall call) {
        DownloadEngine eng = DownloadEngine.get(getContext());
        JSObject ret = new JSObject();
        JSArray vols = new JSArray();
        File ph = eng.phoneDir(), sd = eng.sdDir();
        for (Object[] v : new Object[][] {{"phone", "Téléphone", ph}, {"sd", "Carte SD", sd}}) {
            File f = (File) v[2];
            if (f == null) {
                continue;
            }
            try {
                f.mkdirs();
                StatFs fs = new StatFs(f.getAbsolutePath());
                JSObject o = new JSObject();
                o.put("id", v[0]);
                o.put("label", v[1]);
                o.put("free", fs.getAvailableBytes());
                o.put("total", fs.getTotalBytes());
                vols.put(o);
            } catch (Exception ignored) {
                // volume not readable
            }
        }
        ret.put("volumes", vols);
        ret.put("current", eng.useSd() && sd != null ? "sd" : "phone");
        call.resolve(ret);
    }

    @PluginMethod
    public void setStorage(PluginCall call) {
        DownloadEngine.get(getContext()).setUseSd("sd".equals(call.getString("id", "phone")));
        call.resolve();
    }

    /** Looks inside a downloaded video: does it carry French subtitles, is its audio French? */
    @PluginMethod
    public void probeTracks(PluginCall call) {
        String uri = call.getString("uri", "");
        if (TextUtils.isEmpty(uri)) {
            call.reject("Fichier manquant");
            return;
        }
        getActivity().runOnUiThread(() -> {
            final androidx.media3.exoplayer.ExoPlayer p = new androidx.media3.exoplayer.ExoPlayer.Builder(getContext()).build();
            final boolean[] done = {false};
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable finish = () -> {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                JSObject r = new JSObject();
                boolean frText = false, frAudio = false, anyText = false;
                for (androidx.media3.common.Tracks.Group g : p.getCurrentTracks().getGroups()) {
                    for (int i = 0; i < g.length; i++) {
                        String lang = g.getTrackFormat(i).language;
                        boolean fr = lang != null && (lang.startsWith("fr") || lang.equals("fre"));
                        if (g.getType() == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                            anyText = true;
                            frText |= fr;
                        } else if (g.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                            frAudio |= fr;
                        }
                    }
                }
                r.put("frText", frText);
                r.put("frAudio", frAudio);
                r.put("anyText", anyText);
                r.put("known", !p.getCurrentTracks().isEmpty());
                p.release();
                call.resolve(r);
            };
            p.addListener(new androidx.media3.common.Player.Listener() {
                @Override
                public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                    if (!tracks.isEmpty()) {
                        finish.run();
                    }
                }

                @Override
                public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    finish.run();
                }
            });
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri));
            p.setPlayWhenReady(false);
            p.prepare();
            h.postDelayed(finish, 15000);
        });
    }

    @PluginMethod
    public void removeDownload(PluginCall call) {
        String id = call.getString("id");
        try {
            if (id != null) {
                DownloadEngine eng = DownloadEngine.get(getContext());
                if (eng.has(id)) {
                    eng.remove(id);
                } else {
                    dm().remove(Long.parseLong(id));
                }
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Suppression impossible : " + e.getMessage());
        }
    }

    /* ---------- send to a screen on the same Wi-Fi: Chromecast / Google TV and DLNA TVs ---------- */

    private DlnaHelper.Device dlnaActive;

    /** The phone keeps relaying / steering the TV with the app in the background: foreground service on / off. */
    private void castFg(boolean on, String title, String text) {
        android.content.Context ctx = getContext();
        try {
            if (on) {
                CastService.title = title;
                CastService.text = text;
                CastService.onStop = () -> {
                    DlnaHelper.Device d = dlnaActive;
                    SubServer.stopRelay();
                    if (d != null) {
                        try {
                            DlnaHelper.soap(d, "Stop", "");
                        } catch (Exception ignored) {
                            // TV already off
                        }
                        dlnaActive = null;
                    } else {
                        CastHelper.main.post(() -> CastHelper.control("stop", 0));
                    }
                    JSObject o = new JSObject();
                    o.put("state", "stopped");
                    notifyListeners("castStopped", o);
                };
                Intent i = new Intent(ctx, CastService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            } else {
                ctx.stopService(new Intent(ctx, CastService.class));
            }
        } catch (Exception ignored) {
            // no notification: casting still works while the app is open
        }
    }

    @PluginMethod
    public void castScan(PluginCall call) {
        CastHelper.main.post(() -> CastHelper.startScan(getContext()));
        new Thread(() -> {
            java.util.List<DlnaHelper.Device> dl = DlnaHelper.discover(getContext(), 2500);
            CastHelper.main.post(() -> {
                JSArray out = new JSArray();
                for (String[] c : CastHelper.devices()) {
                    JSObject o = new JSObject();
                    o.put("id", c[0]);
                    o.put("name", c[1]);
                    o.put("model", c[2]);
                    o.put("type", "cast");
                    out.put(o);
                }
                for (DlnaHelper.Device x : dl) {
                    JSObject o = new JSObject();
                    o.put("id", x.id);
                    o.put("name", x.name);
                    o.put("model", x.model);
                    o.put("type", "dlna");
                    out.put(o);
                }
                JSObject ret = new JSObject();
                ret.put("devices", out);
                call.resolve(ret);
            });
        }).start();
    }

    @PluginMethod
    public void castStopScan(PluginCall call) {
        CastHelper.main.post(CastHelper::stopScan);
        call.resolve();
    }

    @PluginMethod
    public void castPlay(PluginCall call) {
        String id = call.getString("id", "");
        String url = call.getString("url", "");
        String title = call.getString("title", "");
        String subtitle = call.getString("subtitle", "");
        String image = call.getString("image", "");
        String mime = call.getString("mime", "video/mp4");
        long startMs = call.getData().optLong("startMs", 0L);
        boolean live = call.getBoolean("live", false);
        boolean subsOn = call.getBoolean("subsOn", true);
        String srtUrl = null;
        String vttUrl = null;
        JSArray subs = call.getArray("subtitles");
        if (subs != null && subs.length() > 0) {
            org.json.JSONObject first = subs.optJSONObject(0);
            String data = first == null ? "" : first.optString("url", "");
            int comma = data.indexOf(',');
            if (data.startsWith("data:") && comma > 0) {
                try {
                    String srt = new String(android.util.Base64.decode(data.substring(comma + 1), android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
                    String[] urls = SubServer.publish(srt, call.getData().optLong("subOffset", 0L));
                    if (urls != null) {
                        srtUrl = urls[0];
                        vttUrl = urls[1];
                    }
                } catch (Exception ignored) {
                    // play without subtitles
                }
            }
        }
        final String srtFinal = srtUrl;
        final String vttFinal = vttUrl;
        if (id.startsWith("dlna:")) {
            DlnaHelper.Device dev = DlnaHelper.devices.get(id);
            if (dev == null) {
                call.reject("TV introuvable, relance la recherche");
                return;
            }
            String ua = call.getString("userAgent", "");
            new Thread(() -> {
                try {
                    String m = DlnaHelper.mimeFor(dev, mime);
                    // the phone relays the stream to the TV (redirections, https, picky TVs); direct address if it cannot
                    String viaPhone = call.getBoolean("direct", false) ? null : SubServer.relay(getContext(), url, m, live, ua, android.net.Uri.parse(dev.control).getHost());
                    DlnaHelper.play(dev, viaPhone != null ? viaPhone : url, title, m, live ? 0 : startMs, subsOn ? srtFinal : null, live);
                    dlnaActive = dev;
                    castFg(true, title, "Diffusion sur " + dev.name);
                    call.resolve();
                } catch (Exception e) {
                    call.reject("Envoi vers la TV impossible : " + e.getMessage());
                }
            }).start();
            return;
        }
        dlnaActive = null;
        CastHelper.main.post(() -> CastHelper.play(getContext(), id, url, title, subtitle, image, mime, startMs, live, vttFinal, subsOn, new CastHelper.Done() {
            @Override
            public void ok() {
                castFg(true, title, "Diffusion sur l'écran");
                call.resolve();
            }

            @Override
            public void fail(String why) {
                call.reject(why);
            }
        }));
    }

    @PluginMethod
    public void castStatus(PluginCall call) {
        DlnaHelper.Device dev = dlnaActive;
        if (dev != null) {
            new Thread(() -> {
                JSObject ret = new JSObject();
                try {
                    String st = DlnaHelper.state(dev);
                    long[] pos = DlnaHelper.position(dev);
                    ret.put("state", "PLAYING".equals(st) ? "playing" : "PAUSED_PLAYBACK".equals(st) ? "paused"
                            : "TRANSITIONING".equals(st) ? "buffering" : "STOPPED".equals(st) || "NO_MEDIA_PRESENT".equals(st) ? "idle" : "buffering");
                    ret.put("positionMs", pos[0]);
                    ret.put("durationMs", pos[1]);
                } catch (Exception e) {
                    ret.put("state", "unreachable");
                }
                ret.put("relayHits", SubServer.relayHits);
                if (SubServer.relayErr != null) {
                    ret.put("relayErr", SubServer.relayErr);
                }
                call.resolve(ret);
            }).start();
            return;
        }
        CastHelper.main.post(() -> {
            Object[] st = CastHelper.status();
            JSObject ret = new JSObject();
            ret.put("state", st[0]);
            ret.put("positionMs", st[1]);
            ret.put("durationMs", st[2]);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void castControl(PluginCall call) {
        String action = call.getString("action", "");
        if ("release".equals(action)) {
            // the video ended or could not start: nothing to keep alive in the background any more
            castFg(false, null, null);
            SubServer.stopRelay();
            call.resolve();
            return;
        }
        long ms = call.getData().optLong("ms", 0L);
        DlnaHelper.Device dev = dlnaActive;
        if (dev != null) {
            new Thread(() -> {
                try {
                    switch (action) {
                        case "pause":
                            DlnaHelper.soap(dev, "Pause", "");
                            break;
                        case "play":
                            DlnaHelper.soap(dev, "Play", "<Speed>1</Speed>");
                            break;
                        case "seek":
                            DlnaHelper.seek(dev, ms);
                            break;
                        case "stop":
                        case "disconnect":
                            castFg(false, null, null);
                            SubServer.stopRelay();
                            DlnaHelper.soap(dev, "Stop", "");
                            dlnaActive = null;
                            break;
                        default:
                            break;
                    }
                    call.resolve();
                } catch (Exception e) {
                    if ("stop".equals(action) || "disconnect".equals(action)) {
                        SubServer.stopRelay();
                        dlnaActive = null;
                    }
                    call.reject("La TV ne répond pas : " + e.getMessage());
                }
            }).start();
            return;
        }
        if ("stop".equals(action) || "disconnect".equals(action)) {
            castFg(false, null, null);
        }
        CastHelper.main.post(() -> {
            CastHelper.control(action, ms);
            call.resolve();
        });
    }

    /** Screen mirroring: Samsung Smart View, else Android's "Cast" screen, only if this phone has it. */
    @PluginMethod
    public void castMirror(PluginCall call) {
        android.content.pm.PackageManager pm = getContext().getPackageManager();
        java.util.List<Intent> tries = new java.util.ArrayList<>();
        Intent smart = pm.getLaunchIntentForPackage("com.samsung.android.smartmirroring");
        if (smart != null) {
            tries.add(smart);
        }
        Intent sv = new Intent("com.samsung.android.smartmirroring.CAPTURE");
        tries.add(sv);
        tries.add(new Intent("android.settings.CAST_SETTINGS"));
        tries.add(new Intent("android.settings.WIFI_DISPLAY_SETTINGS"));
        for (Intent i : tries) {
            if (i.resolveActivity(pm) == null) {
                continue;
            }
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                JSObject ret = new JSObject();
                ret.put("opened", true);
                call.resolve(ret);
                return;
            } catch (Exception ignored) {
                // try the next one
            }
        }
        JSObject ret = new JSObject();
        ret.put("opened", false);
        call.resolve(ret);
    }

    /** Opens Android's share sheet (Messages, WhatsApp, mail...) with a text. */
    @PluginMethod
    public void shareText(PluginCall call) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, call.getString("title", ""));
            send.putExtra(Intent.EXTRA_TEXT, call.getString("text", ""));
            Intent chooser = Intent.createChooser(send, "Partager TV Streamy");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject("Partage impossible : " + e.getMessage());
        }
    }

    /** Tells the app whether it runs on a TV (Android TV, Google TV...). */
    @PluginMethod
    public void deviceInfo(PluginCall call) {
        boolean tv = false;
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) getContext().getSystemService(Context.UI_MODE_SERVICE);
            tv = um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
            tv = tv || getContext().getPackageManager().hasSystemFeature("android.software.leanback");
        } catch (Exception ignored) {
            // phone
        }
        JSObject r = new JSObject();
        r.put("tv", tv);
        call.resolve(r);
    }

    @ActivityCallback
    private void onPlayerClosed(PluginCall call, ActivityResult result) {
        if (call == null) {
            launchPending();
            return;
        }
        JSObject ret = new JSObject();
        Intent data = result.getData();
        if (data != null) {
            ret.put("positionMs", data.getLongExtra(PlayerActivity.RESULT_POSITION, 0L));
            ret.put("durationMs", data.getLongExtra(PlayerActivity.RESULT_DURATION, 0L));
            ret.put("ended", data.getBooleanExtra(PlayerActivity.RESULT_ENDED, false));
            ret.put("subOffsetMs", data.getLongExtra(PlayerActivity.RESULT_SUB_OFFSET, 0L));
            ret.put("usedFallback", data.getBooleanExtra(PlayerActivity.RESULT_USED_FALLBACK, false));
            String zsid = data.getStringExtra(PlayerActivity.RESULT_SID);
            if (zsid != null) {
                ret.put("sid", zsid);
            }
            String taken = data.getStringExtra(PlayerActivity.RESULT_TAKEN_BY);
            if (taken != null) {
                ret.put("takenBy", taken);
            }
            String error = data.getStringExtra(PlayerActivity.RESULT_ERROR);
            if (error != null) {
                ret.put("error", error);
            }
        }
        call.resolve(ret);
        launchPending();
    }

    private void launchPending() {
        if (pendingIntent != null) {
            PluginCall next = pendingCall;
            Intent intent = pendingIntent;
            pendingCall = null;
            pendingIntent = null;
            // give the provider a moment to see the previous stream closed
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> startActivityForResult(next, intent, "onPlayerClosed"), 700);
        }
    }
}
