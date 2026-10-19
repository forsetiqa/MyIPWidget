package dev.myip.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Общий провайдер для обоих размеров виджета. */
public abstract class BaseWidget extends AppWidgetProvider {

    @Override
    public void onEnabled(Context context) {
        NetworkWatcher.register(context, true);
    }

    @Override
    public void onDisabled(Context context) {
        ThemeClock.scheduleNext(context); // виджетов не осталось — будильник снимется
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        NetworkWatcher.register(context, false);
        WidgetUpdater.systemUpdateAsync(context, goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        WidgetUpdater.rerenderAsync(context, goAsync());
    }
}
