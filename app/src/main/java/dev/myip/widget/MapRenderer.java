package dev.myip.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Рисует карту вокруг координат IP в стиле карт iOS: серые тайлы Esri Dark Gray Canvas
 * перекрашиваются в палитру текущего режима (рассвет, день, закат, ночь), сверху — название
 * города и «пилюля» с координатами. Если Esri недоступен — тайлы OpenStreetMap. Ключи API не нужны.
 */
final class MapRenderer {
    private static final String TAG = "MapRenderer";
    private static final int TILE = 256;
    private static final int MAX_ZOOM = 11;
    /** Пикселей тайла z11 на 1 dp виджета: ~44 км по ширине карты 150 dp на широте Москвы. */
    private static final float SRC_PX_PER_DP_Z11 = 6.7f;
    /** Для виджета: битмап < ~400 КБ, т.к. RemoteViews передаются через Binder. */
    static final int WIDGET_MAX_SIDE_PX = 440;
    private static final long TILE_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long ESRI_RETRY_MS = 60L * 60 * 1000;
    /** Когда Esri последний раз не отдал ни одного тайла (например, недоступен из РФ). */
    private static volatile long esriFailedAt;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "map-tiles");
        t.setDaemon(true);
        return t;
    });

    private enum Provider {
        ESRI("esri", 0xFF4C4C4E,
                "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/%1$d/%3$d/%2$d"),
        OSM("osm", 0xFFF2EFE9, "https://tile.openstreetmap.org/%1$d/%2$d/%3$d.png");

        final String key;
        final int baseColor;
        final String urlFormat;

        Provider(String key, int baseColor, String urlFormat) {
            this.key = key;
            this.baseColor = baseColor;
            this.urlFormat = urlFormat;
        }
    }

    private MapRenderer() {}

    /**
     * @param viewWdp     ширина области карты в dp
     * @param viewHdp     высота области карты в dp
     * @param density     плотность экрана (px на dp)
     * @param theme       палитра режима
     * @param bgOffsetDp  отступ карты от верха фона (для заливки углов градиентом фона)
     * @param bgHeightDp  высота фона в dp
     * @param cornerDp    радиус скругления в dp; 0 — без скругления (углы обрежет сам View)
     * @param maxSidePx   ограничение на размер битмапа по большей стороне
     */
    static Bitmap render(Context ctx, IpInfo info, float viewWdp, float viewHdp, float density,
                         Theme theme, float bgOffsetDp, float bgHeightDp, float cornerDp,
                         boolean allowNetwork, long deadline, int maxSidePx) {
        viewWdp = Math.max(40f, viewWdp);
        viewHdp = Math.max(40f, viewHdp);
        float k = Math.min(density, maxSidePx / Math.max(viewWdp, viewHdp)); // px битмапа на dp
        int w = Math.max(1, Math.round(viewWdp * k));
        int h = Math.max(1, Math.round(viewHdp * k));

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(theme.land());

        if (info != null && info.hasCoords()) {
            boolean esriFirst = SystemClock.elapsedRealtime() - esriFailedAt > ESRI_RETRY_MS || esriFailedAt == 0;
            Provider first = esriFirst ? Provider.ESRI : Provider.OSM;
            Provider second = esriFirst ? Provider.OSM : Provider.ESRI;
            Bitmap base = drawBase(ctx, first, theme, info, w, h, k, allowNetwork, deadline);
            if (base == null && first == Provider.ESRI && allowNetwork) {
                esriFailedAt = SystemClock.elapsedRealtime();
            }
            if (base == null) base = drawBase(ctx, second, theme, info, w, h, k, allowNetwork, deadline);
            if (base != null) {
                c.drawBitmap(base, 0f, 0f, null);
                base.recycle();
            }
        }
        drawCityLabel(c, theme, info, w, h, k);
        drawCoordinates(c, theme, info, w, h, k);
        if (cornerDp > 0) {
            drawCorners(c, w, h, cornerDp * k, theme, -bgOffsetDp * k, (bgHeightDp - bgOffsetDp) * k);
        }
        return out;
    }

    /** Собирает подложку w×h из тайлов. null — если не удалось получить ни одного тайла. */
    private static Bitmap drawBase(Context ctx, Provider p, Theme theme, IpInfo info, int w, int h, float k,
                                   boolean allowNetwork, long deadline) {
        double srcPerPx = SRC_PX_PER_DP_Z11 / k; // пикселей тайла z11 на пиксель результата
        int z = MAX_ZOOM;
        while (srcPerPx > 2.4 && z > 3) {
            srcPerPx /= 2;
            z--;
        }
        // При сильном уменьшении рисуем вдвое крупнее и сжимаем ровно в 2 раза — тонкие дороги не «рвутся».
        int m = srcPerPx > 1.3 ? 2 : 1;
        int iw = w * m;
        int ih = h * m;
        double scale = m / srcPerPx; // пикселей промежуточного битмапа на пиксель тайла

        int n = 1 << z;
        double lat = Math.max(-85.05, Math.min(85.05, info.lat));
        double latRad = Math.toRadians(lat);
        double worldX = (info.lon + 180.0) / 360.0 * n * TILE;
        double worldY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n * TILE;
        double srcW = iw / scale;
        double srcH = ih / scale;
        double left = worldX - srcW / 2.0;
        double top = worldY - srcH / 2.0;
        int tx0 = (int) Math.floor(left / TILE);
        int tx1 = (int) Math.floor((left + srcW) / TILE);
        int ty0 = (int) Math.floor(top / TILE);
        int ty1 = (int) Math.floor((top + srcH) / TILE);

        List<int[]> positions = new ArrayList<>();
        List<Future<Bitmap>> futures = new ArrayList<>();
        final int zoom = z;
        for (int ty = ty0; ty <= ty1; ty++) {
            if (ty < 0 || ty >= n) continue;
            for (int tx = tx0; tx <= tx1; tx++) {
                final int x = ((tx % n) + n) % n;
                final int y = ty;
                positions.add(new int[]{tx, ty});
                futures.add(POOL.submit(() -> loadTile(ctx, p, zoom, x, y, allowNetwork, deadline)));
            }
        }

        Bitmap inter = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        Canvas ic = new Canvas(inter);
        ic.drawColor(p.baseColor);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        float size = (float) (TILE * scale);
        int loaded = 0;
        for (int i = 0; i < futures.size(); i++) {
            Bitmap tile = null;
            try {
                long wait = Math.max(50L, deadline + 1500L - SystemClock.elapsedRealtime());
                tile = futures.get(i).get(wait, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                futures.get(i).cancel(true);
            }
            if (tile == null) continue;
            int[] pos = positions.get(i);
            float dl = (float) ((pos[0] * (double) TILE - left) * scale);
            float dt = (float) ((pos[1] * (double) TILE - top) * scale);
            ic.drawBitmap(tile, null, new RectF(dl, dt, dl + size + 0.5f, dt + size + 0.5f), paint);
            tile.recycle();
            loaded++;
        }
        if (loaded == 0) {
            inter.recycle();
            return null;
        }

        Bitmap scaled = inter;
        if (m != 1) {
            scaled = Bitmap.createScaledBitmap(inter, w, h, true);
            inter.recycle();
        }
        int[] px = new int[w * h];
        scaled.getPixels(px, 0, w, 0, 0, w, h);
        scaled.recycle();
        if (p == Provider.ESRI) recolorEsri(px, theme.mapLut);
        else styleOsm(px, theme);
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap loadTile(Context ctx, Provider p, int z, int x, int y,
                                   boolean allowNetwork, long deadline) {
        File dir = new File(ctx.getCacheDir(), "tiles");
        File f = new File(dir, p.key + "_" + z + "_" + x + "_" + y + ".png");
        boolean cached = f.exists() && f.length() > 0;
        boolean fresh = cached && System.currentTimeMillis() - f.lastModified() < TILE_TTL_MS;
        if (cached && (fresh || !allowNetwork)) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
        }
        if (!allowNetwork) return null;
        int timeout = IpFetcher.timeoutUntil(deadline);
        if (timeout < 1000) return cached ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;

        String url = String.format(Locale.ROOT, p.urlFormat, z, x, y);
        try {
            byte[] data = IpFetcher.getBytes(url, timeout);
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            if (dir.isDirectory() || dir.mkdirs()) {
                File tmp = new File(dir, f.getName() + "." + Thread.currentThread().getId() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (!tmp.renameTo(f)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            return b;
        } catch (IOException e) {
            Log.w(TAG, "tile " + url + ": " + e.getMessage());
            return cached ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        }
    }

    private static void recolorEsri(int[] px, int[] lut) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int gray = (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
            px[i] = lut[gray];
        }
    }

    /**
     * Запасная карта OSM: для тёмных режимов — инверсия с поворотом оттенка на 180°
     * (как CSS invert/hue-rotate), затем подкраска под режим.
     */
    private static void styleOsm(int[] px, Theme theme) {
        float a = theme.osmTintAlpha;
        float tr = ((theme.osmTint >> 16) & 0xFF) * a;
        float tg = ((theme.osmTint >> 8) & 0xFF) * a;
        float tb = (theme.osmTint & 0xFF) * a;
        float keep = 1f - a;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            float r = (c >> 16) & 0xFF;
            float g = (c >> 8) & 0xFF;
            float b = c & 0xFF;
            if (theme.dark) {
                float nr = 0.4908f * r - 1.2227f * g - 0.1231f * b + 230.1f;
                float ng = -0.3642f * r - 0.3677f * g - 0.1231f * b + 230.1f;
                float nb = -0.3642f * r - 1.2227f * g + 0.7319f * b + 230.1f;
                r = nr;
                g = ng;
                b = nb;
            }
            px[i] = 0xFF000000 | (clamp(r * keep + tr) << 16) | (clamp(g * keep + tg) << 8) | clamp(b * keep + tb);
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }

    private static void drawCityLabel(Canvas c, Theme theme, IpInfo info, int w, int h, float k) {
        String label = "";
        if (info != null) {
            label = info.place();
            if (label.isEmpty()) label = info.country;
        }
        if (label.isEmpty()) return;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(theme.label);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(19f * k);
        float maxW = w * 0.88f;
        float tw = p.measureText(label);
        if (tw > maxW) p.setTextSize(p.getTextSize() * maxW / tw);
        p.setShadowLayer(3f * k, 0f, 0f, theme.labelHalo);

        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = h * 0.44f - (fm.ascent + fm.descent) / 2f;
        c.drawText(label, w / 2f, baseline, p);
    }

    private static void drawCoordinates(Canvas c, Theme theme, IpInfo info, int w, int h, float k) {
        if (info == null || !info.hasCoords()) return;
        String text = String.format(Locale.getDefault(), "%.6f %.6f", info.lat, info.lon);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(theme.pillText);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(10.5f * k);
        float tw = p.measureText(text);
        float maxTw = w * 0.84f;
        if (tw > maxTw) {
            p.setTextSize(p.getTextSize() * maxTw / tw);
            tw = maxTw;
        }
        float ph = p.getTextSize() * 1.9f;
        float pw = tw + p.getTextSize() * 1.6f;
        float bottom = h - 8f * k;
        RectF r = new RectF(w / 2f - pw / 2f, bottom - ph, w / 2f + pw / 2f, bottom);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(theme.pillBg);
        if (!theme.dark) bg.setShadowLayer(2.5f * k, 0f, 0.5f * k, 0x40000000); // светлая пилюля на светлой карте
        c.drawRoundRect(r, ph / 2f, ph / 2f, bg);

        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(text, w / 2f, r.centerY() - (fm.ascent + fm.descent) / 2f, p);
    }

    /**
     * Закрашивает углы фоном виджета (с его вертикальным градиентом) — так скругление работает и в RGB_565.
     * bgTop/bgBottom — где верх и низ фона виджета в координатах битмапа.
     */
    private static void drawCorners(Canvas c, int w, int h, float radius, Theme theme, float bgTop, float bgBottom) {
        Path mask = new Path();
        mask.addRect(0, 0, w, h, Path.Direction.CW);
        Path rounded = new Path();
        rounded.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        mask.op(rounded, Path.Op.DIFFERENCE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (theme.bgTop == theme.bgBottom) {
            p.setColor(theme.bgTop);
        } else {
            p.setShader(new LinearGradient(0f, bgTop, 0f, bgBottom, theme.bgTop, theme.bgBottom,
                    Shader.TileMode.CLAMP));
        }
        c.drawPath(mask, p);
    }
}
