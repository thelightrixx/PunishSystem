package de.lightrixx.punish.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProtokollTextSyncTest {

    @Test
    void stand_roundtrip() throws Exception {
        String hash = "a".repeat(64);
        byte[] roh = Protokoll.textStand(hash);
        assertEquals(Protokoll.TEXT_STAND, Protokoll.paket(roh).typ());
        assertEquals(hash, Protokoll.leseTextStand(Protokoll.paket(roh).nutzdaten()).paketHash());
    }

    @Test
    void stand_leerBedeutetKeine() throws Exception {
        byte[] roh = Protokoll.textStand("");
        assertEquals("", Protokoll.leseTextStand(Protokoll.paket(roh).nutzdaten()).paketHash());
    }

    @Test
    void metaBlockFertig_roundtrip() throws Exception {
        byte[] daten = "messages:\n  a: b\n".getBytes(StandardCharsets.UTF_8);
        String hash = "d".repeat(64);
        var meta = new Protokoll.TextMeta("messages.yml", daten.length, hash, 8192, 1);
        byte[] rohMeta = Protokoll.textMeta(meta);
        assertEquals(Protokoll.TEXT_META, Protokoll.paket(rohMeta).typ());
        assertEquals(meta, Protokoll.leseTextMeta(Protokoll.paket(rohMeta).nutzdaten()));

        var block = new Protokoll.TextBlock("messages.yml", 0, daten);
        byte[] rohBlock = Protokoll.textBlock(block);
        assertEquals(Protokoll.TEXT_BLOCK, Protokoll.paket(rohBlock).typ());
        var gelesen = Protokoll.leseTextBlock(Protokoll.paket(rohBlock).nutzdaten());
        assertEquals("messages.yml", gelesen.dateiname());
        assertArrayEquals(daten, gelesen.daten());

        byte[] rohFertig = Protokoll.textFertig(new Protokoll.TextFertig(hash));
        assertEquals(Protokoll.TEXT_FERTIG, Protokoll.paket(rohFertig).typ());
        assertEquals(hash, Protokoll.leseTextFertig(Protokoll.paket(rohFertig).nutzdaten()).paketHash());
    }

    @Test
    void meta_fremderName_wirdAbgelehnt() {
        String hash = "e".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("velocity.toml", 10, hash, 8192, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("../messages.yml", 10, hash, 8192, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("config/messages.yml", 10, hash, 8192, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("server.properties", 10, hash, 8192, 1));
    }

    @Test
    void meta_zuGross_wirdAbgelehnt() {
        String hash = "f".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> new Protokoll.TextMeta("messages.yml",
                        Protokoll.MAX_TEXT_DATEI_BYTES + 1, hash, 8192, 33));
    }

    @Test
    void fertig_brauchtHash() {
        assertThrows(IllegalArgumentException.class, () -> new Protokoll.TextFertig(""));
    }

    @Test
    void paketHash_istSortiertUndDeterministisch() {
        var a = java.util.Map.of("b.yml", new byte[]{2}, "a.yml", new byte[]{1});
        var b = new java.util.LinkedHashMap<String, byte[]>();
        b.put("a.yml", new byte[]{1});
        b.put("b.yml", new byte[]{2});
        assertEquals(Protokoll.textPaketHash(a), Protokoll.textPaketHash(b));
        assertEquals(64, Protokoll.textPaketHash(a).length());
        var c = new java.util.LinkedHashMap<String, byte[]>(b);
        c.put("a.yml", new byte[]{9});
        assertNotEquals(Protokoll.textPaketHash(b), Protokoll.textPaketHash(c));
    }

    @Test
    void quittung_roundtrip() throws Exception {
        String hash = "c".repeat(64);
        byte[] roh = Protokoll.textQuittung(new Protokoll.TextQuittung(hash, true));
        assertEquals(Protokoll.TEXT_QUITTUNG, Protokoll.paket(roh).typ());
        var gelesen = Protokoll.leseTextQuittung(Protokoll.paket(roh).nutzdaten());
        assertEquals(hash, gelesen.paketHash());
        assertTrue(gelesen.erfolg());
    }
}
