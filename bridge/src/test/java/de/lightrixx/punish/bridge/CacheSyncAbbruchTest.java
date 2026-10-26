package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-004: Ein SYNC_BEGINN ohne SYNC_ENDE (Verbindungsabbruch, Proxy-Neustart mitten im
 * Chunk-Sync) ließ die Caches dauerhaft im Staging-Modus. Jeder spätere vollständige
 * Sync wurde dadurch stillschweigend verworfen: Mutes/Banns wurden auf dem Backend
 * nicht mehr durchgesetzt, bis der Server neu startete.
 */
class CacheSyncAbbruchTest {

    private static final long JETZT = 1_700_000_000_000L;
    private static final long GENERATION = 4711L;

    private static Protokoll.MuteEintrag mute(UUID uuid, String scope) {
        return new Protokoll.MuteEintrag(uuid, "Spieler", "Grund", "Mod", JETZT, JETZT + 60_000L, scope);
    }

    private static Protokoll.BanEintrag bann(UUID uuid, String scope) {
        return new Protokoll.BanEintrag(uuid, "Spieler", "Grund", "Mod", JETZT, JETZT + 60_000L, scope);
    }

    @Test
    @DisplayName("D-004: Angefangener Chunk-Sync verwirft spätere Voll-Syncs nicht mehr (Mute)")
    void muteNachAbbruchWiederSynchron() {
        UUID uuid = UUID.randomUUID();
        MuteCache cache = new MuteCache();
        cache.syncBeginn(GENERATION);

        cache.syncVerwerfen();
        cache.ersetzeAlle(List.of(mute(uuid, "global")));

        assertTrue(cache.istJeSynchronisiert(), "Nach dem Verwerfen muss ein Voll-Sync wieder greifen");
        assertTrue(cache.findeAktiv(uuid, "lobby", JETZT).isPresent(), "Mute muss im Cache liegen");
    }

    @Test
    @DisplayName("D-004: Verwerfen verliert den zuletzt angewandten Stand nicht")
    void verwerfenBehaeltLetztenStand() {
        UUID uuid = UUID.randomUUID();
        MuteCache cache = new MuteCache();
        cache.ersetzeAlle(List.of(mute(uuid, "global")));
        cache.syncBeginn(GENERATION);
        cache.syncVerwerfen();

        assertTrue(cache.istJeSynchronisiert());
        assertTrue(cache.findeAktiv(uuid, "lobby", JETZT).isPresent(),
                "Ein abgebrochener Sync darf den letzten vollständigen Stand nicht löschen");
        assertEquals(1, cache.groesse());
    }

    @Test
    @DisplayName("D-004: Abgebrochener Chunk-Sync blockiert Voll-Syncs in allen Straf-Caches")
    void alleCachesNachAbbruchWiederSynchron() {
        UUID uuid = UUID.randomUUID();

        BanCache banns = new BanCache();
        banns.syncBeginn(GENERATION);
        banns.syncVerwerfen();
        banns.ersetzeAlle(List.of(bann(uuid, "global")));
        assertTrue(banns.istJeSynchronisiert());
        assertTrue(banns.findeAktiv(uuid, "lobby", JETZT).isPresent());

        TimeoutCache timeouts = new TimeoutCache();
        timeouts.syncBeginn(GENERATION);
        timeouts.syncVerwerfen();
        timeouts.ersetzeAlle(List.of(new Protokoll.TimeoutEintrag(uuid, "Spieler", "Grund", "Mod",
                JETZT, JETZT + 60_000L, "global")));
        assertTrue(timeouts.istJeSynchronisiert());
        assertTrue(timeouts.findeAktiv(uuid, "lobby", JETZT).isPresent());

        FreezeCache freezes = new FreezeCache();
        freezes.syncBeginn(GENERATION);
        freezes.syncVerwerfen();
        freezes.ersetzeAlle(List.of(new Protokoll.FreezeEintrag(uuid, "Spieler", "Grund", "Mod",
                JETZT, JETZT + 60_000L, "global")));
        assertTrue(freezes.istJeSynchronisiert());
        assertTrue(freezes.findeAktiv(uuid, "lobby", JETZT).isPresent());

        IpMuteCache ipMutes = new IpMuteCache();
        ipMutes.syncBeginn(GENERATION);
        ipMutes.syncVerwerfen();
        ipMutes.ersetzeAlle(List.of(new Protokoll.IpMuteEintrag("10.0.0.7", "Grund", "Mod",
                JETZT, JETZT + 60_000L, "global")));
        assertTrue(ipMutes.istJeSynchronisiert());
        assertTrue(ipMutes.findeAktiv("10.0.0.7", "lobby", JETZT).isPresent());
    }

