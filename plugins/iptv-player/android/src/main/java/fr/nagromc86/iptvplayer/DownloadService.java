package fr.nagromc86.iptvplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps downloads going when the app is in the background, with a progress notification. */
public class DownloadService extends Service {

    private static final String CHANNEL = "tvs_downloads";
    private static final int ID = 4107;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ticking;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        DownloadEngine.get(this).kick();
        if (!ticking) {
            ticking = true;
            handler.post(tick);
        }
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            DownloadEngine e = DownloadEngine.get(DownloadService.this);
            if (!e.busy()) {
                ticking = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                stopSelf();
                return;
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(ID, build());
            handler.postDelayed(this, 1000);
        }
    };

    @SuppressWarnings("deprecation")
    private Notification build() {
        Object[] s = DownloadEngine.get(this).summary();
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle((String) s[0])
                .setContentText((String) s[1])
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        int pct = (Integer) s[2];
        b.setProgress(100, Math.max(0, pct), pct < 0);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            int fl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            b.setContentIntent(PendingIntent.getActivity(this, 0, open, fl));
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        ticking = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
