package com.taroot.basma;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** حفظ الإعدادات وسجل اليوم، وأدوات الوقت. الأوقات بالدقائق منذ منتصف الليل (بكسور للثواني). */
final class Store {
    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("basma", Context.MODE_PRIVATE);
    }

    static JSONObject cfg(Context c) {
        try {
            return new JSONObject(prefs(c).getString("cfg", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void putCfg(Context c, String json) {
        prefs(c).edit().putString("cfg", json).apply();
    }

    static JSONObject day(Context c, String date) {
        try {
            return new JSONObject(prefs(c).getString("day_" + date, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void putDay(Context c, String date, JSONObject o) {
        prefs(c).edit().putString("day_" + date, o.toString()).commit();
    }

    static String dateKey(Calendar k) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH));
    }

    static String today() {
        return dateKey(Calendar.getInstance());
    }

    /** منتصف ليل اليوم بعد إضافة أيام. */
    static long midnight(int addDays) {
        Calendar k = Calendar.getInstance();
        k.add(Calendar.DAY_OF_MONTH, addDays);
        k.set(Calendar.HOUR_OF_DAY, 0);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    static double minOf(long ms) {
        return (ms - midnight(0)) / 60000.0;
    }

    static long msOf(double min) {
        return midnight(0) + Math.round(min * 60000.0);
    }

    /** الأحد إلى الخميس. */
    static boolean workday(int addDays) {
        Calendar k = Calendar.getInstance();
        k.add(Calendar.DAY_OF_MONTH, addDays);
        int w = k.get(Calendar.DAY_OF_WEEK);
        return w != Calendar.FRIDAY && w != Calendar.SATURDAY;
    }

    /** وقت بصيغة 7:43:55 ص */
    static String fmt(double min) {
        long s = Math.round(min * 60.0);
        long h = (s / 3600) % 24, m = (s / 60) % 60, sec = s % 60;
        String ap = h < 12 ? "ص" : "م";
        long h12 = h % 12 == 0 ? 12 : h % 12;
        if (sec == 0) return String.format(Locale.US, "%d:%02d %s", h12, m, ap);
        return String.format(Locale.US, "%d:%02d:%02d %s", h12, m, sec, ap);
    }
}
