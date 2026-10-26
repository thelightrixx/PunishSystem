package de.lightrixx.punish.chatfilter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class WortlistenFilter {

    private static final int MIN_LAENGE_ZERLEGT = 4;

    private final List<Eintrag> eintraege;

    public WortlistenFilter(List<String> zeilen) {
        Objects.requireNonNull(zeilen, "Die Zeilen dürfen nicht null sein.");
        var sauber = zeilen.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(zeile -> !zeile.isEmpty() && !zeile.startsWith("#"))
                .map(WortlistenFilter::gueltigerEintrag)
                .flatMap(Optional::stream)
                .distinct()
                .map(Eintrag::von)
                .toList();
        if (sauber.isEmpty()) {
            throw new IllegalArgumentException("Die Wortliste enthält keine Einträge.");
        }
        this.eintraege = sauber;
    }

    public static Optional<String> gueltigerEintrag(String roh) {
        if (roh == null) {
            return Optional.empty();
        }
        String getrimmt = roh.trim();
        if (getrimmt.length() < 2 || getrimmt.length() > 50) {
            return Optional.empty();
        }
        if (getrimmt.contains("\n") || getrimmt.contains("\r")) {
            return Optional.empty();
        }
        String normiert = TextNormalisierer.normalisiere(getrimmt);
        if (normiert.length() < 2) {
            return Optional.empty();
        }
        return Optional.of(normiert);
    }

    public Optional<String> ersterTreffer(String rohtext) {
        Objects.requireNonNull(rohtext, "Der Text darf nicht null sein.");
        if (rohtext.isBlank()) {
            return Optional.empty();
        }
        String normiert = TextNormalisierer.normalisiere(rohtext);
        if (normiert.isEmpty()) {
            return Optional.empty();
        }
        String normiertKompakt = TextNormalisierer.ohneAbstaende(normiert);
        String kollabiert = kollabiereWiederholungen(normiert);
        String kollabiertKompakt = kollabiereWiederholungen(normiertKompakt);
        boolean varianteNoetig = !kollabiert.equals(normiert) || !kollabiertKompakt.equals(normiertKompakt);
        var kern = Textkern.von(normiert, false);
        var kernKollabiert = varianteNoetig ? Textkern.von(kollabiert, true) : null;
        for (Eintrag eintrag : eintraege) {
            if (eintrag.passt(kern)
                    || eintrag.passtKollabiert(kern)
                    || (kernKollabiert != null
                            && (eintrag.passt(kernKollabiert) || eintrag.passtKollabiert(kernKollabiert)))) {
                return Optional.of(eintrag.normal());
            }
        }
        return Optional.empty();
    }

    public List<String> eintraege() {
        return eintraege.stream().map(Eintrag::normal).toList();
    }

    static String kollabiereWiederholungen(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == c) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Textvariante mit kompaktem Suchschlüssel: Der Schlüssel ist der Text ohne
     * Leerzeichen (bei der Kollabiert-Variante zusätzlich mit gekürzten
     * Zeichen-Wiederholungen); pro Schlüsselposition ist vermerkt, ob sie an
     * einem Wortanfang des Textes beginnt. Kompakt-Treffer zählen nur ab
     * Wortanfang (F-009): „arsch“ trifft „arschloch“ und „hurensohn1“ trifft
     * „hurensohn“, aber „arsch“ trifft weder „Marschieren“ noch „war schon“.
     */
    private record Textkern(String text, String schluessel, boolean[] wortanfaenge) {

        static Textkern von(String text, boolean kollabiere) {
            StringBuilder schluessel = new StringBuilder(text.length());
            boolean[] wortanfaenge = new boolean[text.length()];
            boolean wortanfang = true;
            char vorher = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ' ') {
                    wortanfang = true;
                    continue;
                }
                if (kollabiere && c == vorher) {
                    continue;
                }
                vorher = c;
                wortanfaenge[schluessel.length()] = wortanfang;
                schluessel.append(c);
                wortanfang = false;
            }
            return new Textkern(text, schluessel.toString(), wortanfaenge);
        }

        boolean enthaeltAbWortanfang(String muster) {
            if (muster.isEmpty()) {
                return false;
            }
            int treffer = schluessel.indexOf(muster);
            while (treffer >= 0) {
                if (wortanfaenge[treffer]) {
                    return true;
                }
                treffer = schluessel.indexOf(muster, treffer + 1);
            }
            return false;
        }
    }

    private record Eintrag(String normal, String kompakt, String kollabiert, String kollabiertKompakt) {

        static Eintrag von(String normalisiert) {
            String kompakt = TextNormalisierer.ohneAbstaende(normalisiert);
            return new Eintrag(normalisiert, kompakt, kollabiereWiederholungen(normalisiert),
                    kollabiereWiederholungen(kompakt));
        }

        boolean passt(Textkern kern) {
            if ((" " + kern.text() + " ").contains(" " + normal + " ")) {
                return true;
            }
            return !normal.contains(" ") && normal.length() >= MIN_LAENGE_ZERLEGT
                    && kern.enthaeltAbWortanfang(kompakt);
        }

        boolean passtKollabiert(Textkern kern) {
            if (kollabiert.equals(normal) && kollabiertKompakt.equals(kompakt)) {
                return false;
            }
            if ((" " + kern.text() + " ").contains(" " + kollabiert + " ")) {
                return true;
            }
            return !kollabiert.contains(" ") && kollabiert.length() >= MIN_LAENGE_ZERLEGT
                    && kern.enthaeltAbWortanfang(kollabiertKompakt);
        }
    }
}
