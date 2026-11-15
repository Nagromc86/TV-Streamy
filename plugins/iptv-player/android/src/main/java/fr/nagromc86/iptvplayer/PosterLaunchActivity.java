package fr.nagromc86.iptvplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/** Tap on a poster: the match is on (or about to start) → its channel plays; otherwise the sports hub opens. */
public class PosterLaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String id = getIntent().getStringExtra(PostersWidget.EXTRA_POSTER);
        JSONObject p = null;
        try {
            JSONArray a = new JSONArray(PostersWidget.prefs(this).getString("list", "[]"));
            for (int i = 0; i < a.length() && id != null; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    p = o;
                }
            }
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis() / 1000L;
        if (p != null && !TextUtils.isEmpty(p.optString("url")) && p.optLong("s") - now < 45 * 60) {
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_URL, p.optString("url"));
            i.putExtra(PlayerActivity.EXTRA_FALLBACK_URL, p.optString("fallbackUrl"));
            i.putExtra(PlayerActivity.EXTRA_TITLE, p.optString("chName"));
            i.putExtra(PlayerActivity.EXTRA_SUBTITLE, p.optString("title"));
            i.putExtra(PlayerActivity.EXTRA_LIVE, true);
            i.putExtra(PlayerActivity.EXTRA_BG_AUDIO, true);
            i.putExtra(PlayerActivity.EXTRA_MUTED, false);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else {
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null) {
                open.setAction("fr.nagromc86.tvstreamy.SPORTS");
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                open.putExtra(PostersWidget.EXTRA_SPORTS, true);
                startActivity(open);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
