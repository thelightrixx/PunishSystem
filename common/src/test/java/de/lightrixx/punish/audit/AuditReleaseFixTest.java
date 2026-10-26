package de.lightrixx.punish.audit;

import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditReleaseFixTest {

    @TempDir
    File temp;

    private IoExecutor ioExecutor;

    @AfterEach
    void teardown() {
        if (ioExecutor != null) {
            ioExecutor.herunterfahren(5, TimeUnit.SECONDS);
        }
    }

    static final class FakeSpeicher implements YamlSpeicher {
        @Override
        public Map<String, Object> laden(File datei) {
            return Map.of();
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
        }
    }

    private ReportRepository repository() {
        ioExecutor = new IoExecutor();
        return new ReportRepository(temp, new FakeSpeicher(), ioExecutor);
    }

    private static ReportRecord offen(long id) {
        return new ReportRecord(id, UUID.randomUUID(), "Ziel" + id, UUID.randomUUID(),
                "Reporter" + id, "Grund " + id, "lobby", System.currentTimeMillis(),
                ReportStatus.OFFEN, "", 0L, "");
    }

    private static ReportRecord geschlossen(long id) {
        return new ReportRecord(id, UUID.randomUUID(), "Ziel" + id, UUID.randomUUID(),
                "Reporter" + id, "Grund " + id, "lobby", System.currentTimeMillis(),
                ReportStatus.ERLEDIGT, "Mod", System.currentTimeMillis(), "ok");
    }

    @SuppressWarnings("unchecked")
    private static void fuelle(ReportRepository repository, List<ReportRecord> eintraege) throws Exception {
        Field feld = ReportRepository.class.getDeclaredField("reports");
        feld.setAccessible(true);
        ConcurrentHashMap<Long, ReportRecord> karte = new ConcurrentHashMap<>();
        for (ReportRecord eintrag : eintraege) {
            karte.put(eintrag.id(), eintrag);
        }
        feld.set(repository, karte);
    }

    @Test
    void volleOffeneReportsWerdenAbgewiesenStattVerdraengt() throws Exception {
        ReportRepository repository = repository();
        List<ReportRecord> eintraege = new ArrayList<>(10000);
        for (long id = 1; id <= 10000; id++) {
            eintraege.add(offen(id));
        }
        fuelle(repository, eintraege);

        assertThrows(IllegalStateException.class, () -> repository.hinzufuegen(
                UUID.randomUUID(), "Neu", UUID.randomUUID(), "Reporter", "Grund", "lobby"));
        assertEquals(10000, repository.alle().size());
        assertEquals(10000, repository.offene().size());
    }

    @Test
    void geschlosseneReportsWerdenWeiterVerdraengt() throws Exception {
        ReportRepository repository = repository();
        List<ReportRecord> eintraege = new ArrayList<>(10000);
        for (long id = 1; id <= 9990; id++) {
            eintraege.add(geschlossen(id));
        }
        for (long id = 9991; id <= 10000; id++) {
            eintraege.add(offen(id));
        }
        fuelle(repository, eintraege);

        ReportRecord neu = repository.hinzufuegen(
                UUID.randomUUID(), "Neu", UUID.randomUUID(), "Reporter", "Grund", "lobby");
        assertNotNull(neu);
        assertEquals(10 + 1, repository.offene().size());
        assertTrue(repository.findenNachId(9991L).isPresent());
    }

    @Test
    void mySqlVerdraengungLoeschtVerdraengteZeilen() throws Exception {
        ioExecutor = new IoExecutor();
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.prepareStatement(anyString())).thenReturn(statement);
        ReportRepository repository = new ReportRepository(mysql, ioExecutor);
        Field zaehlerFeld = ReportRepository.class.getDeclaredField("zaehler");
        zaehlerFeld.setAccessible(true);
        ((AtomicLong) zaehlerFeld.get(repository)).set(10000L);

        List<ReportRecord> eintraege = new ArrayList<>(10000);
        for (long id = 1; id <= 9990; id++) {
            eintraege.add(geschlossen(id));
        }
        for (long id = 9991; id <= 10000; id++) {
            eintraege.add(offen(id));
        }
        fuelle(repository, eintraege);

        ReportRecord neu = repository.hinzufuegen(
                UUID.randomUUID(), "Neu", UUID.randomUUID(), "Reporter", "Grund", "lobby");
        assertNotNull(neu);
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        verify(statement).setLong(1, 1L);
        verify(statement).setLong(1, 100L);
        verify(statement, never()).setLong(1, 9991L);
    }

    static final class StubConfig implements de.lightrixx.punish.core.Konfiguration {
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
        public java.time.ZoneId anzeigeZone() {
            return java.time.ZoneId.of("Europe/Berlin");
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

    @Test
    void paralleleDuplikatReportsNurEinErfolg() throws Exception {
        ioExecutor = new IoExecutor();
        ReportRepository repository = new ReportRepository(temp, new FakeSpeicher(), ioExecutor);
        ReportService service = new ReportService(repository, new StubConfig(0, 10, 10));
        UUID reporter = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch fertig = new CountDownLatch(threads);
        AtomicInteger erfolge = new AtomicInteger();
        List<Throwable> fehler = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    var ergebnis = service.melden(ziel, "Steve", reporter,
                            "Alex", "Hacking", "lobby");
                    if (ergebnis.erfolg()) {
                        erfolge.incrementAndGet();
                    }
                } catch (Throwable t) {
                    fehler.add(t);
                } finally {
                    fertig.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(fertig.await(15, TimeUnit.SECONDS), "Threads hängen (Lock-Problem?).");
        assertTrue(fehler.isEmpty(), () -> "Worker-Fehler dürfen nicht maskiert werden: " + fehler);
        assertEquals(1, erfolge.get());
    }

    @Test
    void vorabPlatzhalterErkannt() {
        UUID platzhalter = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:Steve").getBytes(StandardCharsets.UTF_8));
        assertTrue(PlayerCache.istVorabPlatzhalter(platzhalter, "Steve"));
        assertFalse(PlayerCache.istVorabPlatzhalter(UUID.randomUUID(), "Steve"));
        assertFalse(PlayerCache.istVorabPlatzhalter(platzhalter, "Alex"));
        assertFalse(PlayerCache.istVorabPlatzhalter(null, "Steve"));
        assertFalse(PlayerCache.istVorabPlatzhalter(platzhalter, " "));
    }

    private static UUID platzhalterFuer(String name) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private BanService banService(File ordner, IoExecutor io) {
        BanRepository repo = new BanRepository(ordner, new FakeSpeicher(), io);
        repo.laden();
        var schutz = org.mockito.Mockito.mock(de.lightrixx.punish.service.ProtectionService.class);
        try {
            org.mockito.Mockito.when(schutz.istGeschuetzt(
                    org.mockito.ArgumentMatchers.any(UUID.class),
                    org.mockito.ArgumentMatchers.any())).thenReturn(false);
        } catch (Exception e) {
            fail("Schutz-Mock unerwartet fehlgeschlagen.");
        }
        return new BanService(repo,
                org.mockito.Mockito.mock(de.lightrixx.punish.service.HistoryService.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.service.BroadcastService.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.LogWriter.class),
                schutz,
                org.mockito.Mockito.mock(de.lightrixx.punish.service.BanKickHelper.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
    }

    @Test
    void vorabBannVomPlatzhalterWirdMigriert(@TempDir File ordner) {
        IoExecutor nutzIo = new IoExecutor();
        try {
            BanService service = banService(ordner, nutzIo);
            UUID platzhalter = platzhalterFuer("NeuSpieler");
            UUID echt = UUID.randomUUID();
            assertTrue(service.bannen(platzhalter, "NeuSpieler", 3_600_000L, "Test",
                    "Mod", ServerScope.global(), "lobby"));
            var migriert = service.migriereVorabBann(platzhalter, echt, "NeuSpieler");
            assertTrue(migriert.isPresent());
            assertTrue(service.findeAbdeckend(echt, ServerScope.global()).isPresent());
        } finally {
            nutzIo.herunterfahren(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void vorabBannVonEchterUuidWirdNichtMigriert(@TempDir File ordner) {
        IoExecutor nutzIo = new IoExecutor();
        try {
            BanService service = banService(ordner, nutzIo);
            UUID vormals = UUID.randomUUID();
            UUID neu = UUID.randomUUID();
            assertTrue(service.bannen(vormals, "WechselName", 3_600_000L, "Test",
                    "Mod", ServerScope.global(), "lobby"));
            var migriert = service.migriereVorabBann(vormals, neu, "WechselName");
            assertTrue(migriert.isEmpty(), "Fremde echte UUID darf nichts erben (A-002).");
            assertTrue(service.findeAbdeckend(neu, ServerScope.global()).isEmpty());
            assertTrue(service.findeAbdeckend(vormals, ServerScope.global()).isPresent());
        } finally {
            nutzIo.herunterfahren(5, TimeUnit.SECONDS);
        }
    }
}
