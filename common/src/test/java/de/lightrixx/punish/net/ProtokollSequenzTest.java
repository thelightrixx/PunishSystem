package de.lightrixx.punish.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtokollSequenzTest {

    @Test
    void versionSiebenOhneLegacyUntergrenze() {
        assertEquals(7, Protokoll.VERSION);
        assertEquals(7, Protokoll.MIN_VERSION);
        assertEquals(1000L, Protokoll.REPLAY_FENSTER);
    }

    @Test
    void rahmenRoundtrip() throws Exception {
        byte[] paket = Protokoll.hello("lobby", "1.0", "bridge");
        byte[] gerahmt = Protokoll.rahmeMitSequenz(paket, 7L);
        Protokoll.SequenzRahmen rahmen = Protokoll.entrahmeSequenz(gerahmt);
        assertTrue(rahmen.sequenziert());
        assertEquals(7L, rahmen.sequenz());
        assertArrayEquals(paket, rahmen.paket());
    }

    @Test
    void legacyBleibtLesbar() throws Exception {
        byte[] paket = Protokoll.hello("lobby", "1.0", "bridge");
        Protokoll.SequenzRahmen rahmen = Protokoll.entrahmeSequenz(paket);
        assertFalse(rahmen.sequenziert());
        assertArrayEquals(paket, rahmen.paket());
    }

    @Test
    void ungueltigeSequenzAbgewiesen() {
        assertThrows(java.io.IOException.class,
                () -> Protokoll.rahmeMitSequenz(new byte[]{0x01}, 0L));
        assertThrows(java.io.IOException.class,
                () -> Protokoll.entrahmeSequenz(null));
    }

    @Test
    void prueferStrengMonoton() {
        var pruefer = new Protokoll.SequenzPruefer();
        assertTrue(pruefer.akzeptiere(1L));
        assertTrue(pruefer.akzeptiere(2L));
        assertFalse(pruefer.akzeptiere(2L));
        assertFalse(pruefer.akzeptiere(1L));
        assertTrue(pruefer.akzeptiere(3L));
        assertEquals(3L, pruefer.letzte());
    }

    @Test
    void prueferFensterUndResync() {
        var pruefer = new Protokoll.SequenzPruefer();
        assertTrue(pruefer.akzeptiere(5000L));
        assertFalse(pruefer.akzeptiere(3999L));
        assertTrue(pruefer.brauchtResync(3999L));
        assertFalse(pruefer.brauchtResync(4500L));
    }

    @Test
    void helloSetztZurueck() {
        var pruefer = new Protokoll.SequenzPruefer();
        assertTrue(pruefer.akzeptiere(42L));
        pruefer.zuruecksetzen();
        assertEquals(-1L, pruefer.letzte());
        assertTrue(pruefer.akzeptiere(1L));
    }
}
