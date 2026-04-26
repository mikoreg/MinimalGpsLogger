package com.github.mikoreg.gpslogger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures raw NMEA from the device's internal GNSS engine.
 *
 * This class intentionally logs only native NMEA sentences. It does not synthesize
 * fake NMEA from android.location.Location, because that would no longer be the
 * native GPS data stream.
 */
final class InternalNmeaEngine implements StoppableLoggerEngine {
    private static final long STATS_PERIOD_MS = 1000L;
    private static final long FLUSH_PERIOD_MS = NmeaFileLogger.FLUSH_PERIOD_MS;

    private final Context appContext;
    private final NmeaLoggerEngine.StatsSink sink;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object lock = new Object();

    private final MutableNmeaStats mutableStats = new MutableNmeaStats("Internal GPS");

    private volatile long lastDataAt = SystemClock.elapsedRealtime();
    private volatile boolean criticalNoDataAlertSent;
    private volatile boolean criticalNoFixAlertSent;

    private @Nullable HandlerThread callbackThread;
    private @Nullable LocationManager locationManager;
    private @Nullable NmeaFileLogger logger;
    private @Nullable LocationListener locationListener;
    private GpsStatus.@Nullable NmeaListener legacyNmeaListener;
    private @Nullable InternalNmeaApi24 api24NmeaRegistration;

    InternalNmeaEngine(Context context, NmeaLoggerEngine.StatsSink sink) {
        this.appContext = context.getApplicationContext();
        this.sink = sink;
    }

    @Override
    public void run() {
        try {
            if (!hasFineLocationPermission()) {
                throw new SecurityException("ACCESS_FINE_LOCATION is required for internal GPS NMEA.");
            }

            LocationManager lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                throw new IOException("LocationManager is not available.");
            }
            locationManager = lm;

            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                throw new IOException("GPS provider is disabled in Android location settings.");
            }

            callbackThread = new HandlerThread("gps-internal-nmea-callbacks");
            callbackThread.start();
            Handler callbackHandler = new Handler(callbackThread.getLooper());

            synchronized (lock) {
                logger = new NmeaFileLogger(appContext);
                mutableStats.setState("LOGGING");
                mutableStats.setConnected(true);
                mutableStats.setCurrentFileName(logger.currentFileName());
                mutableStats.setSessionStartRealtimeMs(SystemClock.elapsedRealtime());
            }
            publish();

            if (!registerNmeaListener(lm, callbackHandler)) {
                throw new IOException("Failed to register NMEA listener (hardware/API not supported).");
            }
            requestGpsWakeup(lm, callbackHandler);

            long nextFlushAt = SystemClock.elapsedRealtime() + FLUSH_PERIOD_MS;
            long nextStatsAt = SystemClock.elapsedRealtime() + STATS_PERIOD_MS;

