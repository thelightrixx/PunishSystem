package de.lightrixx.punish.command;

import de.lightrixx.punish.model.ServerScope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ScopeParser {

    private ScopeParser() {
    }

    public record Ergebnis(String[] bereinigteArgs, ServerScope scope, boolean hatteFlag, boolean silent) {
        public Ergebnis(String[] bereinigteArgs, ServerScope scope, boolean hatteFlag) {
            this(bereinigteArgs, scope, hatteFlag, false);
        }
    }

    public record SilentErgebnis(String[] bereinigteArgs, boolean silent) {
    }

    public static final String SILENT_FLAG = "-s";

    public static boolean istSilentFlag(String arg) {
        return SILENT_FLAG.equals(arg);
    }

    public static Ergebnis parsen(String[] args, ServerScope standard, Collection<String> bekannteServer) {
        return parsen(args, standard, bekannteServer, false);
    }

    public static Ergebnis parsen(String[] args, ServerScope standard, Collection<String> bekannteServer,
                                  boolean unbekanntErlaubt) {
        Objects.requireNonNull(args, "args darf nicht null sein.");
        Objects.requireNonNull(standard, "standard darf nicht null sein.");
        if (args.length == 0) {
            return new Ergebnis(args, standard, false, false);
        }

        String[] aktuell = args;
        ServerScope wirksam = standard;
        boolean hatteFlag = false;
        boolean silent = false;
        while (aktuell.length > 0) {
            String letztes = aktuell[aktuell.length - 1];
            if (istSilentFlag(letztes)) {
                silent = true;
                String[] bereinigt = new String[aktuell.length - 1];
                System.arraycopy(aktuell, 0, bereinigt, 0, aktuell.length - 1);
                aktuell = bereinigt;
                continue;
            }
            if (letztes == null || !letztes.startsWith("--") || letztes.length() < 3) {
                break;
            }
            ServerScope scope = scopeAusFlag(letztes, bekannteServer, unbekanntErlaubt);
            if (scope == null) {
                break;
            }
            wirksam = scope;
            hatteFlag = true;
            String[] bereinigt = new String[aktuell.length - 1];
            System.arraycopy(aktuell, 0, bereinigt, 0, aktuell.length - 1);
            aktuell = bereinigt;
        }
        return new Ergebnis(aktuell, wirksam, hatteFlag, silent);
    }

    public static SilentErgebnis parsenSilent(String[] args) {
        if (args == null || args.length == 0) {
            return new SilentErgebnis(args == null ? new String[0] : args, false);
        }
        String[] aktuell = args;
        boolean silent = false;
        while (aktuell.length > 0 && istSilentFlag(aktuell[aktuell.length - 1])) {
            silent = true;
            String[] bereinigt = new String[aktuell.length - 1];
            System.arraycopy(aktuell, 0, bereinigt, 0, aktuell.length - 1);
            aktuell = bereinigt;
        }
        return new SilentErgebnis(aktuell, silent);
    }

    public static String[] strippeScopeFlags(String[] args, Collection<String> bekannteServer,
                                             boolean unbekanntErlaubt) {
        if (args == null || args.length == 0) {
            return args == null ? new String[0] : args;
        }
        String[] aktuell = args;
        while (aktuell.length > 0) {
            String letztes = aktuell[aktuell.length - 1];
            if (istSilentFlag(letztes)) {
                String[] bereinigt = new String[aktuell.length - 1];
                System.arraycopy(aktuell, 0, bereinigt, 0, aktuell.length - 1);
                aktuell = bereinigt;
                continue;
            }
            if (!istScopeFlag(letztes)) {
                break;
            }
            ServerScope scope = scopeAusFlag(letztes, bekannteServer, unbekanntErlaubt);
            if (scope == null) {
                break;
            }
            if (!istBekannterScopeFuerStrip(letztes, scope, bekannteServer, unbekanntErlaubt)) {
                break;
            }
            String[] bereinigt = new String[aktuell.length - 1];
            System.arraycopy(aktuell, 0, bereinigt, 0, aktuell.length - 1);
            aktuell = bereinigt;
        }
        return aktuell;
    }

    private static boolean istBekannterScopeFuerStrip(String flag, ServerScope scope,
                                                      Collection<String> bekannteServer,
                                                      boolean unbekanntErlaubt) {
        if (scope.typ() == ServerScope.Typ.GLOBAL) {
            return true;
        }
        if (scope.typ() == ServerScope.Typ.UNBEKANNT) {
            return unbekanntErlaubt;
        }
        if (flag != null) {
            String rest = flag.substring(2).trim();
            String lower = rest.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("server=")) {
                return true;
            }
        }
        if (bekannteServer == null || bekannteServer.isEmpty()) {
            return false;
        }
        try {
            String kandidat = scope.typ() == ServerScope.Typ.SERVER ? scope.server() : null;
            if (kandidat == null || kandidat.isBlank()) {
                return false;
            }
            for (String bekannt : bekannteServer) {
                String normiert = ServerScope.normalisiereServerName(bekannt);
                if (normiert != null && normiert.equalsIgnoreCase(kandidat.trim())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    static boolean siehtNachScopeVersuchAus(String arg) {
        if (arg == null || !arg.startsWith("--") || arg.length() < 3) {
            return false;
        }
        String rest = arg.substring(2).trim();
        if (rest.isEmpty()) {
            return false;
        }

        if (rest.contains(" ") || rest.contains("\t")) {
            return false;
        }
        return rest.matches("[A-Za-z0-9_\\-=.]+");
    }

    static ServerScope scopeAusFlag(String flag, Collection<String> bekannteServer) {
        return scopeAusFlag(flag, bekannteServer, false);
    }

    static ServerScope scopeAusFlag(String flag, Collection<String> bekannteServer, boolean unbekanntErlaubt) {
        if (flag == null || !flag.startsWith("--")) {
            return null;
        }
        String rest = flag.substring(2).trim();
        if (rest.isEmpty()) {
            return null;
        }
        if (rest.equalsIgnoreCase(ServerScope.GLOBAL_WERT)) {
            return ServerScope.global();
        }

        if (rest.equalsIgnoreCase(ServerScope.UNBEKANNT_WERT)) {
            return unbekanntErlaubt ? ServerScope.unbekannt() : null;
        }
        String lower = rest.toLowerCase(Locale.ROOT);
        if (lower.startsWith("server=")) {
            String name = rest.substring("server=".length()).trim();
            String normiert = ServerScope.normalisiereServerName(name);
            return normiert == null ? null : ServerScope.server(normiert);
        }

        if (lower.equals("server")) {
            return null;
        }
        String normiert = ServerScope.normalisiereServerName(rest);
        if (normiert == null) {
            return null;
        }
        return ServerScope.server(normiert);
    }

    public static List<String> vorschlaege(String aktuellesArg, Collection<String> bekannteServer) {
        return vorschlaege(aktuellesArg, bekannteServer, false);
    }

    public static List<String> vorschlaege(String aktuellesArg, Collection<String> bekannteServer,
                                           boolean unbekanntErlaubt) {
        String prefix = aktuellesArg == null ? "--" : aktuellesArg;
        if (!prefix.startsWith("--")) {
            return List.of();
        }
        String vergleich = prefix.toLowerCase(Locale.ROOT);
        List<String> ergebnis = new ArrayList<>();
        if ("--global".startsWith(vergleich)) {
            ergebnis.add("--global");
        }
        if (unbekanntErlaubt && "--unbekannt".startsWith(vergleich)) {
            ergebnis.add("--unbekannt");
        }
        if (bekannteServer != null) {
            List<String> server = new ArrayList<>();
            for (String roh : bekannteServer) {
                String normiert = ServerScope.normalisiereServerName(roh);
                if (normiert == null || server.contains(normiert)) {
                    continue;
                }
                String kandidat = "--" + normiert;
                if (kandidat.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                    server.add(normiert);
                }
            }
            server.sort(String.CASE_INSENSITIVE_ORDER);
            for (String name : server) {
                ergebnis.add("--" + name);
            }
        }

        if ("--server=".startsWith(vergleich) && !ergebnis.contains("--server=")) {

            ergebnis.add("--server=");
        }
        return ergebnis;
    }

    public static boolean istScopeFlag(String arg) {
        return arg != null && arg.startsWith("--") && arg.length() >= 3;
    }

    public static String saeubereGrund(String grund) {
        return saeubereGrund(grund, null);
    }

    public static String saeubereGrund(String grund, Collection<String> bekannteServer) {
        if (grund == null || grund.isBlank()) {
            return grund == null ? "" : grund;
        }
        String getrimmt = grund.trim();
        boolean geaendert = true;
        while (geaendert) {
            geaendert = false;
            int letztesLeer = getrimmt.lastIndexOf(' ');
            String letztes;
            String rest;
            if (letztesLeer < 0) {
                letztes = getrimmt;
                rest = "";
            } else {
                letztes = getrimmt.substring(letztesLeer + 1);
                rest = getrimmt.substring(0, letztesLeer).trim();
            }
            if (istSilentFlag(letztes)) {
                if (!rest.isEmpty()) {
                    getrimmt = rest;
                    geaendert = true;
                    continue;
                }
                return "";
            }
            if (istScopeFlag(letztes) && siehtNachScopeVersuchAus(letztes)
                    && istStripbarerScope(letztes, bekannteServer)) {
                if (!rest.isEmpty()) {
                    getrimmt = rest;
                    geaendert = true;
                    continue;
                }
                return "";
            }
            break;
        }
        return getrimmt;
    }

    private static boolean istStripbarerScope(String flag, Collection<String> bekannteServer) {
        ServerScope scope = scopeAusFlag(flag, bekannteServer, true);
        if (scope == null) {
            return false;
        }
        if (scope.typ() == ServerScope.Typ.GLOBAL || scope.typ() == ServerScope.Typ.UNBEKANNT) {
            return true;
        }
        if (scope.typ() != ServerScope.Typ.SERVER) {
            return false;
        }
        String rest = flag.substring(2).trim();
        if (rest.toLowerCase(java.util.Locale.ROOT).startsWith("server=")) {
            return true;
        }
        if (bekannteServer == null || bekannteServer.isEmpty()) {
            return false;
        }
        String kandidat = scope.server();
        if (kandidat == null || kandidat.isBlank()) {
            return false;
        }
        for (String bekannt : bekannteServer) {
            if (bekannt != null && bekannt.trim().equalsIgnoreCase(kandidat.trim())) {
                return true;
            }
        }
        return false;
    }
}
