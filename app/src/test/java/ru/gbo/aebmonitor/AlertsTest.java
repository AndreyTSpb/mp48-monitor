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
}
