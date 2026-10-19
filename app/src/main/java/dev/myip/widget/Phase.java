package dev.myip.widget;

/** Время суток, от которого зависит оформление. */
enum Phase {
    DAWN("Рассвет", 6 * 60),
    DAY("День", 9 * 60),
    DUSK("Закат", 18 * 60),
    NIGHT("Ночь", 21 * 60);

    final String title;
    /** Начало по умолчанию, минут от полуночи. */
    final int defaultStart;

    Phase(String title, int defaultStart) {
        this.title = title;
        this.defaultStart = defaultStart;
    }

    static Phase fromName(String name) {
        if (name != null) {
            for (Phase p : values()) {
                if (p.name().equals(name)) return p;
            }
        }
        return null;
    }
}
