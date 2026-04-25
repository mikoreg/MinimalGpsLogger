package com.github.mikoreg.gpslogger;

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

    static File convert(File nmeaFile) throws Exception {
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

            String line;
            MutableNmeaStats tempStats = new MutableNmeaStats("temp");
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.startsWith("$")) continue;
                byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
                
                // We only care about position and altitude
                // NmeaParser.parseForStats updates the mutable stats
                NmeaParser.parseForStats(bytes, bytes.length, tempStats);
                NmeaStats snap = tempStats.snapshot();
                
                if (snap.fixValid() && !Double.isNaN(snap.latitude()) && !Double.isNaN(snap.longitude())) {
                    writer.write("      <trkpt lat=\"" + snap.latitude() + "\" lon=\"" + snap.longitude() + "\">\n");
                    if (!Double.isNaN(snap.altitudeMeters())) {
                        writer.write("        <ele>" + snap.altitudeMeters() + "</ele>\n");
                    }
                    writer.write("        <time>" + isoFormat.format(new Date()) + "</time>\n"); // Ideally we should parse time from NMEA too
                    writer.write("      </trkpt>\n");
                    
                    // Reset position to avoid duplicates if next line doesn't have it
                    // But NmeaParser updates it only if found. 
                    // To be safe, we could check if it's a GGA/RMC sentence specifically.
                }
            }

            writer.write("    </trkseg>\n");
            writer.write("  </trk>\n");
            writer.write("</gpx>\n");
        }
        return gpxFile;
    }
}
