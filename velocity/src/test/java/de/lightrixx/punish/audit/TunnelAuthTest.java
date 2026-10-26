package de.lightrixx.punish.audit;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.net.TunnelSignatur;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.velocity.BrueckenKanal;
import de.lightrixx.punish.velocity.PunishVelocity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TunnelAuthTest {

    private static BrueckenKanal kanal() {
        ProxyServer proxy = mock(ProxyServer.class);
        MuteRepository repo = mock(MuteRepository.class);
        return new BrueckenKanal(mock(PunishVelocity.class), proxy,
                mock(org.slf4j.Logger.class), repo, null);
    }

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);
        return server;
    }

    @Test
    void ohneSecretFailClosed() throws Exception {
        BrueckenKanal kanal = kanal();
        RegisteredServer server = server("lobby");
        byte[] paket = Protokoll.hello("lobby", "4.0-beta+EA25b", "bridge");
        assertNull(kanal.eingehendPruefen(server, paket),
                "Ohne Secret muss der Tunnel fail-closed abweisen (B-001)");
    }

    @Test
    void mitSecretUnsigniertAbgewiesen() throws Exception {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of("lobby", "geheim-123"));
        RegisteredServer server = server("lobby");
        byte[] paket = Protokoll.hello("lobby", "4.0-beta+EA25b", "bridge");
        assertNull(kanal.eingehendPruefen(server, paket),
                "Unsigniertes HELLO muss bei gesetztem Secret abgewiesen werden");
    }

    @Test
    void mitSecretSigniertAkzeptiert() throws Exception {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of("lobby", "geheim-123"));
        RegisteredServer server = server("lobby");
        byte[] paket = Protokoll.hello("lobby", "4.0-beta+EA25b", "bridge");
        byte[] versand = TunnelSignatur.signieren(paket,
                TunnelSignatur.schluesselAus("geheim-123",
                        TunnelSignatur.Richtung.BRIDGE_ZU_PROXY));
        byte[] klar = kanal.eingehendPruefen(server, versand);
        assertNotNull(klar, "Korrekt signiertes Paket muss akzeptiert werden");
        assertArrayEquals(paket, klar);
        Protokoll.Hello hello = Protokoll.leseHello(Protokoll.paket(klar).nutzdaten());
        assertNotNull(hello);
    }

    @Test
    void falschesSecretAbgewiesen() throws Exception {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of("lobby", "richtig"));
        RegisteredServer server = server("lobby");
        byte[] paket = Protokoll.hello("lobby", "", "");
        byte[] versand = TunnelSignatur.signieren(paket,
                TunnelSignatur.schluesselAus("falsch",
                        TunnelSignatur.Richtung.BRIDGE_ZU_PROXY));
        assertNull(kanal.eingehendPruefen(server, versand));
    }

    @Test
    void fremdeBridgeKannSichNichtAlsAndereAusgeben() throws Exception {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of(
                "lobby", "lobby-secret-123",
                "survival", "survival-secret-123"));
        RegisteredServer survival = server("survival");
        byte[] hello = Protokoll.hello("survival", "4.0", "bridge");
        byte[] mitLobbySecret = TunnelSignatur.signieren(hello,
                TunnelSignatur.schluesselAus("lobby-secret-123",
                        TunnelSignatur.Richtung.BRIDGE_ZU_PROXY));
        assertNull(kanal.eingehendPruefen(survival, mitLobbySecret),
                "Eine kompromittierte Lobby-Bridge darf keine Survival-Nachrichten signieren");
        byte[] mitEigenemSecret = TunnelSignatur.signieren(hello,
                TunnelSignatur.schluesselAus("survival-secret-123",
                        TunnelSignatur.Richtung.BRIDGE_ZU_PROXY));
        assertNotNull(kanal.eingehendPruefen(survival, mitEigenemSecret));
    }

    @Test
    void bridgeOhneEintragFailClosed() throws Exception {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of("lobby", "lobby-secret-123"));
        RegisteredServer survival = server("survival");
        byte[] hello = Protokoll.hello("survival", "4.0", "bridge");
        byte[] versand = TunnelSignatur.signieren(hello,
                TunnelSignatur.schluesselAus("lobby-secret-123",
                        TunnelSignatur.Richtung.BRIDGE_ZU_PROXY));
        assertNull(kanal.eingehendPruefen(survival, versand),
                "Ohne eigenen Eintrag in bridge-secrets bleibt die Bridge stumm");
    }

    @Test
    void protokollV6WirdAbgewiesen() {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of("lobby", "geheim-123"));
        RegisteredServer server = server("lobby");
        kanal.beantworteHello(server, "lobby", 6, "4.0", "bridge");
        verify(server, never()).sendPluginMessage(any(), any(byte[].class));
    }

    @Test
    void falscherServernameWirdAbgewiesen() {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of("lobby", "geheim-123"));
        RegisteredServer server = server("lobby");
        kanal.beantworteHello(server, "survival", 7, "4.0", "bridge");
        verify(server, never()).sendPluginMessage(any(), any(byte[].class));
    }

    @Test
    void sendeAnSigniertBeiGesetztemSecret() throws Exception {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of("lobby", "geheim-123"));
        RegisteredServer server = server("lobby");
        byte[] paket = Protokoll.muteSync(java.util.List.of(
                new Protokoll.MuteEintrag(UUID.randomUUID(), "Ziel", "Grund", "Mod",
                        1L, 2L, "global")));
        kanal.sendeAn(server, paket);
        ArgumentCaptor<byte[]> fang = ArgumentCaptor.forClass(byte[].class);
        verify(server).sendPluginMessage(any(), fang.capture());
        byte[] versand = fang.getValue();
        assertNotNull(versand);
        byte[] klar = TunnelSignatur.pruefenUndAbziehen(versand,
                TunnelSignatur.schluesselAus("geheim-123",
                        TunnelSignatur.Richtung.PROXY_ZU_BRIDGE));
        assertArrayEquals(paket, klar);
    }

    @Test
    void sendeAnOhneSecretVerweigert() throws Exception {
        BrueckenKanal kanal = kanal();
        RegisteredServer server = server("lobby");
        byte[] paket = Protokoll.muteSync(java.util.List.of());
        kanal.sendeAn(server, paket);
        verify(server, never()).sendPluginMessage(any(), eq(paket));
    }

    @Test
    void sekretnullAktiviertFailClosed() throws Exception {
        BrueckenKanal kanal = kanal();
        kanal.setBridgeSecrets(Map.of("lobby", "geheim-123"));
        kanal.setBridgeSecrets(Map.of());
        RegisteredServer server = server("lobby");
        byte[] paket = Protokoll.hello("lobby", "", "");
        assertNull(kanal.eingehendPruefen(server, paket),
                "Nach Entfernen des Secrets muss fail-closed greifen");
    }
}
