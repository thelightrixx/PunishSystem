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

    @Test
    void abgelaufeneCacheStrafenWerdenSofortAlsInaktivAngezeigt() {
        var ablauf = System.currentTimeMillis() - 1000L;
        var antwort = new Protokoll.PlatzhalterAntwort(1L, true,
                true, ablauf, true, ablauf, true, ablauf, true, ablauf,
                0, 0, 0, 0, 0, "Spam", "Mod", 1L, "Bann", "Mod", 1L,
                "Timeout", "Mod", 1L, "Freeze", "Mod", 1L, 0, 0, "", "");
        var spieler = mock(OfflinePlayer.class);
        var expansion = expansionMit(antwort, spieler);
        for (var key : java.util.List.of("muted", "banned", "timeouted", "frozen", "is_punished")) {
            assertEquals("false", expansion.onRequest(spieler, key), key);
        }
        assertEquals("0", expansion.onRequest(spieler, "active_count"));
        assertEquals("clean", expansion.onRequest(spieler, "status"));
        for (var typ : java.util.List.of("mute", "ban", "timeout", "freeze")) {
            for (var detail : java.util.List.of("reason", "by", "since", "rest")) {
                assertEquals("-", expansion.onRequest(spieler, typ + "_" + detail));
            }
            assertEquals("-1", expansion.onRequest(spieler, typ + "_rest_seconds"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void alteWerteBleibenWaehrendRefreshBeiAllenAbfragenVerfuegbar() throws Exception {
        var plugin = mock(Plugin.class);
        var server = mock(org.bukkit.Server.class);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        var aufgaben = new java.util.ArrayList<Runnable>();
        when(scheduler.runTaskAsynchronously(org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.any(Runnable.class))).thenAnswer(inv -> {
            aufgaben.add(inv.getArgument(1));
            return mock(org.bukkit.scheduler.BukkitTask.class);
        });
        var kanal = mock(de.lightrixx.punish.bridge.KanalClient.class);
        when(kanal.naechsteGuiId()).thenReturn(7L);
        var antwort = antwortMitDetails();
        when(kanal.fragePlatzhalterAn(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(antwort);
        var cache = new PlatzhalterCache(plugin, kanal);
        cache.aktuelle(null, "Steve");
        aufgaben.getFirst().run();
        var feld = PlatzhalterCache.class.getDeclaredField("cache");
        feld.setAccessible(true);
        var eintraege = (java.util.Map<java.util.UUID, Object>) feld.get(cache);
        var schluessel = eintraege.keySet().iterator().next();
        var konstruktor = eintraege.get(schluessel).getClass().getDeclaredConstructors()[0];
        konstruktor.setAccessible(true);
        eintraege.put(schluessel, konstruktor.newInstance(antwort, System.currentTimeMillis() - 31_000L));
        assertEquals(antwort, cache.aktuelle(null, "Steve"));
        assertEquals(antwort, cache.aktuelle(null, "Steve"));
        assertEquals(2, aufgaben.size());
    }
}
