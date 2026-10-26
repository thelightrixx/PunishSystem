package de.lightrixx.punish.model;

import java.util.Objects;
import java.util.UUID;

public record BanRecord(
        UUID spielerUuid,
        String spielerName,
        String grund,
        String ausfuehrer,
        long erstelltAm,
        Long ablaufAm,
        boolean autoBann,
        ServerScope scope
) {

    public BanRecord(UUID spielerUuid, String spielerName, String grund,
                     String ausfuehrer, long erstelltAm, Long ablaufAm, boolean autoBann) {
        this(spielerUuid, spielerName, grund, ausfuehrer, erstelltAm, ablaufAm, autoBann,
                ServerScope.unbekannt());
    }

    public BanRecord {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        if (scope == null) {
            throw new IllegalArgumentException("Der Scope darf nicht null sein (nutze ServerScope.unbekannt()).");
        }
        if (spielerName == null || spielerName.isBlank()) {
            throw new IllegalArgumentException("Der Spielername darf nicht leer sein.");
        }
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        if (grund.length() > 1024) {
            throw new IllegalArgumentException("Der Grund ist zu lang (max. 1024 Zeichen).");
        }
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        if (erstelltAm <= 0) {
            throw new IllegalArgumentException("Der Erstellungszeitpunkt muss positiv sein.");
        }

        if (ablaufAm != null && (ablaufAm <= 0 || ablaufAm == -1L || ablaufAm == Long.MAX_VALUE)) {
            ablaufAm = null;
        }
        if (ablaufAm != null && ablaufAm <= erstelltAm) {
            throw new IllegalArgumentException(
                    "Der Ablaufzeitpunkt muss nach dem Erstellungszeitpunkt liegen.");
        }
    }

    public boolean istPermanent() {
        return ablaufAm == null;
    }

    public boolean istAktiv(long jetztMillis) {
        if (istPermanent()) {
            return true;
        }
        return ablaufAm > jetztMillis;
    }

    public boolean istAktivFuer(long jetztMillis, String aktuellerServer) {
        return istAktiv(jetztMillis) && scope.giltFuer(aktuellerServer);
    }

    public boolean istAbgelaufen(long jetztMillis) {
        return !istPermanent() && ablaufAm <= jetztMillis;
    }

    public long verbleibendeMillis(long jetztMillis) {
        if (istPermanent()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, ablaufAm - jetztMillis);
    }
}
