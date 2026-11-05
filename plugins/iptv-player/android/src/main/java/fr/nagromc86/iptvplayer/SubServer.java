package fr.nagromc86.iptvplayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny web server on the phone, only for subtitle files: the TV or the Chromecast
 * downloads them from here while it plays the video from the provider.
 */
class SubServer {

    private static ServerSocket server;
    private static final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /* a few requests at a time are plenty for a TV; a flood from the network cannot create endless threads */
    private static final java.util.concurrent.ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(1, 8, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(16), new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    /* ---------- relay of the video for DLNA TVs ----------
       Many TVs (Samsung in particular) cannot open the provider's address themselves: redirections, https, a refused
       player name, no "DLNA" headers. The phone fetches the stream and hands it to the TV on the Wi-Fi. */
    private static final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile String relayId, relayUrl, relayMime, relayUa;
    private static volatile boolean relayLive;
    private static final java.util.Set<java.net.HttpURLConnection> upstreams = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static android.os.PowerManager.WakeLock wake;
    private static android.net.wifi.WifiManager.WifiLock wifi;

    static synchronized String relay(android.content.Context ctx, String url, String mime, boolean live, String ua) {
        String ip = lanIp();
        if (ip == null) {
            return null;
        }
        try {
            start();
        } catch (IOException e) {
            return null;
        }
        stopRelay();
        relayId = "v" + System.currentTimeMillis();
        relayUrl = url;
        relayMime = mime;
        relayLive = live;
        relayUa = ua;
        try {
            // the phone keeps the stream going with its screen off
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getApplicationContext().getSystemService(android.content.Context.POWER_SERVICE);
            wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "tvstreamy:relay");
            wake.acquire(6 * 3600 * 1000L);
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvstreamy:relay");
            wifi.acquire();
        } catch (Exception ignored) {
            // locks are a comfort, not a requirement
        }
        String ext = mime.contains("mp2t") || mime.contains("mpeg-tts") || mime.equals("video/mpeg") ? ".ts" : mime.contains("matroska") || mime.contains("mkv") ? ".mkv" : mime.contains("mpegurl") ? ".m3u8" : ".mp4";
        return "http://" + ip + ":" + server.getLocalPort() + "/" + relayId + ext;
    }

