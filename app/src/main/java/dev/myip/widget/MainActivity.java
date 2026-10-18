package dev.myip.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Экран приложения: текущий IP, карта, добавление виджетов и названия IP. */
public class MainActivity extends Activity {
    private static final float MAP_HEIGHT_DP = 190f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView updatedView;
    private TextView nameView;
    private TextView ipView;
    private TextView locationView;
    private TextView detailsView;
    private ImageView mapView;
    private EditText namesEdit;
    private Button refreshButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.main_root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        updatedView = findViewById(R.id.main_updated);
        nameView = findViewById(R.id.main_name);
        ipView = findViewById(R.id.main_ip);
        locationView = findViewById(R.id.main_location);
        detailsView = findViewById(R.id.main_details);
        mapView = findViewById(R.id.main_map);
        namesEdit = findViewById(R.id.names);
        refreshButton = findViewById(R.id.btn_refresh);

        namesEdit.setText(Store.names(this));
        refreshButton.setOnClickListener(v -> refresh());
        findViewById(R.id.btn_pin_medium).setOnClickListener(v -> pin(IpWidgetMedium.class));
        findViewById(R.id.btn_pin_small).setOnClickListener(v -> pin(IpWidgetSmall.class));
        findViewById(R.id.btn_save).setOnClickListener(v -> saveNames());

        NetworkWatcher.register(this, false);

        IpInfo cached = Store.load(this);
        show(cached);
        if (cached == null || System.currentTimeMillis() - cached.fetchedAt > 60_000L) {
            refresh();
        }
    }

    private void refresh() {
        refreshButton.setEnabled(false);
        refreshButton.setText("Обновляю…");
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = WidgetUpdater.refreshNow(app, 25_000L);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                refreshButton.setEnabled(true);
                refreshButton.setText(R.string.btn_refresh);
                if (!ok) {
                    Toast.makeText(app, "Не удалось получить IP — проверь подключение",
                            Toast.LENGTH_SHORT).show();
                }
                show(Store.load(app));
            });
        }, "app-refresh").start();
    }

    private void show(IpInfo info) {
        boolean has = info != null && !info.ip.isEmpty();
        boolean failed = Store.lastFailed(this);

        if (has && info.fetchedAt > 0) {
            String when = DateUtils.formatDateTime(this, info.fetchedAt,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
            updatedView.setText("Обновлено: " + when + (failed ? " · сейчас нет связи" : ""));
        } else {
            updatedView.setText(failed ? "Нет связи" : "Загрузка…");
        }

        ipView.setText(has ? info.ip : getString(R.string.placeholder_value));

        String name = has ? Store.nameFor(this, info.ip) : null;
        nameView.setVisibility(name == null ? View.GONE : View.VISIBLE);
        nameView.setText(name == null ? "" : name);

        if (!has) {
            locationView.setText("");
            detailsView.setText("");
            renderMap(null);
            return;
        }

        locationView.setText(join(", ", info.place(), info.countryWithFlag()));

        StringBuilder d = new StringBuilder();
        if (!info.region.isEmpty() && !info.region.equals(info.city)) {
            d.append("Регион: ").append(info.region).append('\n');
        }
        if (!info.isp.isEmpty()) d.append("Провайдер: ").append(info.isp).append('\n');
        if (!info.timezone.isEmpty()) d.append("Часовой пояс: ").append(info.timezone).append('\n');
        if (info.hasCoords()) {
            d.append(String.format(Locale.getDefault(), "Координаты: %.6f, %.6f", info.lat, info.lon))
                    .append('\n');
        }
        if (!info.source.isEmpty()) d.append("Источник: ").append(info.source);
        detailsView.setText(d.toString().trim());

        renderMap(info);
    }

    private void renderMap(final IpInfo info) {
        final Context app = getApplicationContext();
        final float density = getResources().getDisplayMetrics().density;
        final float widthDp = Math.max(200f, getResources().getConfiguration().screenWidthDp - 40f);
        final int bg = getColor(R.color.app_bg);
        new Thread(() -> {
            final Bitmap bmp;
            try {
                bmp = MapRenderer.render(app, info, widthDp, MAP_HEIGHT_DP, density, bg,
                        WidgetUpdater.MAP_CORNER_DP, true, SystemClock.elapsedRealtime() + 10_000L, 1080);
            } catch (RuntimeException | OutOfMemoryError e) {
                return;
            }
            ui.post(() -> {
                if (!isFinishing() && !isDestroyed()) mapView.setImageBitmap(bmp);
            });
        }, "app-map").start();
    }

    private void pin(Class<?> widgetClass) {
        AppWidgetManager m = AppWidgetManager.getInstance(this);
        if (m.isRequestPinAppWidgetSupported()) {
            m.requestPinAppWidget(new ComponentName(this, widgetClass), null, null);
        } else {
            Toast.makeText(this,
                    "Лаунчер не умеет добавлять сам: зажми пустое место на экране → Виджеты → My IP",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveNames() {
        Store.setNames(this, namesEdit.getText().toString());
        WidgetUpdater.rerenderAsync(this, null);
        show(Store.load(this));
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
    }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }
}
