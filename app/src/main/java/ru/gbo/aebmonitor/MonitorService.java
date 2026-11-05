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
        void onQuit();
        void onMap(KMap map, String error);
    }

    public static final String ACTION_QUIT = "ru.gbo.aebmonitor.QUIT";
    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 1;

    public final class LocalBinder extends Binder {
        public MonitorService get() { return MonitorService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler h = new Handler(Looper.getMainLooper());

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

    private Protocol.Frame last;
    private int linkState = AebLink.ST_CLOSED;
    private String linkText = "Кабель не подключён";
    private Alerts.Alert alert;
    private KMap kmap;
    private String mapError;
    private boolean demo;
    private long demoT0;
    private long fpsWindowStart;
    private int fpsCount;
    private float fps;

    // ---------- жизненный цикл ----------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        stats = new Stats(prefs);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        alerts.configure(prefs.lowPress(), prefs.waitMinutes());
        overlay = new OverlayPanel(this, prefs);
        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(detachReceiver, f);
        startForegroundCompat();
        startGps();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        stopLink();
        h.removeCallbacks(demoTick);
        logger.stop();
        stats.save();
        overlay.hide();
        stopGps();
        if (tone != null) tone.release();
        try { unregisterReceiver(detachReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** Полный выход: всё остановить и закрыть службу. */
    public void quit() {
        stopLink();
        demo = false;
        h.removeCallbacks(demoTick);
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
        if (last != null && linkState == AebLink.ST_ONLINE) {
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

    public boolean isLinked() { return link != null; }

    public void reconnect() {
        stopLink();
    }

    private void stopLink() {
        if (link != null) {
            link.shutdown();
            link = null;
            linkDevice = null;
        }
        setLink(AebLink.ST_CLOSED, "Кабель не подключён");
        alerts.reset();
        alert = null;
    }

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && linkDevice != null && d.getDeviceName().equals(linkDevice.getDeviceName())) {
                stopLink();
                setLink(AebLink.ST_CLOSED, "Кабель отключён");
                if (logger.isOpen()) stopLog();
                if (ui == null && !demo) quit();          // окно закрыто — служба не нужна
            }
        }
    };

    private void setLink(int st, String text) {
        linkState = st;
        linkText = text;
        if (ui != null) ui.onLinkState(st, text);
        overlay.update(last, linkState, alert, demo);
        refreshNotification();
    }

    // ---------- AebLink.Listener (главный поток) ----------

    @Override
    public void onLinkState(AebLink src, int state, String text) {
        if (src != link || demo) return;
        setLink(state, text);
        if (state == AebLink.ST_ERROR) {
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
        if (map != null || kmap == null) kmap = map;   // при ошибке оставляем прежнюю карту
        mapError = error;
        if (ui != null) ui.onMap(kmap, error);
    }

    public KMap kmap() { return kmap; }

    /** Перечитать карту K из блока. */
    public boolean reloadMap() {
        if (demo) { kmap = KMap.demo(); if (ui != null) ui.onMap(kmap, null); return true; }
        if (link == null) return false;
        link.requestMap();
        return true;
    }

    @Override
    public void onFuelResult(AebLink src, boolean ok, String text) {
        if (src != link) return;
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
        Alerts.Alert fresh = alerts.onFrame(f);
        Alerts.Alert gasAlert = real ? alerts.onGasLevel(stats.gasLeftFrac(), gasL, rangeKm, f.timeMs) : null;
        if (fresh == null) fresh = gasAlert;
        if (fresh != null) beep(fresh.type);
        Alerts.Alert cur = alerts.current(f);
        boolean changed = (cur == null) != (alert == null) || (cur != null && cur.type != alert.type);
        alert = cur;
        if (ui != null) {
            ui.onFrame(f, fps);
            if (changed || cur != null) ui.onAlert(cur);
        }
        overlay.update(f, linkState, alert, demo);
        refreshNotification();
    }

    // ---------- топливо ----------

    public boolean requestFuel(boolean gas) {
        if (link == null || demo) return false;
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
            if (ui != null) ui.onLogState(true, logger.where());
            refreshNotification();
            return "Пишу в " + logger.where();
        } catch (IOException e) {
            return "Не удалось начать запись: " + e.getMessage();
        }
    }

    public String stopLog() {
        String w = logger.where();
        logger.stop();
        if (ui != null) ui.onLogState(false, w);
        refreshNotification();
        return "Запись сохранена: " + w;
    }

    // ---------- предупреждения ----------

    public void applySettings() {
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
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null || !lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) return;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, Looper.getMainLooper());
            gpsOn = true;
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private void stopGps() {
        if (lm != null && gpsOn) {
            try { lm.removeUpdates(gpsListener); } catch (SecurityException ignored) { }
        }
        gpsOn = false;
    }

    public boolean gpsEnabled() { return gpsOn; }

    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { stats.onLocation(location); }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    // ---------- демо ----------

    public boolean isDemo() { return demo; }

    public void setDemo(boolean on) {
        if (on == demo) return;
        if (on) {
            stopLink();
            demo = true;
            demoT0 = System.currentTimeMillis();
            trend.clear();
            setLink(AebLink.ST_ONLINE, "Демо-режим");
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
            setLink(AebLink.ST_CLOSED, "Кабель не подключён");
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
