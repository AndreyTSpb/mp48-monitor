package ru.gbo.aebmonitor;

import java.util.Arrays;

/**
 * Кадры KWP2000 по K-line для ЭБУ Bosch ME17.9.7.
 *
 * Формат кадра (как в обмене OpenDiag):
 *   [формат] [получатель] [отправитель] [данные] [контрольная сумма]
 * Если младшие 6 бит формата равны 0, длина идёт отдельным байтом после адресов:
 *   [0x80] [получатель] [отправитель] [длина] [данные] [CS]
 * Иначе длина = младшие 6 бит формата: [0x81..0xBF] [получатель] [отправитель] [данные] [CS].
 *
 * Контрольная сумма — сумма всех байт кадра по модулю 256.
 */
public final class Kwp2000 {

    public static final int START_COMMUNICATION = 0x81;
    public static final int STOP_COMMUNICATION = 0x82;
    public static final int READ_DATA_BY_LOCAL_IDENTIFIER = 0x21;
    public static final int READ_DATA_BY_COMMON_IDENTIFIER = 0x1A;
    public static final int TESTER_PRESENT = 0x3E;
    public static final int NEGATIVE_RESPONSE = 0x7F;

    public static final int ECU_ADDRESS = 0x10;
    public static final int TESTER_ADDRESS = 0xF1;

    private Kwp2000() {
    }

    /** Собирает кадр запроса с контрольной суммой. */
    public static byte[] request(int target, int source, int... data) {
        if (data == null || data.length > 0xFF) {
            throw new IllegalArgumentException("длина данных: " + (data == null ? -1 : data.length));
        }
        int len = data.length;
        int header = len <= 0x3F ? 3 : 4;
        byte[] frame = new byte[header + len + 1];
        if (len <= 0x3F) {
            frame[0] = (byte) (0x80 | len);
            frame[1] = (byte) target;
            frame[2] = (byte) source;
        } else {
            frame[0] = (byte) 0x80;
            frame[1] = (byte) target;
            frame[2] = (byte) source;
            frame[3] = (byte) len;
        }
        for (int i = 0; i < len; i++) {
            frame[header + i] = (byte) data[i];
        }
        frame[frame.length - 1] = (byte) checksum(frame, 0, frame.length - 1);
        return frame;
    }

    public static Frame parse(byte[] frame) {
        if (frame == null || frame.length < 5) {
            throw new IllegalArgumentException("короткий кадр");
        }
        int fmt = frame[0] & 0xFF;
        if ((fmt & 0x80) == 0) {
            throw new IllegalArgumentException("нет адресной информации: " + fmt);
        }
        int len = fmt & 0x3F;
        int pos = 3;
        if (len == 0) {
            if (frame.length < 6) {
                throw new IllegalArgumentException("нет байта длины");
            }
            len = frame[3] & 0xFF;
            pos = 4;
        }
        int end = pos + len;
        if (end + 1 > frame.length) {
            throw new IllegalArgumentException("кадр короче заявленной длины");
        }
        byte[] data = Arrays.copyOfRange(frame, pos, end);
        int cs = frame[end] & 0xFF;
        int sum = checksum(frame, 0, end);
        boolean ok = cs == sum;
        return new Frame(frame[1] & 0xFF, frame[2] & 0xFF, data, cs, ok);
    }

    public static int checksum(byte[] data, int from, int to) {
        int sum = 0;
        for (int i = from; i < to; i++) {
            sum += data[i] & 0xFF;
        }
        return sum & 0xFF;
    }

    public static final class Frame {
        public final int target;
        public final int source;
        public final byte[] data;
        public final int checksum;
        public final boolean checksumOk;

        Frame(int target, int source, byte[] data, int checksum, boolean checksumOk) {
            this.target = target;
            this.source = source;
            this.data = data;
            this.checksum = checksum;
            this.checksumOk = checksumOk;
        }

        public int service() {
            return data.length == 0 ? -1 : data[0] & 0xFF;
        }

        public boolean positive() {
            return service() != NEGATIVE_RESPONSE;
        }

        public int negativeCode() {
            return data.length >= 3 ? data[2] & 0xFF : -1;
        }
    }
}
