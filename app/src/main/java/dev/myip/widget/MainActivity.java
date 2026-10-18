package dev.myip.widget;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Экран приложения: текущий IP, карта, выбор сервиса, добавление виджетов и названия IP. */
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
    private EditText customUrlEdit;
    private View customBox;
    private Button refreshButton;

    private boolean refreshing;
    private ObjectAnimator pulse;
    private int dotsStep;
    private final Runnable dotsTick = new Runnable() {
        @Override
        public void run() {
            if (!refreshing) return;
            updatedView.setText("Выполняется обновление" + "...".substring(0, dotsStep % 4));
            dotsStep++;
            ui.postDelayed(this, 400);
        }
    };

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
        customUrlEdit = findViewById(R.id.custom_url);
        customBox = findViewById(R.id.custom_box);
        refreshButton = findViewById(R.id.btn_refresh);

        namesEdit.setText(Store.names(this));
        customUrlEdit.setText(Store.customUrl(this));
        setupSourceChooser();

        refreshButton.setOnClickListener(v -> refresh());
        findViewById(R.id.btn_pin_medium).setOnClickListener(v -> pin(IpWidgetMedium.class));
        findViewById(R.id.btn_pin_small).setOnClickListener(v -> pin(IpWidgetSmall.class));
        findViewById(R.id.btn_save).setOnClickListener(v -> saveNames());
        findViewById(R.id.btn_custom_save).setOnClickListener(v -> {
            Store.setCustomUrl(this, customUrlEdit.getText().toString());
            refresh();
        });

        NetworkWatcher.register(this, false);

        IpInfo cached = Store.load(this);
        show(cached);
        if (cached == null || System.currentTimeMillis() - cached.fetchedAt > 60_000L) {
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        refreshing = false;
        ui.removeCallbacks(dotsTick);
        if (pulse != null) pulse.cancel();
        super.onDestroy();
    }

    private void setupSourceChooser() {
        RadioGroup group = findViewById(R.id.source_group);
        IpSource current = Store.source(this);
        for (IpSource s : IpSource.values()) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(s.name());
            String label = s.title;
            if (s == IpSource.YANDEX) label += " (по умолчанию)";
            if (!s.host.isEmpty()) label += "\n" + s.host;
            rb.setText(label);
            rb.setTextColor(getColor(R.color.text_primary));
            rb.setTextSize(14f);
            rb.setPadding(rb.getPaddingLeft(), 12, rb.getPaddingRight(), 12);
            group.addView(rb);
            if (s == current) rb.setChecked(true);
        }
        customBox.setVisibility(current == IpSource.CUSTOM ? View.VISIBLE : View.GONE);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            RadioButton rb = g.findViewById(checkedId);
            if (rb == null || rb.getTag() == null) return;
            IpSource s = IpSource.fromName(rb.getTag().toString());
            if (s == Store.source(this)) return;
            Store.setSource(this, s);
            customBox.setVisibility(s == IpSource.CUSTOM ? View.VISIBLE : View.GONE);
            if (s != IpSource.CUSTOM || !Store.customUrl(this).isEmpty()) refresh();
        });
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        refreshButton.setEnabled(false);
        refreshButton.setText("Обновляю…");
        startAnimation();

        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ok = WidgetUpdater.refreshNow(app, 25_000L);
            ui.post(() -> {
                refreshing = false;
                stopAnimation();
                if (isFinishing() || isDestroyed()) return;
                refreshButton.setEnabled(true);
                refreshButton.setText(R.string.btn_refresh);
                if (!ok) {
                    Toast.makeText(app, "Не удалось получить IP — проверь подключение или выбранный сервис",
                            Toast.LENGTH_SHORT).show();
                }
                show(Store.load(app));
            });
        }, "app-refresh").start();
    }

    /** IP плавно мигает, в строке статуса бегут точки — как в виджете. */
    private void startAnimation() {
        if (pulse == null) {
            pulse = ObjectAnimator.ofFloat(ipView, View.ALPHA, 1f, 0.25f);
            pulse.setDuration(650);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
        }
        pulse.start();
        dotsStep = 0;
        ui.removeCallbacks(dotsTick);
        ui.post(dotsTick);
    }

    private void stopAnimation() {
        ui.removeCallbacks(dotsTick);
        if (pulse != null) pulse.cancel();
        ipView.setAlpha(1f);
    }

    private void show(IpInfo info) {
        boolean has = info != null && !info.ip.isEmpty();
        boolean failed = Store.lastFailed(this);

        if (!refreshing) {
            if (has && info.fetchedAt > 0) {
                String when = DateUtils.formatDateTime(this, info.fetchedAt,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
                updatedView.setText("Обновлено: " + when + (failed ? " · сейчас нет связи" : ""));
            } else {
                updatedView.setText(failed ? "Нет связи" : "");
            }
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
        if (!info.source.isEmpty()) d.append("IP определил: ").append(info.source).append('\n');
        if (!info.geoSource.isEmpty() && !info.geoSource.equals(info.source)) {
            d.append("Местоположение: ").append(info.geoSource);
        }
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
