package de.lightrixx.punish.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZeitFormatTest {

    @Test
    void formatRestzeitTage() {
        long siebenTage = 7L * 24 * 60 * 60 * 1000;
        String result = ZeitFormat.formatRestzeit(siebenTage);
        assertTrue(result.contains("7 Tage"));
    }

    @Test
    void formatRestzeitStundenUndMinuten() {
        long dauer = 3600000L + 60000L * 30;
        String result = ZeitFormat.formatRestzeit(dauer);
        assertTrue(result.contains("1 Stunde"));
        assertTrue(result.contains("30 Minuten"));
    }

    @Test
    void formatRestzeitAbgelaufenBeiNull() {
        assertEquals("abgelaufen", ZeitFormat.formatRestzeit(0));
        assertEquals("abgelaufen", ZeitFormat.formatRestzeit(-100));
    }

    @Test
    void formatRestzeitSekundenFallback() {

        String result = ZeitFormat.formatRestzeit(500);
        assertTrue(result.contains("Sekunde"));
    }

    @Test
    void formatZeitpunktLiefertDatum() {
        long jetzt = System.currentTimeMillis();
        String result = ZeitFormat.formatZeitpunkt(jetzt);
        assertNotNull(result);
        assertTrue(result.matches("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}"));
    }
}
