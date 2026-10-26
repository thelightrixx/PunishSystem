package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.net.TunnelSignatur;
import de.lightrixx.punish.storage.MuteRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TunnelSequenzTest {

    private static final String SECRET = "geheim-123";

    private static BrueckenKanal kanal() {
        ProxyServer proxy = mock(ProxyServer.class);
        MuteRepository repo = mock(MuteRepository.class);
        when(repo.alle()).thenReturn(List.of());
        BrueckenKanal kanal = new BrueckenKanal(mock(PunishVelocity.class), proxy,
                mock(org.slf4j.Logger.class), repo, null);
        kanal.setBridgeSecrets(Map.of("lobby", SECRET));
        return kanal;
    }

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);
        return server;
    }

    private static byte[] signiert(byte[] paket) throws Exception {
        return TunnelSignatur.signieren(paket,
                TunnelSignatur.schluesselAus(SECRET,
                        TunnelSignatur.Richtung.BRIDGE_ZU_PROXY));
    }

    @Test
    void nichtHelloVorHelloVerworfen() throws Exception {
        BrueckenKanal kanal = kanal();
        RegisteredServer server = server("lobby");
        byte[] paket = Protokoll.befehlAusfuehren(null, "ban Notch 7d X");
        byte[] klar = kanal.eingehendPruefen(server, signiert(paket));
        assertNotNull(klar);
        assertNull(kanal.sequenzPruefen(server, klar),
                "Nicht-HELLO vor erstem HELLO muss verworfen werden (B-012)");
    }

    @Test
    void helloReplayWirdAbgewiesen() throws Exception {
        BrueckenKanal kanal = kanal();
        RegisteredServer server = server("lobby");
        byte[] hello = Protokoll.hello("lobby", "1.0", "bridge");
        byte[] klar = kanal.eingehendPruefen(server, signiert(hello));
        assertNotNull(kanal.sequenzPruefen(server, klar));
        assertNull(kanal.sequenzPruefen(server, klar.clone()),
                "Ein wiedergegebenes HELLO darf die Empfangssequenz nicht erneut zurücksetzen");
    }

    @Test
    void helloOeffnetTor() throws Exception {
        BrueckenKanal kanal = kanal();
        RegisteredServer server = server("lobby");
        byte[] hello = Protokoll.hello("lobby", "1.0", "bridge");
        byte[] klar = kanal.eingehendPruefen(server, signiert(hello));
        assertNotNull(kanal.sequenzPruefen(server, klar));
        kanal.beantworteHello(server, "lobby", 7, "1.0", "bridge");
        byte[] befehl = Protokoll.rahmeMitSequenz(
                Protokoll.befehlAusfuehren(null, "ban Notch 7d X"), 1L);
        byte[] klarBefehl = kanal.eingehendPruefen(server, signiert(befehl));
        assertNotNull(kanal.sequenzPruefen(server, klarBefehl),
                "Nach HELLO muss eine sequenzierte Nachricht akzeptiert werden");
    }

    @Test
    void proxySendetSequenziertBeiV7() throws Exception {
        BrueckenKanal kanal = kanal();
        RegisteredServer server = server("lobby");
        byte[] hello = Protokoll.hello("lobby", "1.0", "bridge");
        assertNotNull(kanal.sequenzPruefen(server,
                kanal.eingehendPruefen(server, signiert(hello))));
        kanal.beantworteHello(server, "lobby", 7, "1.0", "bridge");
        byte[] befehl = Protokoll.befehlAusfuehren(null, "ban Notch 7d X");
        kanal.sendeAn(server, befehl);
        var fang = org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.Mockito.verify(server, org.mockito.Mockito.atLeastOnce())
                .sendPluginMessage(org.mockito.ArgumentMatchers.any(), fang.capture());
        byte[] versand = fang.getAllValues().get(fang.getAllValues().size() - 1);
        byte[] klar = TunnelSignatur.pruefenUndAbziehen(versand,
                TunnelSignatur.schluesselAus(SECRET,
                        TunnelSignatur.Richtung.PROXY_ZU_BRIDGE));
        Protokoll.SequenzRahmen rahmen = Protokoll.entrahmeSequenz(klar);
        assertTrue(rahmen.sequenziert(), "Ab v7 muss der Proxy ein Sequenzrahmen senden");
        assertTrue(rahmen.sequenz() > 0L, "Die Sequenznummer muss positiv sein");
        assertEquals(Protokoll.BEFEHL_AUSFUEHREN, Protokoll.paket(rahmen.paket()).typ(),
                "Der Rahmen muss das unveränderte Paket tragen");
        assertNotNull(kanal.sequenzPruefen(server, klar), "Erste sequenzierte Nachricht akzeptiert");
        assertNull(kanal.sequenzPruefen(server, klar.clone()),
                "Wiederholter Empfang derselben Nachricht ist ein Replay (B-003)");

        kanal.sendeAn(server, Protokoll.befehlAusfuehren(null, "mute Notch 10m X"));
        var zweiterFang = org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.Mockito.verify(server, org.mockito.Mockito.atLeast(2))
                .sendPluginMessage(org.mockito.ArgumentMatchers.any(), zweiterFang.capture());
        var alleVersande = zweiterFang.getAllValues();
        byte[] zweiterVersand = alleVersande.get(alleVersande.size() - 1);
        Protokoll.SequenzRahmen zweiterRahmen = Protokoll.entrahmeSequenz(
                TunnelSignatur.pruefenUndAbziehen(zweiterVersand,
                        TunnelSignatur.schluesselAus(SECRET,
                                TunnelSignatur.Richtung.PROXY_ZU_BRIDGE)));
        assertTrue(zweiterRahmen.sequenziert(), "Auch die zweite Nachricht ist sequenziert");
        assertTrue(zweiterRahmen.sequenz() > rahmen.sequenz(),
                "Die Sendesequenz muss steigen");
    }

    @Test
    void doppelterEmpfangAbgewiesen() throws Exception {
        BrueckenKanal kanal = kanal();
        RegisteredServer server = server("lobby");
        byte[] hello = Protokoll.hello("lobby", "1.0", "bridge");
        kanal.sequenzPruefen(server, kanal.eingehendPruefen(server, signiert(hello)));
        kanal.beantworteHello(server, "lobby", 7, "1.0", "bridge");
        byte[] befehl = Protokoll.befehlAusfuehren(null, "mute Notch 10m X");
        byte[] gerahmt = Protokoll.rahmeMitSequenz(befehl, 1L);
        assertNotNull(kanal.sequenzPruefen(server, gerahmt));
        assertNull(kanal.sequenzPruefen(server, gerahmt.clone()),
                "Replay derselben Sequenz muss abgewiesen werden (B-003)");
    }

    @Test
    void helloSetztSequenzZurueck() throws Exception {
        BrueckenKanal kanal = kanal();
        RegisteredServer server = server("lobby");
        byte[] hello = Protokoll.hello("lobby", "1.0", "bridge");
        kanal.sequenzPruefen(server, kanal.eingehendPruefen(server, signiert(hello)));
        kanal.beantworteHello(server, "lobby", 7, "1.0", "bridge");
        byte[] befehl = Protokoll.befehlAusfuehren(null, "mute Notch 10m X");
        assertNotNull(kanal.sequenzPruefen(server, Protokoll.rahmeMitSequenz(befehl, 50L)));
        byte[] hello2 = Protokoll.hello("lobby", "1.0", "bridge");
        assertNotNull(kanal.sequenzPruefen(server, hello2));
        assertNotNull(kanal.sequenzPruefen(server, Protokoll.rahmeMitSequenz(befehl, 1L)),
                "Nach HELLO beginnt die Sequenz neu");
    }

    @Test
    void identischeBridgeSecretsWerdenErkannt() {
        java.util.Map<String, String> gleich = new java.util.LinkedHashMap<>();
        gleich.put("lobby", "gleich");
        gleich.put("survival", "gleich");
        assertEquals(List.of("survival"), BrueckenKanal.doppelteSecretNamen(gleich));
        assertTrue(BrueckenKanal.doppelteSecretNamen(
                Map.of("lobby", "eins", "survival", "zwei")).isEmpty());
        assertTrue(BrueckenKanal.doppelteSecretNamen(Map.of("lobby", " ")).isEmpty());
        assertTrue(BrueckenKanal.doppelteSecretNamen(null).isEmpty());
    }
}
