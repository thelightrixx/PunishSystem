package de.lightrixx.punish.audit;

import de.lightrixx.punish.command.DeleteIpCommand;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpMuteRecord;
import de.lightrixx.punish.model.IpTimeoutRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditFixV6RegressionTest {

    static final class FakeSpeicher implements YamlSpeicher {
        @Override
        public Map<String, Object> laden(File datei) {
            return Map.of();
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
        }
    }

    private IoExecutor io;

    @AfterEach
    void shutdown() {
        if (io != null) {
            try {
                io.herunterfahren(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            io = null;
        }
    }

    private BefehlsSender senderMit(String... rechte) {
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenAnswer(inv -> {
            String knot = inv.getArgument(0);
            for (String r : rechte) {
                if (r.equals(knot)) {
                    return true;
                }
            }
            return false;
        });
        when(sender.istSpieler()).thenReturn(true);
        when(sender.name()).thenReturn("Mod");
        return sender;
    }

    private de.lightrixx.punish.command.PunishStatusCommand statusCmd() {
        var messageService = mock(de.lightrixx.punish.service.MessageService.class);
        var config = mock(de.lightrixx.punish.core.Konfiguration.class);
        var protection = mock(de.lightrixx.punish.service.ProtectionService.class);
        var cache = mock(PlayerCache.class);
        var verstossService = mock(de.lightrixx.punish.service.VerstossService.class);
        return new de.lightrixx.punish.command.PunishStatusCommand(
                messageService, config, protection, cache, verstossService,
                mock(de.lightrixx.punish.storage.BanRepository.class),
                mock(de.lightrixx.punish.storage.MuteRepository.class),
                mock(de.lightrixx.punish.storage.TimeoutRepository.class),
                mock(de.lightrixx.punish.storage.IpBanRepository.class),
                mock(de.lightrixx.punish.storage.VerstossRepository.class),
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
    }

    private de.lightrixx.punish.command.PunishHistoryCommand historyCmd() {
        var messageService = mock(de.lightrixx.punish.service.MessageService.class);
        var config = mock(de.lightrixx.punish.core.Konfiguration.class);
        var protection = mock(de.lightrixx.punish.service.ProtectionService.class);
        var cache = mock(PlayerCache.class);
        return new de.lightrixx.punish.command.PunishHistoryCommand(
                messageService, config, protection, cache,
                mock(de.lightrixx.punish.service.HistoryService.class),
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
    }

    @Test
    void statusServerBrauchtEigenePermission() {
        var cmd = statusCmd();
        BefehlsSender nurStatus = senderMit("punish.status");
        assertFalse(cmd.hatRecht(nurStatus, new String[]{"server"}),
                "server-Ansicht darf nicht mit punish.status allein gehen");
        BefehlsSender mitServer = senderMit("punish.status.server");
        assertTrue(cmd.hatRecht(mitServer, new String[]{"server"}));
        assertTrue(cmd.hatRecht(nurStatus, new String[]{"SpielerX"}),
                "Fremd-Ansicht bleibt mit punish.status möglich");
        assertFalse(cmd.hatRecht(null, new String[]{"server"}));
    }

    @Test
    void historyServerBrauchtEigenePermission() {
        var cmd = historyCmd();
        BefehlsSender nurHistory = senderMit("punish.history");
        assertFalse(cmd.hatRecht(nurHistory, new String[]{"server"}),
                "server-Verlauf darf nicht mit punish.history allein gehen");
        assertTrue(cmd.hatRecht(senderMit("punish.history.server"), new String[]{"server"}));
        assertTrue(cmd.hatRecht(nurHistory, new String[]{"SpielerX"}));
    }

    @Test
    void statusServerTabLeaktKeinServerOhneRecht() {
        var cmd = statusCmd();
        BefehlsSender nurStatus = senderMit("punish.status");
        List<String> vorschlaege = cmd.vervollstaendigen(nurStatus, new String[]{""});
        assertFalse(vorschlaege.contains("server"), "Tab darf server ohne Recht nicht vorschlagen");
        List<String> mitRecht = statusCmd().vervollstaendigen(
                senderMit("punish.status", "punish.status.server"), new String[]{""});
        assertTrue(mitRecht.contains("server"), "Tab muss server mit Recht vorschlagen");
    }

    @Test
    void deleteIpEntferntSperrenUndAnonymisiertCache(@TempDir File temp) {
        io = new IoExecutor();
        var speicher = new FakeSpeicher();
        var ipBanRepo = new IpBanRepository(temp, speicher, io);
        var ipMuteRepo = new IpMuteRepository(temp, speicher, io);
        var ipTimeoutRepo = new IpTimeoutRepository(temp, speicher, io);
        var cache = mock(PlayerCache.class);
        UUID uuid = UUID.randomUUID();
        String ip = "10.99.99.99";
        when(cache.findeNachIp(ip)).thenReturn(List.of(
                new CachedPlayer(uuid, "Opfer", ip, System.currentTimeMillis())));
        long jetzt = System.currentTimeMillis();
        assertTrue(ipBanRepo.bannenWennFrei(new IpBanRecord(ip, "Evasion", "Mod", jetzt, jetzt + 3_600_000L)));
        assertTrue(ipMuteRepo.mutenWennFrei(new IpMuteRecord(ip, "Evasion", "Mod",
                jetzt, jetzt + 3_600_000L)));
        assertTrue(ipTimeoutRepo.timeoutVerhaengenWennFrei(new IpTimeoutRecord(ip, "Evasion",
                "Mod", jetzt, jetzt + 3_600_000L)));
        var messageService = mock(de.lightrixx.punish.service.MessageService.class);
        var config = mock(de.lightrixx.punish.core.Konfiguration.class);
        var protection = mock(de.lightrixx.punish.service.ProtectionService.class);
        var verzeichnis = mock(de.lightrixx.punish.core.SpielerVerzeichnis.class);
        var cmd = new DeleteIpCommand(messageService, config, protection, cache,
                ipBanRepo, ipMuteRepo, ipTimeoutRepo, verzeichnis);
        BefehlsSender sender = senderMit("punish.deleteip");
        cmd.ausfuehren(sender, new String[]{ip});
        assertFalse(ipBanRepo.alleFuer(ip).isEmpty(),
                "Ohne confirm darf /punish-delete-ip nichts löschen");
        cmd.ausfuehren(sender, new String[]{ip, "confirm"});
        assertTrue(ipBanRepo.alleFuer(ip).isEmpty(), "IP-Banns müssen gelöscht sein");
        assertTrue(ipMuteRepo.alleFuer(ip).isEmpty(), "IP-Mutes müssen gelöscht sein");
        assertTrue(ipTimeoutRepo.alleFuer(ip).isEmpty(), "IP-Timeouts müssen gelöscht sein");
        verify(cache).aktualisieren(argThat(c -> c != null && c.uuid().equals(uuid)
                && "unbekannt".equals(c.letzteIp())));
        verify(messageService).senden(eq(sender), eq(de.lightrixx.punish.core.MessageKeys.DELETE_IP_ERFOLG), anyMap());
    }

    @Test
    void deleteIpSyntaxOhneEingabe() {
        var messageService = mock(de.lightrixx.punish.service.MessageService.class);
        var cmd = new DeleteIpCommand(messageService,
                mock(de.lightrixx.punish.core.Konfiguration.class),
                mock(de.lightrixx.punish.service.ProtectionService.class),
                mock(PlayerCache.class),
                mock(IpBanRepository.class), mock(IpMuteRepository.class),
                mock(IpTimeoutRepository.class),
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
        BefehlsSender sender = senderMit("punish.deleteip");
        cmd.ausfuehren(sender, new String[0]);
        verify(messageService).senden(eq(sender), eq(de.lightrixx.punish.core.MessageKeys.DELETE_IP_SYNTAX));
    }

    @Test
    void banEintragRoundtripMitPerma() throws Exception {
        UUID uuid = UUID.randomUUID();
        var perma = new Protokoll.BanEintrag(uuid, "Steve", "Cheating", "Mod", 1000L, Long.MAX_VALUE, "global");
        assertTrue(perma.istPermanent());
        assertTrue(perma.istAktiv(9999999999999L));
        byte[] paket = Protokoll.banSync(List.of(perma));
        var gelesen = Protokoll.leseBanSync(Protokoll.paket(paket).nutzdaten());
        assertEquals(1, gelesen.size());
        assertEquals(perma, gelesen.get(0));
        var temp = new Protokoll.BanEintrag(uuid, "Steve", "Spam", "Mod", 1000L, 2000L, "lobby");
        assertFalse(temp.istPermanent());
        assertTrue(temp.istAktiv(1500L));
        assertFalse(temp.istAktiv(2500L));
    }

    @Test
    void timeoutEintragRoundtrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        var eintrag = new Protokoll.TimeoutEintrag(uuid, "Steve", "Cool-down", "Mod", 1000L, 2000L, "global");
        assertTrue(eintrag.istAktiv(1500L));
        assertFalse(eintrag.istAktiv(2500L));
        byte[] paket = Protokoll.timeoutSync(List.of(eintrag));
        var gelesen = Protokoll.leseTimeoutSync(Protokoll.paket(paket).nutzdaten());
        assertEquals(1, gelesen.size());
        assertEquals(eintrag, gelesen.get(0));
    }

    @Test
    void banRecordScopeFilter() {
        long jetzt = System.currentTimeMillis();
        BanRecord global = new BanRecord(UUID.randomUUID(), "A", "G", "M", jetzt, jetzt + 60000L,
                false, ServerScope.global());
        assertTrue(global.scope().decktAb(ServerScope.server("lobby")));
    }
}
