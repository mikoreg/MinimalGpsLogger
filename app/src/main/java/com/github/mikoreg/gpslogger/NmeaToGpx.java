package com.github.mikoreg.gpslogger;

import android.location.Location;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class NmeaToGpx {
    private NmeaToGpx() {}

    /**
     * Converts a raw NMEA file to GPX format with filters.
     * @param nmeaFile The source .nmea file.
     * @param minIntervalMs Minimum time between GPX points in milliseconds. 0 for all points.
     * @param minDistanceMeters Minimum distance between GPX points in meters. 0 to disable.
     * @return The created .gpx file.
     */
    static File convert(File nmeaFile, long minIntervalMs, float minDistanceMeters) throws Exception {
        String baseName = nmeaFile.getName();
        if (baseName.endsWith(".nmea")) {
            baseName = baseName.substring(0, baseName.length() - 5);
        }
        File gpxFile = new File(nmeaFile.getParentFile(), baseName + ".gpx");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(nmeaFile), StandardCharsets.US_ASCII));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(gpxFile), StandardCharsets.UTF_8))) {

            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<gpx version=\"1.1\" creator=\"MinimalGpsLogger\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
            writer.write("  <trk>\n");
            writer.write("    <name>Track " + baseName + "</name>\n");
            writer.write("    <trkseg>\n");

            MutableNmeaStats tempStats = new MutableNmeaStats("temp");
            
            // If interval is >= 1s, we use 1s resolution. Otherwise, we include milliseconds.
            String timePattern = (minIntervalMs >= 1000) 
                    ? "yyyy-MM-dd'T'HH:mm:ss'Z'" 
                    : "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
            SimpleDateFormat isoFormat = new SimpleDateFormat(timePattern, Locale.US);
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            String line;
            long lastWrittenTs = -1;
            double lastWrittenLat = Double.NaN;
            double lastWrittenLon = Double.NaN;

            float[] distanceResult = new float[1];
            List<GpxPoint> buffer = new ArrayList<>();
            long currentSecond = -1;
            long virtualTsCounter = System.currentTimeMillis();

            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("$")) continue;
                byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
                
                NmeaParser.parseForStats(bytes, bytes.length, tempStats, virtualTsCounter);
                NmeaStats snap = tempStats.snapshot(virtualTsCounter);
                
                // GGA, RMC and GLL sentences usually contain position
                boolean isPosSentence = line.contains("GGA") || line.contains("RMC") || line.contains("GLL");
                
                if (isPosSentence && snap.fixValid() && !Double.isNaN(snap.latitude()) && !Double.isNaN(snap.longitude())) {
                    long currentTs = snap.utcTimestampMs();
                    if (currentTs <= 0) {
                        // Monotonic fallback if NMEA has no timestamp
                        currentTs = virtualTsCounter++;
                    }

                    // 1. Time Filter
                    if (lastWrittenTs != -1 && minIntervalMs > 0) {
                        if (currentTs > lastWrittenTs) {
                            if (currentTs - lastWrittenTs < minIntervalMs) continue;
                        } else if (currentTs == lastWrittenTs) {
                            // If multiple points share the same timestamp (second precision in NMEA),
                            // we allow them only if they fit into the requested rate for one second.
                            if ((buffer.size() + 1) * minIntervalMs > 1000) continue;
                        } else {
                            // Out of order points (rare in files) - we accept them if they are far enough
                            // or just skip to keep GPX monotonic. Let's skip.
                            continue;
                        }
                    }

                    // 2. Distance/Motion Filter
                    if (!Double.isNaN(lastWrittenLat) && minDistanceMeters > 0) {
                        Location.distanceBetween(lastWrittenLat, lastWrittenLon, snap.latitude(), snap.longitude(), distanceResult);
                        if (distanceResult[0] < minDistanceMeters) continue;
                    }

                    // Group points by integer second to allow even distribution
                    long second = currentTs / 1000;
                    if (currentSecond != -1 && second != currentSecond) {
                        flushBuffer(writer, buffer, isoFormat);
                        buffer.clear();
                    }
                    
                    currentSecond = second;
                    
                    // Duplicate suppression in the same second
                    boolean identical = false;
                    if (!buffer.isEmpty()) {
                        GpxPoint last = buffer.get(buffer.size() - 1);
                        if (last.lat == snap.latitude() && last.lon == snap.longitude()) {
                            if (Double.isNaN(last.ele) && Double.isNaN(snap.altitudeMeters())) {
                                identical = true;
                            } else if (last.ele == snap.altitudeMeters()) {
                                identical = true;
                            }
                        }
                    }

                    if (!identical) {
                        buffer.add(new GpxPoint(snap.latitude(), snap.longitude(), snap.altitudeMeters(), currentTs));
                        lastWrittenTs = currentTs;
                        lastWrittenLat = snap.latitude();
                        lastWrittenLon = snap.longitude();
                    }
                }
            }
            
            if (!buffer.isEmpty()) {
                flushBuffer(writer, buffer, isoFormat);
            }

            writer.write("    </trkseg>\n");
            writer.write("  </trk>\n");
            writer.write("</gpx>\n");
        }
        return gpxFile;
    }

    private static void flushBuffer(BufferedWriter writer, List<GpxPoint> buffer, SimpleDateFormat isoFormat) throws IOException {
        int n = buffer.size();
        for (int i = 0; i < n; i++) {
            GpxPoint p = buffer.get(i);
            long timeToWrite = p.ts;
            
            // If more than one point in this second, space them out evenly
            if (n > 1) {
                long baseTs = (p.ts / 1000) * 1000;
                timeToWrite = baseTs + (i * 1000L / n);
            }

            writer.write("      <trkpt lat=\"" + p.lat + "\" lon=\"" + p.lon + "\">\n");
            if (!Double.isNaN(p.ele)) {
                writer.write("        <ele>" + p.ele + "</ele>\n");
            }
            writer.write("        <time>" + isoFormat.format(new Date(timeToWrite)) + "</time>\n");
            writer.write("      </trkpt>\n");
        }
    }

    private static final class GpxPoint {
        final double lat, lon, ele;
        final long ts;

        GpxPoint(double lat, double lon, double ele, long ts) {
            this.lat = lat;
            this.lon = lon;
            this.ele = ele;
            this.ts = ts;
        }
    }
}
