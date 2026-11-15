package fr.nagromc86.iptvplayer;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class TonightWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            ListWidgets.draw(c, mgr, id, "tonight");
        }
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.tvs_l_list);
    }
}
