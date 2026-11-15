package fr.nagromc86.iptvplayer;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Opens when a channel widget is placed (or reconfigured): pick one of the favourite channels. */
public class ChannelConfigActivity extends Activity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setResult(RESULT_CANCELED);
        Intent in = getIntent();
        if (in != null && in.getExtras() != null) {
            widgetId = in.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(24), dp(16), dp(24));
        list.setBackgroundColor(Color.parseColor("#141418"));
        TextView h = new TextView(this);
        h.setText("Quelle chaîne pour ce raccourci ?");
        h.setTextColor(Color.WHITE);
        h.setTextSize(20);
        h.setPadding(0, 0, 0, dp(6));
        list.addView(h);
        JSONArray a = ChannelWidget.channels(this);
        TextView sub = new TextView(this);
        sub.setTextColor(Color.parseColor("#B3FFFFFF"));
        sub.setTextSize(14);
        sub.setPadding(0, 0, 0, dp(14));
        sub.setText(a.length() == 0
                ? "Aucune chaîne favorite. Dans TV Streamy, mets des chaînes en favoris (étoile), reviens à l'accueil de l'appli, puis replace ce widget."
                : "Tes chaînes favorites de TV Streamy. Un appui sur le raccourci lancera la chaîne directement.");
        list.addView(sub);
        for (int i = 0; i < a.length(); i++) {
            final JSONObject ch = a.optJSONObject(i);
            if (ch == null) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#22FFFFFF"));
            bg.setCornerRadius(dp(12));
            row.setBackground(bg);
            ImageView logo = new ImageView(this);
            File f = ChannelWidget.logoFile(this, ch.optString("id"));
            if (f.exists()) {
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) {
                    logo.setImageBitmap(bm);
                }
            }
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(32)));
            TextView t = new TextView(this);
            t.setText(ch.optString("name"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(16);
            t.setPadding(dp(12), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> choose(ch));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            list.addView(row, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#141418"));
        sv.addView(list);
        setContentView(sv);
    }

    private void choose(JSONObject ch) {
        ChannelWidget.assign(this, widgetId, ch);
        new Thread(() -> {
            if (!ChannelWidget.logoFile(this, ch.optString("id")).exists()) {
                ChannelWidget.fetchLogo(getApplicationContext(), ch.optString("id"), ch.optString("logo"));
            }
            runOnUiThread(() -> ChannelWidget.draw(this, AppWidgetManager.getInstance(this), widgetId));
        }).start();
        ChannelWidget.draw(this, AppWidgetManager.getInstance(this), widgetId);
        Intent r = new Intent();
        r.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, r);
        finish();
    }
}
