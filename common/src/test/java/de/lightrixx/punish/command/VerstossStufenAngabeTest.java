package de.lightrixx.punish.command;

import de.lightrixx.punish.model.VerstossStufe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerstossStufenAngabeTest {

    @Test
    @DisplayName("level= am Ende wird erkannt und aus dem Grund entfernt")
    void levelAmEndeWirdErkannt() {
        String[] args = {"add", "Steve", "Cheat", "level=high"};
        var angabe = VerstossCommand.leseStufenAngabe(args, 2);
        assertEquals(VerstossStufe.HIGH, angabe.stufe());
        assertTrue(angabe.angegeben());
        assertFalse(angabe.ungueltig());
        assertEquals(Set.of(3), angabe.ausgeschlossene());
        assertEquals("Cheat", VerstossCommand.grundAusBereich(args, 2, angabe.ausgeschlossene()));
    }

    @Test
    @DisplayName("level= direkt nach dem Spieler wird ebenso erkannt")
    void levelNachSpielerWirdErkannt() {
        String[] args = {"add", "Steve", "level=low", "Cheat"};
        var angabe = VerstossCommand.leseStufenAngabe(args, 2);
        assertEquals(VerstossStufe.LOW, angabe.stufe());
        assertEquals(Set.of(2), angabe.ausgeschlossene());
        assertEquals("Cheat", VerstossCommand.grundAusBereich(args, 2, angabe.ausgeschlossene()));
    }

    @Test
    @DisplayName("level= mitten im Befehl fällt nicht in den Grund")
    void levelMittendrinFaelltNichtInDenGrund() {
        String[] args = {"add", "Steve", "Cheat", "level=medium", "Spam"};
        var angabe = VerstossCommand.leseStufenAngabe(args, 2);
        assertEquals(VerstossStufe.MEDIUM, angabe.stufe());
        assertEquals(Set.of(3), angabe.ausgeschlossene());
        assertEquals("Cheat Spam", VerstossCommand.grundAusBereich(args, 2, angabe.ausgeschlossene()));
    }

    @Test
    @DisplayName("Ohne level=-Token bleibt der Grund unverändert")
    void ohneLevelTokenBleibtGrundVollstaendig() {
        String[] args = {"add", "Steve", "Spam", "Cheat"};
        var angabe = VerstossCommand.leseStufenAngabe(args, 2);
        assertNull(angabe.stufe());
        assertFalse(angabe.angegeben());
        assertFalse(angabe.ungueltig());
        assertTrue(angabe.ausgeschlossene().isEmpty());
        assertEquals("Spam Cheat", VerstossCommand.grundAusBereich(args, 2, angabe.ausgeschlossene()));
    }

    @Test
    @DisplayName("Ungültige Stufe wird mit Rohwert gemeldet")
    void ungueltigeStufeWirdGemeldet() {
        String[] args = {"add", "Steve", "Cheat", "level=extrem"};
        var angabe = VerstossCommand.leseStufenAngabe(args, 2);
        assertTrue(angabe.ungueltig());
        assertEquals("extrem", angabe.rohWert());
        assertEquals(Set.of(3), angabe.ausgeschlossene());
    }

    @Test
    @DisplayName("Mehrere level=-Tokens: letzter gewinnt, beide aus dem Grund")
    void mehrfachesLevelLetzterGewinnt() {
        String[] args = {"add", "Steve", "level=low", "Cheat", "level=high"};
        var angabe = VerstossCommand.leseStufenAngabe(args, 2);
        assertEquals(VerstossStufe.HIGH, angabe.stufe());
        assertEquals(Set.of(2, 4), angabe.ausgeschlossene());
        assertEquals("Cheat", VerstossCommand.grundAusBereich(args, 2, angabe.ausgeschlossene()));
    }

    @Test
    @DisplayName("Aliase und Groß-/Kleinschreibung werden erkannt")
    void aliasUndSchreibungWirdErkannt() {
        String[] args = {"add", "Steve", "LEVEL=Schwer"};
        var angabe = VerstossCommand.leseStufenAngabe(args, 2);
        assertEquals(VerstossStufe.HIGH, angabe.stufe());
        assertTrue(angabe.angegeben());

        String[] aliasArgs = {"add", "Steve", "Schweres Cheating", "level=schwer"};
        var aliasAngabe = VerstossCommand.leseStufenAngabe(aliasArgs, 2);
        assertEquals(VerstossStufe.HIGH, aliasAngabe.stufe());
        assertEquals("Schweres Cheating",
                VerstossCommand.grundAusBereich(aliasArgs, 2, aliasAngabe.ausgeschlossene()));
    }

    @Test
    @DisplayName("Nur level= ohne Grund liefert leeren Grund")
    void nurLevelOhneGrundLiefertLeerenGrund() {
        String[] args = {"add", "Steve", "level=high"};
        var angabe = VerstossCommand.leseStufenAngabe(args, 2);
        assertEquals(VerstossStufe.HIGH, angabe.stufe());
        assertEquals(Set.of(2), angabe.ausgeschlossene());
        assertEquals("", VerstossCommand.grundAusBereich(args, 2, angabe.ausgeschlossene()));
    }
}
