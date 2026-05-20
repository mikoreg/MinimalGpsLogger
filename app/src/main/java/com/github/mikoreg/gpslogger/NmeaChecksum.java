package com.github.mikoreg.gpslogger;

/**
 * Utility for NMEA sentence checksum validation.
 * NMEA sentences use a simple XOR-based checksum at the end of the line.
 */
final class NmeaChecksum {
    private NmeaChecksum() {
    }

    /**
     * Validates the checksum of an NMEA sentence.
     * @param line Byte array containing the NMEA sentence.
     * @param length Effective length of the sentence.
     * @return true if the checksum is valid, false otherwise.
     */
    static boolean isValid(byte[] line, int length) {
        if (length < 4 || line[0] != '$') {
            return false;
        }

        int star = -1;
        for (int i = 1; i < length; i++) {
            if (line[i] == '*') {
                star = i;
                break;
            }
        }

        if (star < 0 || star + 2 >= length) {
            return false;
        }

        int xor = 0;
        for (int i = 1; i < star; i++) {
            xor ^= line[i] & 0xFF;
        }

        int high = hex(line[star + 1]);
        int low = hex(line[star + 2]);
        if (high < 0 || low < 0) {
            return false;
        }

        int expected = high * 16 + low;
        return xor == expected;
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        return -1;
    }

    static String calculate(String sentenceWithoutDollar) {
        int xor = 0;
        for (int i = 0; i < sentenceWithoutDollar.length(); i++) {
            char c = sentenceWithoutDollar.charAt(i);
            if (c == '*') break;
            xor ^= c;
        }
        String hex = Integer.toHexString(xor).toUpperCase();
        return hex.length() == 1 ? "0" + hex : hex;
    }
}
