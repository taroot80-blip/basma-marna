package com.taroot.basma;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONException;
import org.json.JSONObject;

/** يستقبل المنبهات وأزرار الإشعارات وأحداث النظام (إعادة التشغيل وتغيير الوقت). */
public class AlarmReceiver extends BroadcastReceiver {
    static final String ACT_PRES = "com.taroot.basma.DONE_PRES";
    static final String ACT_OUT = "com.taroot.basma.DONE_OUT";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        switch (a) {
            case Scheduler.ACT_ALERT:
                String kind = intent.getStringExtra("kind");
                long target = intent.getLongExtra("target", 0);
                if (kind != null && target > 0) Notifier.alert(c, kind, target);
                break;
            case Scheduler.ACT_WATCH:
                WatchService.start(c, intent.getLongExtra("until", 0));
                break;
            case ACT_PRES:
                mark(c, "pres");
                Notifier.cancel(c, Notifier.ID_PRES, Notifier.ID_PRES_LAST, Notifier.ID_DETECT);
                break;
            case ACT_OUT:
                mark(c, "out");
                Notifier.cancel(c, Notifier.ID_OUT, Notifier.ID_DETECT);
                break;
            default:
                Scheduler.schedule(c);
        }
    }

    private static void mark(Context c, String key) {
        String date = Store.today();
        JSONObject d = Store.day(c, date);
        try {
            double at = d.has("last_close_" + key) ? d.getDouble("last_close_" + key) : Store.minOf(System.currentTimeMillis());
            d.put(key, at);
        } catch (JSONException ignored) { }
        Store.putDay(c, date, d);
        Scheduler.schedule(c);
    }
}
