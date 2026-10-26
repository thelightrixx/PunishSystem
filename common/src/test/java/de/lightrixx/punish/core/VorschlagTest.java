package de.lightrixx.punish.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VorschlagTest {

    @Test
    void befehlKette_ipFaelltAufBasisZurueck() {
        assertEquals(List.of("ban-ip", "ban"), Konfiguration.vorschlagBefehlKette("ban-ip"));
        assertEquals(List.of("mute-ip", "mute"), Konfiguration.vorschlagBefehlKette("Mute-IP"));
        assertEquals(List.of("timeout-ip", "timeout"), Konfiguration.vorschlagBefehlKette("timeout-ip"));
    }

    @Test
    void befehlKette_einfachUndNormalisiert() {
        assertEquals(List.of("ban"), Konfiguration.vorschlagBefehlKette("ban"));
        assertEquals(List.of("ban"), Konfiguration.vorschlagBefehlKette("  BAN  "));
        assertEquals(List.of("ban"), Konfiguration.vorschlagBefehlKette("/ban"));
        assertTrue(Konfiguration.vorschlagBefehlKette("").isEmpty());
        assertTrue(Konfiguration.vorschlagBefehlKette(null).isEmpty());
        assertTrue(Konfiguration.vorschlagBefehlKette("   ").isEmpty());
    }

    @Test
    void defaults_fallenAufGlobaleListenZurueck() {
        Konfiguration config = mock(Konfiguration.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(config.tabGrundListe()).thenReturn(List.of("G1"));
        when(config.tabDauerListe()).thenReturn(List.of("D1"));

        assertEquals(List.of("G1"), config.grundListe("ban"));
        assertEquals(List.of("G1"), config.grundListe("ban-ip"));
        assertEquals(List.of("G1"), config.grundListe("unbekannt-befehl"));
        assertEquals(List.of("D1"), config.dauerListe("mute"));
        assertEquals(List.of("D1"), config.dauerListe("mute-ip"));
        assertNull(config.empfohleneDauer("ban", "Cheating"));
        assertNull(config.empfohleneDauer("ban", null));
        assertTrue(config.vorlagenFuer("ban").isEmpty());
    }
}
