package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CacheSyncFramingTest {

    private record ModellEintrag(UUID uuid, String scope, long ende, String grund) {
    }

    @Test
    void zufaelligeAblaufeEntsprechenUnabhaengigemStrafmodell() {
        for (long seed : new long[]{17L, 731L, 20261103L}) {
            var zufall = new java.util.Random(seed);
            var mutes = new MuteCache();
            var banns = new BanCache();
            var timeouts = new TimeoutCache();
            var freezes = new FreezeCache();
            var ips = new IpMuteCache();
            java.util.Map<String, ModellEintrag> sichtbar = new java.util.HashMap<>();
            java.util.Map<String, ModellEintrag> offen = null;
            long generation = 0L;
            long naechsteGeneration = 0L;
            long jetzt = 1_700_000_000_000L;
            for (int schritt = 0; schritt < 2000; schritt++) {
                var uuid = new UUID(0L, 1L + zufall.nextInt(8));
                String ip = "10.0.0." + uuid.getLeastSignificantBits();
                switch (zufall.nextInt(8)) {
                    case 0 -> {
                        generation = ++naechsteGeneration;
                        offen = new java.util.HashMap<>();
                        mutes.syncBeginn(generation); banns.syncBeginn(generation);
                        timeouts.syncBeginn(generation); freezes.syncBeginn(generation); ips.syncBeginn(generation);
                    }
                    case 1 -> {
                        if (offen != null) {
                            mutes.syncBeginn(generation); banns.syncBeginn(generation);
                            timeouts.syncBeginn(generation); freezes.syncBeginn(generation); ips.syncBeginn(generation);
                        }
                    }
                    case 2 -> {
                        long endeGeneration = zufall.nextBoolean() ? generation : naechsteGeneration + 100L;
                        mutes.syncEnde(endeGeneration); banns.syncEnde(endeGeneration);
                        timeouts.syncEnde(endeGeneration); freezes.syncEnde(endeGeneration); ips.syncEnde(endeGeneration);
                        if (offen != null && endeGeneration == generation) {
                            sichtbar = offen;
                            offen = null;
                            generation = 0L;
                        }
                    }
                    case 3 -> {
                        mutes.syncVerwerfen(); banns.syncVerwerfen();
                        timeouts.syncVerwerfen(); freezes.syncVerwerfen(); ips.syncVerwerfen();
                        offen = null;
                        generation = 0L;
                    }
                    case 4, 5 -> {
                        String scope = new String[]{"global", "lobby", "survival"}[zufall.nextInt(3)];
                        long ende = jetzt + zufall.nextInt(2001) - 500L;
                        String grund = "Stand-" + schritt;
                        var eintrag = new ModellEintrag(uuid, scope, ende, grund);
                        (offen == null ? sichtbar : offen).put(uuid + "|" + scope, eintrag);
                        mutes.setze(new Protokoll.MuteEintrag(uuid, "Spieler", grund, "Mod", 1L, ende, scope));
                        banns.setze(new Protokoll.BanEintrag(uuid, "Spieler", grund, "Mod", 1L, ende, scope));
                        timeouts.setze(new Protokoll.TimeoutEintrag(uuid, "Spieler", grund, "Mod", 1L, ende, scope));
                        freezes.setze(new Protokoll.FreezeEintrag(uuid, "Spieler", grund, "Mod", 1L, ende, scope));
                        ips.setze(new Protokoll.IpMuteEintrag(ip, grund, "Mod", 1L, ende, scope));
                    }
                    case 6 -> {
                        if (offen == null) {
                            sichtbar.values().removeIf(e -> e.uuid().equals(uuid));
                            mutes.entferne(List.of(uuid)); banns.entferne(List.of(uuid));
                            timeouts.entferne(List.of(uuid)); freezes.entferne(List.of(uuid)); ips.entferne(List.of(ip));
                        }
                    }
                    case 7 -> jetzt += zufall.nextInt(300);
                    default -> throw new AssertionError();
                }
                for (long id = 1L; id <= 8L; id++) {
                    var ziel = new UUID(0L, id);
                    for (String server : List.of("lobby", "survival")) {
                        ModellEintrag erwartet = sichtbar.get(ziel + "|global");
                        if (erwartet == null || erwartet.ende() <= jetzt) {
                            erwartet = sichtbar.get(ziel + "|" + server);
                        }
                        String grund = erwartet != null && erwartet.ende() > jetzt ? erwartet.grund() : "";
                        String kontext = "seed=" + seed + ", Schritt=" + schritt + ", Ziel=" + id + ", Server=" + server;
                        assertEquals(grund, mutes.findeAktiv(ziel, server, jetzt).map(Protokoll.MuteEintrag::grund).orElse(""), kontext);
                        assertEquals(grund, banns.findeAktiv(ziel, server, jetzt).map(Protokoll.BanEintrag::grund).orElse(""), kontext);
                        assertEquals(grund, timeouts.findeAktiv(ziel, server, jetzt).map(Protokoll.TimeoutEintrag::grund).orElse(""), kontext);
                        assertEquals(grund, freezes.findeAktiv(ziel, server, jetzt).map(Protokoll.FreezeEintrag::grund).orElse(""), kontext);
                        assertEquals(grund, ips.findeAktiv("10.0.0." + id, server, jetzt).map(Protokoll.IpMuteEintrag::grund).orElse(""), kontext);
                    }
                }
            }
        }
    }


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
