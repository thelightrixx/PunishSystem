package de.lightrixx.punish.velocity;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.service.AutoBannService;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static de.lightrixx.punish.velocity.VpnBlocklistenDienst.Aktion.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VpnErweiterungenTest {

    private static final URI ROUTING = URI.create(VpnBlocklistenDienst.PROTON_ROUTING);
    private static final URI VPN = URI.create("https://raw.githubusercontent.com/test/vpn/main/ips.txt");
    @TempDir Path ordner;
    private VelocityKonfiguration config;
    private final AtomicLong zeit = new AtomicLong(1_000_000L);

    @BeforeEach
    void vorbereiten() {
        config = mock(VelocityKonfiguration.class);
        when(config.vpnSchutzAktiv()).thenReturn(true);
        when(config.vpnSchutzModus()).thenReturn("streng");
        when(config.vpnSchutzQuellen()).thenReturn(List.of(ROUTING.toString()));
        when(config.vpnSchutzIntervallMinuten()).thenReturn(360L);
        when(config.vpnSchutzMeldungsCooldownSekunden()).thenReturn(60L);
        when(config.vpnSchutzTeamMeldungen()).thenReturn(true);
    }

    private VpnBlocklistenDienst dienst(VpnBlocklistenDienst.Download download) {
        return new VpnBlocklistenDienst(config, mock(Logger.class), ordner, download,
                mock(ScheduledExecutorService.class), zeit::get);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void strengerStandardUndAusgewogenUnterscheidenRoutingVonVpn() {
        try (var dienst = dienst(q -> q.equals(ROUTING) ? bytes("190.2.128.0/19") : bytes("190.2.154.240"))) {
            dienst.neuLaden();
            assertEquals(OHNE_LISTEN, dienst.pruefe("190.2.154.240").aktion());
            dienst.aktualisieren();
            assertEquals(SPERREN, dienst.pruefe("190.2.154.240").aktion());
            when(config.vpnSchutzModus()).thenReturn("ausgewogen");
            dienst.neuLaden();
            var routing = dienst.pruefe("::ffff:190.2.154.240");
            assertEquals(MELDEN, routing.aktion());
            assertEquals("190.2.128.0/19", routing.treffer().getFirst().cidr());
            assertEquals(VpnBlocklistenDienst.Art.ROUTING, routing.treffer().getFirst().quelle().art());
            when(config.vpnSchutzQuellen()).thenReturn(List.of(ROUTING.toString(), VPN.toString()));
            dienst.neuLaden();
            dienst.aktualisieren();
            var exakt = dienst.pruefe("190.2.154.240");
            assertEquals(SPERREN, exakt.aktion());
            assertEquals(2, exakt.treffer().size());
            assertEquals(MELDEN, dienst.pruefe("190.2.154.241").aktion());
            assertEquals(FREI, dienst.pruefe("190.2.160.0").aktion());
            when(config.vpnSchutzFreigaben()).thenReturn(List.of("190.2.154.240"));
            dienst.neuLaden();
            var frei = dienst.pruefe("190.2.154.240");
            assertEquals(FREIGEGEBEN, frei.aktion());
            assertEquals("190.2.154.240/32", frei.freigabe().orElseThrow());
            assertEquals(2, frei.treffer().size());
        }
    }

    @Test
    void eigeneRoutingQuellenSindKonfigurierbarUndUngueltigerModusErsetztKeinenStand() {
        when(config.vpnSchutzQuellen()).thenReturn(List.of(VPN.toString()));
        when(config.vpnSchutzModus()).thenReturn("ausgewogen");
        try (var dienst = dienst(q -> bytes("198.51.100.0/24"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            assertTrue(dienst.gesperrt("198.51.100.7"));
            when(config.vpnSchutzRoutingQuellen()).thenReturn(List.of(VPN.toString()));
            dienst.neuLaden();
            assertEquals(MELDEN, dienst.pruefe("198.51.100.7").aktion());
            when(config.vpnSchutzModus()).thenReturn("unbekannt");
            assertThrows(IllegalArgumentException.class, dienst::neuLaden);
            assertEquals(MELDEN, dienst.pruefe("198.51.100.7").aktion());
        }
    }

    @Test
    void zeigtOriginaleEngsteCidrStattVerschmolzenemSuchbereich() {
        try (var dienst = dienst(q -> bytes("198.51.100.0/25\n198.51.100.128/25\n"
                + "198.51.100.64/26\n2001:db8::/32\n2001:db8:abcd::/48"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            assertEquals("198.51.100.64/26", dienst.pruefe("198.51.100.70").treffer().getFirst().cidr());
            assertEquals("198.51.100.128/25", dienst.pruefe("198.51.100.200").treffer().getFirst().cidr());
            assertTrue(dienst.pruefe("2001:db8:abcd::7").treffer().getFirst().cidr().endsWith("/48"));
            assertEquals(FREI, dienst.pruefe("2001:db9::7").aktion());
        }
    }

    @Test
    void downloadzeitIstKeineInhaltsaenderungUndZeitenBleibenNachNeustartErhalten() {
        var daten = new AtomicReference<>(bytes("190.2.128.0/19"));
        try (var dienst = dienst(q -> daten.get())) {
            dienst.neuLaden();
            dienst.aktualisieren();
            var anfang = dienst.status().quellen().getFirst();
            assertEquals(zeit.get(), anfang.letzterDownload());
            assertEquals(zeit.get(), anfang.letzteAenderung());
            zeit.addAndGet(60_000L);
            dienst.aktualisieren();
            var gleich = dienst.status().quellen().getFirst();
            assertEquals(zeit.get(), gleich.letzterDownload());
            assertEquals(anfang.letzteAenderung(), gleich.letzteAenderung());
            zeit.addAndGet(60_000L);
            daten.set(bytes("190.2.128.0/19\n2001:db8::/32"));
            dienst.aktualisieren();
            assertEquals(zeit.get(), dienst.status().quellen().getFirst().letzteAenderung());
        }
        try (var neu = dienst(q -> { throw new IOException("Offline"); })) {
            neu.neuLaden();
            var vorher = neu.status().quellen().getFirst();
            assertEquals(zeit.get(), vorher.letzterDownload());
            assertEquals(zeit.get(), vorher.letzteAenderung());
            zeit.addAndGet(60_000L);
            neu.aktualisieren();
            var fehler = neu.status().quellen().getFirst();
            assertEquals(vorher.letzterDownload(), fehler.letzterDownload());
            assertEquals(vorher.letzteAenderung(), fehler.letzteAenderung());
            assertEquals(zeit.get(), fehler.fehlerSeit());
            assertEquals("Offline", fehler.fehler());
            assertTrue(neu.gesperrt("190.2.154.240"));
        }
    }

    @Test
    void alterCacheOhneMetadatenErfindetKeinAktualisierungsdatum() throws Exception {
        try (var dienst = dienst(q -> bytes("190.2.128.0/19"))) {
            Files.createDirectories(dienst.cacheDatei(ROUTING).getParent());
            Files.writeString(dienst.cacheDatei(ROUTING), "190.2.128.0/19");
            dienst.neuLaden();
            assertEquals(0L, dienst.status().quellen().getFirst().letzterDownload());
            assertEquals(0L, dienst.status().quellen().getFirst().letzteAenderung());
            assertTrue(dienst.gesperrt("190.2.154.240"));
            dienst.aktualisieren();
            assertEquals(zeit.get(), dienst.status().quellen().getFirst().letzterDownload());
            assertEquals(0L, dienst.status().quellen().getFirst().letzteAenderung());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kaputt", "falscher-hash\n100\n100\n"})
    void beschaedigteMetadatenVerwerfenKeinenGueltigenInhalt(String meta) throws Exception {
        Path datei;
        try (var dienst = dienst(q -> bytes("190.2.128.0/19"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            datei = dienst.cacheDatei(ROUTING);
        }
        Files.writeString(datei.resolveSibling(datei.getFileName() + ".meta"), meta);
        try (var neu = dienst(q -> { throw new IOException("Offline"); })) {
            neu.neuLaden();
            assertTrue(neu.gesperrt("190.2.154.240"));
            assertEquals(0L, neu.status().quellen().getFirst().letzterDownload());
        }
    }

    @Test
    void befehlZeigtEntscheidungQuelleCidrUndFreigabeOhneNetzzugriff() {
        try (var dienst = dienst(q -> bytes("190.2.128.0/19"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            when(config.vpnSchutzFreigaben()).thenReturn(List.of("190.2.154.240"));
            dienst.neuLaden();
            var nachrichten = mock(MessageService.class);
            var sender = mock(BefehlsSender.class);
            when(sender.hatRecht("punish.vpn")).thenReturn(true);
            var befehl = new VelocityVpnBefehl(nachrichten, config, mock(ProtectionService.class),
                    mock(PlayerCache.class), mock(AuftragsPlaner.class), dienst);
            befehl.ausfuehren(sender, new String[]{"check", "190.2.154.240"});
            verify(nachrichten).senden(eq(sender), eq(MessageKeys.VPN_CHECK), argThat(m ->
                    m.get("freigabe").equals("190.2.154.240/32") && m.get("entscheidung").contains("Freigabe")));
            verify(nachrichten).senden(eq(sender), eq(MessageKeys.VPN_TREFFER), argThat(m ->
                    m.get("cidr").equals("190.2.128.0/19") && m.get("url").equals(ROUTING.toString())));
            befehl.ausfuehren(sender, new String[]{"check", "example.com"});
            verify(nachrichten).senden(sender, MessageKeys.VPN_IP_UNGUELTIG);
            befehl.ausfuehren(sender, new String[]{"status"});
            verify(nachrichten).senden(eq(sender), eq(MessageKeys.VPN_STATUS), argThat(m -> m.get("bereit").equals("1")));
            verify(nachrichten).senden(sender, MessageKeys.VPN_QUELLENHINWEIS);
            assertFalse(befehl.unterstuetztScope());
            assertEquals(List.of("check", "status"), befehl.vervollstaendigen(sender, new String[]{""}));
            when(sender.hatRecht("punish.vpn")).thenReturn(false);
            assertFalse(befehl.hatRecht(sender, new String[0]));
            assertTrue(befehl.vervollstaendigen(sender, new String[]{""}).isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"streng, true", "streng, false", "ausgewogen, true", "ausgewogen, false"})
    void jederGesperrteLoginWirdGemeldetUndNurRoutingHinweiseWerdenGedrosselt(String modus, boolean routing)
            throws Exception {
        when(config.vpnSchutzModus()).thenReturn(modus);
        when(config.vpnSchutzQuellen()).thenReturn(List.of((routing ? ROUTING : VPN).toString()));
        boolean gesperrt = "streng".equals(modus) || !routing;
        var proxy = mock(ProxyServer.class);
        var voll = mock(Player.class);
        when(voll.hasPermission("punish.message.vpn")).thenReturn(true);
        when(voll.hasPermission("punish.alts")).thenReturn(true);
        var maskiert = mock(Player.class);
        when(maskiert.hasPermission("punish.message.vpn")).thenReturn(true);
        var ohneRecht = mock(Player.class);
        when(proxy.getAllPlayers()).thenReturn(List.of(voll, maskiert, ohneRecht));
        var nachrichten = mock(MessageService.class);
        when(nachrichten.komponenteOhnePraefix(eq(MessageKeys.VPN_GESPERRT), anyMap())).thenReturn(Component.text("VPN"));
        var logger = mock(Logger.class);
        var listener = new StrafListener(proxy, logger, mock(PlayerCache.class), mock(BanService.class),
                mock(IpBanService.class), mock(TimeoutService.class), mock(MuteService.class),
                mock(AutoBannService.class), config, nachrichten, mock(AuftragsPlaner.class),
                mock(BanRepository.class), mock(BrueckenKanal.class));
        var spieler = mock(Player.class);
        var uuid = UUID.randomUUID();
        when(spieler.getUniqueId()).thenReturn(uuid);
        when(spieler.getUsername()).thenReturn("Spieler");
        when(spieler.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName("190.2.154.240"), 25565));
        try (var dienst = dienst(q -> bytes("190.2.128.0/19"))) {
            dienst.neuLaden();
            dienst.aktualisieren();
            listener.setVpnBlocklistenDienst(dienst);
            for (int i = 0; i < 3; i++) {
                var event = new LoginEvent(spieler);
                listener.onLogin(event);
                assertEquals(!gesperrt, event.getResult().isAllowed());
            }
            int meldungen = gesperrt ? 3 : 1;
            String key = gesperrt ? MessageKeys.VPN_TEAM_GESPERRT : MessageKeys.VPN_TEAM_HINWEIS;
            @SuppressWarnings("unchecked")
            var platzhalter = ArgumentCaptor.forClass(Map.class);
            verify(nachrichten, times(2 * meldungen)).senden(any(BefehlsSender.class), eq(key), platzhalter.capture());
            assertEquals("190.2.154.240", platzhalter.getAllValues().getFirst().get("ip"));
            assertTrue(platzhalter.getAllValues().getFirst().get("treffer").toString().contains("190.2.128.0/19"));
            assertEquals("190.2.154.*", platzhalter.getAllValues().getLast().get("ip"));
            assertFalse(platzhalter.getAllValues().getLast().get("treffer").toString().contains("190.2.128"));
            verify(logger, times(meldungen)).info(eq("VPN-Treffer aktion={} spieler={} uuid={} ip={} treffer={}"),
                    eq(gesperrt ? SPERREN : MELDEN), eq("Spieler"), eq(uuid), eq("190.2.154.240"), anyString());
            for (int i = 1; i <= 20; i++) {
                when(spieler.getRemoteAddress()).thenReturn(new InetSocketAddress(
                        InetAddress.getByName("190.2.153." + i), 25565));
                var event = new LoginEvent(spieler);
                listener.onLogin(event);
                assertEquals(!gesperrt, event.getResult().isAllowed());
            }
            meldungen = gesperrt ? 23 : 10;
            verify(logger, times(meldungen)).info(eq("VPN-Treffer aktion={} spieler={} uuid={} ip={} treffer={}"),
                    eq(gesperrt ? SPERREN : MELDEN), eq("Spieler"), eq(uuid), anyString(), anyString());
            verify(nachrichten, times(2 * meldungen)).senden(any(BefehlsSender.class), eq(key), anyMap());
            when(config.vpnSchutzTeamMeldungen()).thenReturn(false);
            when(spieler.getRemoteAddress()).thenReturn(
                    new InetSocketAddress(InetAddress.getByName("190.2.154.240"), 25565));
            var event = new LoginEvent(spieler);
            listener.onLogin(event);
            assertEquals(!gesperrt, event.getResult().isAllowed());
            verify(logger, times(gesperrt ? meldungen + 1 : meldungen))
                    .info(eq("VPN-Treffer aktion={} spieler={} uuid={} ip={} treffer={}"),
                            eq(gesperrt ? SPERREN : MELDEN), eq("Spieler"), eq(uuid), anyString(), anyString());
            verify(nachrichten, times(2 * meldungen)).senden(any(BefehlsSender.class), eq(key), anyMap());
        }
    }

    @Test
    void benutzerdefinierteQuellenBleibenBeimKonfigUpdateErhalten() throws Exception {
        var echt = new VelocityKonfiguration(mock(ProxyServer.class), ordner.toFile());
        echt.sicherstellen();
        Files.writeString(ordner.resolve("config/general.yml"), "vpn-schutz:\n  quellen: [\"" + VPN + "\"]\n");
        echt.laden();
        assertEquals("streng", echt.vpnSchutzModus());
        assertEquals(List.of(VPN.toString()), echt.vpnSchutzQuellen());
        assertTrue(echt.vpnSchutzTeamMeldungen());
        assertEquals(60L, echt.vpnSchutzMeldungsCooldownSekunden());
    }
}
