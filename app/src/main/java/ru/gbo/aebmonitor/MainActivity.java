package ru.gbo.aebmonitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity implements MonitorService.Ui {

    private static final String ACTION_PERMISSION = "ru.gbo.aebmonitor.USB_PERMISSION";
    private static final int REQ_LOCATION = 10;
    private static final int PAGE_DASH = 0, PAGE_TREND = 1, PAGE_MAP = 2, PAGE_MCURVE = 3, PAGE_STATS = 4, PAGES = 5;
    private static final String[] PAGE_NAMES = {"Табло", "Графики", "Карта K", "Множитель", "Статистика"};

    private UsbManager usb;
    private Prefs prefs;
    private MonitorService svc;
    private UsbDevice pendingDevice;
    private boolean permissionAsked;

    private DashView dash;
    private TrendView trendView;
    private StatsView statsView;
    private MapView mapView;
    private MultCurveView mcurveView;
    private int page = PAGE_DASH;
    private Button btnFuel, btnLog, btnPage, btnOverlay, btnMenu;
    private boolean rawMode;
    private int lastFuel = -1;
    private boolean fuelBusy;
    private android.view.View dimLayer;
    private final android.os.Handler nightTick = new android.os.Handler();

    // ---------- жизненный цикл ----------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        FrameLayout pages = new FrameLayout(this);
        dash = new DashView(this);
        trendView = new TrendView(this);
        statsView = new StatsView(this);
        mapView = new MapView(this);
        mcurveView = new MultCurveView(this);
        trendView.setWindowSec(prefs.trendWindowSec());
        trendView.setLowPress(prefs.lowPress());
        trendView.setWindowListener(new TrendView.WindowListener() {
            @Override public void onWindowChanged(int sec) { prefs.setTrendWindowSec(sec); }
        });
        pages.addView(dash);
        pages.addView(trendView);
        pages.addView(mapView);
        pages.addView(mcurveView);
        pages.addView(statsView);
        mapView.setMode(prefs.mapCellMode());
        mapView.setModeListener(new MapView.ModeListener() {
            @Override public void onModeChanged(int m) { prefs.setMapCellMode(m); }
        });
        dimLayer = new android.view.View(this);
        dimLayer.setBackgroundColor(0xFF000000);
        dimLayer.setAlpha(0f);
        dimLayer.setClickable(false);
        dimLayer.setFocusable(false);
        pages.addView(dimLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(pages, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(6);
        bar.setPadding(p, 0, p, p);
        btnFuel = addButton(bar, "Газ / бензин", 1.2f, new View.OnClickListener() {
            @Override public void onClick(View v) { onFuelButton(); }
        });
        btnLog = addButton(bar, "Запись", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLog(); }
        });
        btnPage = addButton(bar, "Табло", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { showPage((page + 1) % PAGES); }
        });
        btnOverlay = addButton(bar, "Мини-панель", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { toggleOverlay(); }
        });
        btnMenu = addButton(bar, "Меню", 0.8f, new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(); }
        });
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
        setContentView(root);

        showPage(PAGE_DASH);
        styleButton(btnOverlay, prefs.overlay());
        updateFuelButton();
        applyNight();

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(usbReceiver, f);

        startMonitorService();
        handleIntent(getIntent());
        askLocationOnce();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startMonitorService();
        bindService(new Intent(this, MonitorService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (svc != null) svc.setUiVisible(true);
        connectIfPossible();
        applyNight();
        nightTick.postDelayed(nightTicker, 60000);
    }

    @Override
    protected void onPause() {
        if (svc != null) svc.setUiVisible(false);
        nightTick.removeCallbacks(nightTicker);
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (svc != null) {
            svc.attachUi(null);
            svc.setUiVisible(false);
            try { unbindService(conn); } catch (Exception ignored) { }
            svc = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void startMonitorService() {
        Intent i = new Intent(this, MonitorService.class);
        try {
            Compat.startForegroundService(this, i);
        } catch (RuntimeException ignored) {
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            svc = ((MonitorService.LocalBinder) b).get();
            svc.attachUi(MainActivity.this);
            svc.setUiVisible(true);
            trendView.setTrend(svc.trend);
            statsView.setStats(svc.stats);
            mapView.setTrend(svc.trend);
            mapView.setCells(svc.cells);
            mcurveView.setCells(svc.cells);
            updateGpsInfo();
            dash.setDemo(svc.isDemo());
            if (pendingDevice != null) {
                UsbDevice d = pendingDevice;
                pendingDevice = null;
                svc.connect(d);
            }
            connectIfPossible();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    // ---------- USB ----------

    private void handleIntent(Intent i) {
        if (i != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
            permissionAsked = false;
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (Pl2303.isSupported(d)) {
                if (svc != null) svc.connect(d); else pendingDevice = d;
            }
        }
    }

    private UsbDevice findCable() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (Pl2303.isSupported(d)) return d;
        }
        return null;
    }

    private void connectIfPossible() {
        if (svc == null || svc.isLinked() || svc.isDemo()) return;
        UsbDevice d = findCable();
        if (d == null) return;
        if (usb.hasPermission(d)) {
            svc.connect(d);
        } else if (!permissionAsked) {
            permissionAsked = true;
            dash.setLink(AebLink.ST_OPENING, "Разрешите доступ к USB");
            Intent pi = new Intent(ACTION_PERMISSION);
            pi.setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0;
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, pi, flags));
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String a = i.getAction();
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_PERMISSION.equals(a)) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                    if (svc != null) svc.connect(d); else pendingDevice = d;
                } else {
                    dash.setLink(AebLink.ST_ERROR, "Доступ к USB не разрешён — Меню → Переподключить");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                permissionAsked = false;
                connectIfPossible();
            }
        }
    };

    // ---------- MonitorService.Ui ----------

    @Override
    public void onLinkState(int state, String text) {
        dash.setLink(state, text);
        if (state != AebLink.ST_ONLINE) {
            fuelBusy = false;
            if (state == AebLink.ST_CLOSED) lastFuel = -1;
            updateFuelButton();
        }
    }

    @Override
    public void onFrame(Protocol.Frame f, float fps) {
        dash.setFrame(f, fps);
        if (page == PAGE_MAP) mapView.setFrame(f);
        if (page == PAGE_MCURVE) mcurveView.setFrame(f);
        if (f.fuel != lastFuel) {
            lastFuel = f.fuel;
            updateFuelButton();
        }
    }

    @Override
    public void onFuelResult(boolean ok, String text) {
        fuelBusy = false;
        updateFuelButton();
        toast(text);
    }

    @Override
    public void onAlert(Alerts.Alert a) {
        dash.setAlert(a);
    }

    @Override
    public void onLogState(boolean on, String where) {
        styleButton(btnLog, on);
        dash.setLogging(on);
    }

    @Override
    public void onMap(KMap map, String error) {
        mapView.setMap(map, error);
        mcurveView.setMap(map);
    }

    @Override
    public void onQuit() {
        finish();
    }

    // ---------- страницы ----------

    private void showPage(int pg) {
        page = pg;
        dash.setVisibility(pg == PAGE_DASH ? View.VISIBLE : View.GONE);
        trendView.setVisibility(pg == PAGE_TREND ? View.VISIBLE : View.GONE);
        statsView.setVisibility(pg == PAGE_STATS ? View.VISIBLE : View.GONE);
        mapView.setVisibility(pg == PAGE_MAP ? View.VISIBLE : View.GONE);
        mcurveView.setVisibility(pg == PAGE_MCURVE ? View.VISIBLE : View.GONE);
        if (pg == PAGE_MAP && svc != null) mapView.setFrame(svc.lastFrame());
        if (pg == PAGE_MCURVE && svc != null) { mcurveView.setMap(svc.kmap()); mcurveView.setFrame(svc.lastFrame()); }
        btnPage.setText(PAGE_NAMES[pg] + " ›");
        if (pg == PAGE_STATS) updateGpsInfo();
    }

    // ---------- кнопки ----------

    private void onFuelButton() {
        if (svc == null) return;
        if (svc.isDemo()) { toast("В демо-режиме переключение недоступно"); return; }
        if (!svc.isLinked() || lastFuel < 0) { toast("Нет связи с блоком"); return; }
        if (fuelBusy || svc.isSwitching()) return;
        boolean toGas = lastFuel == Protocol.FUEL_PETROL;
        if (svc.requestFuel(toGas)) {
            fuelBusy = true;
            updateFuelButton();
        }
    }

    private void toggleLog() {
        if (svc == null) return;
        toast(svc.isLogging() ? svc.stopLog() : svc.startLog());
    }

    private void toggleOverlay() {
        boolean on = !prefs.overlay();
        if (on && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("Разрешите «поверх других приложений» для «" + getString(R.string.app_name) + "» и нажмите кнопку ещё раз");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                toast("На этой магнитоле нет экрана такого разрешения");
            }
            return;
        }
        prefs.setOverlay(on);
        styleButton(btnOverlay, on);
        toast(on ? "Мини-панель появится, когда вы переключитесь на другое приложение" : "Мини-панель выключена");
        if (svc != null) svc.updateOverlay();
    }

    private static final int M_RAW = 1, M_DEMO = 2, M_RECONNECT = 3, M_AUTOLOG = 4, M_REFILL = 5,
            M_RESET_TRIP = 6, M_SETTINGS = 7, M_GPS = 8, M_QUIT = 9, M_ABOUT = 10, M_MAP = 11, M_NIGHT = 12, M_RECORDS = 13;

    private void showMenu() {
        final boolean demo = svc != null && svc.isDemo();
        final java.util.List<Integer> ids = new java.util.ArrayList<>();
        final java.util.List<String> names = new java.util.ArrayList<>();
        addItem(ids, names, M_RAW, check(rawMode) + "Байты кадра");
        addItem(ids, names, M_DEMO, check(demo) + "Демо-режим");
        addItem(ids, names, M_AUTOLOG, check(prefs.autoLog()) + "Автозапись при подключении");
        addItem(ids, names, M_SETTINGS, "Настройки предупреждений…");
        addItem(ids, names, M_REFILL, "Заправка (калибровка расхода)…");
        addItem(ids, names, M_RESET_TRIP, "Сбросить статистику поездки");
        addItem(ids, names, M_RECORDS, "Записи…");
        addItem(ids, names, M_NIGHT, "Ночной режим…");
        if (!hasLocation()) addItem(ids, names, M_GPS, "Разрешить GPS (пробег)");
        addItem(ids, names, M_MAP, "Перечитать карту K из блока");
        addItem(ids, names, M_RECONNECT, "Переподключить");
        addItem(ids, names, M_ABOUT, "О программе");
        addItem(ids, names, M_QUIT, "Выход");
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name) + " " + versionName())
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        onMenu(ids.get(which));
                    }
                })
                .show();
    }

    private static void addItem(java.util.List<Integer> ids, java.util.List<String> names, int id, String name) {
        ids.add(id);
        names.add(name);
    }

    private static String check(boolean on) {
        return on ? "✓  " : "     ";
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void onMenu(int id) {
        switch (id) {
            case M_RAW:
                rawMode = !rawMode;
                dash.setRaw(rawMode);
                break;
            case M_DEMO:
                if (svc != null) {
                    boolean on = !svc.isDemo();
                    svc.setDemo(on);
                    dash.setDemo(on);
                    lastFuel = -1;
                    updateFuelButton();
                    if (!on) { permissionAsked = false; connectIfPossible(); }
                }
                break;
            case M_AUTOLOG:
                prefs.setAutoLog(!prefs.autoLog());
                toast(prefs.autoLog() ? "Запись будет включаться сама при подключении кабеля" : "Автозапись выключена");
                break;
            case M_SETTINGS: settingsDialog(); break;
            case M_REFILL: refillDialog(); break;
            case M_RESET_TRIP:
                if (svc != null) { svc.stats.resetTrip(); svc.cells.reset(); statsView.invalidate(); mapView.invalidate(); }
                break;
            case M_GPS: requestLocation(); break;
            case M_RECONNECT:
                if (svc != null) svc.reconnect();
                permissionAsked = false;
                connectIfPossible();
                break;
            case M_NIGHT: nightDialog(); break;
            case M_RECORDS: recordsDialog(); break;
            case M_ABOUT: aboutDialog(); break;
            case M_MAP:
                if (svc == null) break;
                mapView.setMap(null, null);
                if (svc.reloadMap()) toast("Читаю карту K из блока…");
                else { toast("Нет связи с блоком"); mapView.setMap(svc.kmap(), null); }
                break;
            case M_QUIT:
                if (svc != null) svc.quit();
                finish();
                break;
        }
    }

    private void aboutDialog() {
        StringBuilder sb = new StringBuilder();
        sb.append("Версия ").append(versionName()).append(" (").append(versionCode()).append(")\n\n");
        sb.append(getString(R.string.about_description)).append("\n\n");
        appendLine(sb, "Разработчик", getString(R.string.about_developer));
        appendLine(sb, "Контакты", getString(R.string.about_contacts));
        appendLine(sb, "Исходный код", getString(R.string.about_source));
        sb.append("\n© ").append(getString(R.string.about_year)).append(" · ").append(getString(R.string.about_license));
        sb.append("\n\n").append(getString(R.string.about_disclaimer));

        TextView t = new TextView(this);
        int p = dp(22);
        t.setPadding(p, dp(12), p, dp(4));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setLineSpacing(0, 1.15f);
        t.setText(sb.toString());
        t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.EMAIL_ADDRESSES);
        t.setLinksClickable(true);
        t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setIcon(R.mipmap.ic_launcher)
                .setView(sv)
                .setPositiveButton("OK", null)
                .show();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.trim().isEmpty() || value.trim().equals("—")) return;
        sb.append(label).append(": ").append(value).append("\n");
    }

    @SuppressWarnings("deprecation")
    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- диалоги ----------

    private void settingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);
        final EditText press = numberField(box, "Порог низкого давления на газу, бар",
                String.format(Locale.US, "%.2f", prefs.lowPress()), true);
        final EditText wait = numberField(box, "Предупреждать, если нет перехода на газ дольше, мин",
                String.valueOf(prefs.waitMinutes()), false);
        final CheckBox snd = new CheckBox(this);
        snd.setText("Звуковой сигнал");
        snd.setChecked(prefs.sound());
        box.addView(snd);
        new AlertDialog.Builder(this)
                .setTitle("Предупреждения")
                .setView(box)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        float lp = parseF(press.getText().toString(), prefs.lowPress());
                        int wm = Math.round(parseF(wait.getText().toString(), prefs.waitMinutes()));
                        lp = Math.max(0.3f, Math.min(2.0f, lp));
                        wm = Math.max(1, Math.min(60, wm));
                        prefs.setAlerts(lp, wm, snd.isChecked());
                        trendView.setLowPress(lp);
                        if (svc != null) svc.applySettings();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void refillDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);
        TextView hint = new TextView(this);
        hint.setText("Заправляйтесь каждый раз до полного (до отсечки) и вводите, сколько литров вошло. "
                + "Первая такая заправка запускает отсчёт, со второй появляется оценка расхода.");
        box.addView(hint);
        final EditText lit = numberField(box, "Залито литров", "", true);
        new AlertDialog.Builder(this)
                .setTitle("Заправка")
                .setView(box)
                .setPositiveButton("Готово", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (svc == null) return;
                        float l = parseF(lit.getText().toString(), 0);
                        toast(svc.stats.onRefill(l));
                        statsView.invalidate();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private EditText numberField(LinearLayout box, String label, String value, boolean decimal) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, dp(10), 0, 0);
        box.addView(t);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setText(value);
        e.setSelectAllOnFocus(true);
        box.addView(e);
        return e;
    }

    private static float parseF(String s, float def) {
        try { return Float.parseFloat(s.trim().replace(',', '.')); } catch (Exception e) { return def; }
    }

    // ---------- GPS ----------

    private boolean hasLocation() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askLocationOnce() {
        if (hasLocation() || prefs.locationAsked()) return;
        prefs.setLocationAsked();
        requestLocation();
    }

    private void requestLocation() {
        if (Build.VERSION.SDK_INT >= 23 && !hasLocation()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_LOCATION && hasLocation() && svc != null) svc.startGps();
        updateGpsInfo();
    }

    private void updateGpsInfo() {
        statsView.setGps(hasLocation(), svc != null && svc.gpsEnabled());
    }

    // ---------- оформление ----------

    private void updateFuelButton() {
        if (btnFuel == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        String t;
        int bg, edge, fg = 0xFFFFFFFF;
        boolean demo = svc != null && svc.isDemo();
        if (fuelBusy) {
            t = "Переключаю…"; bg = 0xFF3A3320; edge = Theme.WAIT;
        } else if (lastFuel < 0 || demo) {
            t = "Газ / бензин"; bg = Theme.TILE; edge = Theme.GRID; fg = 0xFF6B7380;
        } else if (lastFuel == Protocol.FUEL_PETROL) {
            t = "На газ"; bg = 0xFF1F4A35; edge = Theme.GAS;
        } else {
            t = "На бензин"; bg = 0xFF1C3A5C; edge = Theme.PETROL;
        }
        g.setColor(bg);
        g.setStroke(dp(2), edge);
        btnFuel.setBackground(g);
        btnFuel.setTextColor(fg);
        btnFuel.setText(t);
    }

    private Button addButton(LinearLayout bar, String title, float weight, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setSingleLine(true);
        b.setOnClickListener(l);
        styleButton(b, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(b, lp);
        return b;
    }

    private void styleButton(Button b, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(active ? 0xFF2E5C46 : Theme.TILE);
        g.setStroke(dp(1), active ? Theme.GAS : Theme.GRID);
        b.setBackground(g);
        b.setTextColor(active ? 0xFFFFFFFF : 0xFFC9D1DC);
    }

    // ---------- ночной режим ----------

    private final Runnable nightTicker = new Runnable() {
        @Override public void run() {
            if (prefs.nightMode() == Prefs.NIGHT_AUTO) applyNight();
            nightTick.postDelayed(this, 60000);
        }
    };

    private boolean nightActive() {
        int m = prefs.nightMode();
        if (m == Prefs.NIGHT_NIGHT) return true;
        if (m == Prefs.NIGHT_DAY) return false;
        int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hr >= 19 || hr < 7;
    }

    private void applyNight() {
        boolean night = nightActive();
        if (dimLayer != null) dimLayer.setAlpha(night ? prefs.nightDim() : 0f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = night ? Math.max(0.02f, Math.min(1f, prefs.nightBrightness()))
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private void nightDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pd = dp(20);
        box.setPadding(pd, dp(8), pd, 0);

        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        String[] names = {"Авто (по времени, 19–7 ч)", "Всегда день", "Всегда ночь"};
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(names[i]);
            rb.setId(100 + i);
            rg.addView(rb);
        }
        rg.check(100 + prefs.nightMode());
        box.addView(rg);

        final TextView brLabel = new TextView(this);
        box.addView(brLabel);
        final android.widget.SeekBar br = new android.widget.SeekBar(this);
        br.setMax(100);
        br.setProgress(Math.round(prefs.nightBrightness() * 100));
        box.addView(br);
        final TextView dimLabel = new TextView(this);
        box.addView(dimLabel);
        final android.widget.SeekBar dim = new android.widget.SeekBar(this);
        dim.setMax(70);
        dim.setProgress(Math.round(prefs.nightDim() * 100));
        box.addView(dim);

        final Runnable upd = new Runnable() {
            @Override public void run() {
                brLabel.setText("Яркость подсветки ночью: " + Math.max(2, br.getProgress()) + " %");
                dimLabel.setText("Затемнение интерфейса ночью: " + dim.getProgress() + " %");
            }
        };
        upd.run();
        android.widget.SeekBar.OnSeekBarChangeListener l = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int pr, boolean u) { upd.run(); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        };
        br.setOnSeekBarChangeListener(l);
        dim.setOnSeekBarChangeListener(l);

        new AlertDialog.Builder(this)
                .setTitle("Ночной режим")
                .setView(box)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int sel = rg.getCheckedRadioButtonId() - 100;
                        prefs.setNightMode(sel < 0 ? Prefs.NIGHT_AUTO : sel);
                        prefs.setNightBrightness(Math.max(2, br.getProgress()) / 100f);
                        prefs.setNightDim(dim.getProgress() / 100f);
                        applyNight();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ---------- записи ----------

    private void recordsDialog() {
        final java.util.List<Recordings.Entry> list = Recordings.list(this);
        if (list.isEmpty()) {
            toast("Записей нет. Включите «Запись» в поездке.");
            return;
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd.MM HH:mm", Locale.US);
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Recordings.Entry e = list.get(i);
            items[i] = e.name + "\n" + Recordings.humanSize(e.size) + " · " + fmt.format(new java.util.Date(e.dateMs));
        }
        new AlertDialog.Builder(this)
                .setTitle("Записи (" + list.size() + ")")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { recordActions(list.get(which)); }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void recordActions(final Recordings.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(new String[]{"Поделиться / отправить", "Удалить"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) shareRecord(e);
                        else confirmDeleteRecord(e);
                    }
                })
                .setNegativeButton("Назад", null)
                .show();
    }

    private void shareRecord(Recordings.Entry e) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, e.uri);
            i.putExtra(Intent.EXTRA_SUBJECT, e.name);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Отправить запись"));
        } catch (Exception ex) {
            toast("Не удалось отправить: " + ex.getMessage());
        }
    }

    private void confirmDeleteRecord(final Recordings.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить запись?")
                .setMessage(e.name)
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        toast(Recordings.delete(MainActivity.this, e) ? "Удалено" : "Не удалось удалить");
                        recordsDialog();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
