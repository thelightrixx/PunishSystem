package de.lightrixx.punish.storage.mysql;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerpassteBroadcastsRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class YamlNachMysqlMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(YamlNachMysqlMigrator.class);

    private YamlNachMysqlMigrator() {
    }

    public static int migrieren(File datenOrdner, File pluginOrdner, YamlSpeicher yaml,
                                IoExecutor io, OfflineVerzeichnis verzeichnis, MysqlVerbindung mysql,
                                int ipRetentionTage) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        Objects.requireNonNull(pluginOrdner, "Der Plugin-Ordner darf nicht null sein.");
        Objects.requireNonNull(yaml, "Der YAML-Speicher darf nicht null sein.");
        Objects.requireNonNull(io, "Der I/O-Executor darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Offline-Verzeichnis darf nicht null sein.");
        Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        int gesamt = 0;
        List<Nachweis> nachweise = new ArrayList<>();

        BanRepository yamlBans = new BanRepository(datenOrdner, yaml, io);
        yamlBans.laden();
        BanRepository mysqlBans = new BanRepository(mysql, io);
        gesamt += melde(nachweise, "Banns", MysqlSchema.T_BANS, mysqlBans.migrationImport(yamlBans.alle()));

        IpBanRepository yamlIpBans = new IpBanRepository(datenOrdner, yaml, io);
        yamlIpBans.laden();
        IpBanRepository mysqlIpBans = new IpBanRepository(mysql, io);
        gesamt += melde(nachweise, "IP-Banns", MysqlSchema.T_IP_BANS, mysqlIpBans.migrationImport(yamlIpBans.alle()));

        MuteRepository yamlMutes = new MuteRepository(datenOrdner, yaml, io);
        yamlMutes.laden();
        MuteRepository mysqlMutes = new MuteRepository(mysql, io);
        gesamt += melde(nachweise, "Mutes", MysqlSchema.T_MUTES, mysqlMutes.migrationImport(yamlMutes.alle()));

        IpMuteRepository yamlIpMutes = new IpMuteRepository(datenOrdner, yaml, io);
        yamlIpMutes.laden();
        IpMuteRepository mysqlIpMutes = new IpMuteRepository(mysql, io);
        gesamt += melde(nachweise, "IP-Mutes", MysqlSchema.T_IP_MUTES, mysqlIpMutes.migrationImport(yamlIpMutes.alle()));

        TimeoutRepository yamlTimeouts = new TimeoutRepository(datenOrdner, yaml, io);
        yamlTimeouts.laden();
        TimeoutRepository mysqlTimeouts = new TimeoutRepository(mysql, io);
        gesamt += melde(nachweise, "Timeouts", MysqlSchema.T_TIMEOUTS, mysqlTimeouts.migrationImport(yamlTimeouts.alle()));

        FreezeRepository yamlFreezes = new FreezeRepository(datenOrdner, yaml, io);
        yamlFreezes.laden();
        FreezeRepository mysqlFreezes = new FreezeRepository(mysql, io);
        gesamt += melde(nachweise, "Freezes", MysqlSchema.T_FREEZES, mysqlFreezes.migrationImport(yamlFreezes.alle()));

        IpTimeoutRepository yamlIpTimeouts = new IpTimeoutRepository(datenOrdner, yaml, io);
        yamlIpTimeouts.laden();
        IpTimeoutRepository mysqlIpTimeouts = new IpTimeoutRepository(mysql, io);
        gesamt += melde(nachweise, "IP-Timeouts", MysqlSchema.T_IP_TIMEOUTS, mysqlIpTimeouts.migrationImport(yamlIpTimeouts.alle()));

        WarnRepository yamlWarns = new WarnRepository(datenOrdner, yaml, io);
        yamlWarns.laden();
        WarnRepository mysqlWarns = new WarnRepository(mysql, io);
        gesamt += melde(nachweise, "Warns", MysqlSchema.T_WARNS, mysqlWarns.migrationImport(yamlWarns.alle()));

        VerstossRepository yamlVerstoesse = new VerstossRepository(datenOrdner, yaml, io);
        yamlVerstoesse.laden();
        VerstossRepository mysqlVerstoesse = new VerstossRepository(mysql, io);
        gesamt += melde(nachweise, "Verstöße", MysqlSchema.T_VERSTOESSE, mysqlVerstoesse.migrationImport(yamlVerstoesse.alle()));

        NoteRepository yamlNotizen = new NoteRepository(datenOrdner, yaml, io);
        yamlNotizen.laden();
        NoteRepository mysqlNotizen = new NoteRepository(mysql, io);
        gesamt += melde(nachweise, "Notizen", MysqlSchema.T_NOTIZEN, mysqlNotizen.migrationImport(yamlNotizen.alle()));

        ReportRepository yamlReports = new ReportRepository(datenOrdner, yaml, io);
        yamlReports.laden();
        ReportRepository mysqlReports = new ReportRepository(mysql, io);
        gesamt += melde(nachweise, "Reports", MysqlSchema.T_REPORTS,
                mysqlReports.migrationImport(yamlReports.alle(), yamlReports.zaehlerStand()));

        HistoryRepository yamlHistory = new HistoryRepository(datenOrdner, yaml, io);
        yamlHistory.laden();
        HistoryRepository mysqlHistory = new HistoryRepository(mysql, io);
        gesamt += melde(nachweise, "History-Einträge", MysqlSchema.T_HISTORY, mysqlHistory.migrationImport(yamlHistory.alleSchluessel()));
        gesamt += melde(nachweise, "History-Archiv", MysqlSchema.T_HISTORY_ARCHIV,
                mysqlHistory.migrationImportArchiv(yamlHistory.archivEintraege()));

        PlayerCache yamlCache = new PlayerCache(pluginOrdner, yaml, io, verzeichnis);
        yamlCache.setIpRetentionTage(ipRetentionTage);
        yamlCache.laden();
        yamlCache.sweepAbgelaufeneIps();
        PlayerCache mysqlCache = new PlayerCache(mysql, io, verzeichnis);
        gesamt += melde(nachweise, "Spieler-Cache-Einträge", MysqlSchema.T_SPIELER, mysqlCache.migrationImport(yamlCache.alle()));

        VerpassteBroadcastsRepository yamlMissed = new VerpassteBroadcastsRepository(datenOrdner, yaml, io);
        yamlMissed.laden();
        VerpassteBroadcastsRepository mysqlMissed = new VerpassteBroadcastsRepository(mysql, io);
        gesamt += melde(nachweise, "Verpasste Broadcasts", MysqlSchema.T_MISSED_BROADCASTS,
                mysqlMissed.migrationImport(yamlMissed.momentaufnahme(), yamlMissed.cursorMomentaufnahme()));

        if (!io.warteAufIdle(120L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Schreib-Queue nach Migration nicht leer ("
                    + io.anzahlAusstehend() + " ausstehend) – Migrationsstatus bleibt 'running', "
                    + "Start wird verweigert (kein stiller Teil-Import).");
        }
        List<String> abweichungen = new ArrayList<>();
        for (Nachweis nachweis : nachweise) {
            long ist = zaehle(mysql, nachweis.tabelle());
            if (ist != nachweis.erwartet()) {
                abweichungen.add(nachweis.bereich() + ": erwartet " + nachweis.erwartet()
                        + ", in MySQL " + nachweis.tabelle() + " sind " + ist);
            }
        }
        if (!abweichungen.isEmpty()) {
            throw new IllegalStateException("YAML→MySQL-Migration unvollständig – Status bleibt 'running', "
                    + "Start wird verweigert; Abweichungen: " + abweichungen);
        }
        LOG.info("YAML→MySQL-Migration abgeschlossen und verifiziert: {} Einträge übernommen.", gesamt);
        return gesamt;
    }

    private static long zaehle(MysqlVerbindung mysql, String tabelle) {
        try (Connection verbindung = mysql.verbindung();
             Statement statement = verbindung.createStatement();
             ResultSet ergebnis = statement.executeQuery("SELECT COUNT(*) FROM " + tabelle)) {
            return ergebnis.next() ? ergebnis.getLong(1) : -1L;
        } catch (Exception e) {
            throw new IllegalStateException("Migration kann " + tabelle + " nicht verifizieren – "
                    + "Start wird verweigert (Fail-Closed).", e);
        }
    }

    private static int melde(List<Nachweis> nachweise, String bereich, String tabelle, int anzahl) {
        LOG.info("Migration: {} {} übernommen.", anzahl, bereich);
        nachweise.add(new Nachweis(bereich, tabelle, anzahl));
        return anzahl;
    }

    private record Nachweis(String bereich, String tabelle, long erwartet) {
    }
}
