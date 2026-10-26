package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpMuteRecord;
import de.lightrixx.punish.model.IpTimeoutRecord;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.TimeoutRecord;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.IpMuteService;
import de.lightrixx.punish.service.IpTimeoutService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.NoteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1-08: dauer=perm bei Mute-/Timeout-Edits meldet perm-nicht-erlaubt statt dauer-ungueltig.
 */
class PunishEditDauerPermTest {

    private final MessageService messages = mock(MessageService.class);
    private final ProtectionService schutz = mock(ProtectionService.class);
    private final BanService banService = mock(BanService.class);
    private final IpBanService ipBanService = mock(IpBanService.class);
    private final MuteService muteService = mock(MuteService.class);
    private final TimeoutService timeoutService = mock(TimeoutService.class);
    private final IpMuteService ipMuteService = mock(IpMuteService.class);
    private final IpTimeoutService ipTimeoutService = mock(IpTimeoutService.class);
    private final TimeParser timeParser = mock(TimeParser.class);

    private BefehlsSender sender() {
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.istSpieler()).thenReturn(false);
        when(sender.name()).thenReturn("Konsole");
        return sender;
    }

    private Konfiguration config() {
        Konfiguration config = mock(Konfiguration.class);
        when(config.standardScope()).thenReturn(ServerScope.global());
        when(config.bekannteServer()).thenReturn(List.of("lobby"));
        when(config.banMaxMinuten()).thenReturn(525600L);
        when(config.banMinMinuten()).thenReturn(1L);
        when(config.muteMaxMinuten()).thenReturn(43200L);
        when(config.standardGrund(anyString())).thenReturn("Regelverstoß");
        when(config.serverName()).thenReturn("lobby");
        return config;
    }

    private PunishEditCommand bauen(PlayerCache cache) {
        when(schutz.istSelbstbestrafung(any(BefehlsSender.class), any(UUID.class))).thenReturn(false);
        when(schutz.pruefeSchutzSofort(any(UUID.class), any())).thenReturn(
                Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT));
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpielerNachName(anyString())).thenReturn(Optional.empty());
        when(verzeichnis.onlineSpieler()).thenReturn(List.of());
        return new PunishEditCommand(messages, config(), schutz, cache,
                mock(VerstossService.class), mock(WarnService.class), mock(NoteService.class),
                banService, muteService, timeoutService,
                ipBanService, ipMuteService, ipTimeoutService,
                timeParser, mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                verzeichnis);
    }

    private PlayerCache cacheMit(UUID uuid) {
        PlayerCache cache = mock(PlayerCache.class);
        when(cache.findenNachName("Ziel"))
                .thenReturn(Optional.of(new CachedPlayer(uuid, "Ziel", "1.2.3.4",
                        System.currentTimeMillis())));
        when(cache.istFrischeNamensZuordnung("Ziel")).thenReturn(true);
        return cache;
    }

    @Test
    @DisplayName("Reason-only bei temporärem IP-Bann bleibt befristet")
    void ipBannGrundEditBleibtBefristet() {
        PunishEditCommand befehl = bauen(mock(PlayerCache.class));
        long jetzt = System.currentTimeMillis();
        when(ipBanService.ipFindeAbdeckend(eq("1.2.3.4"), any())).thenReturn(Optional.of(
                new IpBanRecord("1.2.3.4", "Alt", "Mod", jetzt - 60_000L,
                        jetzt + 60_000L, ServerScope.global())));

        befehl.ausfuehren(sender(), new String[]{"ban-ip", "1.2.3.4", "grund=Neu"});

        ArgumentCaptor<Long> dauer = ArgumentCaptor.forClass(Long.class);
        verify(ipBanService).ipBannBearbeiten(eq("1.2.3.4"), dauer.capture(), eq("Neu"),
                anyString(), any(), anyString(), eq(false));
        assertNotNull(dauer.getValue(), "Restdauer muss erhalten bleiben");
        assertTrue(dauer.getValue() > 0L && dauer.getValue() <= 60_000L,
                "Reason-only darf nicht permanent machen: " + dauer.getValue());
    }

    @Test
    @DisplayName("Grund mit führendem Dauer-Token bleibt Grund, Restdauer bleibt")
    void ipBannGrundMitDauerTokenBleibtGrund() {
        PunishEditCommand befehl = bauen(mock(PlayerCache.class));
        long jetzt = System.currentTimeMillis();
        when(ipBanService.ipFindeAbdeckend(eq("1.2.3.4"), any())).thenReturn(Optional.of(
                new IpBanRecord("1.2.3.4", "Alt", "Mod", jetzt - 60_000L,
                        jetzt + 300_000L, ServerScope.global())));

        befehl.ausfuehren(sender(), new String[]{"ban-ip", "1.2.3.4", "grund=1d Entschuldigung"});

        ArgumentCaptor<Long> dauer = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> grund = ArgumentCaptor.forClass(String.class);
        verify(ipBanService).ipBannBearbeiten(eq("1.2.3.4"), dauer.capture(), grund.capture(),
                anyString(), any(), anyString(), eq(false));
        assertEquals("1d Entschuldigung", grund.getValue(),
                "Expliziter Grund darf nicht als Dauer interpretiert werden");
        assertNotNull(dauer.getValue(), "Restdauer muss erhalten bleiben");
        assertTrue(dauer.getValue() > 0L && dauer.getValue() <= 300_000L,
                "Restdauer darf nicht aus dem Grund gesetzt werden: " + dauer.getValue());
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    @DisplayName("Grund, der wie eine Dauer aussieht, meldet kein dauer-ungueltig")
    void ipBannGrundWieDauerMeldetKeinDauerUngueltig() {
        PunishEditCommand befehl = bauen(mock(PlayerCache.class));
        long jetzt = System.currentTimeMillis();
        when(ipBanService.ipFindeAbdeckend(eq("1.2.3.4"), any())).thenReturn(Optional.of(
                new IpBanRecord("1.2.3.4", "Alt", "Mod", jetzt - 60_000L,
                        jetzt + 300_000L, ServerScope.global())));

        befehl.ausfuehren(sender(), new String[]{"ban-ip", "1.2.3.4", "grund=2ter Verstoß"});

        ArgumentCaptor<String> grund = ArgumentCaptor.forClass(String.class);
        verify(ipBanService).ipBannBearbeiten(eq("1.2.3.4"), anyLong(), grund.capture(),
                anyString(), any(), anyString(), eq(false));
        assertEquals("2ter Verstoß", grund.getValue());
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    @DisplayName("Grundänderung an permanentem IP-Bann bleibt permanent")
    void ipPermanenterBannGrundEditBleibtPermanent() {
        PunishEditCommand befehl = bauen(mock(PlayerCache.class));
        when(ipBanService.ipFindeAbdeckend(eq("1.2.3.4"), any())).thenReturn(Optional.of(
                new IpBanRecord("1.2.3.4", "Alt", "Mod", System.currentTimeMillis() - 60_000L,
                        null, ServerScope.global())));

        befehl.ausfuehren(sender(), new String[]{"ban-ip", "1.2.3.4", "grund=Neu"});

        verify(ipBanService).ipBannBearbeiten(eq("1.2.3.4"), isNull(), eq("Neu"),
                anyString(), any(), anyString(), eq(false));
        verify(messages, never()).senden(any(), eq(MessageKeys.PERM_NICHT_ERLAUBT));
    }

    @Test
    @DisplayName("Permanenter Bann ohne punish.permabann nicht verkürzbar")
    void permanenterBannOhneRechtNichtVerkuerzbar() {
        UUID uuid = UUID.randomUUID();
        PunishEditCommand befehl = bauen(cacheMit(uuid));
        when(banService.findeAbdeckend(eq(uuid), any())).thenReturn(Optional.of(new BanRecord(
                uuid, "Ziel", "Alt", "Mod", System.currentTimeMillis() - 60_000L,
                null, false, ServerScope.global())));
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.hatRecht(Permissions.PUNISH_PERMABANN.node())).thenReturn(false);
        when(sender.istSpieler()).thenReturn(false);
        when(sender.name()).thenReturn("Mod");
        when(timeParser.parsen("30m")).thenReturn(new ParseResult.Geltzeit(30L * 60_000L));

        befehl.ausfuehren(sender, new String[]{"ban", "Ziel", "dauer=30m"});

        verify(messages).senden(any(), eq(MessageKeys.PERM_NICHT_ERLAUBT));
        verify(banService, never()).bearbeiten(any(), anyString(), anyLong(), any(),
                anyString(), any(), anyString(), anyBoolean());
        verify(banService, never()).bearbeitenPermanent(any(), anyString(), any(),
                anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("S1-08: /punish edit mute Ziel dauer=perm → perm-nicht-erlaubt")
    void editMuteDauerPermMeldetPermNichtErlaubt() {
        UUID uuid = UUID.randomUUID();
        PunishEditCommand befehl = bauen(cacheMit(uuid));
        when(muteService.findeAbdeckend(eq(uuid), any())).thenReturn(Optional.of(new MuteRecord(
                uuid, "Ziel", "Spam", "Mod", System.currentTimeMillis() - 60000,
                System.currentTimeMillis() + 60000, ServerScope.global())));
        befehl.ausfuehren(sender(), new String[]{"mute", "Ziel", "dauer=perm"});
        verify(messages).senden(any(), eq(MessageKeys.PERM_NICHT_ERLAUBT));
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    @DisplayName("S1-08: /punish edit timeout Ziel dauer=perm → perm-nicht-erlaubt")
    void editTimeoutDauerPermMeldetPermNichtErlaubt() {
        UUID uuid = UUID.randomUUID();
        PunishEditCommand befehl = bauen(cacheMit(uuid));
        when(timeoutService.findeAbdeckend(eq(uuid), any())).thenReturn(Optional.of(new TimeoutRecord(
                uuid, "Ziel", "Spam", "Mod", System.currentTimeMillis() - 60000,
                System.currentTimeMillis() + 60000, ServerScope.global())));
        befehl.ausfuehren(sender(), new String[]{"timeout", "Ziel", "dauer=perm"});
        verify(messages).senden(any(), eq(MessageKeys.PERM_NICHT_ERLAUBT));
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    @DisplayName("S1-08: /punish edit mute-ip 1.2.3.4 dauer=perm → perm-nicht-erlaubt")
    void editMuteIpDauerPermMeldetPermNichtErlaubt() {
        PunishEditCommand befehl = bauen(mock(PlayerCache.class));
        when(ipMuteService.findeAbdeckend(eq("1.2.3.4"), any())).thenReturn(Optional.of(new IpMuteRecord(
                "1.2.3.4", "Spam", "Mod", System.currentTimeMillis() - 60000,
                System.currentTimeMillis() + 60000, ServerScope.global())));
        befehl.ausfuehren(sender(), new String[]{"mute-ip", "1.2.3.4", "dauer=perm"});
        verify(messages).senden(any(), eq(MessageKeys.PERM_NICHT_ERLAUBT));
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    @DisplayName("S1-08: /punish edit timeout-ip 1.2.3.4 dauer=perm → perm-nicht-erlaubt")
    void editTimeoutIpDauerPermMeldetPermNichtErlaubt() {
        PunishEditCommand befehl = bauen(mock(PlayerCache.class));
        when(ipTimeoutService.findeAbdeckend(eq("1.2.3.4"), any())).thenReturn(
                Optional.of(new IpTimeoutRecord(
                        "1.2.3.4", "Spam", "Mod", System.currentTimeMillis() - 60000,
                        System.currentTimeMillis() + 60000, ServerScope.global())));
        befehl.ausfuehren(sender(), new String[]{"timeout-ip", "1.2.3.4", "dauer=perm"});
        verify(messages).senden(any(), eq(MessageKeys.PERM_NICHT_ERLAUBT));
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }
}
