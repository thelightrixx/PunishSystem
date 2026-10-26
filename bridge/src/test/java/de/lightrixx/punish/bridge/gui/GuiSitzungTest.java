package de.lightrixx.punish.bridge.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuiSitzungTest {

    @Test
    @DisplayName("Command-Builder baut mute-Zeile mit Scope")
    void bautMuteZeile() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.MUTE);
        sitzung.grund("Spamming");
        sitzung.dauer("10m");
        sitzung.scope("--global");
        assertTrue(sitzung.bereitZumSenden());
        assertEquals("mute Max 10m Spamming --global", sitzung.baueZeile());
    }

    @Test
    @DisplayName("Verstoss nutzt level statt Dauer")
    void bautVerstossZeile() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.VERSTOSS);
        sitzung.grund("Cheating");
        sitzung.stufe("high");
        assertTrue(sitzung.bereitZumSenden());
        assertEquals("verstoss add Max Cheating level=high", sitzung.baueZeile());
    }

    @Test
    @DisplayName("Unvollständig ist nicht sendebereit")
    void unvollstaendigNichtBereit() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.BAN);
        assertFalse(sitzung.bereitZumSenden());
    }

    @Test
    @DisplayName("Entstrafe braucht nur Ziel")
    void entstrafeBrauchtNurZiel() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.UNMUTE);
        assertTrue(sitzung.bereitZumSenden());
        assertEquals("unmute Max --global", sitzung.baueZeile());
    }

    @Test
    @DisplayName("Command-Builder baut freeze-Zeile mit Dauer und Scope")
    void bautFreezeZeile() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.FREEZE);
        sitzung.grund("Cheating-Verdacht");
        sitzung.dauer("1h");
        sitzung.scope("--global");
        assertTrue(sitzung.bereitZumSenden());
        assertEquals("freeze Max 1h Cheating-Verdacht --global", sitzung.baueZeile());
    }

    @Test
    @DisplayName("Freeze ohne Dauer wird permanent gebaut")
    void bautFreezeZeileOhneDauerPermanent() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.FREEZE);
        sitzung.grund("Cheating-Verdacht");
        sitzung.dauer("");
        sitzung.scope("--global");
        assertEquals("freeze Max perm Cheating-Verdacht --global", sitzung.baueZeile());
    }

    @Test
    @DisplayName("Unfreeze braucht nur Ziel")
    void bautUnfreezeZeile() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.UNFREEZE);
        assertTrue(sitzung.bereitZumSenden());
        assertEquals("unfreeze Max --global", sitzung.baueZeile());
    }

    @Test
    @DisplayName("D-003: Entstrafen trägt den gewählten Scope mit")
    void entstrafeTraegtScopeMit() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.UNMUTE);
        sitzung.scope("--global");
        assertEquals("unmute Max --global", sitzung.baueZeile());
        sitzung.scope("--server=lobby");
        assertEquals("unmute Max --server=lobby", sitzung.baueZeile());
    }

    @Test
    @DisplayName("D-003: Alle Entstrafen-Befehle tragen den Scope mit")
    void alleEntstrafenTragenScope() {
        sitzungMit(GuiSitzung.Aktion.PARDON, "pardon Max --global");
        sitzungMit(GuiSitzung.Aktion.UNTIMEOUT, "untimeout Max --global");
        sitzungMit(GuiSitzung.Aktion.UNFREEZE, "unfreeze Max --global");
        sitzungMit(GuiSitzung.Aktion.UNMUTE, "unmute Max --global");
    }

    private static void sitzungMit(GuiSitzung.Aktion aktion, String erwartet) {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(aktion);
        sitzung.scope("--global");
        assertEquals(erwartet, sitzung.baueZeile());
    }

    @Test
    @DisplayName("A-013: Scope-Flag im Grund überschreibt die GUI-Auswahl nicht")
    void grundScopeFlagUeberschreibtAuswahlNicht() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Ziel");
        sitzung.aktion(GuiSitzung.Aktion.BAN);
        sitzung.dauer("7d");
        sitzung.grund("Griefing --global");
        sitzung.scope("--server=lobby");
        assertEquals("ban Ziel 7d Griefing --server=lobby", sitzung.baueZeile());
    }

    @Test
    @DisplayName("A-013: trailing -s im Grund aktiviert nicht stillschweigend Silent")
    void grundSilentFlagWirdBereinigt() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Ziel");
        sitzung.aktion(GuiSitzung.Aktion.MUTE);
        sitzung.dauer("10m");
        sitzung.grund("Spamming -s");
        sitzung.scope("--global");
        assertEquals("mute Ziel 10m Spamming --global", sitzung.baueZeile());
    }

    @Test
    @DisplayName("zuruecksetzen löscht Ziel, Aktion, Grund, Dauer und Seite")
    void zuruecksetzenLoeschtAlles() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Max");
        sitzung.aktion(GuiSitzung.Aktion.BAN);
        sitzung.grund("Cheating");
        sitzung.dauer("7d");
        sitzung.scope("--lobby");
        sitzung.silent(true);
        sitzung.seite(3);
        sitzung.zuruecksetzen();
        assertEquals("", sitzung.ziel());
        assertNull(sitzung.aktion());
        assertEquals("", sitzung.grund());
        assertEquals("", sitzung.dauer());
        assertEquals("--global", sitzung.scope());
        assertFalse(sitzung.silent());
        assertEquals(1, sitzung.seite());
        assertFalse(sitzung.bereitZumSenden());
    }
}
