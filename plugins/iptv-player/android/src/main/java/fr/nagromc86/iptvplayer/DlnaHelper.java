package fr.nagromc86.iptvplayer;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * DLNA / UPnP renderers on the local network (Samsung, LG, Sony, Philips TVs, many boxes):
 * found with an SSDP search, driven with the AVTransport service (SOAP over HTTP).
 */
class DlnaHelper {

    static class Device {
        String id;
        String name;
        String model;
        String control;
        String serviceType;
        String cmControl;
        String cmType;
        String sink;
    }

    static final Map<String, Device> devices = new ConcurrentHashMap<>();

    static List<Device> discover(Context ctx, int timeoutMs) {
        Set<String> locations = new LinkedHashSet<>();
        WifiManager.MulticastLock lock = null;
        DatagramSocket sock = null;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("tvstreamy-dlna");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            // send from the Wi-Fi address: with mobile data on, the search could leave by the wrong network
            String ip = SubServer.lanIp();
            if (ip != null) {
                sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.bind(new java.net.InetSocketAddress(InetAddress.getByName(ip), 0));
            } else {
                sock = new DatagramSocket();
            }
            sock.setSoTimeout(300);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            String[] targets = {"urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1"};
            for (int round = 0; round < 3; round++) {
                for (String st : targets) {
                    String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st
                            + "\r\nUSER-AGENT: Android UPnP/1.1 TVStreamy/1.0\r\n\r\n";
                    byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
                    sock.send(new DatagramPacket(b, b.length, group, 1900));
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                for (String line : resp.split("\r\n")) {
                    int c = line.indexOf(':');
                    if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("LOCATION")) {
                        locations.add(line.substring(c + 1).trim());
                    }
                }
            }
        } catch (Exception ignored) {
            // no Wi-Fi, multicast refused...: return what was found
        } finally {
            if (sock != null) {
                sock.close();
            }
            if (lock != null && lock.isHeld()) {
                lock.release();
            }
        }
        // ask every answering device for its description at the same time, and don't wait for slow ones
        Map<String, Device> found = new ConcurrentHashMap<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(8, locations.size())));
        for (String loc : locations) {
            pool.submit(() -> {
                Device d = describe(loc);
                if (d != null) {
                    found.putIfAbsent(d.id, d);
                    devices.put(d.id, d);
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            // return what we have
        }
        pool.shutdownNow();
        return new ArrayList<>(found.values());
    }

    private static Device describe(String location) {
        try {
            String xml = http("GET", location, null, null, 2500);
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            // a device on the Wi-Fi sends this XML: refuse DOCTYPE / external entities (XXE)
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // feature unknown to this parser
            }
            try {
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {
                // feature unknown to this parser
            }
            f.setExpandEntityReferences(false);
            if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) {
                return null;
            }
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            String base = text(root, "URLBase");
            NodeList services = root.getElementsByTagName("service");
            String cmControl = null, cmType = null;
            for (int i = 0; i < services.getLength(); i++) {
                Element s = (Element) services.item(i);
                if (text(s, "serviceType").contains(":ConnectionManager:")) {
                    cmType = text(s, "serviceType");
                    cmControl = new URL(new URL(base.isEmpty() ? location : base), text(s, "controlURL")).toString();
                }
            }
            for (int i = 0; i < services.getLength(); i++) {
                Element s = (Element) services.item(i);
                String type = text(s, "serviceType");
                if (!type.contains(":AVTransport:")) {
                    continue;
                }
                Device d = new Device();
                String udn = text(root, "UDN");
                d.id = "dlna:" + (udn.isEmpty() ? location : udn);
                d.name = text(root, "friendlyName");
                if (d.name.isEmpty()) {
                    d.name = new URL(location).getHost();
                }
                d.model = text(root, "modelName");
                d.serviceType = type;
                d.control = new URL(new URL(base.isEmpty() ? location : base), text(s, "controlURL")).toString();
                d.cmControl = cmControl;
                d.cmType = cmType;
                return d;
            }
        } catch (Exception ignored) {
            // not a renderer we can drive
        }
        return null;
    }

    private static String text(Element el, String tag) {
        NodeList n = el.getElementsByTagName(tag);
        return n.getLength() > 0 && n.item(0).getTextContent() != null ? n.item(0).getTextContent().trim() : "";
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String soap(Device d, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + d.serviceType + "\"><InstanceID>0</InstanceID>" + args
                + "</u:" + action + "></s:Body></s:Envelope>";
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "text/xml; charset=\"utf-8\"");
        h.put("SOAPACTION", "\"" + d.serviceType + "#" + action + "\"");
        return http("POST", d.control, body, h, 8000);
    }

    /** The formats the TV says it can play (ConnectionManager / GetProtocolInfo, "Sink"). */
    static String sink(Device d) {
        if (d.sink != null || d.cmControl == null) {
            return d.sink == null ? "" : d.sink;
        }
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:GetProtocolInfo xmlns:u=\"" + d.cmType + "\"></u:GetProtocolInfo></s:Body></s:Envelope>";
            Map<String, String> h = new HashMap<>();
            h.put("Content-Type", "text/xml; charset=\"utf-8\"");
            h.put("SOAPACTION", "\"" + d.cmType + "#GetProtocolInfo\"");
            d.sink = tag(http("POST", d.cmControl, body, h, 4000), "Sink").replace("&amp;", "&");
        } catch (Exception e) {
            d.sink = "";
        }
        return d.sink;
    }

    /** The name the TV itself uses for this kind of file (Samsung: video/mp2t, video/vnd.dlna.mpeg-tts, video/mpeg...). */
    static String mimeFor(Device d, String mime) {
        String sk = sink(d).toLowerCase(java.util.Locale.ROOT);
        String[] cand;
        if (mime.contains("mp2t") || mime.contains("mpeg-tts")) {
            cand = new String[] {"video/mp2t", "video/vnd.dlna.mpeg-tts", "video/mpeg", "video/mpeg2"};
        } else if (mime.contains("matroska") || mime.contains("mkv")) {
            cand = new String[] {"video/x-matroska", "video/x-mkv", "video/mkv"};
        } else if (mime.contains("mpegurl")) {
            cand = new String[] {"application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl"};
        } else if (mime.contains("avi")) {
            cand = new String[] {"video/x-msvideo", "video/avi"};
        } else {
            cand = new String[] {mime};
        }
        for (String c : cand) {
            if (sk.contains(":" + c + ":")) {
                return c;
            }
        }
        return cand[0];
    }

    static void play(Device d, String url, String title, String mime, long startMs, String srtUrl) throws IOException {
        play(d, url, title, mime, startMs, srtUrl, false);
    }

    static void play(Device d, String url, String title, String mime, long startMs, String srtUrl, boolean live) throws IOException {
        try {
            soap(d, "Stop", "");
        } catch (IOException ignored) {
            // nothing was playing
        }
        boolean subs = srtUrl != null && !srtUrl.isEmpty();
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:sec=\"http://www.sec.co.kr/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + esc(title) + "</dc:title><upnp:class>object.item.videoItem</upnp:class>"
                + (subs ? "<sec:CaptionInfoEx sec:type=\"srt\">" + esc(srtUrl) + "</sec:CaptionInfoEx><sec:CaptionInfo sec:type=\"srt\">" + esc(srtUrl) + "</sec:CaptionInfo>" : "")
                + "<res protocolInfo=\"http-get:*:" + mime + ":" + SubServer.dlnaFeatures(live) + "\">"
                + esc(url) + "</res>"
                + "</item></DIDL-Lite>";
        soap(d, "SetAVTransportURI", "<CurrentURI>" + esc(url) + "</CurrentURI><CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
        // Samsung and others refuse "Play" while they are still loading the address (error 701): try again a few times
        IOException last = null;
        for (int i = 0; i < 8; i++) {
            try {
                soap(d, "Play", "<Speed>1</Speed>");
                last = null;
                break;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(700);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        if (startMs > 0) {
            new Thread(() -> {
                // most TVs only accept a seek once playback has really started
                for (int i = 0; i < 30; i++) {
                    try {
                        Thread.sleep(500);
                        if ("PLAYING".equals(state(d))) {
                            seek(d, startMs);
                            return;
                        }
                    } catch (Exception ignored) {
                        // keep waiting
                    }
                }
            }).start();
        }
    }

    static String state(Device d) throws IOException {
        return tag(soap(d, "GetTransportInfo", ""), "CurrentTransportState");
    }

    static long[] position(Device d) throws IOException {
        String r = soap(d, "GetPositionInfo", "");
        return new long[] {ms(tag(r, "RelTime")), ms(tag(r, "TrackDuration"))};
    }

    static void seek(Device d, long posMs) throws IOException {
        long s = posMs / 1000;
        String t = String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        soap(d, "Seek", "<Unit>REL_TIME</Unit><Target>" + t + "</Target>");
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + ">([^<]*)<").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static long ms(String t) {
        try {
            String[] p = t.split(":");
            if (p.length != 3) {
                return 0;
            }
            return Math.round((Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Double.parseDouble(p[2])) * 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    static String http(String method, String url, String body, Map<String, String> headers, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (body != null) {
            c.setDoOutput(true);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String out = "";
        if (in != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            in.close();
            out = buf.toString("UTF-8");
        }
        c.disconnect();
        if (code >= 400) {
            String why = tag(out, "errorDescription");
            throw new IOException("la TV a refusé (" + code + (why.isEmpty() ? "" : " : " + why) + ")");
        }
        return out;
    }
}
