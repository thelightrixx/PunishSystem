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

    @BeforeEach
    void setup() {
        banRepository = mock(BanRepository.class);
        verstossRepository = mock(VerstossRepository.class);
        when(banRepository.alleFuer(any())).thenReturn(List.of());
        service = new AutoBannService(
                mock(AuftragsPlaner.class),
                banRepository,
                mock(Konfiguration.class),
                mock(TimeParser.class),
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
}
