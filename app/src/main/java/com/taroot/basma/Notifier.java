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
    static final int ID_PRES = 101, ID_PRES_LAST = 102, ID_OUT = 103, ID_DETECT = 104, ID_WATCH = 7;
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
        if (kind.startsWith("pres")) b.addAction(act(c, "بصمتُ التواجد", action(c, AlarmReceiver.ACT_PRES, 71)));
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

    static void cancel(Context c, int... ids) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (int id : ids) nm.cancel(id);
    }
}
