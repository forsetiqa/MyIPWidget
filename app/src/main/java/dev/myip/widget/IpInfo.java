package dev.myip.widget;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Сведения о публичном IP. */
final class IpInfo {
    String ip = "";
    String country = "";
    String countryCode = "";
    String region = "";
    String city = "";
    double lat = Double.NaN;
    double lon = Double.NaN;
    String isp = "";
    String timezone = "";
    String source = "";
    long fetchedAt;

    boolean hasCoords() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    /** Эмодзи-флаг из двухбуквенного кода страны. */
    String flag() {
        if (countryCode == null || countryCode.length() != 2) return "";
        String cc = countryCode.toUpperCase(Locale.ROOT);
        char a = cc.charAt(0);
        char b = cc.charAt(1);
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "";
        int base = 0x1F1E6;
        return new String(Character.toChars(base + (a - 'A')))
                + new String(Character.toChars(base + (b - 'A')));
    }

    /** "Russia 🇷🇺" */
    String countryWithFlag() {
        String f = flag();
        if (country.isEmpty()) return f;
        return f.isEmpty() ? country : country + " " + f;
    }

    /** Город, иначе регион, иначе пустая строка. */
    String place() {
        if (!city.isEmpty()) return city;
        return region;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ip", ip);
        o.put("country", country);
        o.put("countryCode", countryCode);
        o.put("region", region);
        o.put("city", city);
        if (hasCoords()) {
            o.put("lat", lat);
            o.put("lon", lon);
        }
        o.put("isp", isp);
        o.put("timezone", timezone);
        o.put("source", source);
        o.put("fetchedAt", fetchedAt);
        return o;
    }

    static IpInfo fromJson(JSONObject o) {
        IpInfo i = new IpInfo();
        i.ip = Json.str(o, "ip");
        i.country = Json.str(o, "country");
        i.countryCode = Json.str(o, "countryCode");
        i.region = Json.str(o, "region");
        i.city = Json.str(o, "city");
        i.lat = Json.num(o, "lat");
        i.lon = Json.num(o, "lon");
        i.isp = Json.str(o, "isp");
        i.timezone = Json.str(o, "timezone");
        i.source = Json.str(o, "source");
        i.fetchedAt = o.optLong("fetchedAt", 0L);
        return i;
    }
}
