package dev.myip.widget;

import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Определение публичного IP через несколько бесплатных сервисов по очереди. */
final class IpFetcher {
    static final String USER_AGENT = "MyIPWidget/1.0 (Android)";
    private static final int MAX_TIMEOUT_MS = 6000;
    private static final int MIN_TIMEOUT_MS = 1500;

    private IpFetcher() {}

    /** @param deadline момент в {@link SystemClock#elapsedRealtime()}, после которого не начинаем новые запросы */
    static IpInfo fetch(long deadline) throws IOException {
        IOException last = new IOException("no providers tried");
        for (int p = 0; p < 3; p++) {
            int timeout = timeoutUntil(deadline);
            if (timeout < MIN_TIMEOUT_MS) break;
            try {
                IpInfo info;
                switch (p) {
                    case 0:
                        info = fromIpWhoIs(new JSONObject(getString("https://ipwho.is/", timeout)));
                        break;
                    case 1:
                        info = fromIpapiCo(new JSONObject(getString("https://ipapi.co/json/", timeout)));
                        break;
                    default:
                        info = fromIpinfo(new JSONObject(getString("https://ipinfo.io/json", timeout)));
                        break;
                }
                if (info.ip.isEmpty()) throw new IOException("empty ip from " + info.source);
                if (info.country.isEmpty() && info.countryCode.length() == 2) {
                    info.country = new Locale("", info.countryCode).getDisplayCountry(Locale.ENGLISH);
                }
                return info;
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        throw last;
    }

    static int timeoutUntil(long deadline) {
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0) return 0;
        return (int) Math.min(MAX_TIMEOUT_MS, left);
    }

    private static IpInfo fromIpWhoIs(JSONObject o) throws IOException {
        if (o.has("success") && !o.optBoolean("success", false)) {
            throw new IOException("ipwho.is: " + Json.str(o, "message"));
        }
        IpInfo i = new IpInfo();
        i.ip = Json.str(o, "ip");
        i.country = Json.str(o, "country");
        i.countryCode = Json.str(o, "country_code");
        i.region = Json.str(o, "region");
        i.city = Json.str(o, "city");
        i.lat = Json.num(o, "latitude");
        i.lon = Json.num(o, "longitude");
        JSONObject conn = o.optJSONObject("connection");
        if (conn != null) {
            i.isp = Json.str(conn, "isp");
            if (i.isp.isEmpty()) i.isp = Json.str(conn, "org");
        }
        JSONObject tz = o.optJSONObject("timezone");
        if (tz != null) i.timezone = Json.str(tz, "id");
        i.source = "ipwho.is";
        return i;
    }

    private static IpInfo fromIpapiCo(JSONObject o) throws IOException {
        if (o.optBoolean("error", false)) {
            throw new IOException("ipapi.co: " + Json.str(o, "reason"));
        }
        IpInfo i = new IpInfo();
        i.ip = Json.str(o, "ip");
        i.country = Json.str(o, "country_name");
        i.countryCode = Json.str(o, "country_code");
        i.region = Json.str(o, "region");
        i.city = Json.str(o, "city");
        i.lat = Json.num(o, "latitude");
        i.lon = Json.num(o, "longitude");
        i.isp = Json.str(o, "org");
        i.timezone = Json.str(o, "timezone");
        i.source = "ipapi.co";
        return i;
    }

    private static IpInfo fromIpinfo(JSONObject o) {
        IpInfo i = new IpInfo();
        i.ip = Json.str(o, "ip");
        i.countryCode = Json.str(o, "country");
        i.region = Json.str(o, "region");
        i.city = Json.str(o, "city");
        String loc = Json.str(o, "loc");
        int comma = loc.indexOf(',');
        if (comma > 0) {
            try {
                i.lat = Double.parseDouble(loc.substring(0, comma).trim());
                i.lon = Double.parseDouble(loc.substring(comma + 1).trim());
            } catch (NumberFormatException ignored) {
                i.lat = Double.NaN;
                i.lon = Double.NaN;
            }
        }
        // "AS12345 Provider" -> "Provider"
        i.isp = Json.str(o, "org").replaceFirst("^AS\\d+\\s+", "");
        i.timezone = Json.str(o, "timezone");
        i.source = "ipinfo.io";
        return i;
    }

    static String getString(String url, int timeoutMs) throws IOException {
        return new String(getBytes(url, timeoutMs), StandardCharsets.UTF_8);
    }

    static byte[] getBytes(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            // Без keep-alive: после смены сети/VPN не переиспользуем старое соединение.
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
