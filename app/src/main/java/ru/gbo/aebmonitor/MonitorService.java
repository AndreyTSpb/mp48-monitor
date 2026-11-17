package ru.gbo.aebmonitor;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.io.IOException;
import java.util.Locale;

/**
 * Фоновая служба: связь с блоком, запись, статистика, предупреждения, мини-панель.
 * Работает и когда на экране навигатор; окно приложения подключается к ней.
 */
public final class MonitorService extends Service implements AebLink.Listener {

    public interface Ui {
        void onLinkState(int state, String text);
        void onFrame(Protocol.Frame f, float fps);
        void onFuelResult(boolean ok, String text);
        void onAlert(Alerts.Alert a);
        void onLogState(boolean on, String where);
        void onMessage(String text);
        void onQuit();
        void onMap(KMap map, String error);
    }

    public static final String ACTION_QUIT = "ru.gbo.aebmonitor.QUIT";
    /** Служба запущена после загрузки магнитолы (для лога). */
    public static final String EXTRA_BOOT = "boot";
    private static final String ACTION_USB_PERMISSION = "ru.gbo.aebmonitor.USB_PERMISSION_SERVICE";
    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 1;

    public final class LocalBinder extends Binder {
        public MonitorService get() { return MonitorService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler h = new Handler(Looper.getMainLooper());

    private static volatile boolean running;

    /** Служба сейчас работает (для плановой проверки автозапуска). */
    public static boolean isRunning() {
        return running;
    }

    private Prefs prefs;
    private UsbManager usb;
    private AebLink link;
    private UsbDevice linkDevice;
    private Ui ui;
    private boolean uiVisible;

    public final Trend trend = new Trend();
    public final CellStats cells = new CellStats();
    public final Eco eco = new Eco();
    public Stats stats;
    private final Alerts alerts = new Alerts();
    private final CsvLogger logger = new CsvLogger();
    private OverlayPanel overlay;
    private ToneGenerator tone;
    private LocationManager lm;
    private boolean gpsOn;
    private boolean permissionAsked;      // разрешение на USB уже запрашивали в этой сессии

    private Protocol.Frame last;
    private int linkState = LinkState.CLOSED;
    private String linkText = "Кабель не подключён";
    private Alerts.Alert alert;
    private KMap kmap;
    private String mapError;
    private boolean demo;
    private long demoT0;
    private long fpsWindowStart;
    private int fpsCount;
    private float fps;
    private long lastStatusLog;

    // ---------- жизненный цикл ----------

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = new Prefs(this);
        if (prefs.debug() && !DebugLog.active()) DebugLog.start(this);
        DebugLog.log("service", "onCreate");
        stats = new Stats(prefs);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        alerts.configure(prefs.lowPress(), prefs.waitMinutes());
        overlay = new OverlayPanel(this, prefs);
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, f);
        startForegroundCompat();
        startGps();
        h.postDelayed(autoConnectTask, 2000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean boot = intent != null && intent.getBooleanExtra(EXTRA_BOOT, false);
        DebugLog.log("service", "onStartCommand " + (intent == null ? "null" : String.valueOf(intent.getAction()))
                + (boot ? " (после загрузки системы)" : ""));
        if (intent != null && ACTION_QUIT.equals(intent.getAction())) {
            quit();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        DebugLog.log("service", "onDestroy");
        stopLink();
        h.removeCallbacks(demoTick);
        h.removeCallbacks(autoConnectTask);
        logger.stop();
        stats.save();
        overlay.hide();
        stopGps();
        if (tone != null) tone.release();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        DebugLog.stop();
        running = false;
        super.onDestroy();
    }

    /** Полный выход: всё остановить и закрыть службу. */
    public void quit() {
        DebugLog.log("service", "quit");
        AutoStart.cancel(this);
        stopLink();
        demo = false;
        h.removeCallbacks(demoTick);
        h.removeCallbacks(autoConnectTask);
        logger.stop();
        overlay.hide();
        if (ui != null) {
            ui.onLogState(false, null);
            ui.onQuit();
        }
        stopForeground(true);
        stopSelf();
    }

    private void startForegroundCompat() {
        Compat.createChannel(this, CHANNEL, "Связь с блоком ГБО");
        startForeground(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        int fl = Build.VERSION.SDK_INT >= 23 ? 0x04000000 /* FLAG_IMMUTABLE */ : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fl);
        PendingIntent quit = PendingIntent.getService(this, 1,
                new Intent(this, MonitorService.class).setAction(ACTION_QUIT), fl);
        Notification.Builder b = Compat.builder(this, CHANNEL);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(notifText())
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "Выход", quit);
        return b.build();
    }

