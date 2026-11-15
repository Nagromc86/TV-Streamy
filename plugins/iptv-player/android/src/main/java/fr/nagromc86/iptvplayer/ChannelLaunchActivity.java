package fr.nagromc86.iptvplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONObject;

/** Invisible step between a channel widget and the player: the channel plays at once. */
public class ChannelLaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        JSONObject ch = ChannelWidget.channel(this, getIntent().getStringExtra(ChannelWidget.EXTRA_CH));
        if (ch != null && !ch.optString("url").isEmpty()) {
            if (PlayerActivity.current != null && !PlayerActivity.current.isFinishing()) {
                PlayerActivity.current.finish();
            }
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_URL, ch.optString("url"));
            i.putExtra(PlayerActivity.EXTRA_FALLBACK_URL, ch.optString("fallbackUrl"));
            i.putExtra(PlayerActivity.EXTRA_TITLE, ch.optString("name"));
            i.putExtra(PlayerActivity.EXTRA_SUBTITLE, "En direct");
            i.putExtra(PlayerActivity.EXTRA_IMAGE, ch.optString("logo"));
            i.putExtra(PlayerActivity.EXTRA_LIVE, true);
            i.putExtra(PlayerActivity.EXTRA_SUB_MODE, ch.optString("subMode", "auto"));
            i.putExtra(PlayerActivity.EXTRA_BG_AUDIO, true);
            i.putExtra(PlayerActivity.EXTRA_MUTED, false);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else {
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(open);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
