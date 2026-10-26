package de.lightrixx.punish.model;

import java.util.Objects;

public record HistoryEntry(
        long id,
        HistoryType typ,
        String ausfuehrer,
        String ziel,
        String grund,
        long zeitstempel,
        Long ablauf,
        boolean aktiv,
        String scope,
        String herkunft
) {

    public HistoryEntry(long id, HistoryType typ, String ausfuehrer, String ziel,
                        String grund, long zeitstempel, Long ablauf, boolean aktiv) {
        this(id, typ, ausfuehrer, ziel, grund, zeitstempel, ablauf, aktiv,
                ServerScope.UNBEKANNT_WERT, ServerScope.UNBEKANNT_WERT);
    }

    public HistoryEntry {
        if (id < 1) {
            throw new IllegalArgumentException("Die History-ID muss mindestens 1 sein.");
        }
        Objects.requireNonNull(typ, "Der History-Typ darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Objects.requireNonNull(ziel, "Das Ziel darf nicht null sein.");
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        if (zeitstempel <= 0) {
            throw new IllegalArgumentException("Der Zeitstempel muss positiv sein.");
        }
        if (ablauf != null && ablauf <= zeitstempel) {
            throw new IllegalArgumentException(
                    "Der Ablaufzeitpunkt muss nach dem Erstellungszeitstempel liegen.");
        }
        if (scope == null || scope.isBlank()) {
            scope = ServerScope.UNBEKANNT_WERT;
        } else {
            scope = scope.trim().toLowerCase(java.util.Locale.ROOT);
        }
        if (herkunft == null || herkunft.isBlank()) {
            herkunft = ServerScope.UNBEKANNT_WERT;
        } else {
            herkunft = herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public boolean istEffektivAktiv(long jetztMillis) {
        if (!aktiv) {
            return false;
        }
        if (ablauf == null) {
            return true;
        }
        return ablauf > jetztMillis;
    }

    public HistoryEntry mitAktiv(boolean neuerAktivStatus) {
        return new HistoryEntry(id, typ, ausfuehrer, ziel, grund, zeitstempel, ablauf, neuerAktivStatus,
                scope, herkunft);
    }

    public ServerScope scopeAlsObjekt() {
        return ServerScope.ausSpeicherwert(scope);
    }
}
