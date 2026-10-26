package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.VerpassteNachricht;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VerpassteBroadcastsRepositoryTest {

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

    @TempDir
    File temp;

    private IoExecutor ioExecutor;
    private FakeSpeicher speicher;
    private VerpassteBroadcastsRepository repository;

    @BeforeEach
    void setup() {
        ioExecutor = new IoExecutor();
        speicher = new FakeSpeicher();
        repository = new VerpassteBroadcastsRepository(temp, speicher, ioExecutor);
    }

    @AfterEach
    void teardown() {
        ioExecutor.herunterfahren(5, TimeUnit.SECONDS);
    }

    @Test
    void ablegenVergibtFortlaufendeIds() {
        VerpassteNachricht eins = repository.ablegen("ban", "<red>Test eins");
        VerpassteNachricht zwei = repository.ablegen("mute", "<yellow>Test zwei");
        assertEquals(1L, eins.id());
        assertEquals(2L, zwei.id());
        assertEquals(2, repository.anzahl());
    }

    @Test
    void ablegenLehntLeerenTypAb() {
        assertThrows(IllegalArgumentException.class, () -> repository.ablegen("  ", "text"));
        assertThrows(IllegalArgumentException.class, () -> repository.ablegen(null, "text"));
        assertEquals(0, repository.anzahl());
    }

    @Test
    void findeNachFiltertNachZeitstempelUndLimit() {
        repository.ablegen("ban", "eins");
        repository.ablegen("mute", "zwei");
        assertEquals(2, repository.findeNach(0L, 100).size());
        assertEquals(1, repository.findeNach(0L, 1).size());
        assertTrue(repository.findeNach(Long.MAX_VALUE, 100).isEmpty());
        assertTrue(repository.findeNach(0L, 0).isEmpty());
        List<VerpassteNachricht> alle = repository.findeNach(0L, 100);
        assertTrue(alle.get(0).id() < alle.get(1).id());
    }

    @Test
    void cursorMergeNimmtMaximum() {
        UUID uuid = UUID.randomUUID();
        assertTrue(repository.cursorFuer(uuid).isEmpty());
        repository.cursorSetzen(uuid, 100L);
        repository.cursorSetzen(uuid, 50L);
        assertEquals(100L, repository.cursorFuer(uuid).orElseThrow());
        repository.cursorSetzen(uuid, 200L);
        assertEquals(200L, repository.cursorFuer(uuid).orElseThrow());
        repository.cursorSetzen(uuid, 0L);
        assertEquals(200L, repository.cursorFuer(uuid).orElseThrow());
    }

    @Test
    void ladenRoundtripUeberDatei() {
        UUID uuid = UUID.randomUUID();
        repository.ablegen("ban", "<red>Text mit Umlauten äöü");
        repository.ablegen("report-neu", "zweite Nachricht");
        repository.cursorSetzen(uuid, 12345L);
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        VerpassteBroadcastsRepository neu =
                new VerpassteBroadcastsRepository(temp, speicher, ioExecutor);
        neu.laden();
        assertEquals(2, neu.anzahl());
        assertEquals(12345L, neu.cursorFuer(uuid).orElseThrow());
        List<VerpassteNachricht> alle = neu.findeNach(0L, 100);
        assertEquals("ban", alle.get(0).typ());
        assertEquals("<red>Text mit Umlauten äöü", alle.get(0).text());
    }

    @Test
    void beschneidenEntferntNurAlte() {
        repository.ablegen("ban", "eins");
        assertEquals(0, repository.beschneidenAelterAls(0L));
        assertEquals(1, repository.anzahl());
        assertEquals(1, repository.beschneidenAelterAls(Long.MAX_VALUE));
        assertEquals(0, repository.anzahl());
    }
}
