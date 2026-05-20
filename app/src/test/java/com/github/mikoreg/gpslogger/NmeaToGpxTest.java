package com.github.mikoreg.gpslogger;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class NmeaToGpxTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String withSum(String s) {
        return "$" + s + "*" + NmeaChecksum.calculate(s) + "\n";
    }

    @Test
    public void convertBasicFile() throws Exception {
        File nmeaFile = folder.newFile("test.nmea");
        String content = 
            withSum("GNRMC,120428.00,A,5123.20019,N,02109.54547,E,0.053,,250426,,,D,V") +
            withSum("GNGGA,120428.00,5123.20019,N,02109.54547,E,1,12,0.8,110.5,M,34.0,M,,");
        Files.write(nmeaFile.toPath(), content.getBytes(StandardCharsets.US_ASCII));

        File gpxFile = NmeaToGpx.convert(nmeaFile, 1000, 0);
        Assert.assertTrue(gpxFile.exists());
        
        List<String> lines = Files.readAllLines(gpxFile.toPath());
        int count = 0;
        for (String line : lines) {
            if (line.contains("<trkpt")) count++;
        }
        // Should be 1 because coordinates are identical and in the same second
        Assert.assertEquals(1, count);
    }

    @Test
    public void testInterpolation() throws Exception {
        File nmeaFile = folder.newFile("test_int.nmea");
        String content = 
            withSum("GNGGA,120428.00,5123.20019,N,02109.54547,E,1,12,0.8,110.5,M,34.0,M,,") +
            withSum("GNGGA,120428.00,5123.20100,N,02109.54600,E,1,12,0.8,110.5,M,34.0,M,,");
        Files.write(nmeaFile.toPath(), content.getBytes(StandardCharsets.US_ASCII));

        // Use 0 interval to allow both points
        File gpxFile = NmeaToGpx.convert(nmeaFile, 0, 0);
        List<String> lines = Files.readAllLines(gpxFile.toPath());
        
        int count = 0;
        for (String line : lines) {
            if (line.contains("<trkpt")) count++;
        }
        Assert.assertEquals(2, count);
        
        boolean has000 = false;
        boolean has500 = false;
        for (String line : lines) {
            if (line.contains(":28.000Z")) has000 = true;
            if (line.contains(":28.500Z")) has500 = true;
        }
        Assert.assertTrue("Should have .000 ms", has000);
        Assert.assertTrue("Should have .500 ms", has500);
    }
}
