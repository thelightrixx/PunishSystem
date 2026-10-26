package de.lightrixx.punish.util;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ZeitFormatThreadSafetyTest {

    @Test
    void gleichzeitigeZonenwechselUndFormatierungBleibtKonsistent() throws Exception {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        ZoneId utc = ZoneId.of("UTC");
        long probe = 1700000000000L;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    if (idx % 10 == 0) {
                        ZeitFormat.setAnzeigeZone(idx % 20 == 0 ? berlin : utc);
                    }
                    return ZeitFormat.formatZeitpunkt(probe);
                }));
            }
            start.countDown();
            for (Future<String> f : futures) {
                String ergebnis = f.get(10, TimeUnit.SECONDS);
                assertNotNull(ergebnis);
                assertNotEquals("unbekannt", ergebnis);

                assertTrue(ergebnis.matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}"),
                        "Unerwartetes Format: " + ergebnis);
            }
        } finally {
            pool.shutdownNow();
            ZeitFormat.setAnzeigeZone(berlin);
        }
    }

    @Test
    void nullZoneWirdIgnoriert() {
        ZoneId vorher = ZeitFormat.anzeigeZone();
        ZeitFormat.setAnzeigeZone(null);
        assertEquals(vorher, ZeitFormat.anzeigeZone());
    }
}
