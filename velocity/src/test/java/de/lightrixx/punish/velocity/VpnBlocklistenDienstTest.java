package de.lightrixx.punish.velocity;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.AutoBannService;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VpnBlocklistenDienstTest {

    private static final URI QUELLE = URI.create("https://raw.githubusercontent.com/test/vpn/main/ips.txt");
    private static final URI ZWEITE_QUELLE = URI.create("https://raw.githubusercontent.com/test/vpn/main/ipv6.txt");
    @TempDir Path ordner;
    private VelocityKonfiguration config;
    private ScheduledExecutorService pool;

    @BeforeEach
    void vorbereiten() {
        config = mock(VelocityKonfiguration.class);
        pool = mock(ScheduledExecutorService.class);
        when(config.vpnSchutzAktiv()).thenReturn(true);
        when(config.vpnSchutzIntervallMinuten()).thenReturn(360L);
        when(config.vpnSchutzQuellen()).thenReturn(List.of(QUELLE.toString()));
        when(config.vpnSchutzFreigaben()).thenReturn(List.of());
    }

    private VpnBlocklistenDienst dienst(VpnBlocklistenDienst.Download download) {
        return new VpnBlocklistenDienst(config, mock(Logger.class), ordner, download, pool);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void prueftIpv4Ipv6CidrGrenzenUeberlappungenUndGemappteAdressen() {
        try (var dienst = dienst(q -> bytes("\uFEFF# VPNs\r\n"
                + "198.51.100.128/25\r\n198.51.100.0/25 # überlappend\r\n"
                + "198.51.100.64/26\r\n203.0.113.9\r\n"
                + "2001:db8:abcd:12::99/64\r\n2001:db8:ffff::1/128\r\n"
                + "::ffff:192.0.2.0/120\r\n"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            for (String ip : List.of("198.51.100.0", "198.51.100.255", "203.0.113.9",
                    "2001:db8:abcd:12::", "2001:DB8:ABCD:12:ffff:ffff:ffff:ffff",
                    "2001:db8:ffff::1", "::ffff:198.51.100.15", "192.0.2.255")) {
                assertTrue(dienst.gesperrt(ip), ip);
            }
            for (String ip : List.of("198.51.99.255", "198.51.101.0", "203.0.113.8",
                    "203.0.113.10", "2001:db8:abcd:11:ffff:ffff:ffff:ffff",
                    "2001:db8:abcd:13::", "2001:db8:ffff::2", "192.0.3.0")) {
                assertFalse(dienst.gesperrt(ip), ip);
            }
            assertThrows(IllegalArgumentException.class, () -> dienst.gesperrt("example.com"));
        }
    }

    @Test
    void protonRoutingQuelleErfasstGemeldeteIpUndErlaubtGezielteAusnahmen() {
        when(config.vpnSchutzQuellen()).thenReturn(VpnBlocklistenDienst.STANDARD_QUELLEN);
        try (var dienst = dienst(q -> {
            if (q.toString().equals("https://raw.githubusercontent.com/tn3w/ProtonVPN-IPs/master/protonvpn_entry_ip_ranges.txt")) {
                return bytes("# Proton-bezogener Routing-Bereich\n190.2.128.0/19\n");
            }
            throw new IOException("Andere Quellen sind nicht Teil dieser Testdaten.");
        })) {
            dienst.neuLaden();
            dienst.aktualisieren();
            assertTrue(dienst.gesperrt("190.2.154.240"));
            assertTrue(dienst.gesperrt("::ffff:190.2.154.240"));
            assertFalse(dienst.gesperrt("190.2.127.255"));
            assertFalse(dienst.gesperrt("190.2.160.0"));
            assertFalse(dienst.gesperrt("203.0.113.7"));

            when(config.vpnSchutzFreigaben()).thenReturn(List.of("190.2.154.241"));
            dienst.neuLaden();
            assertFalse(dienst.gesperrt("190.2.154.241"));
            assertTrue(dienst.gesperrt("190.2.154.240"));
        }
    }

    @Test
    void ohneGueltigeListenBleibenLoginsErlaubtUndPruefungLaedtNichts() {
        var aufrufe = new AtomicInteger();
        try (var dienst = dienst(q -> {
            aufrufe.incrementAndGet();
            throw new IOException("Quelle nicht erreichbar");
        })) {
            dienst.neuLaden();
            assertFalse(dienst.gesperrt("198.51.100.7"));
            assertEquals(0, aufrufe.get());
            dienst.aktualisieren();
            for (int i = 0; i < 100; i++) {
                assertFalse(dienst.gesperrt("198.51.100.7"));
            }
            assertEquals(1, aufrufe.get());
        }
    }

    @Test
    void cacheWirdBeimNeustartOhneNetzwerkGeladen() {
        try (var dienst = dienst(q -> bytes("198.51.100.0/24\n"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            assertTrue(Files.isRegularFile(dienst.cacheDatei(QUELLE)));
        }
        var aufrufe = new AtomicInteger();
        try (var dienst = dienst(q -> {
            aufrufe.incrementAndGet();
            throw new IOException("Offline");
        })) {
            dienst.neuLaden();
            assertTrue(dienst.gesperrt("198.51.100.7"));
            assertEquals(0, aufrufe.get());
            dienst.aktualisieren();
            assertTrue(dienst.gesperrt("198.51.100.7"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "# keine Einträge\n", "<html>Fehler</html>",
            "203.0.113.0/24\ninvalid", "0.0.0.0/0", "::/0", "198.51.100.0/33",
            "2001:db8::/129", "::ffff:192.0.2.0/64", "198.51.100.1/-1",
            "198.51.100.1/", "198.51.100.0/24/1", "999.0.0.1", "127.1", "010.0.0.1",
            "example.com", "2001:db8:::1", "fe80::1%eth0"})
    void fehlerhafteDownloadsErsetzenWederRamNochCache(String ungueltig) throws Exception {
        var daten = new AtomicReference<>(bytes("198.51.100.0/24\n"));
        try (var dienst = dienst(q -> daten.get())) {
            dienst.neuLaden();
            dienst.aktualisieren();
            byte[] vorher = Files.readAllBytes(dienst.cacheDatei(QUELLE));
            daten.set(bytes(ungueltig));
            dienst.aktualisieren();
            assertTrue(dienst.gesperrt("198.51.100.7"));
            assertFalse(dienst.gesperrt("203.0.113.7"));
            assertArrayEquals(vorher, Files.readAllBytes(dienst.cacheDatei(QUELLE)));
        }
    }

    @Test
    void zuGrosserDownloadUndBeschaedigterCacheWerdenVerworfen() throws Exception {
        var daten = new AtomicReference<>(bytes("198.51.100.0/24"));
        try (var dienst = dienst(q -> daten.get())) {
            Files.createDirectories(dienst.cacheDatei(QUELLE).getParent());
            Files.writeString(dienst.cacheDatei(QUELLE), "<html>Fehler</html>");
            dienst.neuLaden();
            assertFalse(dienst.gesperrt("198.51.100.7"));
            dienst.aktualisieren();
            daten.set(new byte[VpnBlocklistenDienst.MAX_BYTES + 1]);
            dienst.aktualisieren();
            assertTrue(dienst.gesperrt("198.51.100.7"));
        }
    }

    @Test
    void quellenWerdenUnabhaengigAktualisiertUndEntfernteQuellenEntfallen() {
        when(config.vpnSchutzQuellen()).thenReturn(List.of(QUELLE.toString(), ZWEITE_QUELLE.toString()));
        var daten = new HashMap<URI, byte[]>();
        daten.put(QUELLE, bytes("198.51.100.0/24"));
        try (var dienst = dienst(q -> {
            if (!daten.containsKey(q)) {
                throw new IOException("Offline");
            }
            return daten.get(q);
        })) {
            dienst.neuLaden();
            dienst.aktualisieren();
            assertTrue(dienst.gesperrt("198.51.100.7"));
            daten.remove(QUELLE);
            daten.put(ZWEITE_QUELLE, bytes("2001:db8::/32"));
            dienst.aktualisieren();
            assertTrue(dienst.gesperrt("198.51.100.7"));
            assertTrue(dienst.gesperrt("2001:db8:abcd::1"));
            when(config.vpnSchutzQuellen()).thenReturn(List.of(ZWEITE_QUELLE.toString()));
            dienst.neuLaden();
            assertFalse(dienst.gesperrt("198.51.100.7"));
            assertTrue(dienst.gesperrt("2001:db8:abcd::1"));
        }
    }

    @Test
    void erfolgreicheAktualisierungEntferntVeralteteEintraege() {
        var daten = new AtomicReference<>(bytes("198.51.100.0/24"));
        try (var dienst = dienst(q -> daten.get())) {
            dienst.neuLaden();
            dienst.aktualisieren();
            daten.set(bytes("203.0.113.7"));
            dienst.aktualisieren();
            assertFalse(dienst.gesperrt("198.51.100.7"));
            assertTrue(dienst.gesperrt("203.0.113.7"));
        }
    }

    @Test
    void freigabenGreifenSofortNachReloadUndFilterIstAbschaltbar() {
        try (var dienst = dienst(q -> bytes("198.51.100.0/24\n2001:db8::/32"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            when(config.vpnSchutzFreigaben()).thenReturn(List.of("198.51.100.7", "2001:db8:abcd::/48"));
            dienst.neuLaden();
            assertFalse(dienst.gesperrt("198.51.100.7"));
            assertTrue(dienst.gesperrt("198.51.100.8"));
            assertFalse(dienst.gesperrt("2001:db8:abcd::1"));
            assertTrue(dienst.gesperrt("2001:db8:abce::1"));
            when(config.vpnSchutzFreigaben()).thenReturn(List.of("invalid"));
            assertThrows(IllegalArgumentException.class, dienst::neuLaden);
            assertTrue(dienst.gesperrt("198.51.100.8"));
            when(config.vpnSchutzAktiv()).thenReturn(false);
            dienst.neuLaden();
            assertFalse(dienst.gesperrt("198.51.100.8"));
        }
    }

    @Test
    void laufenderDownloadKannReloadOderShutdownNichtUeberschreiben() {
        var referenz = new AtomicReference<VpnBlocklistenDienst>();
        try (var dienst = dienst(q -> {
            when(config.vpnSchutzAktiv()).thenReturn(false);
            referenz.get().neuLaden();
            return bytes("198.51.100.0/24");
        })) {
            referenz.set(dienst);
            dienst.neuLaden();
            dienst.aktualisieren();
            assertFalse(dienst.gesperrt("198.51.100.7"));
            assertFalse(Files.exists(dienst.cacheDatei(QUELLE)));
        }
        verify(pool).shutdownNow();
    }

    @Test
    void hintergrundWartungBeachtetDasAktualisierungsintervall() {
        var aufrufe = new AtomicInteger();
        try (var dienst = dienst(q -> {
            aufrufe.incrementAndGet();
            return bytes("198.51.100.0/24");
        })) {
            dienst.neuLaden();
            var auftrag = ArgumentCaptor.forClass(Runnable.class);
            verify(pool).scheduleWithFixedDelay(auftrag.capture(), eq(0L), eq(1L), eq(TimeUnit.MINUTES));
            assertEquals(0, aufrufe.get());
            auftrag.getValue().run();
            auftrag.getValue().run();
            assertEquals(1, aufrufe.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://raw.githubusercontent.com/test/vpn/main/ips.txt",
            "https://example.com/ips.txt", "https://github.com/test/vpn/blob/main/ips.txt",
            "https://user@raw.githubusercontent.com/test/vpn/main/ips.txt",
            "https://raw.githubusercontent.com:444/test/vpn/main/ips.txt",
            "https://raw.githubusercontent.com/test/vpn/main/ips.txt?format=text"})
    void nurDirekteGitHubHttpsQuellenWerdenAkzeptiert(String url) {
        assertThrows(IllegalArgumentException.class, () -> VpnBlocklistenDienst.pruefeQuelle(url));
    }

    @Test
    void bestehendeKonfigurationBekommtDefaultsUndLeereQuellenBleibenLeer() throws Exception {
        var echt = new VelocityKonfiguration(mock(ProxyServer.class), ordner.toFile());
        echt.sicherstellen();
        Files.writeString(ordner.resolve("config/general.yml"), "prefix: '<red>Punish'\n");
        echt.laden();
        assertTrue(echt.vpnSchutzAktiv());
        assertEquals(360L, echt.vpnSchutzIntervallMinuten());
        assertEquals(VpnBlocklistenDienst.STANDARD_QUELLEN, echt.vpnSchutzQuellen());
        assertTrue(echt.nachricht(MessageKeys.VPN_GESPERRT).contains("VPN"));
        Files.writeString(ordner.resolve("config/general.yml"),
                "vpn-schutz:\n  aktiv: false\n  quellen: []\n  intervall-minuten: 1\n");
        echt.laden();
        assertFalse(echt.vpnSchutzAktiv());
        assertEquals(5L, echt.vpnSchutzIntervallMinuten());
        assertEquals(List.of(), echt.vpnSchutzQuellen());
    }

    @Test
    void loginWirdBeiVpnTrefferAbgelehntUndFreigabeUmgehtKeinenIpBann() throws Exception {
        var ipBanns = mock(IpBanService.class);
        var nachrichten = mock(MessageService.class);
        when(nachrichten.komponenteOhnePraefix(eq(MessageKeys.VPN_GESPERRT), anyMap()))
                .thenReturn(Component.text("VPN gesperrt"));
        var cache = mock(PlayerCache.class);
        var listener = new StrafListener(mock(ProxyServer.class), mock(Logger.class), cache,
                mock(BanService.class), ipBanns, mock(TimeoutService.class), mock(MuteService.class),
                mock(AutoBannService.class), config, nachrichten, mock(AuftragsPlaner.class),
                mock(BanRepository.class), mock(BrueckenKanal.class));
        var spieler = mock(Player.class);
        when(spieler.getUniqueId()).thenReturn(UUID.randomUUID());
        when(spieler.getUsername()).thenReturn("Steve");
        when(spieler.getRemoteAddress()).thenReturn(
                new InetSocketAddress(InetAddress.getByName("198.51.100.7"), 25565));
        try (var dienst = dienst(q -> bytes("198.51.100.0/24"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            listener.setVpnBlocklistenDienst(dienst);
            var gesperrt = new LoginEvent(spieler);
            listener.onLogin(gesperrt);
            assertFalse(gesperrt.getResult().isAllowed());
            verify(cache, never()).aktualisieren(any());

            when(config.vpnSchutzFreigaben()).thenReturn(List.of("198.51.100.7"));
            dienst.neuLaden();
            var erlaubt = new LoginEvent(spieler);
            listener.onLogin(erlaubt);
            assertTrue(erlaubt.getResult().isAllowed());

            when(ipBanns.ipFindeAbdeckend("198.51.100.7", ServerScope.global()))
                    .thenReturn(Optional.of(new IpBanRecord("198.51.100.7", "Test", "Mod",
                            1L, Long.MAX_VALUE, ServerScope.global())));
            var ipBann = new LoginEvent(spieler);
            listener.onLogin(ipBann);
            assertFalse(ipBann.getResult().isAllowed());
        }
    }
}
