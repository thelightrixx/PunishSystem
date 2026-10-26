package de.lightrixx.punish.model;

public enum ReportStatus {
    OFFEN,
    ANGENOMMEN,
    ABGELEHNT,
    ERLEDIGT;

    public static ReportStatus parse(String roh) {
        if (roh == null || roh.isBlank()) {
            return null;
        }
        String normiert = roh.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return ReportStatus.valueOf(normiert);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean istOffen() {
        return this == OFFEN || this == ANGENOMMEN;
    }
}
