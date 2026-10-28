package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ReportRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ReportRepository.class);
    private static final int MAX_REPORTS_GESAMT = 10000;
    private static final String DATEI_NAME = "reports.yml";
    private static final String SCHLUESSEL_ZAEHLER = "zaehler";
    private static final String META_ZAEHLER = "report_zaehler";
    private static final String SCHLUESSEL_LISTE = "reports";
    private static final String SCHLUESSEL_ID = "id";
    private static final String SCHLUESSEL_ZIEL_UUID = "ziel-uuid";
    private static final String SCHLUESSEL_ZIEL_NAME = "ziel-name";
    private static final String SCHLUESSEL_REPORTER_UUID = "reporter-uuid";
    private static final String SCHLUESSEL_REPORTER_NAME = "reporter-name";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_SERVER = "server";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";
    private static final String SCHLUESSEL_STATUS = "status";
    private static final String SCHLUESSEL_BEARBEITER = "bearbeiter";
    private static final String SCHLUESSEL_BEARBEITET_AM = "bearbeitet-am";
    private static final String SCHLUESSEL_CLOSE_GRUND = "close-grund";

    private volatile ConcurrentHashMap<Long, ReportRecord> reports = new ConcurrentHashMap<>();
    private final AtomicLong zaehler = new AtomicLong(0);
    private volatile List<Object> rohQuarantaene = List.of();

    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public ReportRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Der YAML-Speicher darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(datenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public ReportRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
        this.mysql = Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = null;
        this.yamlSpeicher = null;
    }

    public boolean istMysql() {
        return mysql != null;
    }

    public void laden() {
        try {
            boolean idle = ioExecutor.warteAufIdle(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!idle) {
                LOG.warn("Report-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Report-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = reports.size();
            long alterZaehler = zaehler.get();
            YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
            Map<String, Object> daten = ladeStatus.daten();
            if (ladeStatus.fehler()) {
                LOG.error("reports.yml konnte nicht gelesen werden ({} im Memory) – behalte alten Stand.",
                        alteGroesse);
                return;
            }
            if (daten.containsKey(SCHLUESSEL_LISTE) && !(daten.get(SCHLUESSEL_LISTE) instanceof List)) {
                LOG.error("reports.yml hat einen unbrauchbaren '{}'-Abschnitt – behalte alten Stand (Fail-Closed).",
                        SCHLUESSEL_LISTE);
                return;
            }
            if (daten.isEmpty()) {
                reports = new ConcurrentHashMap<>();
                rohQuarantaene = List.of();
                return;
            }
            long geladenerZaehler = Karten.zahl(daten, SCHLUESSEL_ZAEHLER, 0L);
            List<Object> rohEintraege = Karten.liste(daten, SCHLUESSEL_LISTE);
            ConcurrentHashMap<Long, ReportRecord> tmp = new ConcurrentHashMap<>();
            List<Object> quar = new ArrayList<>();
            long maxId = geladenerZaehler;
            for (Object rohObj : rohEintraege) {
                Map<String, Object> eintrag = Karten.abschnitt(rohObj);
                if (eintrag == null) {
                    LOG.error("Ein Report-Eintrag ist korrupt (kein Map) und wird QUARANTIERT (nicht gelöscht).");
                    if (rohObj != null) {
                        quar.add(rohObj);
                    }
                    continue;
                }
                try {
                    ReportRecord record = parseEintrag(eintrag);
                    if (tmp.putIfAbsent(record.id(), record) != null) {
                        LOG.error("Doppelte Report-ID {} – Eintrag wird QUARANTIERT.", record.id());
                        quar.add(rohObj);
                        continue;
                    }
                    if (record.id() > maxId) {
                        maxId = record.id();
                    }
                } catch (Exception e) {
                    LOG.error("Ein Report-Eintrag ist korrupt und wird QUARANTIERT (nicht gelöscht).", e);
                    quar.add(rohObj);
                }
            }
            reports = tmp;
            rohQuarantaene = List.copyOf(quar);
            zaehler.set(Math.max(geladenerZaehler, maxId));
            if (!quar.isEmpty()) {
                LOG.warn("{} korrupte Report-Einträge in Quarantäne (bleiben auf Disk erhalten).", quar.size());
            }
            LOG.info("Es wurden {} Reports geladen (Zähler={}).", tmp.size(), zaehler.get());
            if (alterZaehler > zaehler.get()) {
                LOG.warn("Report-Zähler sank von {} auf {} – korrigiere auf {}.",
                        alterZaehler, zaehler.get(), alterZaehler);
                zaehler.set(alterZaehler);
            }
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public ReportRecord hinzufuegen(UUID zielUuid, String zielName, UUID reporterUuid,
                                    String reporterName, String grund, String server) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        Objects.requireNonNull(reporterName, "Der Reportername darf nicht null sein.");
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            if (reports.size() >= MAX_REPORTS_GESAMT) {
                List<Long> loeschbar = reports.values().stream()
                        .filter(r -> r != null && !r.istOffen())
                        .sorted(Comparator.comparingLong(ReportRecord::id))
                        .map(ReportRecord::id)
                        .limit((long) reports.size() - MAX_REPORTS_GESAMT + 100L)
                        .toList();
                for (Long id : loeschbar) {
                    reports.remove(id);
                    if (mysql != null) {
                        final long verdrangt = id;
                        ioExecutor.ausfuehren(() -> loescheZeileNachMysql(verdrangt));
                    }
                }
                if (reports.size() >= MAX_REPORTS_GESAMT) {
                    throw new IllegalStateException("Report-Limit erreicht (" + reports.size()
                            + " Einträge, alle offen): älteste offene Reports werden nicht verdrängt.");
                } else {
                    LOG.warn("Report-Limit erreicht ({} Einträge). Älteste geschlossene Reports verdrängt.", reports.size());
                }
            }
            long id = zaehler.incrementAndGet();
            ReportRecord neu = new ReportRecord(id, zielUuid, zielName.trim(),
                    reporterUuid, reporterName.trim(), grund.trim(),
                    server == null || server.isBlank() ? "unbekannt" : server.trim(),
                    System.currentTimeMillis(), ReportStatus.OFFEN, "", 0L, "");
            reports.put(id, neu);
            if (mysql != null) {
                final ReportRecord snapshot = neu;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
            } else {
                persistiere();
            }
            return neu;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<ReportRecord> findenNachId(long id) {
        ReportRecord record = reports.get(id);
        return Optional.ofNullable(record);
    }

    public List<ReportRecord> alle() {
        return reports.values().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(ReportRecord::id).reversed())
                .toList();
    }

    public List<ReportRecord> offene() {
        return reports.values().stream()
                .filter(Objects::nonNull)
                .filter(ReportRecord::istOffen)
                .sorted(Comparator.comparingLong(ReportRecord::id).reversed())
                .toList();
    }

    public List<ReportRecord> alleFuer(UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        return reports.values().stream()
                .filter(r -> r != null && zielUuid.equals(r.zielUuid()))
                .sorted(Comparator.comparingLong(ReportRecord::id).reversed())
                .toList();
    }

    public int zaehleOffeneVonReporter(UUID reporterUuid) {
        if (reporterUuid == null) {
            return 0;
        }
        int anzahl = 0;
        for (ReportRecord record : reports.values()) {
            if (record != null && record.istOffen() && reporterUuid.equals(record.reporterUuid())) {
                anzahl++;
            }
        }
        return anzahl;
    }

    public Optional<ReportRecord> findeDuplikat(UUID reporterUuid, UUID zielUuid, long seitMillis) {
        if (reporterUuid == null || zielUuid == null) {
            return Optional.empty();
        }
        return reports.values().stream()
                .filter(r -> r != null && r.istOffen()
                        && reporterUuid.equals(r.reporterUuid())
                        && zielUuid.equals(r.zielUuid())
                        && r.erstelltAm() >= seitMillis)
                .max(Comparator.comparingLong(ReportRecord::erstelltAm));
    }

    public Optional<ReportRecord> statusSetzen(long id, ReportStatus status,
                                               String bearbeiter, String closeGrund) {
        return statusSetzenWenn(id, status, bearbeiter, closeGrund, null);
    }

    public Optional<ReportRecord> statusSetzenWenn(long id, ReportStatus status,
                                                  String bearbeiter, String closeGrund,
                                                  java.util.function.Predicate<ReportRecord> bedingung) {
        Objects.requireNonNull(status, "Der Status darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            ReportRecord alt = reports.get(id);
            if (alt == null) {
                return Optional.empty();
            }
            if (bedingung != null && !bedingung.test(alt)) {
                return Optional.empty();
            }
            ReportRecord neu = alt.mitStatus(status, bearbeiter, closeGrund);
            reports.put(id, neu);
            if (mysql != null) {
                final ReportRecord snapshot = neu;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
            } else {
                persistiere();
            }
            return Optional.of(neu);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<ReportRecord> entfernen(long id) {
        StoreLock.LOCK.writeLock().lock();
        try {
            ReportRecord entfernt = reports.remove(id);
            if (entfernt != null) {
                if (mysql != null) {
                    ioExecutor.ausfuehren(() -> loescheZeileNachMysql(id));
                } else {
                    persistiere();
                }
            }
            return Optional.ofNullable(entfernt);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public int anzahlOffen() {
        int anzahl = 0;
        for (ReportRecord record : reports.values()) {
            if (record != null && record.istOffen()) {
                anzahl++;
            }
        }
        return anzahl;
    }

    private void persistiere() {
        final List<ReportRecord> snapshot = alleAufsteigend();
        if (mysql != null) {
            ioExecutor.ausfuehren(() -> speichereNachMysql(snapshot));
            return;
        }
        final List<Object> rohSnapshot = List.copyOf(rohQuarantaene);
        final long zaehlerSnapshot = zaehler.get();
        final Map<String, Object> daten = konfigurationErstellen(snapshot, rohSnapshot, zaehlerSnapshot);
        ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> yamlSpeicher.speichern(daten, datei));
    }

    public int migrationImport(Collection<ReportRecord> alle) {
        return migrationImport(alle, 0L);
    }

    public long zaehlerStand() {
        return zaehler.get();
    }

    public int migrationImport(Collection<ReportRecord> alle, long mindestZaehler) {
        Objects.requireNonNull(alle, "Die Report-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<Long, ReportRecord> neu = new ConcurrentHashMap<>();
            long maxId = 0L;
            for (ReportRecord report : alle) {
                if (report == null) {
                    continue;
                }
                neu.put(report.id(), report);
                if (report.id() > maxId) {
                    maxId = report.id();
                }
            }
            reports = neu;
            zaehler.set(Math.max(maxId, Math.max(zaehler.get(), mindestZaehler)));
            List<ReportRecord> snapshot = alleAufsteigend();
            ioExecutor.ausfuehren(() -> speichereNachMysql(snapshot));
            return neu.size();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = reports.size();
            long alterZaehler = zaehler.get();
            ConcurrentHashMap<Long, ReportRecord> tmp = new ConcurrentHashMap<>();
            long maxId = 0L;
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT id, ziel_uuid, ziel_name, reporter_uuid, reporter_name, grund, server,"
                                 + " erstellt_am, status, bearbeiter, bearbeitet_am, close_grund"
                                 + " FROM " + MysqlSchema.T_REPORTS)) {
                while (ergebnis.next()) {
                    try {
                        ReportRecord record = zeileLesen(ergebnis);
                        tmp.put(record.id(), record);
                        if (record.id() > maxId) {
                            maxId = record.id();
                        }
                    } catch (Exception e) {
                        LOG.error("Eine Report-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Reports konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            reports = tmp;
            long persistierterZaehler = leseZaehlerAusMeta();
            zaehler.set(Math.max(maxId, Math.max(alterZaehler, persistierterZaehler)));
            LOG.info("Es wurden {} Reports aus MySQL geladen (Zähler={}).", tmp.size(), zaehler.get());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private long leseZaehlerAusMeta() {
        try (Connection verbindung = mysql.verbindung()) {
            String roh = MysqlSchema.metaLesen(verbindung, META_ZAEHLER);
            if (roh == null || roh.isBlank()) {
                return 0L;
            }
            return Math.max(0L, Long.parseLong(roh.trim()));
        } catch (Exception e) {
            LOG.warn("Der persistierte Report-Zähler konnte nicht gelesen werden; er wird aus den IDs rekonstruiert.", e);
            return 0L;
        }
    }

    private void schreibeZaehlerNachMysql(Connection verbindung) throws Exception {
        MysqlSchema.metaSchreiben(verbindung, META_ZAEHLER, String.valueOf(zaehler.get()));
    }

    private void speichereNachMysql(List<ReportRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (Statement loeschen = verbindung.createStatement()) {
                loeschen.execute("DELETE FROM " + MysqlSchema.T_REPORTS);
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_REPORTS
                            + " (id, ziel_uuid, ziel_name, reporter_uuid, reporter_name, grund, server,"
                            + " erstellt_am, status, bearbeiter, bearbeitet_am, close_grund)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (ReportRecord report : snapshot) {
                    if (report == null) {
                        continue;
                    }
                    einfuegen.setLong(1, report.id());
                    einfuegen.setString(2, report.zielUuid().toString());
                    einfuegen.setString(3, report.zielName());
                    einfuegen.setString(4, report.reporterUuid() == null ? null
                            : report.reporterUuid().toString());
                    einfuegen.setString(5, report.reporterName());
                    einfuegen.setString(6, report.grund());
                    einfuegen.setString(7, report.server());
                    einfuegen.setLong(8, report.erstelltAm());
                    einfuegen.setString(9, report.status().name());
                    einfuegen.setString(10, report.bearbeiter());
                    einfuegen.setLong(11, report.bearbeitetAm());
                    einfuegen.setString(12, report.closeGrund());
                    einfuegen.addBatch();
                }
                einfuegen.executeBatch();
            }
            schreibeZaehlerNachMysql(verbindung);
            verbindung.commit();
        } catch (Exception e) {
            try {
                if (verbindung != null) {
                    verbindung.rollback();
                }
            } catch (Exception rollbackFehler) {
                LOG.debug("Rollback fehlgeschlagen.", rollbackFehler);
            }
            LOG.error("Reports konnten nicht nach MySQL geschrieben werden.", e);
            throw new IllegalStateException("Reports konnten nicht nach MySQL geschrieben werden.", e);
        } finally {
            if (verbindung != null) {
                try {
                    verbindung.setAutoCommit(autoCommit);
                } catch (Exception restoreFehler) {
                    LOG.debug("autoCommit konnte nicht wiederhergestellt werden.", restoreFehler);
                }
                try {
                    verbindung.close();
                } catch (Exception closeFehler) {
                    LOG.debug("MySQL-Verbindung konnte nicht geschlossen werden.", closeFehler);
                }
            }
        }
    }

    void upsertZeileNachMysql(ReportRecord report) {
        java.util.Objects.requireNonNull(report, "Der Report darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement upsert = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_REPORTS
                                + " (id, ziel_uuid, ziel_name, reporter_uuid, reporter_name, grund, server,"
                                + " erstellt_am, status, bearbeiter, bearbeitet_am, close_grund)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE ziel_uuid = VALUES(ziel_uuid),"
                                + " ziel_name = VALUES(ziel_name), reporter_uuid = VALUES(reporter_uuid),"
                                + " reporter_name = VALUES(reporter_name), grund = VALUES(grund),"
                                + " server = VALUES(server), erstellt_am = VALUES(erstellt_am),"
                                + " status = VALUES(status), bearbeiter = VALUES(bearbeiter),"
                                + " bearbeitet_am = VALUES(bearbeitet_am), close_grund = VALUES(close_grund)")) {
            schreibeReportZeile(upsert, report);
            upsert.executeUpdate();
            schreibeZaehlerNachMysql(verbindung);
        } catch (Exception e) {
            LOG.error("Report-Zeile {} konnte nicht nach MySQL geschrieben werden.", report.id(), e);
            throw new IllegalStateException("Report-Zeile konnte nicht nach MySQL geschrieben werden.", e);
        }
    }

    void loescheZeileNachMysql(long id) {
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_REPORTS + " WHERE id = ?")) {
            loeschen.setLong(1, id);
            loeschen.executeUpdate();
            schreibeZaehlerNachMysql(verbindung);
        } catch (Exception e) {
            LOG.error("Report-Zeile {} konnte nicht aus MySQL gelöscht werden.", id, e);
            throw new IllegalStateException("Report-Zeile konnte nicht aus MySQL gelöscht werden.", e);
        }
    }

    private static void schreibeReportZeile(PreparedStatement statement, ReportRecord report) throws Exception {
        statement.setLong(1, report.id());
        statement.setString(2, report.zielUuid().toString());
        statement.setString(3, report.zielName());
        statement.setString(4, report.reporterUuid() == null ? null : report.reporterUuid().toString());
        statement.setString(5, report.reporterName());
        statement.setString(6, report.grund());
        statement.setString(7, report.server());
        statement.setLong(8, report.erstelltAm());
        statement.setString(9, report.status().name());
        statement.setString(10, report.bearbeiter());
        statement.setLong(11, report.bearbeitetAm());
        statement.setString(12, report.closeGrund());
    }

    private static ReportRecord zeileLesen(ResultSet ergebnis) throws Exception {
        String reporterUuidText = ergebnis.getString("reporter_uuid");
        UUID reporterUuid = null;
        if (reporterUuidText != null && !reporterUuidText.isBlank()) {
            reporterUuid = UUID.fromString(reporterUuidText.trim());
        }
        ReportStatus status = ReportStatus.parse(ergebnis.getString("status"));
        if (status == null) {
            throw new IllegalArgumentException("Unbekannter Report-Status.");
        }
        return new ReportRecord(ergebnis.getLong("id"),
                UUID.fromString(ergebnis.getString("ziel_uuid")),
                ergebnis.getString("ziel_name"),
                reporterUuid,
                ergebnis.getString("reporter_name"),
                ergebnis.getString("grund"),
                ergebnis.getString("server"),
                ergebnis.getLong("erstellt_am"),
                status,
                ergebnis.getString("bearbeiter"),
                ergebnis.getLong("bearbeitet_am"),
                ergebnis.getString("close_grund"));
    }

    private List<ReportRecord> alleAufsteigend() {
        return reports.values().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(ReportRecord::id))
                .toList();
    }

    private static ReportRecord parseEintrag(Map<String, Object> eintrag) {
        long id = Karten.zahl(eintrag, SCHLUESSEL_ID, 0L);
        String zielUuidText = Karten.text(eintrag, SCHLUESSEL_ZIEL_UUID, null);
        String zielName = Karten.text(eintrag, SCHLUESSEL_ZIEL_NAME, "Unbekannt");
        String reporterUuidText = Karten.text(eintrag, SCHLUESSEL_REPORTER_UUID, null);
        String reporterName = Karten.text(eintrag, SCHLUESSEL_REPORTER_NAME, "Konsole");
        String grund = Karten.text(eintrag, SCHLUESSEL_GRUND, "");
        String server = Karten.text(eintrag, SCHLUESSEL_SERVER, "unbekannt");
        long erstelltAm = Karten.zahl(eintrag, SCHLUESSEL_ERSTELLT_AM, 0L);
        String statusText = Karten.text(eintrag, SCHLUESSEL_STATUS, "OFFEN");
        String bearbeiter = Karten.text(eintrag, SCHLUESSEL_BEARBEITER, "");
        long bearbeitetAm = Karten.zahl(eintrag, SCHLUESSEL_BEARBEITET_AM, 0L);
        String closeGrund = Karten.text(eintrag, SCHLUESSEL_CLOSE_GRUND, "");
        UUID zielUuid = UUID.fromString(zielUuidText);
        UUID reporterUuid = null;
        if (reporterUuidText != null && !reporterUuidText.isBlank()) {
            try {
                reporterUuid = UUID.fromString(reporterUuidText.trim());
            } catch (IllegalArgumentException ignored) {
                reporterUuid = null;
            }
        }
        ReportStatus status = ReportStatus.parse(statusText);
        if (status == null) {
            throw new IllegalArgumentException("Unbekannter Report-Status: " + statusText);
        }
        return new ReportRecord(id, zielUuid, zielName, reporterUuid, reporterName,
                grund, server, erstelltAm, status, bearbeiter, bearbeitetAm, closeGrund);
    }

    private Map<String, Object> konfigurationErstellen(List<ReportRecord> liste,
                                                       List<?> rohQuarantaeneListe,
                                                       long zaehlerWert) {
        Map<String, Object> daten = new LinkedHashMap<>();
        daten.put(SCHLUESSEL_ZAEHLER, zaehlerWert);
        List<Object> eintraege = new ArrayList<>();
        for (ReportRecord report : liste) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put(SCHLUESSEL_ID, report.id());
            eintrag.put(SCHLUESSEL_ZIEL_UUID, report.zielUuid().toString());
            eintrag.put(SCHLUESSEL_ZIEL_NAME, report.zielName());
            eintrag.put(SCHLUESSEL_REPORTER_UUID,
                    report.reporterUuid() == null ? "" : report.reporterUuid().toString());
            eintrag.put(SCHLUESSEL_REPORTER_NAME, report.reporterName());
            eintrag.put(SCHLUESSEL_GRUND, report.grund());
            eintrag.put(SCHLUESSEL_SERVER, report.server());
            eintrag.put(SCHLUESSEL_ERSTELLT_AM, report.erstelltAm());
            eintrag.put(SCHLUESSEL_STATUS, report.status().name());
            eintrag.put(SCHLUESSEL_BEARBEITER, report.bearbeiter());
            eintrag.put(SCHLUESSEL_BEARBEITET_AM, report.bearbeitetAm());
            eintrag.put(SCHLUESSEL_CLOSE_GRUND, report.closeGrund());
            eintraege.add(eintrag);
        }
        if (rohQuarantaeneListe != null) {
            for (Object roh : rohQuarantaeneListe) {
                if (roh != null) {
                    eintraege.add(roh);
                }
            }
        }
        daten.put(SCHLUESSEL_LISTE, eintraege);
        return daten;
    }
}
