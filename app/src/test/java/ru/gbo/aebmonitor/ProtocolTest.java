package ru.gbo.aebmonitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Проверка разбора протокола на кадрах из записей USB (02.11.2026) против txt-лога штатной программы. */
public class ProtocolTest {

    private static byte[] h(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    public void realtimeFramesMatchLog() throws Exception {
        InputStream in = getClass().getResourceAsStream("/vectors.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int n = 0;
        int[] ok = new int[8];
        double[] tol = {0.5, 0.025, 0.006, 0.006, 0.011, 0.011, 1.01, 1.01};
        while ((line = r.readLine()) != null) {
            String[] p = line.trim().split(" ");
            if (p.length < 9) continue;
            byte[] rx = h(p[0]);
            Protocol.Reply rep = null;
            for (int k = 1; k <= rx.length; k++) {
                rep = Protocol.parse(Protocol.CMD_REALTIME, rx, k);
                if (k < rx.length) assertNull("ответ раньше конца кадра", rep);
            }
            assertEquals(Protocol.Reply.OK, rep.kind);
            Protocol.Frame f = Protocol.decode(rep.data, 0);
            double[] got = {f.rpm, f.lambdaV, f.gasMs, f.benzMs, f.pressBar, f.mapBar, Math.floor(f.tRid), Math.floor(f.tGas)};
            for (int i = 0; i < 8; i++) if (Math.abs(got[i] - Double.parseDouble(p[i + 1])) <= tol[i]) ok[i]++;
            n++;
        }
        assertTrue(n > 1000);
        for (int i = 0; i < 8; i++) assertTrue("поле " + i + ": " + ok[i] + "/" + n, ok[i] >= n * 0.995);
    }

    @Test
    public void errorsAndChecksums() {
        byte[] err = h("000202ca0101cc");
        Protocol.Reply r = Protocol.parse(Protocol.CMD_IDENT, err, err.length);
        assertEquals(Protocol.Reply.ERROR, r.kind);
        assertEquals(1, r.errCode);
        byte[] noEcho = h("5304fc4f340be1");
        assertEquals(Protocol.Reply.OK, Protocol.parse(Protocol.CMD_IDENT, noEcho, noEcho.length).kind);
        byte[] bad = h("0002025304fc4f340be2");
        assertEquals(Protocol.Reply.BAD, Protocol.parse(Protocol.CMD_IDENT, bad, bad.length).kind);
    }

    @Test
    public void multiplierIgnoresTransientFrames() {
        byte[] d = new byte[34];
        d[6] = (byte) 0x40; d[7] = 0x0E;   // газ ≈ 9,3 мс
        d[8] = (byte) 0x92; d[9] = 0x01;   // бензин ≈ 1,0 мс (короткий импульс)
        d[11] = (byte) 0xB1;
        Protocol.Frame f = Protocol.decode(d, 0);
        assertEquals(Protocol.FUEL_GAS, f.fuel);
        assertTrue(Float.isNaN(f.multiplier()));
        d[11] = (byte) 0xB0;
        d[8] = (byte) 0x20; d[9] = 0x0B;   // бензин ≈ 7,2 мс
        assertEquals(1.29f, Protocol.decode(d, 0).multiplier(), 0.01f);
    }

    @Test
    public void fuelSequenceMatchesStockSoftware() {
        byte[] petrol = {(byte) 0x84, 0x24, 0x09, 0x00};
        byte[] gas = {(byte) 0x84, 0x34, 0x09, 0x00};
        String[] toGas = {"35 03 00 84 24 09 00 E9", "35 03 00 84 2C 09 00 F1", "35 03 00 84 3C 09 00 01", "35 03 00 84 34 09 00 F9"};
        String[] toPetrol = {"35 03 00 84 34 09 00 F9", "35 03 00 84 3C 09 00 01", "35 03 00 84 2C 09 00 F1", "35 03 00 84 24 09 00 E9"};
        byte[][] s1 = Protocol.fuelSequence(petrol, true), s2 = Protocol.fuelSequence(gas, false);
        for (int i = 0; i < 4; i++) {
            assertEquals(toGas[i], Protocol.hex(Protocol.writeVar(3, s1[i])));
            assertEquals(toPetrol[i], Protocol.hex(Protocol.writeVar(3, s2[i])));
        }
        assertArrayEquals(h("2903002c"), Protocol.readVar(3));
    }

    /**
     * Байт состояния (записи 04.11 и 05.11.2026): 0x88/0xA8 — ждёт перехода (бит 0x20 — таймер после пуска,
     * на переход не влияет), 0x90/0xB0 — газ, 0x80/0x00 при оборотах 0 — двигатель заглушён.
     */
    @Test
    public void fuelStateBits() {
        int[] st = {0xA0, 0x88, 0xA8, 0x90, 0xB0, 0x80, 0x00};
        int[] rpm = {850, 850, 850, 900, 900, 0, 0};
        int[] fuel = {Protocol.FUEL_PETROL, Protocol.FUEL_GAS_WAIT, Protocol.FUEL_GAS_WAIT,
                Protocol.FUEL_GAS, Protocol.FUEL_GAS, Protocol.FUEL_PETROL, Protocol.FUEL_PETROL};
        int[] valve = {Protocol.VALVE_CLOSED, Protocol.VALVE_FILL, Protocol.VALVE_FILL,
                Protocol.VALVE_OPEN, Protocol.VALVE_OPEN, Protocol.VALVE_CLOSED, Protocol.VALVE_CLOSED};
        for (int i = 0; i < st.length; i++) {
            byte[] d = new byte[Protocol.REALTIME_LEN];
            d[0] = (byte) (rpm[i] & 0xFF);
            d[1] = (byte) (rpm[i] >> 8);
            d[11] = (byte) st[i];
            Protocol.Frame f = Protocol.decode(d, 0);
            assertEquals(fuel[i], f.fuel);
            assertEquals(valve[i], f.valve());
            assertEquals(rpm[i] == 0, f.engineStopped());
        }
    }
}
