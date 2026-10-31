package fr.nagromc86.iptvplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;

import java.util.ArrayList;
import java.util.List;

/** Chromecast / Google TV through the Google Cast framework. Everything here runs on the main thread. */
class CastHelper {

    interface Done {
        void ok();

        void fail(String why);
    }

    static final Handler main = new Handler(Looper.getMainLooper());
    private static CastContext cast;
    private static MediaRouter router;
    private static MediaRouteSelector selector;
    private static final MediaRouter.Callback routerCallback = new MediaRouter.Callback() { };
    private static Runnable pendingLoad;
    private static Done pendingDone;
    private static String pendingDeviceId;

    @SuppressWarnings("deprecation")
    static boolean init(Context ctx) {
        if (cast != null) {
            return true;
        }
        try {
            cast = CastContext.getSharedInstance(ctx.getApplicationContext());
            router = MediaRouter.getInstance(ctx.getApplicationContext());
            selector = new MediaRouteSelector.Builder()
                    .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                    .build();
            cast.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
            return true;
        } catch (Exception e) {
            // no Google Play services on this phone: Cast unavailable, DLNA still works
            cast = null;
            return false;
        }
    }

    static void startScan(Context ctx) {
        if (init(ctx)) {
            router.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
        }
    }

    static void stopScan() {
        if (router != null) {
            router.removeCallback(routerCallback);
        }
    }

    static List<String[]> devices() {
        List<String[]> out = new ArrayList<>();
        if (router == null) {
            return out;
        }
        for (MediaRouter.RouteInfo r : router.getRoutes()) {
            if (r.isDefault() || !r.isEnabled() || !r.matchesSelector(selector)) {
                continue;
            }
            String desc = r.getDescription() == null ? "" : r.getDescription();
            out.add(new String[] {"cast:" + r.getId(), r.getName(), desc});
        }
        return out;
    }

    private static MediaRouter.RouteInfo route(String id) {
        if (router == null) {
            return null;
        }
        for (MediaRouter.RouteInfo r : router.getRoutes()) {
            if (("cast:" + r.getId()).equals(id)) {
                return r;
            }
        }
        return null;
    }

    private static CastSession session() {
        if (cast == null) {
            return null;
        }
        CastSession s = cast.getSessionManager().getCurrentCastSession();
        return s != null && s.isConnected() ? s : null;
    }

    private static final SessionManagerListener<CastSession> sessionListener = new SessionManagerListener<CastSession>() {
        @Override
        public void onSessionStarted(CastSession s, String id) {
            runPending();
        }

        @Override
        public void onSessionResumed(CastSession s, boolean wasSuspended) {
            runPending();
        }

        @Override
        public void onSessionStartFailed(CastSession s, int error) {
            failPending("connexion au Chromecast impossible (" + error + ")");
        }

        @Override
        public void onSessionStarting(CastSession s) { }

        @Override
        public void onSessionEnding(CastSession s) { }

        @Override
        public void onSessionEnded(CastSession s, int error) { }

        @Override
        public void onSessionResuming(CastSession s, String id) { }

        @Override
        public void onSessionResumeFailed(CastSession s, int error) {
            failPending("connexion au Chromecast impossible (" + error + ")");
        }

        @Override
        public void onSessionSuspended(CastSession s, int reason) { }
    };

    private static void runPending() {
        Runnable r = pendingLoad;
        pendingLoad = null;
        if (r != null) {
            r.run();
        }
    }

    private static void failPending(String why) {
        Done d = pendingDone;
        pendingLoad = null;
        pendingDone = null;
        if (d != null) {
            d.fail(why);
        }
    }

