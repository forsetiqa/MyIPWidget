package dev.myip.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Кнопка ↻, смена сети, смена режима по времени, перезагрузка телефона и обновление приложения. */
public class EventReceiver extends BroadcastReceiver {
    static final String ACTION_REFRESH = "dev.myip.widget.action.REFRESH";
    static final String ACTION_NETWORK = "dev.myip.widget.action.NETWORK";
    static final String ACTION_THEME = "dev.myip.widget.action.THEME";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        switch (action) {
            case ACTION_REFRESH:
                WidgetUpdater.refreshAsync(context, goAsync(), 0);
                break;
            case ACTION_NETWORK:
                // Даём VPN/маршрутам пару секунд устояться, иначе можно увидеть старый IP.
                if (WidgetUpdater.hasWidgets(context)) {
                    WidgetUpdater.refreshAsync(context, goAsync(), 2500);
                }
                break;
            case ACTION_THEME:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
                // Смена режима по времени, перевод часов или часового пояса — перекрасить без запроса IP.
                if (WidgetUpdater.hasWidgets(context)) {
                    WidgetUpdater.rerenderAsync(context, goAsync());
                }
                break;
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                NetworkWatcher.register(context, true);
                if (WidgetUpdater.hasWidgets(context)) {
                    WidgetUpdater.refreshAsync(context, goAsync(), 3000);
                }
                break;
            default:
                break;
        }
    }
}
