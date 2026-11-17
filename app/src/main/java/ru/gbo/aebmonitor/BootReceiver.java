package ru.gbo.aebmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Автозапуск после включения магнитолы и после обновления приложения.
 *
 * По умолчанию — «тихий старт»: поднимается только служба (связь, статистика, запись,
 * мини-панель), окно не открывается. Галочка «Открывать окно при автозапуске» включает окно.
 * Если прошивка не присылает BOOT_COMPLETED, службу поднимет {@link AutoStartJobService}.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        boolean unlock = Intent.ACTION_USER_UNLOCKED.equals(action);
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !unlock && !replaced) return;

        Prefs prefs = new Prefs(ctx);
        DebugLog.log("boot", "получено: " + action + ", автозапуск " + (prefs.autoStart() ? "вкл" : "выкл"));
        if (!prefs.autoStart()) return;

        try {
            Compat.startForegroundService(ctx, new Intent(ctx, MonitorService.class)
                    .putExtra(MonitorService.EXTRA_BOOT, true));
            DebugLog.log("boot", "служба запущена");
        } catch (RuntimeException e) {
            DebugLog.log("boot", "служба не запустилась: " + e);
        }

        if (boot && prefs.autoStartUi()) {
            try {
                ctx.startActivity(new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (RuntimeException e) {
                DebugLog.log("boot", "окно не открылось: " + e);
            }
        }
    }
}
