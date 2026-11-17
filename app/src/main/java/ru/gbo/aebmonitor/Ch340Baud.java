package ru.gbo.aebmonitor;

/**
 * Формула скорости CH340/CH341: регистры 0x1312 (старший байт) и 0x0F2C (младший).
 * Вынесена отдельно, чтобы проверялась юнит-тестами без Android.
 */
public final class Ch340Baud {

    private static final long BAUDBASE_FACTOR = 1532620800L;
    private static final int BAUDBASE_DIVMAX = 3;

    private Ch340Baud() {
    }

    /** @return {значение для 0x1312, значение для 0x0F2C} */
    public static int[] registers(int baud) {
        if (baud <= 0) throw new IllegalArgumentException("Скорость: " + baud);
        long factor = BAUDBASE_FACTOR / baud;
        int divisor = BAUDBASE_DIVMAX;
        while (factor > 0xFFF0 && divisor > 0) {
            factor >>= 3;
            divisor--;
        }
        if (factor > 0xFFF0) throw new IllegalArgumentException("Скорость не поддерживается: " + baud);
        factor = 0x10000 - factor;
        divisor |= 0x80;
        return new int[]{(int) ((factor & 0xFF00) | divisor), (int) (factor & 0xFF)};
    }

    /**
     * Значения регистров, которые пишет драйвер CH340 для Windows (USBPcap OpenDiag).
     * Для 10 400 и 205 они отличаются от общей формулы: 0x0F2C = 07 и FC.
     */
    public static int[] windows(int baud) {
        if (baud == 10400) return new int[]{0xB882, 0x07};
        if (baud == 205) return new int[]{0xC680, 0xFC};
        return registers(baud);
    }
}
