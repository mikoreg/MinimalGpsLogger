package com.github.mikoreg.gpslogger;

final class MutableNmeaStats {
    private final String deviceName;
    private String state = "IDLE";
    private String currentFileName = "";
    private String lastError = "";

    private boolean connected;
    private boolean fixValid;

    private double latitude = Double.NaN;
    private double longitude = Double.NaN;
    private double altitudeMeters = Double.NaN;
    private double speedKmh = Double.NaN;
    private double courseDegrees = Double.NaN;
    private double hdop = Double.NaN;
    private double vdop = Double.NaN;
    private double pdop = Double.NaN;
    private double nmeaSentencesPerSecond = Double.NaN;

    private int fixQuality = -1;
    private int gsaFixType = -1;
    private int satellitesUsed = -1;
    private int satellitesVisible = -1;

    private long bytesWritten;
    private long sentencesTotal;
    private long sentencesInWindow;
    private long checksumErrors;
    private long parseErrors;
    private long tooLongLines;
    private long reconnects;
    private long lastSentenceElapsedRealtimeMs = -1L;
    private long windowStartRealtimeMs = -1L;
    private long lastDataRealtimeMs = 0;
    private long lastPositionRealtimeMs = 0;
    private long sessionStartRealtimeMs = 0;
    
    private long utcTimestampMs = -1L;
    private int lastDate = -1; // DDMMYY

    MutableNmeaStats(String deviceName) {
        this.deviceName = deviceName;
    }

    void setState(String state) {
        this.state = state;
    }

    void setCurrentFileName(String currentFileName) {
        this.currentFileName = currentFileName;
    }

    void setLastError(String lastError) {
        this.lastError = lastError;
    }

    void setConnected(boolean connected) {
        this.connected = connected;
    }

    void setFixValid(boolean fixValid) {
        this.fixValid = fixValid;
    }

    void setPosition(double latitude, double longitude, long now) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.lastPositionRealtimeMs = now;
    }

    void setAltitudeMeters(double altitudeMeters) {
        this.altitudeMeters = altitudeMeters;
    }

    void setSpeedKmh(double speedKmh) {
        this.speedKmh = speedKmh;
    }

    void setCourseDegrees(double courseDegrees) {
        this.courseDegrees = courseDegrees;
    }

    void setHdop(double hdop) {
        this.hdop = hdop;
    }

    void setVdop(double vdop) {
        this.vdop = vdop;
    }

    void setPdop(double pdop) {
        this.pdop = pdop;
    }

    void setFixQuality(int fixQuality) {
        this.fixQuality = fixQuality;
    }

    void setGsaFixType(int gsaFixType) {
        this.gsaFixType = gsaFixType;
    }

    void setSatellitesUsed(int satellitesUsed) {
        this.satellitesUsed = satellitesUsed;
    }

    void setSatellitesVisible(int satellitesVisible) {
        this.satellitesVisible = satellitesVisible;
    }

    void setBytesWritten(long bytesWritten) {
        this.bytesWritten = bytesWritten;
    }

    void setSessionStartRealtimeMs(long sessionStartRealtimeMs) {
        this.sessionStartRealtimeMs = sessionStartRealtimeMs;
    }

    void incrementSentences() {
        this.sentencesTotal++;
    }

    void incrementWindowSentences() {
        this.sentencesInWindow++;
    }

    void incrementChecksumErrors() {
        this.checksumErrors++;
    }

    void incrementParseErrors() {
        this.parseErrors++;
    }

    void incrementTooLongLines() {
        this.tooLongLines++;
    }

    void incrementReconnects() {
        this.reconnects++;
    }

    void setLastSentenceElapsedRealtimeMs(long lastSentenceElapsedRealtimeMs) {
        this.lastSentenceElapsedRealtimeMs = lastSentenceElapsedRealtimeMs;
    }
    
    void setLastDataRealtimeMs(long lastDataRealtimeMs) {
        this.lastDataRealtimeMs = lastDataRealtimeMs;
    }

    long getLastPositionRealtimeMs() {
        return lastPositionRealtimeMs;
    }
    
    void setUtcTimestampMs(long utcTimestampMs) {
        this.utcTimestampMs = utcTimestampMs;
    }
    
    void setLastDate(int lastDate) {
        this.lastDate = lastDate;
    }
    
    int getLastDate() {
        return lastDate;
    }

    void rollOneSecondWindow(long now) {
        if (windowStartRealtimeMs < 0) {
            windowStartRealtimeMs = now;
            sentencesInWindow = 0;
            return;
        }

        long elapsed = now - windowStartRealtimeMs;
        if (elapsed >= 1000) {
            nmeaSentencesPerSecond = (sentencesInWindow * 1000.0d) / elapsed;
            windowStartRealtimeMs = now;
            sentencesInWindow = 0;
        }
    }

    NmeaStats snapshot(long now) {
        long age = lastSentenceElapsedRealtimeMs < 0 ? -1L : now - lastSentenceElapsedRealtimeMs;
        
        boolean hasCoordinates = !Double.isNaN(latitude) && !Double.isNaN(longitude);
        boolean uiFixValid = fixValid && hasCoordinates;

        return new NmeaStats(
                state, deviceName, currentFileName, lastError, connected, uiFixValid,
                latitude, longitude, altitudeMeters, speedKmh, courseDegrees,
                hdop, vdop, pdop, nmeaSentencesPerSecond,
                fixQuality, gsaFixType, satellitesUsed, satellitesVisible,
                bytesWritten, sentencesTotal, checksumErrors, parseErrors, tooLongLines, reconnects,
                age, lastDataRealtimeMs, lastPositionRealtimeMs, utcTimestampMs,
                sessionStartRealtimeMs
        );
    }
}
