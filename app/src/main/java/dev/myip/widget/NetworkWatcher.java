package dev.myip.widget;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

/**
 * Подписка на появление сетей (Wi-Fi, мобильная, VPN) через PendingIntent:
 * система будит приложение даже когда оно не запущено, и виджет обновляет IP.
 */
final class NetworkWatcher {
    private static final String TAG = "NetworkWatcher";
    private static boolean registered;

    private NetworkWatcher() {}

    static synchronized void register(Context ctx, boolean force) {
        if (registered && !force) return;
        Context app = ctx.getApplicationContext();
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return;

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE; // система дописывает extras
        Intent intent = new Intent(app, EventReceiver.class).setAction(EventReceiver.ACTION_NETWORK);
        PendingIntent pi = PendingIntent.getBroadcast(app, 7, intent, flags);

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // VPN тоже считаем
                .build();
        try {
            cm.unregisterNetworkCallback(pi);
        } catch (RuntimeException ignored) {
        }
        try {
            cm.registerNetworkCallback(request, pi);
            registered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "registerNetworkCallback failed", e);
        }
    }
}
