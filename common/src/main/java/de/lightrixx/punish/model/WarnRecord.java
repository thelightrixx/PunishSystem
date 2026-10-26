package de.lightrixx.punish.model;

import java.util.Objects;

public record WarnRecord(
        int nummer,
        String spielerName,
        String grund,
        String ausfuehrer,
        long erstelltAm
) {
    public WarnRecord {
        if (nummer < 1) {
            throw new IllegalArgumentException("Die Warnungs-Nummer muss mindestens 1 sein.");
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
    }

    public WarnRecord mitGrund(String neuerGrund) {
        return new WarnRecord(nummer, spielerName, neuerGrund, ausfuehrer, erstelltAm);
    }
}
