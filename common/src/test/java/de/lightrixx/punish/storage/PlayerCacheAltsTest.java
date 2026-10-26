package de.lightrixx.punish.storage;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerCacheAltsTest {

    private record CachePaar(PlayerCache cache, IoExecutor io) implements AutoCloseable {
        @Override
        public void close() {
            io.herunterfahren(5L, TimeUnit.SECONDS);
        }
    }

    private CachePaar neuerCache(File ordner) {
        YamlSpeicher speicher = new YamlSpeicher() {
            @Override
            public Map<String, Object> laden(File datei) {
                return Map.of();
            }

            @Override
            public void speichern(Map<String, Object> daten, File datei) {
            }
        };
        IoExecutor io = new IoExecutor();
        OfflineVerzeichnis offline = new OfflineVerzeichnis() {
            @Override
            public Optional<ExternSpieler> findeNachName(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<ExternSpieler> findeNachUuid(UUID uuid) {
                return Optional.empty();
            }

            @Override
            public boolean istMainThread() {
                return false;
            }
        };
        PlayerCache cache = new PlayerCache(ordner, speicher, io, offline);
        cache.laden();
        return new CachePaar(cache, io);
    }

    @Test
    void gleicheIp_findetAlleAlts(@TempDir File ordner) {
        try (CachePaar paar = neuerCache(ordner)) {
            PlayerCache cache = paar.cache();
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            cache.aktualisieren(new CachedPlayer(a, "AltA", "1.2.3.4", 1000L));
            cache.aktualisieren(new CachedPlayer(b, "AltB", "1.2.3.4", 2000L));
            cache.aktualisieren(new CachedPlayer(c, "Solo", "5.6.7.8", 3000L));

            List<CachedPlayer> treffer = cache.findeNachIp("1.2.3.4");
            assertEquals(2, treffer.size());
            assertTrue(treffer.stream().anyMatch(s -> s.uuid().equals(a)));
            assertTrue(treffer.stream().anyMatch(s -> s.uuid().equals(b)));

            List<CachedPlayer> solo = cache.findeNachIp("5.6.7.8");
            assertEquals(1, solo.size());
            assertEquals("Solo", solo.get(0).name());
        }
    }

    @Test
    void unbekannteIp_wirdIgnoriert(@TempDir File ordner) {
        try (CachePaar paar = neuerCache(ordner)) {
            PlayerCache cache = paar.cache();
            cache.aktualisieren(new CachedPlayer(UUID.randomUUID(), "Ghost", "unbekannt", 1000L));
            assertTrue(cache.findeNachIp("unbekannt").isEmpty());
            assertTrue(cache.findeNachIp(null).isEmpty());
            assertTrue(cache.findeNachIp("  ").isEmpty());
            assertTrue(cache.findeNachIp("9.9.9.9").isEmpty());
        }
    }
}
