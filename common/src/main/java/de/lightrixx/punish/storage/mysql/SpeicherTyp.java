package de.lightrixx.punish.storage.mysql;

import java.util.Locale;

public enum SpeicherTyp {
    YAML,
    MYSQL;

    public static SpeicherTyp ausWert(String roh) {
        if (roh == null || roh.isBlank()) {
            return YAML;
        }
        String normiert = roh.trim().toLowerCase(Locale.ROOT);
        return switch (normiert) {
            case "mysql", "mariadb", "db", "sql", "datenbank" -> MYSQL;
            default -> YAML;
        };
    }

    public static SpeicherTyp ausWertStrikt(String roh) {
        if (roh == null || roh.isBlank()) {
            return YAML;
        }
        String normiert = roh.trim().toLowerCase(Locale.ROOT);
        if (normiert.equals("yaml") || normiert.equals("yml") || normiert.equals("datei")) {
            return YAML;
        }
        if (normiert.equals("mysql") || normiert.equals("mariadb") || normiert.equals("db")
                || normiert.equals("sql") || normiert.equals("datenbank")) {
            return MYSQL;
        }
        throw new IllegalArgumentException("Unbekannter Speichertyp: '" + roh + "'");
    }

    public String speicherwert() {
        return name().toLowerCase(Locale.ROOT);
    }
}
