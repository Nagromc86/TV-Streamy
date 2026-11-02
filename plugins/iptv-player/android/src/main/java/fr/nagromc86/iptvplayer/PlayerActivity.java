package fr.nagromc86.iptvplayer;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Rational;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

/**
 * Full-screen player: live TV (TS or HLS), films and episodes (MP4, MKV...).
 * Audio and subtitle tracks are chosen from the player's settings button; French is preferred.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_FALLBACK_URL = "fallbackUrl";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_SUBTITLE = "subtitle";
    static final String EXTRA_START_MS = "startMs";
    static final String EXTRA_LIVE = "live";
    static final String EXTRA_USER_AGENT = "userAgent";
    static final String EXTRA_SUB_MODE = "subMode";
    static final String EXTRA_SUB_URLS = "subUrls";
    static final String EXTRA_SUB_LABELS = "subLabels";
    static final String EXTRA_SUB_LANGS = "subLangs";

    static final String RESULT_POSITION = "positionMs";
    static final String RESULT_DURATION = "durationMs";
    static final String RESULT_ENDED = "ended";
    static final String RESULT_ERROR = "error";

    /** The player currently open (full screen or in its floating window). */
    static PlayerActivity current;

    private ExoPlayer player;
    private TextView pipButton;
    private boolean inPip;
    private PlayerView playerView;
    private LinearLayout titleBox;
    private boolean live;
    private boolean ended;
    private boolean triedFallback;
    private String fallbackUrl;
    private String lastError;
    private boolean finishing;
    private boolean tracksChecked;
    private String subMode;
    private List<MediaItem.SubtitleConfiguration> extraSubs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent in = getIntent();
        String url = in.getStringExtra(EXTRA_URL);
        fallbackUrl = in.getStringExtra(EXTRA_FALLBACK_URL);
        live = in.getBooleanExtra(EXTRA_LIVE, false);
        long startMs = in.getLongExtra(EXTRA_START_MS, 0L);
        subMode = in.getStringExtra(EXTRA_SUB_MODE);
        if (subMode == null) {
            subMode = "auto";
        }
        String[] subUrls = in.getStringArrayExtra(EXTRA_SUB_URLS);
        String[] subLabels = in.getStringArrayExtra(EXTRA_SUB_LABELS);
        String[] subLangs = in.getStringArrayExtra(EXTRA_SUB_LANGS);
        if (subUrls != null) {
            for (int i = 0; i < subUrls.length; i++) {
                if (TextUtils.isEmpty(subUrls[i])) {
                    continue;
                }
                extraSubs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrls[i]))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(subLangs != null && i < subLangs.length ? subLangs[i] : "fr")
                        .setLabel(subLabels != null && i < subLabels.length ? subLabels[i] : "Sous-titres")
                        .build());
            }
        }
        String userAgent = in.getStringExtra(EXTRA_USER_AGENT);
        if (TextUtils.isEmpty(userAgent)) {
            userAgent = "Mozilla/5.0 (Linux; Android) LecteurIPTV/1.0";
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerView.setShowSubtitleButton(true);
        playerView.setKeepScreenOn(true);
        root.addView(playerView);
        titleBox = buildTitle(in.getStringExtra(EXTRA_TITLE), in.getStringExtra(EXTRA_SUBTITLE));
        root.addView(titleBox);
        pipButton = buildPipButton();
        if (pipButton != null) {
            root.addView(pipButton);
        }
        setContentView(root);
        current = this;
        hideSystemUi();

        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            titleBox.setVisibility(inPip ? View.GONE : visibility);
            if (pipButton != null) {
                pipButton.setVisibility(inPip ? View.GONE : visibility);
            }
            if (visibility != View.VISIBLE) {
                hideSystemUi();
            }
        });

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http));
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sources).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage("fr")
                .setPreferredTextLanguage("fr")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, "never".equals(subMode))
                .build());
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && !live) {
                    ended = true;
                    finishWithResult();
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                updatePipParams();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePipParams();
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                // Tell once, when the video has loaded, whether it carries subtitles at all
                if (tracksChecked || live || tracks.isEmpty() || !tracks.containsType(C.TRACK_TYPE_VIDEO)) {
                    return;
                }
                tracksChecked = true;
                if ("auto".equals(subMode) && audioIsFrench(tracks)) {
                    // French audio: keep subtitles available in CC but don't show them
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                    return;
                }
                if ("never".equals(subMode)) {
                    return;
                }
                String msg;
                if (!tracks.containsType(C.TRACK_TYPE_TEXT)) {
                    msg = "Pas de sous-titres dans cette vidéo";
                } else if (!hasFrenchText(tracks)) {
                    msg = "Pas de sous-titres français · autres langues dans CC";
                } else {
                    return;
                }
                Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                lastError = error.getErrorCodeName();
                if (!triedFallback && !TextUtils.isEmpty(fallbackUrl)) {
                    triedFallback = true;
                    long pos = live ? C.TIME_UNSET : player.getCurrentPosition();
                    player.setMediaItem(buildItem(fallbackUrl, getIntent().getStringExtra(EXTRA_TITLE)));
                    if (pos != C.TIME_UNSET && pos > 0) {
                        player.seekTo(pos);
                    }
                    player.prepare();
                    player.play();
                    return;
                }
                Toast.makeText(PlayerActivity.this, "Lecture impossible : " + describe(error), Toast.LENGTH_LONG).show();
            }
        });

        player.setMediaItem(buildItem(url, in.getStringExtra(EXTRA_TITLE)));
        if (!live && startMs > 0) {
            player.seekTo(startMs);
        }
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /* ---------- floating window (picture-in-picture) ---------- */

    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        Rational ratio = new Rational(16, 9);
        if (player != null) {
            VideoSize v = player.getVideoSize();
            if (v.width > 0 && v.height > 0) {
                float r = v.width * v.pixelWidthHeightRatio / v.height;
                r = Math.max(1 / 2.39f, Math.min(2.39f, r));
                ratio = new Rational(Math.round(r * 1000), 1000);
            }
        }
        b.setAspectRatio(ratio);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(player != null && player.isPlaying());
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    private void updatePipParams() {
        if (!pipSupported()) {
            return;
        }
        try {
            setPictureInPictureParams(buildPipParams());
        } catch (Exception ignored) {
            // the system may refuse while the activity is finishing
        }
    }

    private void enterPip(boolean fromButton) {
        if (!pipSupported()) {
            if (fromButton) {
                Toast.makeText(this, "Ce téléphone ne permet pas la vidéo en fenêtre", Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            enterPictureInPictureMode(buildPipParams());
        } catch (Exception e) {
            if (fromButton) {
                Toast.makeText(this, "Vidéo en fenêtre désactivée : Réglages Android › Applis › TV Streamy › Picture-in-picture", Toast.LENGTH_LONG).show();
            }
        }
    }

    private TextView buildPipButton() {
        if (!pipSupported()) {
            return null;
        }
        TextView b = new TextView(this);
        b.setText("Réduire");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x99FFFFFF);
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        b.setContentDescription("Réduire la vidéo dans une fenêtre");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(14), dp(20), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> enterPip(true));
        return b;
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 12+ enters the window by itself (auto-enter); older versions need this
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player != null && player.isPlaying() && !finishing) {
            enterPip(false);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        inPip = isInPictureInPictureMode;
        playerView.setUseController(!inPip);
        if (inPip) {
            playerView.hideController();
            titleBox.setVisibility(View.GONE);
            if (pipButton != null) {
                pipButton.setVisibility(View.GONE);
            }
        } else {
            hideSystemUi();
        }
    }

    /** Close from outside (another video is being started). */
    void closeForNewVideo() {
        finishWithResult();
    }

    private static boolean audioIsFrench(Tracks tracks) {
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO || !g.isSelected()) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSelected(i)) {
                    continue;
                }
                String lang = g.getTrackFormat(i).language;
                return lang != null && (lang.startsWith("fr") || lang.equals("fre"));
            }
        }
        return false;
    }

    private static boolean hasFrenchText(Tracks tracks) {
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                String lang = g.getTrackFormat(i).language;
                if (lang != null && (lang.startsWith("fr") || lang.equals("fre"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private MediaItem buildItem(String url, String title) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        if (url != null && url.toLowerCase().contains(".m3u8")) {
            b.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        if (!extraSubs.isEmpty()) {
            b.setSubtitleConfigurations(extraSubs);
        }
        if (!TextUtils.isEmpty(title)) {
            b.setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        }
        return b.build();
    }

    private String describe(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "connexion au serveur impossible.";
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "le serveur a refusé le flux (abonnement, nombre de connexions ?).";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "format vidéo non pris en charge par ce téléphone.";
            default:
                return e.getErrorCodeName();
        }
    }

    private LinearLayout buildTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(14), pad, dp(14));
        box.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        box.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(title == null ? "" : title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(0xCCFFFFFF);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setMaxLines(1);
            s.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(s);
        }
        return box;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        finishWithResult();
    }

    private void finishWithResult() {
        if (finishing) {
            return;
        }
        finishing = true;
        Intent data = new Intent();
        if (player != null) {
            long duration = player.getDuration();
            data.putExtra(RESULT_POSITION, Math.max(0L, player.getCurrentPosition()));
            data.putExtra(RESULT_DURATION, duration == C.TIME_UNSET ? 0L : duration);
        }
        data.putExtra(RESULT_ENDED, ended);
        if (lastError != null) {
            data.putExtra(RESULT_ERROR, lastError);
        }
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (inPip) {
            // the floating window was closed: stop and report where we were
            finishWithResult();
            return;
        }
        if (player != null && !isFinishing()) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) {
            current = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
