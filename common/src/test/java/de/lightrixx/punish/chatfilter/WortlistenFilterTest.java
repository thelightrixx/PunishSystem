package de.lightrixx.punish.chatfilter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WortlistenFilterTest {

    private static WortlistenFilter filter(String... zeilen) {
        return new WortlistenFilter(List.of(zeilen));
    }

    @Test
    void exaktesWort_wirdGefunden() {
        var filter = filter("hurensohn");
        assertTrue(filter.ersterTreffer("Du Hurensohn!").isPresent());
    }

    @Test
    void leetspeak_wirdTrotzListeGefunden() {
        var filter = filter("hurensohn");
        assertEquals("hurensohn", filter.ersterTreffer("Du Hur3nsohn").orElseThrow());
    }

    @Test
    void zerlegteSchreibweise_wirdGefunden() {
        var filter = filter("hurensohn");
        assertTrue(filter.ersterTreffer("h u r e n s o h n").isPresent());
    }

    @Test
    void teilwort_ohneGrenze_keinTreffer() {
        var filter = filter("arsch");
        assertTrue(filter.ersterTreffer("Hey, willkommen!").isEmpty());
    }

    @Test
    void kurzesWort_nurMitGrenze() {
        var filter = filter("ab");
        assertTrue(filter.ersterTreffer("haben").isEmpty());
        assertTrue(filter.ersterTreffer("ab morgen").isPresent());
    }

    @Test
    void kommentarUndLeerzeilen_werdenIgnoriert() {
        var filter = filter("# Kommentar", "", "  ", "idiot");
        assertEquals(List.of("idiot"), filter.eintraege());
    }

    @Test
    void leereListe_wirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class, () -> new WortlistenFilter(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WortlistenFilter(List.of("# nur kommentar")));
    }

    @Test
    void lehrerText_keinTreffer() {
        var filter = filter("hurensohn", "idiot");
        assertTrue(filter.ersterTreffer("   ").isEmpty());
    }

    @Test
    void gueltigerEintrag_validiert() {
        assertEquals("hurensohn", WortlistenFilter.gueltigerEintrag(" HurenS0hn ").orElseThrow());
        assertTrue(WortlistenFilter.gueltigerEintrag("x").isEmpty());
        assertTrue(WortlistenFilter.gueltigerEintrag("a".repeat(51)).isEmpty());
        assertTrue(WortlistenFilter.gueltigerEintrag("a\nb").isEmpty());
        assertEquals("iii", WortlistenFilter.gueltigerEintrag("!!!").orElseThrow());
        assertTrue(WortlistenFilter.gueltigerEintrag(null).isEmpty());
    }

    @Test
    void zeichenWiederholungen_werdenErkannt() {
        var filter = filter("hurensohn");
        assertEquals("hurensohn", filter.ersterTreffer("Du Huuuurensohn!").orElseThrow());
        assertTrue(filter.ersterTreffer("h u r e e e e n s o h n").isPresent());
        assertTrue(filter.ersterTreffer("HUUURRENNSOOOHN").isPresent());
    }

    @Test
    void eintragMitWiederholungen_trifftAuchDieNormalform() {
        var filter = filter("fuuuck");
        assertTrue(filter.ersterTreffer("fuck dich").isPresent());
        assertTrue(filter.ersterTreffer("fuuuuuck").isPresent());
    }

    @Test
    void normaleTexteOhneWiederholung_unveraendert() {
        var filter = filter("hurensohn");
        assertTrue(filter.ersterTreffer("Hallo Welt").isEmpty());
        assertTrue(filter.ersterTreffer("Marschieren ist schön").isEmpty());
    }

    @Test
    void kompaktMatchBeginntNichtMittenImWort() {
        var filter = filter("arsch");
        assertTrue(filter.ersterTreffer("war schon").isEmpty());
        assertTrue(filter.ersterTreffer("Marschieren ist schön").isEmpty());
        assertTrue(filter.ersterTreffer("Barschfilet ist lecker").isEmpty());
    }

    @Test
    void kompaktMatchAbWortanfangBleibtTreffer() {
        var filter = filter("arsch");
        assertTrue(filter.ersterTreffer("du Arschloch!").isPresent());
        assertTrue(filter.ersterTreffer("arsch1").isPresent());
        assertTrue(filter.ersterTreffer("a r s c h").isPresent());
    }

    @Test
    void kompaktMatchAuchAbSpaeteremWortanfang() {
        var filter = filter("arsch");
        assertTrue(filter.ersterTreffer("war arsch").isPresent());
        assertTrue(filter.ersterTreffer("so ein arsch sein").isPresent());
    }
}
