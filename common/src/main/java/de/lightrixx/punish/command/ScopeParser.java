package de.lightrixx.punish.command;

import de.lightrixx.punish.model.ServerScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ScopeParser {

    private ScopeParser() {
    }

    public record Ergebnis(String[] bereinigteArgs, ServerScope scope, boolean hatteFlag, boolean silent,
                           String ungueltigesFlag) {
        public Ergebnis(String[] bereinigteArgs, ServerScope scope, boolean hatteFlag, boolean silent) {
            this(bereinigteArgs, scope, hatteFlag, silent, "");
        }

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
        int ende = args.length;
        ServerScope wirksam = standard;
        boolean hatteFlag = false;
        boolean silent = false;
        while (ende > 0) {
            String letztes = args[ende - 1];
            if (istSilentFlag(letztes)) {
                silent = true;
                ende--;
                continue;
            }
            ServerScope scope = scopeAusFlag(letztes, bekannteServer, unbekanntErlaubt);
            if (scope == null) {
                if ("--".equals(letztes) || istExpliziteScopeSyntax(letztes)
                        || siehtNachScopeVersuchAus(letztes)) {
                    return new Ergebnis(args, standard, false, silent, letztes);
                }
                break;
            }
            if (hatteFlag && !wirksam.equals(scope)) {
                return new Ergebnis(args, standard, false, silent, letztes);
            }
            wirksam = scope;
            hatteFlag = true;
            ende--;
        }
        for (int i = 0; i < ende; i++) {
            String argument = args[i];
            ServerScope scope = scopeAusFlag(argument, bekannteServer, unbekanntErlaubt);
            if (istExpliziteScopeSyntax(argument) || (scope != null
                    && istBekannterScopeFuerStrip(argument, scope, bekannteServer, unbekanntErlaubt))) {
                return new Ergebnis(args, standard, false, silent, argument);
            }
        }
        return new Ergebnis(ende == args.length ? args : Arrays.copyOf(args, ende), wirksam, hatteFlag, silent);
    }

    private static boolean istExpliziteScopeSyntax(String argument) {
        if (!istScopeFlag(argument)) {
            return false;
        }
        String normiert = argument.toLowerCase(Locale.ROOT);
        return normiert.equals("--server") || normiert.startsWith("--server=")
                || normiert.equals("--unbekannt");
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
                if (normiert == null) {
                    continue;
                }
                String kandidat = vergleich.startsWith("--server=") || normiert.equals("server")
                        ? "--server=" + normiert : "--" + normiert;
                if (kandidat.startsWith(vergleich) && !server.contains(kandidat)) {
                    server.add(kandidat);
                }
            }
            server.sort(String.CASE_INSENSITIVE_ORDER);
            ergebnis.addAll(server);
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
