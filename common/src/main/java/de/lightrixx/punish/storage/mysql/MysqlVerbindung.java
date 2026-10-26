package de.lightrixx.punish.storage.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class MysqlVerbindung implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MysqlVerbindung.class);

    private static final long CONNECTION_TIMEOUT_MS = 10_000L;
    private static final long VALIDATION_TIMEOUT_MS = 5_000L;

    private final HikariDataSource quelle;

    private MysqlVerbindung(HikariDataSource quelle) {
        this.quelle = quelle;
    }

    public static MysqlVerbindung oeffnen(MysqlKonfiguration konfiguration) {
        Objects.requireNonNull(konfiguration, "Die MySQL-Konfiguration darf nicht null sein.");
        if (konfiguration.sslWarnungNoetig()) {
            LOG.warn("MySQL läuft ohne TLS (useSSL=false): Zugangsdaten und Daten gehen unverschlüsselt "
                    + "über das Netz, allowPublicKeyRetrieval=true erlaubt Schlüsselabfragen ohne TLS. "
                    + "In Produktion useSSL=true verwenden.");
        }
        treiberRegistrieren();
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(konfiguration.jdbcUrl());
        poolConfig.setUsername(konfiguration.benutzer());
        poolConfig.setPassword(konfiguration.passwort());
        poolConfig.setPoolName("PunishSystem-MySQL");
        poolConfig.setMaximumPoolSize(konfiguration.poolMax());
        poolConfig.setMinimumIdle(Math.min(2, konfiguration.poolMax()));
        poolConfig.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        poolConfig.setValidationTimeout(VALIDATION_TIMEOUT_MS);
        poolConfig.addDataSourceProperty("cachePrepStmts", "true");
        poolConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        poolConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        try {
            MysqlVerbindung verbindung = new MysqlVerbindung(new HikariDataSource(poolConfig));
            if (!verbindung.ping()) {
                try {
                    verbindung.schliessen();
                } catch (Exception e) {
                    LOG.warn("MySQL-Verbindung nach Fehlschlag nicht sauber geschlossen.", e);
                }
                throw new IllegalStateException("MySQL ist nicht erreichbar (Ping fehlgeschlagen).");
            }
            return verbindung;
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Der MySQL-Pool konnte nicht geöffnet werden.", e);
        }
    }

    private static void treiberRegistrieren() {
        for (String treiber : new String[]{
                "de.lightrixx.punish.lib.mysql.cj.jdbc.Driver",
                "com.mysql.cj.jdbc.Driver"}) {
            try {
                Class.forName(treiber);
                return;
            } catch (ClassNotFoundException e) {
                LOG.trace("MySQL-Treiber nicht gefunden, nächster Versuch.", e);
            }
        }
        LOG.debug("MySQL-Treiber nicht per Class.forName gefunden, verlasse mich auf ServiceLoader.");
    }

    public Connection verbindung() throws SQLException {
        return quelle.getConnection();
    }

    public boolean ping() {
        try (Connection verbindung = quelle.getConnection();
             Statement statement = verbindung.createStatement()) {
            statement.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            LOG.warn("MySQL-Ping fehlgeschlagen.", e);
            return false;
        }
    }

    public boolean istOffen() {
        return quelle != null && !quelle.isClosed();
    }

    public void schliessen() {
        try {
            quelle.close();
        } catch (Exception e) {
            LOG.warn("MySQL-Pool konnte nicht sauber geschlossen werden.", e);
        }
    }

    @Override
    public void close() {
        schliessen();
    }
}
