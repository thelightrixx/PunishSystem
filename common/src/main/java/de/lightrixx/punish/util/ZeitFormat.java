package de.lightrixx.punish.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class ZeitFormat {

    private static final long SEKUNDE = 1000L;
    private static final long MINUTE = 60L * SEKUNDE;
    private static final long STUNDE = 60L * MINUTE;
    private static final long TAG = 24L * STUNDE;

    private record ZonenFormat(ZoneId zone, DateTimeFormatter format) {}
    private static volatile ZonenFormat zonenFormat = erstelleFormat(ZoneId.of("Europe/Berlin"));

    private static ZonenFormat erstelleFormat(ZoneId zone) {
        return new ZonenFormat(zone, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone));
    }

    public static void setAnzeigeZone(ZoneId zone) {
        if (zone == null) {
            return;
        }
        zonenFormat = erstelleFormat(zone);
    }

    public static ZoneId anzeigeZone() {
        return zonenFormat.zone();
    }

    private ZeitFormat() {
    }

    public static String formatRestzeit(long millis) {
        if (millis <= 0) {
            return "abgelaufen";
        }
        long tage = millis / TAG;
        long stunden = (millis / STUNDE) % 24;
        long minuten = (millis / MINUTE) % 60;
        long sekunden = (millis / SEKUNDE) % 60;

        List<String> teile = new ArrayList<>();
        if (tage > 0) {
            teile.add(formatEinheit(tage, "Tag", "Tage"));
        }
        if (stunden > 0) {
            teile.add(formatEinheit(stunden, "Stunde", "Stunden"));
        }
        if (minuten > 0) {
            teile.add(formatEinheit(minuten, "Minute", "Minuten"));
        }
        if (teile.isEmpty()) {
            long angezeigteSekunden = Math.max(1, sekunden);
            teile.add(formatEinheit(angezeigteSekunden, "Sekunde", "Sekunden"));
        }
        return String.join(", ", teile);
    }

    public static String formatZeitpunkt(long millis) {
        try {
            ZonenFormat aktuell = zonenFormat;
            return Instant.ofEpochMilli(millis).atZone(aktuell.zone()).format(aktuell.format());
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    private static String formatEinheit(long anzahl, String singular, String plural) {
        return anzahl + " " + (anzahl == 1 ? singular : plural);
    }
}
