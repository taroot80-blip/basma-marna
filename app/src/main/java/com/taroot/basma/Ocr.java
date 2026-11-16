package com.taroot.basma;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * يقرأ لقطة شاشة «الحضور والانصراف» على الهاتف نفسه دون إنترنت،
 * ويستخرج أزواج (التاريخ، الوقت) من كل صف بمطابقة موضعها العمودي.
 */
final class Ocr {
    private Ocr() {}

    interface Done {
        void done(String json);
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[:：]\\s*(\\d{2})");
    private static final Pattern DATE = Pattern.compile("(20\\d{2})\\s*[-/.]\\s*(\\d{1,2})\\s*[-/.]\\s*(\\d{1,2})");

    private static final class Item {
        final String s;
        final float cy, h;
        Item(String s, Rect r) {
            this.s = s;
            this.cy = r == null ? 0 : r.exactCenterY();
            this.h = r == null ? 0 : r.height();
        }
    }

    static void read(Context c, Uri uri, final Done cb) {
        final InputImage img;
        try {
            img = InputImage.fromFilePath(c, uri);
        } catch (Exception e) {
            cb.done("{\"error\":\"image\"}");
            return;
        }
        final TextRecognizer r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        r.process(img)
                .addOnSuccessListener(text -> {
                    cb.done(parse(text));
                    r.close();
                })
                .addOnFailureListener(e -> {
                    cb.done("{\"error\":\"ocr\"}");
                    r.close();
                });
    }

    private static String parse(Text text) {
        List<Item> lines = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks()) {
            for (Text.Line l : b.getLines()) lines.add(new Item(l.getText(), l.getBoundingBox()));
        }
        List<Item> dates = new ArrayList<>();
        List<String> dateVals = new ArrayList<>();
        for (Item it : lines) {
            Matcher m = DATE.matcher(it.s);
            while (m.find()) {
                dates.add(it);
                dateVals.add(String.format(Locale.US, "%s-%02d-%02d", m.group(1),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
            }
        }
        JSONArray items = new JSONArray();
        try {
            for (Item it : lines) {
                Matcher m = TIME.matcher(it.s);
                while (m.find()) {
                    int hh = Integer.parseInt(m.group(1)), mm = Integer.parseInt(m.group(2)), ss = Integer.parseInt(m.group(3));
                    if (hh > 23 || mm > 59 || ss > 59) continue;
                    String date = null;
                    float best = Float.MAX_VALUE;
                    for (int i = 0; i < dates.size(); i++) {
                        Item d = dates.get(i);
                        float dy = Math.abs(d.cy - it.cy);
                        float tol = Math.max(Math.max(d.h, it.h) * 0.8f, 12f);
                        if (dy <= tol && dy < best) {
                            best = dy;
                            date = dateVals.get(i);
                        }
                    }
                    if (date == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("date", date);
                    o.put("time", String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss));
                    items.put(o);
                }
            }
            JSONObject res = new JSONObject();
            res.put("items", items);
            return res.toString();
        } catch (JSONException e) {
            return "{\"error\":\"json\"}";
        }
    }
}
