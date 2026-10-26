package de.lightrixx.punish.bridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelAuthBremseTest {

    @Test
    void wiederholteFehlversucheSperrenDenSpielerZeitweise() {
        TunnelAuthBremse bremse = new TunnelAuthBremse();
        UUID spieler = UUID.randomUUID();
        long jetzt = 1_000_000L;
        for (int i = 0; i < TunnelAuthBremse.SPIELER_FEHLER_MAX - 1; i++) {
            bremse.fehlschlag(spieler, jetzt + i);
        }
        assertFalse(bremse.gesperrt(spieler, jetzt + 100L));
        bremse.fehlschlag(spieler, jetzt + 100L);
        assertTrue(bremse.gesperrt(spieler, jetzt + 200L));
        assertTrue(bremse.gesperrt(spieler, jetzt + TunnelAuthBremse.SPERRE_MILLIS - 1L));
        assertFalse(bremse.gesperrt(spieler, jetzt + 100L + TunnelAuthBremse.SPERRE_MILLIS));
    }

    @Test
    void erfolgreichePruefungLoeschtDenZaehler() {
        TunnelAuthBremse bremse = new TunnelAuthBremse();
        UUID spieler = UUID.randomUUID();
        for (int i = 0; i < TunnelAuthBremse.SPIELER_FEHLER_MAX; i++) {
            bremse.fehlschlag(spieler, 1000L + i);
        }
        bremse.erfolg(spieler);
        assertFalse(bremse.gesperrt(spieler, 1100L));
    }

    @Test
    void globaleFehlerflutDrosseltWeitereFehlversuche() {
        TunnelAuthBremse bremse = new TunnelAuthBremse();
        long jetzt = 5_000L;
        for (int i = 0; i < TunnelAuthBremse.GLOBAL_FEHLER_MAX; i++) {
            bremse.fehlschlag(UUID.randomUUID(), jetzt);
        }
        assertTrue(bremse.globalGesperrt(jetzt + 1L));
        assertFalse(bremse.globalGesperrt(jetzt + TunnelAuthBremse.FENSTER_MILLIS + 1L));
    }
}
