package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.net.TunnelSignatur;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KanalHelloTest {

    @Test
    @DisplayName("A-017: HELLO ohne Online-Spieler wirft nicht")
    void helloOhneTraegerWirftNicht() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        KanalClient client = new KanalClient(plugin, new MuteCache());
        assertDoesNotThrow(() -> client.sendeHello("lobby"));
    }

    @Test
    void nurAuthentifiziertesHelloAckBestaetigtProxyKontakt() throws Exception {
        var client = new KanalClient(mock(Plugin.class), new MuteCache());
        client.setTunnelSecret("test-bridge-secret");
        byte[] ack = de.lightrixx.punish.net.Protokoll.helloAck(
                de.lightrixx.punish.net.Protokoll.VERSION, "lobby", "4.0");
        client.onPluginMessageReceived(de.lightrixx.punish.net.Protokoll.KANAL, (org.bukkit.entity.Player) null, ack);
        assertEquals(0L, client.letzteProxyBestaetigungMillis());
        byte[] signiert = de.lightrixx.punish.net.TunnelSignatur.signieren(
                de.lightrixx.punish.net.Protokoll.rahmeMitSequenz(ack, 1L),
                de.lightrixx.punish.net.TunnelSignatur.schluesselAus("test-bridge-secret",
                        de.lightrixx.punish.net.TunnelSignatur.Richtung.PROXY_ZU_BRIDGE));
        long vorher = System.currentTimeMillis();
        client.onPluginMessageReceived(de.lightrixx.punish.net.Protokoll.KANAL, (org.bukkit.entity.Player) null, signiert);
        assertTrue(client.letzteProxyBestaetigungMillis() >= vorher);
    }

    @Test
    void sammelabgleichNachAbbruchGibtErstSyncFrei() throws Exception {
        var plugin = mock(Plugin.class);
        var server = mock(Server.class);
        var spieler = mock(Player.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(true);
        doReturn(List.of(spieler)).when(server).getOnlinePlayers();
        var mutes = new MuteCache();
        var client = new KanalClient(plugin, mutes);
        String secret = "test-bridge-secret";
        client.setTunnelSecret(secret);

        sende(client, secret, Protokoll.syncBegin(41L), 1L);
        client.sendeHello("lobby");
        assertTrue(mutes.syncLaeuft(), "HELLO darf einen laufenden Sync nicht abbrechen");
        var fang = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(spieler, times(4)).sendPluginMessage(eq(plugin), eq(Protokoll.KANAL), fang.capture());
        byte[] helloPaket = Protokoll.entrahmeSequenz(TunnelSignatur.pruefenUndAbziehen(
                fang.getAllValues().getFirst(),
                TunnelSignatur.schluesselAus(secret, TunnelSignatur.Richtung.BRIDGE_ZU_PROXY))).paket();
        var hello = Protokoll.leseHello(Protokoll.paket(helloPaket).nutzdaten());
        assertTrue(hello.vollSyncAngefordert());

        sende(client, secret, Protokoll.helloAck(Protokoll.VERSION, "lobby", "4.0", hello.sessionId()), 2L);
        assertTrue(mutes.syncLaeuft(), "Das ACK allein liefert noch keinen neuen Strafbestand");
        assertTrue(client.vollSyncNoetig());
        UUID ziel = UUID.randomUUID();
        var mute = new Protokoll.MuteEintrag(ziel, "Spieler", "Grund", "Mod", 1L, Long.MAX_VALUE, "global");
        sende(client, secret, Protokoll.muteSync(List.of(mute)), 3L);
        sende(client, secret, Protokoll.syncEnd(41L), 4L);

        assertFalse(mutes.syncLaeuft());
        assertTrue(mutes.istJeSynchronisiert());
        assertFalse(client.vollSyncNoetig());
        assertEquals(List.of(mute), mutes.alle());
    }

    private static void sende(KanalClient client, String secret, byte[] paket, long sequenz) throws Exception {
        byte[] signiert = TunnelSignatur.signieren(Protokoll.rahmeMitSequenz(paket, sequenz),
                TunnelSignatur.schluesselAus(secret, TunnelSignatur.Richtung.PROXY_ZU_BRIDGE));
        client.onPluginMessageReceived(Protokoll.KANAL, (Player) null, signiert);
    }
}
