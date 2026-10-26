package de.lightrixx.punish.storage.mysql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

public final class MysqlSchema {

    private static final Logger LOG = LoggerFactory.getLogger(MysqlSchema.class);

    public static final String T_BANS = "punish_bans";
    public static final String T_MUTES = "punish_mutes";
    public static final String T_TIMEOUTS = "punish_timeouts";
    public static final String T_FREEZES = "punish_freezes";
    public static final String T_IP_BANS = "punish_ipbans";
    public static final String T_IP_MUTES = "punish_ipmutes";
    public static final String T_IP_TIMEOUTS = "punish_iptimeouts";
    public static final String T_WARNS = "punish_warns";
    public static final String T_VERSTOESSE = "punish_verstoesse";
    public static final String T_NOTIZEN = "punish_notizen";
    public static final String T_REPORTS = "punish_reports";
    public static final String T_HISTORY = "punish_history";
    public static final String T_HISTORY_ARCHIV = "punish_history_archiv";
    public static final String T_SPIELER = "punish_spieler";
    public static final String T_IP_HISTORIE = "punish_ip_historie";
    public static final String T_MISSED_BROADCASTS = "punish_missed_broadcasts";
    public static final String T_MISSED_CURSOR = "punish_missed_cursor";
    public static final String T_META = "punish_meta";

    public static final List<String> ALLE_TABELLEN = List.of(
            T_BANS, T_MUTES, T_TIMEOUTS, T_FREEZES, T_IP_BANS, T_IP_MUTES, T_IP_TIMEOUTS,
            T_WARNS, T_VERSTOESSE, T_NOTIZEN, T_REPORTS, T_HISTORY, T_HISTORY_ARCHIV, T_SPIELER,
            T_IP_HISTORIE, T_MISSED_BROADCASTS, T_MISSED_CURSOR);

    static final String META_SCHEMA_VERSION = "schema_version";
    static final String META_MIGRATION = "yaml_migration";
    static final String SCHEMA_VERSION = "2";
    private static final String SCHEMA_VERSION_IP_HISTORIE = "1";
    public static final String MIGRATION_LAEUFT = "running";
    public static final String MIGRATION_FERTIG = "fertig";

    private static final String ENGINE = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private MysqlSchema() {
    }

