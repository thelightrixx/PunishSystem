package de.lightrixx.punish.net;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ProtokollDecoderFuzzTest {

    @Test
    void zufallsdatenLoesenNurErwarteteFehlerAus() {
        Random zufall = new Random(0xC0FFEEL);
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

        for (int runde = 0; runde < 2000; runde++) {
            byte[] daten = new byte[zufall.nextInt(256)];
            zufall.nextBytes(daten);
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
