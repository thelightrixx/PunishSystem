package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.util.TimeParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression fuer A11-3: Schlägt der Verstoß-Snapshot fehl, wird der Reset abgebrochen.
 * Ein vollständiger Lösch-Fallback würde nicht zuordenbare Verstöße mitentfernen.
 */
class AutoBannSnapshotFehlerTest {

    private AutoBannService service;
    private BanRepository banRepository;
    private VerstossRepository verstossRepository;
    private Konfiguration config;
    private AuftragsPlaner planer;

    @BeforeEach
    void setup() {
        banRepository = mock(BanRepository.class);
        verstossRepository = mock(VerstossRepository.class);
        config = mock(Konfiguration.class);
        planer = mock(AuftragsPlaner.class);
        when(banRepository.alleFuer(any())).thenReturn(List.of());
        service = new AutoBannService(
                planer,
                banRepository,
                config,
                new TimeParser(),
                mock(HistoryService.class),
                mock(BroadcastService.class),
                mock(LogWriter.class),
                mock(ProtectionService.class),
                verstossRepository,
                mock(MessageService.class),
                mock(BanKickHelper.class),
                mock(SpielerVerzeichnis.class));
    }

    @Test
    void fehlerhafterVerstossSnapshotBrichtResetAb() {
        UUID ziel = UUID.randomUUID();
        BanRecord bann = new BanRecord(ziel, "Testspieler", "Auto-Bann", "System", 1L, 2L, true);
        when(banRepository.entbannenWenn(any(), any(), any())).thenReturn(Optional.of(bann));
        when(verstossRepository.finden(ziel)).thenThrow(new IllegalStateException("Snapshot-Scheitern"));

        service.behandleAutoBannAblauf(ziel, "Testspieler");

        verify(verstossRepository, never()).zuruecksetzen(any());
        verify(verstossRepository, never()).zuruecksetzenNur(any(), any());
    }

    @Test
    void vorhandenerVerstossSnapshotGrenztResetEin() {
        UUID ziel = UUID.randomUUID();
        BanRecord bann = new BanRecord(ziel, "Testspieler", "Auto-Bann", "System", 1L, 2L, true);
        when(banRepository.entbannenWenn(any(), any(), any())).thenReturn(Optional.of(bann));
        de.lightrixx.punish.model.VerstossRecord record = mock(de.lightrixx.punish.model.VerstossRecord.class);
        when(record.nummer()).thenReturn(7);
        when(verstossRepository.finden(ziel)).thenReturn(List.of(record));
        when(verstossRepository.zuruecksetzenNur(any(), any())).thenReturn(List.of());

        service.behandleAutoBannAblauf(ziel, "Testspieler");

        verify(verstossRepository).zuruecksetzenNur(ziel, java.util.Set.of(7));
        verify(verstossRepository, never()).zuruecksetzen(any());
    }
    @Test
    void cleanupLaesstManuelleBannsFuerRegulaereAblaufhistorieBestehen() {
        var bann = new BanRecord(UUID.randomUUID(), "Steve", "Manuell", "Mod", 1L, 2L, false);
        when(banRepository.alle()).thenReturn(List.of(bann));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                service.bereinigeAbgelaufeneMitZeremonie(System.currentTimeMillis()));
        verify(banRepository, never()).entferneAbgelaufene(org.mockito.ArgumentMatchers.anyLong());
        verify(banRepository, never()).entbannenWenn(any(), any(), any());
    }

    @Test
    void autoPardonUndAblaufMeldenZustandsaenderung() {
        var beobachter = mock(BanService.BanBeobachter.class);
        service.beobachterHinzufuegen(beobachter);
        UUID uuid = UUID.randomUUID();
        var bann = new BanRecord(uuid, "Steve", "Auto", "System", 1L, 2L, true);
        when(banRepository.entbannenWenn(any(), any(), any())).thenReturn(Optional.of(bann));
        when(verstossRepository.finden(uuid)).thenReturn(List.of());
        when(verstossRepository.zuruecksetzenNur(any(), any())).thenReturn(List.of());
        service.autoPardonAufheben(uuid, "Steve", "Mod");
        service.behandleAutoBannAblauf(uuid, "Steve");
        verify(beobachter, org.mockito.Mockito.times(2)).banGeaendert();
    }
    @Test
    void neuerAutoBannMeldetZustandsaenderung() {
        when(config.limitSnapshot()).thenReturn(new Konfiguration.LimitSnapshot(6, "7d"));
        when(config.standardGrund(any())).thenReturn("Auto");
        when(banRepository.bannenWennFrei(any())).thenReturn(true);
        when(planer.spaeter(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(mock(de.lightrixx.punish.core.GeplanteAufgabe.class));
        var beobachter = mock(BanService.BanBeobachter.class);
        service.beobachterHinzufuegen(beobachter);
        org.junit.jupiter.api.Assertions.assertTrue(service.autoBann(UUID.randomUUID(), "Steve", "System", 6, 6));
        verify(beobachter).banGeaendert();
    }
}
