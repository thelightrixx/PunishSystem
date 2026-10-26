package de.lightrixx.punish.util;

import de.lightrixx.punish.model.ParseResult;

import java.util.Locale;
import java.util.OptionalLong;

public final class TimeParser {

    private static final long SEKUNDE = 1000L;
    private static final long MINUTE = 60L * SEKUNDE;
    private static final long STUNDE = 60L * MINUTE;
    private static final long TAG = 24L * STUNDE;
    private static final long WOCHE = 7L * TAG;
    private static final long MONAT = 30L * TAG;
    private static final long JAHR = 365L * TAG;

    public ParseResult parsen(String eingabe) {
        if (eingabe == null) {
            return new ParseResult.Ungueltig();
        }

        String getrimmt = eingabe.trim();
        if (getrimmt.isEmpty()) {
            return new ParseResult.Ungueltig();
        }

        String klein = getrimmt.toLowerCase(Locale.ROOT);
        if (klein.equals("perm") || klein.equals("permanent") || klein.equals("perma")) {
            return new ParseResult.Permanent();
        }

        String[] gruppen = getrimmt.split("\\s+");
        long gesamtMillis = 0L;
        for (String gruppe : gruppen) {
            if (gruppe.isEmpty()) {
                continue;
            }
            String gruppeKlein = gruppe.toLowerCase(Locale.ROOT);
            if (gruppeKlein.equals("perm") || gruppeKlein.equals("permanent") || gruppeKlein.equals("perma")) {
                return new ParseResult.Ungueltig();
            }
            OptionalLong beitrag = parseGruppe(gruppe);
            if (beitrag.isEmpty()) {
                return new ParseResult.Ungueltig();
            }
            try {
                gesamtMillis = Math.addExact(gesamtMillis, beitrag.getAsLong());
            } catch (ArithmeticException e) {
                return new ParseResult.Ungueltig();
            }
        }

        if (gesamtMillis <= 0) {
            return new ParseResult.Ungueltig();
        }
        if (gesamtMillis > 100L * JAHR) {
            return new ParseResult.Ungueltig();
        }
        return new ParseResult.Geltzeit(gesamtMillis);
    }

    private OptionalLong parseGruppe(String normalisiert) {
        long gruppenMillis = 0L;
        int index = 0;
        int laenge = normalisiert.length();

        while (index < laenge) {
            int zahlStart = index;
            while (index < laenge && Character.isDigit(normalisiert.charAt(index))) {
                index++;
            }
            if (index == zahlStart) {
                return OptionalLong.empty();
            }

            long wert;
            try {
                wert = Long.parseLong(normalisiert.substring(zahlStart, index));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }

            int suffixStart = index;
            while (index < laenge && Character.isLetter(normalisiert.charAt(index))) {
                index++;
            }
            String suffix = normalisiert.substring(suffixStart, index);

            long multiplikator = multiplikatorFuer(suffix);
            if (multiplikator < 0) {
                return OptionalLong.empty();
            }

            try {
                long beitrag = Math.multiplyExact(wert, multiplikator);
                gruppenMillis = Math.addExact(gruppenMillis, beitrag);
            } catch (ArithmeticException e) {
                return OptionalLong.empty();
            }
        }

        return OptionalLong.of(gruppenMillis);
    }

    private long multiplikatorFuer(String suffix) {
        if (suffix == null) {
            return -1L;
        }

        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case "", "m", "min", "minute", "minuten", "mins" -> MINUTE;
            case "s", "sek", "sec", "secs", "sekunde", "sekunden" -> SEKUNDE;
            case "h", "std", "stunde", "stunden" -> STUNDE;
            case "d", "t", "tag", "tage", "tagen" -> TAG;
            case "w", "woche", "wochen" -> WOCHE;
            case "mo", "monat", "monate", "monaten" -> MONAT;
            case "y", "j", "jahr", "jahre", "jahren" -> JAHR;
            default -> -1L;
        };
    }
}
