package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.ServerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanDoppelAusfuehrungTest {

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
    private BanRepository repository;

    @BeforeEach
    void setup() {
        ioExecutor = new IoExecutor();
        repository = new BanRepository(temp, new FakeSpeicher(), ioExecutor);
    }

    @AfterEach
    void teardown() {
        ioExecutor.herunterfahren(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A-005: zwei gleichzeitige Banns desselben Ziels ergeben genau einen Erfolg")
    void doppelterBanNurEinmalErfolgreich() throws Exception {
        UUID ziel = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        BanRecord erster = new BanRecord(ziel, "Ziel", "Hack", "ModA", jetzt, jetzt + 3600000L, false,
                ServerScope.global());
        BanRecord zweiter = new BanRecord(ziel, "Ziel", "Hack", "ModB", jetzt, jetzt + 3600000L, false,
                ServerScope.global());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch fertig = new CountDownLatch(2);
        AtomicInteger erfolge = new AtomicInteger();
        List<Throwable> fehler = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (BanRecord kandidat : new BanRecord[]{erster, zweiter}) {
            pool.execute(() -> {
                try {
                    start.await();
                    if (repository.bannenWennFrei(kandidat)) {
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
        assertTrue(fertig.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(fehler.isEmpty(), () -> "Worker warfen: " + fehler);
        assertEquals(1, erfolge.get());
    }
}
