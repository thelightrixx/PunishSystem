package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("null")
class FallbackDenyRegressionTest {

    @Test
    void banCacheFilternNachScopeUndAblauf() {
        BanCache cache = new BanCache();
        assertFalse(cache.istJeSynchronisiert());
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        cache.ersetzeAlle(List.of(new Protokoll.BanEintrag(uuid, "Steve", "Cheating", "Mod",
                jetzt, jetzt + 3_600_000L, "global")));
        assertTrue(cache.istJeSynchronisiert());
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isPresent(), "global deckt lobby ab");
        UUID fremd = UUID.randomUUID();
        assertTrue(cache.findeAktiv(fremd, "lobby", jetzt).isEmpty());
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt + 4_000_000L).isEmpty(), "abgelaufen");
        cache.entferne(List.of(uuid));
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isEmpty());
    }

    @Test
    void banCachePermaBleibtAktiv() {
        BanCache cache = new BanCache();
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        cache.setze(new Protokoll.BanEintrag(uuid, "Steve", "Cheating", "Mod",
                jetzt, Long.MAX_VALUE, "global"));
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt + 365L * 24 * 3600 * 1000).isPresent());
    }

    @Test
    void timeoutCacheFilternNachScopeUndAblauf() {
        TimeoutCache cache = new TimeoutCache();
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        cache.ersetzeAlle(List.of(new Protokoll.TimeoutEintrag(uuid, "Steve", "Cool-down", "Mod",
                jetzt, jetzt + 600_000L, "lobby")));
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isPresent());
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt).isEmpty(), "falscher Server");
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt + 700_000L).isEmpty(), "abgelaufen");
    }

    @Test
    void vorErstSyncKeinDeny() {
        BanCache cache = new BanCache();
        TimeoutCache timeouts = new TimeoutCache();
        assertFalse(cache.istJeSynchronisiert());
        assertFalse(timeouts.istJeSynchronisiert());
    }

    @Test
    void banCacheBehaeltMehrereScopes() {
        BanCache cache = new BanCache();
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        cache.ersetzeAlle(List.of(
                new Protokoll.BanEintrag(uuid, "Steve", "Cheating", "Mod",
                        jetzt, jetzt + 3_600_000L, "lobby"),
                new Protokoll.BanEintrag(uuid, "Steve", "Hacking", "Mod",
                        jetzt, jetzt + 3_600_000L, "survival")));
        assertEquals(2, cache.groesse(), "lobby- und survival-Bann müssen koexistieren");
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isPresent());
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt).isPresent());
        assertTrue(cache.findeAktiv(uuid, "citybuild", jetzt).isEmpty());
        cache.setze(new Protokoll.BanEintrag(uuid, "Steve", "Cheating", "Mod",
                jetzt, jetzt + 3_600_000L, "global"));
        assertTrue(cache.findeAktiv(uuid, "citybuild", jetzt).isPresent(), "global deckt alles ab");
        assertEquals(3, cache.groesse());
        cache.entferne(List.of(uuid));
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isEmpty());
        assertEquals(0, cache.groesse());
    }

    @Test
    void muteCacheBehaeltMehrereScopes() {
        MuteCache cache = new MuteCache();
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        cache.ersetzeAlle(List.of(
                new Protokoll.MuteEintrag(uuid, "Steve", "Spam", "Mod",
                        jetzt, jetzt + 3_600_000L, "lobby"),
                new Protokoll.MuteEintrag(uuid, "Steve", "Spam", "Mod",
                        jetzt, jetzt + 3_600_000L, "survival")));
        assertEquals(2, cache.groesse());
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isPresent());
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt).isPresent());
        assertTrue(cache.findeAktiv(uuid, "citybuild", jetzt).isEmpty());
    }

    @Test
    void timeoutCacheBehaeltMehrereScopes() {
        TimeoutCache cache = new TimeoutCache();
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        cache.ersetzeAlle(List.of(
                new Protokoll.TimeoutEintrag(uuid, "Steve", "Cool-down", "Mod",
                        jetzt, jetzt + 600_000L, "lobby"),
                new Protokoll.TimeoutEintrag(uuid, "Steve", "Cool-down", "Mod",
                        jetzt, jetzt + 600_000L, "survival")));
        assertEquals(2, cache.groesse());
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isPresent());
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt).isPresent());
        assertTrue(cache.findeAktiv(uuid, "citybuild", jetzt).isEmpty());
    }
}
