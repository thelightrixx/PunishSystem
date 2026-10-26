package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HistoryNebenlaeufigkeitTest {

    static final class FakeSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> laden(File datei) {
            Map<String, Object> daten = dateien.get(datei.getAbsolutePath());
            return daten == null ? Map.of() : daten;
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
    private HistoryRepository repository;

    @BeforeEach
    void setup() {
        ioExecutor = new IoExecutor();
        speicher = new FakeSpeicher();
        repository = new HistoryRepository(temp, speicher, ioExecutor);
    }

    @AfterEach
    void teardown() {
        ioExecutor.herunterfahren(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A-002: gleichzeitige Einträge desselben Ziels gehen nicht verloren (Snapshot unter Lock)")
    void gleichzeitigeEintraegeGehenNichtVerloren() throws Exception {
        UUID ziel = UUID.randomUUID();
        int threads = 8;
        int proThread = 25;
        int gesamt = threads * proThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch fertig = new CountDownLatch(gesamt);
        for (int i = 0; i < gesamt; i++) {
            final int nummer = i;
            pool.execute(() -> {
                try {
                    start.await();
                    long jetzt = System.currentTimeMillis();
                    repository.hinzufuegen(new HistoryEntry(1000L + nummer,
                            (nummer % 2 == 0) ? HistoryType.BAN : HistoryType.MUTE,
                            "Mod" + nummer, "Ziel", "Grund " + nummer, jetzt,
                            jetzt + 60000L, true),
                            ziel);
                } catch (Exception e) {
                    fail("hinzufuegen warf: " + e);
                } finally {
                    fertig.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(fertig.await(30, TimeUnit.SECONDS), "alle hinzufuegen-Aufrufe müssen enden");
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(ioExecutor.warteAufIdle(10, TimeUnit.SECONDS), "I/O-Queue muss leer laufen");

        assertEquals(gesamt, repository.finden(ziel, gesamt + 10).size(),
                "alle Einträge müssen im Memory stehen");
        String shardPfad = new File(new File(temp, "history"), ziel + ".yml").getAbsolutePath();
        Map<String, Object> shard = speicher.dateien.get(shardPfad);
        assertNotNull(shard, "Shard-Datei muss persistiert sein");
        Object eintraege = shard.get("eintraege");
        assertInstanceOf(List.class, eintraege);
        assertEquals(gesamt, ((List<?>) eintraege).size(),
                "A-002: der letzte persistierte Snapshot muss alle Einträge enthalten");
    }

    @Test
    @DisplayName("A09-1: archivierte YAML-History wird für die Migration gelesen")
    void archivEintraegeWerdenGelesen() throws Exception {
        UUID ziel = UUID.randomUUID();
        File archiv = new File(new File(temp, "history"), "archive");
        assertTrue(archiv.mkdirs(), "Archivordner muss anlegbar sein");
        File datei = new File(archiv, ziel + "-1700000000000-1-abcd1234.yml");
        assertTrue(datei.createNewFile(), "Archivdatei muss anlegbar sein");

        Map<String, Object> roh = new LinkedHashMap<>();
        roh.put("id", 42L);
        roh.put("typ", HistoryType.BAN.name());
        roh.put("ausfuehrer", "Console");
        roh.put("ziel", "Testspieler");
        roh.put("grund", "Archiv-Grund");
        roh.put("zeitstempel", 1_000_000L);
        roh.put("aktiv", true);
        roh.put("scope", "global");
        roh.put("herkunft", "global");
        speicher.dateien.put(datei.getAbsolutePath(), Map.of("eintraege", List.of(roh)));

        Map<UUID, List<HistoryEntry>> bestand = repository.archivEintraege();

        assertNotNull(bestand.get(ziel), "Archiv muss dem Ziel zugeordnet werden");
        assertEquals(1, bestand.get(ziel).size());
        assertEquals(42L, bestand.get(ziel).get(0).id());
        assertEquals(HistoryType.BAN, bestand.get(ziel).get(0).typ());
    }
}
