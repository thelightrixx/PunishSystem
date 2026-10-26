package de.lightrixx.punish.storage.mysql;

import java.util.Objects;

public record MysqlKonfiguration(
        String host,
        int port,
        String datenbank,
        String benutzer,
        String passwort,
        boolean useSsl,
        int poolMax
) {

    public MysqlKonfiguration {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        } else {
            host = host.trim();
        }
        if (host.indexOf('/') >= 0 || host.indexOf('?') >= 0 || host.indexOf('&') >= 0
                || host.indexOf('#') >= 0 || host.indexOf('\\') >= 0 || host.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Der MySQL-Host enthält URL-Zeichen (?, &, #, /, \\, Leerzeichen) "
                    + "und würde die JDBC-URL verändern.");
        }
        if (port <= 0 || port > 65535) {
            port = 3306;
        }
        Objects.requireNonNull(datenbank, "Der MySQL-Datenbankname darf nicht null sein.");
        if (datenbank.isBlank()) {
            throw new IllegalArgumentException("Der MySQL-Datenbankname darf nicht leer sein.");
        }
        datenbank = datenbank.trim();
        if (datenbank.indexOf('?') >= 0 || datenbank.indexOf('&') >= 0 || datenbank.indexOf('#') >= 0
                || datenbank.indexOf('/') >= 0 || datenbank.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Der MySQL-Datenbankname enthält URL-Zeichen (?, &, #, /, \\) "
                    + "und würde die JDBC-URL verändern.");
        }
        Objects.requireNonNull(benutzer, "Der MySQL-Benutzer darf nicht null sein.");
        if (benutzer.isBlank()) {
            throw new IllegalArgumentException("Der MySQL-Benutzer darf nicht leer sein.");
        }
        benutzer = benutzer.trim();
        if (passwort == null) {
            passwort = "";
        }
        if (poolMax < 1 || poolMax > 20) {
            poolMax = 10;
        }
    }

    public boolean sslWarnungNoetig() {
        return !useSsl;
    }

    @Override
    public String toString() {
        return "MysqlKonfiguration[host=" + host + ", port=" + port + ", datenbank=" + datenbank
                + ", benutzer=" + benutzer + ", passwort=***, useSsl=" + useSsl + ", poolMax=" + poolMax + "]";
    }

    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + datenbank
                + "?useSSL=" + useSsl
                + "&allowPublicKeyRetrieval=" + !useSsl
                + "&serverTimezone=UTC"
                + "&useUnicode=true&characterEncoding=UTF-8";
    }
}
