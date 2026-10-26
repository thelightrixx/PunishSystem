package de.lightrixx.punish.model;

import java.util.Objects;

public record NoteRecord(
        int nummer,
        String spielerName,
        String text,
        String ausfuehrer,
        long erstelltAm
) {
    public NoteRecord {
        if (nummer < 1) {
            throw new IllegalArgumentException("Die Notiz-Nummer muss mindestens 1 sein.");
        }
        if (spielerName == null || spielerName.isBlank()) {
            throw new IllegalArgumentException("Der Spielername darf nicht leer sein.");
        }
        Objects.requireNonNull(text, "Der Text darf nicht null sein.");
        if (text.length() > 1024) {
            throw new IllegalArgumentException("Der Text ist zu lang (max. 1024 Zeichen).");
        }
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        if (erstelltAm <= 0) {
            throw new IllegalArgumentException("Der Erstellungszeitpunkt muss positiv sein.");
        }
    }

    public NoteRecord mitText(String neuerText) {
        return new NoteRecord(nummer, spielerName, neuerText, ausfuehrer, erstelltAm);
    }
}
