package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("null")
class MuteCacheV4Test {

    @Test
    @DisplayName("V4-11: Cache startet unsynchronisiert (Fail-Open-Fenster erkennbar)")
    void startetUnsynchronisiert() {
        MuteCache cache = new MuteCache();
        assertFalse(cache.istJeSynchronisiert(),
                "Frisch gestarteter Cache darf sich nicht synchronisiert melden");
    }

    @Test
    @DisplayName("V4-11: erster MUTE_SYNC markiert synchronisiert")
    void ersterSyncMarkiert() {
        MuteCache cache = new MuteCache();
        cache.ersetzeAlle(List.of());
        assertTrue(cache.istJeSynchronisiert());
    }

    @Test
    @DisplayName("V4-Scope: globaler Mute gilt auf Lobby, Lobby-Mute nicht auf Survival")
    void scopeScharf() {
        MuteCache cache = new MuteCache();
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        cache.ersetzeAlle(List.of(
                new Protokoll.MuteEintrag(uuid, "Steve", "Spam", "Mod",
                        jetzt - 1000L, jetzt + 3_600_000L, "global")));
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isPresent());
        UUID uuid2 = UUID.randomUUID();
        cache.ersetzeAlle(List.of(
                new Protokoll.MuteEintrag(uuid2, "Alex", "Spam", "Mod",
                        jetzt - 1000L, jetzt + 3_600_000L, "lobby")));
        assertTrue(cache.findeAktiv(uuid2, "lobby", jetzt).isPresent());
        assertTrue(cache.findeAktiv(uuid2, "survival", jetzt).isEmpty());
    }

    @Test
    @DisplayName("V4-Scope: abgelaufener Eintrag ist inaktiv (toter Cache wird ignoriert)")
    void abgelaufenIstInaktiv() {
        MuteCache cache = new MuteCache();
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        cache.ersetzeAlle(List.of(
                new Protokoll.MuteEintrag(uuid, "Steve", "Spam", "Mod",
                        jetzt - 7_200_000L, jetzt - 3_600_000L, "global")));
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isEmpty());
    }
}
