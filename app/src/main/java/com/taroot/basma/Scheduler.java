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
    private static final int[] ALERT_IDS = {1, 2, 3, 7, 8};
    /** نطاق الدوام: التطبيق يعمل وينبّه من 6:00 ص إلى 3:00 م فقط. */
    static final double DAY_FROM = 360, DAY_TO = 900;
    /** نظام التفعيل: يلغي قيد 6:00–15:00 للتجربة. */
    static boolean test(Context c) { return Store.cfg(c).optBoolean("testMode", false); }
    static double from(Context c) { return test(c) ? 0 : DAY_FROM; }
    static double to(Context c) { return test(c) ? 1439.9 : DAY_TO; }
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
        double work = cfg.optDouble("workMin", 370);
        double latest = cfg.optDouble("latestOut", 845);
        double delay = cfg.optDouble("presDelay", 120);
        double len = cfg.optDouble("presLen", 60);
        // الحضور قبل بداية السماح يُحتسب من بدايتها، وكل تأخير يؤخّر التواجد والانصراف.
        final double pad = 1; // البصمة تصح بعد مضيّ الدقيقة كاملة
        double ps = Math.max(hi, earliest) + delay + pad;
        double pe = Math.max(lo, earliest) + delay + len;
        double out = Math.min(Math.max(hi, earliest) + work, latest) + pad;
        String mode = d.optString("mode", "");
        if ("late".equals(mode)) {
            // تأخير: يُحسب الحضور عند نهاية فترة السماح، والانصراف بعد 6 ساعات منها.
            double a = cfg.optDouble("graceEnd", 475);
            // النشرة 40/2024: تواجد المتأخر من (بداية السماح + ساعتين) حتى ساعة بعدها، وانصرافه عند نهاية الدوام الرسمي.
            ps = Math.max(earliest + delay + pad, Math.ceil(hi));
            pe = earliest + delay + len;
            out = cfg.optDouble("offEnd", a + 360) + pad;
        } else if ("perm".equals(mode)) {
            // استئذان صباحي: الانصراف من (بداية احتساب الاستئذان + مدة العمل) حتى آخر الانصراف.
            out = Math.min(d.optDouble("permFrom", earliest) + work, latest) + pad;
            // التواجد: إن انتهى الاستئذان قبل النافذة فخلالها، وإن انتهى داخلها فبعد ساعة من الحضور.
            double ws = earliest + delay + pad, we = earliest + delay + len;
            if (hi < ws) { ps = ws; pe = we; } else { ps = Math.ceil(hi) + 60 + pad; pe = Math.ceil(hi) + 120; }
        }
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

        long dayFrom = Store.midnight(0) + (long) (from(c) * 60000), dayTo = Store.midnight(0) + (long) (to(c) * 60000);
        boolean workToday = (test(c) || Store.workday(0)) && now < dayTo;

        if (workToday) {
            if (p != null) {
                if (!d.has("pres") && !d.optBoolean("exempt")) {
                    alert(c, am, 1, "pres", Store.msOf(p[0]), Store.msOf(p[0]) - (long) (leadSec * 1000), now);
                    alert(c, am, 2, "presLast", Store.msOf(p[1]), Store.msOf(p[1] - lastCall), now);
                }
                if (!d.has("out")) {
                    alert(c, am, 3, "out", Store.msOf(p[2]), Store.msOf(p[2]) - (long) (leadSec * 1000), now);
                }
            } else {
                // لم يُسجَّل الحضور: تنبيه عند اقتراب وقته، وتنبيه قبل نهاية فترة السماح بخمس دقائق.
                double earliest = cfg.optDouble("earliest", 430), grace = cfg.optDouble("graceEnd", 475);
                alert(c, am, 7, "inSoon", Store.msOf(earliest), Store.msOf(earliest - 10), now);
                alert(c, am, 8, "inLast", Store.msOf(grace), Store.msOf(grace - 5), now);
            }
            // التطبيق يعمل في الخلفية طوال نطاق الدوام.
            if (now >= dayFrom) WatchService.start(c, dayTo);
            else startAt(c, am, 6, dayFrom, dayTo);
        } else {
            WatchService.stop(c);
            int add = 1;
            while (!Store.workday(add) && add < 7) add++;
            startAt(c, am, 6, Store.midnight(add) + (long) (DAY_FROM * 60000), Store.midnight(add) + (long) (DAY_TO * 60000));
        }
    }

    private static void startAt(Context c, AlarmManager am, int id, long from, long to) {
        PendingIntent p = pi(c, ACT_WATCH, id, null, 0, to);
        try {
            if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, from, p);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, from, p);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, from, p);
        }
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
        double t = Store.minOf(target);
        if (t < from(c) || t > to(c)) return;
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


}
