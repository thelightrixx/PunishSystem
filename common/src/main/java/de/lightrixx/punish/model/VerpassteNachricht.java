package de.lightrixx.punish.model;

import java.util.Objects;

public record VerpassteNachricht(
        long id,
        String typ,
        String text,
        long zeitstempel
) {

    public VerpassteNachricht {
        if (id < 1) {
            throw new IllegalArgumentException("Die Nachrichten-ID muss mindestens 1 sein.");
        }
        if (typ == null || typ.isBlank()) {
            throw new IllegalArgumentException("Der Broadcast-Typ darf nicht leer sein.");
        }
        Objects.requireNonNull(text, "Der Nachrichtentext darf nicht null sein.");
        if (zeitstempel <= 0) {
            throw new IllegalArgumentException("Der Zeitstempel muss positiv sein.");
        }
        typ = typ.trim();
        if (typ.isBlank()) {
            throw new IllegalArgumentException("Der Broadcast-Typ darf nicht leer sein.");
        }
    }
}
