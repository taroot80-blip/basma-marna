package com.taroot.basma;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONObject;

/** يحسب مواعيد اليوم ويضبط منبهات النظام الدقيقة ونوافذ مراقبة تطبيق الوزارة. */
final class Scheduler {
    private Scheduler() {}

    static final String ACT_ALERT = "com.taroot.basma.ALERT";
    static final String ACT_WATCH = "com.taroot.basma.WATCH";
    private static final int[] ALERT_IDS = {1, 2, 3};
    private static final int[] WATCH_IDS = {4, 5, 6};

    /** {فتح التواجد، إغلاق التواجد، الانصراف} أو null إن لم يُعرف الحضور.
     *  عند الوقت التقديري (lo..hi) يُؤخذ الأحوط: البدايات من hi والنهايات من lo. */
    static double[] plan(JSONObject cfg, JSONObject d) {
        double lo, hi;
        if (d.has("in")) {
            lo = hi = d.optDouble("in");
        } else if (d.has("lo")) {
            lo = d.optDouble("lo");
            hi = d.optDouble("hi", lo);
        } else {
            return null;
        }
        double earliest = cfg.optDouble("earliest", 430);
        double work = cfg.optDouble("workMin", 360);
        double latest = cfg.optDouble("latestOut", 845);
        double delay = cfg.optDouble("presDelay", 120);
        double len = cfg.optDouble("presLen", 60);
        double ps = hi + delay;
        double pe = lo + delay + len;
        double out = Math.min(Math.max(hi, earliest) + work, latest);
        return new double[] {ps, pe, out};
    }

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (int id : ALERT_IDS) am.cancel(pi(c, ACT_ALERT, id, null, 0, 0));
        for (int id : WATCH_IDS) am.cancel(pi(c, ACT_WATCH, id, null, 0, 0));

        JSONObject cfg = Store.cfg(c);
        JSONObject d = Store.day(c, Store.today());
        double leadSec = cfg.optDouble("lead", 30);
        double lastCall = cfg.optDouble("lastCall", 5);
        double latest = cfg.optDouble("latestOut", 845);
        long now = System.currentTimeMillis();
        double[] p = plan(cfg, d);

        if (p != null && Store.workday(0)) {
            if (!d.has("pres") && !d.optBoolean("exempt")) {
                alert(c, am, 1, "pres", Store.msOf(p[0]), Store.msOf(p[0]) - (long) (leadSec * 1000), now);
                alert(c, am, 2, "presLast", Store.msOf(p[1]), Store.msOf(p[1] - lastCall), now);
                watch(c, am, 4, Store.msOf(p[0] - 5), Store.msOf(p[1] + 5), now);
            }
            if (!d.has("out")) {
                alert(c, am, 3, "out", Store.msOf(p[2]), Store.msOf(p[2]) - (long) (leadSec * 1000), now);
                watch(c, am, 5, Store.msOf(p[2] - 5), Store.msOf(latest + 45), now);
            }
        }

        // نافذة الصباح: اليوم إن لم يُسجَّل الحضور ولم تنتهِ، وإلا أول يوم عمل قادم.
        double mFrom = cfg.optDouble("earliest", 430) - 70;
        double mTo = cfg.optDouble("graceEnd", 475) + 60;
        int add = 0;
        if (p != null || !Store.workday(0) || now > Store.midnight(0) + (long) (mTo * 60000)) {
            add = 1;
            while (!Store.workday(add) && add < 7) add++;
        }
        long from = Store.midnight(add) + (long) (mFrom * 60000);
        long to = Store.midnight(add) + (long) (mTo * 60000);
        watch(c, am, 6, from, to, now);
    }

    private static PendingIntent pi(Context c, String action, int id, String kind, long target, long until) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action);
        if (kind != null) i.putExtra("kind", kind);
        i.putExtra("target", target);
        i.putExtra("until", until);
        return PendingIntent.getBroadcast(c, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean canExact(AlarmManager am) {
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    private static void alert(Context c, AlarmManager am, int id, String kind, long target, long when, long now) {
        if (target <= now) return;
        if (when < now) when = now + 1000;
        PendingIntent p = pi(c, ACT_ALERT, id, kind, target, 0);
        try {
            if (canExact(am)) {
                PendingIntent show = PendingIntent.getActivity(c, 90,
                        new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), p);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, p);
        }
    }

    private static void watch(Context c, AlarmManager am, int id, long from, long to, long now) {
        if (to <= now) return;
        if (from <= now) {
            WatchService.start(c, to);
            return;
        }
        PendingIntent p = pi(c, ACT_WATCH, id, null, 0, to);
        try {
            if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, from, p);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, from, p);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, from, p);
        }
    }
}
