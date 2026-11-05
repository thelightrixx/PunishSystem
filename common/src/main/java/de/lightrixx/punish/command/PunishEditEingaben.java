package de.lightrixx.punish.command;

import de.lightrixx.punish.command.PunishEditCommand.Felder;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.TimeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

final class PunishEditEingaben {

    private static final Logger LOG = LoggerFactory.getLogger(PunishEditEingaben.class);

    private final TimeParser timeParser;

    PunishEditEingaben(TimeParser timeParser) {
        this.timeParser = Objects.requireNonNull(timeParser, "Der TimeParser darf nicht null sein.");
    }

    Felder parseFelder(String[] args, int start) {
        StringBuilder grundSammler = new StringBuilder();
        StringBuilder legacySammler = new StringBuilder();
        boolean grundModus = false;
        boolean grundExplizit = false;
        VerstossStufe stufe = null;
        boolean stufeUngueltig = false;
        String stufeRoh = "";
        Long dauerMillis = null;
        boolean dauerUngueltig = false;
        String dauerRoh = "";
        boolean perm = false;
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            int gleich = token.indexOf('=');
            if (gleich > 0) {
                String key = token.substring(0, gleich).trim().toLowerCase(Locale.ROOT);
                String wert = token.substring(gleich + 1);
                switch (key) {
                    case "grund", "reason", "text" -> {
                        grundModus = true;
                        grundExplizit = true;
                        anhaengen(grundSammler, wert);
                    }
                    case "level", "stufe" -> {
                        String w = wert.trim();
                        var parsed = VerstossStufe.parse(w);
                        if (parsed.isPresent()) {
                            stufe = parsed.get();
                        } else {
                            stufeUngueltig = true;
                            stufeRoh = w;
                        }
                        grundModus = false;
                    }
                    case "dauer", "duration", "zeit", "time" -> {
                        String w = wert.trim();
                        if (w.equalsIgnoreCase("perm") || w.equalsIgnoreCase("permanent")
                                || w.equalsIgnoreCase("perma")) {
                            perm = true;
                        } else {
                            ParseResult parsed = timeParser.parsen(w);
                            if (parsed instanceof ParseResult.Geltzeit(long millis)) {
                                dauerMillis = millis;
                            } else if (parsed instanceof ParseResult.Permanent) {
                                perm = true;
                            } else {
                                dauerUngueltig = true;
                                dauerRoh = w;
                            }
                        }
                        grundModus = false;
                    }
                    default -> {
                        if (grundModus) {
                            anhaengen(grundSammler, token);
                        } else {
                            anhaengen(legacySammler, token);
                        }
                    }
                }
            } else if (grundModus) {
                anhaengen(grundSammler, token);
            } else {
                anhaengen(legacySammler, token);
            }
        }
        String grund = grundSammler.isEmpty() ? legacySammler.toString() : grundSammler.toString();
        return new Felder(PunishCommand.normalisiereGrund(grund), stufe, stufeUngueltig, stufeRoh,
                dauerMillis, dauerUngueltig, dauerRoh, perm, grundExplizit);
    }

    Felder parseFelderMitLegacyDauer(String[] args, int start) {
        Felder basis = parseFelder(args, start);
        if (basis.dauerMillis() != null || basis.perm() || basis.dauerUngueltig() || basis.grundExplizit()) {
            return basis;
        }
        if (basis.grund() == null || basis.grund().isBlank()) {
            return basis;
        }
        String[] teile = basis.grund().split(" ", 2);
        String kandidat = teile[0];
        ParseResult parsed = timeParser.parsen(kandidat);
        if (parsed instanceof ParseResult.Geltzeit(long millis)) {
            String rest = teile.length > 1 ? teile[1] : "";
            return new Felder(PunishCommand.normalisiereGrund(rest), basis.stufe(), basis.stufeUngueltig(), basis.stufeRoh(),
                    millis, false, "", false, false);
        }
        if (parsed instanceof ParseResult.Permanent) {
            String rest = teile.length > 1 ? teile[1] : "";
            return new Felder(PunishCommand.normalisiereGrund(rest), basis.stufe(), basis.stufeUngueltig(), basis.stufeRoh(),
                    null, false, "", true, false);
        }
        boolean siehtNachDauerAus = kandidat.matches("(?i)^[0-9]+[a-z]*$|^(perm|permanent|perma)$");
        if (siehtNachDauerAus) {
            return new Felder(basis.grund(), basis.stufe(), basis.stufeUngueltig(), basis.stufeRoh(),
                    null, true, kandidat, false, false);
        }
        return basis;
    }

    static void anhaengen(StringBuilder sammler, String teil) {
        if (teil == null) {
            return;
        }
        String getrimmt = teil.trim();
        if (getrimmt.isEmpty() && sammler.isEmpty()) {
            return;
        }
        if (!sammler.isEmpty()) {
            sammler.append(" ");
        }
        sammler.append(teil);
    }

    Optional<Integer> parseNummer(String roh) {
        if (roh == null || roh.isBlank()) {
            return Optional.empty();
        }
        int trenner = roh.indexOf(' ');
        String teil = (trenner >= 0 ? roh.substring(0, trenner) : roh).trim();
        try {
            int nummer = Integer.parseInt(teil);
            return nummer >= 1 ? Optional.of(nummer) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    int skipVorschauTokens(String[] args, int nummerIndex, int nummer, VerstossRecord record) {
        if (record == null || args.length <= nummerIndex + 1) {
            return 0;
        }
        String kennung;
        try {
            kennung = MiniMessageUtil.miniMessage().escapeTags(
                    de.lightrixx.punish.model.VerstossKennung.kennung(record));
        } catch (Exception e) {
            LOG.debug("Verstoß-Vorschau konnte nicht gelesen werden.", e);
            return 0;
        }
        return skipTokenSequenz(args, nummerIndex, nummer, kennung.split(" "));
    }

    int skipVorschauTokens(String[] args, int nummerIndex, int nummer, String vorschau) {
        if (vorschau == null || vorschau.isBlank() || args.length <= nummerIndex + 1) {
            return 0;
        }
        String sauber;
        try {
            sauber = MiniMessageUtil.miniMessage().escapeTags(vorschau);
        } catch (Exception e) {
            LOG.debug("Vorschau konnte nicht maskiert werden.", e);
            sauber = vorschau;
        }
        return skipTokenSequenz(args, nummerIndex, nummer, sauber.split(" "));
    }

    int skipTokenSequenz(String[] args, int nummerIndex, int nummer, String[] tokens) {
        if (tokens == null || tokens.length == 0) {
            return 0;
        }
        int start = nummerIndex + 1;
        if (args.length >= start + tokens.length) {
            boolean treffer = true;
            for (int i = 0; i < tokens.length; i++) {
                if (!tokens[i].equals(args[start + i])) {
                    treffer = false;
                    break;
                }
            }
            if (treffer) {
                return tokens.length;
            }
        }

        if (tokens.length > 1 && args.length > nummerIndex && args[nummerIndex] != null
                && args[nummerIndex].trim().equals(nummer + " " + tokens[0])) {
            String[] rest = Arrays.copyOfRange(tokens, 1, tokens.length);
            if (args.length >= start + rest.length) {
                boolean treffer = true;
                for (int i = 0; i < rest.length; i++) {
                    if (!rest[i].equals(args[start + i])) {
                        treffer = false;
                        break;
                    }
                }
                if (treffer) {
                    return rest.length;
                }
            }
        }
        return 0;
    }
}
