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
    static final String ACT_IN = "com.taroot.basma.DONE_IN";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (ACT_IN.equals(a) || ACT_PRES.equals(a) || ACT_OUT.equals(a)) Notifier.doneAlerts(c);
        if (a == null) return;
        switch (a) {
            case Scheduler.ACT_ALERT:
                String kind = intent.getStringExtra("kind");
                long target = intent.getLongExtra("target", 0);
                if (kind != null && target > 0) Notifier.alert(c, kind, target);
                break;
            case Scheduler.ACT_WATCH:
                // بداية نطاق الدوام: يضبط تنبيهات اليوم ويشغّل الخدمة في الخلفية حتى 3:00 م.
                Scheduler.schedule(c);
                break;
            case ACT_IN:
                markIn(c);
                Notifier.cancel(c, Notifier.ID_IN_SOON, Notifier.ID_IN_LAST);
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

    /** «بصمتُ الحضور الآن» من الإشعار: يُسجَّل وقت الضغط إن لم يُسجَّل حضور. */
    private static void markIn(Context c) {
        double now = Store.minOf(System.currentTimeMillis());
        if (now < Scheduler.from(c) || now >= Scheduler.to(c)) return;
        String date = Store.today();
        JSONObject d = Store.day(c, date);
        if (d.has("in") || d.has("lo")) return;
        try {
            d.put("in", Math.round(now * 60.0) / 60.0);
        } catch (JSONException ignored) { }
        Store.putDay(c, date, d);
        Scheduler.schedule(c);
    }

    private static void mark(Context c, String key) {
        double now = Store.minOf(System.currentTimeMillis());
        if (now < 360 || now >= 900) return; // خارج نطاق الدوام: من 6:00 ص إلى 3:00 م فقط
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
