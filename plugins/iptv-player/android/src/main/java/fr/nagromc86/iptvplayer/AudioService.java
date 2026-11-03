package fr.nagromc86.iptvplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaStyleNotificationHelper;

/**
 * Keeps the video's sound playing while the screen is locked: a "now playing" notification with
 * pause / stop, also shown on the lock screen. The player itself stays in PlayerActivity.
 */
public class AudioService extends Service {

    static final String CHANNEL = "tvs_playback";
    static final int ID = 4108;
    static final String ACTION_TOGGLE = "fr.nagromc86.iptvplayer.TOGGLE";
    static final String ACTION_STOP = "fr.nagromc86.iptvplayer.STOP";
    static MediaSession session;
    static String title = "TV Streamy";
    static String text = "";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        PlayerActivity p = PlayerActivity.current;
        if (ACTION_TOGGLE.equals(a)) {
            if (p != null) {
                p.togglePlay();
            }
        } else if (ACTION_STOP.equals(a)) {
            if (p != null) {
                p.closeForNewVideo();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Lecture en arrière-plan", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification n = build(p != null && p.isPlayingNow());
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID, n);
        }
        return START_NOT_STICKY;
    }

    private PendingIntent self(String action, int code) {
        Intent i = new Intent(this, AudioService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build(boolean playing) {
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Lecture", self(ACTION_TOGGLE, 2))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", self(ACTION_STOP, 3));
        if (session != null) {
            b.setStyle(new MediaStyleNotificationHelper.MediaStyle(session).setShowActionsInCompactView(0, 1));
        }
        return b.build();
    }

    /** Called by the player when it pauses / resumes, to update the button. */
    static void refresh(android.content.Context c) {
        if (PlayerActivity.current == null) {
            return;
        }
        Intent i = new Intent(c, AudioService.class);
        try {
            c.startService(i);
        } catch (Exception ignored) {
            // not allowed from the background on some versions: the notification keeps its old button
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
