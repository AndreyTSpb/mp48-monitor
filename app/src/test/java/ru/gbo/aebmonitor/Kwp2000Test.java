package ru.gbo.aebmonitor;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Кадры KWP2000: сборка, контрольная сумма, разбор реальных ответов ME17.9.7. */
public class Kwp2000Test {

    @Test
    public void buildsStartCommunication() {
        assertArrayEquals(
                hex("81 10 F1 81 03"),
                Kwp2000.request(Kwp2000.ECU_ADDRESS, Kwp2000.TESTER_ADDRESS, Kwp2000.START_COMMUNICATION));
    }

    @Test
    public void buildsIdentificationRequest() {
        assertArrayEquals(
                hex("82 10 F1 1A 90 2D"),
                Kwp2000.request(Kwp2000.ECU_ADDRESS, Kwp2000.TESTER_ADDRESS,
                        Kwp2000.READ_DATA_BY_COMMON_IDENTIFIER, 0x90));
    }

    @Test
    public void buildsBlockRequest() {
        assertArrayEquals(
                hex("82 10 F1 21 01 A5"),
                Kwp2000.request(Kwp2000.ECU_ADDRESS, Kwp2000.TESTER_ADDRESS,
                        Kwp2000.READ_DATA_BY_LOCAL_IDENTIFIER, 0x01));
    }

    @Test
    public void buildsSessionRequest() {
        assertArrayEquals(
                hex("83 10 F1 30 01 01 B6"),
                Kwp2000.request(Kwp2000.ECU_ADDRESS, Kwp2000.TESTER_ADDRESS, 0x30, 0x01, 0x01));
    }

    @Test
    public void parsesStartCommunicationResponse() {
        Kwp2000.Frame f = Kwp2000.parse(hex("83 F1 10 C1 EF 8F C3"));
        assertTrue(f.positive());
        assertTrue(f.checksumOk);
        assertEquals(0xC1, f.service());
        assertArrayEquals(new byte[]{(byte) 0xC1, (byte) 0xEF, (byte) 0x8F}, f.data);
    }

    @Test
    public void parsesLongBlockResponse() {
        byte[] data = new byte[131];
        data[0] = 0x61;
        data[1] = 0x01;
        for (int i = 2; i < data.length; i++) {
            data[i] = (byte) i;
        }
        byte[] frame = Kwp2000.request(0xF1, 0x10, toInts(data));
        assertEquals(0x80, frame[0] & 0xFF);
        assertEquals(131, frame[3] & 0xFF);
        Kwp2000.Frame f = Kwp2000.parse(frame);
        assertTrue(f.checksumOk);
        assertEquals(131, f.data.length);
        assertEquals(0x61, f.data[0] & 0xFF);
    }

    @Test
    public void detectsBadChecksum() {
        byte[] frame = hex("82 10 F1 21 01 A5");
        frame[frame.length - 1] = 0;
        Kwp2000.Frame f = Kwp2000.parse(frame);
        assertFalse(f.checksumOk);
    }

    @Test
    public void parsesNegativeResponse() {
        byte[] frame = Kwp2000.request(0xF1, 0x10, 0x7F, 0x21, 0x11);
        Kwp2000.Frame f = Kwp2000.parse(frame);
        assertFalse(f.positive());
        assertEquals(0x11, f.negativeCode());
    }

    private static int[] toInts(byte[] data) {
        int[] out = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[i] & 0xFF;
        }
        return out;
    }

    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }
}
