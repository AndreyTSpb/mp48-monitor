package ru.gbo.aebmonitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Запись текущих параметров в текстовый файл (табуляция, точка — как txt AEB).
 * Android 10+: папка «Загрузки/AEB» (Download/AEB). Старее — папка приложения.
 *
 * Кадры приходят 10 раз в секунду в главном потоке, поэтому запись в файл идёт
 * в отдельном потоке: {@link #write} только форматирует строку и кладёт её в очередь.
 * Файл в MediaStore создаётся «незавершённым» (is_pending) и публикуется после закрытия —
 * так в «Загрузках» не появляется недописанный файл.
 *
 * Каждый запуск записи — отдельный {@link Run} со своей очередью и потоком, поэтому
 * остановка и немедленный новый старт не перемешивают файлы.
 */
public final class CsvLogger {
    private static final int QUEUE_CAP = 512;          // ~50 с записи; переполнение не тормозит поток обмена
    private static final long FLUSH_MS = 2000;
    private static final String EOF = new String("\u0000EOF");

    /** Одна сессия записи: свой файл, очередь и поток. */
    private static final class Run {
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAP);
        final BufferedWriter out;
        final Uri uri;
        final Context app;
        volatile boolean open = true;
        volatile IOException error;
        long t0;
        long dropped;

        Run(BufferedWriter out, Uri uri, Context app) {
            this.out = out;
            this.uri = uri;
            this.app = app;
        }
    }

    private volatile Run run;
    private volatile Thread thread;
    private volatile String where;

    /** Запись идёт (файл открыт, поток записи работает). */
    public boolean isOpen() {
        Run r = run;
        return r != null && r.open;
    }

    public String where() {
        return where;
    }

    /** Запись прервалась из-за ошибки диска/потока. */
    public boolean failed() {
        Run r = run;
        return r != null && r.error != null;
    }

    /** Текст ошибки (и сброс флага), либо null. */
    public String takeFailure() {
        Run r = run;
        if (r == null) return null;
        IOException e = r.error;
        r.error = null;
        return e == null ? null : e.getMessage();
    }

    /** Сколько строк выброшено из-за переполнения очереди (медленный диск). */
    public long dropped() {
        Run r = run;
        return r == null ? 0 : r.dropped;
    }

    public void start(Context ctx) throws IOException {
        stop();
        Context app = ctx.getApplicationContext();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String name = "aeb-" + stamp + ".txt";
        OutputStream os;
        Uri uri = null;
        String path;
        ContentResolver cr = Build.VERSION.SDK_INT >= 29 ? app.getContentResolver() : null;
        if (cr != null) {
            ContentValues v = new ContentValues();
            v.put("_display_name", name);
            v.put("mime_type", "text/plain");
            v.put("relative_path", "Download/AEB");
            v.put("is_pending", 1);                    // файл появится в «Загрузках» после закрытия
            uri = cr.insert(Uri.parse("content://media/external/downloads"), v);
            if (uri == null) throw new IOException("Не удалось создать файл в «Загрузках»");
            os = cr.openOutputStream(uri);
            if (os == null) {
                tryDelete(cr, uri);
                throw new IOException("Не удалось открыть файл");
            }
            path = "Загрузки/AEB/" + name;
        } else {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) dir = app.getFilesDir();
            File f = new File(dir, name);
            os = new FileOutputStream(f);
            path = f.getAbsolutePath();
        }

        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
        try {
            w.write(CsvFormat.title(stamp));
            w.write('\n');
            w.write(CsvFormat.HEADER);
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            closeQuietly(w);
            if (uri != null) tryDelete(cr, uri);
            throw e;
        }

        Run r = new Run(w, uri, app);
        run = r;
        where = path;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(r); }
        }, "CsvLogger");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Поставить кадр в очередь записи. Вызывается из главного потока — не блокирует. */
    public void write(Protocol.Frame f, float speedKmh, Eco eco, double gasL, double rangeKm) {
        Run r = run;
        if (r == null || !r.open || f == null) return;
        if (r.t0 == 0) r.t0 = f.timeMs;
        if (!r.queue.offer(CsvFormat.line(f.timeMs - r.t0, f, speedKmh, eco, gasL, rangeKm))) r.dropped++;
    }

    private void loop(Run r) {
        BufferedWriter w = r.out;
        long lastFlush = System.currentTimeMillis();
        try {
            while (true) {
                String s;
                try {
                    s = r.queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (s == null) {
                    if (System.currentTimeMillis() - lastFlush > FLUSH_MS) {
                        w.flush();
                        lastFlush = System.currentTimeMillis();
                    }
                    if (!r.open && r.queue.isEmpty()) break;
                    continue;
                }
                if (s == EOF) break;
                w.write(s);
                w.write('\n');
                if (System.currentTimeMillis() - lastFlush > FLUSH_MS) {
                    w.flush();
                    lastFlush = System.currentTimeMillis();
                }
            }
            w.flush();
        } catch (IOException e) {
            r.error = e;
        } finally {
            closeQuietly(w);
            r.open = false;
            publish(r);
        }
    }

    /** Завершить запись и дождаться, пока поток допишет и закроет файл. */
    public void stop() {
        Run r = run;
        Thread t = thread;
        run = null;
        thread = null;
        if (r == null) return;
        r.open = false;
        r.queue.clear();
        r.queue.offer(EOF);
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) closeQuietly(r.out);
        }
    }

    /** Снять с файла признак «незавершённый», чтобы он стал виден другим приложениям. */
    private static void publish(Run r) {
        if (r.uri == null || r.app == null) return;
        try {
            ContentValues v = new ContentValues();
            v.put("is_pending", 0);
            r.app.getContentResolver().update(r.uri, v, null, null);
        } catch (Exception ignored) {
        }
    }

    private static void tryDelete(ContentResolver cr, Uri uri) {
        try { cr.delete(uri, null, null); } catch (Exception ignored) { }
    }

    private static void closeQuietly(BufferedWriter w) {
        try { w.close(); } catch (IOException ignored) { }
    }
}
