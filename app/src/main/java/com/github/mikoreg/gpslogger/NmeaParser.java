package com.github.mikoreg.gpslogger;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Lightweight NMEA sentence parser.
 * This class extracts basic GPS statistics (position, speed, satellites) 
 * from raw NMEA byte arrays without using expensive String operations or Regex.
 */
final class NmeaParser {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private NmeaParser() {
    }

    /**
     * Parses a single NMEA line and updates the provided stats object.
     * Validates checksum before parsing.
     */
    static void parseForStats(byte[] line, int length, MutableNmeaStats stats, long now) {
        if (length < 6 || line[0] != '$') {
            stats.incrementParseErrors();
            return;
        }

        if (!NmeaChecksum.isValid(line, length)) {
            stats.incrementChecksumErrors();
            return;
        }

        byte a = upper(line[3]);
        byte b = upper(line[4]);
        byte c = upper(line[5]);

        try {
            if (a == 'R' && b == 'M' && c == 'C') {
                parseRmc(line, length, stats, now);
            } else if (a == 'G' && b == 'G' && c == 'A') {
                parseGga(line, length, stats, now);
            } else if (a == 'V' && b == 'T' && c == 'G') {
                parseVtg(line, length, stats);
            } else if (a == 'G' && b == 'S' && c == 'A') {
                parseGsa(line, length, stats);
            } else if (a == 'G' && b == 'S' && c == 'V') {
                parseGsv(line, length, stats);
            }
        } catch (RuntimeException ex) {
            stats.incrementParseErrors();
        }
    }

    private static void parseRmc(byte[] line, int length, MutableNmeaStats stats, long now) {
        Field status = field(line, length, 2);
        boolean valid = status.length() == 1 && upper(line[status.start]) == 'A';
        stats.setFixValid(valid);

        Field timeField = field(line, length, 1);
        Field dateField = field(line, length, 9);
        
        if (dateField.length() == 6) {
            int day = parseInt(new Field(dateField.start, dateField.start + 2), line);
            int month = parseInt(new Field(dateField.start + 2, dateField.start + 4), line);
            int year = parseInt(new Field(dateField.start + 4, dateField.start + 6), line);
            if (day > 0 && month > 0 && year >= 0) {
                stats.setLastDate(day * 10000 + month * 100 + year);
            }
        }

        if (timeField.length() >= 6) {
            long ts = parseUtcTimestamp(line, timeField, stats.getLastDate());
            if (ts > 0) stats.setUtcTimestampMs(ts);
        }

        if (!valid) {
            return;
        }

        double latitude = parseCoordinate(line, length, 3, 4, 2);
        double longitude = parseCoordinate(line, length, 5, 6, 3);
        if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
            stats.setPosition(latitude, longitude, now);
        }

        double speedKnots = parseDouble(field(line, length, 7), line);
        if (!Double.isNaN(speedKnots)) {
            stats.setSpeedKmh(speedKnots * 1.852d);
        }

