package de.lightrixx.punish.net;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtokollChatfilterTest {

    @Test
    void treffer_roundtrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        var original = new Protokoll.ChatfilterTreffer(uuid, "Notch", "CHAT", "KI",
                0.85d, "abc123", "Du Hurensohn", 123456789L);
        byte[] roh = Protokoll.chatfilterTreffer(original);
        Protokoll.Paket paket = Protokoll.paket(roh);
        assertEquals(Protokoll.CHATFILTER_TREFFER, paket.typ());
        var gelesen = Protokoll.leseChatfilterTreffer(paket.nutzdaten());
        assertEquals(original, gelesen);
    }

    @Test
    void treffer_ohneUuid_wirdAbgelehnt() throws Exception {
        byte[] roh = Protokoll.nachricht(Protokoll.CHATFILTER_TREFFER, out -> {
            Protokoll.schreibeUuid(out, null);
            out.writeUTF("Notch");
            out.writeUTF("CHAT");
            out.writeUTF("KI");
            out.writeDouble(0.9d);
            out.writeUTF("abc123");
            out.writeLong(1L);
        });
        var paket = Protokoll.paket(roh);
        assertThrows(java.io.IOException.class,
                () -> Protokoll.leseChatfilterTreffer(paket.nutzdaten()));
    }

    @Test
    void configReload_roundtrip() throws Exception {
        byte[] roh = Protokoll.configReload();
        Protokoll.Paket paket = Protokoll.paket(roh);
        assertEquals(Protokoll.CONFIG_RELOAD, paket.typ());
    }

    @Test
    void steuerung_roundtrip() throws Exception {
        var original = new Protokoll.ChatfilterSteuerung(2, 0);
        byte[] roh = Protokoll.chatfilterSteuerung(original.filterAn(), original.kiAn());
        assertEquals(Protokoll.CHATFILTER_STEUERUNG, Protokoll.paket(roh).typ());
        var gelesen = Protokoll.leseChatfilterSteuerung(Protokoll.paket(roh).nutzdaten());
        assertEquals(Boolean.TRUE, gelesen.filterAn());
        assertNull(gelesen.kiAn());
        var aus = Protokoll.leseChatfilterSteuerung(
                Protokoll.paket(Protokoll.chatfilterSteuerung(Boolean.FALSE, null)).nutzdaten());
        assertEquals(Boolean.FALSE, aus.filterAn());
    }

    @Test
    void wort_roundtripUndValidierung() throws Exception {
        byte[] roh = Protokoll.chatfilterWort("hurensohn");
        assertEquals(Protokoll.CHATFILTER_WORT, Protokoll.paket(roh).typ());
        assertEquals("hurensohn",
                Protokoll.leseChatfilterWort(Protokoll.paket(roh).nutzdaten()));
        byte[] ungueltig = Protokoll.nachricht(Protokoll.CHATFILTER_WORT,
                out -> out.writeUTF("a\nb"));
        assertThrows(java.io.IOException.class,
                () -> Protokoll.leseChatfilterWort(Protokoll.paket(ungueltig).nutzdaten()));
    }

    @Test
    void modellStand_roundtrip() throws Exception {
        String hash = "a".repeat(64);
        byte[] roh = Protokoll.modellStand(hash, "");
        assertEquals(Protokoll.MODELL_STAND, Protokoll.paket(roh).typ());
        var gelesen = Protokoll.leseModellStand(Protokoll.paket(roh).nutzdaten());
        assertEquals(hash, gelesen.modellHash());
        assertEquals("", gelesen.vocabHash());
    }

    @Test
    void modellStand_ungueltigerHash_wirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class, () -> Protokoll.modellStand("kein-hash", ""));
        assertThrows(IllegalArgumentException.class, () -> new Protokoll.ModellStand("x".repeat(63), ""));
    }

    @Test
    void modellMeta_roundtrip() throws Exception {
        var original = new Protokoll.ModellMeta("modell_int8.onnx", 104857600L, "b".repeat(64),
                262144, 400);
        byte[] roh = Protokoll.modellMeta(original);
        assertEquals(Protokoll.MODELL_META, Protokoll.paket(roh).typ());
        assertEquals(original, Protokoll.leseModellMeta(Protokoll.paket(roh).nutzdaten()));
    }

    @Test
    void modellMeta_falscheAnzahl_wirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class, () -> new Protokoll.ModellMeta(
                "modell_int8.onnx", 104857600L, "b".repeat(64), 262144, 399));
        assertThrows(IllegalArgumentException.class, () -> new Protokoll.ModellMeta(
                "../x.onnx", 100L, "b".repeat(64), 4096, 1));
        assertThrows(IllegalArgumentException.class, () -> new Protokoll.ModellMeta(
                "modell.exe", 100L, "b".repeat(64), 4096, 1));
    }

    @Test
    void modellBlock_roundtrip() throws Exception {
        var original = new Protokoll.ModellBlock("vocab.txt", 3, new byte[]{1, 2, 3});
        byte[] roh = Protokoll.modellBlock(original);
        assertEquals(Protokoll.MODELL_BLOCK, Protokoll.paket(roh).typ());
        var gelesen = Protokoll.leseModellBlock(Protokoll.paket(roh).nutzdaten());
        assertEquals(original.dateiname(), gelesen.dateiname());
        assertEquals(3, gelesen.index());
        assertArrayEquals(new byte[]{1, 2, 3}, gelesen.daten());
    }

    @Test
    void modellFertigUndQuittung_roundtrip() throws Exception {
        var fertig = new Protokoll.ModellFertig("modell_int8.onnx", "c".repeat(64), 104857600L);
        byte[] rohFertig = Protokoll.modellFertig(fertig);
        assertEquals(Protokoll.MODELL_FERTIG, Protokoll.paket(rohFertig).typ());
        assertEquals(fertig, Protokoll.leseModellFertig(Protokoll.paket(rohFertig).nutzdaten()));
        var quittung = new Protokoll.ModellQuittung("vocab.txt", true);
        byte[] rohQuittung = Protokoll.modellQuittung(quittung);
        assertEquals(Protokoll.MODELL_QUITTUNG, Protokoll.paket(rohQuittung).typ());
        assertEquals(quittung, Protokoll.leseModellQuittung(Protokoll.paket(rohQuittung).nutzdaten()));
    }
}
