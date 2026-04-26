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

final class NmeaLoggerEngine implements StoppableLoggerEngine {
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
    
    private long sessionStartAt;
    private volatile long lastSuccessfulDataAt;
    private boolean noDataAlarmTriggered = false;
    private boolean noFixAlarmTriggered = false;

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
        this.sessionStartAt = SystemClock.elapsedRealtime();
        this.lastSuccessfulDataAt = sessionStartAt;
    }

    @Override
    public void run() {
        MutableNmeaStats mutableStats = new MutableNmeaStats(deviceName);
        sessionStartAt = SystemClock.elapsedRealtime();
        mutableStats.setSessionStartRealtimeMs(sessionStartAt);
        int reconnectDelayMs = 1000;
        lastSuccessfulDataAt = sessionStartAt;

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
                
                checkAlarms(mutableStats);
                
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

    @Override
    public void stop() {
        running.set(false);
        closeQuietly(currentLogger);
        closeQuietly(currentSocket);
    }

    private void checkAlarms(MutableNmeaStats stats) {
        if (!running.get()) return;
        long now = SystemClock.elapsedRealtime();
        
        if (!noDataAlarmTriggered && (now - lastSuccessfulDataAt > 30000)) {
            sink.onCriticalError("No GPS data for 30 seconds!");
            noDataAlarmTriggered = true;
        }

        long lastFix = stats.getLastPositionRealtimeMs();
        long fixReference = lastFix > 0 ? lastFix : sessionStartAt;
        if (!noFixAlarmTriggered && fixReference > 0 && (now - fixReference > 30000)) {
            sink.onCriticalError("No GPS Fix for over 30 seconds!");
            noFixAlarmTriggered = true;
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
                noDataAlarmTriggered = false;
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
                mutableStats.setCurrentFileName(logger.currentFileName());
                mutableStats.setBytesWritten(logger.totalBytesWritten());
                mutableStats.rollOneSecondWindow(now);
                checkAlarms(mutableStats);
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
        long now = SystemClock.elapsedRealtime();
        logger.writeLine(line, length);
        mutableStats.incrementSentences();
        mutableStats.incrementWindowSentences();
        mutableStats.setLastSentenceElapsedRealtimeMs(now);
        NmeaParser.parseForStats(line, length, mutableStats, now);
        if (mutableStats.getLastPositionRealtimeMs() == now) {
            noFixAlarmTriggered = false;
        }
    }

    private void publish(MutableNmeaStats mutableStats) {
        mutableStats.setLastDataRealtimeMs(lastSuccessfulDataAt);
        sink.onStats(mutableStats.snapshot(SystemClock.elapsedRealtime()));
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
