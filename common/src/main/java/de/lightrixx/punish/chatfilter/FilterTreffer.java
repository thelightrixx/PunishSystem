package de.lightrixx.punish.chatfilter;

import java.util.Objects;

public record FilterTreffer(FilterKanal kanal, TrefferQuelle quelle, double bewertung, String textHash) {

    public FilterTreffer {
        Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        Objects.requireNonNull(quelle, "Die Quelle darf nicht null sein.");
        if (Double.isNaN(bewertung) || bewertung < 0.0d || bewertung > 1.0d) {
            throw new IllegalArgumentException("Die Bewertung muss zwischen 0 und 1 liegen.");
        }
        if (textHash == null || textHash.isBlank()) {
            throw new IllegalArgumentException("Der Text-Hash darf nicht leer sein.");
        }
    }
}
