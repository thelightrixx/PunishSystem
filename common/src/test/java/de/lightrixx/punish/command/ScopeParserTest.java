package de.lightrixx.punish.command;

import de.lightrixx.punish.model.ServerScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScopeParserTest {

    @Test
    void ohneFlag_giltStandard() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "7d", "Cheating"},
                ServerScope.server("survival"), List.of("lobby", "survival"));
        assertFalse(ergebnis.hatteFlag());
        assertEquals(ServerScope.server("survival"), ergebnis.scope());
        assertArrayEquals(new String[]{"Notch", "7d", "Cheating"}, ergebnis.bereinigteArgs());
    }

    @Test
    void lobbyFlag_wirdServerScope() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "7d", "Cheating", "--lobby"},
                ServerScope.server("survival"), List.of("lobby", "survival"));
        assertTrue(ergebnis.hatteFlag());
        assertEquals(ServerScope.server("lobby"), ergebnis.scope());
        assertArrayEquals(new String[]{"Notch", "7d", "Cheating"}, ergebnis.bereinigteArgs());
    }

    @Test
    void globalFlag() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "--global"},
                ServerScope.server("survival"), List.of("lobby"));
        assertTrue(ergebnis.hatteFlag());
        assertEquals(ServerScope.global(), ergebnis.scope());
        assertArrayEquals(new String[]{"Notch"}, ergebnis.bereinigteArgs());
    }

    @Test
    void serverGleichForm() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "--server=lobby"},
                ServerScope.server("survival"), List.of("lobby"));
        assertTrue(ergebnis.hatteFlag());
        assertEquals(ServerScope.server("lobby"), ergebnis.scope());
    }

    @Test
    void unbekanntWirdNieAlsNeuerScopeAkzeptiert() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "--unbekannt"},
                ServerScope.server("survival"), List.of("lobby"));
        assertFalse(ergebnis.hatteFlag());
        assertEquals(ServerScope.server("survival"), ergebnis.scope());
        assertArrayEquals(new String[]{"Notch", "--unbekannt"}, ergebnis.bereinigteArgs());
    }

    @Test
    void unbekanntAlsSelektorFuerAufhebungErlaubt() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "--unbekannt"},
                ServerScope.server("survival"), List.of("lobby"), true);
        assertTrue(ergebnis.hatteFlag());
        assertEquals(ServerScope.unbekannt(), ergebnis.scope());
        assertArrayEquals(new String[]{"Notch"}, ergebnis.bereinigteArgs());
    }

    @Test
    void vorschlaegeMitUnbekanntFuerAufhebung() {
        List<String> treffer = ScopeParser.vorschlaege("--unb", List.of("lobby"), true);
        assertTrue(treffer.contains("--unbekannt"));
        List<String> ohne = ScopeParser.vorschlaege("--unb", List.of("lobby"), false);
        assertFalse(ohne.contains("--unbekannt"));
    }

    @Test
    void ungueltigesFlag_bleibtGrundtext() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "7d", "--!!"},
                ServerScope.server("survival"), List.of("lobby"));
        assertFalse(ergebnis.hatteFlag());
        assertArrayEquals(new String[]{"Notch", "7d", "--!!"}, ergebnis.bereinigteArgs());
    }

    @Test
    void vorschlaege_listenGlobalZuerstDannServer() {
        List<String> alle = ScopeParser.vorschlaege("--", List.of("survival", "lobby"));
        assertTrue(alle.contains("--global"));
        assertTrue(alle.contains("--lobby"));
        assertTrue(alle.contains("--survival"));
        assertEquals("--global", alle.get(0));
    }

    @Test
    void vorschlaege_filternPrefix() {
        List<String> treffer = ScopeParser.vorschlaege("--lob", List.of("lobby", "survival"));
        assertTrue(treffer.contains("--lobby"));
        assertFalse(treffer.contains("--survival"));
        assertFalse(treffer.contains("--global"));
    }

    @Test
    void vorschlaege_ohneServerliste_nurGlobal() {
        List<String> treffer = ScopeParser.vorschlaege("--", List.of());
        assertTrue(treffer.contains("--global"));
    }

    @Test
    void keinScopeFlag_keineVorschlaege() {
        assertTrue(ScopeParser.vorschlaege("Notch", List.of("lobby")).isEmpty());
    }

    @Test
    void silentFlag_wirdGestrippt() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "7d", "Cheating", "-s"},
                ServerScope.server("survival"), List.of("lobby", "survival"));
        assertTrue(ergebnis.silent());
        assertFalse(ergebnis.hatteFlag());
        assertEquals(ServerScope.server("survival"), ergebnis.scope());
        assertArrayEquals(new String[]{"Notch", "7d", "Cheating"}, ergebnis.bereinigteArgs());
    }

    @Test
    void silentUndScope_inBeidenReihenfolgen() {
        ScopeParser.Ergebnis a = ScopeParser.parsen(
                new String[]{"Notch", "7d", "Cheating", "-s", "--global"},
                ServerScope.server("survival"), List.of("lobby", "survival"));
        assertTrue(a.silent());
        assertTrue(a.hatteFlag());
        assertEquals(ServerScope.global(), a.scope());
        assertArrayEquals(new String[]{"Notch", "7d", "Cheating"}, a.bereinigteArgs());

        ScopeParser.Ergebnis b = ScopeParser.parsen(
                new String[]{"Notch", "7d", "Cheating", "--global", "-s"},
                ServerScope.server("survival"), List.of("lobby", "survival"));
        assertTrue(b.silent());
        assertTrue(b.hatteFlag());
        assertEquals(ServerScope.global(), b.scope());
        assertArrayEquals(new String[]{"Notch", "7d", "Cheating"}, b.bereinigteArgs());
    }

    @Test
    void doppelSilent_nurEinmalGestrippt() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "-s", "-s"},
                ServerScope.server("survival"), List.of("lobby"));
        assertTrue(ergebnis.silent());
        assertArrayEquals(new String[]{"Notch"}, ergebnis.bereinigteArgs());
    }

    @Test
    void silentMittenImGrund_bleibtText() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "7d", "-s", "Cheating"},
                ServerScope.server("survival"), List.of("lobby"));
        assertFalse(ergebnis.silent());
        assertArrayEquals(new String[]{"Notch", "7d", "-s", "Cheating"}, ergebnis.bereinigteArgs());
    }

    @Test
    void silentOhneScope_parsenSilent() {
        ScopeParser.SilentErgebnis ergebnis = ScopeParser.parsenSilent(
                new String[]{"Notch", "Grund", "-s"});
        assertTrue(ergebnis.silent());
        assertArrayEquals(new String[]{"Notch", "Grund"}, ergebnis.bereinigteArgs());

        ScopeParser.SilentErgebnis ohne = ScopeParser.parsenSilent(
                new String[]{"Notch", "Grund"});
        assertFalse(ohne.silent());
        assertArrayEquals(new String[]{"Notch", "Grund"}, ohne.bereinigteArgs());
    }

    @Test
    void silentFlagErkennung_nurExakt() {
        assertTrue(ScopeParser.istSilentFlag("-s"));
        assertFalse(ScopeParser.istSilentFlag("--silent"));
        assertFalse(ScopeParser.istSilentFlag("-S"));
        assertFalse(ScopeParser.istSilentFlag(null));
    }

    @Test
    void grundSaeuberung_entferntSilent() {
        assertEquals("Cheating", ScopeParser.saeubereGrund("Cheating -s"));
        assertEquals("Cheating --lobby", ScopeParser.saeubereGrund("Cheating --lobby -s"));
        assertEquals("Cheating", ScopeParser.saeubereGrund("Cheating --lobby -s",
                java.util.List.of("lobby", "survival")));
        assertEquals("Cheating --evil", ScopeParser.saeubereGrund("Cheating --evil",
                java.util.List.of("lobby", "survival")));
        assertEquals("Cheating", ScopeParser.saeubereGrund("Cheating --global",
                java.util.List.of("lobby")));
    }
}
