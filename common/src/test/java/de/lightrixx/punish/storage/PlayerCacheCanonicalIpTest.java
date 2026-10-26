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

class PlayerCacheCanonicalIpTest {

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
    void ipv6VariantenWerdenAlsGleicheIpErkannt(@TempDir File ordner) {
        try (CachePaar paar = neuerCache(ordner)) {
            PlayerCache cache = paar.cache();
            UUID alt = UUID.randomUUID();

            cache.aktualisieren(new CachedPlayer(alt, "AltV6",
                    "2001:0DB8:0000:0000:0000:0000:0000:0001", 1000L));

            List<CachedPlayer> treffer = cache.findeNachIp("2001:db8::1");
            assertEquals(1, treffer.size(), "IPv6-Variante muss gefunden werden");
            assertEquals(alt, treffer.get(0).uuid());

            UUID zweit = UUID.randomUUID();
            cache.aktualisieren(new CachedPlayer(zweit, "ZweitV6", "2001:db8::1", 2000L));
            List<CachedPlayer> langform = cache.findeNachIp("2001:0DB8:0000:0000:0000:0000:0000:0001");
            assertEquals(2, langform.size(), "Beide V6-Einträge müssen gefunden werden");
        }
    }

    @Test
    void unkanonisierbareIp_faelltAufRoheVergleichZurueck(@TempDir File ordner) {
        try (CachePaar paar = neuerCache(ordner)) {
            PlayerCache cache = paar.cache();
            cache.aktualisieren(new CachedPlayer(UUID.randomUUID(), "Kaputt", "nicht-eine-ip", 1000L));
            assertEquals(1, cache.findeNachIp("nicht-eine-ip").size());
            assertEquals(0, cache.findeNachIp("andere-zeichenkette").size());
        }
    }
}
