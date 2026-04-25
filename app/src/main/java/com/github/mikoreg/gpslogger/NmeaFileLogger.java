package com.github.mikoreg.gpslogger;

import android.content.Context;

import org.jspecify.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class NmeaFileLogger implements AutoCloseable {
    static final long FLUSH_PERIOD_MS = 5000L;

    private static final int FILE_BUFFER_SIZE = 128 * 1024;
    private static final long MAX_FILE_BYTES = 100L * 1024L * 1024L;

    private final File directory;
    private final String sessionName;

    private int part = 1;
    private long totalBytesWritten;
    private long currentFileBytes;
    private @Nullable File currentFile;
    private @Nullable BufferedOutputStream out;

    NmeaFileLogger(Context context) throws IOException {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        directory = new File(base, "tracks");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create tracks directory: " + directory.getAbsolutePath());
        }
        sessionName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        openNextFile();
    }

    void writeLine(byte[] line, int length) throws IOException {
        BufferedOutputStream stream = requireStream();
        stream.write(line, 0, length);
        stream.write('\n');
        currentFileBytes += length + 1L;
        totalBytesWritten += length + 1L;

        if (currentFileBytes >= MAX_FILE_BYTES) {
            rotate();
        }
    }

    void flush() throws IOException {
        BufferedOutputStream stream = out;
        if (stream != null) {
            stream.flush();
        }
    }

    long totalBytesWritten() {
        return totalBytesWritten;
    }

    String currentFileName() {
        File file = currentFile;
        return file == null ? "" : file.getAbsolutePath();
    }

    @Override
    public void close() throws IOException {
        BufferedOutputStream stream = out;
        out = null;
        if (stream != null) {
            stream.flush();
            stream.close();
        }
    }

    private void rotate() throws IOException {
        close();
        part++;
        openNextFile();
    }

    private void openNextFile() throws IOException {
        String fileName = String.format(Locale.US, "gps_%s_part%03d.nmea", sessionName, part);
        File file = new File(directory, fileName);
        currentFile = file;
        currentFileBytes = 0L;
        out = new BufferedOutputStream(new FileOutputStream(file, true), FILE_BUFFER_SIZE);
    }

    private BufferedOutputStream requireStream() throws IOException {
        BufferedOutputStream stream = out;
        if (stream == null) {
            throw new IOException("NMEA output stream is closed.");
        }
        return stream;
    }
}
