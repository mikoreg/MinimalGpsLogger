package com.github.mikoreg.gpslogger;

import java.util.Locale;

public final class NmeaStats {
    private final String state;
    private final String deviceName;
    private final String currentFileName;
    private final String lastError;
    private final boolean connected;
    private final boolean fixValid;
    private final double latitude;
    private final double longitude;
    private final double altitudeMeters;
    private final double speedKmh;
    private final double courseDegrees;
    private final double hdop;
    private final double vdop;
    private final double pdop;
    private final double nmeaSentencesPerSecond;
    private final int fixQuality;
    private final int gsaFixType;
    private final int satellitesUsed;
    private final int satellitesVisible;
    private final long bytesWritten;
    private final long sentencesTotal;
    private final long checksumErrors;
    private final long parseErrors;
    private final long tooLongLines;
    private final long reconnects;
    private final long lastSentenceAgeMs;
    private final long lastDataRealtimeMs;
    private final long lastPositionRealtimeMs;
    private final long utcTimestampMs;

    NmeaStats(
            String state,
            String deviceName,
            String currentFileName,
            String lastError,
            boolean connected,
            boolean fixValid,
            double latitude,
            double longitude,
            double altitudeMeters,
            double speedKmh,
            double courseDegrees,
            double hdop,
            double vdop,
            double pdop,
            double nmeaSentencesPerSecond,
            int fixQuality,
            int gsaFixType,
            int satellitesUsed,
            int satellitesVisible,
            long bytesWritten,
            long sentencesTotal,
            long checksumErrors,
            long parseErrors,
            long tooLongLines,
            long reconnects,
            long lastSentenceAgeMs,
            long lastDataRealtimeMs,
            long lastPositionRealtimeMs,
            long utcTimestampMs
    ) {
        this.state = state;
        this.deviceName = deviceName;
        this.currentFileName = currentFileName;
        this.lastError = lastError;
        this.connected = connected;
        this.fixValid = fixValid;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.speedKmh = speedKmh;
        this.courseDegrees = courseDegrees;
        this.hdop = hdop;
        this.vdop = vdop;
        this.pdop = pdop;
        this.nmeaSentencesPerSecond = nmeaSentencesPerSecond;
        this.fixQuality = fixQuality;
        this.gsaFixType = gsaFixType;
        this.satellitesUsed = satellitesUsed;
        this.satellitesVisible = satellitesVisible;
        this.bytesWritten = bytesWritten;
        this.sentencesTotal = sentencesTotal;
        this.checksumErrors = checksumErrors;
        this.parseErrors = parseErrors;
        this.tooLongLines = tooLongLines;
        this.reconnects = reconnects;
        this.lastSentenceAgeMs = lastSentenceAgeMs;
        this.lastDataRealtimeMs = lastDataRealtimeMs;
        this.lastPositionRealtimeMs = lastPositionRealtimeMs;
        this.utcTimestampMs = utcTimestampMs;
    }

    public static NmeaStats idle() {
        return new NmeaStats(
                "IDLE", "", "", "", false, false,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                -1, -1, -1, -1,
                0L, 0L, 0L, 0L, 0L, 0L, -1L, 0L, 0L, -1L
        );
    }

    public static NmeaStats error(String error) {
        return new NmeaStats(
                "ERROR", "", "", error, false, false,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                -1, -1, -1, -1,
                0L, 0L, 0L, 0L, 0L, 0L, -1L, 0L, 0L, -1L
        );
    }

    NmeaStats withState(String newState) {
        return new NmeaStats(
                newState, deviceName, currentFileName, lastError, connected, fixValid,
                latitude, longitude, altitudeMeters, speedKmh, courseDegrees,
                hdop, vdop, pdop, nmeaSentencesPerSecond,
                fixQuality, gsaFixType, satellitesUsed, satellitesVisible,
                bytesWritten, sentencesTotal, checksumErrors, parseErrors, tooLongLines, reconnects,
                lastSentenceAgeMs, lastDataRealtimeMs, lastPositionRealtimeMs, utcTimestampMs
        );
    }

    public String state() { return state; }
    public String deviceName() { return deviceName; }
    public String currentFileName() { return currentFileName; }
    public String lastError() { return lastError; }
    public boolean connected() { return connected; }
    public boolean fixValid() { return fixValid; }
    public double latitude() { return latitude; }
    public double longitude() { return longitude; }
    public double altitudeMeters() { return altitudeMeters; }
    public double speedKmh() { return speedKmh; }
    public double courseDegrees() { return courseDegrees; }
    public double hdop() { return hdop; }
    public double vdop() { return vdop; }
    public double pdop() { return pdop; }
    public double nmeaSentencesPerSecond() { return nmeaSentencesPerSecond; }
    public int fixQuality() { return fixQuality; }
    public int gsaFixType() { return gsaFixType; }
    public int satellitesUsed() { return satellitesUsed; }
    public int satellitesVisible() { return satellitesVisible; }
    public long bytesWritten() { return bytesWritten; }
    public long sentencesTotal() { return sentencesTotal; }
    public long checksumErrors() { return checksumErrors; }
    public long parseErrors() { return parseErrors; }
    public long tooLongLines() { return tooLongLines; }
    public long reconnects() { return reconnects; }
    public long lastSentenceAgeMs() { return lastSentenceAgeMs; }
    public long lastDataRealtimeMs() { return lastDataRealtimeMs; }
    public long lastPositionRealtimeMs() { return lastPositionRealtimeMs; }
    public long utcTimestampMs() { return utcTimestampMs; }

    public String formatDouble(double value, int decimals) {
        if (Double.isNaN(value)) return "-";
        return String.format(Locale.US, "%." + decimals + "f", value).trim();
    }

    public String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0d;
        if (kb < 1024.0d) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0d;
        return String.format(Locale.US, "%.2f MB", mb);
    }

    public String formatInt(int value) {
        return value < 0 ? "-" : Integer.toString(value);
    }
}
