package dev.myip.widget;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Экран приложения: текущий IP, карта, оформление, выбор сервиса, добавление виджетов и названия IP. */
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
    private LinearLayout scheduleBox;
    private Theme theme;

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
        theme = Theme.current(this);
        setTheme(theme.dark ? R.style.AppTheme : R.style.AppTheme_Light);
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
        scheduleBox = findViewById(R.id.theme_schedule);

        applyTheme(root);
        mapView.setClipToOutline(true); // скругление углов карты по фону-карточке
        setupThemeChooser();

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
    protected void onResume() {
        super.onResume();
        // Пока приложение было свёрнуто, мог наступить другой режим.
        if (Theme.current(this).phase != theme.phase) recreate();
    }

    @Override
    protected void onDestroy() {
        refreshing = false;
        ui.removeCallbacks(dotsTick);
        if (pulse != null) pulse.cancel();
        super.onDestroy();
    }

    // ---------- оформление ----------

    /** Перекрашивает экран под режим: фон-градиент, карточки, цвета текста, системные панели. */
    private void applyTheme(View root) {
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{theme.bgTop, theme.bgBottom}));
        recolor(root, getColor(R.color.text_primary), getColor(R.color.text_secondary), getColor(R.color.accent));

        Window w = getWindow();
        w.setStatusBarColor(theme.bgTop);
        w.setNavigationBarColor(theme.bgBottom);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(theme.dark ? 0 : mask, mask);
            }
        } else {
            View decor = w.getDecorView();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int f = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(theme.dark ? (f & ~light) : (f | light));
        }
    }

    private void recolor(View v, int primary, int secondary, int accent) {
        if ("card".equals(v.getTag())) v.setBackgroundResource(theme.cardRes);
        if (v instanceof TextView && !(v instanceof Button)) {
            TextView tv = (TextView) v;
            int c = tv.getCurrentTextColor();
            if (c == primary) tv.setTextColor(theme.text);
            else if (c == secondary) tv.setTextColor(theme.textSecondary);
            else if (c == accent) tv.setTextColor(theme.accent);
            if (v instanceof EditText) tv.setHintTextColor(theme.textSecondary);
        }
        if (v instanceof ViewGroup && !(v instanceof AdapterView)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolor(g.getChildAt(i), primary, secondary, accent);
        }
    }

    private void setupThemeChooser() {
        Spinner spinner = findViewById(R.id.theme_mode);
        Phase[] phases = Phase.values();
        String[] items = new String[phases.length + 1];
        items[0] = "Авто — по времени на телефоне";
        for (int i = 0; i < phases.length; i++) items[i + 1] = "Всегда: " + phases[i].title;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        Phase fixed = Phase.fromName(Store.themeMode(this));
        spinner.setSelection(fixed == null ? 0 : fixed.ordinal() + 1, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String mode = position == 0 ? ThemeClock.MODE_AUTO : phases[position - 1].name();
                if (mode.equals(Store.themeMode(MainActivity.this))) return;
                Store.setThemeMode(MainActivity.this, mode);
                onThemeSettingsChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        buildSchedule();
    }

    /** Строки «Рассвет · с 06:00»: по нажатию — выбор времени начала. */
    private void buildSchedule() {
        scheduleBox.removeAllViews();
        boolean auto = ThemeClock.isAuto(this);
        for (Phase p : Phase.values()) {
            Theme pt = Theme.of(p);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));

            View swatch = new View(this);
            GradientDrawable sw = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{pt.bgTop, pt.bgBottom});
            sw.setShape(GradientDrawable.OVAL);
            sw.setStroke(dp(1), theme.textDim);
            swatch.setBackground(sw);
            row.addView(swatch, new LinearLayout.LayoutParams(dp(22), dp(22)));

            TextView title = new TextView(this);
            title.setText(p.title);
            title.setTextColor(theme.text);
            title.setTextSize(16f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(12));
            row.addView(title, lp);

            TextView now = new TextView(this);
            if (p == theme.phase) now.setText("  · сейчас");
            now.setTextColor(theme.accent);
            now.setTextSize(13f);
            row.addView(now, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final int start = Store.phaseStart(this, p);
            TextView time = new TextView(this);
            time.setText("с " + ThemeClock.format(start));
            time.setTextColor(auto ? theme.accent : theme.textSecondary);
            time.setTextSize(16f);
            time.setTypeface(time.getTypeface(), android.graphics.Typeface.BOLD);
            row.addView(time);

            row.setOnClickListener(v -> new TimePickerDialog(this, (picker, h, m) -> {
                Store.setPhaseStart(this, p, h * 60 + m);
                onThemeSettingsChanged();
            }, start / 60, start % 60, DateFormat.is24HourFormat(this)).show());
            scheduleBox.addView(row);
        }
    }

    private void onThemeSettingsChanged() {
        WidgetUpdater.rerenderAsync(this, null);
        if (Theme.current(this).phase != theme.phase) {
            recreate();
        } else {
            buildSchedule();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- сервис IP ----------

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
            rb.setTextColor(theme.text);
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
        final Theme t = theme;
        new Thread(() -> {
            final Bitmap bmp;
            try {
                bmp = MapRenderer.render(app, info, widthDp, MAP_HEIGHT_DP, density, t, 0f, 0f, 0f,
                        true, SystemClock.elapsedRealtime() + 10_000L, 1080);
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
