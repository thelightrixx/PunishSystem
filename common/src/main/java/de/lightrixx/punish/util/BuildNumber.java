package de.lightrixx.punish.util;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildNumber implements Comparable<BuildNumber> {

    private static final Pattern MUSTER_MINUTE =
            Pattern.compile("^([0-9]{2})([0-9]{2})([0-9]{2})-([0-9]{2})([0-9]{2})$");
    private static final Pattern MUSTER_STUNDE =
            Pattern.compile("^([0-9]{2})([0-9]{2})([0-9]{2})-([0-9]{2})$");
    private static final DateTimeFormatter DATUM_FORMAT =
            DateTimeFormatter.ofPattern("ddMMyy-HHmm", Locale.ROOT);
    private static final DateTimeFormatter DATUM_ANZEIGE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'", Locale.GERMANY);

    private final LocalDateTime zeitpunkt;

    private BuildNumber(LocalDateTime zeitpunkt) {
        this.zeitpunkt = Objects.requireNonNull(zeitpunkt, "zeitpunkt")
                .withSecond(0)
                .withNano(0);
    }

    public static BuildNumber datum(LocalDateTime zeitpunkt) {
        return new BuildNumber(zeitpunkt);
    }

    public static BuildNumber parse(String eingabe) {
        BuildNumber nummer = tryParse(eingabe).orElse(null);
        if (nummer == null) {
            throw new IllegalArgumentException(
                    "Ungueltige Buildnummer '" + eingabe + "' (erwartet DDMMYY-HHMM, z. B. 011126-1853).");
        }
        return nummer;
    }

    public static BuildNumber parsen(String eingabe) {
        return parse(eingabe);
    }

    public static Optional<BuildNumber> tryParse(String eingabe) {
        if (eingabe == null) {
            return Optional.empty();
        }
        return parseDatum(eingabe.trim());
    }

    private static Optional<BuildNumber> parseDatum(String getrimmt) {
        Matcher matcher = MUSTER_MINUTE.matcher(getrimmt);
        if (matcher.matches()) {
            return baue(matcher.group(1), matcher.group(2), matcher.group(3),
                    matcher.group(4), matcher.group(5));
        }
        matcher = MUSTER_STUNDE.matcher(getrimmt);
        if (matcher.matches()) {
            return baue(matcher.group(1), matcher.group(2), matcher.group(3),
                    matcher.group(4), "00");
        }
        return Optional.empty();
    }

    private static Optional<BuildNumber> baue(String tag, String monat, String jahr,
                                              String stunde, String minute) {
        int t = Integer.parseInt(tag);
        int m = Integer.parseInt(monat);
        int j = 2000 + Integer.parseInt(jahr);
        int h = Integer.parseInt(stunde);
        int min = Integer.parseInt(minute);
        try {
            return Optional.of(new BuildNumber(LocalDateTime.of(j, m, t, h, min)));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    public static boolean istGueltig(String eingabe) {
        return tryParse(eingabe).isPresent();
    }

    public Optional<LocalDateTime> zeitpunkt() {
        return Optional.of(zeitpunkt);
    }

    public BuildNumber naechsterBuild() {
        return new BuildNumber(zeitpunkt.plusMinutes(1));
    }

    @Override
    public int compareTo(BuildNumber andere) {
        if (andere == null) {
            return 1;
        }
        return zeitpunkt.compareTo(andere.zeitpunkt);
    }

    @Override
    public String toString() {
        return DATUM_FORMAT.format(zeitpunkt);
    }

    public String beschreibung() {
        return toString() + " (Build vom " + DATUM_ANZEIGE.format(zeitpunkt) + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof BuildNumber andere && zeitpunkt.equals(andere.zeitpunkt);
    }

    @Override
    public int hashCode() {
        return zeitpunkt.hashCode();
    }
}
