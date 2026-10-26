package de.lightrixx.punish.command;

import java.util.Objects;

public record PunishmentsQuery(
        PunishmentsFilter filter,
        ZielTyp zielTyp,
        String spielerName,
        int seite
) {
    public PunishmentsQuery {
        Objects.requireNonNull(filter, "Der Filter darf nicht null sein.");
        Objects.requireNonNull(zielTyp, "Der Ziel-Typ darf nicht null sein.");
        if (zielTyp == ZielTyp.SPIELER && (spielerName == null || spielerName.isBlank())) {
            throw new IllegalArgumentException("Bei Ziel SPIELER muss ein Spielername angegeben sein.");
        }
        if (seite < 1) {
            throw new IllegalArgumentException("Die Seite muss mindestens 1 sein.");
        }
    }

    public enum ZielTyp {
        SELF,
        SPIELER,
        SERVER
    }
}