    @Test
    @DisplayName("V01-1: Voll-Sync mit über 200 Banns behält Mutes, wenn der Proxy sie im selben Rahmen sendet")
    void vollSyncMitVielenBannsBehaeltMutes() {
        UUID muteZiel = UUID.randomUUID();
        MuteCache mutes = new MuteCache();
        IpMuteCache ipMutes = new IpMuteCache();
        BanCache banns = new BanCache();
        mutes.setze(mute(muteZiel, "global"));
        ipMutes.setze(new Protokoll.IpMuteEintrag("10.1.2.3", "Grund", "Mod",
                JETZT, JETZT + 60_000L, "global"));

        mutes.syncBeginn(GENERATION);
        ipMutes.syncBeginn(GENERATION);
        banns.syncBeginn(GENERATION);
        for (int i = 0; i < 205; i++) {
            banns.setze(bann(UUID.randomUUID(), "global"));
        }
        mutes.setze(mute(muteZiel, "global"));
        ipMutes.setze(new Protokoll.IpMuteEintrag("10.1.2.3", "Grund", "Mod",
                JETZT, JETZT + 60_000L, "global"));

        mutes.syncEnde(GENERATION);
        ipMutes.syncEnde(GENERATION);
        banns.syncEnde(GENERATION);

        assertEquals(205, banns.groesse(), "Der gechunkte Ban-Bestand muss vollständig übernommen werden");
        assertTrue(mutes.findeAktiv(muteZiel, "lobby", JETZT).isPresent(),
                "Ein Mute im selben Sync-Rahmen darf nach über 200 Ban-Frames nicht verloren gehen");
        assertTrue(ipMutes.findeAktiv("10.1.2.3", "lobby", JETZT).isPresent(),
                "Ein IP-Mute im selben Sync-Rahmen darf nach über 200 Ban-Frames nicht verloren gehen");
    }

    @Test
    @DisplayName("V01-1: Rahmen ohne MUTE_SETZEN ersetzt die Mutes – der Proxy muss alle Caches im selben Rahmen senden")
    void rahmenOhneMuteSetzenErsetztMutes() {
        UUID muteZiel = UUID.randomUUID();
        MuteCache mutes = new MuteCache();
        IpMuteCache ipMutes = new IpMuteCache();
        BanCache banns = new BanCache();
        mutes.setze(mute(muteZiel, "global"));
        ipMutes.setze(new Protokoll.IpMuteEintrag("10.4.5.6", "Grund", "Mod",
                JETZT, JETZT + 60_000L, "global"));

        mutes.syncBeginn(GENERATION);
        ipMutes.syncBeginn(GENERATION);
        banns.syncBeginn(GENERATION);
        for (int i = 0; i < 205; i++) {
            banns.setze(bann(UUID.randomUUID(), "global"));
        }
        mutes.syncEnde(GENERATION);
        ipMutes.syncEnde(GENERATION);
        banns.syncEnde(GENERATION);

        assertEquals(205, banns.groesse());
        assertTrue(mutes.findeAktiv(muteZiel, "lobby", JETZT).isEmpty(),
                "Ein Rahmen ohne MUTE_SETZEN gilt als vollständig: der Bestand wird ersetzt. "
                        + "Der Proxy muss Mutes im selben Rahmen mitsenden (V01-1, proxy-seitig geprüft).");
        assertTrue(ipMutes.findeAktiv("10.4.5.6", "lobby", JETZT).isEmpty(),
                "Ein Rahmen ohne IP_MUTE_SETZEN gilt als vollständig");
    }

    @Test
    @DisplayName("D-004: Ohne Verwerfen bleibt ein abgebrochener Sync folgenlos für den Stand")
    void abbruchOhneVerwerfenCharakterisierung() {
        UUID uuid = UUID.randomUUID();
        MuteCache cache = new MuteCache();
        cache.syncBeginn(GENERATION);
        cache.ersetzeAlle(List.of(mute(uuid, "global")));

        assertFalse(cache.istJeSynchronisiert(),
                "Während eines offenen Chunk-Syncs wird ein Voll-Sync nur gestaged (Design)");
        assertFalse(cache.findeAktiv(uuid, "lobby", JETZT).isPresent());
    }
}
