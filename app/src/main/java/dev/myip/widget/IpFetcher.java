package dev.myip.widget;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Определение публичного IP выбранным сервисом и местоположения этого IP.
 * Местоположение запрашивается отдельно по самому адресу, поэтому от маршрута не зависит.
 */
final class IpFetcher {
    static final String USER_AGENT = "MyIPWidget/1.1 (+https://github.com/forsetiqa/MyIPWidget)";
    private static final int MAX_TIMEOUT_MS = 6000;
    private static final int MIN_TIMEOUT_MS = 1500;
    private static final long GEO_REUSE_MS = 12L * 60 * 60 * 1000;

    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9.])((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})(?![0-9.])");
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![0-9A-Fa-f:])((?:[0-9A-Fa-f]{1,4}:){2,7}(?::|[0-9A-Fa-f]{1,4})|(?:[0-9A-Fa-f]{1,4}:){1,6}:(?:[0-9A-Fa-f]{1,4}:?){0,5}[0-9A-Fa-f]{0,4})(?![0-9A-Fa-f:])");

    private IpFetcher() {}

    /**
     * @param previous прошлый результат — его местоположение переиспользуется, если IP не изменился
     * @param deadline момент в {@link SystemClock#elapsedRealtime()}, после которого не начинаем новые запросы
     */
    static IpInfo fetch(IpSource source, String customUrl, IpInfo previous, long deadline) throws IOException {
        IpInfo info;
        switch (source) {
            case IPWHOIS:
                info = fromIpWhoIs(json(getString("https://ipwho.is/", timeout(deadline))));
                break;
            case TWOIP:
                info = from2ip(json(getString("https://api.2ip.io/", timeout(deadline))));
                break;
            case CLOUDFLARE:
                info = ipOnly(parseTrace(getString("https://www.cloudflare.com/cdn-cgi/trace", timeout(deadline))));
                break;
            case IPIFY:
                info = ipOnly(extractIp(getString("https://api.ipify.org?format=json", timeout(deadline))));
                break;
            case CUSTOM:
                if (customUrl == null || !customUrl.trim().matches("(?i)https?://.+")) {
                    throw new IOException("Укажи адрес сервиса (http:// или https://)");
                }
                info = ipOnly(extractIp(getString(customUrl.trim(), timeout(deadline))));
                break;
            case YANDEX:
            default:
                info = ipOnly(yandexIp(deadline));
                break;
        }
        info.source = source == IpSource.CUSTOM ? hostOf(customUrl) : source.title;

        if (!info.hasGeo()) {
            if (previous != null && info.ip.equals(previous.ip) && previous.hasGeo()
                    && System.currentTimeMillis() - previous.fetchedAt < GEO_REUSE_MS) {
                info.copyGeoFrom(previous);
            } else {
                IpInfo geo = geolocate(info.ip, deadline);
                if (geo != null) {
                    info.copyGeoFrom(geo);
                } else if (previous != null && info.ip.equals(previous.ip) && previous.hasGeo()) {
                    info.copyGeoFrom(previous); // лучше старое местоположение, чем никакого
                }
            }
        } else {
            info.geoSource = info.source;
        }
        if (info.country.isEmpty() && info.countryCode.length() == 2) {
            info.country = new Locale("", info.countryCode).getDisplayCountry(Locale.ENGLISH);
        }
        return info;
    }

    // ---------- определение IP ----------

    private static String yandexIp(long deadline) throws IOException {
        IOException last = null;
        for (String url : new String[]{
                "https://ipv4-internet.yandex.net/api/v0/ip",
                "https://yandex.ru/internet/api/v0/ip"}) {
            int t = timeout(deadline);
            try {
                return extractIp(getString(url, t));
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static String parseTrace(String body) throws IOException {
        for (String line : body.split("\n")) {
            if (line.startsWith("ip=")) return extractIp(line.substring(3));
        }
        throw new IOException("no ip in trace");
    }

    /** Достаёт IP из ответа: JSON-строка "1.2.3.4", JSON с полем ip, текст или HTML. */
    static String extractIp(String body) throws IOException {
        String s = body == null ? "" : body.trim();
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(s);
                for (String key : new String[]{"ip", "ipAddress", "query", "origin"}) {
                    String v = Json.str(o, key);
                    if (!v.isEmpty()) {
                        s = v;
                        break;
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        Matcher m4 = IPV4.matcher(s);
        if (m4.find()) return m4.group(1);
        Matcher m6 = IPV6.matcher(s);
        if (m6.find()) return m6.group(1).toLowerCase(Locale.ROOT);
        throw new IOException("IP не найден в ответе сервиса");
    }

    private static IpInfo ipOnly(String ip) {
        IpInfo i = new IpInfo();
        i.ip = ip;
        return i;
    }

    // ---------- местоположение ----------

    /** Местоположение конкретного IP: ipwho.is → ipapi.co → ipinfo.io. */
    private static IpInfo geolocate(String ip, long deadline) {
        String enc = ip.replace(":", "%3A");
        for (int p = 0; p < 3; p++) {
            int t = timeoutUntil(deadline);
            if (t < MIN_TIMEOUT_MS) break;
            try {
                IpInfo g;
                switch (p) {
                    case 0:
                        g = fromIpWhoIs(json(getString("https://ipwho.is/" + enc, t)));
                        break;
                    case 1:
                        g = fromIpapiCo(json(getString("https://ipapi.co/" + enc + "/json/", t)));
                        break;
                    default:
                        g = fromIpinfo(json(getString("https://ipinfo.io/" + enc + "/json", t)));
                        break;
                }
                if (g.hasGeo()) return g;
            } catch (Exception ignored) {
                // следующий сервис
            }
        }
        return null;
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
        i.geoSource = "ipwho.is";
        requireIp(i);
        return i;
    }

    private static IpInfo from2ip(JSONObject o) throws IOException {
        IpInfo i = new IpInfo();
        i.ip = Json.str(o, "ip");
        i.country = Json.str(o, "country");
        i.countryCode = Json.str(o, "code");
        i.region = Json.str(o, "region");
        i.city = Json.str(o, "city");
        i.lat = Json.num(o, "lat");
        i.lon = Json.num(o, "lon");
        i.timezone = Json.str(o, "timezone");
        JSONObject asn = o.optJSONObject("asn");
        if (asn != null) i.isp = Json.str(asn, "name");
        i.geoSource = "2ip.io";
        requireIp(i);
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
        i.geoSource = "ipapi.co";
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
        i.geoSource = "ipinfo.io";
        return i;
    }

    private static void requireIp(IpInfo i) throws IOException {
        if (i.ip.isEmpty()) throw new IOException("empty ip");
    }

    // ---------- HTTP ----------

    private static JSONObject json(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("bad json", e);
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url.trim()).getHost();
        } catch (Exception e) {
            return "свой адрес";
        }
    }

    private static int timeout(long deadline) throws IOException {
        int t = timeoutUntil(deadline);
        if (t < MIN_TIMEOUT_MS) throw new IOException("timeout");
        return t;
    }

    static int timeoutUntil(long deadline) {
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0) return 0;
        return (int) Math.min(MAX_TIMEOUT_MS, left);
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
