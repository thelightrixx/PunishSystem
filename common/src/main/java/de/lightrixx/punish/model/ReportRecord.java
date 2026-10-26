package de.lightrixx.punish.model;

import java.util.Objects;
import java.util.UUID;

public record ReportRecord(
        long id,
        UUID zielUuid,
        String zielName,
        UUID reporterUuid,
        String reporterName,
        String grund,
        String server,
        long erstelltAm,
        ReportStatus status,
        String bearbeiter,
        long bearbeitetAm,
        String closeGrund
) {
    public ReportRecord {
        if (id < 1) {
            throw new IllegalArgumentException("Die Report-ID muss mindestens 1 sein.");
        }
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        if (zielName == null || zielName.isBlank()) {
            throw new IllegalArgumentException("Der Zielname darf nicht leer sein.");
        }
        if (reporterName == null || reporterName.isBlank()) {
            throw new IllegalArgumentException("Der Reportername darf nicht leer sein.");
        }
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        if (grund.isBlank()) {
            throw new IllegalArgumentException("Der Grund darf nicht leer sein.");
        }
        if (grund.length() > 256) {
            throw new IllegalArgumentException("Der Grund ist zu lang (max. 256 Zeichen).");
        }
        if (server == null || server.isBlank()) {
            server = "unbekannt";
        }
        if (erstelltAm <= 0) {
            throw new IllegalArgumentException("Der Erstellungszeitpunkt muss positiv sein.");
        }
        if (status == null) {
            status = ReportStatus.OFFEN;
        }
        if (bearbeiter == null) {
            bearbeiter = "";
        }
        if (closeGrund == null) {
            closeGrund = "";
        }
    }

    public boolean istOffen() {
        return status != null && status.istOffen();
    }

    public ReportRecord mitStatus(ReportStatus neuerStatus, String bearbeiterName, String grund) {
        return new ReportRecord(id, zielUuid, zielName, reporterUuid, reporterName,
                this.grund, server, erstelltAm,
                neuerStatus == null ? ReportStatus.OFFEN : neuerStatus,
                bearbeiterName == null ? "" : bearbeiterName,
                System.currentTimeMillis(),
                grund == null ? "" : grund);
    }
}
