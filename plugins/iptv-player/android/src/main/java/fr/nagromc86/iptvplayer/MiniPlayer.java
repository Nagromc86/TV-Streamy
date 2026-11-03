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

    static boolean active() {
        return player != null;
    }

    static void play(Activity act, View webView, String u, String fb, String ua, float[] rect, boolean muted, Listener l) {
        listener = l;
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
        url = u;
        fallback = fb;
        triedFallback = false;
        retries = 0;
        player.setVolume(muted ? 0f : 1f);
        place(act, webView, rect);
        start(u);
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
        if (box == null || rect == null) {
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
        if (player != null) {
            player.setVolume(m ? 0f : 1f);
        }
    }

    static void stop() {
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
