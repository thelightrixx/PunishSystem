package de.lightrixx.punish.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tiefen-Audit (M9/M13): Die Größengrenze des Tunnels ist die einzige Bremse gegen
 * riesige Frames vor der HMAC-Berechnung. Beide Aufrufstellen müssen sie durchsetzen.
 */
class TunnelSignaturGroesseTest {

    private static final byte[] SCHLUESSEL = TunnelSignatur.schluesselAus(
            "geheim-1234567890", TunnelSignatur.Richtung.PROXY_ZU_BRIDGE);
    private static final int MAX_PAKET_MIT_SCHLUESSEL =
            Protokoll.MAX_NUTZDATEN + 1 + TunnelSignatur.LAENGE - TunnelSignatur.LAENGE;
    private static final int MAX_ROH_MIT_SCHLUESSEL =
            Protokoll.MAX_NUTZDATEN + 1 + TunnelSignatur.LAENGE;

    @Test
    @DisplayName("signieren akzeptiert exakt die Höchstgröße und weist ein Byte mehr ab")
    void signierenGroessengrenze() {
        assertDoesNotThrow(() -> TunnelSignatur.signieren(
                new byte[MAX_PAKET_MIT_SCHLUESSEL], SCHLUESSEL));
        assertThrows(IOException.class, () -> TunnelSignatur.signieren(
                new byte[MAX_PAKET_MIT_SCHLUESSEL + 1], SCHLUESSEL));
    }

    @Test
    @DisplayName("pruefenUndAbziehen weist zu große Frames vor der Signaturprüfung ab")
    void pruefenGroessengrenze() {
        assertThrows(IOException.class, () -> TunnelSignatur.pruefenUndAbziehen(
                new byte[MAX_ROH_MIT_SCHLUESSEL + 1], SCHLUESSEL));
        assertThrows(IOException.class, () -> TunnelSignatur.pruefenUndAbziehen(
                new byte[2 * Protokoll.MAX_NUTZDATEN], SCHLUESSEL));
    }

    @Test
    @DisplayName("Das kleinste signierte Paket (1 Byte + 32 Byte Signatur) ist gültig")
    void minimalesSigniertesPaketWirdAkzeptiert() throws Exception {
        byte[] versand = TunnelSignatur.signieren(new byte[]{0x01}, SCHLUESSEL);
        assertEquals(33, versand.length);
        assertArrayEquals(new byte[]{0x01}, TunnelSignatur.pruefenUndAbziehen(versand, SCHLUESSEL));
    }

    @Test
    @DisplayName("Ein korrekt signierter, aber zu großer Frame wird vor der Prüfung abgewiesen")
    void gueltigSignierterRiesrahmenWirdAbgewiesen() throws Exception {
        byte[] roh = new byte[MAX_ROH_MIT_SCHLUESSEL + 1];
        roh[0] = 0x01;
        int nutzlaenge = roh.length - TunnelSignatur.LAENGE;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SCHLUESSEL, "HmacSHA256"));
        byte[] signatur = mac.doFinal(Arrays.copyOf(roh, nutzlaenge));
        System.arraycopy(signatur, 0, roh, nutzlaenge, TunnelSignatur.LAENGE);
        assertThrows(IOException.class, () -> TunnelSignatur.pruefenUndAbziehen(roh, SCHLUESSEL));
    }
}