    public static void initialisieren(MysqlVerbindung mysql) {
        Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
             Statement statement = verbindung.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_BANS + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "spieler_name VARCHAR(64) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "ablauf_am BIGINT NULL,"
                    + "auto_bann TINYINT(1) NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (uuid, scope))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_MUTES + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "spieler_name VARCHAR(64) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "ablauf_am BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid, scope))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_TIMEOUTS + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "spieler_name VARCHAR(64) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "ablauf_am BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid, scope))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_FREEZES + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "spieler_name VARCHAR(64) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "ablauf_am BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid, scope))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_IP_BANS + " ("
                    + "ip VARCHAR(64) NOT NULL,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "ablauf_am BIGINT NULL,"
                    + "PRIMARY KEY (ip, scope))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_IP_MUTES + " ("
                    + "ip VARCHAR(64) NOT NULL,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "ablauf_am BIGINT NOT NULL,"
                    + "PRIMARY KEY (ip, scope))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_IP_TIMEOUTS + " ("
                    + "ip VARCHAR(64) NOT NULL,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "ablauf_am BIGINT NOT NULL,"
                    + "PRIMARY KEY (ip, scope))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_WARNS + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "nummer INT NOT NULL,"
                    + "spieler_name VARCHAR(64) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid, nummer))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_VERSTOESSE + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "nummer INT NOT NULL,"
                    + "spieler_name VARCHAR(64) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "stufe VARCHAR(16) NOT NULL,"
                    + "PRIMARY KEY (uuid, nummer))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_NOTIZEN + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "nummer INT NOT NULL,"
                    + "spieler_name VARCHAR(64) NOT NULL,"
                    + "text VARCHAR(1024) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid, nummer))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_REPORTS + " ("
                    + "id BIGINT NOT NULL,"
                    + "ziel_uuid CHAR(36) NOT NULL,"
                    + "ziel_name VARCHAR(64) NOT NULL,"
                    + "reporter_uuid CHAR(36) NULL,"
                    + "reporter_name VARCHAR(64) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "server VARCHAR(64) NOT NULL,"
                    + "erstellt_am BIGINT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "bearbeiter VARCHAR(64) NOT NULL,"
                    + "bearbeitet_am BIGINT NOT NULL,"
                    + "close_grund VARCHAR(1024) NOT NULL,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_reports_ziel (ziel_uuid))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_HISTORY + " ("
                    + "id BIGINT NOT NULL,"
                    + "schluessel CHAR(36) NOT NULL,"
                    + "typ VARCHAR(32) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "ziel VARCHAR(128) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "zeitstempel BIGINT NOT NULL,"
                    + "ablauf BIGINT NULL,"
                    + "aktiv TINYINT(1) NOT NULL DEFAULT 1,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "herkunft VARCHAR(32) NOT NULL,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_history_schluessel (schluessel))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_HISTORY_ARCHIV + " ("
                    + "id BIGINT NOT NULL,"
                    + "schluessel CHAR(36) NOT NULL,"
                    + "typ VARCHAR(32) NOT NULL,"
                    + "ausfuehrer VARCHAR(64) NOT NULL,"
                    + "ziel VARCHAR(128) NOT NULL,"
                    + "grund VARCHAR(1024) NOT NULL,"
                    + "zeitstempel BIGINT NOT NULL,"
                    + "ablauf BIGINT NULL,"
                    + "aktiv TINYINT(1) NOT NULL DEFAULT 1,"
                    + "scope VARCHAR(32) NOT NULL,"
                    + "herkunft VARCHAR(32) NOT NULL,"
                    + "archiviert_am BIGINT NOT NULL,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_history_archiv_schluessel (schluessel))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_SPIELER + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "name VARCHAR(64) NOT NULL,"
                    + "letzte_ip VARCHAR(64) NOT NULL,"
                    + "zuletzt_gesehen BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid),"
                    + "KEY idx_spieler_name (name),"
                    + "KEY idx_spieler_ip (letzte_ip))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_IP_HISTORIE + " ("
                    + "ip VARCHAR(64) NOT NULL,"
                    + "uuid CHAR(36) NOT NULL,"
                    + "name VARCHAR(64) NOT NULL,"
                    + "zuletzt_gesehen BIGINT NOT NULL,"
                    + "PRIMARY KEY (ip, uuid),"
                    + "KEY idx_ip_historie_uuid (uuid),"
                    + "KEY idx_ip_historie_zeit (zuletzt_gesehen))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_MISSED_BROADCASTS + " ("
                    + "id BIGINT NOT NULL,"
                    + "typ VARCHAR(64) NOT NULL,"
                    + "text MEDIUMTEXT NOT NULL,"
                    + "zeitstempel BIGINT NOT NULL,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_missed_zeit (zeitstempel))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_MISSED_CURSOR + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "zuletzt_nachgeholt BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid))" + ENGINE);
            statement.execute("CREATE TABLE IF NOT EXISTS " + T_META + " ("
                    + "schluessel VARCHAR(64) NOT NULL,"
                    + "wert VARCHAR(64) NOT NULL,"
                    + "PRIMARY KEY (schluessel))" + ENGINE);
            String vorhandeneVersion = metaLesen(verbindung, META_SCHEMA_VERSION);
            if (vorhandeneVersion == null) {
                metaSchreiben(verbindung, META_SCHEMA_VERSION, SCHEMA_VERSION);
                LOG.info("MySQL-Schema angelegt ({} Tabellen, Schema-Version {}).",
                        ALLE_TABELLEN.size(), SCHEMA_VERSION);
            } else if (vorhandeneVersion.equals(SCHEMA_VERSION)) {
                LOG.debug("MySQL-Schema-Version {} ist aktuell.", vorhandeneVersion);
            } else if (vorhandeneVersion.equals(SCHEMA_VERSION_IP_HISTORIE)) {
                ipHistorieNachfuellen(verbindung);
                metaSchreiben(verbindung, META_SCHEMA_VERSION, SCHEMA_VERSION);
                LOG.info("MySQL-Schema von Version {} auf {} migriert (IP-Historie).",
                        vorhandeneVersion, SCHEMA_VERSION);
            } else {
                throw new IllegalStateException("MySQL-Schema-Version '" + vorhandeneVersion
                        + "' passt nicht zu dieser Plugin-Version (erwartet '" + SCHEMA_VERSION
                        + "'). Bitte Plugin-Stände und Datenbank abgleichen – Start verweigert, "
                        + "damit kein leerer Strafbestand entsteht.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MySQL-Schema konnte nicht angelegt werden.", e);
        }
        LOG.info("MySQL-Schema geprüft ({} Tabellen).", ALLE_TABELLEN.size());
    }

    private static void ipHistorieNachfuellen(Connection verbindung) throws Exception {
        try (Statement statement = verbindung.createStatement()) {
            int zeilen = statement.executeUpdate("INSERT INTO " + T_IP_HISTORIE
                    + " (ip, uuid, name, zuletzt_gesehen)"
                    + " SELECT letzte_ip, uuid, name, zuletzt_gesehen FROM " + T_SPIELER
                    + " WHERE letzte_ip IS NOT NULL AND letzte_ip <> '' AND letzte_ip <> 'unbekannt'"
                    + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                    + " zuletzt_gesehen = GREATEST(zuletzt_gesehen, VALUES(zuletzt_gesehen))");
            LOG.info("IP-Historie aus bestehenden Spieler-Cache-Einträgen nachgefüllt ({} Zeilen aktualisiert).",
                    zeilen);
        }
    }

    public static boolean istLeer(MysqlVerbindung mysql) {
        Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
             Statement statement = verbindung.createStatement()) {
            for (String tabelle : ALLE_TABELLEN) {
                try (ResultSet ergebnis = statement.executeQuery("SELECT COUNT(*) FROM " + tabelle)) {
                    if (ergebnis.next() && ergebnis.getLong(1) > 0L) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("MySQL-Leerstand konnte nicht geprüft werden "
                    + "(Migration deshalb nicht fortsetzbar).", e);
        }
    }

    public static boolean migrationOffen(MysqlVerbindung mysql) {
        Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung()) {
            return MIGRATION_LAEUFT.equals(metaLesen(verbindung, META_MIGRATION));
        } catch (Exception e) {
            throw new IllegalStateException("Migrationsstatus konnte nicht gelesen werden.", e);
        }
    }

    public static void markiereMigration(MysqlVerbindung mysql, String status) {
        Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        Objects.requireNonNull(status, "Der Status darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung()) {
            metaSchreiben(verbindung, META_MIGRATION, status);
        } catch (Exception e) {
            throw new IllegalStateException("Migrationsstatus konnte nicht gespeichert werden.", e);
        }
    }

    public static String metaLesen(Connection verbindung, String schluessel) throws Exception {
        try (java.sql.PreparedStatement statement = verbindung.prepareStatement(
                "SELECT wert FROM " + T_META + " WHERE schluessel = ?")) {
            statement.setString(1, schluessel);
            try (ResultSet ergebnis = statement.executeQuery()) {
                return ergebnis.next() ? ergebnis.getString(1) : null;
            }
        }
    }

    public static void metaSchreiben(Connection verbindung, String schluessel, String wert) throws Exception {
        try (java.sql.PreparedStatement statement = verbindung.prepareStatement(
                "INSERT INTO " + T_META + " (schluessel, wert) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE wert = VALUES(wert)")) {
            statement.setString(1, schluessel);
            statement.setString(2, wert);
            statement.executeUpdate();
        }
    }
}
