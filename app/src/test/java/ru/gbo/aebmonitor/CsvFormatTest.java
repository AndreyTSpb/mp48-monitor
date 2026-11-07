package ru.gbo.aebmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Формат файла записи: шапка, число колонок, пустые значения вместо NaN. */
public class CsvFormatTest {

    private static Protocol.Frame frame() {
        Protocol.Frame f = new Protocol.Frame();
        f.rpm = 2500;
        f.lambdaV = 0.5f;
        f.gasMs = 5.5f;
        f.benzMs = 4.25f;
        f.pressBar = 1.234f;
        f.mapBar = 0.456f;
        f.tRid = 51.4f;
        f.tGas = 8.2f;
        f.status = 0xB0;
        f.raw = new byte[Protocol.REALTIME_LEN];
        return f;
    }

    @Test
    public void headerHasEighteenColumns() {
        String[] c = CsvFormat.HEADER.split("\t", -1);
        assertEquals(CsvFormat.COLUMNS, c.length);
        assertEquals("TEMPO", c[0]);
        assertEquals("RAW", c[10]);
        assertEquals("SPEED", c[11]);
        assertEquals("RANGE_KM", c[17]);
    }

    @Test
    public void rowMatchesHeaderColumns() {
        Protocol.Frame f = frame();
        Eco eco = new Eco();
        eco.l100 = 12.34f;
        eco.lh = 1.5f;
        eco.ratio = 1.05f;
        eco.level = Eco.LEVEL_OK;
        String[] c = CsvFormat.line(1234, f, 72.4f, eco, 38.5, 210.0).split("\t", -1);
        assertEquals(CsvFormat.COLUMNS, c.length);
        assertEquals("1234", c[0]);
        assertEquals("2500", c[1]);
        assertEquals("0.50", c[2]);
        assertEquals("5.50", c[3]);
        assertEquals("4.25", c[4]);
        assertEquals("1.234", c[5]);
        assertEquals("0.456", c[6]);
        assertEquals("51.4", c[7]);
        assertEquals("8.2", c[8]);
        assertEquals("B0", c[9]);
        assertEquals(101, c[10].length());       // 34 байта через пробел
        assertEquals("72.4", c[11]);
        assertEquals("12.3", c[12]);
        assertEquals("1.50", c[13]);
        assertEquals("1.050", c[14]);
        assertEquals("1", c[15]);
        assertEquals("38.50", c[16]);
        assertEquals("210", c[17]);
    }

    @Test
    public void missingDataLeavesEmptyColumns() {
        String[] c = CsvFormat.line(0, frame(), Float.NaN, null, Double.NaN, Double.NaN).split("\t", -1);
        assertEquals(CsvFormat.COLUMNS, c.length);
        assertEquals("", c[11]);
        for (int i = 12; i <= 17; i++) assertEquals("колонка " + i, "", c[i]);
        assertTrue(c[10].startsWith("00 00"));
    }

    @Test
    public void statusIsUppercaseHex() {
        Protocol.Frame f = frame();
        f.status = 0x0A;
        String[] c = CsvFormat.line(0, f, 0f, null, Double.NaN, Double.NaN).split("\t", -1);
        assertEquals("0A", c[9]);
    }
}
