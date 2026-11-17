package ru.gbo.aebmonitor;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Проверка раскладки блока 21 01 по реальным блокам из лога OpenDiag 09.11.2026
 * и скриншотам параметров (ХХ 868 об/мин и 1684 об/мин).
 */
public class EcuBlockTest {

    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    /** ХХ, 14:41:30: обороты 868, желаемые 860, ОЖ 60,0, УОЗ 6,8, катушка 3,22, воздух 18. */
    private static final String IDLE =
            "61 01 0d 8d 0d 70 ff e3 00 91 80 6e 3f 09 09 09 09 0c bc 00 b3 00 29 00 00 00 7e "
                    + "02 25 03 4d 01 a4 01 f7 fb 60 80 00 02 00 80 80 00 00 68 00 00 82 86 ff ff "
                    + "00 92 00 4a 00 7f 03 80 51 5c 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 "
                    + "00 00 20 00 0c 00 32 01 ab 00 00 00 00 17 d7 12 30 0e 4e 1b 3c 17 60 11 50 "
                    + "0c a0 1d d0 00 08 00 00 02 f2 00 00 00 00 00 00 00 05 ff ff ff ff ff 00 00 "
                    + "01 1e 23 50";

    /** 1684 об/мин, 14:45:34: желаемые 1520, ОЖ 76,5, УОЗ 31,5/30,8, катушка 3,23, воздух 29. */
    private static final String HIGH =
            "61 01 1a 44 17 c0 fd 7c 00 a6 80 67 3f 2a 29 29 2a 0c a0 01 1e 00 2e 25 60 00 c5 "
                    + "01 c2 02 b7 01 59 00 e4 fa fd 7e e7 47 ff 80 80 00 00 6a 00 00 32 88 ff ff "
                    + "01 15 00 8c 00 8c 03 72 88 5c 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 "
                    + "00 00 20 00 0c 00 32 01 ac 00 00 00 00 38 0b 1f 7b 17 49 26 80 3c 70 1d 50 "
                    + "18 80 1a 70 00 00 00 00 04 a4 00 00 00 00 00 00 00 05 ff ff ff ff ff 00 00 "
                    + "00 e6 1c 8b";

    @Test
    public void decodesIdleBlock() {
        byte[] data = hex(IDLE);
        assertEquals(EcuBlock.LENGTH, data.length);
        assertTrue(EcuBlock.looksLikeBlock(data, 0));

        EcuBlock b = EcuBlock.decode(data, 0);
        assertEquals(867.25f, b.rpm, 0.001f);
        assertEquals(860f, b.desiredIdle, 0.001f);
        assertEquals(65507, b.idleErrorRaw);
        assertEquals(-7.25f, b.idleError(), 0.001f);   // 860 − 867,25
        assertEquals(145, b.coolantRaw);
        assertArrayEquals(new float[]{6.75f, 6.75f, 6.75f, 6.75f}, b.uoz, 0.001f);
        assertEquals(3.26f, b.coilMs, 0.001f);
        assertEquals(17.9f, b.mafKgH, 0.001f);
        assertEquals(0, b.mapRaw);
        assertEquals(3.5649f, b.injectionMs, 0.002f);
    }

    @Test
    public void decodesHighRpmBlock() {
        byte[] data = hex(HIGH);
        assertEquals(EcuBlock.LENGTH, data.length);

        EcuBlock b = EcuBlock.decode(data, 0);
        assertEquals(1681f, b.rpm, 0.001f);
        assertEquals(1520f, b.desiredIdle, 0.001f);
        assertEquals(64892, b.idleErrorRaw);
        assertEquals(-161f, b.idleError(), 0.001f);    // 1520 − 1681
        assertEquals(166, b.coolantRaw);
        assertArrayEquals(new float[]{31.5f, 30.75f, 30.75f, 31.5f}, b.uoz, 0.001f);
        assertEquals(3.232f, b.coilMs, 0.001f);
        assertEquals(28.6f, b.mafKgH, 0.001f);
        assertEquals(9568, b.mapRaw);
        assertEquals(2.922f, b.injectionMs, 0.002f);
    }

    @Test
    public void decodeWorksAtOffset() {
        byte[] block = hex(IDLE);
        byte[] wrapped = new byte[block.length + 7];
        System.arraycopy(block, 0, wrapped, 5, block.length);
        EcuBlock b = EcuBlock.decode(wrapped, 5);
        assertEquals(867.25f, b.rpm, 0.001f);
    }

    @Test
    public void rejectsForeignData() {
        byte[] wrong = hex(HIGH);
        wrong[0] = 0x62;
        assertFalse(EcuBlock.looksLikeBlock(wrong, 0));
        try {
            EcuBlock.decode(wrong, 0);
            throw new AssertionError("ожидалось исключение");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
