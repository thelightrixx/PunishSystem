package de.lightrixx.punish.model;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record StrafSchluessel(UUID uuid, String scope) {

    public StrafSchluessel {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        if (scope == null || scope.isBlank()) {
            scope = ServerScope.UNBEKANNT_WERT;
        } else {
            scope = scope.trim().toLowerCase(Locale.ROOT);
        }
    }

    public static StrafSchluessel von(UUID uuid, ServerScope scope) {
        return new StrafSchluessel(uuid,
                scope == null ? ServerScope.UNBEKANNT_WERT : scope.speicherwert());
    }
}
