package de.lightrixx.punish.bridge.chatfilter;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ModellEmpfaengerTest {

    @TempDir
    File ordner;

    private ChatfilterKonfiguration konfiguration;
    private int neuGeladen;
    private ModellEmpfaenger empfaenger;

    private static final class Quittung {
        final String datei;
        final boolean erfolg;

        Quittung(String datei, boolean erfolg) {
            this.datei = datei;
            this.erfolg = erfolg;
        }
    }

    private final List<Quittung> quittungsListe = new ArrayList<>();

    @BeforeEach
    void aufsetzen() {
        konfiguration = Mockito.mock(ChatfilterKonfiguration.class);
        when(konfiguration.modellDateiname()).thenReturn("modell_int8.onnx");
        when(konfiguration.vocabDateiname()).thenReturn("vocab.txt");
        when(konfiguration.dateiAufloesen(anyString())).thenAnswer(
                aufruf -> new File(ordner, aufruf.getArgument(0)));
        empfaenger = new ModellEmpfaenger(konfiguration, new ModellEmpfaenger.Rueckmeldung() {
            @Override
            public void quittung(String dateiname, boolean erfolg) {
                quittungsListe.add(new Quittung(dateiname, erfolg));
            }

            @Override
            public void neuLaden() {
                neuGeladen++;
            }
        });
    }

    private static String sha256(byte[] daten) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(daten));
    }

    private void sendeDatei(String name, byte[] inhalt, int chunk) throws Exception {
        int anzahl = (inhalt.length + chunk - 1) / chunk;
        empfaenger.empfangeMeta(new Protokoll.ModellMeta(name, inhalt.length, sha256(inhalt),
                chunk, anzahl));
        for (int i = 0; i < anzahl; i++) {
            int von = i * chunk;
            int bis = Math.min(inhalt.length, von + chunk);
            byte[] teil = java.util.Arrays.copyOfRange(inhalt, von, bis);
            empfaenger.empfangeBlock(new Protokoll.ModellBlock(name, i, teil));
        }
        empfaenger.empfangeFertig(new Protokoll.ModellFertig(name, sha256(inhalt), inhalt.length));
    }

    @Test
    void kompletterTransfer_installiertDatei() throws Exception {
        byte[] inhalt = "modell-bytes-1234567890".getBytes(StandardCharsets.UTF_8);
        sendeDatei("modell_int8.onnx", inhalt, 8192);
        assertArrayEquals(inhalt, Files.readAllBytes(new File(ordner, "modell_int8.onnx").toPath()));
        assertEquals(1, quittungsListe.size());
        assertTrue(quittungsListe.get(0).erfolg);
        assertEquals(1, neuGeladen);
    }

    @Test
    void falscherHash_verwirftDatei() throws Exception {
        byte[] inhalt = "modell-bytes".getBytes(StandardCharsets.UTF_8);
        empfaenger.empfangeMeta(new Protokoll.ModellMeta("modell_int8.onnx", inhalt.length,
                sha256(inhalt), 8192, 1));
        empfaenger.empfangeBlock(new Protokoll.ModellBlock("modell_int8.onnx", 0, inhalt));
        empfaenger.empfangeFertig(new Protokoll.ModellFertig("modell_int8.onnx",
                "f".repeat(64), inhalt.length));
        assertFalse(new File(ordner, "modell_int8.onnx").exists());
        assertFalse(new File(ordner, "modell_int8.onnx.part").exists());
        assertEquals(1, quittungsListe.size());
        assertFalse(quittungsListe.get(0).erfolg);
        assertEquals(0, neuGeladen);
    }

    @Test
    void unbekannterDateiname_abgewiesen() {
        empfaenger.empfangeMeta(new Protokoll.ModellMeta("fremd.onnx", 10L,
                "a".repeat(64), 4096, 1));
        assertEquals(1, quittungsListe.size());
        assertFalse(quittungsListe.get(0).erfolg);
        assertEquals("fremd.onnx", quittungsListe.get(0).datei);
        assertFalse(new File(ordner, "fremd.onnx").exists());
    }

    @Test
    void blockAusserhalbReihenfolge_brichtAb() throws Exception {
        byte[] inhalt = "12345678".getBytes(StandardCharsets.UTF_8);
        empfaenger.empfangeMeta(new Protokoll.ModellMeta("vocab.txt", inhalt.length,
                sha256(inhalt), 8192, 1));
        empfaenger.empfangeBlock(new Protokoll.ModellBlock("vocab.txt", 1, inhalt));
        assertFalse(new File(ordner, "vocab.txt").exists());
        assertEquals(1, quittungsListe.size());
        assertFalse(quittungsListe.get(0).erfolg);
    }

    @Test
    void blockOhneMeta_wirdIgnoriert() {
        empfaenger.empfangeBlock(new Protokoll.ModellBlock("vocab.txt", 0,
                new byte[]{1, 2}));
        assertTrue(quittungsListe.isEmpty());
    }

    @Test
    void abbrechen_verwirftPartUndIgnoriertFolgeBloecke() throws Exception {
        byte[] inhalt = "12345678901234567890".getBytes(StandardCharsets.UTF_8);
        int chunk = 8192;
        int anzahl = (inhalt.length + chunk - 1) / chunk;
        empfaenger.empfangeMeta(new Protokoll.ModellMeta("vocab.txt", inhalt.length,
                sha256(inhalt), chunk, anzahl));
        empfaenger.abbrechen();
        assertFalse(new File(ordner, "vocab.txt.part").exists());
        empfaenger.empfangeBlock(new Protokoll.ModellBlock("vocab.txt", 0,
                java.util.Arrays.copyOfRange(inhalt, 0, 10)));
        assertTrue(quittungsListe.isEmpty());
        assertFalse(new File(ordner, "vocab.txt").exists());
    }

    @Test
    void neuesMeta_brichtAltenTransferAb() throws Exception {
        byte[] alt = "altinhalt-1234567890".getBytes(StandardCharsets.UTF_8);
        byte[] neu = "neuinhalt-0987654321".getBytes(StandardCharsets.UTF_8);
        int chunk = 8192;
        empfaenger.empfangeMeta(new Protokoll.ModellMeta("vocab.txt", alt.length,
                sha256(alt), chunk, (alt.length + chunk - 1) / chunk));
        empfaenger.empfangeMeta(new Protokoll.ModellMeta("vocab.txt", neu.length,
                sha256(neu), chunk, (neu.length + chunk - 1) / chunk));
        empfaenger.empfangeBlock(new Protokoll.ModellBlock("vocab.txt", 0, neu));
        empfaenger.empfangeFertig(new Protokoll.ModellFertig("vocab.txt", sha256(neu), neu.length));
        assertArrayEquals(neu, Files.readAllBytes(new File(ordner, "vocab.txt").toPath()));
    }
}
