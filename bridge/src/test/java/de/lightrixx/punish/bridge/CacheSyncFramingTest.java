package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.net.TunnelSignatur;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
    @SuppressWarnings("null")
    void timeoutUndIpMuteCachesUnterstuetzenFraming() {
        TimeoutCache timeouts = new TimeoutCache();
        IpMuteCache ipMutes = new IpMuteCache();
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();

        timeouts.ersetzeAlle(List.of(new Protokoll.TimeoutEintrag(uuid, "Steve", "Cool",
                "Admin", jetzt, jetzt + 3600000L, "global")));
        ipMutes.ersetzeAlle(List.of(new Protokoll.IpMuteEintrag("1.2.3.4", "Spam",
                "Admin", jetzt, jetzt + 3600000L, "global")));

        long generation = 555L;
        timeouts.syncBeginn(generation);
        ipMutes.syncBeginn(generation);
        timeouts.setze(new Protokoll.TimeoutEintrag(UUID.randomUUID(), "Alex", "X",
                "Admin", jetzt, jetzt + 3600000L, "global"));
        ipMutes.setze(new Protokoll.IpMuteEintrag("5.6.7.8", "Neu", "Admin",
                jetzt, jetzt + 3600000L, "global"));

        assertTrue(timeouts.findeAktiv(uuid, "lobby", jetzt).isPresent());
        assertTrue(ipMutes.findeAktiv("1.2.3.4", "lobby", jetzt).isPresent(),
                "Während des Rahmens bleibt der alte IP-Mute sichtbar");
        assertTrue(ipMutes.findeAktiv("5.6.7.8", "lobby", jetzt).isEmpty(),
                "Gestagter IP-Mute darf vor syncEnde nicht sichtbar sein");

        timeouts.syncEnde(generation);
        ipMutes.syncEnde(generation);
        assertTrue(timeouts.findeAktiv(uuid, "lobby", jetzt).isEmpty());
        assertTrue(ipMutes.findeAktiv("1.2.3.4", "lobby", jetzt).isEmpty(),
                "Nach syncEnde muss der alte IP-Mute ersetzt sein");
        assertTrue(ipMutes.findeAktiv("5.6.7.8", "lobby", jetzt).isPresent(),
                "Nach syncEnde muss der gestagte IP-Mute sichtbar sein");
    }

    @Test
    void ipMuteFalscheGenerationBehaeltBestand() {
        IpMuteCache ipMutes = new IpMuteCache();
        long jetzt = System.currentTimeMillis();
        ipMutes.ersetzeAlle(List.of(new Protokoll.IpMuteEintrag("9.9.9.9", "Spam",
                "Admin", jetzt, jetzt + 3600000L, "global")));

        ipMutes.syncBeginn(111L);
        ipMutes.syncEnde(222L);

        assertTrue(ipMutes.findeAktiv("9.9.9.9", "lobby", jetzt).isPresent(),
                "Ein fremder Rahmen darf den Live-Bestand nicht leeren");
        assertTrue(ipMutes.istJeSynchronisiert());
    }

    private static final String TUNNEL_SECRET = "geheim-bridge-test";

    private static KanalClient kanalClient(MuteCache mutes, IpMuteCache ipMutes, BanCache banns,
                                           TimeoutCache timeouts, FreezeCache freezes) {
        KanalClient client = new KanalClient(mock(Plugin.class), mutes, ipMutes, banns, timeouts, freezes);
        client.setTunnelSecret(TUNNEL_SECRET);
        return client;
    }

    private static void sende(KanalClient client, java.util.concurrent.atomic.AtomicLong sequenz,
                              byte[] paket) throws Exception {
        byte[] signiert = TunnelSignatur.signieren(
                Protokoll.rahmeMitSequenz(paket, sequenz.incrementAndGet()),
                TunnelSignatur.schluesselAus(TUNNEL_SECRET, TunnelSignatur.Richtung.PROXY_ZU_BRIDGE));
        client.onPluginMessageReceived(Protokoll.KANAL, (org.bukkit.entity.Player) null, signiert);
    }

    @Test
    void grosserBanSyncMitMuteAnteilVerliertKeineMutes() throws Exception {
        MuteCache mutes = new MuteCache();
        IpMuteCache ipMutes = new IpMuteCache();
        BanCache banns = new BanCache();
        TimeoutCache timeouts = new TimeoutCache();
        FreezeCache freezes = new FreezeCache();
        KanalClient client = kanalClient(mutes, ipMutes, banns, timeouts, freezes);

        long jetzt = System.currentTimeMillis();
        UUID alt = UUID.randomUUID();
        mutes.setze(mute(alt, "global"));

        long generation = 424242L;
        java.util.concurrent.atomic.AtomicLong sequenz = new java.util.concurrent.atomic.AtomicLong();
        sende(client, sequenz, Protokoll.syncBegin(generation));
        for (int i = 0; i < 250; i++) {
            Protokoll.BanEintrag eintrag = ban(UUID.randomUUID(), "global");
            sende(client, sequenz, Protokoll.nachricht(Protokoll.BAN_SETZEN,
                    out -> Protokoll.schreibeBan(out, eintrag)));
        }
        UUID neu = UUID.randomUUID();
        Protokoll.MuteEintrag neuerMute = mute(neu, "global");
        sende(client, sequenz, Protokoll.nachricht(Protokoll.MUTE_SETZEN,
                out -> Protokoll.schreibeMute(out, neuerMute)));
        sende(client, sequenz, Protokoll.syncEnd(generation));

        assertTrue(mutes.findeAktiv(neu, "lobby", jetzt).isPresent(),
                "Mute aus dem Rahmen muss nach SYNC_END aktiv sein (V01-1)");
        assertTrue(mutes.findeAktiv(alt, "lobby", jetzt).isEmpty());
        assertEquals(250, banns.alle().size(), "Alle Banns des chunktierten Rahmens müssen ankommen");
        assertTrue(banns.istJeSynchronisiert());
    }

    @Test
    void grosserBanSyncMitIpMuteAnteilVerliertKeinenIpMute() throws Exception {
        MuteCache mutes = new MuteCache();
        IpMuteCache ipMutes = new IpMuteCache();
        BanCache banns = new BanCache();
        TimeoutCache timeouts = new TimeoutCache();
        FreezeCache freezes = new FreezeCache();
        KanalClient client = kanalClient(mutes, ipMutes, banns, timeouts, freezes);

        long jetzt = System.currentTimeMillis();
        ipMutes.setze(new Protokoll.IpMuteEintrag("10.1.1.1", "Alt", "Admin",
                jetzt, jetzt + 3600000L, "global"));

        long generation = 777333L;
        java.util.concurrent.atomic.AtomicLong sequenz = new java.util.concurrent.atomic.AtomicLong();
        sende(client, sequenz, Protokoll.syncBegin(generation));
        for (int i = 0; i < 250; i++) {
            Protokoll.BanEintrag eintrag = ban(UUID.randomUUID(), "global");
            sende(client, sequenz, Protokoll.nachricht(Protokoll.BAN_SETZEN,
                    out -> Protokoll.schreibeBan(out, eintrag)));
        }
        Protokoll.IpMuteEintrag neuerIpMute = new Protokoll.IpMuteEintrag("10.2.2.2", "Neu",
                "Admin", jetzt, jetzt + 3600000L, "global");
        sende(client, sequenz, Protokoll.nachricht(Protokoll.IP_MUTE_SETZEN,
                out -> Protokoll.schreibeIpMute(out, neuerIpMute)));
        sende(client, sequenz, Protokoll.syncEnd(generation));

        assertTrue(ipMutes.findeAktiv("10.2.2.2", "lobby", jetzt).isPresent(),
                "IP-Mute aus dem Rahmen muss nach SYNC_END aktiv sein (V01-1)");
        assertTrue(ipMutes.findeAktiv("10.1.1.1", "lobby", jetzt).isEmpty());
        assertEquals(250, banns.alle().size());
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
