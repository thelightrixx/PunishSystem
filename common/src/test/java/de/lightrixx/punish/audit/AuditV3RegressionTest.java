package de.lightrixx.punish.audit;

import de.lightrixx.punish.command.ScopeParser;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.service.BanKickHelper;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditV3RegressionTest {

    @TempDir
    File datenOrdner;

    private BanRepository neuesRepo() {
        YamlSpeicher speicher = mock(YamlSpeicher.class);
        IoExecutor io = mock(IoExecutor.class);
        BanRepository repo = new BanRepository(datenOrdner, speicher, io);
        return repo;
    }

    @Test
    @DisplayName("V3-PA1: pardon-all mit --lobby entfernt nur Lobby-Banns")
    void pardonAllMitScopeFiltert() {
        BanRepository repo = neuesRepo();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        repo.bannenWennFrei(new BanRecord(a, "Alpha", "Grund", "Mod", jetzt, jetzt + 3_600_000L, false,
                ServerScope.server("lobby")));
        repo.bannenWennFrei(new BanRecord(b, "Beta", "Grund", "Mod", jetzt, jetzt + 3_600_000L, false,
                ServerScope.global()));

        var entfernt = repo.alleEntbannenNachScope(ServerScope.server("lobby"));
        assertEquals(1, entfernt.size());
        assertTrue(repo.finden(a, ServerScope.server("lobby")).isEmpty());
        assertTrue(repo.finden(b, ServerScope.global()).isPresent());
    }

    @Test
    @DisplayName("V3-PA1: pardon-all ohne Filter entfernt alle (Legacy)")
    void pardonAllOhneFilterEntferntAlle() {
        BanRepository repo = neuesRepo();
        long jetzt = System.currentTimeMillis();
        repo.bannenWennFrei(new BanRecord(UUID.randomUUID(), "A", "G", "M", jetzt, jetzt + 1000L, false,
                ServerScope.server("lobby")));
        repo.bannenWennFrei(new BanRecord(UUID.randomUUID(), "B", "G", "M", jetzt, jetzt + 1000L, false,
                ServerScope.global()));
        assertEquals(2, repo.alleEntbannen().size());
    }

    @Test
    @DisplayName("V3-SC1: BanRecord-Dauer ableitbar (ablauf-erstellt), nicht Restzeit")
    void dauerIstGesamtNichtRest() {
        long erstellt = 1_000_000L;
        long ablauf = erstellt + 7L * 24 * 3_600_000L;
        BanRecord record = new BanRecord(UUID.randomUUID(), "Steve", "Cheating", "Mod",
                erstellt, ablauf, false, ServerScope.global());
        assertEquals(7L * 24 * 3_600_000L, record.ablaufAm() - record.erstelltAm());

        long restNach6Tagen = ablauf - (erstellt + 6L * 24 * 3_600_000L);
        assertEquals(24 * 3_600_000L, restNach6Tagen);
        assertNotEquals(record.ablaufAm() - record.erstelltAm(), restNach6Tagen);
    }

    @Test
    @DisplayName("V3-SC2: Tippfehler-Scope wird geparst (Warnung, kein harter Bruch)")
    void tippfehlerScopeWirdGeparstAberErkennbar() {
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(
                new String[]{"Notch", "7d", "Cheating", "--survial"},
                ServerScope.server("survival"), List.of("lobby", "survival"));

        assertTrue(ergebnis.hatteFlag());
        assertEquals(ServerScope.server("survial"), ergebnis.scope());
    }

    @Test
    @DisplayName("V3-PA2: entbannenAlle ohne Treffer sendet keinen Broadcast")
    void entbannenAlleLeerOhneBroadcast() {
        BanRepository repo = neuesRepo();
        HistoryService history = mock(HistoryService.class);
        BroadcastService broadcast = mock(BroadcastService.class);
        LogWriter log = mock(LogWriter.class);
        ProtectionService schutz = mock(ProtectionService.class);
        BanKickHelper kick = mock(BanKickHelper.class);
        BanService service = new BanService(repo, history, broadcast, log, schutz, kick, mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
        int anzahl = service.entbannenAlle("Grund", "Mod", "konsole", null);
        assertEquals(0, anzahl);
        verify(broadcast, never()).senden(any(), any());
    }
}
