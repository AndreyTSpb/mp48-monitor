package ru.gbo.aebmonitor;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;

/**
 * Задача-«сторож»: раз в 15 минут проверяет, работает ли служба, и поднимает её.
 * После перезагрузки JobScheduler восстанавливает задачу, поэтому автозапуск срабатывает
 * даже там, где BOOT_COMPLETED до приложения не доходит.
 */
public final class AutoStartJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!MonitorService.isRunning()) {
            DebugLog.log("boot", "плановая проверка: служба не работает, запускаю");
            try {
                Compat.startForegroundService(this, new Intent(this, MonitorService.class));
            } catch (RuntimeException e) {
                DebugLog.log("boot", "не удалось запустить службу: " + e);
            }
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
