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
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
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
    static final String EXTRA_SUB_OFFSET = "subOffset";
    static final String RESULT_SUB_OFFSET = "subOffsetMs";
    static final String RESULT_USED_FALLBACK = "usedFallback";
    static final String RESULT_TAKEN_BY = "takenBy";
    static final String EXTRA_WATCH_URL = "watchUrl";
    static final String EXTRA_WATCH_TOKEN = "watchToken";
    static final String EXTRA_WATCH_REFRESH = "watchRefresh";
    static final String EXTRA_WATCH_KEY = "watchKey";
    static final String EXTRA_WATCH_DEV = "watchDev";
    static final String EXTRA_WATCH_SINCE = "watchSince";
    /* one screen at a time between synced devices: see watchTick */
    private String watchUrl, watchToken, watchRefresh, watchKey, watchDev;
    private long watchSince;
    private String takenBy;
    private final android.os.Handler watchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean fallbackWorked;

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
    /* the provider often still counts the previous stream for a few seconds: retry instead of failing */
    private int httpRetries;
    private String currentUrl;
    private boolean stoppedInBackground;
    private long backgroundPos;
    private final android.os.Handler retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean tracksChecked;
    private boolean audioFixTried;
    private String subMode;
    private List<MediaItem.SubtitleConfiguration> extraSubs = new ArrayList<>();

    /* OpenSubtitles subtitles are drawn by us over the video, so they can be shifted instantly */
    private final List<long[]> cueTimes = new ArrayList<>();
    private final List<CharSequence> cueTexts = new ArrayList<>();
    private long subOffset;
    private boolean extOn = true;
    private FrameLayout rootView;
    private LinearLayout actionsRow;
    private boolean frenchAudio;
    /* live pause: short pauses resume from memory; longer ones through the provider's replay (timeshift) */
    static final String EXTRA_TS_TPL = "tsTpl";
    static final String EXTRA_CHANNELS = "channels";
    static final String EXTRA_CH_INDEX = "chIndex";
    static final String RESULT_SID = "sid";
    static final String EXTRA_TS_TZ = "tsTz";
    private String tsTpl;
    private int tsTz;
    private String liveUrl;
    private long pausedAt;
    private long behindMs;
    private boolean onTimeshift;
    private long tsStartWall;
    private TextView goLiveButton;
    /* TV: zapping without leaving the player (▲ ▼, CH+/CH−) and the channel list on OK */
    private org.json.JSONArray channels;
    private int chIndex = -1;
    private String zapSid;
    private LinearLayout chPanel;
    private android.widget.ListView chList;
    private int eatenKey = -1;
    /* phone: videos start without sound; the Volume + key or the button brings it back */
    private boolean muted;
    private TextView muteButton;
    private final android.os.Handler zapHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /* subtitles: the video's own French ones first (CC); OpenSubtitles only when the video has none, or on request */
    static final String EXTRA_OS_ON = "osOn";
    static final String EXTRA_BG_AUDIO = "bgAudio";
    static final String EXTRA_MUTED = "startMuted";
    /* sound goes on with the screen locked (foreground service + lock-screen controls) */
    private boolean bgAudio;
    private boolean inBackgroundAudio;
    private androidx.media3.session.MediaSession mediaSession;
    private boolean osOn;
    private boolean embeddedFrench;
    private boolean osRequested;
    private int lastCue = -2;
    private TextView subView;
    private TextView subButton;
    private LinearLayout subPanel;
    private TextView subValue;
    private TextView subToggle;
    private final android.os.Handler subHandler = new android.os.Handler(android.os.Looper.getMainLooper());

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
                if (subUrls[i].startsWith("data:") && cueTimes.isEmpty()) {
                    parseSrt(subUrls[i]);
                    if (!cueTimes.isEmpty()) {
                        continue;
                    }
                }
                extraSubs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrls[i]))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(subLangs != null && i < subLangs.length ? subLangs[i] : "fr")
                        .setLabel(subLabels != null && i < subLabels.length ? subLabels[i] : "Sous-titres")
                        .build());
            }
        }
        subOffset = in.getLongExtra(EXTRA_SUB_OFFSET, 0L);
        extOn = false;
        osOn = in.getBooleanExtra(EXTRA_OS_ON, false);
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
        if (!cueTimes.isEmpty()) {
            buildSubOverlay(root);
        } else if (osOn) {
            subButton = pill("OpenSub");
            subButton.setContentDescription("Sous-titres français OpenSubtitles");
            subButton.setOnClickListener(v -> {
                if (subPanel != null) {
                    showSubPanel(subPanel.getVisibility() != View.VISIBLE);
                } else {
                    requestOpenSub(true);
                }
            });
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        alp.setMargins(0, dp(14), dp(20), 0);
        actions.setLayoutParams(alp);
        if (subButton != null) {
            actions.addView(subButton);
        }
        tsTpl = in.getStringExtra(EXTRA_TS_TPL);
        tsTz = in.getIntExtra(EXTRA_TS_TZ, 0);
        if (live) {
            goLiveButton = pill("Retour au direct");
            goLiveButton.setContentDescription("Revenir au direct");
            goLiveButton.setVisibility(View.GONE);
            goLiveButton.setOnClickListener(v -> goLive(null));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.setMargins(dp(10), 0, 0, 0);
            goLiveButton.setLayoutParams(glp);
            actions.addView(goLiveButton);
        }
        if (pipButton != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.setMargins(dp(10), 0, 0, 0);
            pipButton.setLayoutParams(plp);
            actions.addView(pipButton);
        }
        muted = in.getBooleanExtra(EXTRA_MUTED, false);
        muteButton = pill("Son coupé · toucher pour l'activer");
        muteButton.setContentDescription("Activer le son");
        muteButton.setOnClickListener(v -> setMuted(false));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlp.setMargins(0, 0, 0, dp(96));
        muteButton.setLayoutParams(mlp);
        muteButton.setVisibility(muted ? View.VISIBLE : View.GONE);
        root.addView(actions);
        root.addView(muteButton);
        String chs = in.getStringExtra(EXTRA_CHANNELS);
        if (live && !TextUtils.isEmpty(chs)) {
            try {
                channels = new org.json.JSONArray(chs);
                chIndex = in.getIntExtra(EXTRA_CH_INDEX, -1);
            } catch (Exception ignored) {
                channels = null;
            }
            if (channels != null && channels.length() > 1) {
                buildChannelPanel(root);
            } else {
                channels = null;
            }
        }
        rootView = root;
        actionsRow = actions;
        setContentView(root);
        current = this;
        hideSystemUi();

        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            titleBox.setVisibility(inPip ? View.GONE : visibility);
            if (pipButton != null) {
                pipButton.setVisibility(inPip ? View.GONE : visibility);
            }
            if (subButton != null) {
                subButton.setVisibility(inPip ? View.GONE : visibility);
            }
            if (goLiveButton != null) {
                goLiveButton.setVisibility(inPip || !isBehind() ? View.GONE : visibility);
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

        ExoPlayer.Builder pb = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sources);
        if (!live) {
            // films and episodes: start after 1 s of video instead of 2.5 s; after a stall, still wait for 5 s
            // so playback does not stop and go. Live TV keeps the default, steadier start.
            pb.setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                            1000, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                    .build());
        }
        player = pb.build();
        if (muted) {
            player.setVolume(0f);
        }
        // calls and other apps pause the video; unplugging headphones pauses too; CPU and Wi-Fi stay awake screen off
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        bgAudio = in.getBooleanExtra(EXTRA_BG_AUDIO, true);
        try {
            mediaSession = new androidx.media3.session.MediaSession.Builder(this, player).setId("tvs" + System.currentTimeMillis()).build();
            AudioService.session = mediaSession;
            AudioService.title = in.getStringExtra(EXTRA_TITLE) == null ? "TV Streamy" : in.getStringExtra(EXTRA_TITLE);
            AudioService.text = in.getStringExtra(EXTRA_SUBTITLE) == null ? "" : in.getStringExtra(EXTRA_SUBTITLE);
        } catch (Exception ignored) {
            // no lock-screen controls on this device
        }
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage("fr")
                .setPreferredTextLanguage("fr")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, "never".equals(subMode))
                .build());
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    httpRetries = 0;
                    lastError = null;
                    if (triedFallback) {
                        fallbackWorked = true;
                    }
                }
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
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (!live || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    return;
                }
                if (!playWhenReady) {
                    if (pausedAt == 0) {
                        pausedAt = System.currentTimeMillis();
                    }
                    return;
                }
                if (pausedAt == 0) {
                    return;
                }
                long paused = System.currentTimeMillis() - pausedAt;
                pausedAt = 0;
                if (paused < 3000) {
                    return;
                }
                if (onTimeshift) {
                    // the provider may have closed the replay while paused: reopen it where we were
                    if (paused > 60000) {
                        openTimeshift(tsStartWall + player.getCurrentPosition());
                    }
                    return;
                }
                behindMs += paused;
                if (paused <= 45000) {
                    // short pause: the video kept in memory goes on, a little behind the live
                    showBehind();
                } else if (!TextUtils.isEmpty(tsTpl)) {
                    openTimeshift(System.currentTimeMillis() - behindMs);
                } else {
                    goLive("Pause trop longue et pas de replay sur cette chaîne : retour au direct");
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                // Son muet sur certaines vidéos : si la piste audio sélectionnée n'est
                // pas décodable (ex. DTS), basculer vers une piste lisible.
                if (!tracks.isEmpty() && tracks.containsType(C.TRACK_TYPE_AUDIO)) {
                    ensureAudible(tracks);
                }
                // Tell once, when the video has loaded, whether it carries subtitles at all
                if (tracksChecked || live || tracks.isEmpty() || !tracks.containsType(C.TRACK_TYPE_VIDEO)) {
                    return;
                }
                tracksChecked = true;
                if ("auto".equals(subMode) && audioIsFrench(tracks)) {
                    // French audio: keep subtitles available (CC / Sous-titres button) but don't show them
                    frenchAudio = true;
                    extOn = false;
                    refreshSub();
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                    return;
                }
                embeddedFrench = hasFrenchText(tracks);
                if ("never".equals(subMode) || embeddedFrench) {
                    // the video's own French subtitles (CC) are used; OpenSub stays available on the button
                    return;
                }
                if (!cueTimes.isEmpty()) {
                    extOn = true;
                    refreshSub();
                    return;
                }
                if (osOn) {
                    requestOpenSub(false);
                    return;
                }
                Toast.makeText(PlayerActivity.this, tracks.containsType(C.TRACK_TYPE_TEXT) ? "Pas de sous-titres français · autres langues dans CC" : "Pas de sous-titres dans cette vidéo", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                lastError = error.getErrorCodeName();
                if (live && isBehind() && !finishing) {
                    goLive(onTimeshift ? "Le replay ne répond pas : retour au direct" : "Retour au direct");
                    return;
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && httpRetries < 3 && !finishing) {
                    httpRetries++;
                    final long pos = live ? C.TIME_UNSET : player.getCurrentPosition();
                    Toast.makeText(PlayerActivity.this, "Reconnexion à ton fournisseur… nouvel essai (" + httpRetries + "/3)", Toast.LENGTH_SHORT).show();
                    signInAgain(currentUrl);
                    retryHandler.postDelayed(() -> {
                        if (player == null || finishing) {
                            return;
                        }
                        player.setMediaItem(buildItem(currentUrl, getIntent().getStringExtra(EXTRA_TITLE)));
                        if (pos != C.TIME_UNSET && pos > 0) {
                            player.seekTo(pos);
                        }
                        player.prepare();
                        player.play();
                    }, 2000L * httpRetries);
                    return;
                }
                if (!triedFallback && !TextUtils.isEmpty(fallbackUrl)) {
                    triedFallback = true;
                    httpRetries = 0;
                    currentUrl = fallbackUrl;
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

        currentUrl = url;
        liveUrl = url;
        player.setMediaItem(buildItem(url, in.getStringExtra(EXTRA_TITLE)));
        watchUrl = in.getStringExtra(EXTRA_WATCH_URL);
        watchToken = in.getStringExtra(EXTRA_WATCH_TOKEN);
        watchRefresh = in.getStringExtra(EXTRA_WATCH_REFRESH);
        watchKey = in.getStringExtra(EXTRA_WATCH_KEY);
        watchDev = in.getStringExtra(EXTRA_WATCH_DEV);
        watchSince = in.getLongExtra(EXTRA_WATCH_SINCE, 0L);
        if (!TextUtils.isEmpty(watchUrl) && !TextUtils.isEmpty(watchDev) && watchSince > 0) {
            watchHandler.postDelayed(watchTick, 20000);
        }
        if (!live && startMs > 0) {
            player.seekTo(startMs);
        }
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /* ---------- floating window (picture-in-picture) ---------- */

    private boolean isTvDevice() {
        android.app.UiModeManager um = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        return um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

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

    /* ---------- subtitles drawn over the video, with an adjustable delay ---------- */

    private void parseSrt(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            String srt = new String(android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
            srt = srt.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n");
            java.util.regex.Pattern tp = java.util.regex.Pattern.compile("(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");
            List<Object[]> cues = new ArrayList<>();
            for (String block : srt.split("\n\\s*\n")) {
                String[] lines = block.trim().split("\n");
                for (int i = 0; i < lines.length; i++) {
                    java.util.regex.Matcher m = tp.matcher(lines[i]);
                    if (!m.find()) {
                        continue;
                    }
                    StringBuilder t = new StringBuilder();
                    for (int j = i + 1; j < lines.length; j++) {
                        String l = lines[j].replaceAll("\\{[^}]*\\}", "").trim();
                        if (l.isEmpty()) {
                            continue;
                        }
                        if (t.length() > 0) {
                            t.append("<br>");
                        }
                        t.append(l);
                    }
                    if (t.length() > 0) {
                        cues.add(new Object[] {srtMs(m, 1), srtMs(m, 5), fromHtml(t.toString())});
                    }
                    break;
                }
            }
            java.util.Collections.sort(cues, (a, b) -> Long.compare((Long) a[0], (Long) b[0]));
            for (Object[] c : cues) {
                cueTimes.add(new long[] {(Long) c[0], (Long) c[1]});
                cueTexts.add((CharSequence) c[2]);
            }
        } catch (Exception ignored) {
            // unreadable file: play without these subtitles
        }
    }

    private static long srtMs(java.util.regex.Matcher m, int g) {
        StringBuilder f = new StringBuilder(m.group(g + 3));
        while (f.length() < 3) {
            f.append('0');
        }
        return Long.parseLong(m.group(g)) * 3600000L + Long.parseLong(m.group(g + 1)) * 60000L + Long.parseLong(m.group(g + 2)) * 1000L
                + Long.parseLong(f.substring(0, 3));
    }

    @SuppressWarnings("deprecation")
    private static CharSequence fromHtml(String s) {
        return Build.VERSION.SDK_INT >= 24 ? android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY) : android.text.Html.fromHtml(s);
    }

    private TextView pill(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setMinWidth(dp(52));
        GradientDrawable n = new GradientDrawable();
        n.setColor(0x66000000);
        n.setStroke(dp(1), 0x99FFFFFF);
        n.setCornerRadius(dp(18));
        GradientDrawable f = new GradientDrawable();
        f.setColor(0xCCFFFFFF);
        f.setCornerRadius(dp(18));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, f);
        bg.addState(new int[] {android.R.attr.state_pressed}, f);
        bg.addState(new int[] {}, n);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, has) -> ((TextView) v).setTextColor(has ? Color.BLACK : Color.WHITE));
        return b;
    }

    private void buildSubOverlay(FrameLayout root) {
        subView = new TextView(this);
        subView.setTextColor(Color.WHITE);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        subView.setGravity(Gravity.CENTER);
        subView.setShadowLayer(4f, 0f, 2f, 0xFF000000);
        subView.setBackgroundColor(0x80000000);
        subView.setPadding(dp(10), dp(4), dp(10), dp(4));
        subView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(dp(40), 0, dp(40), dp(34));
        subView.setLayoutParams(lp);
        root.addView(subView);

        if (subButton == null) {
            subButton = pill("OpenSub");
            subButton.setContentDescription("Sous-titres OpenSubtitles : décalage, afficher ou masquer");
        }
        subButton.setOnClickListener(v -> showSubPanel(subPanel.getVisibility() != View.VISIBLE));

        subPanel = new LinearLayout(this);
        subPanel.setOrientation(LinearLayout.VERTICAL);
        subPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        subPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xE6101014);
        pbg.setCornerRadius(dp(16));
        subPanel.setBackground(pbg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        plp.setMargins(dp(12), dp(70), dp(12), 0);
        subPanel.setLayoutParams(plp);
        subPanel.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("Décalage des sous-titres");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        subPanel.addView(title);
        subValue = new TextView(this);
        subValue.setTextColor(Color.WHITE);
        subValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        subValue.setPadding(0, dp(2), 0, dp(6));
        subPanel.addView(subValue);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        long[] steps = {-5000, -1000, -500, 500, 1000, 5000};
        String[] labels = {"−5 s", "−1 s", "−0,5", "+0,5", "+1 s", "+5 s"};
        for (int i = 0; i < steps.length; i++) {
            final long st = steps[i];
            TextView b = pill(labels[i]);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> {
                subOffset = Math.max(-120000, Math.min(120000, subOffset + st));
                refreshSub();
                showSubPanel(true);
            });
            row.addView(b);
        }
        subPanel.addView(row);

        TextView hint = new TextView(this);
        hint.setText("− : sous-titres plus tôt   ·   + : plus tard");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(8), 0, dp(8));
        subPanel.addView(hint);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = pill("Remettre à 0");
        reset.setOnClickListener(v -> {
            subOffset = 0;
            refreshSub();
            showSubPanel(true);
        });
        subToggle = pill("");
        subToggle.setOnClickListener(v -> {
            extOn = !extOn;
            refreshSub();
            showSubPanel(true);
        });
        TextView ok = pill("OK");
        ok.setOnClickListener(v -> showSubPanel(false));
        for (TextView b : new TextView[] {reset, subToggle, ok}) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMargins(dp(4), 0, dp(4), 0);
            b.setLayoutParams(blp);
            row2.addView(b);
        }
        subPanel.addView(row2);
        root.addView(subPanel);
        refreshSub();
        subHandler.post(subTick);
    }

    /** OpenSubtitles file found after the video already started: draw it from now on. */
    void addExternalSubs(String dataUri, long offsetMs) {
        runOnUiThread(() -> {
            if (isFinishing() || !cueTimes.isEmpty() || rootView == null) {
                return;
            }
            boolean asked = osRequested;
            parseSrt(dataUri);
            if (cueTimes.isEmpty()) {
                return;
            }
            subOffset = offsetMs;
            extOn = asked || (!"never".equals(subMode) && !frenchAudio && !embeddedFrench);
            if (asked && embeddedFrench && player != null) {
                // asked for OpenSub over the video's own subtitles: show only one set
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            }
            boolean hadButton = subButton != null && subButton.getParent() != null;
            buildSubOverlay(rootView);
            if (!hadButton && subButton != null && actionsRow != null) {
                subButton.setVisibility(playerView.isControllerFullyVisible() && !inPip ? View.VISIBLE : View.GONE);
                actionsRow.addView(subButton, 0);
            }
            if (extOn) {
                Toast.makeText(this, "Sous-titres français OpenSubtitles ajoutés", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Ask the app to fetch French subtitles on OpenSubtitles (byUser: the OpenSub button was pressed). */
    private void requestOpenSub(boolean byUser) {
        if (byUser) {
            osRequested = true;
            Toast.makeText(this, "Recherche des sous-titres français sur OpenSubtitles…", Toast.LENGTH_SHORT).show();
        }
        PlayerPlugin.requestOpenSub(byUser);
    }

    void showMessage(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private final Runnable hidePanel = () -> {
        if (subPanel != null) {
            subPanel.setVisibility(View.GONE);
        }
    };

    private void showSubPanel(boolean show) {
        subHandler.removeCallbacks(hidePanel);
        subPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            subHandler.postDelayed(hidePanel, 8000);
            playerView.showController();
        }
    }

    private void refreshSub() {
        lastCue = -2;
        if (subValue != null) {
            double s = subOffset / 1000.0;
            subValue.setText((s > 0 ? "+" : "") + String.format(java.util.Locale.FRANCE, "%.1f s", s));
        }
        if (subToggle != null) {
            subToggle.setText(extOn ? "Masquer" : "Afficher");
        }
    }

    private final Runnable subTick = new Runnable() {
        @Override
        public void run() {
            if (player != null && subView != null) {
                long t = player.getCurrentPosition() - subOffset;
                int idx = -1;
                for (int i = 0; i < cueTimes.size(); i++) {
                    long[] c = cueTimes.get(i);
                    if (c[0] > t) {
                        break;
                    }
                    if (t < c[1]) {
                        idx = i;
                    }
                }
                if (idx != lastCue) {
                    lastCue = idx;
                    if (idx >= 0 && extOn && !inPip) {
                        subView.setText(cueTexts.get(idx));
                        subView.setVisibility(View.VISIBLE);
                    } else {
                        subView.setVisibility(View.GONE);
                    }
                }
            }
            subHandler.postDelayed(this, 100);
        }
    };

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
        // home / app switch while the video plays: it goes on in a floating window. Android 12+ usually does it by
        // itself (auto-enter); asking again covers the cases where it did not (3-button navigation, some brands)
        if (player != null && player.getPlayWhenReady() && !finishing && !inPip && !isTvDevice()
                && !(Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode())) {
            enterPip(false);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        inPip = isInPictureInPictureMode;
        playerView.setUseController(!inPip);
        if (muteButton != null) {
            muteButton.setVisibility(muted && !inPip ? View.VISIBLE : View.GONE);
        }
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

    /**
     * Many providers only accept a stream after a recent sign-in from the same internet address (after a Wi-Fi / 4G
     * change for example). The stream address holds the account: /live|movie|series/user/password/id.ext
     */
    static void signInAgain(String streamUrl) {
        if (streamUrl == null) {
            return;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https?://.+?)/(?:live|movie|series)/([^/]+)/([^/]+)/[^/]+$").matcher(streamUrl);
        if (!m.find()) {
            return;
        }
        String api = m.group(1) + "/player_api.php?username=" + m.group(2) + "&password=" + m.group(3);
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(api).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LecteurIPTV/1.0");
                java.io.InputStream in = c.getInputStream();
                byte[] b = new byte[4096];
                while (in.read(b) > 0) {
                    // read the answer fully so the sign-in is complete
                }
                in.close();
                c.disconnect();
            } catch (Exception ignored) {
                // the retry will tell
            }
        }).start();
    }

    /** Every 20 s (few reads, so the shared free quota holds): has another synced device started a video since this one began? Then stop here. */
    private final Runnable watchTick = new Runnable() {
        @Override
        public void run() {
            if (finishing) {
                return;
            }
            new Thread(() -> {
                String by = checkTaken();
                runOnUiThread(() -> {
                    if (finishing) {
                        return;
                    }
                    if (by != null) {
                        takenBy = by;
                        Toast.makeText(PlayerActivity.this, "Lecture lancée sur " + by + " : arrêt ici", Toast.LENGTH_LONG).show();
                        finishWithResult();
                    } else {
                        watchHandler.postDelayed(watchTick, 20000);
                    }
                });
            }).start();
        }
    };

    private String checkTaken() {
        try {
            String body = httpGet(watchUrl, watchToken);
            if (body == null && !TextUtils.isEmpty(watchRefresh) && !TextUtils.isEmpty(watchKey)) {
                // the sign-in token expired (1 h): get a new one and ask again
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://securetoken.googleapis.com/v1/token?key=" + watchKey).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.getOutputStream().write(("grant_type=refresh_token&refresh_token=" + java.net.URLEncoder.encode(watchRefresh, "UTF-8")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (c.getResponseCode() == 200) {
                    org.json.JSONObject j = new org.json.JSONObject(readAll(c.getInputStream()));
                    watchToken = j.optString("id_token", watchToken);
                    body = httpGet(watchUrl, watchToken);
                }
                c.disconnect();
            }
            if (body == null) {
                return null;
            }
            org.json.JSONObject f = new org.json.JSONObject(body).optJSONObject("fields");
            if (f == null) {
                return null;
            }
            String dev = f.optJSONObject("dev") != null ? f.optJSONObject("dev").optString("stringValue", "") : "";
            long at = f.optJSONObject("at") != null ? Long.parseLong(f.optJSONObject("at").optString("integerValue", "0")) : 0L;
            if (!dev.isEmpty() && !dev.equals(watchDev) && at > watchSince) {
                String name = f.optJSONObject("name") != null ? f.optJSONObject("name").optString("stringValue", "") : "";
                return name.isEmpty() ? "un autre appareil" : name;
            }
        } catch (Exception ignored) {
            // no network: keep playing
        }
        return null;
    }

    /** Body of a GET, or null when the token was refused (401/403). */
    private static String httpGet(String url, String token) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        if (code == 401 || code == 403) {
            c.disconnect();
            return null;
        }
        if (code != 200) {
            c.disconnect();
            throw new java.io.IOException("HTTP " + code);
        }
        String out = readAll(c.getInputStream());
        c.disconnect();
        return out;
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        in.close();
        return b.toString("UTF-8");
    }

    private boolean isBehind() {
        return onTimeshift || behindMs > 0;
    }

    private void showBehind() {
        if (goLiveButton != null) {
            goLiveButton.setVisibility(inPip ? View.GONE : View.VISIBLE);
        }
        playerView.showController();
    }

    /** Watch the channel from wallMs (device clock) through the provider's replay. */
    private void openTimeshift(long wallMs) {
        long startSec = (wallMs / 60000L) * 60L;
        long behindMin = Math.max(1, (System.currentTimeMillis() / 1000L - startSec) / 60L);
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis((startSec + tsTz * 60L) * 1000L);
        String start = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d:%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        String u = tsTpl.replace("{DUR}", String.valueOf(behindMin + 120)).replace("{START}", start);
        onTimeshift = true;
        tsStartWall = startSec * 1000L;
        currentUrl = u;
        player.setMediaItem(buildItem(u, getIntent().getStringExtra(EXTRA_TITLE)));
        long into = wallMs - tsStartWall;
        if (into > 0) {
            player.seekTo(into);
        }
        player.prepare();
        player.play();
        Toast.makeText(this, "En différé · « Retour au direct » en haut à droite", Toast.LENGTH_SHORT).show();
        showBehind();
    }

    private void goLive(String message) {
        onTimeshift = false;
        behindMs = 0;
        pausedAt = 0;
        currentUrl = liveUrl;
        if (player != null) {
            player.setMediaItem(buildItem(liveUrl, getIntent().getStringExtra(EXTRA_TITLE)));
            player.prepare();
            player.play();
        }
        if (goLiveButton != null) {
            goLiveButton.setVisibility(View.GONE);
        }
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    void togglePlay() {
        runOnUiThread(() -> {
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
                AudioService.refresh(this);
            }
        });
    }

    boolean isPlayingNow() {
        return player != null && player.getPlayWhenReady();
    }

    /** Close from outside (another video is being started). */
    void closeForNewVideo() {
        finishWithResult();
    }

    /**
     * Certaines vidéos ont une piste audio que le téléphone ne sait pas décoder
     * (souvent DTS, parfois E-AC3/TrueHD) : le sélecteur la choisit quand même et
     * la vidéo tourne sans son. On bascule alors sur une piste audio décodable
     * (en préférant le français) ; s'il n'y en a aucune, on prévient l'utilisateur.
     */
    private void ensureAudible(Tracks tracks) {
        if (audioFixTried || player == null) {
            return;
        }
        boolean anyAudio = false, selectedSupported = false;
        Tracks.Group best = null;
        int bestIdx = -1, bestScore = -1;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            anyAudio = true;
            for (int i = 0; i < g.length; i++) {
                boolean sup = g.isTrackSupported(i);
                if (g.isTrackSelected(i) && sup) {
                    selectedSupported = true;
                }
                if (sup) {
                    Format f = g.getTrackFormat(i);
                    int score = 0;
                    String lang = f.language;
                    if (lang != null && (lang.startsWith("fr") || lang.equals("fre"))) {
                        score += 10;
                    }
                    if ((f.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) {
                        score += 2;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = g;
                        bestIdx = i;
                    }
                }
            }
        }
        if (!anyAudio) {
            return; // pas encore d'info audio : on réessaiera au prochain onTracksChanged
        }
        if (selectedSupported) {
            audioFixTried = true;
            return; // déjà audible
        }
        audioFixTried = true;
        if (best != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .addOverride(new TrackSelectionOverride(best.getMediaTrackGroup(), bestIdx))
                    .build());
            Toast.makeText(this, "Piste audio incompatible : passage à une autre piste.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Son indisponible : la piste audio de cette vidéo (ex. DTS) n'est pas prise en charge par ce téléphone.", Toast.LENGTH_LONG).show();
        }
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

    private void setMuted(boolean m) {
        muted = m;
        if (player != null) {
            player.setVolume(m ? 0f : 1f);
        }
        if (muteButton != null) {
            muteButton.setVisibility(m && !inPip ? View.VISIBLE : View.GONE);
        }
    }

    private void buildChannelPanel(FrameLayout root) {
        chPanel = new LinearLayout(this);
        chPanel.setOrientation(LinearLayout.VERTICAL);
        chPanel.setBackgroundColor(0xF0101014);
        chPanel.setPadding(dp(12), dp(18), dp(12), dp(12));
        chPanel.setLayoutParams(new FrameLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        TextView head = new TextView(this);
        head.setText("Chaînes  ·  OK : regarder  ·  ► : fermer");
        head.setTextColor(0xB3FFFFFF);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setPadding(dp(10), 0, 0, dp(10));
        chPanel.addView(head);
        final List<String> labels = new ArrayList<>();
        labels.add("Audio, sous-titres et pause");
        for (int i = 0; i < channels.length(); i++) {
            org.json.JSONObject c = channels.optJSONObject(i);
            String sub = c == null ? "" : c.optString("s", "");
            labels.add((i + 1) + "   " + (c == null ? "" : c.optString("t", "")) + (TextUtils.isEmpty(sub) ? "" : "\n" + sub));
        }
        chList = new android.widget.ListView(this);
        chList.setDivider(null);
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(0xFFE50914);
        sel.setCornerRadius(dp(10));
        chList.setSelector(sel);
        chList.setDrawSelectorOnTop(false);
        chList.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, position == 0 ? 14 : 16);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(dp(14), dp(10), dp(14), dp(10));
                t.setTypeface(null, position - 1 == chIndex ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                t.setAlpha(position == 0 ? 0.8f : 1f);
                return t;
            }
        });
        chList.setOnItemClickListener((parent, view, position, id) -> {
            hideChannels();
            if (position == 0) {
                playerView.showController();
            } else if (position - 1 != chIndex) {
                zapTo(position - 1);
            }
        });
        chPanel.addView(chList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        chPanel.setVisibility(View.GONE);
        root.addView(chPanel);
    }

    private void showChannels() {
        if (chPanel == null) {
            return;
        }
        playerView.hideController();
        chPanel.setVisibility(View.VISIBLE);
        ((android.widget.BaseAdapter) chList.getAdapter()).notifyDataSetChanged();
        int at = Math.max(0, chIndex) + 1;
        chList.requestFocus();
        chList.setSelectionFromTop(at, dp(160));
    }

    private void hideChannels() {
        if (chPanel != null) {
            chPanel.setVisibility(View.GONE);
        }
        playerView.requestFocus();
    }

    private void zapBy(int d) {
        int n = channels.length();
        zapTo(((Math.max(0, chIndex) + d) % n + n) % n);
    }

    private void zapTo(int i) {
        org.json.JSONObject c = channels.optJSONObject(i);
        if (c == null || player == null) {
            return;
        }
        chIndex = i;
        zapSid = c.optString("id", null);
        String u = c.optString("u", "");
        String title = c.optString("t", "");
        String sub = (i + 1) + " · " + c.optString("s", "En direct");
        getIntent().putExtra(EXTRA_TITLE, title);
        fallbackUrl = c.optString("f", "");
        tsTpl = c.optString("ts", "");
        triedFallback = false;
        fallbackWorked = false;
        httpRetries = 0;
        lastError = null;
        onTimeshift = false;
        behindMs = 0;
        pausedAt = 0;
        liveUrl = u;
        currentUrl = u;
        retryHandler.removeCallbacksAndMessages(null);
        if (goLiveButton != null) {
            goLiveButton.setVisibility(View.GONE);
        }
        TextView t0 = (TextView) titleBox.getChildAt(0);
        t0.setText((i + 1) + "   " + title);
        if (titleBox.getChildCount() > 1) {
            ((TextView) titleBox.getChildAt(1)).setText(sub);
        }
        AudioService.title = title;
        AudioService.text = sub;
        titleBox.setVisibility(View.VISIBLE);
        zapHandler.removeCallbacksAndMessages(null);
        zapHandler.postDelayed(() -> {
            if (!playerView.isControllerFullyVisible()) {
                titleBox.setVisibility(View.GONE);
            }
        }, 3500);
        // a short delay, so running through several channels opens only the last one
        zapHandler.postDelayed(() -> {
            if (player == null || finishing || !u.equals(liveUrl)) {
                return;
            }
            player.setMediaItem(buildItem(u, title));
            player.prepare();
            player.play();
        }, 400);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // Volume + while the sound is off: the sound comes back (and the system raises the volume as usual)
        if (muted && e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            setMuted(false);
        }
        if (channels != null && !inPip) {
            int k = e.getKeyCode();
            if (e.getAction() == android.view.KeyEvent.ACTION_UP && k == eatenKey) {
                eatenKey = -1;
                return true;
            }
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                boolean open = chPanel != null && chPanel.getVisibility() == View.VISIBLE;
                boolean eat = false;
                if (open) {
                    if (k == android.view.KeyEvent.KEYCODE_BACK || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                        hideChannels();
                        eat = true;
                    }
                } else if (k == android.view.KeyEvent.KEYCODE_CHANNEL_UP) {
                    zapBy(-1);
                    eat = true;
                } else if (k == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    zapBy(1);
                    eat = true;
                } else if (!playerView.isControllerFullyVisible()) {
                    if (k == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        zapBy(-1);
                        eat = true;
                    } else if (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                        zapBy(1);
                        eat = true;
                    } else if (k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER) {
                        showChannels();
                        eat = true;
                    }
                }
                if (eat) {
                    eatenKey = k;
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
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
        data.putExtra(RESULT_SUB_OFFSET, subOffset);
        data.putExtra(RESULT_USED_FALLBACK, fallbackWorked);
        if (zapSid != null) {
            data.putExtra(RESULT_SID, zapSid);
        }
        if (takenBy != null) {
            data.putExtra(RESULT_TAKEN_BY, takenBy);
        }
        if (lastError != null) {
            data.putExtra(RESULT_ERROR, lastError);
        }
        setResult(RESULT_OK, data);
        retryHandler.removeCallbacksAndMessages(null);
        watchHandler.removeCallbacksAndMessages(null);
        zapHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            // close the connection to the provider right now, so the next video gets the screen
            player.stop();
        }
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
        if (player != null && !isFinishing() && bgAudio && player.getPlayWhenReady()) {
            // screen locked / app left while playing: the sound goes on, with controls on the lock screen
            inBackgroundAudio = true;
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(new Intent(this, AudioService.class));
                } else {
                    startService(new Intent(this, AudioService.class));
                }
            } catch (Exception e) {
                inBackgroundAudio = false;
            }
            if (inBackgroundAudio) {
                return;
            }
        }
        if (player != null && !isFinishing()) {
            // in the background: free the provider's screen (a paused stream still holds it), resume on return
            backgroundPos = player.getCurrentPosition();
            player.stop();
            stoppedInBackground = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (inBackgroundAudio) {
            inBackgroundAudio = false;
            stopService(new Intent(this, AudioService.class));
        }
        if (stoppedInBackground && player != null && !finishing) {
            stoppedInBackground = false;
            if (!live && backgroundPos > 0) {
                player.seekTo(backgroundPos);
            }
            player.prepare();
            player.setPlayWhenReady(live);
        }
    }

    @Override
    protected void onDestroy() {
        subHandler.removeCallbacksAndMessages(null);
        retryHandler.removeCallbacksAndMessages(null);
        watchHandler.removeCallbacksAndMessages(null);
        stopService(new Intent(this, AudioService.class));
        if (mediaSession != null) {
            if (AudioService.session == mediaSession) {
                AudioService.session = null;
            }
            mediaSession.release();
            mediaSession = null;
        }
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
