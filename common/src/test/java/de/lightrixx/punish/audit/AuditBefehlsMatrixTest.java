package de.lightrixx.punish.audit;

import de.lightrixx.punish.command.AltsCommand;
import de.lightrixx.punish.command.BanAllCommand;
import de.lightrixx.punish.command.BanCommand;
import de.lightrixx.punish.command.BanIpCommand;
import de.lightrixx.punish.command.DeleteIpCommand;
import de.lightrixx.punish.command.FreezeCommand;
import de.lightrixx.punish.command.KickAllCommand;
import de.lightrixx.punish.command.KickCommand;
import de.lightrixx.punish.command.KickIpCommand;
import de.lightrixx.punish.command.MuteAllCommand;
import de.lightrixx.punish.command.MuteCommand;
import de.lightrixx.punish.command.MuteIpCommand;
import de.lightrixx.punish.command.PardonAllCommand;
import de.lightrixx.punish.command.PardonCommand;
import de.lightrixx.punish.command.PermabannCommand;
import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.command.PunishDiagnosticsCommand;
import de.lightrixx.punish.command.PunishEditCommand;
import de.lightrixx.punish.command.PunishHelpCommand;
import de.lightrixx.punish.command.PunishHistoryCommand;
import de.lightrixx.punish.command.PunishNoteCommand;
import de.lightrixx.punish.command.PunishStatusCommand;
import de.lightrixx.punish.command.PunishVersionCommand;
import de.lightrixx.punish.command.ReportCommand;
import de.lightrixx.punish.command.ReportsCommand;
import de.lightrixx.punish.command.TimeoutAllCommand;
import de.lightrixx.punish.command.TimeoutCommand;
import de.lightrixx.punish.command.TimeoutIpCommand;
import de.lightrixx.punish.command.UnbanIpCommand;
import de.lightrixx.punish.command.UnfreezeCommand;
import de.lightrixx.punish.command.UnmuteAllCommand;
import de.lightrixx.punish.command.UnmuteCommand;
import de.lightrixx.punish.command.UnmuteIpCommand;
import de.lightrixx.punish.command.UntimeoutAllCommand;
import de.lightrixx.punish.command.UntimeoutCommand;
import de.lightrixx.punish.command.UntimeoutIpCommand;
import de.lightrixx.punish.command.VerstossCommand;
import de.lightrixx.punish.command.WarnCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.AutoBannService;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.FreezeService;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.IpMuteService;
import de.lightrixx.punish.service.IpTimeoutService;
import de.lightrixx.punish.service.KickService;
import de.lightrixx.punish.service.MassenErgebnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.NoteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.util.TimeParser;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditBefehlsMatrixTest {

    static final class Nachrichten {
        final List<String> gesendet = new ArrayList<>();
        final List<String> komponenten = new ArrayList<>();

        boolean gesendetAufgezeichnet(String schluessel) {
            return gesendet.contains(schluessel) || komponenten.contains(schluessel);
        }
    }

    private Nachrichten nachrichten;
    private MessageService messages;
    private Konfiguration config;
    private SpielerVerzeichnis verzeichnis;
    private ProtectionService schutz;
    private PlayerCache cache;
    private AuftragsPlaner planer;
    private TimeParser zeit;

    private BefehlsSender sender() {
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.istSpieler()).thenReturn(false);
        when(sender.name()).thenReturn("Konsole");
        return sender;
    }

    private void aufbauen() {
        nachrichten = new Nachrichten();
        messages = mock(MessageService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            nachrichten.gesendet.add(inv.getArgument(1));
            return null;
        }).when(messages).senden(any(BefehlsSender.class), anyString(), any(Map.class));
        org.mockito.Mockito.doAnswer(inv -> {
            nachrichten.gesendet.add(inv.getArgument(1));
            return null;
        }).when(messages).senden(any(BefehlsSender.class), anyString());
        when(messages.komponente(anyString(), any(Map.class))).thenAnswer(inv -> {
            nachrichten.komponenten.add(String.valueOf((Object) inv.getArgument(0)));
            return Component.text(String.valueOf((Object) inv.getArgument(0)));
        });
        when(messages.komponenteOhnePraefix(anyString(), any(Map.class))).thenAnswer(inv -> {
            nachrichten.komponenten.add(String.valueOf((Object) inv.getArgument(0)));
            return Component.text(String.valueOf((Object) inv.getArgument(0)));
        });
        config = mock(Konfiguration.class);
        when(config.standardScope()).thenReturn(ServerScope.global());
        when(config.bekannteServer()).thenReturn(List.of("lobby"));
        when(config.banMaxMinuten()).thenReturn(525600L);
        when(config.banMinMinuten()).thenReturn(1L);
        when(config.muteMaxMinuten()).thenReturn(43200L);
        when(config.timeoutMaxMinuten()).thenReturn(1440L);
        when(config.standardGrund(anyString())).thenReturn("Regelverstoß");
        when(config.serverName()).thenReturn("lobby");
        when(config.bestaetigungBanAll()).thenReturn(true);
        when(config.bestaetigungMuteAll()).thenReturn(true);
        when(config.bestaetigungTimeoutAll()).thenReturn(true);
        when(config.bestaetigungKickAll()).thenReturn(true);
        when(config.bestaetigungPardonAll()).thenReturn(true);
        when(config.bestaetigungUnmuteAll()).thenReturn(true);
        when(config.bestaetigungUntimeoutAll()).thenReturn(true);
        when(config.ipGeteiltWarnAb()).thenReturn(4);
        when(config.ipGeteiltBlockAb()).thenReturn(11);
        when(config.ipGeteiltBestaetigungAb()).thenReturn(2);
        verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of());
        when(verzeichnis.onlineSpielerNachName(anyString())).thenReturn(Optional.empty());
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        LuckPermsZugang luckPerms = mock(LuckPermsZugang.class);
        schutz = mock(ProtectionService.class);
        when(schutz.istSelbstbestrafung(any(BefehlsSender.class), any(UUID.class))).thenReturn(false);
        when(schutz.pruefeSchutzSofort(any(UUID.class))).thenReturn(
                Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT));
        when(schutz.pruefeSchutzSofort(any(UUID.class), any())).thenReturn(
                Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT));
        when(schutz.istOnlineGeschuetzt(any(OnlineSpieler.class))).thenReturn(false);
        when(schutz.istOnlineGeschuetzt(any(OnlineSpieler.class), any())).thenReturn(false);
        when(schutz.istFailClosed()).thenReturn(true);
        cache = mock(PlayerCache.class);
        when(cache.istFrischeNamensZuordnung(anyString())).thenReturn(true);
        when(cache.findeExternOffline(anyString())).thenReturn(Optional.empty());
        when(cache.mitKanonischerIp(anyString())).thenReturn(List.of());
        planer = mock(AuftragsPlaner.class);
        zeit = new TimeParser();
    }

    private void cacheZiel(UUID uuid, String name) {
        when(cache.findenNachName(name)).thenReturn(
                Optional.of(new CachedPlayer(uuid, name, "10.9.9.9", System.currentTimeMillis())));
    }

    private BanCommand banBefehl(BanService banService) {
        return new BanCommand(messages, config, schutz, cache, banService, zeit, planer, verzeichnis);
    }

    @Test
    void banAllOhneConfirmSendetNurAufforderung() {
        aufbauen();
        BanService banService = mock(BanService.class);
        BanAllCommand befehl = new BanAllCommand(messages, config, schutz, cache,
                banService, zeit, planer, verzeichnis);
        assertDoesNotThrow(() -> befehl.ausfuehren(sender(), new String[]{"1h", "Grund"}));
        verify(banService, never()).banAlle(anyLong(), anyBoolean(), anyString(), anyString(),
                any(), any(), anyString(), anyBoolean());
        assertTrue(nachrichten.komponenten.contains(MessageKeys.BESTAETIGUNG_NOETIG));
    }

    @Test
    void banAllMitConfirmFuehrtAus() {
        aufbauen();
        BanService banService = mock(BanService.class);
        when(banService.banAlle(anyLong(), anyBoolean(), anyString(), anyString(), any(),
                any(), anyString(), anyBoolean())).thenReturn(new MassenErgebnis(2, 0));
        BanAllCommand befehl = new BanAllCommand(messages, config, schutz, cache,
                banService, zeit, planer, verzeichnis);
        befehl.ausfuehren(sender(), new String[]{"1h", "confirm", "Grund"});
        verify(banService).banAlle(anyLong(), anyBoolean(), anyString(), anyString(), any(),
                any(), anyString(), anyBoolean());
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.BESTAETIGUNG_ERFOLGREICH));
    }

    @Test
    void banAllUnbekannterScopeBrichtAb() {
        aufbauen();
        BanService banService = mock(BanService.class);
        BanAllCommand befehl = new BanAllCommand(messages, config, schutz, cache,
                banService, zeit, planer, verzeichnis);
        befehl.ausfuehren(sender(), new String[]{"1h", "confirm", "--survial"});
        verify(banService, never()).banAlle(anyLong(), anyBoolean(), anyString(), anyString(),
                any(), any(), anyString(), anyBoolean());
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SCOPE_UNBEKANNT_WARNUNG));
    }

    @Test
    void muteAllTimeoutAllKickAllPardonAllUnmuteAllUntimeoutAllOhneConfirm() {
        aufbauen();
        MuteService muteService = mock(MuteService.class);
        TimeoutService timeoutService = mock(TimeoutService.class);
        KickService kickService = mock(KickService.class);
        BanService banService = mock(BanService.class);
        int vorher = nachrichten.komponenten.size() + nachrichten.gesendet.size();
        new MuteAllCommand(messages, config, schutz, cache, muteService, zeit, planer, verzeichnis)
                .ausfuehren(sender(), new String[]{"10m"});
        assertTrue(nachrichten.komponenten.size() + nachrichten.gesendet.size() > vorher,
                "mute-all ohne confirm muss eine Rückmeldung senden");
        vorher = nachrichten.komponenten.size() + nachrichten.gesendet.size();
        new TimeoutAllCommand(messages, config, schutz, cache, timeoutService, zeit, planer, verzeichnis)
                .ausfuehren(sender(), new String[]{"10m"});
        assertTrue(nachrichten.komponenten.size() + nachrichten.gesendet.size() > vorher,
                "timeout-all ohne confirm muss eine Rückmeldung senden");
        vorher = nachrichten.komponenten.size() + nachrichten.gesendet.size();
        new KickAllCommand(messages, config, schutz, cache, kickService, verzeichnis)
                .ausfuehren(sender(), new String[]{});
        assertTrue(nachrichten.komponenten.size() + nachrichten.gesendet.size() > vorher,
                "kick-all ohne confirm muss eine Rückmeldung senden");
        vorher = nachrichten.komponenten.size() + nachrichten.gesendet.size();
        new PardonAllCommand(messages, config, schutz, cache, banService, planer, verzeichnis)
                .ausfuehren(sender(), new String[]{});
        assertTrue(nachrichten.komponenten.size() + nachrichten.gesendet.size() > vorher,
                "pardon-all ohne confirm muss eine Rückmeldung senden");
        vorher = nachrichten.komponenten.size() + nachrichten.gesendet.size();
        new UnmuteAllCommand(messages, config, schutz, cache, muteService, planer, verzeichnis)
                .ausfuehren(sender(), new String[]{});
        assertTrue(nachrichten.komponenten.size() + nachrichten.gesendet.size() > vorher,
                "unmute-all ohne confirm muss eine Rückmeldung senden");
        vorher = nachrichten.komponenten.size() + nachrichten.gesendet.size();
        new UntimeoutAllCommand(messages, config, schutz, cache, timeoutService, planer, verzeichnis)
                .ausfuehren(sender(), new String[]{});
        assertTrue(nachrichten.komponenten.size() + nachrichten.gesendet.size() > vorher,
                "untimeout-all ohne confirm muss eine Rückmeldung senden");
        verify(muteService, never()).muteAlle(anyLong(), anyString(), anyString(), any(),
                any(), anyString(), anyBoolean());
        verify(timeoutService, never()).timeoutAlle(anyLong(), anyString(), anyString(), any(),
                any(), anyString(), anyBoolean());
        verify(kickService, never()).kickenAlle(anyString(), anyString(), any(), anyBoolean());
        verify(banService, never()).entbannenAlle(anyString(), anyString(), anyString(), any(),
                anyBoolean());
        verify(muteService, never()).entmutenAlle(anyString(), anyString(), anyString(), any(),
                anyBoolean());
        verify(timeoutService, never()).timeoutAufhebenAlle(anyString(), anyString(), anyString(),
                any(), anyBoolean());
        assertTrue(nachrichten.komponenten.contains(MessageKeys.BESTAETIGUNG_NOETIG));
    }

    @Test
    void banDauerFehlerMeldenDauerUngueltig() {
        aufbauen();
        UUID ziel = UUID.randomUUID();
        cacheZiel(ziel, "Ziel");
        BanService banService = mock(BanService.class);
        BanCommand befehl = banBefehl(banService);
        befehl.ausfuehren(sender(), new String[]{"Ziel", "0min", "Grund"});
        befehl.ausfuehren(sender(), new String[]{"Ziel", "-5min", "Grund"});
        befehl.ausfuehren(sender(), new String[]{"Ziel", "999999d", "Grund"});
        befehl.ausfuehren(sender(), new String[]{"Ziel"});
        verify(banService, never()).bannen(any(), any(), anyLong(), any(), any(), any(), any(), anyBoolean());
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    void banGrundMitMiniMessageWirdUebernommen() {
        aufbauen();
        UUID ziel = UUID.randomUUID();
        cacheZiel(ziel, "Ziel");
        BanService banService = mock(BanService.class);
        when(banService.bannen(any(), any(), anyLong(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(true);
        BanCommand befehl = banBefehl(banService);
        befehl.ausfuehren(sender(), new String[]{"Ziel", "7d", "<red>Test"});
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(banService).bannen(any(), any(), anyLong(), captor.capture(), any(), any(), any(), anyBoolean());
        assertEquals("<red>Test", captor.getValue());
    }

    @Test
    void banGrundZuLangWirdGekuerzt() {
        aufbauen();
        UUID ziel = UUID.randomUUID();
        cacheZiel(ziel, "Ziel");
        BanService banService = mock(BanService.class);
        when(banService.bannen(any(), any(), anyLong(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(true);
        BanCommand befehl = banBefehl(banService);
        befehl.ausfuehren(sender(), new String[]{"Ziel", "7d", "x".repeat(300)});
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(banService).bannen(any(), any(), anyLong(), captor.capture(), any(), any(), any(), anyBoolean());
        assertTrue(captor.getValue().length() <= 256);
    }

    @Test
    void banUnbekannterScopeBrichtAb() {
        aufbauen();
        UUID ziel = UUID.randomUUID();
        cacheZiel(ziel, "Ziel");
        BanService banService = mock(BanService.class);
        BanCommand befehl = banBefehl(banService);
        befehl.ausfuehren(sender(), new String[]{"Ziel", "7d", "Grund", "--survial"});
        verify(banService, never()).bannen(any(), any(), anyLong(), any(), any(), any(), any(), anyBoolean());
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SCOPE_UNBEKANNT_WARNUNG));
    }

    @Test
    void aufhebungenUnbekanntesZielMeldenNichtGefunden() {
        aufbauen();
        new PardonCommand(messages, config, schutz, cache, mock(BanService.class), planer, verzeichnis)
                .ausfuehren(sender(), new String[]{"Niemand"});
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SPIELER_NICHT_GEFUNDEN),
                "pardon muss bei unbekanntem Ziel 'nicht gefunden' melden");
        nachrichten.gesendet.clear();
        nachrichten.komponenten.clear();
        new UnmuteCommand(messages, config, schutz, cache, mock(MuteService.class), verzeichnis)
                .ausfuehren(sender(), new String[]{"Niemand"});
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SPIELER_NICHT_GEFUNDEN),
                "unmute muss bei unbekanntem Ziel 'nicht gefunden' melden");
        nachrichten.gesendet.clear();
        nachrichten.komponenten.clear();
        new UntimeoutCommand(messages, config, schutz, cache, mock(TimeoutService.class), verzeichnis)
                .ausfuehren(sender(), new String[]{"Niemand"});
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SPIELER_NICHT_GEFUNDEN),
                "untimeout muss bei unbekanntem Ziel 'nicht gefunden' melden");
        nachrichten.gesendet.clear();
        nachrichten.komponenten.clear();
        new UnfreezeCommand(messages, config, schutz, cache, mock(FreezeService.class), verzeichnis)
                .ausfuehren(sender(), new String[]{"Niemand"});
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SPIELER_NICHT_GEFUNDEN),
                "unfreeze muss bei unbekanntem Ziel 'nicht gefunden' melden");
        nachrichten.gesendet.clear();
        nachrichten.komponenten.clear();
        new UnbanIpCommand(messages, config, schutz, cache, mock(IpBanService.class),
                mock(IpBanRepository.class), planer, verzeichnis)
                .ausfuehren(sender(), new String[]{"Niemand"});
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SPIELER_NICHT_GEFUNDEN),
                "unban-ip muss bei unbekanntem Ziel 'nicht gefunden' melden");
        nachrichten.gesendet.clear();
        nachrichten.komponenten.clear();
        new UnmuteIpCommand(messages, config, schutz, cache, mock(IpMuteService.class), verzeichnis)
                .ausfuehren(sender(), new String[]{"Niemand"});
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SPIELER_NICHT_GEFUNDEN),
                "unmute-ip muss bei unbekanntem Ziel 'nicht gefunden' melden");
        nachrichten.gesendet.clear();
        nachrichten.komponenten.clear();
        new UntimeoutIpCommand(messages, config, schutz, cache, mock(IpTimeoutService.class), verzeichnis)
                .ausfuehren(sender(), new String[]{"Niemand"});
        assertTrue(nachrichten.gesendetAufgezeichnet(MessageKeys.SPIELER_NICHT_GEFUNDEN),
                "untimeout-ip muss bei unbekanntem Ziel 'nicht gefunden' melden");
    }

    @Test
    void einzelbefehleSyntaxfehlerWerfenNicht() {
        aufbauen();
        UUID ziel = UUID.randomUUID();
        cacheZiel(ziel, "Ziel");
        MuteService muteService = mock(MuteService.class);
        TimeoutService timeoutService = mock(TimeoutService.class);
        KickService kickService = mock(KickService.class);
        FreezeService freezeService = mock(FreezeService.class);
        BanService banService = mock(BanService.class);
        assertDoesNotThrow(() -> {
            new MuteCommand(messages, config, schutz, cache, muteService, zeit, planer, verzeichnis)
                    .ausfuehren(sender(), new String[]{});
            new TimeoutCommand(messages, config, schutz, cache, timeoutService, zeit, planer, verzeichnis)
                    .ausfuehren(sender(), new String[]{"Ziel"});
            new KickCommand(messages, config, schutz, cache, kickService, verzeichnis)
                    .ausfuehren(sender(), new String[]{});
            new FreezeCommand(messages, config, schutz, cache, freezeService, zeit, planer, verzeichnis)
                    .ausfuehren(sender(), new String[]{});
            new PermabannCommand(messages, config, schutz, cache, banService, planer, verzeichnis)
                    .ausfuehren(sender(), new String[]{});
            new MuteIpCommand(messages, config, schutz, cache, mock(IpMuteService.class),
                    zeit, planer, verzeichnis).ausfuehren(sender(), new String[]{});
            new TimeoutIpCommand(messages, config, schutz, cache, mock(IpTimeoutService.class),
                    zeit, planer, verzeichnis).ausfuehren(sender(), new String[]{});
            new KickIpCommand(messages, config, schutz, cache, kickService, verzeichnis)
                    .ausfuehren(sender(), new String[]{});
            new BanIpCommand(messages, config, schutz, cache, mock(IpBanService.class),
                    zeit, planer, verzeichnis).ausfuehren(sender(), new String[]{});
        });
    }

    @Test
    void verwaltungsbefehleOhneArgsWerfenNicht() {
        aufbauen();
        BefehlsSender ausfuehrer = sender();
        assertDoesNotThrow(() -> {
            new WarnCommand(messages, config, schutz, cache, mock(WarnService.class), planer, verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new VerstossCommand(messages, config, schutz, cache, mock(VerstossService.class), planer, verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new PunishNoteCommand(messages, config, schutz, cache, mock(NoteService.class), verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new ReportCommand(messages, config, schutz, cache, mock(ReportService.class), verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new ReportsCommand(messages, config, schutz, cache, mock(ReportService.class), verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new PunishStatusCommand(messages, config, schutz, cache, mock(VerstossService.class),
                    mock(BanRepository.class), mock(MuteRepository.class), mock(TimeoutRepository.class),
                    mock(IpBanRepository.class), mock(VerstossRepository.class), verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new PunishHistoryCommand(messages, config, schutz, cache, mock(HistoryService.class), verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new AltsCommand(messages, config, schutz, cache, mock(BanRepository.class),
                    mock(IpBanRepository.class), verzeichnis).ausfuehren(ausfuehrer, new String[]{});
            new DeleteIpCommand(messages, config, schutz, cache, mock(IpBanRepository.class),
                    mock(IpMuteRepository.class), mock(IpTimeoutRepository.class), verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new PunishVersionCommand(messages, config, schutz, cache, verzeichnis, () -> "4.0-test")
                    .ausfuehren(ausfuehrer, new String[]{});
            new PunishHelpCommand(messages, config, schutz, cache, verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
            new PunishDiagnosticsCommand(messages, config, schutz, cache, verzeichnis,
                    () -> new PunishDiagnosticsCommand.Kontext("Velocity", "4.0-test", "mysql", true,
                            "MySQL aktiv", 1, true, "Kanal ok", true, false, "aus", true, "einheitlich"))
                    .ausfuehren(ausfuehrer, new String[]{});
            new PunishEditCommand(messages, config, schutz, cache, mock(VerstossService.class),
                    mock(WarnService.class), mock(NoteService.class), mock(BanService.class),
                    mock(MuteService.class), mock(TimeoutService.class), mock(IpBanService.class),
                    mock(IpMuteService.class), mock(IpTimeoutService.class), zeit, planer, verzeichnis)
                    .ausfuehren(ausfuehrer, new String[]{});
        });
    }
}
