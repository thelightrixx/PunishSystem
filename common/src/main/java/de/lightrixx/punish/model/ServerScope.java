package de.lightrixx.punish.model;

import java.util.Locale;
import java.util.Objects;

public record ServerScope(Typ typ, String server) {

    public enum Typ {
        GLOBAL,
        SERVER,
        UNBEKANNT
    }

    public static final String UNBEKANNT_WERT = "unbekannt";

    public static final String GLOBAL_WERT = "global";

    public ServerScope {
        Objects.requireNonNull(typ, "Der Scope-Typ darf nicht null sein.");
        if (typ == Typ.SERVER) {
            String normiert = normalisiereServerName(server);
            if (normiert == null) {
                throw new IllegalArgumentException("SERVER-Scope braucht einen Servernamen.");
            }
            server = normiert;
        } else {
            server = null;
        }
    }

    public static ServerScope global() {
        return new ServerScope(Typ.GLOBAL, null);
    }

    public static ServerScope server(String serverName) {
        return new ServerScope(Typ.SERVER, serverName);
    }

    public static ServerScope unbekannt() {
        return new ServerScope(Typ.UNBEKANNT, null);
    }

    public static ServerScope ausSpeicherwert(String roh) {
        if (roh == null || roh.isBlank()) {
            return unbekannt();
        }
        String getrimmt = roh.trim();
        if (getrimmt.length() > 7 && getrimmt.regionMatches(true, 0, "server:", 0, 7)) {
            getrimmt = getrimmt.substring(7).trim();
            if (getrimmt.isEmpty()) {
                return unbekannt();
            }
        }
        if (getrimmt.equalsIgnoreCase(UNBEKANNT_WERT)) {
            return unbekannt();
        }
        if (getrimmt.equalsIgnoreCase(GLOBAL_WERT)) {
            return global();
        }
        String normiert = normalisiereServerName(getrimmt);
        if (normiert == null) {
            return unbekannt();
        }
        return new ServerScope(Typ.SERVER, normiert);
    }

    public String speicherwert() {
        return switch (typ) {
            case GLOBAL -> GLOBAL_WERT;
            case UNBEKANNT -> UNBEKANNT_WERT;
            case SERVER -> server;
        };
    }

    public String anzeige() {
        return speicherwert();
    }

    public boolean giltFuer(String aktuellerServer) {
        if (typ == Typ.GLOBAL || typ == Typ.UNBEKANNT) {
            return true;
        }
        if (aktuellerServer == null || aktuellerServer.isBlank()
                || aktuellerServer.trim().equalsIgnoreCase(UNBEKANNT_WERT)) {
            return true;
        }
        return server.equalsIgnoreCase(aktuellerServer.trim());
    }

    public boolean decktAb(ServerScope ziel) {
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        if (typ == Typ.GLOBAL || typ == Typ.UNBEKANNT) {
            return true;
        }
        return ziel.typ == Typ.SERVER && server.equalsIgnoreCase(ziel.server);
    }

    public static String normalisiereServerName(String roh) {
        if (roh == null) {
            return null;
        }
        String normiert = roh.trim().toLowerCase(Locale.ROOT);
        if (normiert.isEmpty() || normiert.length() > 32) {
            return null;
        }
        if (!normiert.matches("[a-z0-9_\\-]+")) {
            return null;
        }
        if (normiert.equals(GLOBAL_WERT) || normiert.equals(UNBEKANNT_WERT)) {
            return null;
        }
        return normiert;
    }

    public static boolean istGueltigerServerName(String roh) {
        return normalisiereServerName(roh) != null;
    }
}
