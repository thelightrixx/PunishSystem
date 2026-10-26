package de.lightrixx.punish.velocity;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KatalogSeiteTest {

    private static List<Protokoll.GuiZeile> zeilen(int anzahl) {
        List<Protokoll.GuiZeile> liste = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) {
            liste.add(new Protokoll.GuiZeile("SERVER", "server" + i, "", "", ""));
        }
        return liste;
    }

    private static Protokoll.GuiAnfrage anfrage(int seite) {
        return new Protokoll.GuiAnfrage(1L, null, Protokoll.GUI_KATALOG, "", "", seite, "");
    }

    @Test
    void seiten_werdenAufgeteilt() {
        var alle = zeilen(120);
        var eins = GuiBrueckenDienst.katalogSeite(anfrage(1), alle);
        assertEquals(1, eins.seite());
        assertEquals(3, eins.seiten());
        assertEquals(120, eins.gesamt());
        assertEquals(Protokoll.MAX_GUI_ZEILEN, eins.eintraege().size());
        assertEquals("server0", eins.eintraege().get(0).titel());
        var zwei = GuiBrueckenDienst.katalogSeite(anfrage(2), alle);
        assertEquals(2, zwei.seite());
        assertEquals(Protokoll.MAX_GUI_ZEILEN, zwei.eintraege().size());
        assertEquals("server54", zwei.eintraege().get(0).titel());
        var drei = GuiBrueckenDienst.katalogSeite(anfrage(3), alle);
        assertEquals(12, drei.eintraege().size());
        assertEquals("server119", drei.eintraege().get(11).titel());
    }

    @Test
    void ueberlauf_wirdGeklemmt() {
        var alle = zeilen(10);
        var antwort = GuiBrueckenDienst.katalogSeite(anfrage(99), alle);
        assertEquals(1, antwort.seite());
        assertEquals(1, antwort.seiten());
        assertEquals(10, antwort.eintraege().size());
    }

    @Test
    void leer_eineLeereSeite() {
        var antwort = GuiBrueckenDienst.katalogSeite(anfrage(1), List.of());
        assertEquals(1, antwort.seite());
        assertEquals(1, antwort.seiten());
        assertTrue(antwort.eintraege().isEmpty());
    }
}
