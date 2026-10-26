package de.lightrixx.punish.model;

import java.util.Locale;
import java.util.Objects;

public record IpStrafSchluessel(String ip, String scope) {

    public IpStrafSchluessel {
        Objects.requireNonNull(ip, "Die IP darf nicht null sein.");
        ip = ip.trim().toLowerCase(Locale.ROOT);
        if (scope == null || scope.isBlank()) {
            scope = ServerScope.UNBEKANNT_WERT;
        } else {
            scope = scope.trim().toLowerCase(Locale.ROOT);
        }
    }

    public static IpStrafSchluessel von(String ip, ServerScope scope) {
        return new IpStrafSchluessel(ip,
                scope == null ? ServerScope.UNBEKANNT_WERT : scope.speicherwert());
    }
}
