package com.github.mikoreg.gpslogger;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class NmeaParserTest {
    @Test
    public void checksumAcceptsKnownSentence() {
        byte[] line = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
                .getBytes(StandardCharsets.US_ASCII);

        Assert.assertTrue(NmeaChecksum.isValid(line, line.length));
    }

    @Test
    public void parserReadsGgaPosition() {
        MutableNmeaStats stats = new MutableNmeaStats("test");
        byte[] line = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
                .getBytes(StandardCharsets.US_ASCII);

        long now = 1000000L;
        NmeaParser.parseForStats(line, line.length, stats, now);
        NmeaStats snap = stats.snapshot(now);

        Assert.assertEquals(48.1173, snap.latitude(), 0.0001);
        Assert.assertEquals(11.51666, snap.longitude(), 0.0001);
        Assert.assertEquals(8, snap.satellitesUsed());
        Assert.assertEquals(0.9, snap.hdop(), 0.01);
    }
    
    @Test
    public void parserParsesTimestampWithMillis() {
        MutableNmeaStats stats = new MutableNmeaStats("test");
        
        // Let's use a real RMC from user's file and fix checksum if needed.
        // $GNRMC,120428.00,A,5123.20019,N,02109.54547,E,0.053,,250426,,,D,V*1F
        byte[] rmc = "$GNRMC,120428.123,A,5123.20019,N,02109.54547,E,0.053,,250426,,,D,V"
                .getBytes(StandardCharsets.US_ASCII);
        
        // Calculate checksum
        int xor = 0;
        for (int i = 1; i < rmc.length; i++) {
            xor ^= rmc[i] & 0xFF;
        }
        String hex = Integer.toHexString(xor).toUpperCase();
        if (hex.length() == 1) hex = "0" + hex;
        
        byte[] rmcWithSum = ("$GNRMC,120428.123,A,5123.20019,N,02109.54547,E,0.053,,250426,,,D,V*" + hex)
                .getBytes(StandardCharsets.US_ASCII);
        
        long now = 1000000L;
        NmeaParser.parseForStats(rmcWithSum, rmcWithSum.length, stats, now);
        NmeaStats snap = stats.snapshot(now);
        
        Assert.assertEquals(123, snap.utcTimestampMs() % 1000);
    }
}
