package com.taroot.basma;

import android.app.Notification;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * يعمل في نوافذ البصمة فقط: يرصد فتح تطبيق «وزارة التربية - الكويت» وإغلاقه.
 * صباحًا يسجّل وقت الحضور تقديريًا بين لحظة الفتح ولحظة الخروج، ويحسب النوافذ بالأحوط.
 * في نافذتي التواجد والانصراف يسأل: هل بصمت؟
 */
public class WatchService extends Service {
    private static final long TICK = 3000;
    private static final long CLOSE_GRACE = 6000; // تنقّل داخل التطبيق أو كاميرا الصورة الذاتية
    private static final int EV_RESUMED = 1, EV_PAUSED = 2, EV_STOPPED = 23;

    private Handler handler;
    private long until;
    private long lastEventTs;
    private long openTs;
    private long pendingCloseTs;
    private boolean open;

    static void start(Context c, long until) {
        if (until <= System.currentTimeMillis()) return;
        Intent i = new Intent(c, WatchService.class).putExtra("until", until);
        try {
            c.startForegroundService(i);
        } catch (Exception ignored) {
            // قد يمنع النظام البدء من الخلفية؛ تبقى المنبهات تعمل.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long u = intent != null ? intent.getLongExtra("until", 0) : 0;
        until = Math.max(until, u);
        startFg();
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            lastEventTs = System.currentTimeMillis() - 90_000;
            handler.post(tick);
        }
        return START_NOT_STICKY;
    }

    private void startFg() {
        Notifier.channels(this);
        Notification n = new Notification.Builder(this, Notifier.CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFF2B632)
                .setContentTitle("بصمتي المرنة تترقّب بصمتك")
                .setContentText("سأسجّل الوقت حين تفتح تطبيق الوزارة")
                .setOngoing(true)
                .setContentIntent(Notifier.openApp(this))
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(Notifier.ID_WATCH, n);
            }
        } catch (Exception e) {
            stopSelf();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            try {
                poll(now);
            } catch (Exception ignored) { }
            if (pendingCloseTs > 0 && now - pendingCloseTs > CLOSE_GRACE) {
                long closeTs = pendingCloseTs;
                pendingCloseTs = 0;
                open = false;
                onSession(openTs, closeTs);
            }
            if (now > until && !open && pendingCloseTs == 0) {
                finish();
                return;
            }
            handler.postDelayed(this, TICK);
        }
    };

    private void poll(long now) {
        UsageStatsManager um = getSystemService(UsageStatsManager.class);
        if (um == null) return;
        UsageEvents ev = um.queryEvents(lastEventTs - 15_000, now);
        UsageEvents.Event e = new UsageEvents.Event();
        long newest = lastEventTs;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long ts = e.getTimeStamp();
            if (ts <= lastEventTs) continue;
            if (ts > newest) newest = ts;
            if (!Notifier.MOE.equals(e.getPackageName())) continue;
            int t = e.getEventType();
            if (t == EV_RESUMED) {
                if (pendingCloseTs > 0) {
                    pendingCloseTs = 0; // ما زال داخل تطبيق الوزارة
                } else if (!open) {
                    open = true;
                    openTs = ts;
                }
            } else if ((t == EV_PAUSED || t == EV_STOPPED) && open && pendingCloseTs == 0) {
                pendingCloseTs = ts;
            }
        }
        lastEventTs = newest;
    }

    /** جلسة كاملة في تطبيق الوزارة من الفتح إلى الخروج. */
    private void onSession(long openMs, long closeMs) {
        String date = Store.today();
        JSONObject cfg = Store.cfg(this);
        JSONObject d = Store.day(this, date);
        double o = Store.minOf(openMs), cl = Store.minOf(closeMs);
        try {
            double[] p = Scheduler.plan(cfg, d);
            if (p == null) {
                // الصباح: الحضور تقديري بين الفتح والخروج.
                d.put("lo", o);
                d.put("hi", cl);
                Store.putDay(this, date, d);
                Scheduler.schedule(this);
                Notifier.detect(this, "سجّلتُ وقت حضورك تقديريًا",
                        "بين " + Store.fmt(o) + " و" + Store.fmt(cl)
                                + ". ضبطتُ تنبيهات التواجد والانصراف بالأحوط. ولاعتماد الوقت الدقيق اقرأ لقطة شاشة «الحضور والانصراف» في التطبيق.",
                        null, null);
            } else if (d.has("lo") && !d.has("in") && !d.has("pres") && o < p[0] - 30) {
                // جلسة صباحية ثانية قريبة (مثل إعادة المحاولة): نمدّ نهاية التقدير.
                d.put("hi", Math.max(d.optDouble("hi", cl), cl));
                Store.putDay(this, date, d);
                Scheduler.schedule(this);
            } else if (!d.has("pres") && cl >= p[0] - 2 && o <= p[1] + 2) {
                d.put("last_close_pres", cl);
                Store.putDay(this, date, d);
                Notifier.detect(this, "هل بصمتَ التواجد؟",
                        "خرجتَ من تطبيق الوزارة " + Store.fmt(cl) + "، ونافذة التواجد حتى " + Store.fmt(p[1]) + ".",
                        "نعم، بصمتُ التواجد", AlarmReceiver.ACT_PRES);
            } else if (!d.has("out") && cl >= p[2] - 2) {
                d.put("last_close_out", cl);
                Store.putDay(this, date, d);
                Notifier.detect(this, "هل بصمتَ الانصراف؟",
                        "خرجتَ من تطبيق الوزارة " + Store.fmt(cl) + "، والانصراف يصح من " + Store.fmt(p[2]) + ".",
                        "نعم، بصمتُ الانصراف", AlarmReceiver.ACT_OUT);
            }
        } catch (JSONException ignored) { }
    }

    private void finish() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        handler = null;
        if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
