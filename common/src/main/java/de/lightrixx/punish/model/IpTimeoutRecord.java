package de.lightrixx.punish.model;

import java.util.Objects;

public record IpTimeoutRecord(
        String ip,
        String grund,
        String ausfuehrer,
        long erstelltAm,
        long ablaufAm,
        ServerScope scope
) {

    public IpTimeoutRecord(String ip, String grund, String ausfuehrer, long erstelltAm, long ablaufAm) {
        this(ip, grund, ausfuehrer, erstelltAm, ablaufAm, ServerScope.unbekannt());
    }

    public IpTimeoutRecord {
        Objects.requireNonNull(ip, "Die IP-Adresse darf nicht null sein.");
        if (scope == null) {
            throw new IllegalArgumentException("Der Scope darf nicht null sein (nutze ServerScope.unbekannt()).");
        }
        ip = IpBanRecord.kanonisieren(ip);
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        if (grund.length() > 1024) {
            throw new IllegalArgumentException("Der Grund ist zu lang (max. 1024 Zeichen).");
        }
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        if (erstelltAm <= 0) {
            throw new IllegalArgumentException("Der Erstellungszeitpunkt muss positiv sein.");
        }
        if (ablaufAm <= erstelltAm) {
            throw new IllegalArgumentException(
                    "Der Ablaufzeitpunkt muss nach dem Erstellungszeitpunkt liegen.");
        }
    }

    public boolean istAktiv(long jetztMillis) {
        return ablaufAm > jetztMillis;
    }

    public boolean istAktivFuer(long jetztMillis, String aktuellerServer) {
        return istAktiv(jetztMillis) && scope.giltFuer(aktuellerServer);
    }

    public boolean istAbgelaufen(long jetztMillis) {
        return ablaufAm <= jetztMillis;
    }

    public long verbleibendeMillis(long jetztMillis) {
        return Math.max(0, ablaufAm - jetztMillis);
    }
}
