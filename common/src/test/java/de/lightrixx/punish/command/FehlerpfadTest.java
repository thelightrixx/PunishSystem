package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FehlerpfadTest {

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

    private PlayerCache cacheMit(UUID uuid) {
        PlayerCache cache = mock(PlayerCache.class);
        when(cache.findenNachName("Ziel"))
                .thenReturn(Optional.of(new CachedPlayer(uuid, "Ziel", "1.2.3.4", System.currentTimeMillis())));
        when(cache.istFrischeNamensZuordnung("Ziel")).thenReturn(true);
        return cache;
    }

    private SpielerVerzeichnis leeresVerzeichnis() {
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpielerNachName(anyString())).thenReturn(Optional.empty());
        when(verzeichnis.onlineSpieler()).thenReturn(List.of());
        return verzeichnis;
    }

    private ProtectionService neutralerSchutz() {
        ProtectionService schutz = mock(ProtectionService.class);
        when(schutz.istSelbstbestrafung(any(BefehlsSender.class), any(UUID.class))).thenReturn(false);
        when(schutz.pruefeSchutzSofort(any(UUID.class), any())).thenReturn(
                Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT));
        when(schutz.pruefeSchutz(any(UUID.class), any())).thenReturn(
                ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT);
        return schutz;
    }

    @Test
    @DisplayName("A-004: interner Bann-Fehlschlag meldet aktion-fehlgeschlagen statt dauer-ungueltig")
    void banFehlschlagMeldetGenerischenFehler() {
        UUID uuid = UUID.randomUUID();
        MessageService messages = mock(MessageService.class);
        BanService banService = mock(BanService.class);
        when(banService.istAbgedeckt(eq(uuid), any())).thenReturn(false);
        when(banService.bannen(any(), any(), anyLong(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(false);
        TimeParser zeit = mock(TimeParser.class);
        when(zeit.parsen("7d")).thenReturn(new ParseResult.Geltzeit(7L * 86400000L));
        BanCommand befehl = new BanCommand(messages, config(), neutralerSchutz(), cacheMit(uuid),
                banService, zeit, mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                leeresVerzeichnis());
        befehl.ausfuehren(sender(), new String[]{"Ziel", "7d", "Cheating"});
        verify(messages).senden(any(), eq(MessageKeys.AKTION_FEHLGESCHLAGEN));
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    @DisplayName("A-004: interner Mute-Fehlschlag meldet aktion-fehlgeschlagen statt dauer-ungueltig")
    void muteFehlschlagMeldetGenerischenFehler() {
        UUID uuid = UUID.randomUUID();
        MessageService messages = mock(MessageService.class);
        MuteService muteService = mock(MuteService.class);
        when(muteService.istAbgedeckt(eq(uuid), any())).thenReturn(false);
        when(muteService.muten(any(), any(), anyLong(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(false);
        TimeParser zeit = mock(TimeParser.class);
        when(zeit.parsen("10m")).thenReturn(new ParseResult.Geltzeit(600000L));
        MuteCommand befehl = new MuteCommand(messages, config(), neutralerSchutz(), cacheMit(uuid),
                muteService, zeit, mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                leeresVerzeichnis());
        befehl.ausfuehren(sender(), new String[]{"Ziel", "10m", "Spamming"});
        verify(messages).senden(any(), eq(MessageKeys.AKTION_FEHLGESCHLAGEN));
        verify(messages, never()).senden(any(), eq(MessageKeys.DAUER_UNGUELTIG));
    }

    @Test
    @DisplayName("A-003: Fehler beim inneren Scheduler meldet aktion-fehlgeschlagen")
    @SuppressWarnings("unchecked")
    void innererSchedulerFehlerMeldetFehler() {
        MessageService messages = mock(MessageService.class);
        ProtectionService schutz = neutralerSchutz();
        when(schutz.pruefeSchutzSofort(any(UUID.class), any())).thenReturn(Optional.empty());
        de.lightrixx.punish.core.AuftragsPlaner planer =
                mock(de.lightrixx.punish.core.AuftragsPlaner.class);
        doThrow(new IllegalStateException("Shutdown")).when(planer).aufgabe(any(Runnable.class));
        CachedPlayer ziel = new CachedPlayer(UUID.randomUUID(), "Ziel", "1.2.3.4",
                System.currentTimeMillis());
        PunishCommand befehl = new BanCommand(messages, config(), schutz,
                mock(PlayerCache.class), mock(BanService.class), mock(TimeParser.class),
                planer, leeresVerzeichnis());
        var async = mock(de.lightrixx.punish.core.GeplanteAufgabe.class);
        when(planer.aufgabeAsync(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return async;
        });
        befehl.mitSchutz(sender(), new String[0], ziel, () -> {
        });
        verify(messages).senden(any(), eq(MessageKeys.SCHUTZ_PRUEFUNG_LAEUFT), any(Map.class));
        verify(messages).senden(any(), eq(MessageKeys.AKTION_FEHLGESCHLAGEN));
    }

    @Test
    @DisplayName("A-003: abgelehnter Schutz-Scheduler blockiert die Aktion (äußerer catch)")
    void abgelehnterSchutzSchedulerBlockiertAktion() {
        MessageService messages = mock(MessageService.class);
        ProtectionService schutz = neutralerSchutz();
        when(schutz.pruefeSchutzSofort(any(UUID.class), any())).thenReturn(Optional.empty());
        de.lightrixx.punish.core.AuftragsPlaner planer =
                mock(de.lightrixx.punish.core.AuftragsPlaner.class);
        when(planer.aufgabeAsync(any(Runnable.class)))
                .thenThrow(new IllegalStateException("Scheduler geschlossen"));
        CachedPlayer ziel = new CachedPlayer(UUID.randomUUID(), "Ziel", "1.2.3.4",
                System.currentTimeMillis());
        PunishCommand befehl = new BanCommand(messages, config(), schutz,
                mock(PlayerCache.class), mock(BanService.class), mock(TimeParser.class),
                planer, leeresVerzeichnis());
        java.util.concurrent.atomic.AtomicBoolean ausgefuehrt =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        befehl.mitSchutz(sender(), new String[0], ziel, () -> ausgefuehrt.set(true));

        verify(planer).aufgabeAsync(any(Runnable.class));
        org.junit.jupiter.api.Assertions.assertFalse(ausgefuehrt.get(),
                "Ohne abgeschlossene Schutzprüfung darf die Aktion nicht laufen");
    }
}
