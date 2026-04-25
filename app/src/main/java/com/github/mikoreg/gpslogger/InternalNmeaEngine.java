package com.github.mikoreg.gpslogger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Engine that captures NMEA data from the device's internal GPS chip.
 */
final class InternalNmeaEngine implements Runnable {
    private final Context context;
    private final NmeaLoggerEngine.StatsSink sink;
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    private @Nullable NmeaFileLogger logger;
    private long lastDataAt = SystemClock.elapsedRealtime();

    InternalNmeaEngine(Context context, NmeaLoggerEngine.StatsSink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void run() {
        MutableNmeaStats mutableStats = new MutableNmeaStats("Internal GPS");
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        
        if (lm == null) {
            sink.onCriticalError("Location Manager not available");
            return;
        }

        try {
            logger = new NmeaFileLogger(context);
            mutableStats.setState("LOGGING");
            mutableStats.setConnected(true);
            mutableStats.setCurrentFileName(logger.currentFileName());
            publish(mutableStats);

            GpsStatus.NmeaListener nmeaListener = (timestamp, nmea) -> {
                if (!running.get()) return;
                try {
                    lastDataAt = SystemClock.elapsedRealtime();
                    byte[] bytes = nmea.getBytes(StandardCharsets.US_ASCII);
                    // NmeaListener provides string without \n usually
                    int len = bytes.length;
                    if (len > 0) {
                        logger.writeLine(bytes, len);
                        mutableStats.incrementSentences();
                        mutableStats.incrementWindowSentences();
                        mutableStats.setLastSentenceElapsedRealtimeMs(SystemClock.elapsedRealtime());
                        NmeaParser.parseForStats(bytes, len, mutableStats);
                    }
                } catch (IOException ignored) {}
            };

            LocationListener locListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {}
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            lm.addNmeaListener(nmeaListener);
            // We must request location updates to "wake up" the GPS chip
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locListener);

            while (running.get()) {
                long now = SystemClock.elapsedRealtime();
                mutableStats.setBytesWritten(logger.totalBytesWritten());
                mutableStats.rollOneSecondWindow(now);
                mutableStats.setLastDataRealtimeMs(lastDataAt);
                publish(mutableStats);
                
                // Panic check (Internal GPS might be disabled in system)
                if (now - lastDataAt > 30000 && running.get()) {
                    sink.onCriticalError("Internal GPS not providing NMEA data!");
                }

                try {
                    Thread.sleep(1000);
                    logger.flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            lm.removeNmeaListener(nmeaListener);
            lm.removeUpdates(locListener);

        } catch (Exception e) {
            sink.onCriticalError("Internal GPS Error: " + e.getMessage());
        } finally {
            closeLogger();
            mutableStats.setState("STOPPED");
            mutableStats.setConnected(false);
            publish(mutableStats);
        }
    }

    void stop() {
        running.set(false);
    }

    private void publish(MutableNmeaStats stats) {
        sink.onStats(stats.snapshot());
    }

    private void closeLogger() {
        if (logger != null) {
            try {
                logger.close();
            } catch (IOException ignored) {}
        }
    }
}
