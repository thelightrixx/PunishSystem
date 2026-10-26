package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CacheSyncFramingTest {

    private static Protokoll.BanEintrag ban(UUID uuid, String scope) {
        return new Protokoll.BanEintrag(uuid, "Steve", "Hack", "Admin",
                System.currentTimeMillis(), Long.MAX_VALUE, scope);
    }

    private static Protokoll.MuteEintrag mute(UUID uuid, String scope) {
        return new Protokoll.MuteEintrag(uuid, "Steve", "Spam", "Admin",
                System.currentTimeMillis(), System.currentTimeMillis() + 3600000L, scope);
    }

    private static Protokoll.IpMuteEintrag ipMute(String ip, long jetzt, String scope) {
        return new Protokoll.IpMuteEintrag(ip, "Spam", "Admin", jetzt,
                jetzt + 3600000L, scope);
    }

    @Test
    void banCacheBleibtBisSyncEndeUnveraendert() {
        BanCache cache = new BanCache();
        UUID alt = UUID.randomUUID();
        cache.ersetzeAlle(List.of(ban(alt, "global")));
        assertTrue(cache.istJeSynchronisiert());

        long generation = 987654321L;
        cache.syncBeginn(generation);
        UUID neu = UUID.randomUUID();
        cache.setze(ban(neu, "global"));

        long jetzt = System.currentTimeMillis();
        assertTrue(cache.findeAktiv(alt, "lobby", jetzt).isPresent());
        assertTrue(cache.findeAktiv(neu, "lobby", jetzt).isEmpty());

        cache.syncEnde(generation);
        assertTrue(cache.findeAktiv(neu, "lobby", jetzt).isPresent());
        assertTrue(cache.findeAktiv(alt, "lobby", jetzt).isEmpty());
    }

    @Test
    void muteCacheFalscheGenerationWirdIgnoriert() {
        MuteCache cache = new MuteCache();
        UUID alt = UUID.randomUUID();
        cache.ersetzeAlle(List.of(mute(alt, "global")));

        cache.syncBeginn(111L);
        cache.syncEnde(222L);
        long jetzt = System.currentTimeMillis();
        assertTrue(cache.findeAktiv(alt, "lobby", jetzt).isPresent());
        assertTrue(cache.istJeSynchronisiert());
    }

    @Test
    void timeoutUndIpMuteCachesUnterstuetzenFraming() {
        TimeoutCache timeouts = new TimeoutCache();
        IpMuteCache ipMutes = new IpMuteCache();
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();

        timeouts.ersetzeAlle(List.of(new Protokoll.TimeoutEintrag(uuid, "Steve", "Cool",
                "Admin", jetzt, jetzt + 3600000L, "global")));
        ipMutes.ersetzeAlle(List.of(ipMute("1.2.3.4", jetzt, "global")));

        long generation = 555L;
        timeouts.syncBeginn(generation);
        ipMutes.syncBeginn(generation);
        timeouts.setze(new Protokoll.TimeoutEintrag(UUID.randomUUID(), "Alex", "X",
                "Admin", jetzt, jetzt + 3600000L, "global"));
        ipMutes.setze(ipMute("5.6.7.8", jetzt, "global"));

        assertTrue(timeouts.findeAktiv(uuid, "lobby", jetzt).isPresent());
        assertTrue(ipMutes.findeAktiv("1.2.3.4", "lobby", jetzt).isPresent(),
                "Alter IP-Mute bleibt während des Rahmens sichtbar");
        assertTrue(ipMutes.findeAktiv("5.6.7.8", "lobby", jetzt).isEmpty(),
                "Neuer IP-Mute wird bis syncEnde gestagt");

        timeouts.syncEnde(generation);
        ipMutes.syncEnde(generation);
        assertTrue(timeouts.findeAktiv(uuid, "lobby", jetzt).isEmpty());
        assertTrue(ipMutes.findeAktiv("5.6.7.8", "lobby", jetzt).isPresent(),
                "Gestagter IP-Mute muss nach syncEnde aktiv sein");
        assertTrue(ipMutes.findeAktiv("1.2.3.4", "lobby", jetzt).isEmpty(),
                "Alter IP-Mute muss nach syncEnde entfernt sein");
        assertTrue(ipMutes.istJeSynchronisiert());
    }

    @Test
    void ipMuteCacheFalscheGenerationWirdIgnoriert() {
        IpMuteCache cache = new IpMuteCache();
        long jetzt = System.currentTimeMillis();
        cache.ersetzeAlle(List.of(ipMute("1.2.3.4", jetzt, "global")));

        cache.syncBeginn(333L);
        cache.setze(ipMute("5.6.7.8", jetzt, "global"));
        cache.syncEnde(444L);

        assertTrue(cache.findeAktiv("1.2.3.4", "lobby", jetzt).isPresent(),
                "Fremde Generation darf den Live-Bestand nicht ersetzen");
        assertTrue(cache.findeAktiv("5.6.7.8", "lobby", jetzt).isEmpty(),
                "Gestagter Eintrag darf ohne passendes syncEnde nicht sichtbar werden");
    }

    @Test
    void syncBeginMitUngueltigerGenerationIstNoOp() {
        BanCache cache = new BanCache();
        UUID uuid = UUID.randomUUID();
        cache.ersetzeAlle(List.of(ban(uuid, "global")));
        cache.syncBeginn(0L);
        cache.syncEnde(-5L);
        assertTrue(cache.findeAktiv(uuid, "lobby", System.currentTimeMillis()).isPresent());
    }

    @Test
    void grosserSyncBleibtAtomar() {
        MuteCache cache = new MuteCache();
        List<Protokoll.MuteEintrag> initial = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            initial.add(mute(UUID.randomUUID(), "global"));
        }
        cache.ersetzeAlle(initial);
        int vorher = cache.groesse();

        long generation = 999001L;
        cache.syncBeginn(generation);
        for (int i = 0; i < 250; i++) {
            cache.setze(mute(UUID.randomUUID(), "global"));
        }
        assertEquals(vorher, cache.groesse());
        cache.syncEnde(generation);
        assertEquals(250, cache.groesse());
        assertTrue(cache.istJeSynchronisiert());
    }

    @Test
    void leerenSetztGenerationZurueck() {
        BanCache cache = new BanCache();
        cache.syncBeginn(42L);
        cache.leeren();

        UUID neu = UUID.randomUUID();
        cache.setze(ban(neu, "global"));
        long jetzt = System.currentTimeMillis();
        assertTrue(cache.findeAktiv(neu, "lobby", jetzt).isPresent(),
                "Nach leeren() muss setze sofort sichtbar sein (Generation zurückgesetzt)");

        cache.syncEnde(42L);
        assertTrue(cache.findeAktiv(neu, "lobby", jetzt).isPresent(),
                "syncEnde mit der alten Generation darf den neuen Eintrag nicht entfernen");
        assertEquals(1, cache.groesse());
    }

    @Test
    void doppeltesSyncBeginnBehaeltStaging() {
        MuteCache cache = new MuteCache();
        long generation = 777001L;
        cache.syncBeginn(generation);
        UUID neu = UUID.randomUUID();
        cache.setze(mute(neu, "global"));
        cache.syncBeginn(generation);
        long jetzt = System.currentTimeMillis();
        cache.syncEnde(generation);
        assertTrue(cache.findeAktiv(neu, "lobby", jetzt).isPresent());
    }
}
