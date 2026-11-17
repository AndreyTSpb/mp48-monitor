package ru.gbo.aebmonitor;

import android.app.Application;

/**
 * Точка входа процесса: сразу открывает отладочный лог (если он включён),
 * чтобы были видны даже те запуски, где служба не успела стартовать,
 * и планирует плановую проверку автозапуска.
 */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Prefs prefs = new Prefs(this);
        if (prefs.debug() && !DebugLog.active()) DebugLog.start(this);
        DebugLog.log("app", "старт процесса, автозапуск " + (prefs.autoStart() ? "вкл" : "выкл"));
        if (prefs.autoStart()) AutoStart.schedule(this);
    }
}
