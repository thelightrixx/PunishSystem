package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.ReportStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReportRepositoryTest {

    static final class FakeSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new ConcurrentHashMap<>();
        boolean fehler;

        @Override
        public Map<String, Object> laden(File datei) {
            Map<String, Object> daten = dateien.get(datei.getAbsolutePath());
            return daten == null ? Map.of() : daten;
        }

        @Override
        public LadeStatus ladenMitStatus(File datei) {
            return new LadeStatus(laden(datei), true, fehler);
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            dateien.put(datei.getAbsolutePath(), daten);
        }
    }

    @TempDir
    File temp;

    private IoExecutor ioExecutor;
    private FakeSpeicher speicher;
    private ReportRepository repository;

    @BeforeEach
    void setup() {
        ioExecutor = new IoExecutor();
        speicher = new FakeSpeicher();
        repository = new ReportRepository(temp, speicher, ioExecutor);
    }

    @AfterEach
    void teardown() {
        ioExecutor.herunterfahren(5, TimeUnit.SECONDS);
    }

    @Test
    void hinzufuegenVergibtFortlaufendeIds() {
        UUID ziel = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        ReportRecord eins = repository.hinzufuegen(ziel, "Steve", reporter, "Alex", "Hacking", "lobby");
        ReportRecord zwei = repository.hinzufuegen(ziel, "Steve", reporter, "Alex", "Spamming", "lobby");
        assertEquals(1L, eins.id());
        assertEquals(2L, zwei.id());
        assertEquals(ReportStatus.OFFEN, eins.status());
        assertTrue(eins.istOffen());
    }

    @Test
    void statusSetzenUndEntfernen() {
        UUID ziel = UUID.randomUUID();
        ReportRecord neu = repository.hinzufuegen(ziel, "Steve", UUID.randomUUID(), "Alex", "Hacking", "lobby");
        Optional<ReportRecord> geschlossen =
                repository.statusSetzen(neu.id(), ReportStatus.ERLEDIGT, "Mod", "geprüft");
        assertTrue(geschlossen.isPresent());
        assertEquals(ReportStatus.ERLEDIGT, geschlossen.get().status());
        assertFalse(geschlossen.get().istOffen());
        assertEquals(0, repository.offene().size());
        assertEquals(1, repository.alle().size());
        Optional<ReportRecord> entfernt = repository.entfernen(neu.id());
        assertTrue(entfernt.isPresent());
        assertTrue(repository.alle().isEmpty());
    }

    @Test
    void duplikatErkennung() {
        UUID ziel = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        repository.hinzufuegen(ziel, "Steve", reporter, "Alex", "Hacking", "lobby");
        long seit = System.currentTimeMillis() - 60_000L;
        assertTrue(repository.findeDuplikat(reporter, ziel, seit).isPresent());
        assertEquals(1, repository.zaehleOffeneVonReporter(reporter));
        assertTrue(repository.findeDuplikat(UUID.randomUUID(), ziel, seit).isEmpty());
    }

    @Test
    void ladenRoundtrip() {
        UUID ziel = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();
        repository.hinzufuegen(ziel, "Steve", reporter, "Alex", "Hacking", "lobby");
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));
        ReportRepository neu = new ReportRepository(temp, speicher, ioExecutor);
        neu.laden();
        assertEquals(1, neu.alle().size());
        Optional<ReportRecord> gefunden = neu.findenNachId(1L);
        assertTrue(gefunden.isPresent());
        assertEquals("Steve", gefunden.get().zielName());
        assertEquals("Alex", gefunden.get().reporterName());
        assertEquals("Hacking", gefunden.get().grund());
    }

    @Test
    void leererBestandWirdUebernommen() {
        repository.hinzufuegen(UUID.randomUUID(), "Steve", UUID.randomUUID(), "Alex", "Hacking", "lobby");
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));
        speicher.dateien.clear();

        repository.laden();

        assertEquals(0, repository.alle().size(),
                "Ein gültig leerer Bestand muss übernommen werden (kein Wiederauferstehen gelöschter Reports)");
    }

    @Test
    void lesefehlerBehaeltBestand() {
        repository.hinzufuegen(UUID.randomUUID(), "Steve", UUID.randomUUID(), "Alex", "Hacking", "lobby");
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));
        speicher.dateien.clear();
        speicher.fehler = true;

        repository.laden();

        assertEquals(1, repository.alle().size(),
                "Ein Lesefehler darf den Bestand nicht leeren (Fail-Closed)");
    }

    @Test
    void korrupteEintraegeWerdenQuarantaeniert() {
        UUID ziel = UUID.randomUUID();
        repository.hinzufuegen(ziel, "Steve", UUID.randomUUID(), "Alex", "Hacking", "lobby");
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));
        Map<String, Object> daten = speicher.dateien.values().iterator().next();
        @SuppressWarnings("unchecked")
        java.util.List<Object> liste = (java.util.List<Object>) daten.get("reports");
        liste.add("kein-map-eintrag");
        speicher.dateien.put(new File(temp, "reports.yml").getAbsolutePath(), daten);
        ReportRepository neu = new ReportRepository(temp, speicher, ioExecutor);
        neu.laden();
        assertEquals(1, neu.alle().size());

        neu.statusSetzen(1L, ReportStatus.ERLEDIGT, "Mod", "geprüft");
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        @SuppressWarnings("unchecked")
        java.util.List<Object> gespeichert = (java.util.List<Object>) speicher.dateien
                .get(new File(temp, "reports.yml").getAbsolutePath())
                .get("reports");
        assertEquals(2, gespeichert.size(),
                "Der korrupte Roheintrag muss in der Quarantäne erhalten bleiben: " + gespeichert);
        assertTrue(gespeichert.contains("kein-map-eintrag"),
                "Roheintrag fehlt nach dem Speichern: " + gespeichert);

        ReportRepository nachReload = new ReportRepository(temp, speicher, ioExecutor);
        nachReload.laden();
        assertEquals(1, nachReload.alle().size());
        assertEquals("kein-map-eintrag",
                ((java.util.List<?>) speicher.dateien
                        .get(new File(temp, "reports.yml").getAbsolutePath()).get("reports"))
                        .stream()
                        .filter(eintrag -> !(eintrag instanceof java.util.Map<?, ?>))
                        .findFirst()
                        .orElseThrow(),
                "Quarantäne-Eintrag muss auch nach einem Reload roh vorliegen.");
    }
}
