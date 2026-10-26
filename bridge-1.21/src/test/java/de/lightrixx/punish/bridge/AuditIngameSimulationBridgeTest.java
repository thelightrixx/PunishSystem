package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditIngameSimulationBridgeTest {

    @Test
    void bannAnfrageVomProxyBlockiertSpielerAufBridge() throws Exception {
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        Protokoll.BanEintrag eintrag = new Protokoll.BanEintrag(uuid, "Verbrecher", "Hacking",
                "ModJ", jetzt, jetzt + 86_400_000L, "global");

        byte[] paket = Protokoll.banSync(List.of(eintrag));
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.BAN_SYNC, gelesen.typ());

        BanCache cache = new BanCache();
        cache.ersetzeAlle(Protokoll.leseBanSync(gelesen.nutzdaten()));

        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isPresent(), "Gebannt auf lobby");
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt).isPresent(), "Gebannt überall (global)");

        cache.syncBeginn(42L);
        cache.ersetzeAlle(List.of());
        cache.syncEnde(42L);
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isEmpty(),
                "Leerer Voll-Sync muss alten Stand ersetzen (Pardon-Sync)");
    }

    @Test
    @SuppressWarnings("null")
    void muteCacheEntscheidetNachScopeWieDerEnforcementListener() {
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        MuteCache cache = new MuteCache();
        cache.ersetzeAlle(List.of(new Protokoll.MuteEintrag(uuid, "Muted", "Spam", "Mod",
                jetzt, jetzt + 3_600_000L, "lobby")));

        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isPresent(), "lokaler Mute greift");
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt).isEmpty(), "fremder Server frei");

        cache.ersetzeAlle(List.of(new Protokoll.MuteEintrag(uuid, "Muted", "Spam", "Mod",
                jetzt, jetzt + 3_600_000L, "global")));
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt).isPresent(), "global greift überall");

        cache.entferne(List.of(uuid));
        assertTrue(cache.findeAktiv(uuid, "survival", jetzt).isEmpty(), "Entmute-Sync wirkt");
    }

    @Test
    void freezeCacheVerstehtPermanent() throws Exception {
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        byte[] paket = Protokoll.nachricht(Protokoll.FREEZE_SETZEN,
                out -> Protokoll.schreibeFreeze(out, new Protokoll.FreezeEintrag(
                        uuid, "Flüchter", "Check bitte", "Mod", jetzt, Long.MAX_VALUE, "global")));
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.FREEZE_SETZEN, gelesen.typ());

        FreezeCache cache = new FreezeCache();
        cache.setze(Protokoll.leseFreeze(gelesen.nutzdaten()));
        var treffer = cache.findeAktiv(uuid, "lobby", jetzt);
        assertTrue(treffer.isPresent());
        assertTrue(treffer.get().istPermanent(), "Freeze ohne Ablauf ist permanent");

        cache.entferne(List.of(uuid));
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isEmpty(), "Unfreeze-Sync wirkt");
    }

    @Test
    void abgelaufenerTimeoutBlockiertNichtMehr() throws Exception {
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        byte[] paket = Protokoll.nachricht(Protokoll.TIMEOUT_SETZEN,
                out -> Protokoll.schreibeTimeout(out, new Protokoll.TimeoutEintrag(
                        uuid, "Altfall", "kurz", "Mod", jetzt - 10_000L, jetzt - 1L, "global")));
        Protokoll.Paket gelesen = Protokoll.paket(paket);

        TimeoutCache cache = new TimeoutCache();
        cache.setze(Protokoll.leseTimeout(gelesen.nutzdaten()));
        assertTrue(cache.findeAktiv(uuid, "lobby", jetzt).isEmpty(),
                "Abgelaufener Timeout darf nicht blockieren");
    }
}
