package fr.nagromc86.iptvplayer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Our own download queue (replaces Android's DownloadManager):
 * one video at a time, resumes where it stopped (HTTP Range), retries by itself,
 * can split a file over several connections when the subscription allows it,
 * and pauses while a video is being streamed (providers often allow a single connection).
 */
class DownloadEngine {

    static class Task {
        String id;
        String url;
        String file;
        String title;
        String ua;
        boolean mobile;
        int conns = 1;
        long total;
        long bytes;
        String status = "queued"; // queued, running, paused, done, failed
        String error = "";
        boolean userPaused;
        int attempts;
        long retryAt;
        long added;
        long[] segStart;
        long[] segEnd;
        long[] segPos;
    }

    private static DownloadEngine inst;

    static synchronized DownloadEngine get(Context c) {
        if (inst == null) {
            inst = new DownloadEngine(c.getApplicationContext());
        }
        return inst;
    }

    private final Context ctx;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private volatile boolean hold;
    private volatile Task current;
    private volatile boolean stopCurrent;
    private Thread worker;
    private volatile String waitReason = "";
    private volatile double speed;

    private DownloadEngine(Context c) {
        ctx = c;
        load();
    }

    File dir() {
        File d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (d == null) {
            d = new File(ctx.getFilesDir(), "Movies");
        }
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /* ---------- persistence: the queue survives the app being closed ---------- */

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("tvs_downloads", Context.MODE_PRIVATE);
    }

    private synchronized void load() {
        try {
            JSONArray a = new JSONArray(prefs().getString("tasks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Task t = new Task();
                t.id = o.getString("id");
                t.url = o.getString("url");
                t.file = o.getString("file");
                t.title = o.optString("title");
                t.ua = o.optString("ua");
                t.mobile = o.optBoolean("mobile");
                t.conns = o.optInt("conns", 1);
                t.total = o.optLong("total");
                t.bytes = o.optLong("bytes");
                t.status = o.optString("status", "queued");
                if ("running".equals(t.status)) {
                    t.status = "queued";
                }
                t.error = o.optString("error");
                t.userPaused = o.optBoolean("userPaused");
                t.added = o.optLong("added");
                JSONArray s = o.optJSONArray("seg");
                if (s != null && s.length() > 0) {
                    int n = s.length() / 3;
                    t.segStart = new long[n];
                    t.segEnd = new long[n];
                    t.segPos = new long[n];
                    for (int j = 0; j < n; j++) {
                        t.segStart[j] = s.getLong(j * 3);
                        t.segEnd[j] = s.getLong(j * 3 + 1);
                        t.segPos[j] = s.getLong(j * 3 + 2);
                    }
                }
                tasks.put(t.id, t);
            }
        } catch (Exception ignored) {
            // start with an empty queue
        }
    }

    private synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Task t : tasks.values()) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("url", t.url);
                o.put("file", t.file);
                o.put("title", t.title);
                o.put("ua", t.ua);
                o.put("mobile", t.mobile);
                o.put("conns", t.conns);
                o.put("total", t.total);
                o.put("bytes", t.bytes);
                o.put("status", t.status);
                o.put("error", t.error);
                o.put("userPaused", t.userPaused);
                o.put("added", t.added);
                if (t.segPos != null) {
                    JSONArray s = new JSONArray();
                    for (int j = 0; j < t.segPos.length; j++) {
                        s.put(t.segStart[j]);
                        s.put(t.segEnd[j]);
                        s.put(t.segPos[j]);
                    }
                    o.put("seg", s);
                }
                a.put(o);
            }
            prefs().edit().putString("tasks", a.toString()).apply();
        } catch (Exception ignored) {
            // keep going, the queue is still in memory
        }
    }

    /* ---------- API used by the plugin ---------- */

    synchronized String add(String url, String fileName, String title, String ua, boolean mobile, int conns) {
        Task t = new Task();
        t.id = "e" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        t.url = url;
        // never trust the name: no folders, no "..", stays inside the app's video folder
        String safe = fileName == null ? "video" : fileName.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", " ").replace("..", " ").trim();
        if (safe.isEmpty()) {
            safe = "video";
        }
        t.file = new File(dir(), safe).getAbsolutePath();
        t.title = title;
        t.ua = ua;
        t.mobile = mobile;
        t.conns = Math.max(1, Math.min(4, conns));
        t.added = System.currentTimeMillis();
        tasks.put(t.id, t);
        save();
        kick();
        return t.id;
    }

    synchronized void pause(String id) {
        Task t = tasks.get(id);
        if (t == null || "done".equals(t.status)) {
            return;
        }
        t.userPaused = true;
        t.status = "paused";
        if (current == t) {
            stopCurrent = true;
        }
        save();
    }

    synchronized void resume(String id) {
        Task t = tasks.get(id);
        if (t == null || "done".equals(t.status)) {
            return;
        }
        t.userPaused = false;
        t.status = "queued";
        t.attempts = 0;
        t.retryAt = 0;
        t.error = "";
        save();
        kick();
    }

    synchronized void remove(String id) {
        Task t = tasks.remove(id);
        if (t == null) {
            return;
        }
        if (current == t) {
            stopCurrent = true;
        }
        new File(t.file).delete();
        new File(t.file + ".part").delete();
        save();
    }

    void setHold(boolean h) {
        hold = h;
        if (h && current != null) {
            stopCurrent = true;
        }
        if (!h) {
            kick();
        }
    }

    boolean has(String id) {
        return tasks.containsKey(id);
    }

    synchronized JSONObject status(String id) {
        Task t = tasks.get(id);
        if (t == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("id", t.id);
            String st = t.status;
            String wait = "";
            if ("queued".equals(st)) {
                if (hold) {
                    wait = "hold";
                } else if (t.retryAt > System.currentTimeMillis()) {
                    wait = "retry";
                } else if (current != null && current != t) {
                    wait = "queue";
                } else if (!waitReason.isEmpty()) {
                    wait = waitReason;
                }
            }
            o.put("status", st);
            o.put("wait", wait);
            o.put("bytes", t.bytes);
            o.put("total", t.total);
            o.put("speed", current == t ? Math.round(speed) : 0);
            o.put("error", t.error);
            if ("done".equals(st)) {
                o.put("localUri", "file://" + t.file);
            }
        } catch (Exception ignored) {
            // partial status
        }
        return o;
    }

    synchronized boolean busy() {
        for (Task t : tasks.values()) {
            if ("queued".equals(t.status) || "running".equals(t.status)) {
                return true;
            }
        }
        return false;
    }

    /** For the notification: [title, text, percent (-1 = unknown), active count]. */
    synchronized Object[] summary() {
        int waiting = 0;
        for (Task t : tasks.values()) {
            if ("queued".equals(t.status)) {
                waiting++;
            }
        }
        Task c = current;
        if (c != null) {
            int pct = c.total > 0 ? (int) (c.bytes * 100 / c.total) : -1;
            String text = (pct >= 0 ? pct + " %" : mb(c.bytes)) + (speed > 0 ? " · " + mb((long) speed) + "/s" : "");
            if (speed > 0 && c.total > 0) {
                long left = (long) ((c.total - c.bytes) / speed);
                text += " · reste " + (left >= 3600 ? (left / 3600) + " h " + ((left / 60) % 60) + " min" : Math.max(1, left / 60) + " min");
            }
            if (waiting > 0) {
                text += " · " + waiting + " en attente";
            }
            return new Object[] {c.title, text, pct, waiting + 1};
        }
        String why = hold ? "En pause pendant la lecture" : "wifi".equals(waitReason) ? "En attente du Wi-Fi"
                : "network".equals(waitReason) ? "En attente de connexion" : "En attente";
        return new Object[] {"Téléchargements", why + (waiting > 0 ? " · " + waiting + " vidéo" + (waiting > 1 ? "s" : "") : ""), -1, waiting};
    }

    static String mb(long b) {
        if (b >= 1073741824L) {
            return String.format(java.util.Locale.FRANCE, "%.1f Go", b / 1073741824.0);
        }
        return String.format(java.util.Locale.FRANCE, "%.1f Mo", b / 1048576.0);
    }

    /* ---------- worker ---------- */

    synchronized void kick() {
        if (!busy()) {
            return;
        }
        try {
            Intent i = new Intent(ctx, DownloadService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
            // not allowed from the background: the worker still runs while the app is open
        }
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::loop, "tvs-downloads");
            worker.start();
        }
    }

    private synchronized Task next() {
        long now = System.currentTimeMillis();
        for (Task t : tasks.values()) {
            if ("queued".equals(t.status) && !t.userPaused && t.retryAt <= now) {
                return t;
            }
        }
        return null;
    }

    private void loop() {
        while (true) {
            if (!busy()) {
                break;
            }
            Task t = hold ? null : next();
            String net = network();
            if (t != null && !"ok".equals(net) && !("metered".equals(net) && t.mobile)) {
                waitReason = "none".equals(net) ? "network" : "wifi";
                t = null;
            } else {
                waitReason = "";
            }
            if (t == null) {
                sleep(2000);
                continue;
            }
            run(t);
        }
        current = null;
        speed = 0;
    }

    /** ok (unmetered), metered, none */
    private String network() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni == null || !ni.isConnected() ? "none" : cm.isActiveNetworkMetered() ? "metered" : "ok";
            }
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return "none";
            }
            return cm.isActiveNetworkMetered() ? "metered" : "ok";
        } catch (Exception e) {
            return "ok";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // wake up
        }
    }

    private HttpURLConnection open(Task t, long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(t.url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", t.ua == null || t.ua.isEmpty() ? "Mozilla/5.0 (Linux; Android) LecteurIPTV/1.0" : t.ua);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (from > 0 || to >= 0) {
            c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));
        }
        return c;
    }

    private static long contentLength(HttpURLConnection c) {
        try {
            return Long.parseLong(c.getHeaderField("Content-Length"));
        } catch (Exception e) {
            return -1;
        }
    }

    private static long totalFrom(HttpURLConnection c) {
        String cr = c.getHeaderField("Content-Range");
        if (cr != null) {
            Matcher m = Pattern.compile("/(\\d+)").matcher(cr);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        }
        return -1;
    }

    private void run(Task t) {
        synchronized (this) {
            current = t;
            stopCurrent = false;
            t.status = "running";
            t.error = "";
        }
        save();
        File part = new File(t.file + ".part");
        String failure = null;
        try {
            if (t.segPos == null) {
                // first contact: learn the size and whether the server can resume (Range)
                long have = part.exists() ? part.length() : 0;
                HttpURLConnection c = open(t, have, -1);
                int code = c.getResponseCode();
                if (code == 416 && have > 0) {
                    c.disconnect();
                    part.delete();
                    have = 0;
                    c = open(t, 0, -1);
                    code = c.getResponseCode();
                }
                if (code >= 400) {
                    c.disconnect();
                    throw new IOException(code == 401 || code == 403 ? "le fournisseur refuse (" + code + " : limite d'écrans atteinte ?)" : "erreur du serveur (" + code + ")");
                }
                boolean ranges = code == 206;
                long total = ranges ? totalFrom(c) : contentLength(c);
                if (!ranges) {
                    have = 0;
                }
                t.total = total > 0 ? total : 0;
                if (ranges && t.conns > 1 && t.total > 64L * 1024 * 1024) {
                    // several connections: keep this one for the first part, open the others below
                    c.disconnect();
                    int n = t.conns;
                    t.segStart = new long[n];
                    t.segEnd = new long[n];
                    t.segPos = new long[n];
                    for (int i = 0; i < n; i++) {
                        t.segStart[i] = t.total * i / n;
                        t.segEnd[i] = t.total * (i + 1) / n - 1;
                        t.segPos[i] = t.segStart[i];
                    }
                    if (have > 0) {
                        // a single-connection download had already started: keep those bytes
                        t.segPos[0] = Math.min(have, t.segEnd[0] + 1);
                    }
                    save();
                } else {
                    single(t, c, part, have);
                }
            }
            if (t.segPos != null && !stopCurrent) {
                segmented(t, part);
            }
            boolean complete = t.segPos != null ? segmentsDone(t) : (t.total <= 0 || part.length() >= t.total);
            if (!stopCurrent && complete) {
                File fin = new File(t.file);
                fin.delete();
                if (!part.renameTo(fin)) {
                    throw new IOException("impossible d'enregistrer le fichier");
                }
                synchronized (this) {
                    t.status = "done";
                    t.bytes = fin.length();
                    t.total = fin.length();
                    t.segPos = null;
                }
            } else if (!stopCurrent) {
                throw new IOException("connexion interrompue");
            }
        } catch (IOException e) {
            failure = e.getMessage();
        } catch (Exception e) {
            failure = e.toString();
        }
        synchronized (this) {
            speed = 0;
            if (stopCurrent) {
                if (!t.userPaused && tasks.containsKey(t.id) && !"done".equals(t.status)) {
                    t.status = "queued";
                }
            } else if (failure != null) {
                t.attempts++;
                t.error = failure;
                if (t.attempts <= 8) {
                    t.status = "queued";
                    t.retryAt = System.currentTimeMillis() + Math.min(60000, 5000L * t.attempts);
                } else {
                    t.status = "failed";
                }
            }
            current = null;
            stopCurrent = false;
        }
        save();
    }

    private void single(Task t, HttpURLConnection c, File part, long have) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(part, "rw"); InputStream in = c.getInputStream()) {
            out.setLength(have);
            out.seek(have);
            byte[] buf = new byte[128 * 1024];
            long pos = have;
            long mark = System.currentTimeMillis();
            long markBytes = pos;
            long saved = mark;
            int n;
            while (!stopCurrent && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                pos += n;
                t.bytes = pos;
                long now = System.currentTimeMillis();
                if (now - mark >= 1000) {
                    speed = (pos - markBytes) * 1000.0 / (now - mark);
                    mark = now;
                    markBytes = pos;
                }
                if (now - saved > 5000) {
                    saved = now;
                    t.attempts = 0;
                    save();
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private boolean segmentsDone(Task t) {
        for (int i = 0; i < t.segPos.length; i++) {
            if (t.segPos[i] <= t.segEnd[i]) {
                return false;
            }
        }
        return true;
    }

    private void segmented(Task t, File part) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(part, "rw")) {
            if (f.length() < t.total) {
                f.setLength(t.total);
            }
        }
        final IOException[] err = new IOException[1];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < t.segPos.length; i++) {
            final int k = i;
            if (t.segPos[k] > t.segEnd[k]) {
                continue;
            }
            Thread th = new Thread(() -> {
                HttpURLConnection c = null;
                try (RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                    c = open(t, t.segPos[k], t.segEnd[k]);
                    int code = c.getResponseCode();
                    if (code != 206) {
                        throw new IOException(code == 401 || code == 403 ? "le fournisseur refuse plusieurs connexions (" + code + ")" : "erreur du serveur (" + code + ")");
                    }
                    InputStream in = c.getInputStream();
                    out.seek(t.segPos[k]);
                    byte[] buf = new byte[128 * 1024];
                    int n;
                    while (!stopCurrent && t.segPos[k] <= t.segEnd[k] && (n = in.read(buf)) > 0) {
                        int w = (int) Math.min(n, t.segEnd[k] - t.segPos[k] + 1);
                        out.write(buf, 0, w);
                        t.segPos[k] += w;
                    }
                    in.close();
                } catch (IOException e) {
                    synchronized (err) {
                        if (err[0] == null) {
                            err[0] = e;
                        }
                    }
                } finally {
                    if (c != null) {
                        c.disconnect();
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        long mark = System.currentTimeMillis();
        long markBytes = doneBytes(t);
        long saved = mark;
        while (true) {
            boolean alive = false;
            for (Thread th : threads) {
                if (th.isAlive()) {
                    alive = true;
                }
            }
            long b = doneBytes(t);
            t.bytes = b;
            long now = System.currentTimeMillis();
            if (now - mark >= 1000) {
                speed = (b - markBytes) * 1000.0 / (now - mark);
                mark = now;
                markBytes = b;
            }
            if (now - saved > 5000) {
                saved = now;
                t.attempts = 0;
                save();
            }
            if (!alive) {
                break;
            }
            sleep(300);
        }
        t.bytes = doneBytes(t);
        if (err[0] != null && !segmentsDone(t)) {
            if (err[0].getMessage() != null && err[0].getMessage().contains("plusieurs connexions")) {
                // the provider does not like parallel connections: carry on with one
                t.conns = 1;
                mergeToSingle(t);
            }
            throw err[0];
        }
    }

    private long doneBytes(Task t) {
        long b = 0;
        for (int i = 0; i < t.segPos.length; i++) {
            b += t.segPos[i] - t.segStart[i];
        }
        return b;
    }

    /** Fall back to one connection, keeping the contiguous start of the file. */
    private void mergeToSingle(Task t) {
        long contiguous = t.segPos[0];
        for (int i = 1; i < t.segPos.length; i++) {
            if (t.segPos[i - 1] > t.segEnd[i - 1]) {
                contiguous = t.segPos[i];
            } else {
                break;
            }
        }
        File part = new File(t.file + ".part");
        try (RandomAccessFile f = new RandomAccessFile(part, "rw")) {
            f.setLength(contiguous);
        } catch (IOException ignored) {
            // will restart from zero if needed
        }
        t.segStart = null;
        t.segEnd = null;
        t.segPos = null;
        t.bytes = contiguous;
        save();
    }
}
