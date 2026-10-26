package de.lightrixx.punish.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StartBannerTest {

    @Test
    void enthaeltVersionPlattformUndAutor() {
        List<String> zeilen = StartBanner.zeilen("v4.0-beta+EA10c", "Velocity");
        assertEquals(6, zeilen.size());
        String gesamt = String.join("\n", zeilen);
        assertTrue(gesamt.contains("PunishSystem v4.0-beta+EA10c"));
        assertTrue(gesamt.contains("by lightrixx"));
        assertTrue(gesamt.contains("Running on Velocity"));
        assertTrue(zeilen.get(zeilen.size() - 1).isEmpty());
        for (String zeile : zeilen) {
            if (zeile.isEmpty()) {
                continue;
            }
            assertTrue(zeile.endsWith("[0m"));
            assertFalse(zeile.contains("null"));
        }
    }

    @Test
    void nullEingabenFallenAufUnbekanntZurueck() {
        List<String> zeilen = StartBanner.zeilen(null, "  ");
        assertEquals(6, zeilen.size());
        String gesamt = String.join("\n", zeilen);
        assertTrue(gesamt.contains("unbekannt"));
        assertFalse(gesamt.contains("null"));
    }
}
