package de.lightrixx.punish.model;

import java.util.Locale;
import java.util.Optional;

public enum VerstossStufe {

    LOW,
    MEDIUM,
    HIGH;

    public String schluessel() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static VerstossStufe standard() {
        return MEDIUM;
    }

    public static Optional<VerstossStufe> parse(String roh) {
        if (roh == null || roh.isBlank()) {
            return Optional.empty();
        }
        return switch (roh.trim().toLowerCase(Locale.ROOT)) {
            case "low", "leicht", "klein", "minor", "niedrig" -> Optional.of(LOW);
            case "medium", "mittel", "normal", "moderat" -> Optional.of(MEDIUM);
            case "high", "hoch", "schwer", "gravierend", "major", "schwerwiegend" ->
                    Optional.of(HIGH);
            default -> Optional.empty();
        };
    }

    public static VerstossStufe parseOderStandard(String roh) {
        return parse(roh).orElse(standard());
    }

    public String standardDauerText() {
        return switch (this) {
            case LOW -> "30d";
            case MEDIUM -> "90d";
            case HIGH -> "365d";
        };
    }

    public long standardDauerMillis() {
        return switch (this) {
            case LOW -> 30L * 24L * 60L * 60L * 1000L;
            case MEDIUM -> 90L * 24L * 60L * 60L * 1000L;
            case HIGH -> 365L * 24L * 60L * 60L * 1000L;
        };
    }

    public int standardPunkte() {
        return switch (this) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }
}
