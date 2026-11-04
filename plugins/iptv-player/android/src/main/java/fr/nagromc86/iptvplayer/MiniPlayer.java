package fr.nagromc86.iptvplayer;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/**
 * Small live video shown inside the "Direct" page (over the web page, at the place the page reserves for it),
 * so the user can scroll the guide and zap without leaving the page.
 */
class MiniPlayer {

    interface Listener {
        void onState(String state, String detail);
    }

    private static ExoPlayer player;
    private static PlayerView view;
    private static FrameLayout box;
    private static String url, fallback;
    private static boolean triedFallback;
    private static int retries;
    private static Listener listener;
    /* picture in picture: when the user leaves the app (home / app switch), the small video goes on in a floating window */
    static boolean inPip;
    private static Activity act;
    private static View web;
    private static float[] lastRect;
    private static final android.os.Handler pipWatch = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable pipTick = new Runnable() {
        @Override
        public void run() {
            if (player == null || act == null) {
                return;
            }
            boolean now = android.os.Build.VERSION.SDK_INT >= 26 && act.isInPictureInPictureMode();
            if (now != inPip) {
                setPip(now);
            }
            pipWatch.postDelayed(this, 300);
        }
    };

    static boolean isTv(Activity a) {
        android.app.UiModeManager um = (android.app.UiModeManager) a.getSystemService(android.content.Context.UI_MODE_SERVICE);
        return um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    static boolean pipAllowed(Activity a) {
        return android.os.Build.VERSION.SDK_INT >= 26 && !isTv(a)
                && a.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    static android.app.PictureInPictureParams pipParams(boolean auto) {
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder().setAspectRatio(new android.util.Rational(16, 9));
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(auto);
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    /** Android 12+: the system opens the floating window by itself when the user leaves while the video plays. */
    private static void autoPip(boolean on) {
        if (act == null || android.os.Build.VERSION.SDK_INT < 31 || !pipAllowed(act)) {
            return;
        }
        try {
            act.setPictureInPictureParams(pipParams(on));
        } catch (Exception ignored) {
            // activity not declared for picture in picture
        }
    }

    /** Android 8 to 11: called when the app is being left. True if the floating window opened. */
    static boolean enterPipNow() {
        if (player == null || act == null || !pipAllowed(act) || !player.getPlayWhenReady()) {
            return false;
        }
        try {
            return act.enterPictureInPictureMode(pipParams(true));
        } catch (Exception e) {
            return false;
        }
    }

    private static void setPip(boolean on) {
        inPip = on;
        if (box == null || web == null) {
            return;
        }
        if (on) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            box.setLayoutParams(lp);
            box.setTranslationX(0);
            box.setTranslationY(0);
            box.setClipToOutline(false);
            box.setVisibility(View.VISIBLE);
            box.bringToFront();
            if (player != null) {
                player.setVolume(1f);
            }
        } else {
            box.setClipToOutline(true);
            place(act, web, lastRect);
        }
    }

    static boolean active() {
        return player != null;
    }

    static void play(Activity a, View webView, String u, String fb, String ua, float[] rect, boolean muted, Listener l) {
        listener = l;
        act = a;
        web = webView;
        final Activity act = a;
        if (box == null) {
            box = new FrameLayout(act);
            box.setBackgroundColor(Color.BLACK);
            final float r = act.getResources().getDisplayMetrics().density * 12;
            box.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            box.setClipToOutline(true);
            view = new PlayerView(act);
            view.setUseController(false);
            view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            view.setShutterBackgroundColor(Color.BLACK);
            // a video is playing on the page: the screen does not go to sleep
            view.setKeepScreenOn(true);
            box.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ViewGroup root = act.findViewById(android.R.id.content);
            root.addView(box, new FrameLayout.LayoutParams(10, 10));
        }
        if (player == null) {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(TextUtils.isEmpty(ua) ? "Mozilla/5.0 (Linux; Android) LecteurIPTV/1.0" : ua)
                    .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(30000);
            player = new ExoPlayer.Builder(act).setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(act, http))).build();
            player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int s) {
                    if (s == Player.STATE_READY) {
                        retries = 0;
                        autoPip(true);
                        emit("playing", triedFallback ? "fallback" : "");
                    } else if (s == Player.STATE_BUFFERING) {
                        emit("buffering", "");
                    }
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    if (player == null) {
                        return;
                    }
                    if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && retries < 2) {
                        retries++;
                        PlayerActivity.signInAgain(url);
                        view.postDelayed(() -> start(url), 2000L * retries);
                        return;
                    }
                    if (!triedFallback && !TextUtils.isEmpty(fallback)) {
                        triedFallback = true;
                        start(fallback);
                        return;
                    }
                    emit("error", e.getErrorCodeName());
                }
            });
            view.setPlayer(player);
        }
        if (u.equals(url) && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_IDLE) {
            // same channel still playing (back from the floating window): no restart
            player.setVolume(muted ? 0f : 1f);
            place(act, webView, rect);
            emit("playing", "");
            return;
        }
        url = u;
        fallback = fb;
        triedFallback = false;
        retries = 0;
        player.setVolume(muted ? 0f : 1f);
        place(act, webView, rect);
        start(u);
        pipWatch.removeCallbacks(pipTick);
        pipWatch.postDelayed(pipTick, 300);
    }

    private static void start(String u) {
        if (player == null) {
            return;
        }
        player.setMediaItem(MediaItem.fromUri(Uri.parse(u)));
        player.prepare();
        player.play();
        emit("buffering", "");
    }

    /** rect = x, y, width, height in page pixels, visible (1/0). */
    static void place(Activity act, View webView, float[] rect) {
        if (rect != null) {
            lastRect = rect;
        }
        if (box == null || rect == null || inPip) {
            return;
        }
        float d = act.getResources().getDisplayMetrics().density;
        int[] w = new int[2], c = new int[2];
        webView.getLocationInWindow(w);
        ((View) box.getParent()).getLocationInWindow(c);
        int width = Math.max(1, Math.round(rect[2] * d)), height = Math.max(1, Math.round(rect[3] * d));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            box.setLayoutParams(lp);
        }
        box.setTranslationX(w[0] - c[0] + rect[0] * d);
        box.setTranslationY(w[1] - c[1] + rect[1] * d);
        box.setVisibility(rect.length > 4 && rect[4] == 0 ? View.GONE : View.VISIBLE);
    }

    static void mute(boolean m) {
        if (player != null && !inPip) {
            player.setVolume(m ? 0f : 1f);
        }
    }

    static void stop() {
        pipWatch.removeCallbacks(pipTick);
        autoPip(false);
        inPip = false;
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        if (box != null) {
            ViewGroup p = (ViewGroup) box.getParent();
            if (p != null) {
                p.removeView(box);
            }
            box = null;
            view = null;
        }
    }

    private static void emit(String s, String detail) {
        if (listener != null) {
            listener.onState(s, detail);
        }
    }
}
