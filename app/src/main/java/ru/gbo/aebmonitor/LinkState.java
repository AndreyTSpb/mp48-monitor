package ru.gbo.aebmonitor;

/**
 * Состояние связи с блоком. Отдельный класс без Android: экраны и служба зависят
 * от этих констант, а не от потока обмена {@link AebLink} с его USB-классами.
 */
public final class LinkState {
    private LinkState() {}

    public static final int OPENING = 0;    // открываем порт / ждём разрешение USB
    public static final int NO_ECU = 1;     // порт открыт, блок не отвечает
    public static final int ONLINE = 2;     // идёт обмен
    public static final int ERROR = 3;      // сбой связи
    public static final int CLOSED = 4;     // кабель не подключён / обмен остановлен
}
