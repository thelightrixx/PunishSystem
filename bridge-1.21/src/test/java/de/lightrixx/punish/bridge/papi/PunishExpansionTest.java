package de.lightrixx.punish.bridge.papi;

import de.lightrixx.punish.net.Protokoll;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PunishExpansionTest {

    private static Protokoll.PlatzhalterAntwort antwortMitDetails() {
        long jetzt = System.currentTimeMillis();
        return new Protokoll.PlatzhalterAntwort(1L, true,
                true, jetzt + 60000L, true, Long.MAX_VALUE, false,
                Protokoll.PlatzhalterAntwort.INAKTIV, true, jetzt + 60000L, 1, 2, 2, 1, 5,
                "Spam", "Admin", jetzt - 5000L, "Hacking", "Mod", jetzt - 9000L,
                "", "", 0L, "AFK-Verdacht", "Admin", jetzt - 1000L, 3, 7,
                "Beleidigung", "medium");
    }

    private static PunishExpansion expansionMit(Protokoll.PlatzhalterAntwort antwort,
                                                OfflinePlayer betrachter) {
        Plugin plugin = mock(Plugin.class);
        PlatzhalterCache cache = mock(PlatzhalterCache.class);
        when(cache.aktuelle(null, "Steve")).thenReturn(antwort);
        when(betrachter.getName()).thenReturn("Steve");
        return new PunishExpansion(plugin, cache);
    }

    @Test
    void neueDetailKeysWerdenGemeldet() {
        OfflinePlayer betrachter = mock(OfflinePlayer.class);
        PunishExpansion expansion = expansionMit(antwortMitDetails(), betrachter);

        assertEquals("Spam", expansion.onRequest(betrachter, "mute_reason"));
        assertEquals("Admin", expansion.onRequest(betrachter, "mute_by"));
        assertEquals("Hacking", expansion.onRequest(betrachter, "ban_reason"));
        assertEquals("true", expansion.onRequest(betrachter, "ban_permanent"));
        assertEquals("Permanent", expansion.onRequest(betrachter, "ban_rest"));
        assertEquals("permanent", expansion.onRequest(betrachter, "ban_rest_seconds"));
        assertEquals("false", expansion.onRequest(betrachter, "mute_permanent"));
        assertEquals("AFK-Verdacht", expansion.onRequest(betrachter, "freeze_reason"));
        assertEquals("-", expansion.onRequest(betrachter, "timeout_reason"));
        assertEquals("Beleidigung", expansion.onRequest(betrachter, "warns_last_reason"));
        assertEquals("medium", expansion.onRequest(betrachter, "verstoss_stufe"));
        assertEquals("3", expansion.onRequest(betrachter, "notes"));
        assertEquals("7", expansion.onRequest(betrachter, "history_count"));
    }

    @Test
    void statusUndZaehlerWerdenGemeldet() {
        OfflinePlayer betrachter = mock(OfflinePlayer.class);
        PunishExpansion expansion = expansionMit(antwortMitDetails(), betrachter);

        assertEquals("true", expansion.onRequest(betrachter, "is_punished"));
        assertEquals("3", expansion.onRequest(betrachter, "active_count"));
        assertEquals("frozen", expansion.onRequest(betrachter, "status"));
    }

    @Test
    void legacyCodesWerdenNeutralisiert() {
        long jetzt = System.currentTimeMillis();
        Protokoll.PlatzhalterAntwort antwort = new Protokoll.PlatzhalterAntwort(1L, true,
                true, jetzt + 60000L, true, Long.MAX_VALUE, false,
                Protokoll.PlatzhalterAntwort.INAKTIV, false,
                Protokoll.PlatzhalterAntwort.INAKTIV, 0, 0, 0, 0, 0,
                "§4§lHACKER", "&cSpammer", jetzt - 5000L, "A & B", "Mod", jetzt - 9000L,
                "", "", 0L, "", "", 0L, 0, 0,
                "&aBeleidigung", "medium");
        OfflinePlayer betrachter = mock(OfflinePlayer.class);
        PunishExpansion expansion = expansionMit(antwort, betrachter);

        assertEquals("HACKER", expansion.onRequest(betrachter, "mute_reason"));
        assertEquals("Spammer", expansion.onRequest(betrachter, "mute_by"));
        assertEquals("A & B", expansion.onRequest(betrachter, "ban_reason"));
        assertEquals("Beleidigung", expansion.onRequest(betrachter, "warns_last_reason"));
    }

    @Test
    void unbekannteAntwortErgibtStandards() {
        OfflinePlayer betrachter = mock(OfflinePlayer.class);
        PunishExpansion expansion = expansionMit(null, betrachter);

        assertEquals("false", expansion.onRequest(betrachter, "is_punished"));
        assertEquals("0", expansion.onRequest(betrachter, "active_count"));
        assertEquals("clean", expansion.onRequest(betrachter, "status"));
        assertEquals("-", expansion.onRequest(betrachter, "mute_reason"));
        assertEquals("-", expansion.onRequest(betrachter, "mute_since"));
        assertEquals("-1", expansion.onRequest(betrachter, "mute_rest_seconds"));
        assertEquals("false", expansion.onRequest(betrachter, "ban_permanent"));
        assertEquals("0", expansion.onRequest(betrachter, "notes"));
        assertEquals("-", expansion.onRequest(betrachter, "warns_last_reason"));
    }
}
