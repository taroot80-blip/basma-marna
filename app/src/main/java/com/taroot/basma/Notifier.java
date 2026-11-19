package com.taroot.basma;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import java.util.Arrays;

/** قنوات الإشعارات والتنبيه المميّز قبل كل بصمة. */
final class Notifier {
    private Notifier() {}

    static final String CH_ALERT = "basma_alert";
    static final String CH_DETECT = "basma_detect";
    static final String CH_WATCH = "basma_watch";
    static final int ID_PRES = 101, ID_PRES_LAST = 102, ID_OUT = 103, ID_DETECT = 104, ID_WATCH = 7, ID_IN_SOON = 105, ID_IN_LAST = 106;
    static final String MOE = "com.moe.moemobileapp";

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        Uri snd = Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.chime);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        NotificationChannel a = new NotificationChannel(CH_ALERT, "تنبيهات البصمة", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("تنبيه مميّز قبل 30 ثانية من وقت كل بصمة");
        a.setSound(snd, aa);
        a.enableVibration(true);
        a.setVibrationPattern(new long[] {0, 350, 120, 350, 120, 700});
        a.enableLights(true);
        a.setLightColor(0xFFF2B632);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel d = new NotificationChannel(CH_DETECT, "رصد تطبيق الوزارة", NotificationManager.IMPORTANCE_HIGH);
        d.setDescription("تسجيل وقت البصمة حين تفتح تطبيق الوزارة");

        NotificationChannel w = new NotificationChannel(CH_WATCH, "الترقّب", NotificationManager.IMPORTANCE_MIN);
        w.setDescription("يظهر أثناء نوافذ البصمة فقط");
        w.setShowBadge(false);
        nm.createNotificationChannels(Arrays.asList(a, d, w));
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 80, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent openMoe(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(MOE);
        if (i == null) return openApp(c);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 81, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent action(Context c, String act, int code) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(act);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Action act(Context c, String title, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), title, pi).build();
    }

    /** التنبيه المميّز مع عدّ تنازلي حتى وقت البصمة. */
    static void alert(Context c, String kind, long target) {
        channels(c);
        String title, body;
        int id;
        double t = Store.minOf(target);
        switch (kind) {
            case "inSoon":
                id = ID_IN_SOON;
                title = "حان وقت بصمة الحضور";
                body = "تبدأ فترة الحضور " + Store.fmt(t) + ". ابصم من تطبيق الوزارة، ثم سجّل وقتك.";
                break;
            case "inLast":
                id = ID_IN_LAST;
                title = "بقيت 5 دقائق على نهاية فترة السماح";
                body = "تنتهي فترة السماح " + Store.fmt(t) + "، وبعدها يُحتسب تأخيرًا.";
                break;
            case "presLast":
                id = ID_PRES_LAST;
                title = "تنبيه أخير: بصمة التواجد تُغلق";
                body = "تُغلق نافذة التواجد " + Store.fmt(t) + ". ابصم الآن.";
                break;
            case "out":
                id = ID_OUT;
                title = "اقترب وقت بصمة الانصراف";
                body = "تصح بصمة الانصراف من " + Store.fmt(t) + ". الانصراف قبلها يُحتسب نقصًا.";
                break;
            default:
                id = ID_PRES;
                title = "اقترب وقت بصمة التواجد";
                body = "تُفتح بصمة التواجد " + Store.fmt(t) + ". توجّه إلى البصمة.";
        }
        Notification.Builder b = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFF2B632)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM)
                .setWhen(target)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setAutoCancel(true)
                .setTimeoutAfter(20 * 60 * 1000L)
                .setContentIntent(openApp(c))
                .addAction(act(c, "افتح تطبيق الوزارة", openMoe(c)));
        if (kind.startsWith("in")) b.addAction(act(c, "بصمتُ الحضور الآن", action(c, AlarmReceiver.ACT_IN, 74)));
        else if (kind.startsWith("pres")) b.addAction(act(c, "بصمتُ التواجد", action(c, AlarmReceiver.ACT_PRES, 71)));
        else b.addAction(act(c, "بصمتُ الانصراف", action(c, AlarmReceiver.ACT_OUT, 72)));
        c.getSystemService(NotificationManager.class).notify(id, b.build());
    }

    /** سؤال بعد إغلاق تطبيق الوزارة. */
    static void detect(Context c, String title, String body, String confirmLabel, String confirmAct) {
        channels(c);
        Notification.Builder b = new Notification.Builder(c, CH_DETECT)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFF2B632)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openApp(c));
        if (confirmAct != null) b.addAction(act(c, confirmLabel, action(c, confirmAct, 73)));
        c.getSystemService(NotificationManager.class).notify(ID_DETECT, b.build());
    }

    /** الإشعار الدائم أثناء نطاق الدوام: البصمة القادمة مع عدّ تنازلي. */
    static Notification duty(Context c) {
        JSONObjectHolder s = status(c);
        Notification.Builder b = new Notification.Builder(c, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFF2B632)
                .setContentTitle(s.title)
                .setContentText(s.text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(c));
        if (s.target > 0) b.setWhen(s.target).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        else b.setShowWhen(false);
        return b.build();
    }

    static final class JSONObjectHolder {
        String title, text;
        long target;
    }

    static JSONObjectHolder status(Context c) {
        JSONObjectHolder s = new JSONObjectHolder();
        org.json.JSONObject cfg = Store.cfg(c), d = Store.day(c, Store.today());
        double[] p = Scheduler.plan(cfg, d);
        double now = Store.minOf(System.currentTimeMillis());
        s.title = "بصمتي المرنة تعمل حتى 3:00 م";
        if (p == null) {
            double grace = cfg.optDouble("graceEnd", 475);
            s.text = now <= grace ? "البصمة القادمة: الحضور حتى " + Store.fmt(grace) : "لم يُسجَّل الحضور بعد";
            s.target = now <= grace ? Store.msOf(grace) : 0;
        } else if (!d.has("pres") && !d.optBoolean("exempt") && now <= p[1]) {
            boolean open = now >= p[0];
            s.text = open ? "بصمة التواجد مفتوحة الآن حتى " + Store.fmt(p[1]) : "البصمة القادمة: التواجد من " + Store.fmt(p[0]);
            s.target = Store.msOf(open ? p[1] : p[0]);
        } else if (!d.has("out")) {
            boolean open = now >= p[2];
            s.text = open ? "بصمة الانصراف صحيحة الآن" : "البصمة القادمة: الانصراف من " + Store.fmt(p[2]);
            s.target = open ? 0 : Store.msOf(p[2]);
        } else {
            s.text = "أنهيتَ بصماتك اليوم ✓";
            s.target = 0;
        }
        return s;
    }

    static void cancel(Context c, int... ids) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (int id : ids) nm.cancel(id);
    }
}