    private String notifText() {
        if (demo) return "Демо-режим";
        if (last != null && linkState == LinkState.ONLINE) {
            String fuel = last.engineStopped() ? "двигатель заглушён" : last.fuel == Protocol.FUEL_GAS ? "газ" : last.fuel == Protocol.FUEL_GAS_WAIT ? "ожидание газа" : "бензин";
            return linkText + " · " + fuel + (logger.isOpen() ? " · запись" : "");
        }
        return linkText;
    }

    private int lastNotifFuel = -2, lastNotifState = -1;
    private boolean lastNotifLog;

    private void refreshNotification() {
        int fuel = last == null ? -1 : last.fuel;
        if (fuel == lastNotifFuel && linkState == lastNotifState && logger.isOpen() == lastNotifLog) return;
        lastNotifFuel = fuel;
        lastNotifState = linkState;
        lastNotifLog = logger.isOpen();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification());
    }

    // ---------- окно приложения ----------

    public void attachUi(Ui u) {
        ui = u;
        if (u != null) {
            u.onLinkState(linkState, linkText);
            if (last != null) u.onFrame(last, fps);
            u.onAlert(alert);
            u.onLogState(logger.isOpen(), logger.where());
            u.onMap(kmap, mapError);
        }
    }

    public void setUiVisible(boolean v) {
        uiVisible = v;
        updateOverlay();
    }

    // ---------- USB ----------

    public void connect(UsbDevice d) {
        if (demo) return;
        DebugLog.log("usb", "connect " + d.getDeviceName() + (usb.hasPermission(d) ? "" : " — нет разрешения"));
        if (link != null) {
            if (linkDevice != null && linkDevice.getDeviceName().equals(d.getDeviceName())) return;
            stopLink();
        }
        if (!usb.hasPermission(d)) return;
        linkDevice = d;
        link = new AebLink(usb, d, this);
        link.start();
        if (prefs.autoLog() && !logger.isOpen()) startLog();
    }

