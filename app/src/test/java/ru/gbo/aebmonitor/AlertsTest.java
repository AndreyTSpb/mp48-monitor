package ru.gbo.aebmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AlertsTest {

    private static Protocol.Frame f(long t, int fuel, float press, int rpm) {
        Protocol.Frame f = new Protocol.Frame();
        f.timeMs = t;
        f.fuel = fuel;
        f.pressBar = press;
        f.rpm = rpm;
        f.tRid = 30;
        return f;
    }

    @Test
    public void lowPressureNeedsTwoSeconds() {
        Alerts a = new Alerts();
        a.configure(0.9f, 6);
        assertNull(a.onFrame(f(0, Protocol.FUEL_GAS, 0.85f, 900)));
        assertNull(a.onFrame(f(1500, Protocol.FUEL_GAS, 0.85f, 900)));
        Alerts.Alert x = a.onFrame(f(2100, Protocol.FUEL_GAS, 0.85f, 900));
        assertNotNull(x);
        assertEquals(Alerts.LOW_PRESS, x.type);
        assertNull(a.onFrame(f(2200, Protocol.FUEL_GAS, 0.85f, 900)));   // не повторяется
    }

    @Test
    public void fallbackOnlyWithLowPressureAndNotCommanded() {
        Alerts a = new Alerts();
        a.configure(0.9f, 6);
        a.onFrame(f(0, Protocol.FUEL_GAS, 1.2f, 900));
        a.onFrame(f(100, Protocol.FUEL_GAS, 0.7f, 900));
        Alerts.Alert x = a.onFrame(f(200, Protocol.FUEL_PETROL, 0.7f, 900));
        assertNotNull(x);
        assertEquals(Alerts.FALLBACK, x.type);

        Alerts b = new Alerts();
        b.configure(0.9f, 6);
        b.onFrame(f(0, Protocol.FUEL_GAS, 1.2f, 900));
        assertNull(b.onFrame(f(100, Protocol.FUEL_PETROL, 1.2f, 900)));  // кнопка, давление в норме

        Alerts c = new Alerts();
        c.configure(0.9f, 6);
        c.onFrame(f(0, Protocol.FUEL_GAS, 0.7f, 900));
        c.onCommand(50);
        assertNull(c.onFrame(f(100, Protocol.FUEL_PETROL, 0.7f, 900)));  // своя команда
    }

    @Test
    public void notWarming() {
        Alerts a = new Alerts();
        a.configure(0.9f, 1);
        assertNull(a.onFrame(f(0, Protocol.FUEL_GAS_WAIT, 1.2f, 900)));
        assertNull(a.onFrame(f(59000, Protocol.FUEL_GAS_WAIT, 1.2f, 900)));
        Alerts.Alert x = a.onFrame(f(60500, Protocol.FUEL_GAS_WAIT, 1.2f, 900));
        assertNotNull(x);
        assertEquals(Alerts.NOT_WARMING, x.type);
    }

    @Test
    public void lowGasOncePerRefill() {
        Alerts a = new Alerts();
        assertNull(a.onGasLevel(Double.NaN, Double.NaN, Double.NaN, 0));      // нет калибровки
        assertNull(a.onGasLevel(0.40, 16, 100, 0));
        Alerts.Alert x = a.onGasLevel(0.14, 5.6, 34, 1000);
        assertNotNull(x);
        assertEquals(Alerts.LOW_GAS, x.type);
        assertNull(a.onGasLevel(0.10, 4, 24, 2000));                           // не повторяется
        assertNotNull(a.current(null));                                         // висит 20 с
        assertNull(a.onGasLevel(0.10, 4, 24, 22000));
        assertNull(a.current(null));
        assertNull(a.onGasLevel(1.0, 40, 240, 30000));                         // заправка — сброс
        assertNotNull(a.onGasLevel(0.12, 4.8, 29, 40000));
    }
}
