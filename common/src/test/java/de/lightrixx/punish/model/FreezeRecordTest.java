package de.lightrixx.punish.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FreezeRecordTest {

    private static final UUID UUID_A = UUID.randomUUID();
    private static final long JETZT = System.currentTimeMillis();

    @Test
    void permanenterFreezeIstImmerAktiv() {
        FreezeRecord perm = new FreezeRecord(UUID_A, "TestSpieler", "Grund", "Console", JETZT, Long.MAX_VALUE);
        assertTrue(perm.istPermanent());
        assertTrue(perm.istAktiv(JETZT + 1000000L));
        assertFalse(perm.istAbgelaufen(JETZT + 1000000L));
        assertEquals(Long.MAX_VALUE, perm.verbleibendeMillis(JETZT));
    }

    @Test
    void zeitFreezeAktivVorAblauf() {
        long ablauf = JETZT + 60000L;
        FreezeRecord freeze = new FreezeRecord(UUID_A, "TestSpieler", "Grund", "Console", JETZT, ablauf);
        assertFalse(freeze.istPermanent());
        assertTrue(freeze.istAktiv(JETZT + 1000L));
        assertFalse(freeze.istAbgelaufen(JETZT + 1000L));
    }

    @Test
    void zeitFreezeAbgelaufenNachAblauf() {
        long ablauf = JETZT + 60000L;
        FreezeRecord freeze = new FreezeRecord(UUID_A, "TestSpieler", "Grund", "Console", JETZT, ablauf);
        assertFalse(freeze.istAktiv(ablauf));
        assertTrue(freeze.istAbgelaufen(ablauf));
    }

    @Test
    void ablaufVorErstellungWirftException() {
        assertThrows(IllegalArgumentException.class, () ->
                new FreezeRecord(UUID_A, "TestSpieler", "Grund", "Console", JETZT, JETZT - 1));
    }
}
