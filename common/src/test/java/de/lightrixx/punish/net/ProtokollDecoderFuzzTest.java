package de.lightrixx.punish.net;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ProtokollDecoderFuzzTest {

    @Test
    void zufallsdatenLoesenNurErwarteteFehlerAus() throws Exception {
        Random zufall = new Random(0xC0FFEEL);
        List<byte[]> vorlagen = List.of(
                Protokoll.hello("lobby", "4.0", "bridge"),
                Protokoll.helloAck(Protokoll.VERSION, "lobby", "4.0", "session"),
                Protokoll.muteSync(List.of(new Protokoll.MuteEintrag(
                        new java.util.UUID(0L, 1L), "Spieler", "Grund", "Mod", 1L, Long.MAX_VALUE, "global"))),
                Protokoll.updateMeta(new Protokoll.UpdateMeta("bridge", "4.1", 8192L, "a".repeat(64), 4096, 2)),
                Protokoll.guiAntwort(new Protokoll.GuiAntwort(1L, true, "", 1, 1, 1,
                        List.of(new Protokoll.GuiZeile("RECHT", "punish.gui", "", "", "")))));
        List<Decoder> decoder = List.of(
                in -> Protokoll.leseMute(in),
                in -> Protokoll.leseIpMute(in),
                in -> Protokoll.leseHello(in),
                in -> Protokoll.leseMuteSync(in),
                in -> Protokoll.leseIpMuteSync(in),
                in -> Protokoll.leseIpListe(in),
                in -> Protokoll.leseBan(in),
                in -> Protokoll.leseTimeout(in),
                in -> Protokoll.leseFreeze(in),
                in -> Protokoll.leseBanSync(in),
                in -> Protokoll.leseTimeoutSync(in),
                in -> Protokoll.leseFreezeSync(in),
                in -> Protokoll.leseBefehl(in),
                in -> Protokoll.leseChatfilterTreffer(in),
                in -> Protokoll.leseChatfilterSteuerung(in),
                in -> Protokoll.leseChatfilterWort(in),
                in -> Protokoll.leseModellStand(in),
                in -> Protokoll.leseModellMeta(in),
                in -> Protokoll.leseModellBlock(in),
                in -> Protokoll.leseModellFertig(in),
                in -> Protokoll.leseModellQuittung(in),
                in -> Protokoll.leseTextStand(in),
                in -> Protokoll.leseTextMeta(in),
                in -> Protokoll.leseTextBlock(in),
                in -> Protokoll.leseTextFertig(in),
                in -> Protokoll.leseTextQuittung(in),
                in -> Protokoll.leseUpdateStand(in),
                in -> Protokoll.leseUpdateMeta(in),
                in -> Protokoll.leseUpdateBlock(in),
                in -> Protokoll.leseUpdateFertig(in),
                in -> Protokoll.leseUpdateQuittung(in),
                in -> Protokoll.leseUuidListe(in),
                in -> Protokoll.leseGuiAnfrage(in),
                in -> Protokoll.leseGuiZeile(in),
                in -> Protokoll.leseGuiAntwort(in),
                in -> Protokoll.lesePlatzhalterAnfrage(in),
                in -> Protokoll.lesePlatzhalterAntwort(in),
                in -> Protokoll.leseHelloAck(in),
                in -> Protokoll.leseHelloAckMitName(in),
                in -> Protokoll.leseSyncGeneration(in),
                in -> Protokoll.entrahmeSequenz(leseBytes(in)));

        for (int runde = 0; runde < 4000; runde++) {
            byte[] daten;
            if (runde < 2000) {
                daten = new byte[zufall.nextInt(256)];
                zufall.nextBytes(daten);
            } else {
                byte[] vorlage = vorlagen.get(runde % vorlagen.size());
                daten = java.util.Arrays.copyOfRange(vorlage, 1,
                        runde % 3 == 0 ? 1 + zufall.nextInt(vorlage.length) : vorlage.length);
                if (daten.length > 0 && runde % 3 == 1) {
                    daten[zufall.nextInt(daten.length)] ^= (byte) (1 << zufall.nextInt(8));
                } else if (daten.length >= 4 && runde % 3 == 2) {
                    java.nio.ByteBuffer.wrap(daten).putInt(Integer.MAX_VALUE);
                }
            }
            for (Decoder leser : decoder) {
                assertDoesNotThrow(() -> {
                    try {
                        leser.lese(new DataInputStream(new java.io.ByteArrayInputStream(daten)));
                    } catch (IOException | IllegalArgumentException erwartet) {
                        // Erwartete Ablehnung unvollständiger oder ungültiger Daten.
                    }
                });
            }
            assertDoesNotThrow(() -> {
                try {
                    Protokoll.paket(daten);
                } catch (IOException | IllegalArgumentException erwartet) {
                    // Erwartete Ablehnung.
                }
            });
        }
    }

    private static byte[] leseBytes(DataInputStream in) throws IOException {
        byte[] rest = in.readAllBytes();
        return rest;
    }

    @FunctionalInterface
    private interface Decoder {
        void lese(DataInputStream in) throws IOException;
    }
}
