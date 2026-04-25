package com.github.mikoreg.gpslogger;

final class NmeaChecksum {
    private NmeaChecksum() {
    }

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
}