    /**
     * Подключиться к найденному кабелю; при необходимости тихо попросить разрешение на USB.
     * @return true, если кабель найден (или уже подключены) — повторять поиск не нужно
     */
    private boolean autoConnect() {
        if (demo || link != null || usb == null) return true;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (!Pl2303.isSupported(d)) continue;
            if (usb.hasPermission(d)) {
                DebugLog.log("usb", "автоподключение " + d.getDeviceName());
                connect(d);
            } else if (ui == null && !permissionAsked) {
                permissionAsked = true;
                DebugLog.log("usb", "запрос разрешения на USB");
                try {
                    int flags = Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0;
                    PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                            new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
                    usb.requestPermission(d, pi);
                } catch (RuntimeException e) {
                    DebugLog.log("usb", "не удалось запросить разрешение: " + e);
                }
            } else if (ui == null) {
                DebugLog.log("usb", "кабель есть, разрешения нет — нужно открыть приложение");
            }
            return true;
        }
        return false;
    }

    private int autoConnectTries;

    private final Runnable autoConnectTask = new Runnable() {
        @Override
        public void run() {
            // На загрузке кабель может появиться в системе не сразу — ищем до ~25 с.
            if (autoConnect() || ++autoConnectTries >= 6) return;
            h.postDelayed(this, 5000);
        }
    };

    public boolean isLinked() { return link != null; }

    public void reconnect() {
        DebugLog.log("usb", "reconnect");
        stopLink();
    }

    private void stopLink() {
        if (link != null) {
            DebugLog.log("usb", "stopLink");
            link.shutdown();
            link = null;
            linkDevice = null;
        }
        setLink(LinkState.CLOSED, "Кабель не подключён");
        alerts.reset();
        alert = null;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                DebugLog.log("usb", granted ? "разрешение на USB получено" : "разрешение на USB отклонено");
                if (granted && d != null) connect(d);
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                permissionAsked = false;
                autoConnectTries = 0;
                DebugLog.log("usb", "кабель подключён " + (d == null ? "?" : d.getDeviceName()));
                autoConnect();
                return;
            }
            if (d != null && linkDevice != null && d.getDeviceName().equals(linkDevice.getDeviceName())) {
                DebugLog.log("usb", "кабель отключён");
                stopLink();
                setLink(LinkState.CLOSED, "Кабель отключён");
                if (logger.isOpen()) stopLog();
                if (ui == null && !demo) quit();          // окно закрыто — служба не нужна
            }
        }
    };

    private void setLink(int st, String text) {
        if (st != linkState || !text.equals(linkText)) DebugLog.log("link", stateName(st) + ": " + text);
        linkState = st;
        linkText = text;
        if (ui != null) ui.onLinkState(st, text);
        overlay.update(last, linkState, alert, demo);
        refreshNotification();
    }

    private static String stateName(int state) {
        switch (state) {
            case LinkState.OPENING: return "открытие";
            case LinkState.NO_ECU: return "нет блока";
            case LinkState.ONLINE: return "связь";
            case LinkState.ERROR: return "ошибка";
            default: return "закрыто";
        }
    }

    // ---------- AebLink.Listener (главный поток) ----------

    @Override
    public void onLinkState(AebLink src, int state, String text) {
        if (src != link || demo) return;
        setLink(state, text);
        if (state == LinkState.ERROR) {
            final UsbDevice d = linkDevice;
            link = null;
            linkDevice = null;
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (link == null && !demo && d != null && usb.getDeviceList().containsValue(d)) connect(d);
                }
            }, 1000);
        }
    }

    @Override
    public void onFrame(AebLink src, Protocol.Frame f) {
        if (src != link || demo) return;
        handleFrame(f, true);
    }

    @Override
    public void onMap(AebLink src, KMap map, String error) {
        if (src != link || demo) return;
        DebugLog.log("map", map != null ? "карта K прочитана" : "ошибка чтения карты: " + error);
        if (map != null || kmap == null) kmap = map;   // при ошибке оставляем прежнюю карту
        mapError = error;
        if (ui != null) ui.onMap(kmap, error);
    }

    public KMap kmap() { return kmap; }

    /** Перечитать карту K из блока. */
    public boolean reloadMap() {
        DebugLog.log("map", "перечитать карту K");
        if (demo) { kmap = KMap.demo(); if (ui != null) ui.onMap(kmap, null); return true; }
        if (link == null) return false;
        link.requestMap();
        return true;
    }

    @Override
    public void onFuelResult(AebLink src, boolean ok, String text) {
        if (src != link) return;
        DebugLog.log("fuel", (ok ? "ok: " : "отказ: ") + text);
        if (ui != null) ui.onFuelResult(ok, text);
    }

    private void handleFrame(Protocol.Frame f, boolean real) {
        long now = System.currentTimeMillis();
        if (fpsWindowStart == 0) fpsWindowStart = now;
        fpsCount++;
        if (now - fpsWindowStart >= 2000) {
            fps = fpsCount * 1000f / (now - fpsWindowStart);
            fpsCount = 0;
            fpsWindowStart = now;
        }
        last = f;
        trend.add(f);
        cells.onFrame(f, kmap);
        if (real) stats.onFrame(f);
        eco.onFrame(f, stats.speedKmh, stats.k);
        double gasL = stats.gasLeft(), rangeKm = stats.rangeKm();
        logger.write(f, stats.speedKmh, eco, gasL, rangeKm);
        String logError = logger.takeFailure();
        if (logError != null) {
            DebugLog.log("log", "ошибка записи: " + logError);
            stopLog();                                   // обновит кнопку записи
            if (ui != null) ui.onMessage("Запись остановлена: " + logError);
        }
        Alerts.Alert fresh = alerts.onFrame(f);
        Alerts.Alert gasAlert = real ? alerts.onGasLevel(stats.gasLeftFrac(), gasL, rangeKm, f.timeMs) : null;
        if (fresh == null) fresh = gasAlert;
        if (fresh != null) {
            DebugLog.log("alert", fresh.text);
            beep(fresh.type);
        }
        Alerts.Alert cur = alerts.current(f);
        boolean changed = (cur == null) != (alert == null) || (cur != null && cur.type != alert.type);
        alert = cur;
        if (ui != null) {
            ui.onFrame(f, fps);
            if (changed || cur != null) ui.onAlert(cur);
        }
        if (real && now - lastStatusLog > 10000) {
            lastStatusLog = now;
            DebugLog.log("state", String.format(Locale.US,
                    "%.0f кадр/с, %d об/мин, %s, скорость %.1f км/ч, k %.6f, газ %.2f л, запас %.0f км, запись %s",
                    fps, f.rpm, fuelName(f.fuel), stats.speedKmh, stats.k, gasL, rangeKm,
                    logger.isOpen() ? "да" : "нет"));
        }
        overlay.update(f, linkState, alert, demo);
        refreshNotification();
    }

    private static String fuelName(int fuel) {
        switch (fuel) {
            case Protocol.FUEL_GAS: return "газ";
            case Protocol.FUEL_GAS_WAIT: return "ожидание газа";
            default: return "бензин";
        }
    }

    // ---------- топливо ----------

    public boolean requestFuel(boolean gas) {
        if (link == null || demo) return false;
        DebugLog.log("fuel", gas ? "команда: газ" : "команда: бензин");
        alerts.onCommand(System.currentTimeMillis());
        link.requestFuel(gas);
        return true;
    }

    public boolean isSwitching() {
        return link != null && link.isSwitching();
    }

    // ---------- запись ----------

    public boolean isLogging() { return logger.isOpen(); }

    public String startLog() {
        try {
            logger.start(this);
            DebugLog.log("log", "начало записи: " + logger.where());
            if (ui != null) ui.onLogState(true, logger.where());
            refreshNotification();
            return "Пишу в " + logger.where();
        } catch (IOException e) {
            DebugLog.log("log", "не удалось начать запись: " + e.getMessage());
            return "Не удалось начать запись: " + e.getMessage();
        }
    }

    public String stopLog() {
        String w = logger.where();
        logger.stop();
        DebugLog.log("log", "конец записи: " + w);
        if (ui != null) ui.onLogState(false, w);
        refreshNotification();
        return "Запись сохранена: " + w;
    }

    // ---------- предупреждения ----------

    public void applySettings() {
        DebugLog.log("settings", String.format(Locale.US, "давление %.2f бар, ожидание %d мин, звук %s",
                prefs.lowPress(), prefs.waitMinutes(), prefs.sound() ? "вкл" : "выкл"));
        alerts.configure(prefs.lowPress(), prefs.waitMinutes());
        updateOverlay();
    }

    private void beep(int type) {
        if (!prefs.sound()) return;
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tone.startTone(type == Alerts.FALLBACK ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                    : ToneGenerator.TONE_PROP_BEEP2, 600);
        } catch (RuntimeException ignored) {
        }
    }

    // ---------- мини-панель ----------

    public boolean overlayAllowed() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    public void updateOverlay() {
        boolean want = prefs.overlay() && !uiVisible && overlayAllowed() && (link != null || demo);
        if (want) {
            overlay.show();
            overlay.update(last, linkState, alert, demo);
        } else {
            overlay.hide();
        }
    }

    // ---------- GPS ----------

    public void startGps() {
        if (gpsOn) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DebugLog.log("gps", "нет разрешения на геолокацию");
            return;
        }
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null || !lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                DebugLog.log("gps", "GPS-провайдер недоступен");
                return;
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, Looper.getMainLooper());
            gpsOn = true;
            DebugLog.log("gps", "GPS слушается");
        } catch (SecurityException | IllegalArgumentException e) {
            DebugLog.log("gps", "не удалось запустить GPS: " + e);
        }
    }

    private void stopGps() {
        if (lm != null && gpsOn) {
            try { lm.removeUpdates(gpsListener); } catch (SecurityException ignored) { }
            DebugLog.log("gps", "GPS остановлен");
        }
        gpsOn = false;
    }

    public boolean gpsEnabled() { return gpsOn; }

    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            if (l == null) return;
            stats.onFix(l.getLatitude(), l.getLongitude(), l.getTime(),
                    l.getSpeed(), l.hasSpeed(), l.hasAccuracy() ? l.getAccuracy() : 0f);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    // ---------- демо ----------

    public boolean isDemo() { return demo; }

    public void setDemo(boolean on) {
        if (on == demo) return;
        DebugLog.log("demo", on ? "включён" : "выключен");
        if (on) {
            stopLink();
            demo = true;
            demoT0 = System.currentTimeMillis();
            trend.clear();
            setLink(LinkState.ONLINE, "Демо-режим");
            kmap = KMap.demo();
            mapError = null;
            if (ui != null) ui.onMap(kmap, null);
            h.post(demoTick);
        } else {
            demo = false;
            h.removeCallbacks(demoTick);
            alerts.reset();
            alert = null;
            last = null;
            trend.clear();
            kmap = null;
            if (ui != null) ui.onMap(null, null);
            setLink(LinkState.CLOSED, "Кабель не подключён");
        }
        updateOverlay();
    }

    private final Runnable demoTick = new Runnable() {
        @Override
        public void run() {
            if (!demo) return;
            long now = System.currentTimeMillis();
            handleFrame(Protocol.decode(Demo.frame((now - demoT0) / 1000.0), now), false);
            h.postDelayed(this, 100);
        }
    };

    public Protocol.Frame lastFrame() { return last; }
    public float fps() { return fps; }
}
