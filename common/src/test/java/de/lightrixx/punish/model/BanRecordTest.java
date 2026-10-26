package de.lightrixx.punish.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BanRecordTest {

    private static final UUID UUID_A = UUID.randomUUID();
    private static final long JETZT = System.currentTimeMillis();

    @Test
    void permanenterBannIstImmerAktiv() {
        BanRecord perm = new BanRecord(UUID_A, "TestSpieler", "Grund", "Console", JETZT, null, false);
        assertTrue(perm.istPermanent());
        assertTrue(perm.istAktiv(JETZT + 1000000L));
        assertFalse(perm.istAbgelaufen(JETZT + 1000000L));
    }

    @Test
    void zeitBannAktivVorAblauf() {
        long ablauf = JETZT + 60000L;
        BanRecord bann = new BanRecord(UUID_A, "TestSpieler", "Grund", "Console", JETZT, ablauf, false);
        assertFalse(bann.istPermanent());
        assertTrue(bann.istAktiv(JETZT + 1000L));
        assertFalse(bann.istAbgelaufen(JETZT + 1000L));
    }

    @Test
    void zeitBannAbgelaufenNachAblauf() {
        long ablauf = JETZT + 60000L;
        BanRecord bann = new BanRecord(UUID_A, "TestSpieler", "Grund", "Console", JETZT, ablauf, false);
        assertFalse(bann.istAktiv(ablauf));
        assertFalse(bann.istAktiv(ablauf + 1));
        assertTrue(bann.istAbgelaufen(ablauf));
        assertTrue(bann.istAbgelaufen(ablauf + 1));
    }

    @Test
    void autoBannFlagWirdKorrektGesetzt() {
        BanRecord auto = new BanRecord(UUID_A, "TestSpieler", "Grund", "System", JETZT, JETZT + 10000L, true);
        assertTrue(auto.autoBann());
        BanRecord normal = new BanRecord(UUID_A, "TestSpieler", "Grund", "Admin", JETZT, JETZT + 10000L, false);
        assertFalse(normal.autoBann());
    }

    @Test
    void ablaufVorErstellungWirftException() {
        assertThrows(IllegalArgumentException.class, () ->
                new BanRecord(UUID_A, "TestSpieler", "Grund", "Console", JETZT, JETZT - 1, false));
    }

    @Test
    void nullUuidWirftException() {
        assertThrows(NullPointerException.class, () ->
                new BanRecord(null, "TestSpieler", "Grund", "Console", JETZT, null, false));
    }

    @Test
    void leererNameWirftException() {
        assertThrows(IllegalArgumentException.class, () ->
                new BanRecord(UUID_A, " ", "Grund", "Console", JETZT, null, false));
    }
}
