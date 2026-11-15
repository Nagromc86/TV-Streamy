package fr.nagromc86.iptvplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 2 x 1 widget: each playlist and its expiry date, in red from 10 days before. */
public class AccountsWidget extends AppWidgetProvider {
    private static final int[] ROWS = {R.id.tvs_a_row1, R.id.tvs_a_row2, R.id.tvs_a_row3};
    private static final int[] NAMES = {R.id.tvs_a_n1, R.id.tvs_a_n2, R.id.tvs_a_n3};
    private static final int[] DATES = {R.id.tvs_a_d1, R.id.tvs_a_d2, R.id.tvs_a_d3};
    static final int WARN_DAYS = 10;

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("tvs_accounts", Context.MODE_PRIVATE);
    }

    static void setAccounts(Context c, String json) {
        prefs(c).edit().putString("list", json == null ? "[]" : json).apply();
        drawAll(c);
    }

    static void drawAll(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, AccountsWidget.class));
            if (ids.length > 0) {
                draw(c, mgr, ids);
            }
        } catch (Exception ignored) {
        }
    }

    private static void draw(Context c, AppWidgetManager mgr, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.tvs_accounts_widget);
        List<JSONObject> L = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                if (a.optJSONObject(i) != null) {
                    L.add(a.optJSONObject(i));
                }
            }
        } catch (Exception ignored) {
        }
        // the soonest expiry first; playlists without a date last
        Collections.sort(L, (x, y) -> {
            long ex = x.optLong("exp", 0), ey = y.optLong("exp", 0);
            if (ex == 0 && ey == 0) return x.optString("name").compareToIgnoreCase(y.optString("name"));
            if (ex == 0) return 1;
            if (ey == 0) return -1;
            return Long.compare(ex, ey);
        });
        long now = System.currentTimeMillis() / 1000L;
        boolean warn = false;
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", Locale.FRANCE);
        for (int i = 0; i < ROWS.length; i++) {
            if (i >= L.size()) {
                v.setViewVisibility(ROWS[i], View.GONE);
                continue;
            }
            JSONObject o = L.get(i);
            long exp = o.optLong("exp", 0);
            v.setViewVisibility(ROWS[i], View.VISIBLE);
            v.setTextViewText(NAMES[i], o.optString("name"));
            String d;
            int color = Color.parseColor("#B3FFFFFF");
            boolean bold = false;
            if (exp <= 0) {
                d = "sans date";
            } else {
                long days = (long) Math.ceil((exp - now) / 86400.0);
                if (days < 0) {
                    d = "expirée le " + df.format(new Date(exp * 1000L));
                    color = Color.parseColor("#FF5A5F");
                    bold = true;
                } else if (days <= WARN_DAYS) {
                    d = (days == 0 ? "expire aujourd'hui" : days == 1 ? "expire demain" : "expire dans " + days + " j") + " · " + new SimpleDateFormat("d MMM", Locale.FRANCE).format(new Date(exp * 1000L));
                    color = Color.parseColor("#FF5A5F");
                    bold = true;
                } else {
                    d = "jusqu'au " + df.format(new Date(exp * 1000L));
                }
                if (bold) {
                    warn = true;
                }
            }
            SpannableString sp = new SpannableString(d);
            if (bold) {
                sp.setSpan(new StyleSpan(Typeface.BOLD), 0, d.length(), 0);
            }
            v.setTextViewText(DATES[i], sp);
            v.setTextColor(DATES[i], color);
            v.setTextColor(NAMES[i], bold ? Color.parseColor("#FF8A8E") : Color.WHITE);
        }
        v.setTextViewText(R.id.tvs_a_title, L.isEmpty() ? "Aucune playlist" : warn ? "⚠ Abonnement à renouveler" : "Mes playlists");
        v.setTextColor(R.id.tvs_a_title, warn ? Color.parseColor("#FF5A5F") : Color.parseColor("#80FFFFFF"));
        v.setInt(R.id.tvs_a_root, "setBackgroundResource", warn ? R.drawable.tvs_widget_bg_warn : R.drawable.tvs_widget_bg);
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (open != null) {
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.tvs_a_root, PendingIntent.getActivity(c, 9900, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        mgr.updateAppWidget(ids, v);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        draw(context, mgr, ids);
    }
}
