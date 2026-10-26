package de.lightrixx.punish.audit;

import de.lightrixx.punish.command.PunishEditCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpStrafSchluessel;
import de.lightrixx.punish.model.ServerScope;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditFixV10RegressionTest {

    private record Aufbau(PunishEditCommand befehl, MessageService messages, BanService banService,
                          PlayerCache cache, Konfiguration config, UUID uuid) {
    }

    private static Aufbau aufbau() {
        MessageService messages = mock(MessageService.class);
        Konfiguration config = mock(Konfiguration.class);
        PlayerCache cache = mock(PlayerCache.class);
        BanService banService = mock(BanService.class);
        UUID uuid = UUID.randomUUID();
        CachedPlayer ziel = new CachedPlayer(uuid, "Max", "1.2.3.4", System.currentTimeMillis());
        when(cache.findenNachName("Max")).thenReturn(Optional.of(ziel));
        when(cache.istFrischeNamensZuordnung("Max")).thenReturn(true);
        when(config.standardScope()).thenReturn(ServerScope.global());
        when(config.bekannteServer()).thenReturn(List.of());
        when(config.banMaxMinuten()).thenReturn(1440L);
        when(config.banMinMinuten()).thenReturn(1L);
        ProtectionService schutz = mock(ProtectionService.class);
        when(schutz.pruefeSchutzSofort(eq(uuid), any(ProtectionService.IgnoreTyp.class)))
                .thenReturn(Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT));
        PunishEditCommand befehl = new PunishEditCommand(
                messages, config, schutz, cache, mock(VerstossService.class),
                mock(WarnService.class), mock(NoteService.class), banService, mock(MuteService.class),
                mock(TimeoutService.class), mock(IpBanService.class), mock(IpMuteService.class),
                mock(IpTimeoutService.class), mock(TimeParser.class), mock(AuftragsPlaner.class),
                mock(SpielerVerzeichnis.class));
        return new Aufbau(befehl, messages, banService, cache, config, uuid);
    }

    @Test
    void permanentBanGrundEditBleibtPermanentStattDauerFehler() {
        Aufbau aufbau = aufbau();
        long jetzt = System.currentTimeMillis();
        BanRecord permanent = new BanRecord(aufbau.uuid(), "Max", "Altgrund", "Admin",
                jetzt - 60_000L, null, false, ServerScope.global());
        when(aufbau.banService().findeAbdeckend(eq(aufbau.uuid()), any(ServerScope.class)))
                .thenReturn(Optional.of(permanent));
        BanRecord neu = new BanRecord(aufbau.uuid(), "Max", "Neuer Grund", "Konsole",
                jetzt, null, false, ServerScope.global());
        when(aufbau.banService().bearbeitenPermanent(eq(aufbau.uuid()), eq("Max"), eq("Neuer Grund"),
                any(), any(ServerScope.class), any(), anyBoolean()))
                .thenReturn(Optional.of(neu));

        BefehlsSender sender = mock(BefehlsSender.class);
        aufbau.befehl().ausfuehren(sender, new String[]{"ban", "Max", "grund=Neuer", "Grund"});

        verify(aufbau.banService()).bearbeitenPermanent(eq(aufbau.uuid()), eq("Max"), eq("Neuer Grund"),
                any(), any(ServerScope.class), any(), eq(false));
        verify(aufbau.banService(), never()).bearbeiten(any(), any(), anyLong(), any(), any(), any(),
                any(), anyBoolean());
        verify(aufbau.messages(), never()).senden(eq(sender), eq(MessageKeys.DAUER_UNGUELTIG));
        verify(aufbau.messages()).senden(eq(sender), eq(MessageKeys.BAN_EDIT_ERFOLG), anyMap());
    }

    @Test
    void befristeterBanGrundEditBehaeltRestdauer() {
        Aufbau aufbau = aufbau();
        long jetzt = System.currentTimeMillis();
        BanRecord befristet = new BanRecord(aufbau.uuid(), "Max", "Altgrund", "Admin",
                jetzt - 60_000L, jetzt + 300_000L, false, ServerScope.global());
        when(aufbau.banService().findeAbdeckend(eq(aufbau.uuid()), any(ServerScope.class)))
                .thenReturn(Optional.of(befristet));
        BanRecord neu = new BanRecord(aufbau.uuid(), "Max", "Neuer Grund", "Konsole",
                jetzt, jetzt + 240_000L, false, ServerScope.global());
        when(aufbau.banService().bearbeiten(any(), any(), anyLong(), any(), any(),
                any(ServerScope.class), any(), anyBoolean())).thenReturn(Optional.of(neu));

        BefehlsSender sender = mock(BefehlsSender.class);
        aufbau.befehl().ausfuehren(sender, new String[]{"ban", "Max", "grund=Neuer", "Grund"});

        ArgumentCaptor<Long> dauer = ArgumentCaptor.forClass(Long.class);
        verify(aufbau.banService()).bearbeiten(eq(aufbau.uuid()), eq("Max"), dauer.capture(),
                eq("Neuer Grund"), any(), any(ServerScope.class), any(), eq(false));
        long restdauer = dauer.getValue();
        assertTrue(restdauer > 290_000L && restdauer <= 300_000L,
                "Grund-Edit muss die Restdauer (~300 s) unverändert lassen, war: " + restdauer);
        verify(aufbau.banService(), never()).bearbeitenPermanent(any(), any(), any(), any(), any(),
                any(), anyBoolean());
        verify(aufbau.messages(), never()).senden(eq(sender), eq(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    void ipSchluesselKanonisiertLeerraumUndSchreibweise() {
        assertEquals(IpStrafSchluessel.von("1.2.3.4", ServerScope.global()),
                IpStrafSchluessel.von(" 1.2.3.4 ", ServerScope.global()));
        assertEquals(IpStrafSchluessel.von("::ffff:1.2.3.4", ServerScope.global()),
                IpStrafSchluessel.von("::FFFF:1.2.3.4", ServerScope.global()));
    }
}
