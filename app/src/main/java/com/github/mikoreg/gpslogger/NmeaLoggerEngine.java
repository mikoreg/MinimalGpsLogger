package com.github.mikoreg.gpslogger;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;

import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class NmeaLoggerEngine implements Runnable {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int READ_BUFFER_SIZE = 4096;
    private static final int LINE_BUFFER_SIZE = 1024;
    private static final long STATS_PERIOD_MS = 1000L;

    interface StatsSink {
        void onStats(NmeaStats stats);
        void onCriticalError(String message);
    }

    private final Context appContext;
    private final BluetoothAdapter bluetoothAdapter;
    private final String address;
    private final String deviceName;
    private final StatsSink sink;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile @Nullable BluetoothSocket currentSocket;
    private volatile @Nullable NmeaFileLogger currentLogger;
    
    private volatile long lastSuccessfulDataAt = SystemClock.elapsedRealtime();
    private boolean dataAlarmTriggered = false;
    private boolean fixAlarmTriggered = false;

    NmeaLoggerEngine(
            Context context,
            BluetoothAdapter bluetoothAdapter,
            String address,
            String deviceName,
            StatsSink sink
    ) {
        this.appContext = context.getApplicationContext();
        this.bluetoothAdapter = bluetoothAdapter;
        this.address = address;
        this.deviceName = deviceName;
        this.sink = sink;
    }

    @Override
    public void run() {
        MutableNmeaStats mutableStats = new MutableNmeaStats(deviceName);
        int reconnectDelayMs = 1000;
        lastSuccessfulDataAt = SystemClock.elapsedRealtime();

        while (running.get()) {
            BluetoothSocket socket = null;
            NmeaFileLogger logger = null;
            try {
                mutableStats.setState("CONNECTING");
                mutableStats.setLastError("");
                publish(mutableStats);

                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                currentSocket = socket;

                bluetoothAdapter.cancelDiscovery();
                socket.connect();

                logger = new NmeaFileLogger(appContext);
                currentLogger = logger;

                mutableStats.setState("LOGGING");
                mutableStats.setConnected(true);
                mutableStats.setCurrentFileName(logger.currentFileName());
                mutableStats.setLastError("");
                publish(mutableStats);

                reconnectDelayMs = 1000;
                readLoop(socket.getInputStream(), logger, mutableStats);
            } catch (SecurityException ex) {
                String msg = "Bluetooth Permission missing: " + safeMessage(ex);
                mutableStats.setState("ERROR");
                mutableStats.setConnected(false);
                mutableStats.setLastError(msg);
                publish(mutableStats);
                sink.onCriticalError(msg);
                break;
            } catch (IOException ex) {
                mutableStats.incrementReconnects();
                mutableStats.setConnected(false);
                mutableStats.setState(running.get() ? "RECONNECTING" : "STOPPING");
                mutableStats.setLastError(safeMessage(ex));
                publish(mutableStats);
                
                checkDataAlarm();
                
                closeQuietly(logger);
                closeQuietly(socket);
                currentLogger = null;
                currentSocket = null;

                if (running.get()) {
                    sleep(reconnectDelayMs);
                    reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30000);
                }
            } catch (Exception ex) {
                String msg = "Unexpected error: " + safeMessage(ex);
                mutableStats.setState("ERROR");
                mutableStats.setLastError(msg);
                publish(mutableStats);
                sink.onCriticalError(msg);
                if (!running.get()) break;
                sleep(5000);
            } finally {
                closeQuietly(logger);
                closeQuietly(socket);
                currentLogger = null;
                currentSocket = null;
            }
        }

        mutableStats.setConnected(false);
        mutableStats.setState("STOPPED");
        publish(mutableStats);
    }

    void stop() {
        running.set(false);
        closeQuietly(currentLogger);
        closeQuietly(currentSocket);
    }

    private void checkDataAlarm() {
        if (!dataAlarmTriggered && running.get()) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastSuccessfulDataAt > 30000) {
                sink.onCriticalError("No GPS data for 30 seconds!");
                dataAlarmTriggered = true;
            }
        }
    }
    
    private void checkFixAlarm(MutableNmeaStats stats) {
        if (!fixAlarmTriggered && running.get()) {
            long now = SystemClock.elapsedRealtime();
            long lastFix = stats.getLastPositionRealtimeMs();
            
            // If we are logging but haven't received valid coordinates for more than 45 seconds
            // (Giving some extra time for the first fix)
            if (lastFix > 0) {
                if (now - lastFix > 30000) {
                    sink.onCriticalError("GPS Fix lost (no coordinates for 30s)!");
                    fixAlarmTriggered = true;
                }
            } else if (now - lastSuccessfulDataAt > 60000) {
                 // Never had a fix, and receiving data for > 60s
                 sink.onCriticalError("Still no GPS Fix after 60s of data!");
                 fixAlarmTriggered = true;
            }
        }
    }

    private void readLoop(
            InputStream input,
            NmeaFileLogger logger,
            MutableNmeaStats mutableStats
    ) throws IOException {
        byte[] readBuffer = new byte[READ_BUFFER_SIZE];
        byte[] lineBuffer = new byte[LINE_BUFFER_SIZE];
        int lineLength = 0;
        boolean discardCurrentLine = false;

        long nextStatsAt = SystemClock.elapsedRealtime() + STATS_PERIOD_MS;
        long nextFlushAt = SystemClock.elapsedRealtime() + NmeaFileLogger.FLUSH_PERIOD_MS;

        while (running.get()) {
            int read = input.read(readBuffer);
            if (read < 0) {
                throw new EOFException("Bluetooth stream closed by remote device.");
            }

            if (read > 0) {
                lastSuccessfulDataAt = SystemClock.elapsedRealtime();
                dataAlarmTriggered = false;
            }

            for (int i = 0; i < read; i++) {
                byte b = readBuffer[i];

                if (b == '\n') {
                    if (!discardCurrentLine && lineLength > 0) {
                        handleLine(lineBuffer, lineLength, logger, mutableStats);
                    }
                    lineLength = 0;
                    discardCurrentLine = false;
                } else if (b != '\r') {
                    if (discardCurrentLine) {
                        continue;
                    }
                    if (lineLength < lineBuffer.length) {
                        lineBuffer[lineLength] = b;
                        lineLength++;
                    } else {
                        mutableStats.incrementTooLongLines();
                        lineLength = 0;
                        discardCurrentLine = true;
                    }
                }
            }

            long now = SystemClock.elapsedRealtime();
            if (now >= nextFlushAt) {
                logger.flush();
                nextFlushAt = now + NmeaFileLogger.FLUSH_PERIOD_MS;
            }
            if (now >= nextStatsAt) {
                long timeoutMs = now - lastSuccessfulDataAt;
                if (timeoutMs > 20000) {
                    throw new IOException("GPS Data timeout.");
                }
                
                checkFixAlarm(mutableStats);
                
                mutableStats.setCurrentFileName(logger.currentFileName());
                mutableStats.setBytesWritten(logger.totalBytesWritten());
                mutableStats.rollOneSecondWindow(now);
                publish(mutableStats);
                nextStatsAt = now + STATS_PERIOD_MS;
            }
        }
    }

    private void handleLine(
            byte[] line,
            int length,
            NmeaFileLogger logger,
            MutableNmeaStats mutableStats
    ) throws IOException {
        logger.writeLine(line, length);
        mutableStats.incrementSentences();
        mutableStats.incrementWindowSentences();
        mutableStats.setLastSentenceElapsedRealtimeMs(SystemClock.elapsedRealtime());
        NmeaParser.parseForStats(line, length, mutableStats);
        
        // Reset fix alarm if we just got coordinates
        if (mutableStats.getLastPositionRealtimeMs() == SystemClock.elapsedRealtime()) {
            fixAlarmTriggered = false;
        }
    }

    private void publish(MutableNmeaStats mutableStats) {
        mutableStats.setLastDataRealtimeMs(lastSuccessfulDataAt);
        sink.onStats(mutableStats.snapshot());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(@Nullable BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private static void closeQuietly(@Nullable NmeaFileLogger logger) {
        if (logger == null) return;
        try {
            logger.close();
        } catch (IOException ignored) {}
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