            while (running.get()) {
                long now = SystemClock.elapsedRealtime();

                if (now >= nextFlushAt) {
                    flushLogger();
                    nextFlushAt = now + FLUSH_PERIOD_MS;
                }

                if (now >= nextStatsAt) {
                    synchronized (lock) {
                        NmeaFileLogger currentLogger = logger;
                        if (currentLogger != null) {
                            mutableStats.setBytesWritten(currentLogger.totalBytesWritten());
                            mutableStats.setCurrentFileName(currentLogger.currentFileName());
                        }
                        mutableStats.setLastDataRealtimeMs(lastDataAt);
                        mutableStats.rollOneSecondWindow(now);
                    }
                    publish();

                    if (!criticalNoDataAlertSent && now - lastDataAt > 30000L) {
                        criticalNoDataAlertSent = true;
                        sink.onCriticalError("Internal GPS is not providing NMEA data.");
                    }

                    long lastFix = mutableStats.getLastPositionRealtimeMs();
                    long sessionStart = mutableStats.snapshot(now).sessionStartRealtimeMs();
                    long fixReference = lastFix > 0 ? lastFix : sessionStart;

                    if (!criticalNoFixAlertSent && fixReference > 0 && (now - fixReference > 30000L)) {
                        criticalNoFixAlertSent = true;
                        sink.onCriticalError("No GPS Fix for over 30 seconds!");
                    }

                    nextStatsAt = now + STATS_PERIOD_MS;
                }

                sleep(100L);
            }
        } catch (SecurityException ex) {
            fail("Internal GPS permission error: " + safeMessage(ex));
        } catch (Exception ex) {
            fail("Internal GPS error: " + safeMessage(ex));
        } finally {
            unregisterLocationCallbacks();
            closeLogger();
            stopCallbackThread();

            synchronized (lock) {
                mutableStats.setConnected(false);
                mutableStats.setState("STOPPED");
            }
            publish();
        }
    }

    @Override
    public void stop() {
        running.set(false);
    }

    private boolean hasFineLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private boolean registerNmeaListener(LocationManager lm, Handler callbackHandler) {
        if (Build.VERSION.SDK_INT >= 24) {
            InternalNmeaApi24 registration = new InternalNmeaApi24(lm, new InternalNmeaApi24.Sink() {
                @Override
                public void onNmea(long timestamp, String nmea) {
                    onNmeaSentence(timestamp, nmea);
                }
            });
            api24NmeaRegistration = registration;
            return registration.register(callbackHandler);
        } else {
            GpsStatus.NmeaListener listener = new GpsStatus.NmeaListener() {
                @Override
                public void onNmeaReceived(long timestamp, String nmea) {
                    onNmeaSentence(timestamp, nmea);
                }
            };
            legacyNmeaListener = listener;
            return lm.addNmeaListener(listener);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestGpsWakeup(LocationManager lm, Handler callbackHandler) {
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                // NMEA listener is the source of truth. Location updates only keep GNSS active.
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        locationListener = listener;
        lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                listener,
                callbackHandler.getLooper()
        );
    }

    private void onNmeaSentence(long timestamp, String nmea) {
        if (!running.get() || nmea == null || nmea.isEmpty()) {
            return;
        }

        byte[] bytes = nmea.getBytes(StandardCharsets.US_ASCII);
        int length = normalizedLength(bytes);
        if (length <= 0) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        synchronized (lock) {
            NmeaFileLogger currentLogger = logger;
            if (currentLogger == null) {
                return;
            }

            try {
                currentLogger.writeLine(bytes, length);
                mutableStats.incrementSentences();
                mutableStats.incrementWindowSentences();
                mutableStats.setLastSentenceElapsedRealtimeMs(now);
                NmeaParser.parseForStats(bytes, length, mutableStats, now);
                lastDataAt = now;
                criticalNoDataAlertSent = false;
                if (mutableStats.getLastPositionRealtimeMs() == now) {
                    criticalNoFixAlertSent = false;
                }
            } catch (IOException ex) {
                mutableStats.setLastError(safeMessage(ex));
                sink.onCriticalError("Internal GPS write error: " + safeMessage(ex));
            }
        }
    }

    private static int normalizedLength(byte[] bytes) {
        int length = bytes.length;
        while (length > 0 && (bytes[length - 1] == '\n' || bytes[length - 1] == '\r')) {
            length--;
        }
        return length;
    }

    private void flushLogger() {
        synchronized (lock) {
            NmeaFileLogger currentLogger = logger;
            if (currentLogger != null) {
                try {
                    currentLogger.flush();
                } catch (IOException ex) {
                    mutableStats.setLastError(safeMessage(ex));
                }
            }
        }
    }

    private void closeLogger() {
        synchronized (lock) {
            NmeaFileLogger currentLogger = logger;
            logger = null;
            if (currentLogger != null) {
                try {
                    currentLogger.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void unregisterLocationCallbacks() {
        LocationManager lm = locationManager;
        if (lm == null) {
            return;
        }

        InternalNmeaApi24 api24 = api24NmeaRegistration;
        if (api24 != null && Build.VERSION.SDK_INT >= 24) {
            api24.unregister();
            api24NmeaRegistration = null;
        }

        GpsStatus.NmeaListener legacy = legacyNmeaListener;
        if (legacy != null) {
            lm.removeNmeaListener(legacy);
            legacyNmeaListener = null;
        }

        LocationListener loc = locationListener;
        if (loc != null) {
            lm.removeUpdates(loc);
            locationListener = null;
        }
    }

    private void stopCallbackThread() {
        HandlerThread thread = callbackThread;
        callbackThread = null;
        if (thread != null) {
            if (Build.VERSION.SDK_INT >= 18) {
                thread.quitSafely();
            } else {
                thread.quit();
            }
        }
    }

    private void fail(String message) {
        synchronized (lock) {
            mutableStats.setState("ERROR");
            mutableStats.setConnected(false);
            mutableStats.setLastError(message);
        }
        publish();
        sink.onCriticalError(message);
    }

    private void publish() {
        NmeaStats snapshot;
        synchronized (lock) {
            snapshot = mutableStats.snapshot(SystemClock.elapsedRealtime());
        }
        sink.onStats(snapshot);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