    static void play(Context ctx, String deviceId, String url, String title, String subtitle, String image, String mime,
                     long startMs, boolean live, String vttUrl, boolean subsOn, Done done) {
        if (!init(ctx)) {
            done.fail("Google Cast indisponible sur ce téléphone");
            return;
        }
        MediaRouter.RouteInfo r = route(deviceId);
        Runnable load = () -> {
            CastSession s = session();
            RemoteMediaClient client = s == null ? null : s.getRemoteMediaClient();
            if (client == null) {
                done.fail("Chromecast non connecté");
                return;
            }
            MediaMetadata md = new MediaMetadata(live ? MediaMetadata.MEDIA_TYPE_GENERIC : MediaMetadata.MEDIA_TYPE_MOVIE);
            md.putString(MediaMetadata.KEY_TITLE, title == null ? "" : title);
            if (subtitle != null && !subtitle.isEmpty()) {
                md.putString(MediaMetadata.KEY_SUBTITLE, subtitle);
            }
            if (image != null && image.startsWith("http")) {
                md.addImage(new WebImage(Uri.parse(image)));
            }
            MediaInfo.Builder ib = new MediaInfo.Builder(url)
                    .setStreamType(live ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(mime)
                    .setMetadata(md);
            boolean subs = vttUrl != null && !vttUrl.isEmpty();
            if (subs) {
                List<MediaTrack> tracks = new ArrayList<>();
                tracks.add(new MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                        .setName("Français")
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setContentId(vttUrl)
                        .setContentType("text/vtt")
                        .setLanguage("fr")
                        .build());
                ib.setMediaTracks(tracks);
            }
            MediaLoadRequestData.Builder rb = new MediaLoadRequestData.Builder()
                    .setMediaInfo(ib.build())
                    .setAutoplay(true)
                    .setCurrentTime(live ? 0 : startMs);
            if (subs && subsOn) {
                rb.setActiveTrackIds(new long[] {1});
            }
            MediaLoadRequestData req = rb.build();
            client.load(req).setResultCallback(res -> {
                if (res.getStatus().isSuccess()) {
                    done.ok();
                } else {
                    String m = res.getStatus().getStatusMessage();
                    done.fail("le Chromecast n'a pas pu lire cette vidéo" + (m == null ? "" : " (" + m + ")"));
                }
            });
        };
        CastSession cur = session();
        boolean same = false;
        if (cur != null && r != null) {
            CastDevice want = CastDevice.getFromBundle(r.getExtras());
            same = want != null && cur.getCastDevice() != null && want.getDeviceId().equals(cur.getCastDevice().getDeviceId());
        }
        if (cur != null && (same || r == null)) {
            load.run();
            return;
        }
        if (r == null) {
            done.fail("Chromecast introuvable, relance la recherche");
            return;
        }
        pendingLoad = load;
        pendingDone = done;
        pendingDeviceId = deviceId;
        router.selectRoute(r);
        main.postDelayed(() -> {
            if (pendingLoad != null && deviceId.equals(pendingDeviceId)) {
                failPending("le Chromecast ne répond pas");
            }
        }, 20000);
    }

    /** [state, positionMs, durationMs]; state: playing, paused, buffering, idle, finished, error, none */
    static Object[] status() {
        CastSession s = session();
        RemoteMediaClient c = s == null ? null : s.getRemoteMediaClient();
        if (c == null) {
            return new Object[] {"none", 0L, 0L};
        }
        int st = c.getPlayerState();
        String state;
        switch (st) {
            case MediaStatus.PLAYER_STATE_PLAYING:
                state = "playing";
                break;
            case MediaStatus.PLAYER_STATE_PAUSED:
                state = "paused";
                break;
            case MediaStatus.PLAYER_STATE_BUFFERING:
            case MediaStatus.PLAYER_STATE_LOADING:
                state = "buffering";
                break;
            case MediaStatus.PLAYER_STATE_IDLE:
                int why = c.getIdleReason();
                state = why == MediaStatus.IDLE_REASON_FINISHED ? "finished" : why == MediaStatus.IDLE_REASON_ERROR ? "error" : "idle";
                break;
            default:
                state = "idle";
        }
        return new Object[] {state, c.getApproximateStreamPosition(), c.getStreamDuration()};
    }

    static void control(String action, long ms) {
        CastSession s = session();
        RemoteMediaClient c = s == null ? null : s.getRemoteMediaClient();
        if ("disconnect".equals(action)) {
            if (cast != null) {
                cast.getSessionManager().endCurrentSession(true);
            }
            return;
        }
        if (c == null) {
            return;
        }
        switch (action) {
            case "pause":
                c.pause();
                break;
            case "play":
                c.play();
                break;
            case "seek":
                c.seek(new MediaSeekOptions.Builder().setPosition(ms).build());
                break;
            case "stop":
                c.stop();
                break;
            case "subsOn":
                c.setActiveMediaTracks(new long[] {1});
                break;
            case "subsOff":
                c.setActiveMediaTracks(new long[] {});
                break;
            default:
                break;
        }
    }
}
