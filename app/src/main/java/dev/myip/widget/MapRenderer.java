package dev.myip.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Рисует тёмную карту вокруг координат IP: тайлы CARTO Dark Matter,
 * крупное название города по центру и «пилюля» с координатами снизу.
 */
final class MapRenderer {
    private static final String TAG = "MapRenderer";
    private static final int TILE = 512;          // тайлы @2x
    private static final int ZOOM = 9;
    private static final float TILE_PX_PER_DP = 3.6f; // масштаб: ~45 км по ширине виджета на широте Москвы
    /** Для виджета: битмап < ~400 КБ, т.к. RemoteViews передаются через Binder. */
    static final int WIDGET_MAX_SIDE_PX = 440;
    private static final int LAND_COLOR = 0xFF2B2F38;
    private static final long TILE_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    private MapRenderer() {}

    /**
     * @param viewWdp     ширина области карты в dp
     * @param viewHdp     высота области карты в dp
     * @param density     плотность экрана (px на dp)
     * @param cornerColor цвет фона под скруглёнными углами
     * @param cornerDp    радиус скругления в dp
     * @param maxSidePx   ограничение на размер битмапа по большей стороне
     */
    static Bitmap render(Context ctx, IpInfo info, float viewWdp, float viewHdp, float density,
                         int cornerColor, float cornerDp, boolean allowNetwork, long deadline,
                         int maxSidePx) {
        viewWdp = Math.max(40f, viewWdp);
        viewHdp = Math.max(40f, viewHdp);
        float k = Math.min(density, maxSidePx / Math.max(viewWdp, viewHdp)); // px битмапа на dp
        int w = Math.max(1, Math.round(viewWdp * k));
        int h = Math.max(1, Math.round(viewHdp * k));

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        c.drawColor(LAND_COLOR);

        if (info != null && info.hasCoords()) {
            drawTiles(ctx, c, info, w, h, k, allowNetwork, deadline);
        }
        drawCityLabel(c, info, w, h, k);
        drawCoordinates(c, info, w, h, k);
        drawCorners(c, w, h, cornerDp * k, cornerColor);
        return bmp;
    }

    private static void drawTiles(Context ctx, Canvas c, IpInfo info, int w, int h, float k,
                                  boolean allowNetwork, long deadline) {
        double scale = k / TILE_PX_PER_DP; // px битмапа на px тайла
        int n = 1 << ZOOM;
        double lat = Math.max(-85.05, Math.min(85.05, info.lat));
        double latRad = Math.toRadians(lat);
        double worldX = (info.lon + 180.0) / 360.0 * n * TILE;
        double worldY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n * TILE;

        double srcW = w / scale;
        double srcH = h / scale;
        double left = worldX - srcW / 2.0;
        double top = worldY - srcH / 2.0;
        int tx0 = (int) Math.floor(left / TILE);
        int tx1 = (int) Math.floor((left + srcW) / TILE);
        int ty0 = (int) Math.floor(top / TILE);
        int ty1 = (int) Math.floor((top + srcH) / TILE);

        // Dark Matter очень тёмный — чуть осветляем и уводим в синеву, как у карт iOS.
        ColorMatrix cm = new ColorMatrix(new float[]{
                1.25f, 0, 0, 0, 14,
                0, 1.25f, 0, 0, 18,
                0, 0, 1.30f, 0, 30,
                0, 0, 0, 1, 0
        });
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));

        float size = (float) (TILE * scale);
        for (int ty = ty0; ty <= ty1; ty++) {
            if (ty < 0 || ty >= n) continue;
            for (int tx = tx0; tx <= tx1; tx++) {
                int x = ((tx % n) + n) % n;
                Bitmap tile = loadTile(ctx, ZOOM, x, ty, allowNetwork, deadline);
                if (tile == null) continue;
                float dl = (float) ((tx * (double) TILE - left) * scale);
                float dt = (float) ((ty * (double) TILE - top) * scale);
                c.drawBitmap(tile, null, new RectF(dl, dt, dl + size + 0.5f, dt + size + 0.5f), paint);
                tile.recycle();
            }
        }
    }

    private static Bitmap loadTile(Context ctx, int z, int x, int y, boolean allowNetwork, long deadline) {
        File dir = new File(ctx.getCacheDir(), "tiles");
        File f = new File(dir, "dark_" + z + "_" + x + "_" + y + ".png");
        boolean fresh = f.exists() && f.length() > 0
                && System.currentTimeMillis() - f.lastModified() < TILE_TTL_MS;
        if (f.exists() && f.length() > 0 && (fresh || !allowNetwork)) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
        }
        if (!allowNetwork) return null;
        int timeout = IpFetcher.timeoutUntil(deadline);
        if (timeout < 1000) {
            return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        }
        String sub = "abcd".substring((x + y) % 4, (x + y) % 4 + 1);
        String url = "https://" + sub + ".basemaps.cartocdn.com/dark_nolabels/"
                + z + "/" + x + "/" + y + "@2x.png";
        try {
            byte[] data = IpFetcher.getBytes(url, timeout);
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            if (dir.isDirectory() || dir.mkdirs()) {
                File tmp = new File(dir, f.getName() + ".tmp");
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
            return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        }
    }

    private static void drawCityLabel(Canvas c, IpInfo info, int w, int h, float k) {
        String label = "";
        if (info != null) {
            label = info.place();
            if (label.isEmpty()) label = info.country;
        }
        if (label.isEmpty()) return;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(19f * k);
        float maxW = w * 0.88f;
        float tw = p.measureText(label);
        if (tw > maxW) p.setTextSize(p.getTextSize() * maxW / tw);
        p.setShadowLayer(3f * k, 0f, 0f, 0xCC000000);

        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = h * 0.44f - (fm.ascent + fm.descent) / 2f;
        c.drawText(label, w / 2f, baseline, p);
    }

    private static void drawCoordinates(Canvas c, IpInfo info, int w, int h, float k) {
        if (info == null || !info.hasCoords()) return;
        String text = String.format(Locale.getDefault(), "%.6f %.6f", info.lat, info.lon);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
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
        bg.setColor(0xF0000000);
        c.drawRoundRect(r, ph / 2f, ph / 2f, bg);

        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(text, w / 2f, r.centerY() - (fm.ascent + fm.descent) / 2f, p);
    }

    /** Закрашивает углы цветом фона виджета — так скругление работает и в RGB_565. */
    private static void drawCorners(Canvas c, int w, int h, float radius, int color) {
        Path mask = new Path();
        mask.addRect(0, 0, w, h, Path.Direction.CW);
        Path rounded = new Path();
        rounded.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        mask.op(rounded, Path.Op.DIFFERENCE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawPath(mask, p);
    }
}
