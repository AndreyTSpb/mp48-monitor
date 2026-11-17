package ru.gbo.aebmonitor;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/**
 * Плановая проверка автозапуска через JobScheduler.
 *
 * Некоторые прошивки магнитол не доставляют BOOT_COMPLETED сторонним приложениям
 * (или требуют добавить приложение в свой список автозапуска). Задача, сохранённая
 * с setPersisted(true), восстанавливается системой после перезагрузки и поднимает
 * службу, даже если широковещательное сообщение о загрузке не дошло.
 */
public final class AutoStart {
    private static final int JOB_ID = 48;
    private static final long PERIOD_MS = 15 * 60 * 1000L;   // минимальный период JobScheduler

    private AutoStart() {}

    public static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, AutoStartJobService.class))
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS);
            int result = js.schedule(b.build());
            if (result == JobScheduler.RESULT_SUCCESS) {
                DebugLog.log("boot", "плановая проверка автозапуска запланирована");
            } else {
                DebugLog.log("boot", "проверка автозапуска не запланирована, код " + result);
            }
        } catch (RuntimeException e) {
            DebugLog.log("boot", "не удалось запланировать проверку автозапуска: " + e);
        }
    }

    /** Отменить проверку: вызывается при явном «Выходе», чтобы приложение не воскресало. */
    public static void cancel(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(JOB_ID);
        DebugLog.log("boot", "плановая проверка автозапуска отменена");
    }
}
