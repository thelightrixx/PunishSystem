package de.lightrixx.punish.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TabHinweis {

    private TabHinweis() {
    }

    public static List<String> filtere(List<String> vorschlaege, String prefix) {
        if (vorschlaege == null || vorschlaege.isEmpty()) {
            return List.of();
        }
        if (prefix == null || prefix.isEmpty()) {
            List<String> sauber = new ArrayList<>(vorschlaege.size());
            for (String vorschlag : vorschlaege) {
                if (vorschlag != null) {
                    sauber.add(vorschlag);
                }
            }
            return sauber;
        }
        String vergleich = prefix.toLowerCase(Locale.ROOT);
        List<String> ergebnis = new ArrayList<>();
        for (String vorschlag : vorschlaege) {
            if (vorschlag != null && vorschlag.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                ergebnis.add(vorschlag);
            }
        }
        return ergebnis;
    }

    public static List<String> begrenzeDistinct(List<String> vorschlaege, int limit) {
        if (vorschlaege == null || vorschlaege.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> gesehen = new LinkedHashSet<>();
        List<String> ergebnis = new ArrayList<>(Math.min(limit, vorschlaege.size()));
        for (String vorschlag : vorschlaege) {
            if (vorschlag == null || vorschlag.isBlank()) {
                continue;
            }
            if (gesehen.add(vorschlag)) {
                ergebnis.add(vorschlag);
                if (ergebnis.size() >= limit) {
                    break;
                }
            }
        }
        return ergebnis;
    }

    public static String[] teile(String rest) {
        if (rest == null || rest.isEmpty()) {
            return new String[]{""};
        }
        boolean nachgestelltesLeerzeichen = Character.isWhitespace(rest.charAt(rest.length() - 1));
        String getrimmt = rest.trim();
        if (getrimmt.isEmpty()) {
            return new String[]{""};
        }
        String[] teile = getrimmt.split("\\s+");
        if (!nachgestelltesLeerzeichen) {
            return teile;
        }
        String[] mitLeer = new String[teile.length + 1];
        System.arraycopy(teile, 0, mitLeer, 0, teile.length);
        mitLeer[teile.length] = "";
        return mitLeer;
    }

    public static String praefixFuer(String rest) {
        if (rest == null || rest.isEmpty()) {
            return "";
        }
        int idx = -1;
        for (int i = rest.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(rest.charAt(i))) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return "";
        }
        return rest.substring(0, idx + 1);
    }

    public static boolean hatGrundBegonnen(String[] args, int grundStartIndex) {
        if (args == null || grundStartIndex < 0) {
            return false;
        }
        return args.length > grundStartIndex + 1;
    }

    public static String tooltipText(String grund, String dauer) {
        if (grund == null || grund.isBlank() || dauer == null || dauer.isBlank()) {
            return "";
        }
        return "Empf. Dauer: " + dauer.trim();
    }

    public static String kategorieTooltip(String vorschlag) {
        if (vorschlag == null || vorschlag.isBlank()) {
            return "";
        }
        String getrimmt = vorschlag.trim();
        if (getrimmt.equals("-s")) {
            return "Still: keine Broadcasts";
        }
        if (getrimmt.equalsIgnoreCase("confirm")) {
            return "Bestätigung erforderlich";
        }
        if (getrimmt.startsWith("--")) {
            if (getrimmt.equalsIgnoreCase("--global")) {
                return "Gültigkeit: ganzes Netzwerk";
            }
            if (getrimmt.equalsIgnoreCase("--unbekannt")) {
                return "Gültigkeit: unbekannt";
            }
            if (getrimmt.equalsIgnoreCase("--server=")) {
                return "Gültigkeit: Server wählen";
            }
            return "Gültigkeit: nur dieser Server";
        }
        String klein = getrimmt.toLowerCase(Locale.ROOT);
        switch (klein) {
            case "add":
                return "Hinzufügen";
            case "delete":
                return "Löschen";
            case "remove":
                return "Entfernen";
            case "list":
                return "Auflisten";
            case "clear":
                return "Zurücksetzen";
            case "show":
                return "Anzeigen";
            case "info":
                return "Details anzeigen";
            case "accept":
                return "Annehmen";
            case "deny":
                return "Ablehnen";
            case "close":
                return "Schließen";
            case "offen":
                return "Offene Einträge";
            case "alle":
                return "Alle Einträge";
            default:
                break;
        }
        if (klein.startsWith("level=") || klein.startsWith("stufe=")) {
            return "Stufe";
        }
        if (klein.startsWith("dauer=") || klein.startsWith("duration=") || klein.startsWith("zeit=")) {
            return "Dauer setzen";
        }
        if (klein.startsWith("grund=")) {
            return "Grund setzen";
        }
        if (klein.startsWith("text=")) {
            return "Text setzen";
        }
        int trenner = Math.max(getrimmt.indexOf(':'), getrimmt.indexOf('='));
        if (trenner > 0) {
            String schluessel = getrimmt.substring(0, trenner).trim().toLowerCase(Locale.ROOT);
            switch (schluessel) {
                case "filter", "typ", "type":
                    return "Filter";
                case "player", "spieler", "target", "ziel", "p":
                    return "Spieler";
                case "page", "seite":
                    return "Seite";
                default:
                    break;
            }
        }
        if (getrimmt.matches("\\d+")) {
            return "Nummer";
        }
        if (dauerFoermig(getrimmt)) {
            return "Dauer";
        }
        return "";
    }

    public static boolean dauerFoermig(String vorschlag) {
        if (vorschlag == null || vorschlag.isBlank()) {
            return false;
        }
        String klein = vorschlag.trim().toLowerCase(Locale.ROOT);
        if (klein.equals("perm") || klein.equals("permanent") || klein.equals("perma")) {
            return true;
        }
        return klein.matches("\\d+\\s*(s|sec|secs|sek|min|mins|m|h|hour|hours|d|day|days|w|week|weeks|mo|month|months|y|year|years)\\b.*");
    }
}
