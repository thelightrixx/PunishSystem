package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class TextVerteilungTest {

    @TempDir
    File datenOrdner;

    private ProxyServer proxy;
    private List<byte[]> gesendet;
    private TextVerteilung verteilung;

    @BeforeEach
    void aufsetzen() throws Exception {
        proxy = Mockito.mock(ProxyServer.class);
        gesendet = new java.util.concurrent.CopyOnWriteArrayList<>();
        File config = new File(datenOrdner, "config");
        config.mkdirs();
        Files.writeString(new File(config, "general.yml").toPath(),
                "prefix: \"x\"\nnetzwerk:\n  server-name: \"lobby\"\n  server-liste: []\n"
                        + "  tunnel-secret: \"NetzwerkGeheimnis123!\"\n"
                        + "  bridge-secrets:\n    lobby: \"LobbyGeheimnis123!\"\n"
                        + "speicher:\n  typ: \"mysql\"\n  mysql:\n    passwort: \"geheim\"\n",
                StandardCharsets.UTF_8);
        for (String name : List.of("messages.yml", "screens.yml", "broadcasts.yml")) {
            Files.writeString(new File(config, name).toPath(), "a: b\n", StandardCharsets.UTF_8);
        }
        File chatfilter = new File(datenOrdner, "chatfilter");
        chatfilter.mkdirs();
        Files.writeString(new File(chatfilter, "wortliste.txt").toPath(), "# kopf\nwortA\n",
                StandardCharsets.UTF_8);
        verteilung = new TextVerteilung(proxy, LoggerFactory.getLogger("test"),
                datenOrdner, (ziel, paket) -> gesendet.add(paket));
    }

    @Test
    void laden_bereinigtGeheimnisse() throws Exception {
        verteilung.laden();
        assertTrue(verteilung.aktiv());
        String hash1 = verteilung.paketHash();
        verteilung.laden();
        assertEquals(hash1, verteilung.paketHash());

        RegisteredServer server = Mockito.mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(
                new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25566)));
        verteilung.standEmpfangen(server, "");
        long ablauf = System.currentTimeMillis() + 5000L;
        while (gesendet.isEmpty() && System.currentTimeMillis() < ablauf) {
            Thread.sleep(25L);
        }
        assertFalse(gesendet.isEmpty(), "Text-Sync muss nach abweichendem Stand senden");
        StringBuilder general = new StringBuilder();
        for (byte[] paket : gesendet) {
            var rahmen = de.lightrixx.punish.net.Protokoll.paket(paket);
            if (rahmen.typ() != de.lightrixx.punish.net.Protokoll.TEXT_BLOCK) {
                continue;
            }
            var block = de.lightrixx.punish.net.Protokoll.leseTextBlock(rahmen.nutzdaten());
            if ("general.yml".equals(block.dateiname())) {
                general.append(new String(block.daten(), StandardCharsets.UTF_8));
            }
        }
        assertFalse(general.isEmpty(), "general.yml muss im Text-Sync enthalten sein");
        String inhalt = general.toString();
        assertFalse(inhalt.contains("NetzwerkGeheimnis123!"),
                "tunnel-secret darf nicht an Bridges verteilt werden: " + inhalt);
        assertFalse(inhalt.contains("LobbyGeheimnis123!"),
                "bridge-secrets dürfen nicht an Bridges verteilt werden: " + inhalt);
        assertFalse(inhalt.contains("tunnel-secret"), "Schlüssel darf nicht im Paket stehen");
        assertFalse(inhalt.contains("bridge-secrets"), "Schlüssel darf nicht im Paket stehen");
    }

    @Test
    void bereinigeGeneral_entferntSpeicherUndServername() {
        String roh = "prefix: \"x\"\nnetzwerk:\n  server-name: \"lobby\"\n  server-liste: []\n"
                + "speicher:\n  typ: \"mysql\"\n  mysql:\n    passwort: \"geheim\"\n";
        String bereinigt = new String(TextVerteilung.bereinigeGeneral(
                roh.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertFalse(bereinigt.contains("geheim"));
        assertFalse(bereinigt.contains("speicher"));
        assertFalse(bereinigt.contains("lobby"));
        assertTrue(bereinigt.contains("server-liste"));
        assertTrue(bereinigt.contains("prefix"));
    }

    @Test
    void bereinigeGeneral_entferntTunnelSecretUndBridgeSecrets() {
        String roh = "prefix: \"x\"\nnetzwerk:\n  server-name: \"lobby\"\n"
                + "  tunnel-secret: \"NetzwerkGeheimnis123!\"\n"
                + "  bridge-secrets:\n    lobby: \"LobbyGeheimnis123!\"\n"
                + "    survival: \"SurvivalGeheimnis123!\"\n  server-liste: []\n";
        String bereinigt = new String(TextVerteilung.bereinigeGeneral(
                roh.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertFalse(bereinigt.contains("NetzwerkGeheimnis123!"));
        assertFalse(bereinigt.contains("LobbyGeheimnis123!"));
        assertFalse(bereinigt.contains("SurvivalGeheimnis123!"));
        assertFalse(bereinigt.contains("tunnel-secret"));
        assertFalse(bereinigt.contains("bridge-secrets"));
        assertTrue(bereinigt.contains("server-liste"));
    }

    @Test
    void wortHinzufuegen_verteiltAnAlle() throws Exception {
        when(proxy.getAllServers()).thenReturn(List.of());
        verteilung.laden();
        String vorher = verteilung.paketHash();
        assertTrue(verteilung.wortHinzufuegen("NeuWort"));
        assertNotEquals(vorher, verteilung.paketHash());
        String inhalt = Files.readString(
                new File(new File(datenOrdner, "chatfilter"), "wortliste.txt").toPath(),
                StandardCharsets.UTF_8);
        assertTrue(inhalt.contains("neuwort") || inhalt.contains("NeuWort"));
        assertFalse(verteilung.wortHinzufuegen("  "));
        assertFalse(verteilung.wortHinzufuegen("a"));
    }

    @Test
    void wortDoppelt_wirdNichtDupliziert() throws Exception {
        when(proxy.getAllServers()).thenReturn(List.of());
        verteilung.laden();
        assertTrue(verteilung.wortHinzufuegen("WortA"));
        String inhalt = Files.readString(
                new File(new File(datenOrdner, "chatfilter"), "wortliste.txt").toPath(),
                StandardCharsets.UTF_8);
        long anzahl = inhalt.lines()
                .filter(linie -> linie.trim().equalsIgnoreCase("wortA"))
                .count();
        assertEquals(1L, anzahl);
    }

    @Test
    void laden_seededGuiYmlUndBleibtAktiv() throws Exception {
        File gui = new File(new File(datenOrdner, "config"), "gui.yml");
        assertFalse(gui.isFile());
        verteilung.laden();
        assertTrue(verteilung.aktiv());
        assertTrue(gui.isFile(), "gui.yml muss aus dem Jar-Default angelegt werden");
        String inhalt = Files.readString(gui.toPath(), StandardCharsets.UTF_8);
        assertTrue(inhalt.contains("titel"), "gui.yml muss einen Titel enthalten");
    }

    @Test
    void standGleich_keinVersand() throws Exception {
        RegisteredServer server = Mockito.mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(
                new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25566)));
        verteilung.laden();
        verteilung.standEmpfangen(server, verteilung.paketHash());
        Thread.sleep(300L);
        assertTrue(gesendet.isEmpty());
    }

    @Test
    void standAbweichend_sendetPaket() throws Exception {
        RegisteredServer server = Mockito.mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(
                new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25566)));
        verteilung.laden();
        verteilung.standEmpfangen(server, "");
        long ablauf = System.currentTimeMillis() + 5000L;
        while (gesendet.isEmpty() && System.currentTimeMillis() < ablauf) {
            Thread.sleep(50L);
        }
        assertFalse(gesendet.isEmpty());
    }
}
