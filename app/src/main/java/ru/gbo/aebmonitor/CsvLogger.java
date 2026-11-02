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

/**
 * Запись текущих параметров в текстовый файл (табуляция, точка — как txt AEB).
 * Android 10+: папка «Загрузки/AEB» (Download/AEB). Старее — папка приложения.
 */
public final class CsvLogger {
    private BufferedWriter w;
    private long t0;
    private long lastFlush;
    private String where;

    public boolean isOpen() {
        return w != null;
    }

    public String where() {
        return where;
    }

    public void start(Context ctx) throws IOException {
        stop();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String name = "aeb-" + stamp + ".txt";
        OutputStream os;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put("_display_name", name);
            v.put("mime_type", "text/plain");
            v.put("relative_path", "Download/AEB");
            Uri uri = cr.insert(Uri.parse("content://media/external/downloads"), v);
            if (uri == null) throw new IOException("Не удалось создать файл в «Загрузках»");
            os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("Не удалось открыть файл");
            where = "Загрузки/AEB/" + name;
        } else {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File f = new File(dir, name);
            os = new FileOutputStream(f);
            where = f.getAbsolutePath();
        }
        w = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
        w.write("ГБО Монитор, запись " + stamp + "\n");
        w.write("TEMPO\tGIRI\tLAMBDA\tGAS\tBENZ\tPRESS\tMAP\tT.RID\tT.GAS\tSTATO\tRAW\n");
        t0 = 0;
        lastFlush = System.currentTimeMillis();
    }

    public void write(Protocol.Frame f) {
        if (w == null || f == null) return;
        if (t0 == 0) t0 = f.timeMs;
        try {
            w.write(String.format(Locale.US, "%d\t%d\t%.2f\t%.2f\t%.2f\t%.3f\t%.3f\t%.1f\t%.1f\t%02X\t%s\n",
                    f.timeMs - t0, f.rpm, f.lambdaV, f.gasMs, f.benzMs, f.pressBar, f.mapBar,
                    f.tRid, f.tGas, f.status, Protocol.hex(f.raw)));
            long now = System.currentTimeMillis();
            if (now - lastFlush > 2000) {
                w.flush();
                lastFlush = now;
            }
        } catch (IOException e) {
            stop();
        }
    }

    public void stop() {
        if (w != null) {
            try { w.close(); } catch (IOException ignored) { }
        }
        w = null;
    }
}
