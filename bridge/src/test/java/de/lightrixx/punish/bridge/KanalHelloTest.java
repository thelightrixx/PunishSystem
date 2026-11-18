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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void leererServerBleibtStillUndVersuchtBeimBeitrittErneut(boolean serverThread, boolean versandFehler)
            throws Exception {
        var plugin = mock(Plugin.class);
        var server = mock(Server.class);
        var spieler = mock(Player.class);
        var online = new java.util.concurrent.atomic.AtomicReference<List<Player>>(List.of());
        when(plugin.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(serverThread);
        org.mockito.Mockito.doAnswer(inv -> online.get()).when(server).getOnlinePlayers();
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(org.bukkit.scheduler.BukkitTask.class);
        });
        if (versandFehler) {
            org.mockito.Mockito.doThrow(new IllegalStateException("Versand fehlgeschlagen"))
                    .when(spieler).sendPluginMessage(eq(plugin), eq(Protokoll.KANAL), org.mockito.ArgumentMatchers.any());
        }
        byte[] klasse;
        try (var in = KanalClient.class.getResourceAsStream("KanalClient.class")) {
            klasse = java.util.Objects.requireNonNull(in).readAllBytes();
        }
        var isoliert = new ClassLoader(KanalClient.class.getClassLoader()) {
            Class<?> laden() throws java.io.IOException {
                for (String name : List.of("TunnelAuthBremse", "TunnelAuthBremse$Zustand")) {
                    try (var in = KanalClient.class.getResourceAsStream(name + ".class")) {
                        byte[] daten = java.util.Objects.requireNonNull(in).readAllBytes();
                        defineClass("de.lightrixx.punish.bridge." + name, daten, 0, daten.length);
                    }
                }
                return defineClass(KanalClient.class.getName(), klasse, 0, klasse.length);
            }
        }.laden();
        var logger = mock(org.slf4j.Logger.class);
        try (var factory = org.mockito.Mockito.mockStatic(org.slf4j.LoggerFactory.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            factory.when(() -> org.slf4j.LoggerFactory.getLogger(isoliert)).thenReturn(logger);
            var client = isoliert.getConstructor(Plugin.class, MuteCache.class).newInstance(plugin, new MuteCache());
            isoliert.getMethod("setTunnelSecret", String.class).invoke(client, "test-bridge-secret");
            var hello = isoliert.getMethod("sendeHello", String.class);
            org.mockito.Mockito.clearInvocations(logger);
            for (int i = 0; i < 20; i++) {
                hello.invoke(client, "lobby");
            }
            org.mockito.Mockito.verifyNoInteractions(logger, spieler);
            online.set(List.of(spieler));
            hello.invoke(client, "lobby");
            verify(spieler, times(versandFehler ? 1 : 4)).sendPluginMessage(
                    eq(plugin), eq(Protokoll.KANAL), org.mockito.ArgumentMatchers.any());
            assertEquals(versandFehler, org.mockito.Mockito.mockingDetails(logger).getInvocations().stream()
                    .anyMatch(inv -> inv.getMethod().getName().equals("warn")));
            online.set(List.of());
            org.mockito.Mockito.clearInvocations(logger, spieler);
            hello.invoke(client, "lobby");
            org.mockito.Mockito.verifyNoInteractions(logger, spieler);
        }
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
