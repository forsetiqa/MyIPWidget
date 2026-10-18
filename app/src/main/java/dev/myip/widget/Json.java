package dev.myip.widget;

import org.json.JSONObject;

/** Безопасное чтение полей JSON: null и отсутствующие поля превращаются в "" / NaN. */
final class Json {
    private Json() {}

    static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        String s = o.optString(key, "");
        return s == null ? "" : s.trim();
    }

    static double num(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return Double.NaN;
        return o.optDouble(key, Double.NaN);
    }
}
