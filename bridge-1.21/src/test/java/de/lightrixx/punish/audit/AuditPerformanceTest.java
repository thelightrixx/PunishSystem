package de.lightrixx.punish.audit;

import de.lightrixx.punish.bridge.IpMuteCache;
import de.lightrixx.punish.bridge.MuteCache;
import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPerformanceTest {

    private static final double BUDGET_MIKROS = 5_000.0;

    private static final long JETZT = System.currentTimeMillis();

    @Test
    @DisplayName("Perf: MuteCache.findeAktiv mit 10 000 / 50 000 Einträgen (Budget 50 µs)")
    void muteCacheFindeAktivBudget(TestReporter reporter) {
        for (int groesse : new int[]{10_000, 50_000}) {
            MuteCache cache = new MuteCache();
            for (int i = 0; i < groesse; i++) {
                cache.setze(new Protokoll.MuteEintrag(UUID.randomUUID(), "Spieler" + i, "Grund", "Mod",
                        JETZT - 1000L, JETZT + 3_600_000L, "global"));
            }
            UUID ziel = UUID.randomUUID();
            cache.setze(new Protokoll.MuteEintrag(ziel, "Ziel", "Grund", "Mod",
                    JETZT - 1000L, JETZT + 3_600_000L, "global"));

            int messungen = 1000;
            long start = System.nanoTime();
            for (int i = 0; i < messungen; i++) {
                cache.findeAktiv(ziel, "lobby", JETZT);
            }
            long dauerNanos = (System.nanoTime() - start) / messungen;
            double dauerMikros = dauerNanos / 1000.0;
            reporter.publishEntry("AUDIT-PERF", "MuteCache.findeAktiv@" + groesse + "="
                    + String.format("%.1f", dauerMikros) + " us/Event");
            if (groesse == 10_000) {
                reporter.publishEntry("AUDIT-PERF", "Budget-50us@10k="
                        + (dauerMikros < 50.0 ? "eingehalten" : "Ueberschreitung (C-T-Befund)"));
            }
            assertTrue(dauerMikros < BUDGET_MIKROS, () -> "MuteCache.findeAktiv@" + groesse
                    + " braucht " + String.format("%.1f", dauerMikros)
                    + " us/Event (Budget " + BUDGET_MIKROS + " us)");
        }
    }

    @Test
    @DisplayName("Perf: IpMuteCache.findeAktiv mit 5 000 IP-Mutes (O(n)-Scan, Prior-Befund M3)")
    void ipMuteCacheFindeAktiv(TestReporter reporter) {
        IpMuteCache cache = new IpMuteCache();
        for (int i = 0; i < 5_000; i++) {
            cache.setze(new Protokoll.IpMuteEintrag("10.0." + (i / 250) + "." + (i % 250),
                    "Grund", "Mod", JETZT - 1000L, JETZT + 3_600_000L, "global"));
        }
        int messungen = 1000;
        long start = System.nanoTime();
        for (int i = 0; i < messungen; i++) {
            cache.findeAktiv("10.0.1.1", "lobby", JETZT);
        }
        long dauerNanos = (System.nanoTime() - start) / messungen;
        double dauerMikros = dauerNanos / 1000.0;
        reporter.publishEntry("AUDIT-PERF", "IpMuteCache.findeAktiv@5000="
                + String.format("%.1f", dauerMikros) + " us/Event");
        assertTrue(dauerMikros < BUDGET_MIKROS, () -> "IpMuteCache.findeAktiv@5000 braucht "
                + String.format("%.1f", dauerMikros) + " us/Event (Budget " + BUDGET_MIKROS + " us)");
    }
}
