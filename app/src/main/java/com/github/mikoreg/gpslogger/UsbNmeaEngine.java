package com.github.mikoreg.gpslogger;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class UsbNmeaEngine implements StoppableLoggerEngine {
    private static final int READ_BUFFER_SIZE = 4096;
    private static final int LINE_BUFFER_SIZE = 1024;
    private static final long STATS_PERIOD_MS = 1000L;
    private static final int USB_READ_TIMEOUT_MS = 1000;
    private static final int USB_CONTROL_TIMEOUT_MS = 1000;
    private static final int CDC_DEFAULT_BAUD_RATE = 9600;
    private static final int PL2303_GPS_BAUD_RATE = 115200;

    private static final int CDC_SET_LINE_CODING = 0x20;
    private static final int CDC_SET_CONTROL_LINE_STATE = 0x22;
    private static final int CDC_REQUEST_TYPE_OUT = 0x21;
    private static final int PL2303_VENDOR_ID = 0x067B;
    private static final int USB_VENDOR_READ = 0xC0;
    private static final int USB_VENDOR_WRITE = 0x40;
    private static final int PL2303_VENDOR_REQUEST = 0x01;

    private static final int CONFIG_NONE = 0;
    private static final int CONFIG_CDC_ACM = 1;
    private static final int CONFIG_PL2303 = 2;

    private final Context appContext;
    private final String usbDeviceName;
    private final String displayName;
    private final NmeaLoggerEngine.StatsSink sink;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile @Nullable UsbDeviceConnection currentConnection;
    private volatile @Nullable NmeaFileLogger currentLogger;

    private long sessionStartAt;
    private volatile long lastSuccessfulDataAt;
    private boolean noDataAlarmTriggered = false;
    private boolean noFixAlarmTriggered = false;

    UsbNmeaEngine(
            Context context,
            String usbDeviceName,
            String displayName,
            NmeaLoggerEngine.StatsSink sink
    ) {
        this.appContext = context.getApplicationContext();
        this.usbDeviceName = usbDeviceName;
        this.displayName = displayName;
        this.sink = sink;
        this.sessionStartAt = SystemClock.elapsedRealtime();
        this.lastSuccessfulDataAt = sessionStartAt;
    }

    @Override
    public void run() {
        MutableNmeaStats mutableStats = new MutableNmeaStats(displayName);
        sessionStartAt = SystemClock.elapsedRealtime();
        mutableStats.setSessionStartRealtimeMs(sessionStartAt);
        lastSuccessfulDataAt = sessionStartAt;

        UsbDeviceConnection connection = null;
        NmeaFileLogger logger = null;
        ClaimedUsbSerial claimed = null;
        try {
            mutableStats.setState("CONNECTING");
            mutableStats.setLastError("");
            publish(mutableStats);

            UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
            if (manager == null) {
                throw new IOException("USB manager unavailable.");
            }
            UsbDevice device = findDevice(manager);
            if (device == null) {
                throw new IOException("USB GPS device is no longer connected.");
            }
            if (!manager.hasPermission(device)) {
                throw new SecurityException("USB permission missing.");
            }

            connection = manager.openDevice(device);
            if (connection == null) {
                throw new IOException("Cannot open USB GPS device.");
            }
            currentConnection = connection;

            claimed = claimSerial(device, connection);
            if (claimed.configMode == CONFIG_CDC_ACM) {
                configureCdcAcm(connection, claimed.controlInterface);
            } else if (claimed.configMode == CONFIG_PL2303) {
                configurePl2303(connection);
            }

            logger = new NmeaFileLogger(appContext);
            currentLogger = logger;

            mutableStats.setState("LOGGING");
            mutableStats.setConnected(true);
            mutableStats.setCurrentFileName(logger.currentFileName());
            mutableStats.setLastError("");
            publish(mutableStats);

            readLoop(connection, claimed.inputEndpoint, logger, mutableStats);
        } catch (SecurityException ex) {
            String msg = "USB permission missing: " + safeMessage(ex);
            mutableStats.setState("ERROR");
            mutableStats.setConnected(false);
            mutableStats.setLastError(msg);
            publish(mutableStats);
            sink.onCriticalError(msg);
        } catch (IOException ex) {
            String msg = safeMessage(ex);
            mutableStats.setState(running.get() ? "ERROR" : "STOPPING");
            mutableStats.setConnected(false);
            mutableStats.setLastError(msg);
            publish(mutableStats);
            if (running.get()) {
                sink.onCriticalError(msg);
            }
        } catch (Exception ex) {
            String msg = "Unexpected USB error: " + safeMessage(ex);
            mutableStats.setState("ERROR");
            mutableStats.setConnected(false);
            mutableStats.setLastError(msg);
            publish(mutableStats);
            sink.onCriticalError(msg);
        } finally {
            closeQuietly(logger);
            releaseQuietly(connection, claimed);
            closeQuietly(connection);
            currentLogger = null;
            currentConnection = null;
        }

        mutableStats.setConnected(false);
        mutableStats.setState("STOPPED");
        publish(mutableStats);
    }

    @Override
    public void stop() {
        running.set(false);
        closeQuietly(currentLogger);
        closeQuietly(currentConnection);
    }

    private @Nullable UsbDevice findDevice(UsbManager manager) {
        HashMap<String, UsbDevice> devices = manager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (usbDeviceName.equals(device.getDeviceName())) {
                return device;
            }
        }
        return null;
    }

    private ClaimedUsbSerial claimSerial(UsbDevice device, UsbDeviceConnection connection) throws IOException {
        if (isKnownUnsupportedVendorSerialBridge(device)) {
            throw unsupported(device);
        }

        UsbInterface controlInterface = null;
        UsbInterface dataInterface = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) {
                controlInterface = usbInterface;
            } else if (usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                dataInterface = usbInterface;
            }
        }

        int configMode = controlInterface != null && dataInterface != null ? CONFIG_CDC_ACM : CONFIG_NONE;
        if (isPl2303(device)) {
            configMode = CONFIG_PL2303;
        }
        if (dataInterface == null) {
            dataInterface = findBulkInterface(device);
            controlInterface = dataInterface;
        }
        if (dataInterface == null || controlInterface == null) {
            throw unsupported(device);
        }

        UsbEndpoint inputEndpoint = findBulkInputEndpoint(dataInterface);
        if (inputEndpoint == null) {
            throw unsupported(device);
        }

        if (!connection.claimInterface(controlInterface, true)) {
            throw new IOException("Cannot claim USB control interface.");
        }
        boolean dataClaimed = dataInterface == controlInterface || connection.claimInterface(dataInterface, true);
        if (!dataClaimed) {
            connection.releaseInterface(controlInterface);
            throw new IOException("Cannot claim USB data interface.");
        }

        return new ClaimedUsbSerial(controlInterface, dataInterface, inputEndpoint, configMode);
    }

    private static @Nullable UsbInterface findBulkInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (findBulkInputEndpoint(usbInterface) != null) {
                return usbInterface;
            }
        }
        return null;
    }

    private static @Nullable UsbEndpoint findBulkInputEndpoint(UsbInterface usbInterface) {
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                return endpoint;
            }
        }
        return null;
    }

    private void configureCdcAcm(UsbDeviceConnection connection, UsbInterface controlInterface) throws IOException {
        int index = controlInterface.getId();
        setLineCoding(connection, index, CDC_DEFAULT_BAUD_RATE, "USB GPS refused CDC line coding.");
        setControlLineState(connection, index, "USB GPS refused CDC control line state.");
    }

    private void configurePl2303(UsbDeviceConnection connection) throws IOException {
        byte[] buffer = new byte[1];
        vendorRead(connection, 0x8484, 0, buffer);
        vendorWrite(connection, 0x0404, 0);
        vendorRead(connection, 0x8484, 0, buffer);
        vendorRead(connection, 0x8383, 0, buffer);
        vendorRead(connection, 0x8484, 0, buffer);
        vendorWrite(connection, 0x0404, 1);
        vendorRead(connection, 0x8484, 0, buffer);
        vendorRead(connection, 0x8383, 0, buffer);
        vendorWrite(connection, 0, 1);
        vendorWrite(connection, 1, 0);
        vendorWrite(connection, 2, 0x44);
        setLineCoding(connection, 0, PL2303_GPS_BAUD_RATE, "PL2303 refused line coding.");
        setControlLineState(connection, 0, "PL2303 refused control line state.");
        vendorWrite(connection, 8, 0);
        vendorWrite(connection, 9, 0);
    }

    private void setLineCoding(
            UsbDeviceConnection connection,
            int index,
            int baudRate,
            String errorMessage
    ) throws IOException {
        byte[] lineCoding = new byte[] {
                (byte) (baudRate & 0xFF),
                (byte) ((baudRate >> 8) & 0xFF),
                (byte) ((baudRate >> 16) & 0xFF),
                (byte) ((baudRate >> 24) & 0xFF),
                0x00, // 1 stop bit
                0x00, // no parity
                0x08  // 8 data bits
        };
        int result = connection.controlTransfer(
                CDC_REQUEST_TYPE_OUT,
                CDC_SET_LINE_CODING,
                0,
                index,
                lineCoding,
                lineCoding.length,
                USB_CONTROL_TIMEOUT_MS);
        if (result < 0) {
            throw new IOException(errorMessage);
        }
    }

    private void setControlLineState(
            UsbDeviceConnection connection,
            int index,
            String errorMessage
    ) throws IOException {
        int result = connection.controlTransfer(
                CDC_REQUEST_TYPE_OUT,
                CDC_SET_CONTROL_LINE_STATE,
                3,
                index,
                null,
                0,
                USB_CONTROL_TIMEOUT_MS);
        if (result < 0) {
            throw new IOException(errorMessage);
        }
    }

    private void vendorRead(
            UsbDeviceConnection connection,
            int value,
            int index,
            byte[] buffer
    ) throws IOException {
        int result = connection.controlTransfer(
                USB_VENDOR_READ,
                PL2303_VENDOR_REQUEST,
                value,
                index,
                buffer,
                buffer.length,
                USB_CONTROL_TIMEOUT_MS);
        if (result < 0) {
            throw new IOException("PL2303 vendor read failed.");
        }
    }

    private void vendorWrite(
            UsbDeviceConnection connection,
            int value,
            int index
    ) throws IOException {
        int result = connection.controlTransfer(
                USB_VENDOR_WRITE,
                PL2303_VENDOR_REQUEST,
                value,
                index,
                null,
                0,
                USB_CONTROL_TIMEOUT_MS);
        if (result < 0) {
            throw new IOException("PL2303 vendor write failed.");
        }
    }

    private void readLoop(
            UsbDeviceConnection connection,
            UsbEndpoint inputEndpoint,
            NmeaFileLogger logger,
            MutableNmeaStats mutableStats
    ) throws IOException {
        byte[] readBuffer = new byte[Math.max(READ_BUFFER_SIZE, inputEndpoint.getMaxPacketSize())];
        byte[] lineBuffer = new byte[LINE_BUFFER_SIZE];
        int lineLength = 0;
        boolean discardCurrentLine = false;

        long nextStatsAt = SystemClock.elapsedRealtime() + STATS_PERIOD_MS;
        long nextFlushAt = SystemClock.elapsedRealtime() + NmeaFileLogger.FLUSH_PERIOD_MS;

        while (running.get()) {
            int read = connection.bulkTransfer(inputEndpoint, readBuffer, readBuffer.length, USB_READ_TIMEOUT_MS);
            long now = SystemClock.elapsedRealtime();

            if (read < 0) {
                if (now - lastSuccessfulDataAt > 20000) {
                    throw new EOFException("USB GPS data timeout.");
                }
                publishPeriodic(logger, mutableStats, now, nextFlushAt, nextStatsAt);
                if (now >= nextFlushAt) nextFlushAt = now + NmeaFileLogger.FLUSH_PERIOD_MS;
                if (now >= nextStatsAt) nextStatsAt = now + STATS_PERIOD_MS;
                continue;
            }

            if (read > 0) {
                lastSuccessfulDataAt = now;
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

            if (now >= nextFlushAt) {
                logger.flush();
                nextFlushAt = now + NmeaFileLogger.FLUSH_PERIOD_MS;
            }
            if (now >= nextStatsAt) {
                mutableStats.setCurrentFileName(logger.currentFileName());
                mutableStats.setBytesWritten(logger.totalBytesWritten());
                mutableStats.rollOneSecondWindow(now);
                checkAlarms(mutableStats);
                publish(mutableStats);
                nextStatsAt = now + STATS_PERIOD_MS;
            }
        }
    }

    private void publishPeriodic(
            NmeaFileLogger logger,
            MutableNmeaStats mutableStats,
            long now,
            long nextFlushAt,
            long nextStatsAt
    ) throws IOException {
        if (now >= nextFlushAt) {
            logger.flush();
        }
        if (now >= nextStatsAt) {
            mutableStats.setCurrentFileName(logger.currentFileName());
            mutableStats.setBytesWritten(logger.totalBytesWritten());
            mutableStats.rollOneSecondWindow(now);
            checkAlarms(mutableStats);
            publish(mutableStats);
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

    private void checkAlarms(MutableNmeaStats stats) {
        if (!running.get()) return;
        long now = SystemClock.elapsedRealtime();

        if (!noDataAlarmTriggered && (now - lastSuccessfulDataAt > 30000)) {
            sink.onCriticalError("No USB GPS data for 30 seconds!");
            noDataAlarmTriggered = true;
        }

        long lastFix = stats.getLastPositionRealtimeMs();
        long fixReference = lastFix > 0 ? lastFix : sessionStartAt;
        if (!noFixAlarmTriggered && fixReference > 0 && (now - fixReference > 30000)) {
            sink.onCriticalError("No GPS Fix for over 30 seconds!");
            noFixAlarmTriggered = true;
        }
    }

    private void publish(MutableNmeaStats mutableStats) {
        mutableStats.setLastDataRealtimeMs(lastSuccessfulDataAt);
        sink.onStats(mutableStats.snapshot(SystemClock.elapsedRealtime()));
    }

    private static IOException unsupported(UsbDevice device) {
        String message = "Unsupported USB serial device. "
                + "CDC ACM is supported; FTDI, PL2303, CH34x, and CP210x need a vendor driver. "
                + "VID=0x" + Integer.toHexString(device.getVendorId())
                + " PID=0x" + Integer.toHexString(device.getProductId());
        return new IOException(message);
    }

    private static boolean isPl2303(UsbDevice device) {
        return device.getVendorId() == PL2303_VENDOR_ID;
    }

    private static boolean isKnownUnsupportedVendorSerialBridge(UsbDevice device) {
        int vendorId = device.getVendorId();
        return vendorId == 0x0403  // FTDI
                || vendorId == 0x10C4 // Silicon Labs CP210x
                || vendorId == 0x1A86; // WCH CH34x
    }

    private static void releaseQuietly(@Nullable UsbDeviceConnection connection, @Nullable ClaimedUsbSerial claimed) {
        if (connection == null || claimed == null) return;
        try {
            connection.releaseInterface(claimed.dataInterface);
        } catch (RuntimeException ignored) {}
        if (claimed.controlInterface != claimed.dataInterface) {
            try {
                connection.releaseInterface(claimed.controlInterface);
            } catch (RuntimeException ignored) {}
        }
    }

    private static void closeQuietly(@Nullable UsbDeviceConnection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (RuntimeException ignored) {}
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

    private static final class ClaimedUsbSerial {
        final UsbInterface controlInterface;
        final UsbInterface dataInterface;
        final UsbEndpoint inputEndpoint;
        final int configMode;

        ClaimedUsbSerial(
                UsbInterface controlInterface,
                UsbInterface dataInterface,
                UsbEndpoint inputEndpoint,
                int configMode
        ) {
            this.controlInterface = controlInterface;
            this.dataInterface = dataInterface;
            this.inputEndpoint = inputEndpoint;
            this.configMode = configMode;
        }
    }
}
