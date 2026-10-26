package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.net.TunnelSignatur;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KanalTunnelTest {

    private static KanalClient client() {
        return new KanalClient(mock(Plugin.class), new MuteCache());
    }

    @Test
    void sendeBefehlLimit500() {
        Plugin plugin = mock(Plugin.class);
        KanalClient client = new KanalClient(plugin, new MuteCache());
        String lang = "ban Notch 7d " + "x".repeat(490);
        assertDoesNotThrow(() -> client.sendeBefehl(UUID.randomUUID(), lang));
        verify(plugin, never()).getServer();
    }

    @Test
    void sanierenEntferntSteuerung() {
        String roh = "a\rb\nc\u001Bd\u0001e\u007Ff";
        String sauber = KanalClient.sanierenFuerLog(roh);
        assertFalse(sauber.contains("\r"));
        assertFalse(sauber.contains("\n"));
        assertFalse(sauber.contains("\u001B"));
        assertEquals("a b c def", sauber);
        assertEquals("", KanalClient.sanierenFuerLog(null));
    }

    @Test
    void versandOhneSecretBlockiertOhneServerZugriff() {
        Plugin plugin = mock(Plugin.class);
        KanalClient client = new KanalClient(plugin, new MuteCache());
        assertThrows(IllegalStateException.class,
                () -> client.sendeBefehl(UUID.randomUUID(), "mute Ziel 10m Spam"));
        verify(plugin, never()).getServer();
    }

    @Test
    void versandMitSecretErreichtTraeger() {
        Plugin plugin = mock(Plugin.class);
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.entity.Player spieler = mock(org.bukkit.entity.Player.class);
        when(server.isPrimaryThread()).thenReturn(true);
        doReturn(java.util.List.of(spieler)).when(server).getOnlinePlayers();
        when(plugin.getServer()).thenReturn(server);
        KanalClient client = new KanalClient(plugin, new MuteCache());
        client.setTunnelSecret("geheim-123");
        assertDoesNotThrow(() -> client.sendeBefehl(UUID.randomUUID(), "mute Ziel 10m Spam"));
        verify(spieler).sendPluginMessage(eq(plugin), eq(Protokoll.KANAL), any(byte[].class));
    }

    @Test
    void unsequenzierteNachrichtWirdAbgewiesen() throws Exception {
        KanalClient client = client();
        byte[] paket = Protokoll.muteSync(List.of());
        assertNull(client.sequenzGeprueft(paket),
                "Protokoll v7 verlangt einen Sequenzrahmen; Legacy-Passthrough ist entfernt.");
    }

    @Test
    void replayVorHelloAckWirdAbgewiesen() throws Exception {
        KanalClient client = client();
        byte[] gerahmt = Protokoll.rahmeMitSequenz(Protokoll.muteSync(List.of()), 5L);
        assertNotNull(client.sequenzGeprueft(gerahmt));
        assertNull(client.sequenzGeprueft(gerahmt.clone()),
                "Auch vor HELLO_ACK darf eine sequenzierte Nachricht nicht am Replay-Schutz vorbeigehen.");
    }

    @Test
    void replayBeiV7Abgewiesen() throws Exception {
        KanalClient client = client();
        client.setTunnelSecret("geheim-123");
        byte[] ack = Protokoll.rahmeMitSequenz(
                Protokoll.helloAck(7, "lobby", "1.0"), 1L);
        byte[] versand = TunnelSignatur.signieren(ack,
                TunnelSignatur.schluesselAus("geheim-123",
                        TunnelSignatur.Richtung.PROXY_ZU_BRIDGE));
        client.onPluginMessageReceived(Protokoll.KANAL, (org.bukkit.entity.Player) null, versand);
        assertEquals(7, client.proxyVersion());

        byte[] paket = Protokoll.muteSync(List.of());
        byte[] gerahmt = Protokoll.rahmeMitSequenz(paket, 2L);
        assertNotNull(client.sequenzGeprueft(gerahmt));
        assertNull(client.sequenzGeprueft(gerahmt.clone()),
                "Replay derselben Sequenz muss abgewiesen werden (B-003)");
    }
}
