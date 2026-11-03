package ru.gbo.aebmonitor;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Список сохранённых записей (Загрузки/AEB на Android 10+, иначе папка приложения). */
public final class Recordings {
    private Recordings() {}

    public static final class Entry {
        public final String name;
        public final Uri uri;       // content:// (MediaStore) или file://
        public final long size;
        public final long dateMs;

        Entry(String name, Uri uri, long size, long dateMs) {
            this.name = name;
            this.uri = uri;
            this.size = size;
            this.dateMs = dateMs;
        }
    }

    public static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            // MediaStore.Downloads (API 29+) через строковые имена столбцов — чтобы код
            // собирался и упрощённым скриптом против android.jar API 23.
            Uri coll = Uri.parse("content://media/external/downloads");
            String[] proj = {"_id", "_display_name", "_size", "date_modified"};
            try (Cursor cr = ctx.getContentResolver().query(coll, proj,
                    "_display_name LIKE 'aeb-%'", null, "date_modified DESC")) {
                if (cr != null) {
                    int iId = cr.getColumnIndexOrThrow("_id");
                    int iName = cr.getColumnIndexOrThrow("_display_name");
                    int iSize = cr.getColumnIndexOrThrow("_size");
                    int iDate = cr.getColumnIndexOrThrow("date_modified");
                    while (cr.moveToNext()) {
                        long id = cr.getLong(iId);
                        Uri u = Uri.withAppendedPath(coll, String.valueOf(id));
                        out.add(new Entry(cr.getString(iName), u, cr.getLong(iSize), cr.getLong(iDate) * 1000));
                    }
                }
            } catch (Exception ignored) {
            }
        } else {
            File dir = ctx.getExternalFilesDir(null);
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.getName().startsWith("aeb-"))
                        out.add(new Entry(f.getName(), Uri.fromFile(f), f.length(), f.lastModified()));
                }
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(b.dateMs, a.dateMs); }
        });
        return out;
    }

    /** @return true, если файл удалён. */
    public static boolean delete(Context ctx, Entry e) {
        if ("file".equals(e.uri.getScheme())) {
            File f = new File(e.uri.getPath());
            return f.delete();
        }
        try {
            ContentResolver cr = ctx.getContentResolver();
            return cr.delete(e.uri, null, null) > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f МБ", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(java.util.Locale.US, "%.0f КБ", bytes / 1024.0);
        return bytes + " Б";
    }
}
