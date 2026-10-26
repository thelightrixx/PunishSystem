package de.lightrixx.punish.service;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.OnlineSpieler;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class ChatAbstand {

    private static final long FENSTER_MILLIS = 1500L;

    private static final int MAX_EINTRAEGE = 5000;

    private static final long TTL_MILLIS = 10L * 60L * 1000L;

    private static final ConcurrentHashMap<String, Long> ZULETZT = new ConcurrentHashMap<>();

    private ChatAbstand() {
    }

    static boolean brauchtFuehrendeLeerzeile(BefehlsSender empfaenger) {
        Objects.requireNonNull(empfaenger, "Der Empfänger darf nicht null sein.");
        return brauchtFuehrendeLeerzeile(empfaenger.abstandsSchluessel());
    }

    static boolean brauchtFuehrendeLeerzeile(OnlineSpieler spieler) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        return brauchtFuehrendeLeerzeile("player:" + spieler.uuid());
    }

    static void markiere(BefehlsSender empfaenger) {
        Objects.requireNonNull(empfaenger, "Der Empfänger darf nicht null sein.");
        markiere(empfaenger.abstandsSchluessel());
    }

    static void markiere(OnlineSpieler spieler) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        markiere("player:" + spieler.uuid());
    }

    static void markiere(String schluessel) {
        long jetzt = System.currentTimeMillis();
        synchronized (ZULETZT) {
            if (ZULETZT.size() >= MAX_EINTRAEGE) {

                ZULETZT.entrySet().removeIf(eintrag -> jetzt - eintrag.getValue() > TTL_MILLIS);
                if (ZULETZT.size() >= MAX_EINTRAEGE) {
                    int ueberschuss = ZULETZT.size() - MAX_EINTRAEGE + 1;
                    var iterator = ZULETZT.entrySet().iterator();
                    while (ueberschuss > 0 && iterator.hasNext()) {
                        iterator.next();
                        iterator.remove();
                        ueberschuss--;
                    }
                }
            }
            ZULETZT.put(schluessel, jetzt);
        }
    }

    static boolean brauchtFuehrendeLeerzeile(String schluessel) {
        Long zuletzt = ZULETZT.get(schluessel);
        if (zuletzt == null) {
            return true;
        }
        return System.currentTimeMillis() - zuletzt > FENSTER_MILLIS;
    }
}
