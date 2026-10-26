package de.lightrixx.punish.velocity;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.FreezeRecord;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatzhalterDienstKonfigPunkteTest {

    @Test
    void verstossPunkteNutzenKonfigurierteStufenPunkte() {
        Logger logger = mock(Logger.class);
        Konfiguration config = mock(Konfiguration.class);
        when(config.verstossStufenPunkte(VerstossStufe.MEDIUM)).thenReturn(5);

        PlayerCache playerCache = mock(PlayerCache.class);
        UUID uuid = UUID.randomUUID();
        when(playerCache.findenNachName("TestSpieler"))
                .thenReturn(Optional.of(new CachedPlayer(uuid, "TestSpieler", "1.2.3.4", 1000L)));

        VerstossRepository verstossRepository = mock(VerstossRepository.class);
        when(verstossRepository.finden(uuid)).thenReturn(List.of(
                new VerstossRecord(1, "TestSpieler", "Spamming", "Admin", 1000L, VerstossStufe.MEDIUM)));

        BanRepository banRepository = mock(BanRepository.class);
        MuteRepository muteRepository = mock(MuteRepository.class);
        TimeoutRepository timeoutRepository = mock(TimeoutRepository.class);
        WarnRepository warnRepository = mock(WarnRepository.class);
        ReportService reportService = mock(ReportService.class);

        PlatzhalterDienst dienst = new PlatzhalterDienst(logger, config, playerCache,
                banRepository, muteRepository, timeoutRepository, verstossRepository,
                warnRepository, reportService);

        Protokoll.PlatzhalterAntwort antwort = dienst.beantworte(
                new Protokoll.PlatzhalterAnfrage(1L, uuid, "TestSpieler"));

        assertEquals(true, antwort.erfolg());
        assertEquals(1, antwort.verstossAnzahl());
        assertEquals(5, antwort.verstossPunkte(),
                "Punkte müssen aus der Konfiguration kommen (medium=5), nicht Standard (2)");
    }

    @Test
    void unbekannterSpieler_ergibtLeereAntwort() {
        Logger logger = mock(Logger.class);
        Konfiguration config = mock(Konfiguration.class);
        when(config.verstossStufenPunkte(any())).thenReturn(1);

        PlayerCache playerCache = mock(PlayerCache.class);
        when(playerCache.findenNachName("Niemand")).thenReturn(Optional.empty());

        PlatzhalterDienst dienst = new PlatzhalterDienst(logger, config, playerCache,
                mock(BanRepository.class), mock(MuteRepository.class), mock(TimeoutRepository.class),
                mock(VerstossRepository.class), mock(WarnRepository.class), mock(ReportService.class));

        Protokoll.PlatzhalterAntwort antwort = dienst.beantworte(
                new Protokoll.PlatzhalterAnfrage(2L, null, "Niemand"));
        // Protokoll-Semantik: unbekannter Spieler = erfolgreiche LEERE Antwort (erfolg=true,
        // alle Werte 0), nicht fehler – PAPI rendert dann seine Standardwerte.
        assertEquals(true, antwort.erfolg());
        assertEquals(0, antwort.verstossAnzahl());
        assertEquals(0, antwort.verstossPunkte());
    }

    @Test
    void freezeWirdAlsPlatzhalterGemeldet() {
        Logger logger = mock(Logger.class);
        Konfiguration config = mock(Konfiguration.class);
        when(config.verstossStufenPunkte(any())).thenReturn(1);

        PlayerCache playerCache = mock(PlayerCache.class);
        UUID uuid = UUID.randomUUID();
        when(playerCache.findenNachName("Frosty"))
                .thenReturn(Optional.of(new CachedPlayer(uuid, "Frosty", "1.2.3.4", 1000L)));

        long jetzt = System.currentTimeMillis();
        FreezeRepository freezeRepository = mock(FreezeRepository.class);
        when(freezeRepository.alleFuer(uuid)).thenReturn(List.of(
                new FreezeRecord(uuid, "Frosty", "Verdacht", "Admin", jetzt - 1000L,
                        jetzt + 3600000L, ServerScope.global())));

        PlatzhalterDienst dienst = new PlatzhalterDienst(logger, config, playerCache,
                mock(BanRepository.class), mock(MuteRepository.class), mock(TimeoutRepository.class),
                mock(VerstossRepository.class), mock(WarnRepository.class), mock(ReportService.class));
        dienst.setFreezeRepository(freezeRepository);

        Protokoll.PlatzhalterAntwort antwort = dienst.beantworte(
                new Protokoll.PlatzhalterAnfrage(3L, uuid, "Frosty"));

        assertEquals(true, antwort.erfolg());
        assertEquals(true, antwort.frozen());
        assertEquals(jetzt + 3600000L, antwort.freezeAblauf());
    }

    @Test
    void detailsNotizenHistoryUndWarnGrundWerdenGemeldet() {
        Logger logger = mock(Logger.class);
        Konfiguration config = mock(Konfiguration.class);
        when(config.verstossStufenPunkte(any())).thenReturn(2);

        PlayerCache playerCache = mock(PlayerCache.class);
        UUID uuid = UUID.randomUUID();
        when(playerCache.findenNachName("Stummi"))
                .thenReturn(Optional.of(new CachedPlayer(uuid, "Stummi", "1.2.3.4", 1000L)));

        long jetzt = System.currentTimeMillis();
        long muteSeit = jetzt - 5000L;
        long muteAblauf = jetzt + 60000L;
        MuteRepository muteRepository = mock(MuteRepository.class);
        when(muteRepository.alleFuer(uuid)).thenReturn(List.of(
                new MuteRecord(uuid, "Stummi", "Spam", "Admin", muteSeit, muteAblauf,
                        ServerScope.global())));

        VerstossRepository verstossRepository = mock(VerstossRepository.class);
        when(verstossRepository.finden(uuid)).thenReturn(List.of(
                new VerstossRecord(1, "Stummi", "Spamming", "Admin", 1000L, VerstossStufe.MEDIUM)));

        WarnRepository warnRepository = mock(WarnRepository.class);
        when(warnRepository.anzahl(uuid)).thenReturn(2);
        when(warnRepository.finden(uuid)).thenReturn(List.of(
                new WarnRecord(1, "Stummi", "Caps", "Mod", 1000L),
                new WarnRecord(2, "Stummi", "Beleidigung", "Admin", 2000L)));

        NoteRepository noteRepository = mock(NoteRepository.class);
        when(noteRepository.anzahl(uuid)).thenReturn(3);

        HistoryService historyService = mock(HistoryService.class);
        when(historyService.findeSpielerHistory(uuid, 500)).thenReturn(List.of(
                mock(HistoryEntry.class), mock(HistoryEntry.class)));

        PlatzhalterDienst dienst = new PlatzhalterDienst(logger, config, playerCache,
                mock(BanRepository.class), muteRepository, mock(TimeoutRepository.class),
                verstossRepository, warnRepository, mock(ReportService.class));
        dienst.setNoteRepository(noteRepository);
        dienst.setHistoryService(historyService);

        Protokoll.PlatzhalterAntwort antwort = dienst.beantworte(
                new Protokoll.PlatzhalterAnfrage(4L, uuid, "Stummi"));

        assertEquals(true, antwort.erfolg());
        assertEquals(true, antwort.muted());
        assertEquals(muteAblauf, antwort.muteAblauf());
        assertEquals("Spam", antwort.muteGrund());
        assertEquals("Admin", antwort.muteVon());
        assertEquals(muteSeit, antwort.muteSeit());
        assertEquals("", antwort.banGrund());
        assertEquals(0L, antwort.banSeit());
        assertEquals(2, antwort.warns());
        assertEquals("Beleidigung", antwort.warnsLetzterGrund());
        assertEquals("medium", antwort.verstossStufe());
        assertEquals(3, antwort.notizen());
        assertEquals(2, antwort.historyAnzahl());
    }
}
