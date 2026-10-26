package de.lightrixx.punish.command;

import java.util.Locale;
import java.util.Objects;

public final class PunishmentsQueryParser {

    private PunishmentsQueryParser() {
    }

    public record Ergebnis(PunishmentsQuery query, String unbekannterFilter, String unbekannterSchluessel) {
        public Ergebnis {
            Objects.requireNonNull(query, "Die Query darf nicht null sein.");
        }

        public boolean erfolgreich() {
            return unbekannterFilter == null && unbekannterSchluessel == null;
        }
    }

    public static Ergebnis parsen(String[] args) {
        PunishmentsFilter filter = PunishmentsFilter.ALL;
        String unbekannterFilter = null;
        String unbekannterSchluessel = null;
        PunishmentsQuery.ZielTyp zielTyp = null;
        String spielerName = null;
        int seite = 1;

        if (args != null) {
            for (String roh : args) {
                if (roh == null || roh.isBlank()) {
                    continue;
                }
                String token = roh.trim();
                int trenner = indexVonTrenner(token);
                if (trenner >= 0) {
                    String schluessel = token.substring(0, trenner).trim().toLowerCase(Locale.ROOT);
                    String wert = token.substring(trenner + 1).trim();
                    switch (schluessel) {
                        case "filter", "typ", "type" -> {
                            var parsed = PunishmentsFilter.parsen(wert);
                            if (parsed.isPresent()) {
                                filter = parsed.get();
                            } else if (unbekannterFilter == null) {
                                unbekannterFilter = wert;
                            }
                        }
                        case "player", "spieler", "target", "ziel", "p" -> {
                            var ziel = zielAusWert(wert);
                            if (ziel != null) {
                                zielTyp = ziel.typ();
                                spielerName = ziel.name();
                            }
                        }
                        case "page", "seite" -> {
                            int parsed = seiteAusWert(wert);
                            if (parsed >= 1) {
                                seite = parsed;
                            }
                        }
                        default -> {
                            if (unbekannterSchluessel == null) {
                                unbekannterSchluessel = schluessel;
                            }
                        }
                    }
                } else if (istSelf(token)) {
                    zielTyp = PunishmentsQuery.ZielTyp.SELF;
                    spielerName = null;
                } else if (istServer(token)) {
                    zielTyp = PunishmentsQuery.ZielTyp.SERVER;
                    spielerName = null;
                } else if (token.matches("\\d+")) {
                    try {
                        long zahl = Long.parseLong(token);
                        if (zahl >= 1 && zahl <= Integer.MAX_VALUE) {
                            seite = (int) zahl;
                        }
                    } catch (NumberFormatException e) {
                        org.slf4j.LoggerFactory.getLogger(PunishmentsQueryParser.class).trace("Ungültige Seitenzahl übersprungen.", e);
                    }
                } else {
                    zielTyp = PunishmentsQuery.ZielTyp.SPIELER;
                    spielerName = token;
                }
            }
        }

        if (zielTyp == null) {
            zielTyp = PunishmentsQuery.ZielTyp.SELF;
            spielerName = null;
        }
        PunishmentsQuery query = new PunishmentsQuery(filter, zielTyp, spielerName, seite);
        return new Ergebnis(query, unbekannterFilter, unbekannterSchluessel);
    }

    private static int indexVonTrenner(String token) {
        int doppelpunkt = token.indexOf(':');
        int gleich = token.indexOf('=');
        if (doppelpunkt < 0) {
            return gleich;
        }
        if (gleich < 0) {
            return doppelpunkt;
        }
        return Math.min(doppelpunkt, gleich);
    }

    private record ZielTreffer(PunishmentsQuery.ZielTyp typ, String name) {
    }

    private static ZielTreffer zielAusWert(String wert) {
        if (wert == null || wert.isBlank()) {
            return null;
        }
        String kandidat = wert.trim();
        if (istSelf(kandidat)) {
            return new ZielTreffer(PunishmentsQuery.ZielTyp.SELF, null);
        }
        if (istServer(kandidat)) {
            return new ZielTreffer(PunishmentsQuery.ZielTyp.SERVER, null);
        }
        return new ZielTreffer(PunishmentsQuery.ZielTyp.SPIELER, kandidat);
    }

    private static boolean istSelf(String wert) {
        String norm = wert.toLowerCase(Locale.ROOT);
        return norm.equals("self") || norm.equals("me") || norm.equals("ich")
                || norm.equals("mich") || norm.equals("own") || norm.equals("eigen")
                || norm.equals("eigene") || norm.equals("meine");
    }

    private static boolean istServer(String wert) {
        String norm = wert.toLowerCase(Locale.ROOT);
        return norm.equals("server") || norm.equals("global") || norm.equals("alle")
                || norm.equals("all") || norm.equals("*");
    }

    private static int seiteAusWert(String wert) {
        if (wert == null || !wert.matches("\\d+")) {
            return -1;
        }
        try {
            long zahl = Long.parseLong(wert);
            return zahl >= 1 && zahl <= Integer.MAX_VALUE ? (int) zahl : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
