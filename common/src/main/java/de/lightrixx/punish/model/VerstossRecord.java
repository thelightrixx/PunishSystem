package de.lightrixx.punish.model;

import java.util.Objects;

public record VerstossRecord(
        int nummer,
        String spielerName,
        String grund,
        String ausfuehrer,
        long erstelltAm,
        VerstossStufe stufe
) {
    public VerstossRecord {
        if (nummer < 1) {
            throw new IllegalArgumentException("Die Verstoß-Nummer muss mindestens 1 sein.");
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
        Objects.requireNonNull(stufe, "Die Stufe darf nicht null sein.");
    }

    public VerstossRecord(int nummer, String spielerName, String grund, String ausfuehrer,
                          long erstelltAm) {
        this(nummer, spielerName, grund, ausfuehrer, erstelltAm, VerstossStufe.standard());
    }

    public VerstossRecord mitGrund(String neuerGrund) {
        return new VerstossRecord(nummer, spielerName, neuerGrund, ausfuehrer, erstelltAm, stufe);
    }

    public VerstossRecord mitStufe(VerstossStufe neueStufe) {
        return new VerstossRecord(nummer, spielerName, grund, ausfuehrer, erstelltAm,
                neueStufe == null ? stufe : neueStufe);
    }
}
