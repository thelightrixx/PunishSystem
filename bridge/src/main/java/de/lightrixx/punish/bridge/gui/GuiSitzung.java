package de.lightrixx.punish.bridge.gui;

import java.util.Arrays;
import java.util.Objects;

public final class GuiSitzung {

    public enum Aktion {
        BAN("ban", true, true, false, "punish.ban"),
        PERMABANN("permabann", false, true, false, "punish.permabann"),
        MUTE("mute", true, true, false, "punish.mute"),
        KICK("kick", false, true, false, "punish.kick"),
        TIMEOUT("timeout", true, true, false, "punish.timeout"),
        FREEZE("freeze", true, true, false, "punish.freeze"),
        WARN("warn", false, true, false, "punish.warn"),
        VERSTOSS("verstoss", false, true, false, "punish.verstoss"),
        NOTE("note", false, true, false, "punish.note"),
        UNMUTE("unmute", false, false, true, "punish.unmute"),
        PARDON("pardon", false, false, true, "punish.pardon"),
        UNTIMEOUT("untimeout", false, false, true, "punish.untimeout"),
        UNFREEZE("unfreeze", false, false, true, "punish.unfreeze");

        private final String befehl;
        private final boolean brauchtDauer;
        private final boolean brauchtGrund;
        private final boolean entstrafe;
        private final String permission;

        Aktion(String befehl, boolean brauchtDauer, boolean brauchtGrund,
               boolean entstrafe, String permission) {
            this.befehl = befehl;
            this.brauchtDauer = brauchtDauer;
            this.brauchtGrund = brauchtGrund;
            this.entstrafe = entstrafe;
            this.permission = permission;
        }

        public String befehl() {
            return befehl;
        }

        public boolean brauchtDauer() {
            return brauchtDauer;
        }

        public boolean brauchtGrund() {
            return brauchtGrund;
        }

        public boolean entstrafe() {
            return entstrafe;
        }

        public String permission() {
            return permission;
        }
    }

    public enum Schritt {
        SPIELER, AKTION, GRUND, DAUER, SCOPE, BESTAETIGEN
    }

    private String ziel;
    private Aktion aktion;
    private String grund = "";
    private String dauer = "";
    private String stufe = "medium";
    private String scope = "--global";
    private boolean silent = false;
    private Schritt schritt = Schritt.SPIELER;
    private int seite = 1;

    public String ziel() {
        return ziel == null ? "" : ziel;
    }

    public void ziel(String ziel) {
        this.ziel = ziel == null ? "" : ziel.trim();
    }

    public Aktion aktion() {
        return aktion;
    }

    public void aktion(Aktion aktion) {
        this.aktion = aktion;
    }

    public String grund() {
        return grund == null ? "" : grund;
    }

    public void grund(String grund) {
        this.grund = grund == null ? "" : grund.trim();
    }

    public String dauer() {
        return dauer == null ? "" : dauer;
    }

    public void dauer(String dauer) {
        this.dauer = dauer == null ? "" : dauer.trim();
    }

    public String stufe() {
        return stufe;
    }

    public void stufe(String stufe) {
        this.stufe = stufe == null || stufe.isBlank() ? "medium" : stufe.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public String scope() {
        return scope == null || scope.isBlank() ? "--global" : scope;
    }

    public void scope(String scope) {
        this.scope = scope == null || scope.isBlank() ? "--global" : scope.trim();
    }

    public boolean silent() {
        return silent;
    }

    public void silent(boolean silent) {
        this.silent = silent;
    }

    public void wechselSilent() {
        this.silent = !this.silent;
    }

    public Schritt schritt() {
        return schritt;
    }

    public void schritt(Schritt schritt) {
        this.schritt = Objects.requireNonNull(schritt, "Der Schritt darf nicht null sein.");
    }

    public int seite() {
        return seite;
    }

    public void seite(int seite) {
        this.seite = Math.max(1, seite);
    }

    public void zuruecksetzen() {
        this.ziel = "";
        this.aktion = null;
        this.grund = "";
        this.dauer = "";
        this.stufe = "medium";
        this.scope = "--global";
        this.silent = false;
        this.schritt = Schritt.SPIELER;
        this.seite = 1;
    }

    public boolean bereitZumSenden() {
        if (ziel().isBlank() || aktion == null) {
            return false;
        }
        if (aktion.brauchtGrund() && grund().isBlank()) {
            return false;
        }
        if (aktion.brauchtDauer() && dauer().isBlank()) {
            return false;
        }
        return true;
    }

    /**
     * Entfernt trailing Scope-/Silent-Flag-Tokens aus dem Grund-Text. Das GUI-Scope-Flag wird
     * in {@link #baueZeile()} angehängt und von hinten ausgewertet, sodass das am weitesten
     * links stehende Flag gewinnt – Flag-Tokens im Grund würden die GUI-Auswahl still
     * überschreiben. Bewusst lokal umgesetzt: Der Bridge-Shade schließt `command/**` aus.
     */
    private static String bereinigeGrund(String grund) {
        if (grund == null || grund.isEmpty()) {
            return "";
        }
        String[] teile = grund.trim().split("\\s+");
        int ende = teile.length;
        while (ende > 0 && istFlagToken(teile[ende - 1])) {
            ende--;
        }
        return String.join(" ", Arrays.copyOfRange(teile, 0, ende));
    }

    private static boolean istFlagToken(String token) {
        return "-s".equals(token) || (token.startsWith("--") && token.length() >= 3);
    }

    public String baueZeile() {
        String z = ziel().trim();
        String g = bereinigeGrund(grund().trim());
        String d = dauer().trim();
        String s = scope();
        if (z.isEmpty() || aktion == null) {
            return "";
        }
        String basis = switch (aktion) {
            case BAN -> "ban " + z + " " + (d.isEmpty() ? "7d" : d) + " " + (g.isEmpty() ? "Regelverstoß" : g) + " " + s;
            case PERMABANN -> "permabann " + z + " " + (g.isEmpty() ? "Regelverstoß" : g) + " " + s;
            case MUTE -> "mute " + z + " " + (d.isEmpty() ? "10m" : d) + " " + (g.isEmpty() ? "Regelverstoß" : g) + " " + s;
            case KICK -> "kick " + z + " " + (g.isEmpty() ? "Regelverstoß" : g);
            case TIMEOUT -> "timeout " + z + " " + (d.isEmpty() ? "30m" : d) + " " + (g.isEmpty() ? "Cool-down" : g) + " " + s;
            case FREEZE -> "freeze " + z + " " + (d.isEmpty() ? "perm" : d) + " " + (g.isEmpty() ? "Überprüfung" : g) + " " + s;
            case WARN -> "warn " + z + " " + (g.isEmpty() ? "Regelverstoß" : g);
            case VERSTOSS -> "verstoss add " + z + " " + (g.isEmpty() ? "Regelverstoß" : g) + " level=" + stufe();
            case NOTE -> "punish-note add " + z + " " + (g.isEmpty() ? "Notiz" : g);
            case UNMUTE -> "unmute " + z + " " + s;
            case PARDON -> "pardon " + z + " " + s;
            case UNTIMEOUT -> "untimeout " + z + " " + s;
            case UNFREEZE -> "unfreeze " + z + " " + s;
        };
        return silent ? basis + " -s" : basis;
    }

    public String vorschau() {
        String zeile = baueZeile();
        return zeile.isEmpty() ? "/" : "/" + zeile;
    }
}
