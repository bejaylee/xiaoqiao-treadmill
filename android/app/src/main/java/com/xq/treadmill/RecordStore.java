package com.xq.treadmill;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 运动记录：存本地偏好里（JSON 数组），最多保留 200 条 */
public class RecordStore {

    public static class Rec {
        public long ts;
        public String name = "";
        public int seconds;
        public double km;
        public int kcal;
        public double avgSpeed;
        public int segments;

        public String duration() {
            return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
        }

        public String dateText() {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA);
            return f.format(new java.util.Date(ts));
        }
    }

    private static final String PREFS = "workout";
    private static final String KEY = "records";
    private static final int MAX = 200;

    public static List<Rec> all(Context c) {
        List<Rec> list = new ArrayList<Rec>();
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Rec r = new Rec();
                r.ts = o.optLong("ts");
                r.name = o.optString("name");
                r.seconds = o.optInt("seconds");
                r.km = o.optDouble("km");
                r.kcal = o.optInt("kcal");
                r.avgSpeed = o.optDouble("avg");
                r.segments = o.optInt("segs");
                list.add(r);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void add(Context c, Rec r) {
        List<Rec> list = all(c);
        list.add(0, r);
        while (list.size() > MAX) list.remove(list.size() - 1);
        JSONArray arr = new JSONArray();
        try {
            for (Rec x : list) {
                JSONObject o = new JSONObject();
                o.put("ts", x.ts);
                o.put("name", x.name);
                o.put("seconds", x.seconds);
                o.put("km", x.km);
                o.put("kcal", x.kcal);
                o.put("avg", x.avgSpeed);
                o.put("segs", x.segments);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, arr.toString()).apply();
    }

    public static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    public static int totalSeconds(List<Rec> list) {
        int n = 0;
        for (Rec r : list) n += r.seconds;
        return n;
    }

    public static double totalKm(List<Rec> list) {
        double n = 0;
        for (Rec r : list) n += r.km;
        return n;
    }

    public static int totalKcal(List<Rec> list) {
        int n = 0;
        for (Rec r : list) n += r.kcal;
        return n;
    }
}
