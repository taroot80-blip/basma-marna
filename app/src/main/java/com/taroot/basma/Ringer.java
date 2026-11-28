package com.taroot.basma;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * جرس الهاتف القوي: نغمة منبّه الهاتف بأعلى صوت مع اهتزاز قوي،
 * أقوى من صوت الإشعارات المنخفض عادة. يتوقف وحده بعد مدة قصيرة،
 * أو حين يفتح الموظف التطبيق أو يضغط زر الإشعار.
 */
final class Ringer {
    private static final long RING_MS = 15000;
    private static MediaPlayer player;
    private static int savedVol = -1;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable STOP = () -> stop(null);

    private Ringer() { }

    static boolean enabled(Context c) {
        return Store.cfg(c).optBoolean("loudBell", true);
    }

    /** جرس التنبيه الحقيقي: يرن بلا توقف حتى تُسجَّل البصمة، أو حتى حدّ أقصى للسلامة. */
    static final long ALERT_MAX_MS = 10 * 60 * 1000L;
    private static final Runnable GUARD = new Runnable() {
        @Override public void run() {
            if (player == null || appCtx == null) return;
            try {
                AudioManager am = appCtx.getSystemService(AudioManager.class);
                int mx = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                if (am.getStreamVolume(AudioManager.STREAM_ALARM) < mx) am.setStreamVolume(AudioManager.STREAM_ALARM, mx, 0);
                if (player != null && !player.isPlaying()) player.start();
            } catch (Exception ignored) { }
            H.postDelayed(this, 700);
        }
    };
    private static Context appCtx;

    static synchronized void ringAlert(Context c) {
        ring(c, ALERT_MAX_MS);
        appCtx = c.getApplicationContext();
        H.removeCallbacks(GUARD);
        H.postDelayed(GUARD, 700);
    }

    static synchronized void ring(Context c) { ring(c, RING_MS); }

    static synchronized void ring(Context c, long ms) {
        Context app = c.getApplicationContext();
        stop(app);
        AudioManager am = app.getSystemService(AudioManager.class);
        try {
            if (am != null) {
                savedVol = am.getStreamVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            }
        } catch (Exception ignored) { savedVol = -1; }
        Uri u = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM);
        if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (u == null) u = Uri.parse("android.resource://" + app.getPackageName() + "/" + R.raw.chime);
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(app, u);
            mp.setLooping(true);
            mp.setVolume(1f, 1f);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            try {
                MediaPlayer mp = MediaPlayer.create(app, R.raw.chime);
                if (mp != null) { mp.setLooping(true); mp.start(); player = mp; }
            } catch (Exception ignored) { }
        }
        try {
            Vibrator v = app.getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator())
                v.vibrate(VibrationEffect.createWaveform(new long[] {0, 800, 300, 800, 300, 800, 600}, 0));
        } catch (Exception ignored) { }
        H.removeCallbacks(STOP);
        H.postDelayed(() -> stop(app), ms);
    }

    static synchronized void stop(Context c) {
        H.removeCallbacks(STOP);
        H.removeCallbacks(GUARD);
        appCtx = null;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (c != null) {
            try {
                Vibrator v = c.getSystemService(Vibrator.class);
                if (v != null) v.cancel();
            } catch (Exception ignored) { }
            if (savedVol >= 0) {
                try {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    if (am != null) am.setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0);
                } catch (Exception ignored) { }
                savedVol = -1;
            }
        }
    }
}
