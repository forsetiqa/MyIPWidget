package dev.myip.widget;

import android.content.Context;

/** Цвета виджета, приложения и карты для каждого времени суток. */
final class Theme {
    final Phase phase;
    final boolean dark;
    final int widgetBgRes;
    final int cardRes;
    /** Фон сверху и снизу (вертикальный градиент; у Дня и Ночи цвета совпадают). */
    final int bgTop;
    final int bgBottom;
    final int text;
    final int textSecondary;
    final int textDim;
    final int accent;
    /** Перекраска серых тайлов карты: уровень серого → цвет. */
    final int[] mapLut;
    final int label;
    final int labelHalo;
    final int pillBg;
    final int pillText;
    /** Подкраска запасной карты OpenStreetMap. */
    final int osmTint;
    final float osmTintAlpha;

    private Theme(Phase phase, boolean dark, int widgetBgRes, int cardRes, int bgTop, int bgBottom,
                  int text, int textSecondary, int textDim, int accent, int[][] lutStops,
                  int label, int labelHalo, int pillBg, int pillText, int osmTint, float osmTintAlpha) {
        this.phase = phase;
        this.dark = dark;
        this.widgetBgRes = widgetBgRes;
        this.cardRes = cardRes;
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.text = text;
        this.textSecondary = textSecondary;
        this.textDim = textDim;
        this.accent = accent;
        this.mapLut = buildLut(lutStops);
        this.label = label;
        this.labelHalo = labelHalo;
        this.pillBg = pillBg;
        this.pillText = pillText;
        this.osmTint = osmTint;
        this.osmTintAlpha = osmTintAlpha;
    }

    // Опорные точки перекраски: {серый, R, G, B}. 34 — вода, 66–72 — парки, 76 — суша, выше — дороги.

    private static final Theme DAWN = new Theme(Phase.DAWN, false,
            R.drawable.widget_bg_dawn, R.drawable.card_bg_dawn,
            0xFFE6DDF8, 0xFFFFDCC8,
            0xFF2E2440, 0xFF7A6B88, 0x552E2440, 0xFFC0507A,
            new int[][]{{0, 140, 140, 200}, {34, 174, 180, 230}, {50, 190, 200, 215}, {66, 206, 222, 192},
                    {72, 222, 228, 206}, {76, 250, 233, 225}, {95, 255, 243, 237}, {140, 255, 251, 248},
                    {200, 255, 214, 190}, {255, 255, 200, 170}},
            0xFF2E2440, 0xE6FFFFFF, 0xEBFFFFFF, 0xFF2E2440,
            0xFFFAE9E1, 0.3f);

    private static final Theme DAY = new Theme(Phase.DAY, false,
            R.drawable.widget_bg_day, R.drawable.card_bg_day,
            0xFFF2F2F7, 0xFFF2F2F7,
            0xFF1C1C1E, 0xFF8A8A8E, 0x551C1C1E, 0xFF5E4AE3,
            new int[][]{{0, 110, 160, 210}, {34, 160, 200, 238}, {50, 184, 214, 216}, {66, 200, 228, 188},
                    {72, 214, 230, 200}, {76, 236, 233, 224}, {95, 252, 251, 249}, {140, 255, 255, 255},
                    {200, 255, 234, 170}, {255, 255, 222, 140}},
            0xFF1C1C1E, 0xE6FFFFFF, 0xEBFFFFFF, 0xFF1C1C1E,
            0, 0f);

    private static final Theme DUSK = new Theme(Phase.DUSK, true,
            R.drawable.widget_bg_dusk, R.drawable.card_bg_dusk,
            0xFF2B1F50, 0xFFB04A3A,
            0xFFFFFFFF, 0xFFE2CFD9, 0x4DFFFFFF, 0xFFFFB27A,
            new int[][]{{0, 22, 18, 52}, {34, 40, 36, 86}, {50, 58, 46, 80}, {66, 74, 58, 70},
                    {72, 84, 56, 76}, {76, 96, 56, 82}, {90, 150, 82, 92}, {130, 226, 132, 100},
                    {200, 255, 178, 118}, {255, 255, 210, 160}},
            0xFFFFFFFF, 0xCC1A0A20, 0xE61A1030, 0xFFFFFFFF,
            0xFF60384A, 0.35f);

    private static final Theme NIGHT = new Theme(Phase.NIGHT, true,
            R.drawable.widget_bg, R.drawable.card_bg,
            0xFF0E0E12, 0xFF0E0E12,
            0xFFFFFFFF, 0xFF9C9CA6, 0x4DFFFFFF, 0xFF8E7BFF,
            new int[][]{{0, 10, 16, 28}, {34, 24, 33, 50}, {66, 36, 52, 44}, {72, 40, 50, 48},
                    {76, 45, 48, 56}, {95, 76, 80, 90}, {130, 110, 114, 124}, {255, 178, 182, 190}},
            0xFFFFFFFF, 0xCC000000, 0xF0000000, 0xFFFFFFFF,
            0, 0f);

    static Theme of(Phase p) {
        switch (p) {
            case DAWN:
                return DAWN;
            case DAY:
                return DAY;
            case DUSK:
                return DUSK;
            case NIGHT:
            default:
                return NIGHT;
        }
    }

    static Theme current(Context c) {
        return of(ThemeClock.current(c));
    }

    /** Цвет суши — фон карты, пока не загрузились тайлы. */
    int land() {
        return mapLut[76];
    }

    private static int[] buildLut(int[][] stops) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            for (int s = 0; s < stops.length - 1; s++) {
                int[] a = stops[s];
                int[] b = stops[s + 1];
                if (i >= a[0] && i <= b[0]) {
                    float t = b[0] == a[0] ? 0f : (i - a[0]) / (float) (b[0] - a[0]);
                    int r = Math.round(a[1] + (b[1] - a[1]) * t);
                    int g = Math.round(a[2] + (b[2] - a[2]) * t);
                    int bl = Math.round(a[3] + (b[3] - a[3]) * t);
                    lut[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                    break;
                }
            }
        }
        return lut;
    }
}
