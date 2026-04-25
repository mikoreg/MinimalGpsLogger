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

        NmeaParser.parseForStats(line, line.length, stats);
        String ui = stats.snapshot().formatForUi();

        Assert.assertTrue(ui.contains("48.1173000"));
        Assert.assertTrue(ui.contains("11.5166667"));
        Assert.assertTrue(ui.contains("satellites used: 8"));
        Assert.assertTrue(ui.contains("HDOP: 0.90"));
    }
}
