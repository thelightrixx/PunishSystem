package de.lightrixx.punish.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tiefen-Audit (M9): exakte Grenzwerte der Protokoll-Validierung.
 * Jeder Test tötet die Grenzwert-Mutanten der entsprechenden Prüfung.
 */
class ProtokollGrenzenTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("ModellMeta akzeptiert genau die Grenzwerte und weist einen Schritt darüber ab")
    void modellMetaGrenzen() {
        new Protokoll.ModellMeta("modell.onnx", 1L, HASH, 4096, 1);
        new Protokoll.ModellMeta("modell.onnx", Protokoll.MAX_MODELL_DATEI_BYTES, HASH,
                Protokoll.MAX_MODELL_CHUNK_BYTES,
                (int) ((Protokoll.MAX_MODELL_DATEI_BYTES + Protokoll.MAX_MODELL_CHUNK_BYTES - 1)
                        / Protokoll.MAX_MODELL_CHUNK_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellMeta("modell.onnx", 0L, HASH, 4096, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellMeta("modell.onnx", Protokoll.MAX_MODELL_DATEI_BYTES + 1L,
                        HASH, Protokoll.MAX_MODELL_CHUNK_BYTES, 100000));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellMeta("modell.onnx", 1L, HASH, 4095, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellMeta("modell.onnx", 1L, HASH, Protokoll.MAX_MODELL_CHUNK_BYTES + 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellMeta("modell.onnx", 9000L, HASH, 4096, 2),
                "anzahl muss exakt dem erwarteten Chunk-Anzahl entsprechen");
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellMeta("../x.onnx", 1L, HASH, 4096, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellMeta("modell.bin", 1L, HASH, 4096, 1));
    }

    @Test
    @DisplayName("ModellBlock weist leere und zu große Daten sowie negative Indizes ab")
    void modellBlockGrenzen() {
        new Protokoll.ModellBlock("modell.onnx", 0, new byte[]{1});
        new Protokoll.ModellBlock("modell.onnx", 100000, new byte[Protokoll.MAX_MODELL_CHUNK_BYTES]);
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellBlock("modell.onnx", -1, new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellBlock("modell.onnx", 100001, new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellBlock("modell.onnx", 0, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.ModellBlock("modell.onnx", 0,
                        new byte[Protokoll.MAX_MODELL_CHUNK_BYTES + 1]));
    }

    @Test
    @DisplayName("TextMeta/TextBlock halten ihre Grenzen exakt ein")
    void textMetaUndBlockGrenzen() {
        new Protokoll.TextMeta("general.yml", 1L, HASH, 1024, 1);
        new Protokoll.TextMeta("general.yml", Protokoll.MAX_TEXT_DATEI_BYTES, HASH,
                Protokoll.MAX_MODELL_CHUNK_BYTES,
                (int) ((Protokoll.MAX_TEXT_DATEI_BYTES + Protokoll.MAX_MODELL_CHUNK_BYTES - 1)
                        / Protokoll.MAX_MODELL_CHUNK_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("general.yml", 0L, HASH, 1024, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("general.yml", Protokoll.MAX_TEXT_DATEI_BYTES + 1L,
                        HASH, 1024, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("general.yml", 1L, HASH, 1023, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("unbekannt.yml", 1L, HASH, 1024, 1));

        new Protokoll.TextBlock("general.yml", 0, new byte[]{1});
        new Protokoll.TextBlock("general.yml", 1000, new byte[]{1});
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextBlock("general.yml", -1, new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextBlock("general.yml", 1001, new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextBlock("general.yml", 0, new byte[0]));
    }

    @Test
    @DisplayName("GuiAnfrage normalisiert Seite, Typ und requestId an der Grenze")
    void guiAnfrageGrenzen() throws Exception {
        Protokoll.GuiAnfrage anfrage = new Protokoll.GuiAnfrage(1L, null, " ", null, null, 0, null);
        assertEquals(1, anfrage.seite());
        assertEquals(Protokoll.GUI_STATUS, anfrage.typ());
        assertEquals("", anfrage.ziel());
        new Protokoll.GuiAnfrage(1L, null, Protokoll.GUI_STATUS, "", "", 1, "");
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.GuiAnfrage(0L, null, Protokoll.GUI_STATUS, "", "", 1, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.GuiAnfrage(-1L, null, Protokoll.GUI_STATUS, "", "", 1, ""));
    }

    @Test
    @DisplayName("GuiAntwort kürzt genau ab MAX_GUI_ZEILEN und liest nicht mehr Zeilen")
    void guiAntwortGrenzen() throws Exception {
        List<Protokoll.GuiZeile> zeilen = new ArrayList<>();
        for (int i = 0; i < Protokoll.MAX_GUI_ZEILEN + 1; i++) {
            zeilen.add(new Protokoll.GuiZeile("typ", "titel" + i, "details", "aktion", "meta"));
        }
        Protokoll.GuiAntwort antwort = new Protokoll.GuiAntwort(7L, true, "ok", 1, 2, 99, zeilen);
        Protokoll.Paket paket = Protokoll.paket(Protokoll.guiAntwort(antwort));
        Protokoll.GuiAntwort gelesen = Protokoll.leseGuiAntwort(paket.nutzdaten());
        assertEquals(Protokoll.MAX_GUI_ZEILEN, gelesen.eintraege().size());
        assertEquals("titel0", gelesen.eintraege().get(0).titel());
        assertTrue(gelesen.eintraege().get(Protokoll.MAX_GUI_ZEILEN - 1).titel()
                .equals("titel" + (Protokoll.MAX_GUI_ZEILEN - 1)));
    }
}
