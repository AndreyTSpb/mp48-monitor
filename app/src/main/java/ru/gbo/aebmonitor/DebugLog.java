package ru.gbo.aebmonitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Отладочный лог приложения. Когда в меню включён «Отладочный лог», события работы
 * и все всплывающие сообщения пишутся в «Загрузки/AEB/aeb-log-ДАТА.txt».
 * Файл виден в «Записях» и отправляется как обычная запись.
 *
 * Лог пишется сразу (без is_pending): если приложение упадёт, файл останется на месте.
 */
public final class DebugLog {
    private static final String TAG = "GboMonitor";
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static Writer out;
    private static String where;
    private static boolean crashHandler;

    private DebugLog() {}

    public static synchronized boolean active() {
        return out != null;
    }

    /** Путь к текущему логу или null. */
    public static String where() {
        return where;
    }

    public static synchronized void start(Context ctx) {
        if (out != null) stop();
        Context c = ctx.getApplicationContext();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String name = "aeb-log-" + stamp + ".txt";
        OutputStream os = null;
        String path = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put("_display_name", name);
                v.put("mime_type", "text/plain");
                v.put("relative_path", "Download/AEB");
                Uri uri = cr.insert(Uri.parse("content://media/external/downloads"), v);
                if (uri != null) {
                    os = cr.openOutputStream(uri);
                    path = "Загрузки/AEB/" + name;
                }
            }
            if (os == null) {
                File dir = c.getExternalFilesDir(null);
                if (dir == null) dir = c.getFilesDir();
                File f = new File(dir, name);
                os = new FileOutputStream(f);
                path = f.getAbsolutePath();
            }
            out = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            where = path;
            installCrashHandler();
            log("app", "=== лог открыт: " + path + " ===");
            log("app", "версия " + version(c) + ", " + Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        } catch (IOException | RuntimeException e) {
            close();
            where = null;
            try { if (os != null) os.close(); } catch (IOException ignored) { }
            Log.w(TAG, "не удалось открыть отладочный лог", e);
        }
    }

    public static synchronized void stop() {
        if (out == null) return;
        log("app", "=== лог закрыт ===");
        close();
        where = null;
    }

    /** Записать событие. Если лог выключен — ничего не делает. */
    public static synchronized void log(String tag, String message) {
        if (out == null || message == null) return;
        try {
            out.write(TIME.format(new Date()));
            out.write(' ');
            out.write(tag);
            out.write(": ");
            out.write(message);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            close();
        }
        Log.d(TAG, tag + ": " + message);
    }

    private static void close() {
        Writer w = out;
        out = null;
        if (w != null) {
            try { w.close(); } catch (IOException ignored) { }
        }
    }

    private static String version(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName + " (" + p.versionCode + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    /** Падение тоже должно попасть в лог, если отладка включена. */
    private static void installCrashHandler() {
        if (crashHandler) return;
        crashHandler = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                log("crash", "поток " + t.getName() + ": " + e);
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                log("crash", sw.toString());
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
}
