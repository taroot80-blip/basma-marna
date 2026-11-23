package com.taroot.basma;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** يعرض واجهة بصمتي المرنة من assets/www، ويصلها بالمنبهات والمراقبة وقراءة لقطة الشاشة. */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int BG = 0xFF0B0805;
    private static final int REQ_IMG = 11, REQ_VOICE = 12, REQ_NOTIF = 13, REQ_FACE = 14;

    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Notifier.channels(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100); // لا يكبّر خط النظام تصميم الواجهة فيخرجها عن حدود الشاشة
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new AssetClient());
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                faceCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "اختر صورتك"), REQ_FACE);
                } catch (ActivityNotFoundException e) {
                    faceCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    Insets i = in.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                }
            });
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() { handleBack(); }
                    });
        }
        if (state != null) web.restoreState(state);
        else web.loadUrl("https://" + HOST + "/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        Scheduler.schedule(this);
    }

    private ValueCallback<Uri[]> faceCallback;

    @Override
    protected void onResume() {
        super.onResume();
        Ringer.stop(this);
        if (web != null) web.evaluateJavascript("window.onNativeResume&&window.onNativeResume()", null);
    }

    private void js(final String fn, final String arg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window." + fn + "&&window." + fn + "(" + JSONObject.quote(arg) + ")", null);
            }
        });
    }

    private void handleBack() {
        web.evaluateJavascript("window.basmaBack?window.basmaBack():0", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String r) {
                if (!"1".equals(r)) finish();
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { handleBack(); }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FACE) {
            if (faceCallback != null) {
                Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
                faceCallback.onReceiveValue(u != null ? new Uri[] {u} : null);
                faceCallback = null;
            }
        } else if (req == REQ_IMG) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            Ocr.read(this, data.getData(), new Ocr.Done() {
                @Override
                public void done(String json) { js("onOcr", json); }
            });
        } else if (req == REQ_VOICE) {
            JSONArray a = new JSONArray();
            if (res == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null) for (String t : r) a.put(t);
            }
            js("onVoice", a.toString());
        }
    }

    private boolean usageAllowed() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        } else {
            mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void open(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(d); } catch (Exception ignored) { }
        }
    }

    /** الواجهة التي تستدعيها صفحة الويب باسم Native. */
    private final class Bridge {
        @JavascriptInterface
        public String info() {
            JSONObject o = new JSONObject();
            try {
                o.put("notif", Build.VERSION.SDK_INT < 33
                        || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
                o.put("exact", Scheduler.canExact(getSystemService(AlarmManager.class)));
                o.put("usage", usageAllowed());
                o.put("moe", getPackageManager().getLaunchIntentForPackage(Notifier.MOE) != null);
                PowerManager pm = getSystemService(PowerManager.class);
                o.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
            } catch (JSONException ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public void setConfig(String json) {
            Store.putCfg(MainActivity.this, json);
            Scheduler.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public String getDay(String date) {
            return Store.day(MainActivity.this, date).toString();
        }

        @JavascriptInterface
        public void setDay(String date, String json) {
            try {
                JSONObject in = new JSONObject(json);
                JSONObject d = in.optBoolean("reset") ? new JSONObject() : Store.day(MainActivity.this, date);
                if (in.has("in")) {
                    d.put("in", in.getDouble("in"));
                    d.remove("lo");
                    d.remove("hi");
                } else if (in.has("lo")) {
                    d.put("lo", in.getDouble("lo"));
                    d.put("hi", in.optDouble("hi", in.getDouble("lo")));
                    d.remove("in");
                }
                if (in.has("pres")) d.put("pres", in.getDouble("pres"));
                if (in.has("out")) d.put("out", in.getDouble("out"));
                d.put("exempt", in.optBoolean("exempt"));
                if (in.has("mode")) d.put("mode", in.getString("mode"));
                else if (in.optBoolean("reset")) d.remove("mode");
                if (in.has("permFrom")) d.put("permFrom", in.getDouble("permFrom"));
                else if (in.optBoolean("reset")) d.remove("permFrom");
                Store.putDay(MainActivity.this, date, d);
                Scheduler.schedule(MainActivity.this);
            } catch (JSONException ignored) { }
        }

        @JavascriptInterface
        public void pickImage() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                    try {
                        startActivityForResult(Intent.createChooser(i, "اختر لقطة «الحضور والانصراف»"), REQ_IMG);
                    } catch (ActivityNotFoundException e) {
                        js("onOcr", "{\"error\":\"picker\"}");
                    }
                }
            });
        }

        @JavascriptInterface
        public void voice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-KW")
                            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "قل وقت بصمتك، مثلًا: سبعة وعشرين");
                    try {
                        startActivityForResult(i, REQ_VOICE);
                    } catch (ActivityNotFoundException e) {
                        js("onVoice", "[]");
                    }
                }
            });
        }

        @JavascriptInterface
        public void testBell() { Ringer.ring(MainActivity.this); }

        @JavascriptInterface
        public void stopBell() { Ringer.stop(MainActivity.this); }

        @JavascriptInterface
        public void openMoe() {
            Intent i = getPackageManager().getLaunchIntentForPackage(Notifier.MOE);
            if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Notifier.MOE));
            open(i);
        }

        @JavascriptInterface
        public void openUsage() {
            open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }

        @JavascriptInterface
        public void openExact() {
            if (Build.VERSION.SDK_INT >= 31) {
                open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
            }
        }

        @JavascriptInterface
        public void requestNotif() {
            if (Build.VERSION.SDK_INT >= 33) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                    }
                });
            } else {
                open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        }

        @JavascriptInterface
        public void openBattery() {
            String[][] targets = {
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
            };
            for (String[] t : targets) {
                Intent i = new Intent().setComponent(new ComponentName(t[0], t[1])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(getPackageManager()) != null) {
                    try {
                        startActivity(i);
                        return;
                    } catch (Exception ignored) { }
                }
            }
            open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".jpg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            String m = mime(path);
            boolean text = m.startsWith("text/") || m.equals("application/json") || m.equals("image/svg+xml");
            try {
                InputStream in = getAssets().open("www" + path);
                return new WebResourceResponse(m, text ? "utf-8" : null, 200, "OK",
                        Collections.<String, String>emptyMap(), in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                        Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (ActivityNotFoundException e) { /* لا يوجد متصفح */ }
            return true;
        }
    }
}
