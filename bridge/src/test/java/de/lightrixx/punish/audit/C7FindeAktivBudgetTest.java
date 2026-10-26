package de.lightrixx.punish.audit;

import de.lightrixx.punish.bridge.MuteCache;
import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class C7FindeAktivBudgetTest {

    private static MuteCache cacheMit(int anzahl, long jetzt) {
        MuteCache cache = new MuteCache();
        List<Protokoll.MuteEintrag> eintraege = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) {
            eintraege.add(new Protokoll.MuteEintrag(UUID.randomUUID(), "Spieler" + i, "Grund", "Mod",
                    jetzt, jetzt + 3_600_000L, "lobby" + (i % 20)));
        }
        cache.ersetzeAlle(eintraege);
        return cache;
    }

    private static double messung(MuteCache cache, UUID ziel, String scope, long jetzt) {
        for (int i = 0; i < 20_000; i++) {
            cache.findeAktiv(ziel, scope, jetzt);
        }
        double best = Double.MAX_VALUE;
        for (int batch = 0; batch < 5; batch++) {
            int runden = 20_000;
            long start = System.nanoTime();
            for (int i = 0; i < runden; i++) {
                cache.findeAktiv(ziel, scope, jetzt);
            }
            double usProOp = (System.nanoTime() - start) / (double) runden / 1000.0;
            best = Math.min(best, usProOp);
        }
        return best;
    }

    @Test
    @DisplayName("F7: findeAktiv hält Budget <50µs/Op bei 10k Mutes")
    void findeAktivBudget10k() {
        long jetzt = System.currentTimeMillis();
        MuteCache cache = cacheMit(10_000, jetzt);
        UUID ziel = cache.alle().get(5_000).uuid();
        String scope = cache.alle().get(5_000).scope();
        double us = messung(cache, ziel, scope, jetzt + 1000);
        assertTrue(us < 50.0, "findeAktiv 10k: " + us + " µs/Op (Budget <50 µs)");
    }

    @Test
    @DisplayName("F7: findeAktiv skaliert auch bei 50k Mutes unter Budget")
    void findeAktivBudget50k() {
        long jetzt = System.currentTimeMillis();
        MuteCache cache = cacheMit(50_000, jetzt);
        UUID ziel = cache.alle().get(25_000).uuid();
        String scope = cache.alle().get(25_000).scope();
        double us = messung(cache, ziel, scope, jetzt + 1000);
        assertTrue(us < 50.0, "findeAktiv 50k: " + us + " µs/Op (Budget <50 µs)");
    }
}
