package com.github.mikoreg.gpslogger;

import android.location.Location;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
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
        String name = nmeaFile.getName();
        if (name.endsWith(".nmea")) {
            name = name.substring(0, name.length() - 5);
        }
        File gpxFile = new File(nmeaFile.getParentFile(), name + ".gpx");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(nmeaFile), StandardCharsets.US_ASCII));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(gpxFile), StandardCharsets.UTF_8))) {

            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<gpx version=\"1.1\" creator=\"MinimalGpsLogger\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
            writer.write("  <trk>\n");
            writer.write("    <name>Track " + name + "</name>\n");
            writer.write("    <trkseg>\n");

            MutableNmeaStats tempStats = new MutableNmeaStats("temp");
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            String line;
            long lastWrittenTs = -1;
            double lastWrittenLat = Double.NaN;
            double lastWrittenLon = Double.NaN;

            float[] distanceResult = new float[1];

            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("$")) continue;
                byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
                
                long now = System.currentTimeMillis();
                NmeaParser.parseForStats(bytes, bytes.length, tempStats, now);
                NmeaStats snap = tempStats.snapshot(now);
                
                boolean isPosSentence = line.contains("GGA") || line.contains("RMC");
                
                if (isPosSentence && snap.fixValid() && !Double.isNaN(snap.latitude()) && !Double.isNaN(snap.longitude())) {
                    long currentTs = snap.utcTimestampMs();
                    if (currentTs <= 0) currentTs = now;

                    // 1. Time Filter
                    if (lastWrittenTs != -1 && minIntervalMs > 0) {
                        if (currentTs - lastWrittenTs < minIntervalMs) continue;
                    }

                    // 2. Distance/Motion Filter
                    if (!Double.isNaN(lastWrittenLat) && minDistanceMeters > 0) {
                        Location.distanceBetween(lastWrittenLat, lastWrittenLon, snap.latitude(), snap.longitude(), distanceResult);
                        if (distanceResult[0] < minDistanceMeters) continue;
                    }

                    writer.write("      <trkpt lat=\"" + snap.latitude() + "\" lon=\"" + snap.longitude() + "\">\n");
                    if (!Double.isNaN(snap.altitudeMeters())) {
                        writer.write("        <ele>" + snap.altitudeMeters() + "</ele>\n");
                    }
                    writer.write("        <time>" + isoFormat.format(new Date(currentTs)) + "</time>\n");
                    writer.write("      </trkpt>\n");
                    
                    lastWrittenTs = currentTs;
                    lastWrittenLat = snap.latitude();
                    lastWrittenLon = snap.longitude();
                }
            }

            writer.write("    </trkseg>\n");
            writer.write("  </trk>\n");
            writer.write("</gpx>\n");
        }
        return gpxFile;
    }
}
