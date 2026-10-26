package de.lightrixx.punish.chatfilter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextNormalisiererTest {

    @Test
    void kleinschreibung() {
        assertEquals("hallo welt", TextNormalisierer.normalisiere("Hallo Welt"));
    }

    @Test
    void leetspeak_wirdAufgeloest() {
        assertEquals("hallo welt", TextNormalisierer.normalisiere("H4ll0 W3lt"));
        assertEquals("du idiot", TextNormalisierer.normalisiere("Du 1d10t"));
        assertEquals("fick dich", TextNormalisierer.normalisiere("F1ck d1ch"));
    }

    @Test
    void sonderzeichen_werdenTrenner() {
        assertEquals("halt dein mauli", TextNormalisierer.normalisiere("Halt-dein_Maul!"));
        assertEquals("h u r e n s o h n", TextNormalisierer.normalisiere("H-U-R-E-N-S-O-H-N"));
    }

    @Test
    void umlaute_werdenAufgeloest() {
        assertEquals("hurensohn", TextNormalisierer.normalisiere("Hürensöhn"));
        assertEquals("strasse", TextNormalisierer.normalisiere("Straße"));
    }

    @Test
    void leerzeichen_werdenZusammengezogen() {
        assertEquals("a b", TextNormalisierer.normalisiere("  a   b  "));
    }

    @Test
    void symbole_werdenAbgebildet() {
        assertEquals("ass", TextNormalisierer.normalisiere("@$€"));
    }

    @Test
    void leetErweitert_2_6_9_Ausrufezeichen_Pipe() {
        assertEquals("z", TextNormalisierer.normalisiere("2"));
        assertEquals("g", TextNormalisierer.normalisiere("6"));
        assertEquals("g", TextNormalisierer.normalisiere("9"));
        assertEquals("i", TextNormalisierer.normalisiere("!"));
        assertEquals("i", TextNormalisierer.normalisiere("|"));
        assertEquals("hgz", TextNormalisierer.normalisiere("H62"));
        assertEquals("hig", TextNormalisierer.normalisiere("H!9"));
    }

    @Test
    void ohneAbstaende_entferntLeerzeichen() {
        assertEquals("hurensohn", TextNormalisierer.ohneAbstaende("h u r e n s o h n"));
    }
}
