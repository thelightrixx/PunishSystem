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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"   ", "123456789012345", " 123456789012345 "})
    void ungueltigesSecretSperrtAuchEinenBisherAktivenTunnel(String secret) throws Exception {
        var client = client();
        String vorher = "punish-test-secret-lange";
        client.setTunnelSecret(vorher);
        assertTrue(client.tunnelSigniert());

        client.setTunnelSecret(secret);

        assertFalse(client.tunnelSigniert());
        assertThrows(IllegalStateException.class,
                () -> client.sendeBefehl(UUID.randomUUID(), "mute Ziel 10m Spam"));
        byte[] ack = TunnelSignatur.signieren(Protokoll.rahmeMitSequenz(
                Protokoll.helloAck(Protokoll.VERSION, "lobby", "4.0"), 1L),
                TunnelSignatur.schluesselAus(vorher, TunnelSignatur.Richtung.PROXY_ZU_BRIDGE));
        client.onPluginMessageReceived(Protokoll.KANAL, (org.bukkit.entity.Player) null, ack);
        assertEquals(-1, client.proxyVersion());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1234567890123456", " 1234567890123456 "})
    void secretMitGenauSechzehnZeichenAktiviertDenTunnel(String secret) {
        var client = client();
        client.setTunnelSecret(secret);
        assertTrue(client.tunnelSigniert());
    }

    @Test
    void sendeBefehlLimit500() throws Exception {
        Plugin plugin = mock(Plugin.class);
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.entity.Player spieler = mock(org.bukkit.entity.Player.class);
        when(server.isPrimaryThread()).thenReturn(true);
        doReturn(java.util.List.of(spieler)).when(server).getOnlinePlayers();
        when(plugin.getServer()).thenReturn(server);
        KanalClient client = new KanalClient(plugin, new MuteCache());
        client.setTunnelSecret("punish-test-secret-lange");
        UUID absender = UUID.randomUUID();

        String praefix = "ban Notch 7d ";
        String grenze = praefix + "x".repeat(500 - praefix.length());
        assertEquals(500, grenze.length());
        client.sendeBefehl(absender, grenze);

        org.mockito.ArgumentCaptor<byte[]> captor =
                org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(spieler).sendPluginMessage(eq(plugin), eq(Protokoll.KANAL), captor.capture());
        byte[] klar = TunnelSignatur.pruefenUndAbziehen(captor.getValue(),
                TunnelSignatur.schluesselAus("punish-test-secret-lange", TunnelSignatur.Richtung.BRIDGE_ZU_PROXY));
        Protokoll.SequenzRahmen rahmen = Protokoll.entrahmeSequenz(klar);
        assertTrue(rahmen.sequenziert(), "Befehl muss sequenziert gesendet werden");
        Protokoll.Paket paket = Protokoll.paket(rahmen.paket());
        assertEquals(Protokoll.BEFEHL_AUSFUEHREN, paket.typ());
        Protokoll.BefehlsAuftrag auftrag = Protokoll.leseBefehl(paket.nutzdaten());
        assertEquals(absender, auftrag.alsUuid());
        assertEquals(grenze, auftrag.zeile(), "500 Zeichen müssen unverändert ankommen");

        String zuLang = praefix + "x".repeat(501 - praefix.length());
        assertEquals(501, zuLang.length());
        assertDoesNotThrow(() -> client.sendeBefehl(absender, zuLang));
        verify(spieler, org.mockito.Mockito.times(1))
                .sendPluginMessage(eq(plugin), eq(Protokoll.KANAL), any(byte[].class));
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
        client.setTunnelSecret("punish-test-secret-lange");
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
        client.setTunnelSecret("punish-test-secret-lange");
        byte[] ack = Protokoll.rahmeMitSequenz(
                Protokoll.helloAck(7, "lobby", "1.0"), 1L);
        byte[] versand = TunnelSignatur.signieren(ack,
                TunnelSignatur.schluesselAus("punish-test-secret-lange",
                        TunnelSignatur.Richtung.PROXY_ZU_BRIDGE));
        client.onPluginMessageReceived(Protokoll.KANAL, (org.bukkit.entity.Player) null, versand);
        assertEquals(7, client.proxyVersion());

        byte[] paket = Protokoll.muteSync(List.of());
        byte[] gerahmt = Protokoll.rahmeMitSequenz(paket, 2L);
        assertNotNull(client.sequenzGeprueft(gerahmt));
        assertNull(client.sequenzGeprueft(gerahmt.clone()),
                "Replay derselben Sequenz muss abgewiesen werden (B-003)");
    }

    @Test
    void neuesHelloAckNachProxyNeustartSetztNurMitPassenderSessionZurueck() throws Exception {
        var plugin = mock(Plugin.class);
        var server = mock(org.bukkit.Server.class);
        var spieler = mock(org.bukkit.entity.Player.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(true);
        doReturn(List.of(spieler)).when(server).getOnlinePlayers();
        var client = new KanalClient(plugin, new MuteCache());
        client.setTunnelSecret("punish-test-secret-lange");
        byte[] sync = Protokoll.muteSync(List.of());
        assertNotNull(client.sequenzGeprueft(Protokoll.rahmeMitSequenz(sync, 100L)));
        client.sendeHello("lobby");
        var fang = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(spieler, org.mockito.Mockito.times(4))
                .sendPluginMessage(eq(plugin), eq(Protokoll.KANAL), fang.capture());
        byte[] hello = Protokoll.entrahmeSequenz(TunnelSignatur.pruefenUndAbziehen(
                fang.getAllValues().getFirst(), TunnelSignatur.schluesselAus("punish-test-secret-lange",
                        TunnelSignatur.Richtung.BRIDGE_ZU_PROXY))).paket();
        String session = Protokoll.leseHello(Protokoll.paket(hello).nutzdaten()).sessionId();
        byte[] fremdesAck = helloAckMitSession("alte-session");
        assertNull(client.sequenzGeprueft(Protokoll.rahmeMitSequenz(fremdesAck, 1L)),
                "Eine fremde oder alte ACK-Session darf den Replay-Schutz nicht zurücksetzen");
        byte[] neuesAck = Protokoll.rahmeMitSequenz(helloAckMitSession(session), 1L);
        assertNotNull(client.sequenzGeprueft(neuesAck),
                "Die Antwort auf das aktuelle HELLO muss nach Proxy-Neustart wieder bei 1 beginnen dürfen");
        assertNotNull(client.sequenzGeprueft(Protokoll.rahmeMitSequenz(sync, 2L)));
        assertNull(client.sequenzGeprueft(neuesAck),
                "Dasselbe ACK darf den Empfangszähler nicht erneut zurücksetzen");
        assertNull(client.sequenzGeprueft(Protokoll.rahmeMitSequenz(sync, 2L)));
    }

    private static byte[] helloAckMitSession(String session) throws Exception {
        return Protokoll.nachricht(Protokoll.HELLO_ACK, out -> {
            out.writeInt(Protokoll.VERSION);
            out.writeUTF("lobby");
            out.writeUTF("4.0");
            out.writeUTF(session);
        });
    }

    @Test
    void sequenzWirdErstBeimVersandAufDemServerThreadVergeben() throws Exception {
        var plugin = mock(Plugin.class);
        var server = mock(org.bukkit.Server.class);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        var spieler = mock(org.bukkit.entity.Player.class);
        var serverThread = Thread.currentThread();
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenAnswer(inv -> Thread.currentThread() == serverThread);
        doReturn(List.of(spieler)).when(server).getOnlinePlayers();
        var geplant = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            geplant.add(inv.getArgument(1));
            return mock(org.bukkit.scheduler.BukkitTask.class);
        });
        var versand = new java.util.ArrayList<byte[]>();
        org.mockito.Mockito.doAnswer(inv -> {
            versand.add(inv.getArgument(2));
            return null;
        }).when(spieler).sendPluginMessage(eq(plugin), eq(Protokoll.KANAL), any(byte[].class));
        var client = new KanalClient(plugin, new MuteCache());
        client.setTunnelSecret("punish-test-secret-lange");
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var async = pool.submit(() -> client.sendeBefehl(UUID.randomUUID(), "mute Ziel 10m Spam"));
            var aufgabe = geplant.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(aufgabe);
            client.sendeBefehl(UUID.randomUUID(), "warn Ziel Spam");
            aufgabe.run();
            async.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        var schluessel = TunnelSignatur.schluesselAus("punish-test-secret-lange", TunnelSignatur.Richtung.BRIDGE_ZU_PROXY);
        assertEquals(2, versand.size());
        long erste = Protokoll.entrahmeSequenz(TunnelSignatur.pruefenUndAbziehen(versand.get(0), schluessel)).sequenz();
        long zweite = Protokoll.entrahmeSequenz(TunnelSignatur.pruefenUndAbziehen(versand.get(1), schluessel)).sequenz();
        assertTrue(erste < zweite, "Server-Thread muss in steigender Sequenz senden");
    }
}
