package fr.nagromc86.iptvplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Invisible step between the widget and the player: rebuilds the last video's player launch
 * (same file or stream, same title, subtitles mode…) and starts it at the saved position,
 * without opening the app first.
 */
public class ResumeLaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            String key = ResumeStore.get(this, "w_key");
            JSONObject l = ResumeStore.launchOf(this, key);
            if (l != null && !TextUtils.isEmpty(l.optString(PlayerActivity.EXTRA_URL))) {
                Intent i = new Intent(this, PlayerActivity.class);
                Iterator<String> it = l.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = l.get(k);
                    if (v instanceof Boolean) {
                        i.putExtra(k, (Boolean) v);
                    } else if (v instanceof Integer || v instanceof Long) {
                        i.putExtra(k, ((Number) v).longValue());
                    } else if (v instanceof Number) {
                        i.putExtra(k, ((Number) v).longValue());
                    } else {
                        i.putExtra(k, String.valueOf(v));
                    }
                }
                long pos = ResumeStore.positionOf(this, key);
                i.putExtra(PlayerActivity.EXTRA_START_MS, Math.max(0L, pos - 3000L));
                i.putExtra(PlayerActivity.EXTRA_LIVE, false);
                i.putExtra(PlayerActivity.EXTRA_MUTED, false);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                openApp();
            }
        } catch (Exception e) {
            openApp();
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** No player launch saved for this video (played before this version): the app resumes it. */
    private void openApp() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) {
            return;
        }
        // a different action, so Android hands the request to the already-open app instead of just bringing it forward
        open.setAction("fr.nagromc86.tvstreamy.RESUME");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra(ResumeWidget.EXTRA_RESUME, true);
        startActivity(open);
    }
}
