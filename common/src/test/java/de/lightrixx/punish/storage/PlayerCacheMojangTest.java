package de.lightrixx.punish.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCacheMojangTest {

    @Test
    void offlineUuidWirdAbgelehnt() {
        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:TestSpieler").getBytes(StandardCharsets.UTF_8));
        assertTrue(offlineUuid.version() == 3);
        assertFalse(PlayerCache.istMojangProfil(offlineUuid, true, "TestSpieler"));
    }

    @Test
    void mojangUuidWirdAkzeptiert() {
        assertTrue(PlayerCache.istMojangProfil(UUID.randomUUID(), true, "EchterSpieler"));
    }

    @Test
    void offlineModusBleibtStrikt() {
        assertFalse(PlayerCache.istMojangProfil(UUID.randomUUID(), false, "EchterSpieler"));
    }

    @Test
    void leererNameBleibtStrikt() {
        assertFalse(PlayerCache.istMojangProfil(UUID.randomUUID(), true, "  "));
        assertFalse(PlayerCache.istMojangProfil(UUID.randomUUID(), true, null));
    }

    @Test
    void nullWirdAbgelehnt() {
        assertFalse(PlayerCache.istMojangProfil(null, true, "EchterSpieler"));
    }
}
