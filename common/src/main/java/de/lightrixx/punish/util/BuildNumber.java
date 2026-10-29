package de.lightrixx.punish.util;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildNumber implements Comparable<BuildNumber> {

    private static final Pattern MUSTER =
            Pattern.compile("^([A-Za-z]{2})([0-9]{2})([A-Za-z])?$");

    private final String train;
    private final int nummer;
    private final Stabilitaet stabilitaet;

    private BuildNumber(String train, int nummer, Stabilitaet stabilitaet) {
        this.train = train;
        this.nummer = nummer;
        this.stabilitaet = stabilitaet;
    }

    public enum Stabilitaet {
        A('a'), B('b'), C('c'), D('d'), E('e'), F('f'), G('g'), H('h'),
        I('i'), J('j'), K('k'), L('l'), M('m'), N('n'), O('o'), P('p'),
        Q('q'), R('r'), S('s'), T('t'), U('u'), V('v'), W('w'), X('x'),
        Y('y'), Z('z');

        private final char code;

        Stabilitaet(char code) {
            this.code = code;
        }

        public char code() {
            return code;
        }

        public String beschreibung() {
            return switch (code) {
                case 'a' -> "Final – stabilste Stufe, Release-reif";
                case 'b' -> "Release Candidate – fast final";
                case 'c' -> "Beta – feature-fertig, Testphase";
                case 'd' -> "Alpha – Features unfertig, instabil";
                default -> "Experimentell (" + code + ") – nur fuer Entwickler";
            };
        }

        public boolean featureFertig() {
            return code <= 'c';
        }

        public static Stabilitaet vonCode(char code) {
            char klein = Character.toLowerCase(code);
            if (klein < 'a' || klein > 'z') {
                throw new IllegalArgumentException("Unbekannte Stabilitaet '" + code + "' (erlaubt: a-z).");
            }
            return values()[klein - 'a'];
        }
    }

    public static BuildNumber von(String train, int nummer, Stabilitaet stabilitaet) {
        Objects.requireNonNull(train, "train");
        Objects.requireNonNull(stabilitaet, "stabilitaet");
        String normTrain = train.trim().toUpperCase(Locale.ROOT);
        if (!normTrain.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Train muss genau 2 Buchstaben A-Z sein: '" + train + "'.");
        }
        if (nummer < 0 || nummer > 99) {
            throw new IllegalArgumentException("Nummer muss 00-99 sein: " + nummer);
        }
        return new BuildNumber(normTrain, nummer, stabilitaet);
    }

    public static BuildNumber von(String train, int nummer) {
        Objects.requireNonNull(train, "train");
        String normTrain = train.trim().toUpperCase(Locale.ROOT);
        if (!normTrain.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Train muss genau 2 Buchstaben A-Z sein: '" + train + "'.");
        }
        if (nummer < 0 || nummer > 99) {
            throw new IllegalArgumentException("Nummer muss 00-99 sein: " + nummer);
        }
        return new BuildNumber(normTrain, nummer, null);
    }

    public static BuildNumber parse(String eingabe) {
        BuildNumber nummer = tryParse(eingabe).orElse(null);
        if (nummer == null) {
            throw new IllegalArgumentException(
                    "Ungueltige Buildnummer '" + eingabe + "' (erwartet LLNN oder LLNNS, z. B. EA26 oder EA01c).");
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
        String getrimmt = eingabe.trim();
        Matcher matcher = MUSTER.matcher(getrimmt);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String train = matcher.group(1).toUpperCase(Locale.ROOT);
        int nummer;
        try {
            nummer = Integer.parseInt(matcher.group(2), 10);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String stufenGruppe = matcher.group(3);
        if (stufenGruppe == null || stufenGruppe.isEmpty()) {
            return Optional.of(new BuildNumber(train, nummer, null));
        }
        char stufe = Character.toLowerCase(stufenGruppe.charAt(0));
        if (stufe < 'a' || stufe > 'z') {
            return Optional.empty();
        }
        return Optional.of(new BuildNumber(train, nummer, Stabilitaet.vonCode(stufe)));
    }

    public static boolean istGueltig(String eingabe) {
        return tryParse(eingabe).isPresent();
    }

    public static BuildNumber erster(String train) {
        return von(train, 1, Stabilitaet.D);
    }

    public static String trainFuer(int major, int minor) {
        return "" + buchstabeFuer(major, "Major") + buchstabeFuer(minor, "Minor");
    }

    private static char buchstabeFuer(int wert, String name) {
        if (wert < 0 || wert > 25) {
            throw new IllegalArgumentException(
                    name + "-Version " + wert + " ist nicht als Buchstabe kodierbar (erlaubt: 0-25).");
        }
        return (char) ('A' + wert);
    }

    public static BuildNumber ersterFuerVersion(int major, int minor) {
        return von(trainFuer(major, minor), 1, Stabilitaet.D);
    }

    public String train() {
        return train;
    }

    public int trainMajor() {
        return train.charAt(0) - 'A';
    }

    public int trainMinor() {
        return train.charAt(1) - 'A';
    }

    public int nummer() {
        return nummer;
    }

    public String nummerFormatiert() {
        return String.format(Locale.ROOT, "%02d", nummer);
    }

    public Stabilitaet stabilitaet() {
        return stabilitaet;
    }

    public BuildNumber naechsterBuild() {
        if (nummer >= 99) {
            throw new IllegalStateException("Build-Zaehler am Limit (99), neue Minor-Version noetig.");
        }
        return new BuildNumber(train, nummer + 1, stabilitaet);
    }

    public BuildNumber mitStabilitaet(Stabilitaet neu) {
        Objects.requireNonNull(neu, "neu");
        return new BuildNumber(train, nummer, neu);
    }

    public boolean istMindestens(Stabilitaet mindest) {
        Objects.requireNonNull(mindest, "mindest");
        if (stabilitaet == null) {
            return true;
        }
        return stabilitaet.ordinal() <= mindest.ordinal();
    }

    @Override
    public int compareTo(BuildNumber andere) {
        if (andere == null) {
            return 1;
        }
        int cmp = train.compareTo(andere.train);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(nummer, andere.nummer);
        if (cmp != 0) {
            return cmp;
        }
        // Bewusst umgekehrt zu UpdateVergleich: hier sortiert die Freigabe (null)
        // unter die Beta-Stufen; UpdateVergleich behandelt die Freigabe als neueste.
        return Integer.compare(stufenRang(stabilitaet), stufenRang(andere.stabilitaet));
    }

    private static int stufenRang(Stabilitaet stufe) {
        return stufe == null ? -1 : stufe.ordinal();
    }

    @Override
    public String toString() {
        if (stabilitaet == null) {
            return train + nummerFormatiert();
        }
        return train + nummerFormatiert() + stabilitaet.code();
    }

    public String beschreibung() {
        if (stabilitaet == null) {
            return toString() + " (Freigabe – ohne Stufenbuchstabe)";
        }
        return toString() + " (" + stabilitaet.beschreibung() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BuildNumber andere)) {
            return false;
        }
        return nummer == andere.nummer
                && train.equals(andere.train)
                && stabilitaet == andere.stabilitaet;
    }

    @Override
    public int hashCode() {
        return Objects.hash(train, nummer, stabilitaet);
    }
}
