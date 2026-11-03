package ru.gbo.aebmonitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/** Карта K: команды как у штатной программы, разбор осей из записи USB, интерполяция. */
public class KMapTest {

    private static byte[] h(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    public void requestsMatchCapture() {
        assertArrayEquals(h("2a 54 00 00 7e"), Protocol.readMapRow(0));
        assertArrayEquals(h("2a 54 00 0c 8a"), Protocol.readMapRow(12));
        assertArrayEquals(h("29 37 00 60"), Protocol.readVar(Protocol.MAP_AXIS_TB));
        assertArrayEquals(h("29 3d 00 66"), Protocol.readVar(Protocol.MAP_AXIS_RPM));
    }

    @Test
    public void axesAndInterpolation() {
        int[] tb = Protocol.axis(h("0d03d10394045705de0628093 50c420f4f125d156a18771b".replace(" ", "")));
        int[] rp = Protocol.axis(h("f401e803dc05d007c409b80bac0da00f941188137c157017"));
        assertNotNull(tb);
        assertEquals(781, tb[0]);
        assertEquals(7031, tb[11]);
        assertEquals(6000, rp[11]);
        // карта v6 (совпадает с чтением 2A 54 00 i в записи)
        int[][] v6 = {
                {136, 136, 136, 136, 136, 136, 137, 138, 138, 138, 138, 138},
                {138, 138, 138, 138, 138, 138, 139, 141, 141, 141, 141, 141},
                {150, 150, 150, 150, 150, 150, 151, 152, 152, 152, 152, 152},
                {168, 168, 163, 163, 163, 163, 163, 163, 163, 163, 163, 163},
                {171, 171, 169, 170, 170, 170, 170, 170, 170, 170, 170, 170},
                {159, 159, 160, 161, 161, 161, 166, 172, 172, 172, 172, 172},
                {153, 153, 154, 155, 155, 155, 160, 164, 164, 164, 164, 164},
                {147, 147, 147, 147, 147, 151, 155, 164, 167, 167, 167, 167},
                {143, 143, 143, 143, 143, 147, 153, 159, 161, 161, 161, 161},
                {132, 132, 132, 132, 132, 137, 142, 152, 155, 155, 155, 155},
                {132, 132, 132, 132, 132, 137, 142, 152, 155, 155, 155, 155},
                {130, 130, 130, 130, 130, 133, 140, 147, 150, 150, 150, 150},
                {130, 130, 130, 130, 130, 133, 140, 147, 150, 150, 150, 150}};
        byte[][] rows = new byte[13][12];
        for (int r = 0; r < 13; r++) for (int c = 0; c < 12; c++) rows[r][c] = (byte) v6[r][c];
        KMap m = KMap.fromRaw(tb, rp, rows);
        assertNotNull(m);
        assertEquals(2.0f, m.tbMs[0], 0.01f);
        assertEquals(18.0f, m.tbMs[11], 0.01f);
        // ровно в узле: Tб 4,5 мс (строка 4), 1000 об/мин (столбец 1) → 1,71
        assertEquals(1.71f, m.locate(1758 / 390.625f, 1000).k, 0.001f);
        // посередине между 3,5 и 4,5 мс на 1000 об/мин: (1,68 + 1,71) / 2
        assertEquals(1.695f, m.locate(4.0f, 1000).k, 0.002f);
        // за краями — значение крайней ячейки
        assertEquals(1.36f, m.locate(0.5f, 100).k, 0.001f);
        assertEquals(1.50f, m.locate(25f, 7000).k, 0.001f);
    }
}
