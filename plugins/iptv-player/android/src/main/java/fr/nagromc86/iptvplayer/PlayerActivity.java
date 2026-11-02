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
        extOn = !"never".equals(subMode);
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
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        alp.setMargins(0, dp(14), dp(20), 0);
        actions.setLayoutParams(alp);
        if (subButton != null) {
            actions.addView(subButton);
        }
        if (pipButton != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.setMargins(dp(10), 0, 0, 0);
            pipButton.setLayoutParams(plp);
            actions.addView(pipButton);
        }
        root.addView(actions);
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
            public void onTracksChanged(Tracks tracks) {
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
                if ("never".equals(subMode) || !cueTimes.isEmpty()) {
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
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && httpRetries < 3 && !finishing) {
                    httpRetries++;
                    final long pos = live ? C.TIME_UNSET : player.getCurrentPosition();
                    Toast.makeText(PlayerActivity.this, "Le fournisseur libère l'écran précédent… nouvel essai (" + httpRetries + "/3)", Toast.LENGTH_SHORT).show();
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
                    }, 2500L * httpRetries);
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

        subButton = pill("Sous-titres");
        subButton.setContentDescription("Réglage des sous-titres");
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
            parseSrt(dataUri);
            if (cueTimes.isEmpty()) {
                return;
            }
            subOffset = offsetMs;
            extOn = !"never".equals(subMode) && !frenchAudio;
            buildSubOverlay(rootView);
            if (subButton != null && actionsRow != null) {
                subButton.setVisibility(playerView.isControllerFullyVisible() && !inPip ? View.VISIBLE : View.GONE);
                actionsRow.addView(subButton, 0);
            }
            if (extOn) {
                Toast.makeText(this, "Sous-titres français ajoutés", Toast.LENGTH_SHORT).show();
            }
        });
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
        data.putExtra(RESULT_SUB_OFFSET, subOffset);
        data.putExtra(RESULT_USED_FALLBACK, fallbackWorked);
        if (lastError != null) {
            data.putExtra(RESULT_ERROR, lastError);
        }
        setResult(RESULT_OK, data);
        retryHandler.removeCallbacksAndMessages(null);
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
