package ru.gbo.aebmonitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Вызовы API 26+ через отражение: проект собирается и Gradle (compileSdk 34),
 * и упрощённым скриптом против android.jar API 23.
 */
final class Compat {
    private Compat() {}

    static void createChannel(Context c, String id, String name) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Class<?> chCls = Class.forName("android.app.NotificationChannel");
            Object ch = chCls.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(id, name, 2 /* IMPORTANCE_LOW */);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationManager.class.getMethod("createNotificationChannel", chCls).invoke(nm, ch);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, channel);
            } catch (Exception ignored) {
            }
        }
        return new Notification.Builder(c);
    }

    static void startForegroundService(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i);
                return;
            } catch (Exception ignored) {
            }
        }
        c.startService(i);
    }
}
