package de.lightrixx.punish.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelSignaturTest {

    private static final TunnelSignatur.Richtung HIN =
            TunnelSignatur.Richtung.PROXY_ZU_BRIDGE;
    private static final TunnelSignatur.Richtung ZURUECK =
            TunnelSignatur.Richtung.BRIDGE_ZU_PROXY;

    @Test
    void signierenUndPruefenRoundtrip() throws Exception {
        byte[] schluessel = TunnelSignatur.schluesselAus("geheim-123", HIN);
        byte[] paket = Protokoll.hello("lobby", "4.0-beta+EA25b", "bridge");
        byte[] versand = TunnelSignatur.signieren(paket, schluessel);
        assertTrue(versand.length == paket.length + TunnelSignatur.LAENGE);
        assertArrayEquals(paket, TunnelSignatur.pruefenUndAbziehen(versand, schluessel));
    }

    @Test
    void manipulationWirdErkannt() throws Exception {
        byte[] schluessel = TunnelSignatur.schluesselAus("geheim-123", HIN);
        byte[] paket = Protokoll.hello("lobby", "4.0-beta+EA25b", "bridge");
        byte[] versand = TunnelSignatur.signieren(paket, schluessel);
        versand[5] ^= 0x01;
        assertThrows(IOException.class,
                () -> TunnelSignatur.pruefenUndAbziehen(versand, schluessel));
    }

    @Test
    void manipulationsAnSignaturWirdErkannt() throws Exception {
        byte[] schluessel = TunnelSignatur.schluesselAus("geheim-123", HIN);
        byte[] paket = Protokoll.hello("lobby", "4.0-beta+EA25b", "bridge");
        byte[] versand = TunnelSignatur.signieren(paket, schluessel);
        versand[versand.length - 1] ^= 0x01;
        assertThrows(IOException.class,
                () -> TunnelSignatur.pruefenUndAbziehen(versand, schluessel));
    }

    @Test
    void falschesGeheimnisWirdAbgewiesen() throws Exception {
        byte[] versand = TunnelSignatur.signieren(Protokoll.hello("lobby", "", ""),
                TunnelSignatur.schluesselAus("richtig", HIN));
        assertThrows(IOException.class, () -> TunnelSignatur.pruefenUndAbziehen(versand,
                TunnelSignatur.schluesselAus("falsch", HIN)));
    }

    @Test
    void falscheRichtungWirdAbgewiesen() throws Exception {
        byte[] versand = TunnelSignatur.signieren(Protokoll.hello("lobby", "", ""),
                TunnelSignatur.schluesselAus("geheim-123", HIN));
        assertThrows(IOException.class, () -> TunnelSignatur.pruefenUndAbziehen(versand,
                TunnelSignatur.schluesselAus("geheim-123", ZURUECK)),
                "Ein Proxy-Paket darf nicht in die Gegenrichtung gespiegelt werden");
    }

    @Test
    void gleichesSecretErgibtUnterschiedlicheRichtungsschluessel() {
        byte[] hin = TunnelSignatur.schluesselAus("geheim-123", HIN);
        byte[] zurueck = TunnelSignatur.schluesselAus("geheim-123", ZURUECK);
        assertFalse(Arrays.equals(hin, zurueck));
    }

    @Test
    void unsigniertWirdBeiGesetztemSchluesselAbgewiesen() {
        byte[] schluessel = TunnelSignatur.schluesselAus("geheim-123", HIN);
        byte[] paket = new byte[]{0x01, 0x02, 0x03};
        assertThrows(IOException.class,
                () -> TunnelSignatur.pruefenUndAbziehen(
                        Arrays.copyOf(paket, paket.length), schluessel));
    }

    @Test
    void fehlenderSchluesselWirdFailClosedAbgewiesen() throws Exception {
        byte[] paket = Protokoll.hello("lobby", "", "");
        assertThrows(IOException.class, () -> TunnelSignatur.signieren(paket, null));
        assertThrows(IOException.class, () -> TunnelSignatur.pruefenUndAbziehen(paket, null));
    }

    @Test
    void zuKurzesPaketWirdAbgewiesen() {
        byte[] schluessel = TunnelSignatur.schluesselAus("geheim-123", HIN);
        assertThrows(IOException.class,
                () -> TunnelSignatur.pruefenUndAbziehen(new byte[10], schluessel));
    }

    @Test
    void signaturIstDeterministisch() throws Exception {
        byte[] schluessel = TunnelSignatur.schluesselAus("geheim-123", HIN);
        byte[] paket = Protokoll.hello("lobby", "", "");
        assertArrayEquals(TunnelSignatur.signieren(paket, schluessel),
                TunnelSignatur.signieren(paket, schluessel));
    }

    @Test
    void istKonfiguriertErkenntLeerwerte() {
        assertTrue(TunnelSignatur.istKonfiguriert("geheim"));
        assertFalse(TunnelSignatur.istKonfiguriert(""));
        assertFalse(TunnelSignatur.istKonfiguriert("   "));
        assertFalse(TunnelSignatur.istKonfiguriert(null));
    }

    @Test
    void leeresGeheimnisWirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class,
                () -> TunnelSignatur.schluesselAus("", HIN));
        assertThrows(IllegalArgumentException.class,
                () -> TunnelSignatur.schluesselAus("   ", HIN));
        assertThrows(IllegalArgumentException.class,
                () -> TunnelSignatur.schluesselAus(null, HIN));
    }

    @Test
    void istSchwachErkenntKurzeGeheimnisse() {
        assertFalse(TunnelSignatur.istSchwach(null));
        assertFalse(TunnelSignatur.istSchwach("   "));
        assertTrue(TunnelSignatur.istSchwach("kurz"));
        assertTrue(TunnelSignatur.istSchwach("123456789012345"));
        assertFalse(TunnelSignatur.istSchwach("1234567890123456"));
        assertFalse(TunnelSignatur.istSchwach(" 1234567890123456 "));
    }
}
