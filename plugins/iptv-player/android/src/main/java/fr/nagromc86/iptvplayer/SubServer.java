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

    /** Returns [srtUrl, vttUrl], or null when the phone has no local network address. */
    static synchronized String[] publish(String srt) {
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
                    new Thread(() -> handle(s)).start();
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
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                // skip request headers
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
            if ("OPTIONS".equals(method)) {
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
