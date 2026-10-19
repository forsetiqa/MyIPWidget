package dev.myip.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Вся логика обновления виджетов. */
final class WidgetUpdater {
    private static final String TAG = "WidgetUpdater";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final long BACKGROUND_BUDGET_MS = 20_000;
    private static final long MIN_TILE_BUDGET_MS = 6_000;
    private static final long MIN_ANIMATION_MS = 1500;
    static final float MAP_CORNER_DP = 14f;

    private WidgetUpdater() {}

    // ---------- точки входа ----------

    /** Запросить IP заново (кнопка ↻, смена сети, загрузка). */
    static void refreshAsync(Context ctx, final BroadcastReceiver.PendingResult pr, final long delayMs) {
        final Context app = ctx.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            finish(pr);
            return;
        }
        new Thread(() -> {
            try {
                if (delayMs > 0) SystemClock.sleep(delayMs);
                doRefresh(app, SystemClock.elapsedRealtime() + BACKGROUND_BUDGET_MS);
            } catch (Throwable t) {
                Log.w(TAG, "refresh failed", t);
            } finally {
                RUNNING.set(false);
                finish(pr);
            }
        }, "ip-refresh").start();
    }

    /** Плановое обновление от системы: сразу показать сохранённое, затем запросить IP. */
    static void systemUpdateAsync(Context ctx, final BroadcastReceiver.PendingResult pr) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                updateAll(app, false, 0);
                if (RUNNING.compareAndSet(false, true)) {
                    try {
                        doRefresh(app, SystemClock.elapsedRealtime() + BACKGROUND_BUDGET_MS);
                    } finally {
                        RUNNING.set(false);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "system update failed", t);
            } finally {
                finish(pr);
            }
        }, "ip-update").start();
    }

    /** Перерисовать без запроса IP (изменился размер виджета или названия). */
    static void rerenderAsync(Context ctx, final BroadcastReceiver.PendingResult pr) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                updateAll(app, true, SystemClock.elapsedRealtime() + 10_000);
            } catch (Throwable t) {
                Log.w(TAG, "rerender failed", t);
            } finally {
                finish(pr);
            }
        }, "ip-rerender").start();
    }

    /** Синхронно, для экрана приложения. Вызывать не из UI-потока. */
    static boolean refreshNow(Context ctx, long budgetMs) {
        Context app = ctx.getApplicationContext();
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        if (!RUNNING.compareAndSet(false, true)) {
            // Уже обновляется (например, из-за смены сети) — дождёмся результата.
            while (RUNNING.get() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250);
            return !Store.lastFailed(app);
        }
        try {
            return doRefresh(app, deadline);
        } finally {
            RUNNING.set(false);
        }
    }

    static boolean hasWidgets(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        return ids(ctx, m, IpWidgetMedium.class).length > 0 || ids(ctx, m, IpWidgetSmall.class).length > 0;
    }

    // ---------- внутренности ----------

    private static boolean doRefresh(Context app, long deadline) {
        long started = SystemClock.elapsedRealtime();
        Ticker ticker = Ticker.start(app);
        boolean ok;
        try {
            IpInfo previous = Store.load(app);
            IpInfo info = IpFetcher.fetch(Store.source(app), Store.customUrl(app), previous, deadline);
            info.fetchedAt = System.currentTimeMillis();
            Store.save(app, info);
            Store.setLastFailed(app, false);
            ok = true;
        } catch (IOException e) {
            Log.w(TAG, "ip fetch failed: " + e.getMessage());
            Store.setLastFailed(app, true);
            ok = false;
        }
        // Анимацию показываем хотя бы полторы секунды, иначе её не заметно.
        long shown = SystemClock.elapsedRealtime() - started;
        if (shown < MIN_ANIMATION_MS) SystemClock.sleep(MIN_ANIMATION_MS - shown);
        ticker.stop();

        long tileDeadline = Math.max(deadline, SystemClock.elapsedRealtime() + MIN_TILE_BUDGET_MS);
        updateAll(app, true, tileDeadline);
        return ok;
    }

    static void updateAll(Context app, boolean allowNetwork, long deadline) {
        AppWidgetManager m = AppWidgetManager.getInstance(app);
        IpInfo info = Store.load(app);
        Theme theme = Theme.current(app);

        for (int id : ids(app, m, IpWidgetSmall.class)) {
            safeUpdate(m, id, build(app, false, info, null, theme), null);
        }

        int[] medium = ids(app, m, IpWidgetMedium.class);
        // Сначала текст (карта остаётся прежней), потом перерисованная карта.
        for (int id : medium) {
            try {
                m.partiallyUpdateAppWidget(id, build(app, true, info, null, theme));
            } catch (RuntimeException e) {
                Log.w(TAG, "partial update failed", e);
            }
        }
        for (int id : medium) {
            Bitmap map = renderMap(app, m, id, info, theme, allowNetwork, deadline);
            safeUpdate(m, id, build(app, true, info, map, theme), build(app, true, info, null, theme));
        }
        ThemeClock.scheduleNext(app);
    }

    private static void safeUpdate(AppWidgetManager m, int id, RemoteViews views, RemoteViews fallback) {
        try {
            m.updateAppWidget(id, views);
        } catch (RuntimeException e) {
            Log.w(TAG, "update failed", e);
            if (fallback != null) {
                try {
                    m.updateAppWidget(id, fallback);
                } catch (RuntimeException e2) {
                    Log.w(TAG, "fallback update failed", e2);
                }
            }
        }
    }

    private static Bitmap renderMap(Context app, AppWidgetManager m, int id, IpInfo info, Theme theme,
                                    boolean allowNetwork, long deadline) {
        Bundle o = m.getAppWidgetOptions(id);
        boolean land = app.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int wDp = 0;
        int hDp = 0;
        if (o != null) {
            wDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            hDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        }
        if (wDp <= 0) wDp = 320;
        if (hDp <= 0) hDp = 150;
        // Разметка: padding 10dp, две равные колонки, между ними 8dp.
        float mapW = (wDp - 20f - 8f) / 2f;
        float mapH = hDp - 20f;
        float density = app.getResources().getDisplayMetrics().density;
        try {
            // Карта начинается на 10dp ниже верха виджета — углы заливаем его градиентом.
            return MapRenderer.render(app, info, mapW, mapH, density, theme, 10f, hDp,
                    MAP_CORNER_DP, allowNetwork, deadline, MapRenderer.WIDGET_MAX_SIDE_PX);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "map render failed", e);
            return null;
        }
    }

    static RemoteViews build(Context c, boolean medium, IpInfo info, Bitmap map, Theme t) {
        RemoteViews v = new RemoteViews(c.getPackageName(),
                medium ? R.layout.widget_medium : R.layout.widget_small);
        applyTheme(v, medium, t);
        boolean has = info != null && !info.ip.isEmpty();
        String dash = c.getString(R.string.placeholder_value);

        v.setTextViewText(R.id.updated, header(c, info));
        v.setTextViewText(R.id.ip, has ? info.ip : dash);
        v.setTextViewText(R.id.ip_pulse_a, has ? info.ip : dash);
        v.setTextViewText(R.id.ip_pulse_b, has ? info.ip : dash);
        v.setViewVisibility(R.id.ip, View.VISIBLE);
        v.setViewVisibility(R.id.ip_pulse, View.GONE);

        String name = has ? Store.nameFor(c, info.ip) : null;
        if (name != null) {
            v.setTextViewText(R.id.ip_name, name);
            v.setViewVisibility(R.id.ip_name, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.ip_name, View.GONE);
        }

        String country = has ? info.countryWithFlag() : "";
        v.setTextViewText(R.id.country, country.isEmpty() ? c.getString(R.string.label_location) : country);
        String place = has ? info.place() : "";
        v.setTextViewText(R.id.city, place.isEmpty() ? dash : place);

        if (medium && map != null) v.setImageViewBitmap(R.id.map, map);

        v.setOnClickPendingIntent(R.id.refresh, refreshIntent(c));
        v.setOnClickPendingIntent(R.id.root, openAppIntent(c));
        return v;
    }

    /** Цвета режима (рассвет, день, закат, ночь). */
    private static void applyTheme(RemoteViews v, boolean medium, Theme t) {
        v.setInt(R.id.root, "setBackgroundResource", t.widgetBgRes);
        v.setInt(R.id.card_ip, "setBackgroundResource", t.cardRes);
        v.setInt(R.id.card_loc, "setBackgroundResource", t.cardRes);
        if (medium) v.setInt(R.id.map, "setBackgroundResource", t.cardRes);
        v.setTextColor(R.id.updated, t.text);
        v.setInt(R.id.refresh, "setColorFilter", t.text);
        v.setTextColor(R.id.label_ip, t.textSecondary);
        v.setTextColor(R.id.ip_name, t.accent);
        v.setTextColor(R.id.ip, t.text);
        v.setTextColor(R.id.ip_pulse_a, t.text);
        v.setTextColor(R.id.ip_pulse_b, t.textDim);
        v.setTextColor(R.id.country, t.textSecondary);
        v.setTextColor(R.id.city, t.text);
    }

    /**
     * Анимация обновления: IP плавно мигает (ViewFlipper в разметке),
     * в заголовке «Updating» с бегущими точками. Останавливается полным обновлением виджета.
     */
    private static final class Ticker implements Runnable {
        private final Context app;
        private final Thread thread;
        private volatile boolean stopped;

        private Ticker(Context app) {
            this.app = app;
            this.thread = new Thread(this, "ip-ticker");
        }

        static Ticker start(Context app) {
            Ticker t = new Ticker(app);
            t.thread.start();
            return t;
        }

        void stop() {
            stopped = true;
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            AppWidgetManager m = AppWidgetManager.getInstance(app);
            int[] small = ids(app, m, IpWidgetSmall.class);
            int[] medium = ids(app, m, IpWidgetMedium.class);
            IpInfo info = Store.load(app);
            String ip = info != null && !info.ip.isEmpty() ? info.ip : app.getString(R.string.placeholder_value);
            int step = 0;
            while (!stopped) {
                String dots = "...".substring(0, step % 4);
                for (int id : small) partialUpdating(app, m, id, R.layout.widget_small, ip, dots, step == 0);
                for (int id : medium) partialUpdating(app, m, id, R.layout.widget_medium, ip, dots, step == 0);
                step++;
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void partialUpdating(Context app, AppWidgetManager m, int id, int layout,
                                        String ip, String dots, boolean first) {
        RemoteViews v = new RemoteViews(app.getPackageName(), layout);
        v.setTextViewText(R.id.updated, "Updating" + dots);
        if (first) {
            v.setTextViewText(R.id.ip_pulse_a, ip);
            v.setTextViewText(R.id.ip_pulse_b, ip);
            v.setViewVisibility(R.id.ip, View.INVISIBLE);
            v.setViewVisibility(R.id.ip_pulse, View.VISIBLE);
        }
        try {
            m.partiallyUpdateAppWidget(id, v);
        } catch (RuntimeException e) {
            Log.w(TAG, "updating state failed", e);
        }
    }

    static String header(Context c, IpInfo info) {
        boolean failed = Store.lastFailed(c);
        if (info == null || info.fetchedAt <= 0) return failed ? "No connection" : "Tap ↻";
        if (failed) return "Offline · " + clock(c, info.fetchedAt);
        return formatWhen(c, info.fetchedAt);
    }

    /** "Today, 22:48" / "Yesterday, 22:48" / "17 Oct, 22:48" — как в оригинале. */
    static String formatWhen(Context c, long t) {
        String time = clock(c, t);
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(t);
        if (sameDay(now, then)) return "Today, " + time;
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(now, then)) return "Yesterday, " + time;
        return new SimpleDateFormat("d MMM", Locale.ENGLISH).format(new Date(t)) + ", " + time;
    }

    private static String clock(Context c, long t) {
        String pattern = DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.ENGLISH).format(new Date(t));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static PendingIntent refreshIntent(Context c) {
        Intent i = new Intent(c, EventReceiver.class).setAction(EventReceiver.ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openAppIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int[] ids(Context c, AppWidgetManager m, Class<?> cls) {
        int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
        return ids == null ? new int[0] : ids;
    }

    private static void finish(BroadcastReceiver.PendingResult pr) {
        if (pr == null) return;
        try {
            pr.finish();
        } catch (IllegalStateException ignored) {
        }
    }
}
