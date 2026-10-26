package de.lightrixx.punish.bridge;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class TunnelAuthBremse {

    static final int SPIELER_FEHLER_MAX = 8;
    static final int GLOBAL_FEHLER_MAX = 300;
    static final long FENSTER_MILLIS = 60L * 1000L;
    static final long SPERRE_MILLIS = 5L * 60L * 1000L;

    private static final class Zustand {
        private final AtomicInteger fehler = new AtomicInteger();
        private volatile long fensterStart;
        private volatile long sperreBis;
    }

    private final ConcurrentHashMap<UUID, Zustand> spielerZustaende = new ConcurrentHashMap<>();
    private final AtomicInteger globaleFehler = new AtomicInteger();
    private volatile long globalesFensterStart;

    boolean gesperrt(UUID spielerId, long jetzt) {
        Zustand zustand = spielerZustaende.get(spielerId);
        if (zustand == null) {
            return false;
        }
        if (zustand.sperreBis > jetzt) {
            return true;
        }
        if (zustand.sperreBis != 0L) {
            zustand.sperreBis = 0L;
            zustand.fehler.set(0);
            zustand.fensterStart = jetzt;
        }
        return false;
    }

    void erfolg(UUID spielerId) {
        spielerZustaende.remove(spielerId);
    }

    void fehlschlag(UUID spielerId, long jetzt) {
        Zustand zustand = spielerZustaende.computeIfAbsent(spielerId, k -> new Zustand());
        if (zustand.fensterStart == 0L || jetzt - zustand.fensterStart > FENSTER_MILLIS) {
            zustand.fensterStart = jetzt;
            zustand.fehler.set(0);
        }
        if (zustand.fehler.incrementAndGet() >= SPIELER_FEHLER_MAX && zustand.sperreBis <= jetzt) {
            zustand.sperreBis = jetzt + SPERRE_MILLIS;
        }
        globalenFehlschlag(jetzt);
    }

    boolean globalGesperrt(long jetzt) {
        if (globalesFensterStart == 0L || jetzt - globalesFensterStart > FENSTER_MILLIS) {
            return false;
        }
        return globaleFehler.get() >= GLOBAL_FEHLER_MAX;
    }

    private void globalenFehlschlag(long jetzt) {
        if (globalesFensterStart == 0L || jetzt - globalesFensterStart > FENSTER_MILLIS) {
            globalesFensterStart = jetzt;
            globaleFehler.set(0);
        }
        globaleFehler.incrementAndGet();
    }
}
