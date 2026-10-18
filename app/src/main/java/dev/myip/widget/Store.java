package dev.myip.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Последний результат, флаг ошибки и названия IP в SharedPreferences. */
final class Store {
    private static final String PREFS = "ipwidget";
    private static final String KEY_INFO = "info";
    private static final String KEY_ERROR = "error";
    private static final String KEY_NAMES = "names";

    private static final String DEFAULT_NAMES =
            "203.0.113.42 = Home\n203.0.113.43 = Work\n";

    private static final Pattern IP_TOKEN = Pattern.compile(
            "([0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7})");

    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static IpInfo load(Context c) {
        String s = prefs(c).getString(KEY_INFO, null);
        if (s == null) return null;
        try {
            return IpInfo.fromJson(new JSONObject(s));
        } catch (JSONException e) {
            return null;
        }
    }

    static void save(Context c, IpInfo info) {
        try {
            prefs(c).edit().putString(KEY_INFO, info.toJson().toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static boolean lastFailed(Context c) {
        return prefs(c).getBoolean(KEY_ERROR, false);
    }

    static void setLastFailed(Context c, boolean failed) {
        prefs(c).edit().putBoolean(KEY_ERROR, failed).apply();
    }

    static String names(Context c) {
        return prefs(c).getString(KEY_NAMES, DEFAULT_NAMES);
    }

    static void setNames(Context c, String text) {
        prefs(c).edit().putString(KEY_NAMES, text == null ? "" : text).apply();
    }

    /**
     * Название для IP. Понимает строки вида
     * "203.0.113.42 = Home", "Home - 203.0.113.42", "203.0.113.42 Home".
     */
    static String nameFor(Context c, String ip) {
        if (ip == null || ip.isEmpty()) return null;
        String text = names(c);
        if (text == null) return null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = IP_TOKEN.matcher(line);
            while (m.find()) {
                if (!m.group(1).equalsIgnoreCase(ip)) continue;
                String rest = (line.substring(0, m.start()) + " " + line.substring(m.end())).trim();
                rest = rest.replaceAll("^[\\s=:\\-–—]+", "").replaceAll("[\\s=:\\-–—]+$", "");
                if (!rest.isEmpty()) return rest;
            }
        }
        return null;
    }
}
