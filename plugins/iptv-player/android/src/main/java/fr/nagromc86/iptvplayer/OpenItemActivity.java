package fr.nagromc86.iptvplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Widget tap that must open something in the app (a film, a series, the downloads…). */
public class OpenItemActivity extends Activity {
    static final String EXTRA_OPEN = "tvs_open";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String what = getIntent().getStringExtra(EXTRA_OPEN);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            open.setAction("fr.nagromc86.tvstreamy.OPEN");
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (what != null) {
                open.putExtra(EXTRA_OPEN, what);
            }
            startActivity(open);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
