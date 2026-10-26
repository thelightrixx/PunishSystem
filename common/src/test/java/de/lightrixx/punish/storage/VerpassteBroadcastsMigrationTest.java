package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.VerpassteNachricht;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerpassteBroadcastsMigrationTest {

    private IoExecutor io;
    private MysqlVerbindung mysql;
    private VerpassteBroadcastsRepository repository;

    @BeforeEach
    void setup() throws Exception {
        io = new IoExecutor();
        mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.prepareStatement(anyString())).thenReturn(prepared);
        when(verbindung.getAutoCommit()).thenReturn(true);
        repository = new VerpassteBroadcastsRepository(mysql, io);
    }

    @AfterEach
    void teardown() {
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    @Test
    void migrationImportUebernimmtEintraegeUndCursor() {
        UUID uuid = UUID.randomUUID();
        List<VerpassteNachricht> eintraege = List.of(
                new VerpassteNachricht(5L, "ban", "eins", 1000L),
                new VerpassteNachricht(7L, "mute", "zwei", 2000L));

        int anzahl = repository.migrationImport(eintraege, Map.of(uuid, 3000L));

        assertEquals(2, anzahl);
        assertEquals(2, repository.anzahl());
        assertEquals(3000L, repository.cursorFuer(uuid).orElseThrow());
        assertEquals(2, repository.findeNach(0L, 100).size());
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
    }

    @Test
    void migrationImportLehntUngueltigeEingabenAb() {
        assertThrows(NullPointerException.class, () -> repository.migrationImport(null, Map.of()));
        assertThrows(NullPointerException.class, () -> repository.migrationImport(List.of(), null));
        VerpassteBroadcastsRepository yamlModus =
                new VerpassteBroadcastsRepository(new java.io.File("daten"),
                        new YamlSpeicher() {
                            @Override
                            public Map<String, Object> laden(java.io.File datei) {
                                return Map.of();
                            }

                            @Override
                            public void speichern(Map<String, Object> daten, java.io.File datei) {
                            }
                        }, io);
        assertThrows(IllegalStateException.class,
                () -> yamlModus.migrationImport(List.of(), Map.of()));
    }

    @Test
    void migrationImportUnterLastBleibtKonsistent() throws Exception {
        int threads = 4;
        int proThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch fertig = new CountDownLatch(threads + 1);
        List<Throwable> fehler = java.util.Collections.synchronizedList(new ArrayList<>());
        UUID cursorUuid = UUID.randomUUID();

        for (int t = 0; t < threads; t++) {
            final int basis = 10_000 + t * proThread;
            Thread faden = new Thread(() -> {
                try {
                    start.await();
                    List<VerpassteNachricht> batch = new ArrayList<>();
                    for (int i = 0; i < proThread; i++) {
                        batch.add(new VerpassteNachricht(basis + i, "ban", "x", 1000L));
                    }
                    repository.migrationImport(batch, Map.of(cursorUuid, (long) basis));
                    for (int i = 1; i <= proThread; i++) {
                        repository.cursorSetzen(cursorUuid, i);
                    }
                } catch (Throwable e) {
                    fehler.add(e);
                } finally {
                    fertig.countDown();
                }
            });
            faden.start();
        }
        Thread leser = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    repository.findeNach(0L, 100);
                    repository.momentaufnahme();
                }
            } catch (Throwable e) {
                fehler.add(e);
            } finally {
                fertig.countDown();
            }
        });
        leser.start();

        start.countDown();
        assertTrue(fertig.await(30, TimeUnit.SECONDS));
        assertTrue(fehler.isEmpty(), fehler.toString());
        assertEquals(threads * proThread, repository.anzahl());
        long erwartet = 10_000 + (threads - 1) * proThread;
        assertEquals(erwartet, repository.cursorFuer(cursorUuid).orElseThrow());
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        verify(mysql, atLeastOnce()).verbindung();
    }
}
