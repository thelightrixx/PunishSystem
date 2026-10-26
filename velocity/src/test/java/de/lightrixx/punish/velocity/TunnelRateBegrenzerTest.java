package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelRateBegrenzerTest {

    private static StrafListener.TunnelRateBegrenzer bucket(double proSekunde, double kapazitaet,
                                                           AtomicLong uhr) {
        return new StrafListener.TunnelRateBegrenzer(proSekunde, kapazitaet, uhr::get);
    }

    @Test
    void befehlBucket20ProSekunde() {
        AtomicLong uhr = new AtomicLong(0L);
        var bucket = bucket(20.0d, 20.0d, uhr);
        for (int i = 0; i < 20; i++) {
            assertTrue(bucket.versuche(), "Burst bis 20 erlaubt");
        }
        assertFalse(bucket.versuche(), "21. Nachricht sofort verworfen");
        uhr.addAndGet(1000L);
        assertTrue(bucket.versuche(), "Nach 1s wieder Token verfügbar");
    }

    @Test
    void metaBucket1ProSekunde() {
        AtomicLong uhr = new AtomicLong(0L);
        var bucket = bucket(1.0d, 1.0d, uhr);
        assertTrue(bucket.versuche());
        assertFalse(bucket.versuche(), "Zweite META innerhalb 1s ignoriert");
        uhr.addAndGet(999L);
        assertFalse(bucket.versuche(), "Nach 999ms noch kein Token");
        uhr.addAndGet(1L);
        assertTrue(bucket.versuche(), "Nach 1000ms wieder erlaubt");
    }

    @Test
    void kapazitaetDeckelRefill() {
        AtomicLong uhr = new AtomicLong(0L);
        var bucket = bucket(20.0d, 20.0d, uhr);
        uhr.addAndGet(60_000L);
        int erlaubt = 0;
        for (int i = 0; i < 25; i++) {
            if (bucket.versuche()) {
                erlaubt++;
            }
        }
        assertTrue(erlaubt == 20, "Deckel bei 20 trotz langer Pause, war " + erlaubt);
    }
}
