package de.lightrixx.punish.audit;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.BanKickHelper;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.util.ZeitFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditIngameSimulation2Test {

    private IoExecutor io;
    private MapSpeicher speicher;
    private SpielerVerzeichnis verzeichnis;
    private ProtectionService schutz;

    @BeforeEach
    void start() {
        io = new IoExecutor();
        speicher = new MapSpeicher();
        verzeichnis = mock(SpielerVerzeichnis.class);
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        LuckPermsZugang luckPerms = mock(LuckPermsZugang.class);
        when(luckPerms.isAvailable()).thenReturn(true);
        when(offline.istMainThread()).thenReturn(false);
        schutz = new ProtectionService(verzeichnis, offline, luckPerms);
    }

    @AfterEach
    void stop() {
        if (io != null) {
            io.herunterfahren(5, TimeUnit.SECONDS);
            io = null;
        }
    }

    static final class MapSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new HashMap<>();

        @Override
        public Map<String, Object> laden(File datei) {
            Map<String, Object> daten = dateien.get(datei.getName());
            return daten == null ? Map.of() : new HashMap<>(daten);
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            dateien.put(datei.getName(), new HashMap<>(daten));
        }
    }

    private void schutzFrei(UUID uuid) {
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        when(verzeichnis.hatRecht(any(UUID.class), anyString())).thenReturn(false);
        when(verzeichnis.onlineSpieler(any(UUID.class))).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("S1: Temp-Bann → Ablauf → Login ok, History-Eintrag bleibt")
    void szenario1TempBannAblaufMitHistory(@TempDir File ordner) throws Exception {
        HistoryRepository historyRepo = new HistoryRepository(ordner, speicher, io);
        historyRepo.laden();
        HistoryService history = new HistoryService(historyRepo);
        BanRepository repo = new BanRepository(ordner, speicher, io);
        repo.laden();
        BanService service = new BanService(repo, history, mock(BroadcastService.class),
                mock(LogWriter.class), schutz, mock(BanKickHelper.class), verzeichnis);

        UUID ziel = UUID.randomUUID();
        schutzFrei(ziel);

        assertTrue(service.bannen(ziel, "GrieferX", 30L, "Testzweck", "ModA",
                ServerScope.global(), "lobby"), "Temp-Bann muss gesetzt werden");
        assertTrue(service.findeWirksam(ziel, "lobby").isPresent(), "Login-Deny aktiv");

        List<HistoryEntry> verlauf = history.findeSpielerHistory(ziel, 10);
        assertEquals(1, verlauf.size(), "History-Eintrag nach Bann");
        assertNotNull(verlauf.get(0));

        long ablaufFrist = System.currentTimeMillis() + 5_000L;
        while (service.findeWirksam(ziel, "lobby").isPresent()
                && System.currentTimeMillis() < ablaufFrist) {
            Thread.sleep(10L);
        }
        assertTrue(service.findeWirksam(ziel, "lobby").isEmpty(),
                "Nach Zeitablauf muss der Login wieder möglich sein (ohne Pardon)");
        assertEquals(1, history.findeSpielerHistory(ziel, 10).size(),
                "History bleibt auch nach Ablauf erhalten");
    }

    @Test
    @DisplayName("S1: Perm-Bann hält bis Pardon; Restzeit-Anzeige bleibt belegt")
    void szenario1bPermBannBisPardon(@TempDir File ordner) {
        BanRepository repo = new BanRepository(ordner, speicher, io);
        repo.laden();
        BanService service = new BanService(repo, mock(HistoryService.class),
                mock(BroadcastService.class), mock(LogWriter.class), schutz,
                mock(BanKickHelper.class), verzeichnis);

        UUID ziel = UUID.randomUUID();
        schutzFrei(ziel);

        assertTrue(service.permabann(ziel, "CheaterX", "Cheating", "ModB",
                ServerScope.global(), "lobby"));
        Optional<BanRecord> aktiv = service.findeWirksam(ziel, "survival");
        assertTrue(aktiv.isPresent(), "Perm-Bann gilt auf allen Servern");
        assertTrue(aktiv.get().istPermanent(), "Record muss permanent markiert sein");
        assertFalse(ZeitFormat.formatRestzeit(Long.MAX_VALUE).isBlank(),
                "Restzeit-Anzeige darf bei Perm nie leer sein");

        assertTrue(service.entbannen(ziel, "CheaterX", "Admin", ServerScope.global(), "lobby"));
        assertTrue(service.findeWirksam(ziel, "survival").isEmpty(), "Nach Pardon wieder joinbar");
    }

    @Test
    @DisplayName("S2: ban-ip trifft Alt-Device über dieselbe IP; Kanonisierung IPv4-mapped-IPv6")
    void szenario2IpBanAltDeviceUndKanonisierung(@TempDir File ordner) {
        IpBanRepository repo = new IpBanRepository(ordner, speicher, io);
        repo.laden();
        IpBanService service = new IpBanService(repo, mock(HistoryService.class),
                mock(BroadcastService.class), mock(LogWriter.class),
                mock(BanKickHelper.class), schutz, verzeichnis);

        String ip = "10.0.0.1";
        String gemappt = "::ffff:10.0.0.1";
        assertEquals(IpBanRecord.kanonisieren(ip), IpBanRecord.kanonisieren(gemappt),
                "IPv4-mapped-IPv6 muss auf dieselbe kanonische IP abgebildet werden");

        assertTrue(service.ipBannen(ip, "Netzwerk-Sperre", "ModC", ServerScope.global(), "lobby"));

        assertTrue(service.ipFinden(gemappt, ServerScope.global()).isPresent(),
                "Alt-Device mit gemappter IP muss dieselbe Sperre sehen");
        assertTrue(service.ipFinden(ip, ServerScope.global()).isPresent(),
                "Erstes Gerät bleibt gesperrt");
        IpBanRecord record = service.ipFinden(gemappt, ServerScope.global()).orElseThrow();
        assertTrue(record.istAktivFuer(System.currentTimeMillis(), "lobby"));

        Optional<IpBanRecord> entfernt = service.ipEntbannen(ip, "Admin", ServerScope.global(), "lobby");
        assertTrue(entfernt.isPresent(), "unban-ip muss den Record entfernen");
        assertTrue(service.ipFinden(ip, ServerScope.global()).isEmpty(), "Gerät 1 wieder frei");
        assertTrue(service.ipFinden(gemappt, ServerScope.global()).isEmpty(), "Gerät 2 wieder frei");
    }

    @Test
    @DisplayName("S7: report → Duplikat-Sperre → accept → delete; History bleibt konsistent")
    void szenario7ReportWorkflow(@TempDir File ordner) {
        ReportRepository repo = new ReportRepository(ordner, speicher, io);
        repo.laden();
        HistoryRepository historyRepo = new HistoryRepository(ordner, speicher, io);
        historyRepo.laden();
        HistoryService history = new HistoryService(historyRepo);
        Konfiguration config = mock(Konfiguration.class);
        when(config.reportDuplikatSperreMinuten()).thenReturn(10L);
        when(config.reportMaxOffenProReporter()).thenReturn(3);
        ReportService service = new ReportService(repo, config, history,
                mock(BroadcastService.class), mock(LogWriter.class),
                mock(de.lightrixx.punish.service.MessageService.class),
                mock(AuftragsPlaner.class), verzeichnis);

        UUID ziel = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        ReportService.Ergebnis ergebnis = service.melden(ziel, "Troll", reporter,
                "Spieler1", "Beleidigung", "lobby");
        assertTrue(ergebnis.erfolg(), "Report muss angenommen werden");
        long id = ergebnis.report().id();

        ReportService.Ergebnis duplikat = service.melden(ziel, "Troll", reporter,
                "Spieler1", "Beleidigung", "lobby");
        assertFalse(duplikat.erfolg(), "Duplikat-Sperre muss greifen");

        assertTrue(service.schliessen(id, ReportStatus.ANGENOMMEN, "ModA", "").isPresent(),
                "accept muss den Report übernehmen");
        assertEquals(ReportStatus.ANGENOMMEN, service.findenNachId(id).orElseThrow().status());

        service.entfernen(id, "ModA");
        assertTrue(service.findenNachId(id).isEmpty(), "delete entfernt den Report");
        assertEquals(0, service.alle().size(), "Kein Rückstand in der Report-Liste");
        assertFalse(history.findeSpielerHistory(ziel, 10).isEmpty(),
                "History behält REPORT_ADD/REPORT_CLOSE als Rückstand");
    }

    @Test
    @DisplayName("C-SIM7.1 (Fix A-011): zweiter accept fremden Claims wird abgewiesen (erster Claim bleibt)")
    void cS71ClaimKonkurrenz(@TempDir File ordner) {
        ReportRepository repo = new ReportRepository(ordner, speicher, io);
        repo.laden();
        Konfiguration config = mock(Konfiguration.class);
        when(config.reportDuplikatSperreMinuten()).thenReturn(0L);
        ReportService service = new ReportService(repo, config);

        ReportService.Ergebnis ergebnis = service.melden(UUID.randomUUID(), "Troll",
                UUID.randomUUID(), "Spieler1", "Grund", "lobby");
        assertTrue(ergebnis.erfolg());
        long id = ergebnis.report().id();

        service.schliessen(id, ReportStatus.ANGENOMMEN, "ModA", "");

        ReportService.ClaimKonflikt konflikt = assertThrows(ReportService.ClaimKonflikt.class,
                () -> service.schliessen(id, ReportStatus.ANGENOMMEN, "ModB", ""));
        assertEquals("ModA", konflikt.aktuellerBearbeiter());
        assertEquals("ModA", service.findenNachId(id).orElseThrow().bearbeiter(),
                "Fix A-011: erster Claim bleibt bestehen");

        assertDoesNotThrow(() -> service.schliessen(id, ReportStatus.ANGENOMMEN, "ModA", ""));
    }

    @Test
    @DisplayName("S12: Racing zweier Mods über StoreLock ergibt deterministischen, konsistenten Zustand")
    void szenario12RacingZweiMods(@TempDir File ordner) throws Exception {
        BanRepository repo = new BanRepository(ordner, speicher, io);
        repo.laden();
        BanService service = new BanService(repo, mock(HistoryService.class),
                mock(BroadcastService.class), mock(LogWriter.class), schutz,
                mock(BanKickHelper.class), verzeichnis);

        UUID ziel = UUID.randomUUID();
        schutzFrei(ziel);

        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean fehler = new AtomicBoolean(false);
        Runnable modA = () -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    service.bannen(ziel, "Ziel", 60_000L, "A", "ModA", ServerScope.global(), "lobby");
                    service.entbannen(ziel, "Ziel", "ModA", ServerScope.global(), "lobby");
                }
            } catch (Exception e) {
                fehler.set(true);
            }
        };
        Runnable modB = () -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    service.bannen(ziel, "Ziel", 60_000L, "B", "ModB",
                            ServerScope.server("lobby"), "lobby");
                }
            } catch (Exception e) {
                fehler.set(true);
            }
        };
        Thread tA = new Thread(modA, "ModA");
        Thread tB = new Thread(modB, "ModB");
        tA.start();
        tB.start();
        start.countDown();
        tA.join(10_000);
        tB.join(10_000);
        assertFalse(tA.isAlive(), "ModA-Thread hängt (Lock-Deadlock?) – Zustand danach ungeprüft");
        assertFalse(tB.isAlive(), "ModB-Thread hängt (Lock-Deadlock?) – Zustand danach ungeprüft");
        assertFalse(fehler.get(), "Racing darf keine Exception auslösen");

        assertTrue(repo.alle().size() <= 2, "Maximal ein Record je Scope: " + repo.alle().size());
        Optional<BanRecord> erste = service.findeWirksam(ziel, "lobby");
        Optional<BanRecord> zweite = service.findeWirksam(ziel, "lobby");
        assertEquals(erste.isPresent(), zweite.isPresent(), "Lese-Ergebnis muss stabil sein");
        assertEquals(erste.map(BanRecord::ausfuehrer), zweite.map(BanRecord::ausfuehrer),
                "Wiederholtes Lesen muss denselben Zustand liefern (deterministisch)");

        service.entbannen(ziel, "Ziel", "Schluss", ServerScope.global(), "lobby");
        service.entbannen(ziel, "Ziel", "Schluss", ServerScope.server("lobby"), "lobby");
        assertTrue(repo.alle().isEmpty(), "Nach Aufräumen kein Rückstand");
    }
}
