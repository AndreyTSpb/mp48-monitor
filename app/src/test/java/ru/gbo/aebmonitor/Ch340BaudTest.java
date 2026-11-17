package ru.gbo.aebmonitor;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/** Формула скорости CH340: значения регистров для K-line 10 400 и 9600. */
public class Ch340BaudTest {

    @Test
    public void baud10400() {
        assertArrayEquals(new int[]{0xB882, 0x0C}, Ch340Baud.registers(10400));
    }

    @Test
    public void baud9600() {
        assertArrayEquals(new int[]{0xB282, 0x0C}, Ch340Baud.registers(9600));
    }

    /** Значения драйвера Windows (USBPcap OpenDiag): 10 400 = B882/07, 205 = C680/FC. */
    @Test
    public void windows10400() {
        assertArrayEquals(new int[]{0xB882, 0x07}, Ch340Baud.windows(10400));
    }

    @Test
    public void windows205() {
        assertArrayEquals(new int[]{0xC680, 0xFC}, Ch340Baud.windows(205));
    }
}
