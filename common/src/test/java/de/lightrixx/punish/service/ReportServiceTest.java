package de.lightrixx.punish.service;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    static final class FakeSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> laden(File datei) {
            return dateien.getOrDefault(datei.getAbsolutePath(), Map.of());
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            dateien.put(datei.getAbsolutePath(), daten);
        }
    }

    static final class StubConfig implements Konfiguration {
        final long cooldown;
        final int maxOffen;
        final long duplikat;

        StubConfig(long cooldown, int maxOffen, long duplikat) {
            this.cooldown = cooldown;
            this.maxOffen = maxOffen;
            this.duplikat = duplikat;
        }

        @Override
        public String praefix() {
            return "[Punish]";
        }

        @Override
        public String nachricht(String schluessel) {
            return schluessel;
        }

        @Override
        public int verstoesseMaxPunkte() {
            return 6;
        }

        @Override
        public String verstoesseAutoBannDauer() {
            return "7d";
        }

        @Override
        public de.lightrixx.punish.model.VerstossStufe verstossStandardStufe() {
            return de.lightrixx.punish.model.VerstossStufe.standard();
        }

        @Override
        public long verstossStufenDauerMillis(de.lightrixx.punish.model.VerstossStufe stufe) {
            return 1000L;
        }

        @Override
        public int verstossStufenPunkte(de.lightrixx.punish.model.VerstossStufe stufe) {
            return 1;
        }

        @Override
        public LimitSnapshot limitSnapshot() {
            return new LimitSnapshot(6, "7d");
        }

        @Override
        public long timeoutMaxMinuten() {
            return 1440L;
        }

        @Override
        public long muteMaxMinuten() {
            return 43200L;
        }

        @Override
        public long banMaxMinuten() {
            return 525600L;
        }

        @Override
        public boolean bestaetigungPardonAll() {
            return true;
        }

        @Override
        public boolean bestaetigungKickAll() {
            return true;
        }

        @Override
        public boolean bestaetigungBanAll() {
            return true;
        }

        @Override
        public boolean schutzFailClosed() {
            return true;
        }

        @Override
        public boolean offlineAllowNeverSeen() {
            return true;
        }

        @Override
        public ZoneId anzeigeZone() {
            return ZoneId.of("Europe/Berlin");
        }

        @Override
        public int historyEintraegeProSeite() {
            return 10;
        }

        @Override
        public List<String> tabDauerListe() {
            return List.of();
        }

        @Override
        public List<String> tabGrundListe() {
            return List.of();
        }

        @Override
        public List<String> muteBlockierteBefehle() {
            return List.of();
        }

        @Override
        public String standardGrund(String aktion) {
            return "Regelverstoß";
        }

        @Override
        public String screen(String typ) {
            return typ;
        }

        @Override
        public boolean broadcastAktiv(String typ) {
            return false;
        }

        @Override
        public String broadcastNachricht(String typ) {
            return "";
        }

        @Override
        public String serverName() {
            return "unbekannt";
        }

        @Override
        public List<String> bekannteServer() {
            return List.of();
        }

        @Override
        public de.lightrixx.punish.model.ServerScope standardScope() {
            return de.lightrixx.punish.model.ServerScope.unbekannt();
        }

        @Override
        public long reportCooldownSekunden() {
            return cooldown;
        }

        @Override
        public int reportMaxOffenProReporter() {
            return maxOffen;
        }

        @Override
        public long reportDuplikatSperreMinuten() {
            return duplikat;
        }
    }

    @TempDir
    File temp;

    private IoExecutor ioExecutor;

    @AfterEach
    void teardown() {
        if (ioExecutor != null) {
            ioExecutor.herunterfahren(5, TimeUnit.SECONDS);
        }
    }

    private ReportService service(long cooldown, int maxOffen, long duplikat) {
        ioExecutor = new IoExecutor();
        ReportRepository repository = new ReportRepository(temp, new FakeSpeicher(), ioExecutor);
        return new ReportService(repository, new StubConfig(cooldown, maxOffen, duplikat));
    }

    @Test
    void meldenErfolgreich() {
        ReportService service = service(60, 3, 10);
        var ergebnis = service.melden(UUID.randomUUID(), "Steve", UUID.randomUUID(),
                "Alex", "Hacking", "lobby");
        assertTrue(ergebnis.erfolg());
        assertNotNull(ergebnis.report());
        assertEquals(1, service.anzahlOffen());
    }

    @Test
    void selbstReportAbgelehnt() {
        ReportService service = service(0, 3, 0);
        UUID uuid = UUID.randomUUID();
        var ergebnis = service.melden(uuid, "Steve", uuid, "Steve", "Hacking", "lobby");
        assertFalse(ergebnis.erfolg());
        assertEquals(MessageKeys.REPORT_SELBST, ergebnis.fehler());
    }

    @Test
    void cooldownGreift() {
        ReportService service = service(60, 10, 0);
        UUID reporter = UUID.randomUUID();
        var erstes = service.melden(UUID.randomUUID(), "Steve", reporter, "Alex", "Hacking", "lobby");
        assertTrue(erstes.erfolg());
        var zweites = service.melden(UUID.randomUUID(), "Herobrine", reporter, "Alex", "Spamming", "lobby");
        assertFalse(zweites.erfolg());
        assertEquals(MessageKeys.REPORT_COOLDOWN, zweites.fehler());
        assertTrue(zweites.cooldownRestSekunden() > 0);
    }

    @Test
    void duplikatGreift() {
        ReportService service = service(0, 10, 10);
        UUID reporter = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        assertTrue(service.melden(ziel, "Steve", reporter, "Alex", "Hacking", "lobby").erfolg());
        var duplikat = service.melden(ziel, "Steve", reporter, "Alex", "Hacking nochmal", "lobby");
        assertFalse(duplikat.erfolg());
        assertEquals(MessageKeys.REPORT_DUPLIKAT, duplikat.fehler());
    }

    @Test
    void limitGreift() {
        ReportService service = service(0, 1, 0);
        UUID reporter = UUID.randomUUID();
        assertTrue(service.melden(UUID.randomUUID(), "Steve", reporter, "Alex", "Hacking", "lobby").erfolg());
        var zweites = service.melden(UUID.randomUUID(), "Herobrine", reporter, "Alex", "Spamming", "lobby");
        assertFalse(zweites.erfolg());
        assertEquals(MessageKeys.REPORT_LIMIT, zweites.fehler());
    }

    @Test
    void schliessenAendertStatus() {
        ReportService service = service(0, 3, 0);
        var erstellt = service.melden(UUID.randomUUID(), "Steve", UUID.randomUUID(),
                "Alex", "Hacking", "lobby");
        assertTrue(erstellt.erfolg());
        var geschlossen = service.schliessen(erstellt.report().id(), ReportStatus.ERLEDIGT, "Mod", "ok");
        assertTrue(geschlossen.isPresent());
        assertEquals(ReportStatus.ERLEDIGT, geschlossen.get().status());
        assertEquals(0, service.anzahlOffen());
    }

    @Test
    void geschlossenerReportKannNichtNeuBewertetWerden() {
        ReportService service = service(0, 3, 0);
        var erstellt = service.melden(UUID.randomUUID(), "Steve", UUID.randomUUID(),
                "Alex", "Hacking", "lobby");
        assertTrue(erstellt.erfolg());
        long id = erstellt.report().id();
        assertTrue(service.schliessen(id, ReportStatus.ERLEDIGT, "Mod", "ok").isPresent());
        ReportService.BereitsGeschlossen fehler = assertThrows(ReportService.BereitsGeschlossen.class,
                () -> service.schliessen(id, ReportStatus.ABGELEHNT, "Mod", "doch nicht"));
        assertEquals(ReportStatus.ERLEDIGT, fehler.status());
        assertEquals(ReportStatus.ERLEDIGT, service.findenNachId(id).orElseThrow().status());
    }

    @Test
    void loeschenSchreibtHistory(@TempDir File ordner) {
        ioExecutor = new IoExecutor();
        ReportRepository repository = new ReportRepository(temp, new FakeSpeicher(), ioExecutor);
        de.lightrixx.punish.storage.HistoryRepository historyRepo =
                new de.lightrixx.punish.storage.HistoryRepository(ordner, new FakeSpeicher(), ioExecutor);
        historyRepo.laden();
        de.lightrixx.punish.service.HistoryService history =
                new de.lightrixx.punish.service.HistoryService(historyRepo);
        ReportService service = new ReportService(repository, new StubConfig(0, 3, 0),
                history, null, null, null, null, null);
        UUID ziel = UUID.randomUUID();
        var erstellt = service.melden(ziel, "Steve", UUID.randomUUID(), "Alex", "Hacking", "lobby");
        assertTrue(erstellt.erfolg());
        long id = erstellt.report().id();
        assertTrue(service.entfernen(id, "Mod").isPresent());
        assertTrue(service.findenNachId(id).isEmpty());
        boolean geloeschteSpur = history.findeSpielerHistory(ziel, 10).stream()
                .anyMatch(e -> e.typ() == de.lightrixx.punish.model.HistoryType.REPORT_CLOSE
                        && e.grund() != null && e.grund().contains("gelöscht"));
        assertTrue(geloeschteSpur, "Löschung muss als REPORT_CLOSE in der History stehen");
    }
}
