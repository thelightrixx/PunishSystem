package de.lightrixx.punish.audit;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AuditCodecFuzzTest {

    private static final long SEED = 20261007L;
    private static final int ITERATIONEN = 1000;

    @FunctionalInterface
    interface Codec {
        void lese(DataInputStream in) throws Exception;
    }

    private record FuzzKandidat(String name, Codec lese, boolean illegalArgumentErlaubt) {
    }

    private static List<FuzzKandidat> alleCodecs() {
        List<FuzzKandidat> liste = new ArrayList<>();
        liste.add(new FuzzKandidat("leseHello", Protokoll::leseHello, false));
        liste.add(new FuzzKandidat("leseHelloAck", Protokoll::leseHelloAck, false));
        liste.add(new FuzzKandidat("leseHelloAckMitName", Protokoll::leseHelloAckMitName, false));
        liste.add(new FuzzKandidat("leseBefehl", Protokoll::leseBefehl, false));
        liste.add(new FuzzKandidat("leseMute", Protokoll::leseMute, false));
        liste.add(new FuzzKandidat("leseIpMute", Protokoll::leseIpMute, false));
        liste.add(new FuzzKandidat("leseBan", Protokoll::leseBan, false));
        liste.add(new FuzzKandidat("leseTimeout", Protokoll::leseTimeout, false));
        liste.add(new FuzzKandidat("leseFreeze", Protokoll::leseFreeze, false));
        liste.add(new FuzzKandidat("leseMuteSync", Protokoll::leseMuteSync, false));
        liste.add(new FuzzKandidat("leseIpMuteSync", Protokoll::leseIpMuteSync, false));
        liste.add(new FuzzKandidat("leseBanSync", Protokoll::leseBanSync, false));
        liste.add(new FuzzKandidat("leseTimeoutSync", Protokoll::leseTimeoutSync, false));
        liste.add(new FuzzKandidat("leseFreezeSync", Protokoll::leseFreezeSync, false));
        liste.add(new FuzzKandidat("leseUuidListe", Protokoll::leseUuidListe, false));
        liste.add(new FuzzKandidat("leseIpListe", Protokoll::leseIpListe, false));
        liste.add(new FuzzKandidat("leseChatfilterTreffer", Protokoll::leseChatfilterTreffer, false));
        liste.add(new FuzzKandidat("leseChatfilterSteuerung", Protokoll::leseChatfilterSteuerung, false));
        liste.add(new FuzzKandidat("leseChatfilterWort", Protokoll::leseChatfilterWort, false));
        liste.add(new FuzzKandidat("leseGuiAnfrage", Protokoll::leseGuiAnfrage, false));
        liste.add(new FuzzKandidat("leseGuiZeile", Protokoll::leseGuiZeile, false));
        liste.add(new FuzzKandidat("leseGuiAntwort", Protokoll::leseGuiAntwort, false));
        liste.add(new FuzzKandidat("lesePlatzhalterAnfrage", Protokoll::lesePlatzhalterAnfrage, false));
        liste.add(new FuzzKandidat("lesePlatzhalterAntwort", Protokoll::lesePlatzhalterAntwort, false));
        liste.add(new FuzzKandidat("leseModellStand", Protokoll::leseModellStand, false));
        liste.add(new FuzzKandidat("leseModellMeta", Protokoll::leseModellMeta, false));
        liste.add(new FuzzKandidat("leseModellBlock", Protokoll::leseModellBlock, false));
        liste.add(new FuzzKandidat("leseModellFertig", Protokoll::leseModellFertig, false));
        liste.add(new FuzzKandidat("leseModellQuittung", Protokoll::leseModellQuittung, false));
        liste.add(new FuzzKandidat("leseSyncGeneration", Protokoll::leseSyncGeneration, false));
        return liste;
    }

    private static DataInputStream stream(byte[] roh) {
        return new DataInputStream(new ByteArrayInputStream(roh));
    }

    @Test
    @DisplayName("Fuzz 1: 1000 zufällige Frames je Codec erzeugen nur IOException (oder dokumentierte IAE)")
    void fuzzZufaelligeBytes() {
        Random random = new Random(SEED);
        List<String> verletzungen = new ArrayList<>();
        for (FuzzKandidat kandidat : alleCodecs()) {
            for (int i = 0; i < ITERATIONEN; i++) {
                byte[] roh = new byte[random.nextInt(400)];
                random.nextBytes(roh);
                try {
                    kandidat.lese().lese(stream(roh));
                } catch (IOException erwartet) {

                } catch (IllegalArgumentException iae) {
                    if (!kandidat.illegalArgumentErlaubt()) {
                        verletzungen.add(kandidat.name() + " warf IllegalArgumentException: " + iae.getMessage());
                    }
                } catch (Throwable t) {
                    verletzungen.add(kandidat.name() + " warf " + t.getClass().getName() + ": " + t.getMessage());
                }
            }
        }
        assertTrue(verletzungen.isEmpty(), "Codec-Vertrag verletzt: " + verletzungen);
    }

    @Test
    @DisplayName("Grenze: Anzahl 100000 ohne Einträge endet in IOException statt OOM/Endlos-Schleife")
    void grenzeAnzahl100000OhneInhalt() {
        byte[] roh = new byte[4];
        roh[0] = (byte) ((100000 >>> 24) & 0xFF);
        roh[1] = (byte) ((100000 >>> 16) & 0xFF);
        roh[2] = (byte) ((100000 >>> 8) & 0xFF);
        roh[3] = (byte) (100000 & 0xFF);
        assertThrows(IOException.class, () -> Protokoll.leseMuteSync(stream(roh)));
        assertThrows(IOException.class, () -> Protokoll.leseBanSync(stream(roh)));
        assertThrows(IOException.class, () -> Protokoll.leseTimeoutSync(stream(roh)));
        assertThrows(IOException.class, () -> Protokoll.leseFreezeSync(stream(roh)));
        assertThrows(IOException.class, () -> Protokoll.leseIpMuteSync(stream(roh)));
    }

    @Test
    @DisplayName("Grenze: Anzahl über 100000 wird abgewiesen")
    void grenzeAnzahlZuGross() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(100001);
            out.flush();
            assertThrows(IOException.class, () -> Protokoll.leseMuteSync(stream(bytes.toByteArray())));
            assertThrows(IOException.class, () -> Protokoll.leseUuidListe(stream(bytes.toByteArray())));
            assertThrows(IOException.class, () -> Protokoll.leseIpListe(stream(bytes.toByteArray())));
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("Grenze: GUI-Antwort mit mehr als 54 Zeilen wird abgewiesen")
    void grenzeGuiZeilen() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(42L);
            out.writeBoolean(true);
            out.writeUTF("");
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(55);
            out.writeInt(55);
            out.flush();
            assertThrows(IOException.class, () -> Protokoll.leseGuiAntwort(stream(bytes.toByteArray())));
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("Grenze: Nachricht/Paket über MAX_NUTZDATEN wird abgewiesen")
    void grenzeNutzdaten() {
        assertThrows(IOException.class, () -> Protokoll.nachricht(0x01,
                out -> out.write(new byte[Protokoll.MAX_NUTZDATEN + 1])));
        assertDoesNotThrow(() -> Protokoll.nachricht(0x01,
                out -> out.write(new byte[Protokoll.MAX_NUTZDATEN])));
    }

    @Test
    @DisplayName("Grenze: Modell-Block über MAX_MODELL_CHUNK_BYTES wird abgewiesen")
    void grenzeModellBlock() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("m.onnx");
            out.writeInt(0);
            out.writeInt(Protokoll.MAX_MODELL_CHUNK_BYTES + 1);
            out.flush();
            assertThrows(IOException.class, () -> Protokoll.leseModellBlock(stream(bytes.toByteArray())));
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("A-010: MAX_MODELL_CHUNK_BYTES ist als Einzelblock sendbar (kleiner als MAX_NUTZDATEN)")
    void cK51MaxModelChunkNichtSendbar() throws IOException {
        byte[] blockDaten = new byte[Protokoll.MAX_MODELL_CHUNK_BYTES];
        byte[] paket = assertDoesNotThrow(
                () -> Protokoll.modellBlock(new Protokoll.ModellBlock("m.onnx", 0, blockDaten)));
        assertTrue(paket.length <= Protokoll.MAX_NUTZDATEN + 1,
                "Max-Block muss in einen Frame passen: " + paket.length);
    }

    @Test
    @DisplayName("Gegenprobe Chunking: 200 Mutes mit maximalen Feldern kodieren unter MAX_NUTZDATEN")
    void muteSync200MaxFelderSendbar() throws IOException {
        String gross = "x".repeat(1024);
        List<Protokoll.MuteEintrag> eintraege = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            eintraege.add(new Protokoll.MuteEintrag(UUID.randomUUID(), "Spieler" + i, gross, "Mod",
                    1000L, 2000L, "global"));
        }
        byte[] paket = Protokoll.muteSync(eintraege);
        assertTrue(paket.length <= Protokoll.MAX_NUTZDATEN + 1,
                "200 Einträge mit 1024-Zeichen-Grund müssen in einen Frame passen: " + paket.length);
        List<Protokoll.MuteEintrag> gelesen = Protokoll.leseMuteSync(Protokoll.paket(paket).nutzdaten());
        assertEquals(200, gelesen.size());
    }

    @Test
    @DisplayName("Spiegel: Mute/Ban/Timeout/Freeze/IpMute überstehen Write→Read feldgenau")
    void spiegelEintraege() throws IOException {
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        String lang = "ü".repeat(1024);
        Protokoll.MuteEintrag mute = new Protokoll.MuteEintrag(uuid, "Näme", lang, "Mod",
                Long.MAX_VALUE - 2, Long.MAX_VALUE, "lobby");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            Protokoll.schreibeMute(out, mute);
            Protokoll.MuteEintrag gelesen = Protokoll.leseMute(stream(bytes.toByteArray()));
            assertEquals(mute, gelesen, "MuteEintrag muss feldgenau überleben");
        }

        Protokoll.BanEintrag ban = new Protokoll.BanEintrag(uuid, "Name", "Grund", "Mod",
                5L, Long.MAX_VALUE, "global");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            Protokoll.schreibeBan(out, ban);
            assertEquals(ban, Protokoll.leseBan(stream(bytes.toByteArray())));
        }

        Protokoll.TimeoutEintrag timeout = new Protokoll.TimeoutEintrag(uuid, "", "", "",
                5L, 6L, "unbekannt");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            Protokoll.schreibeTimeout(out, timeout);
            assertEquals(timeout, Protokoll.leseTimeout(stream(bytes.toByteArray())));
        }

        Protokoll.FreezeEintrag freeze = new Protokoll.FreezeEintrag(uuid, "F", "G", "A",
                5L, Long.MAX_VALUE, "lobby");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            Protokoll.schreibeFreeze(out, freeze);
            assertEquals(freeze, Protokoll.leseFreeze(stream(bytes.toByteArray())));
        }

        Protokoll.IpMuteEintrag ipMute = new Protokoll.IpMuteEintrag("::ffff:10.0.0.1", "G", "A",
                5L, 6L, "global");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            Protokoll.schreibeIpMute(out, ipMute);
            assertEquals(ipMute, Protokoll.leseIpMute(stream(bytes.toByteArray())));
        }
    }

    @Test
    @DisplayName("Spiegel: PlatzhalterAntwort (v2-Komplettformat) feldgenau")
    void spiegelPlatzhalter() throws IOException {
        Protokoll.PlatzhalterAntwort original = new Protokoll.PlatzhalterAntwort(7L, true,
                true, 100L, false, Protokoll.PlatzhalterAntwort.INAKTIV,
                true, Long.MAX_VALUE, true, 200L,
                3, 4, 5, 6, 7,
                "mG", "mV", 1L, "bG", "bV", 2L,
                "tG", "tV", 3L, "fG", "fV", 4L,
                8, 9, "wG", "stufe");
        byte[] paket = Protokoll.platzhalterAntwort(original);
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        Protokoll.PlatzhalterAntwort ende = Protokoll.lesePlatzhalterAntwort(gelesen.nutzdaten());
        assertEquals(original, ende, "PlatzhalterAntwort v2 muss feldgenau überleben");
    }

    @Test
    @DisplayName("Spiegel: GUI-Anfrage/GUI-Antwort inkl. Kürzen feldgenau")
    void spiegelGui() throws IOException {
        String lang = "x".repeat(5000);
        Protokoll.GuiAnfrage anfrage = new Protokoll.GuiAnfrage(1L, UUID.randomUUID(),
                "status", lang, lang, 0, lang);
        byte[] anfragePaket = Protokoll.guiAnfrage(anfrage);
        Protokoll.GuiAnfrage gelesen = Protokoll.leseGuiAnfrage(Protokoll.paket(anfragePaket).nutzdaten());
        assertEquals("STATUS", gelesen.typ(), "typ wird normalisiert (trim + upper-case)");
        assertEquals(64, gelesen.ziel().length(), "ziel wird auf 64 Zeichen gekürzt");

        List<Protokoll.GuiZeile> zeilen = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            zeilen.add(new Protokoll.GuiZeile("AKTION", "Titel" + i, lang, "ban X 7d " + i, "meta"));
        }
        Protokoll.GuiAntwort antwort = new Protokoll.GuiAntwort(2L, true, lang, 2, 3, 60, zeilen);
        byte[] antwortPaket = Protokoll.guiAntwort(antwort);
        Protokoll.GuiAntwort gelesenAntwort =
                Protokoll.leseGuiAntwort(Protokoll.paket(antwortPaket).nutzdaten());
        assertEquals(54, gelesenAntwort.eintraege().size(),
                "C-K8.1: Zeilen über MAX_GUI_ZEILEN werden still abgeschnitten (kein Seiten-Hinweis)");
        assertEquals(60, gelesenAntwort.gesamt(), "Gesamtzähler bleibt erhalten");
        assertEquals(1000, gelesenAntwort.eintraege().get(0).details().length(),
                "Details werden auf MAX_GUI_TEXT gekürzt");
    }

    @Test
    @DisplayName("Hänge-Test: verkürzter Stream endet in IOException, nicht in Blockade")
    void haengeTestVerkuerzterStream() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            byte[] roh = new byte[]{0, 0, 0, 1, 0, 0, 0, 0};
            assertThrows(IOException.class, () -> Protokoll.leseMuteSync(stream(roh)));
            assertThrows(IOException.class, () -> Protokoll.leseModellBlock(stream(new byte[]{5, 'a', '.', 'o'})));
        });
    }

    @Test
    @DisplayName("Hänge-Test: unendlicher Null-Stream terminiert")
    void haengeTestUnendlicherStream() {
        InputStream endlos = new InputStream() {
            @Override
            public int read() {
                return 0;
            }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try {
                Protokoll.leseMute(new DataInputStream(endlos));
                fail("UUID 0/0 muss als fehlende UUID abgewiesen werden");
            } catch (IOException erwartet) {
                assertTrue(erwartet.getMessage().contains("UUID"), erwartet.getMessage());
            }
        });
    }

    @Test
    @DisplayName("C-S5.1 (Fix A-008): leseChatfilterSteuerung/leseChatfilterTreffer werfen IOException")
    void cS51CodecWirftIllegalArgumentStattIoException() {
        assertThrows(java.io.IOException.class,
                () -> Protokoll.leseChatfilterSteuerung(stream(new byte[]{3, 0})),
                "Fix A-008: filterModus=3 muss IOException werfen (Codec-Vertrag)");

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
              DataOutputStream out = new DataOutputStream(bytes)) {
            Protokoll.schreibeUuid(out, UUID.randomUUID());
            out.writeUTF("Name");
            out.writeUTF("CHAT");
            out.writeUTF("KI");
            out.writeDouble(2.0d);
            out.writeUTF("a".repeat(64));
            out.writeUTF("text");
            out.writeLong(1L);
            out.flush();
            assertThrows(java.io.IOException.class,
                    () -> Protokoll.leseChatfilterTreffer(stream(bytes.toByteArray())),
                    "Fix A-008: bewertung=2.0 muss IOException werfen (Codec-Vertrag)");
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("C-S8.1 (Fix A-009): PlatzhalterAntwort mit halber Freeze-Sektion degradiert auf v1-Werte")
    void cS81PlatzhalterHalbeFreezeSektionVerwirftGanzenFrame() {

        byte[] roh = bauePlatzhalterRoh(true, true);
        Protokoll.PlatzhalterAntwort degradiert = assertDoesNotThrow(
                () -> Protokoll.lesePlatzhalterAntwort(stream(roh)),
                "Fix A-009: halbe Freeze-Sektion muss degradieren statt den Frame zu verwerfen");
        assertEquals(false, degradiert.frozen());
        assertEquals(1, degradiert.verstossAnzahl());
        assertEquals(2, degradiert.verstossPunkte());
        assertEquals(3, degradiert.warns());
        assertEquals(4, degradiert.reportsGegen());
        assertEquals(0, degradiert.reportsOffen());

        Protokoll.PlatzhalterAntwort v1 = assertDoesNotThrow(
                () -> Protokoll.lesePlatzhalterAntwort(stream(bauePlatzhalterRoh(false, false))));
        assertEquals(false, v1.frozen());
        assertEquals(3, v1.warns());
        assertEquals(5, v1.reportsOffen());

        Protokoll.PlatzhalterAntwort v2 = assertDoesNotThrow(
                () -> Protokoll.lesePlatzhalterAntwort(stream(bauePlatzhalterRoh(true, false))));
        assertEquals(true, v2.frozen());
        assertEquals(200L, v2.freezeAblauf());
    }

    private static byte[] bauePlatzhalterRoh(boolean freezeFelder, boolean letztesIntAbschneiden) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(7L);
            out.writeBoolean(true);
            out.writeBoolean(true);
            out.writeLong(100L);
            out.writeBoolean(false);
            out.writeLong(Protokoll.PlatzhalterAntwort.INAKTIV);
            out.writeBoolean(true);
            out.writeLong(200L);
            if (freezeFelder) {
                out.writeBoolean(true);
                out.writeLong(200L);
            }
            out.writeInt(1);
            out.writeInt(2);
            out.writeInt(3);
            out.writeInt(4);
            out.writeInt(5);
            out.flush();
            byte[] alles = bytes.toByteArray();
            if (letztesIntAbschneiden) {
                byte[] gekuerzt = new byte[alles.length - 4];
                System.arraycopy(alles, 0, gekuerzt, 0, gekuerzt.length);
                return gekuerzt;
            }
            return alles;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