    static synchronized void stopRelay() {
        relayId = null;
        for (java.net.HttpURLConnection c : upstreams) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // already closed
            }
        }
        upstreams.clear();
        try {
            if (wake != null && wake.isHeld()) {
                wake.release();
            }
            if (wifi != null && wifi.isHeld()) {
                wifi.release();
            }
        } catch (Exception ignored) {
            // already released
        }
        wake = null;
        wifi = null;
    }

    static String dlnaFeatures(boolean live) {
        return (live ? "DLNA.ORG_OP=00" : "DLNA.ORG_OP=01") + ";DLNA.ORG_CI=0;DLNA.ORG_FLAGS=" + (live ? "8D700000000000000000000000000000" : "01700000000000000000000000000000");
    }

    private static void relayTo(Socket s, String method, String range, OutputStream o) throws IOException {
        String target = relayUrl;
        java.net.HttpURLConnection c = null;
        // providers often allow one connection per account: the TV's previous request (probe, earlier position) is
        // closed before asking for the new one
        for (java.net.HttpURLConnection old : upstreams) {
            try {
                old.disconnect();
            } catch (Exception ignored) {
                // already closed
            }
        }
        upstreams.clear();
        try {
            int hops = 0;
            while (true) {
                c = (java.net.HttpURLConnection) new java.net.URL(target).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", relayUa == null || relayUa.isEmpty() ? "Mozilla/5.0 (Linux; Android) LecteurIPTV/1.0" : relayUa);
                if (range != null && !relayLive) {
                    c.setRequestProperty("Range", range);
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null && hops++ < 6) {
                    target = new java.net.URL(new java.net.URL(target), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                break;
            }
            upstreams.add(c);
            int code = c.getResponseCode();
            if (code >= 400) {
                o.write(("HTTP/1.1 " + code + " Upstream\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                return;
            }
            StringBuilder h = new StringBuilder(code == 206 ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(relayMime).append("\r\n");
            long len = c.getContentLengthLong();
            if (len >= 0 && !relayLive) {
                h.append("Content-Length: ").append(len).append("\r\n");
            }
            String cr = c.getHeaderField("Content-Range");
            if (cr != null && code == 206) {
                h.append("Content-Range: ").append(cr).append("\r\n");
            }
            boolean ranges = code == 206 || "bytes".equalsIgnoreCase(String.valueOf(c.getHeaderField("Accept-Ranges")));
            if (!relayLive && ranges) {
                h.append("Accept-Ranges: bytes\r\n");
            }
            h.append("transferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: ").append(dlnaFeatures(relayLive)).append("\r\nConnection: close\r\n\r\n");
            o.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            o.flush();
            if ("HEAD".equals(method)) {
                return;
            }
            s.setSoTimeout(0);
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                }
            }
        } finally {
            if (c != null) {
                upstreams.remove(c);
                c.disconnect();
            }
        }
    }

    /** Returns [srtUrl, vttUrl], or null when the phone has no local network address. */
    /** Shifts every "00:01:02,345 --> 00:01:04,000" line by offsetMs (positive = later). */
    static String shift(String srt, long offsetMs) {
        if (offsetMs == 0) {
            return srt;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+):(\\d{1,2}):(\\d{1,2})([,.])(\\d{1,3})").matcher(srt);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            StringBuilder f = new StringBuilder(m.group(5));
            while (f.length() < 3) {
                f.append('0');
            }
            long t = Long.parseLong(m.group(1)) * 3600000L + Long.parseLong(m.group(2)) * 60000L + Long.parseLong(m.group(3)) * 1000L + Long.parseLong(f.substring(0, 3));
            t = Math.max(0, t + offsetMs);
            m.appendReplacement(out, String.format(java.util.Locale.ROOT, "%02d:%02d:%02d%s%03d", t / 3600000, (t / 60000) % 60, (t / 1000) % 60, m.group(4), t % 1000));
        }
        m.appendTail(out);
        return out.toString();
    }

    static synchronized String[] publish(String srt, long offsetMs) {
        srt = shift(srt == null ? "" : srt, offsetMs);
        String ip = lanIp();
        if (ip == null || srt == null || srt.isEmpty()) {
            return null;
        }
        try {
            start();
        } catch (IOException e) {
            return null;
        }
        files.clear();
        String clean = srt.replace("﻿", "").replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
        for (String line : clean.split("\n", -1)) {
            vtt.append(line.contains("-->") ? line.replace(',', '.') : line).append('\n');
        }
        String id = "st" + System.currentTimeMillis();
        files.put(id + ".srt", clean.getBytes(StandardCharsets.UTF_8));
        files.put(id + ".vtt", vtt.toString().getBytes(StandardCharsets.UTF_8));
        String base = "http://" + ip + ":" + server.getLocalPort() + "/";
        return new String[] {base + id + ".srt", base + id + ".vtt"};
    }

    private static void start() throws IOException {
        if (server != null && !server.isClosed()) {
            return;
        }
        server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    Socket s = server.accept();
                    if (active.get() > 24) {
                        s.close();
                        continue;
                    }
                    Thread h = new Thread(() -> {
                        active.incrementAndGet();
                        try {
                            handle(s);
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    break;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String first = r.readLine();
            String line, range = null;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Range")) {
                    range = line.substring(c + 1).trim();
                }
            }
            OutputStream o = s.getOutputStream();
            String[] parts = first == null ? new String[0] : first.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            String name = path.startsWith("/") ? path.substring(1) : path;
            String cors = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n";
            String rid = relayId;
            if (rid != null && name.startsWith(rid + ".")) {
                relayTo(s, method, range, o);
            } else if ("OPTIONS".equals(method)) {
                o.write(("HTTP/1.1 204 No Content\r\n" + cors + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            } else {
                byte[] d = files.get(name);
                if (d == null) {
                    o.write(("HTTP/1.1 404 Not Found\r\n" + cors + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                } else {
                    String type = name.endsWith(".vtt") ? "text/vtt; charset=utf-8" : "text/srt; charset=utf-8";
                    o.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + d.length + "\r\n" + cors
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (!"HEAD".equals(method)) {
                        o.write(d);
                    }
                }
            }
            o.flush();
        } catch (IOException ignored) {
            // the TV closed the connection
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    /** The phone's address on the Wi-Fi (192.168.x.x…). */
    static String lanIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")) {
                            return a.getHostAddress();
                        }
                        if (fallback == null) {
                            fallback = a.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        return fallback;
    }
}
