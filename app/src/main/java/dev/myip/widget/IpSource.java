package dev.myip.widget;

/**
 * Сервис, через который определяется публичный IP.
 * При раздельной маршрутизации (podkop и т.п.) от выбора зависит, IP какого маршрута увидим.
 */
enum IpSource {
    YANDEX("Яндекс", "yandex.ru/internet"),
    IPWHOIS("ipwho.is", "ipwho.is"),
    TWOIP("2ip.io", "api.2ip.io"),
    CLOUDFLARE("Cloudflare", "cloudflare.com/cdn-cgi/trace"),
    IPIFY("ipify", "api.ipify.org"),
    CUSTOM("Свой адрес", "");

    final String title;
    final String host;

    IpSource(String title, String host) {
        this.title = title;
        this.host = host;
    }

    static IpSource fromName(String name) {
        if (name != null) {
            for (IpSource s : values()) {
                if (s.name().equals(name)) return s;
            }
        }
        return YANDEX;
    }
}
