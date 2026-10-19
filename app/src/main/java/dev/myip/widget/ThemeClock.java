package dev.myip.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

/**
 * Выбор режима по времени на телефоне (не по местоположению IP) и будильник
 * на ближайшую смену режима, чтобы виджет перекрасился вовремя.
 */
final class ThemeClock {
    static final String MODE_AUTO = "AUTO";
    private static final String TAG = "ThemeClock";
    /** На Android 12+ окно неточного будильника не меньше 10 минут. */
    private static final long WINDOW_MS = 10L * 60 * 1000;

    private ThemeClock() {}

    static boolean isAuto(Context c) {
        return Phase.fromName(Store.themeMode(c)) == null;
    }

    /** Текущий режим: выбранный вручную или по времени. */
    static Phase current(Context c) {
        Phase fixed = Phase.fromName(Store.themeMode(c));
        return fixed != null ? fixed : phaseAt(c, minutesNow());
    }

    /** Режим по расписанию — тот, чьё начало последним прошло (с переходом через полночь). */
    static Phase phaseAt(Context c, int minute) {
        Phase best = null;
        int bestStart = -1;
        Phase latest = Phase.NIGHT;
        int latestStart = -1;
        for (Phase p : Phase.values()) {
            int s = Store.phaseStart(c, p);
            if (s <= minute && s > bestStart) {
                best = p;
                bestStart = s;
            }
            if (s > latestStart) {
                latest = p;
                latestStart = s;
            }
        }
        return best != null ? best : latest;
    }

    /** Ближайшая смена режима (System.currentTimeMillis), или -1 при ручном режиме. */
    static long nextChange(Context c) {
        if (!isAuto(c)) return -1;
        Calendar now = Calendar.getInstance();
        long best = Long.MAX_VALUE;
        for (Phase p : Phase.values()) {
            int s = Store.phaseStart(c, p);
            Calendar t = (Calendar) now.clone();
            t.set(Calendar.HOUR_OF_DAY, s / 60);
            t.set(Calendar.MINUTE, s % 60);
            t.set(Calendar.SECOND, 0);
            t.set(Calendar.MILLISECOND, 0);
            if (!t.after(now)) t.add(Calendar.DAY_OF_YEAR, 1);
            best = Math.min(best, t.getTimeInMillis());
        }
        return best;
    }

    /** Ставит (или снимает) будильник на ближайшую смену режима. */
    static void scheduleNext(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent i = new Intent(app, EventReceiver.class).setAction(EventReceiver.ACTION_THEME);
        PendingIntent pi = PendingIntent.getBroadcast(app, 3, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long next = nextChange(app);
        try {
            if (next < 0 || !WidgetUpdater.hasWidgets(app)) {
                am.cancel(pi);
                return;
            }
            // RTC без пробуждения: если экран выключен, сработает при включении.
            am.setWindow(AlarmManager.RTC, next + 1000L, WINDOW_MS, pi);
        } catch (RuntimeException e) {
            Log.w(TAG, "alarm failed", e);
        }
    }

    static int minutesNow() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    static String format(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