        double course = parseDouble(field(line, length, 8), line);
        if (!Double.isNaN(course)) {
            stats.setCourseDegrees(course);
        }
    }

    private static void parseGga(byte[] line, int length, MutableNmeaStats stats, long now) {
        int quality = parseInt(field(line, length, 6), line);
        stats.setFixQuality(quality);
        stats.setFixValid(quality > 0);

        Field timeField = field(line, length, 1);
        if (timeField.length() >= 6) {
            long ts = parseUtcTimestamp(line, timeField, stats.getLastDate());
            if (ts > 0) stats.setUtcTimestampMs(ts);
        }

        int satellites = parseInt(field(line, length, 7), line);
        if (satellites >= 0) {
            stats.setSatellitesUsed(satellites);
        }

        double hdop = parseDouble(field(line, length, 8), line);
        if (!Double.isNaN(hdop)) {
            stats.setHdop(hdop);
        }

        double altitude = parseDouble(field(line, length, 9), line);
        if (!Double.isNaN(altitude)) {
            stats.setAltitudeMeters(altitude);
        }

        double latitude = parseCoordinate(line, length, 2, 3, 2);
        double longitude = parseCoordinate(line, length, 4, 5, 3);
        if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
            stats.setPosition(latitude, longitude, now);
        }
    }

    private static long parseUtcTimestamp(byte[] line, Field timeField, int date) {
        if (timeField.length() < 6 || date <= 0) return -1;
        
        int hh = parseInt(new Field(timeField.start, timeField.start + 2), line);
        int mm = parseInt(new Field(timeField.start + 2, timeField.start + 4), line);
        int ss = parseInt(new Field(timeField.start + 4, timeField.start + 6), line);
        int ms = 0;
        
        int dot = -1;
        for (int i = timeField.start + 6; i < timeField.end; i++) {
            if (line[i] == '.') {
                dot = i;
                break;
            }
        }
        
        if (dot != -1) {
            int fractLen = timeField.end - (dot + 1);
            int fract = parseInt(new Field(dot + 1, timeField.end), line);
            if (fractLen == 1) ms = fract * 100;
            else if (fractLen == 2) ms = fract * 10;
            else if (fractLen == 3) ms = fract;
            else if (fractLen > 3) {
                ms = parseInt(new Field(dot + 1, dot + 4), line);
            }
        }
        
        int day = date / 10000;
        int month = (date / 100) % 100;
        int year = date % 100 + 2000;
        
        Calendar cal = Calendar.getInstance(UTC);
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month - 1);
        cal.set(Calendar.DAY_OF_MONTH, day);
        cal.set(Calendar.HOUR_OF_DAY, hh);
        cal.set(Calendar.MINUTE, mm);
        cal.set(Calendar.SECOND, ss);
        cal.set(Calendar.MILLISECOND, ms);
        
        return cal.getTimeInMillis();
    }

    private static void parseVtg(byte[] line, int length, MutableNmeaStats stats) {
        double course = parseDouble(field(line, length, 1), line);
        if (!Double.isNaN(course)) {
            stats.setCourseDegrees(course);
        }

        double speedKmh = parseDouble(field(line, length, 7), line);
        if (!Double.isNaN(speedKmh)) {
            stats.setSpeedKmh(speedKmh);
        }
    }

    private static void parseGsa(byte[] line, int length, MutableNmeaStats stats) {
        int fixType = parseInt(field(line, length, 2), line);
        if (fixType >= 0) {
            stats.setGsaFixType(fixType);
        }

        double pdop = parseDouble(field(line, length, 15), line);
        if (!Double.isNaN(pdop)) {
            stats.setPdop(pdop);
        }

        double hdop = parseDouble(field(line, length, 16), line);
        if (!Double.isNaN(hdop)) {
            stats.setHdop(hdop);
        }

        double vdop = parseDouble(field(line, length, 17), line);
        if (!Double.isNaN(vdop)) {
            stats.setVdop(vdop);
        }
    }

    private static void parseGsv(byte[] line, int length, MutableNmeaStats stats) {
        int satellitesVisible = parseInt(field(line, length, 3), line);
        if (satellitesVisible >= 0) {
            stats.setSatellitesVisible(satellitesVisible);
        }
    }

    private static double parseCoordinate(
            byte[] line,
            int length,
            int valueFieldIndex,
            int hemisphereFieldIndex,
            int degreeDigits
    ) {
        Field value = field(line, length, valueFieldIndex);
        Field hemisphere = field(line, length, hemisphereFieldIndex);
        if (value.length() <= degreeDigits || hemisphere.length() < 1) {
            return Double.NaN;
        }

        double degrees = parseUnsignedIntegerPrefix(value, line, degreeDigits);
        double minutes = parseDouble(new Field(value.start + degreeDigits, value.end), line);
        if (Double.isNaN(degrees) || Double.isNaN(minutes)) {
            return Double.NaN;
        }

        double result = degrees + minutes / 60.0d;
        byte h = upper(line[hemisphere.start]);
        if (h == 'S' || h == 'W') {
            result = -result;
        }
        return result;
    }

    static Field field(byte[] line, int length, int fieldIndex) {
        int currentField = 0;
        int start = 0;

        for (int i = 0; i <= length; i++) {
            boolean boundary = i == length || line[i] == ',' || line[i] == '*';
            if (!boundary) {
                continue;
            }
            if (currentField == fieldIndex) {
                return new Field(start, i);
            }
            currentField++;
            start = i + 1;
        }

        return Field.empty();
    }

    static double parseDouble(Field field, byte[] source) {
        if (field.length() == 0) {
            return Double.NaN;
        }

        int i = field.start;
        boolean negative = false;
        if (i < field.end && source[i] == '-') {
            negative = true;
            i++;
        } else if (i < field.end && source[i] == '+') {
            i++;
        }

        double value = 0d;
        boolean hasDigit = false;

        while (i < field.end && source[i] >= '0' && source[i] <= '9') {
            value = value * 10d + (source[i] - '0');
            i++;
            hasDigit = true;
        }

        if (i < field.end && source[i] == '.') {
            i++;
            double place = 0.1d;
            while (i < field.end && source[i] >= '0' && source[i] <= '9') {
                value += (source[i] - '0') * place;
                place *= 0.1d;
                i++;
                hasDigit = true;
            }
        }

        if (!hasDigit || i != field.end) {
            return Double.NaN;
        }

        return negative ? -value : value;
    }

    static int parseInt(Field field, byte[] source) {
        if (field.length() == 0) {
            return -1;
        }

        int value = 0;
        for (int i = field.start; i < field.end; i++) {
            byte b = source[i];
            if (b < '0' || b > '9') {
                return -1;
            }
            value = value * 10 + (b - '0');
        }
        return value;
    }

    private static double parseUnsignedIntegerPrefix(Field field, byte[] source, int digits) {
        if (field.length() < digits) {
            return Double.NaN;
        }
        int value = 0;
        for (int i = 0; i < digits; i++) {
            byte b = source[field.start + i];
            if (b < '0' || b > '9') {
                return Double.NaN;
            }
            value = value * 10 + (b - '0');
        }
        return value;
    }

    private static byte upper(byte b) {
        if (b >= 'a' && b <= 'z') {
            return (byte) (b - 32);
        }
        return b;
    }

    static final class Field {
        final int start;
        final int end;

        Field(int start, int end) {
            this.start = start;
            this.end = end;
        }

        int length() {
            return end - start;
        }

        static Field empty() {
            return new Field(0, 0);
        }
    }
}
