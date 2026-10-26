package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpStrafSchluessel;
import de.lightrixx.punish.model.IpTimeoutRecord;
import de.lightrixx.punish.model.ServerScope;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class IpTimeoutRepository {

    private static final Logger LOG = LoggerFactory.getLogger(IpTimeoutRepository.class);
    private static final String DATEI_NAME = "iptimeouts.yml";
    private static final String SCHLUESSEL_LISTE = "ip-timeouts";
    private static final String SCHLUESSEL_IP = "ip";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";
    private static final String SCHLUESSEL_ABLAUF_AM = "ablauf-am";
    private static final String SCHLUESSEL_SCOPE = "scope";

    private static Map<String, Object> rohKarte(IpTimeoutRecord timeout) {
        Map<String, Object> eintrag = new LinkedHashMap<>();
        eintrag.put(SCHLUESSEL_IP, timeout.ip());
        eintrag.put(SCHLUESSEL_GRUND, timeout.grund());
        eintrag.put(SCHLUESSEL_AUSFUEHRER, timeout.ausfuehrer());
        eintrag.put(SCHLUESSEL_ERSTELLT_AM, timeout.erstelltAm());
        eintrag.put(SCHLUESSEL_ABLAUF_AM, timeout.ablaufAm());
        eintrag.put(SCHLUESSEL_SCOPE, timeout.scope().speicherwert());
        return eintrag;
    }

    private volatile ConcurrentHashMap<IpStrafSchluessel, IpTimeoutRecord> timeouts = new ConcurrentHashMap<>();

    private volatile java.util.List<Object> rohQuarantaeneListe = java.util.List.of();
    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public IpTimeoutRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(datenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public IpTimeoutRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
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
                LOG.warn("IP-Timeout-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor IP-Timeout-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = timeouts.size();
            YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
            Map<String, Object> daten = ladeStatus.daten();
            ConcurrentHashMap<IpStrafSchluessel, IpTimeoutRecord> tmp = new ConcurrentHashMap<>();
            java.util.List<Object> tmpRoh = new java.util.ArrayList<>();
            java.util.List<?> rohListe = Karten.liste(daten, SCHLUESSEL_LISTE);
            if (rohListe == null) {
                rohListe = java.util.List.of();
            }
            for (Object rohObj : rohListe) {
                Map<String, Object> eintrag = Karten.abschnitt(rohObj);
                if (eintrag == null) {
                    LOG.error("Ein IP-Timeout-Eintrag ist korrupt (kein Map) und wird QUARANTIERT (nicht gelöscht).");
                    if (rohObj != null) {
                        tmpRoh.add(rohObj);
                    }
                    continue;
                }
                try {
                    String ip = eintrag.get(SCHLUESSEL_IP) instanceof String wert ? wert : null;
                    String grund = eintrag.get(SCHLUESSEL_GRUND) instanceof String wert ? wert : "";
                    String ausfuehrer = eintrag.get(SCHLUESSEL_AUSFUEHRER) instanceof String wert ? wert : "Konsole";
                    long erstelltAm = eintrag.get(SCHLUESSEL_ERSTELLT_AM) instanceof Number zahl ? zahl.longValue() : 0L;
                    long ablaufAm = eintrag.get(SCHLUESSEL_ABLAUF_AM) instanceof Number zahl ? zahl.longValue() : 0L;
                    String scopeRoh = eintrag.get(SCHLUESSEL_SCOPE) instanceof String wert ? wert : null;
                    ServerScope scope = ServerScope.ausSpeicherwert(scopeRoh);
                    if (erstelltAm <= 0) {
                        if (ablaufAm > 0 && ablaufAm <= System.currentTimeMillis()) {
                            LOG.info("Abgelaufener IP-Timeout-Eintrag für {} wird verworfen (kein gültiges erstellt-am).",
                                    ip);
                            continue;
                        }
                        LOG.warn("IP-Timeout-Eintrag hat ungültiges erstellt-am, heile auf jetzt.");
                        erstelltAm = System.currentTimeMillis();
                    }
                    if (ablaufAm <= 0) {
                        LOG.error("IP-Timeout-Eintrag hat fehlendes/ungültiges ablauf-am und wird QUARANTIERT (nicht geheilt).");
                        tmpRoh.add(rohObj);
                        continue;
                    }
                    IpTimeoutRecord timeout = new IpTimeoutRecord(ip, grund, ausfuehrer, erstelltAm, ablaufAm, scope);
                    IpStrafSchluessel key = IpStrafSchluessel.von(timeout.ip(), scope);
                    IpTimeoutRecord vorher = tmp.put(key, timeout);
                    if (vorher != null) {
                        LOG.warn("Doppelter IP-Timeout-Eintrag für {} (Scope '{}') – letzter gewinnt, "
                                        + "der überschriebene Eintrag wird quarantänisiert.",
                                timeout.ip(), scope.speicherwert());
                        tmpRoh.add(rohKarte(vorher));
                    }
                } catch (Exception e) {
                    LOG.error("Ein IP-Timeout-Eintrag ist korrupt und wird QUARANTIERT (nicht gelöscht).", e);
                    tmpRoh.add(rohObj);
                }
            }

            if (ladeStatus.fehler()) {
                LOG.error("iptimeouts.yml konnte nicht gelesen werden ({} Einträge im Memory) – "
                        + "behalte alten Stand (Fail-Closed).", alteGroesse);
            } else {
                timeouts = tmp;
                rohQuarantaeneListe = java.util.List.copyOf(tmpRoh);
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} korrupte IP-Timeout-Einträge in Quarantäne (bleiben auf Disk erhalten).", tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} IP-Timeouts aus der Datei geladen.", timeouts.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public boolean timeoutVerhaengenWennFrei(IpTimeoutRecord timeout) {
        Objects.requireNonNull(timeout, "Der IP-Timeout darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            long jetzt = System.currentTimeMillis();
            for (java.util.Map.Entry<IpStrafSchluessel, IpTimeoutRecord> eintrag : timeouts.entrySet()) {
                IpTimeoutRecord bestehend = eintrag.getValue();
                if (bestehend != null && eintrag.getKey().ip().equals(timeout.ip())
                        && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(timeout.scope())) {
                    return false;
                }
            }
            timeouts.put(IpStrafSchluessel.von(timeout.ip(), timeout.scope()), timeout);
            if (mysql != null) {
                final IpTimeoutRecord snapshot = timeout;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
            } else {
                speichern();
            }
            return true;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public int entferneAbgelaufene(long jetztMillis) {
        StoreLock.LOCK.writeLock().lock();
        try {
            java.util.Map<IpStrafSchluessel, IpTimeoutRecord> abgelaufen = new java.util.HashMap<>();
            timeouts.forEach((key, record) -> {
                if (record.istAbgelaufen(jetztMillis)) {
                    abgelaufen.put(key, record);
                }
            });
            int entfernt = 0;
            for (java.util.Map.Entry<IpStrafSchluessel, IpTimeoutRecord> eintrag : abgelaufen.entrySet()) {
                if (timeouts.remove(eintrag.getKey(), eintrag.getValue())) {
                    entfernt++;
                }
            }
            if (entfernt > 0) {
                if (mysql != null) {
                    final java.util.List<IpStrafSchluessel> keys = java.util.List.copyOf(abgelaufen.keySet());
                    ioExecutor.ausfuehren(() -> loescheBatchNachMysql(keys));
                } else {
                    speichern();
                }
                LOG.info("Es wurden {} abgelaufene IP-Timeouts bereinigt.", entfernt);
            }
            return entfernt;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<IpTimeoutRecord> timeoutAufheben(String ip, ServerScope scope) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            IpTimeoutRecord entfernt = timeouts.remove(IpStrafSchluessel.von(normalisiert, scope));
            if (entfernt != null) {
                if (mysql != null) {
                    ioExecutor.ausfuehren(() -> loescheZeileNachMysql(normalisiert, scope));
                } else {
                    speichern();
                }
            }
            return Optional.ofNullable(entfernt);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<IpTimeoutRecord> timeoutAufhebenWenn(String ip, ServerScope scope,
                                                          java.util.function.Predicate<IpTimeoutRecord> bedingung) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            IpStrafSchluessel key = IpStrafSchluessel.von(normalisiert, scope);
            IpTimeoutRecord bestehend = timeouts.get(key);
            if (bestehend != null && bedingung.test(bestehend) && timeouts.remove(key, bestehend)) {
                if (mysql != null) {
                    ioExecutor.ausfuehren(() -> loescheZeileNachMysql(normalisiert, scope));
                } else {
                    speichern();
                }
                return Optional.of(bestehend);
            }
            return Optional.empty();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<IpTimeoutRecord> ersetzenWenn(String ip, ServerScope scope,
                                                 java.util.function.Predicate<IpTimeoutRecord> bedingung,
                                                 IpTimeoutRecord neu) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        Objects.requireNonNull(neu, "Der neue IP-Timeout darf nicht null sein.");
        if (!normalisiert.equals(neu.ip())) {
            throw new IllegalArgumentException("Der neue IP-Timeout passt nicht zum Schlüssel " + normalisiert);
        }
        if (!IpStrafSchluessel.von(normalisiert, scope)
                .equals(IpStrafSchluessel.von(neu.ip(), neu.scope()))) {
            throw new IllegalArgumentException("Scope-Wechsel per Edit ist nicht erlaubt.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            IpStrafSchluessel key = IpStrafSchluessel.von(normalisiert, scope);
            IpTimeoutRecord bestehend = timeouts.get(key);
            if (bestehend != null && bedingung.test(bestehend)
                    && timeouts.replace(key, bestehend, neu)) {
                if (mysql != null) {
                    final IpTimeoutRecord snapshot = neu;
                    ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
                } else {
                    speichern();
                }
                return Optional.of(bestehend);
            }
            return Optional.empty();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public java.util.Collection<IpTimeoutRecord> alle() {
        return List.copyOf(timeouts.values());
    }

    public List<IpTimeoutRecord> alleFuer(String ip) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return List.of();
        }
        List<IpTimeoutRecord> treffer = new ArrayList<>();
        for (java.util.Map.Entry<IpStrafSchluessel, IpTimeoutRecord> eintrag : timeouts.entrySet()) {
            if (eintrag.getKey().ip().equals(normalisiert) && eintrag.getValue() != null) {
                treffer.add(eintrag.getValue());
            }
        }
        return List.copyOf(treffer);
    }

    public Optional<IpTimeoutRecord> finden(String ip, ServerScope scope) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return Optional.ofNullable(timeouts.get(IpStrafSchluessel.von(normalisiert, scope)));
    }

    public Optional<IpTimeoutRecord> findeAbdeckend(String ip, ServerScope ziel, long jetztMillis) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        IpTimeoutRecord exakt = null;
        IpTimeoutRecord abdeckend = null;
        for (java.util.Map.Entry<IpStrafSchluessel, IpTimeoutRecord> eintrag : timeouts.entrySet()) {
            if (!eintrag.getKey().ip().equals(normalisiert)) {
                continue;
            }
            IpTimeoutRecord kandidat = eintrag.getValue();
            if (kandidat == null || !kandidat.istAktiv(jetztMillis)
                    || !kandidat.scope().decktAb(ziel)) {
                continue;
            }
            if (eintrag.getKey().scope().equals(ziel.speicherwert())) {
                exakt = kandidat;
                break;
            }
            if (abdeckend == null) {
                abdeckend = kandidat;
            }
        }
        return Optional.ofNullable(exakt != null ? exakt : abdeckend);
    }

    public boolean istAbgedeckt(String ip, ServerScope ziel, long jetztMillis) {
        return findeAbdeckend(ip, ziel, jetztMillis).isPresent();
    }

    private void speichern() {
        java.util.Collection<IpTimeoutRecord> snapshot = List.copyOf(timeouts.values());
        if (mysql != null) {
            List<IpTimeoutRecord> kopie = List.copyOf(snapshot);
            ioExecutor.ausfuehren(() -> speichereNachMysql(kopie));
            return;
        }
        java.util.List<Object> rohSnapshot = java.util.List.copyOf(rohQuarantaeneListe);
        Map<String, Object> root = konfigurationErstellen(snapshot, rohSnapshot);
        ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> yamlSpeicher.speichern(root, datei));
    }

    public int migrationImport(Collection<IpTimeoutRecord> alle) {
        Objects.requireNonNull(alle, "Die IP-Timeout-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<IpStrafSchluessel, IpTimeoutRecord> neu = new ConcurrentHashMap<>();
            for (IpTimeoutRecord timeout : alle) {
                if (timeout == null) {
                    continue;
                }
                neu.put(IpStrafSchluessel.von(timeout.ip(), timeout.scope()), timeout);
            }
            timeouts = neu;
            List<IpTimeoutRecord> snapshot = List.copyOf(neu.values());
            ioExecutor.ausfuehren(() -> speichereNachMysql(snapshot));
            return neu.size();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = timeouts.size();
            ConcurrentHashMap<IpStrafSchluessel, IpTimeoutRecord> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am"
                                 + " FROM " + MysqlSchema.T_IP_TIMEOUTS)) {
                while (ergebnis.next()) {
                    try {
                        IpTimeoutRecord timeout = zeileLesen(ergebnis);
                        tmp.put(IpStrafSchluessel.von(timeout.ip(), timeout.scope()), timeout);
                    } catch (Exception e) {
                        LOG.error("Eine IP-Timeout-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("IP-Timeouts konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            timeouts = tmp;
            LOG.info("Es wurden {} IP-Timeouts aus MySQL geladen.", timeouts.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereNachMysql(Collection<IpTimeoutRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (Statement loeschen = verbindung.createStatement()) {
                loeschen.execute("DELETE FROM " + MysqlSchema.T_IP_TIMEOUTS);
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_IP_TIMEOUTS
                            + " (ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                for (IpTimeoutRecord timeout : snapshot) {
                    if (timeout == null) {
                        continue;
                    }
                    einfuegen.setString(1, timeout.ip());
                    einfuegen.setString(2, timeout.scope().speicherwert());
                    einfuegen.setString(3, timeout.grund());
                    einfuegen.setString(4, timeout.ausfuehrer());
                    einfuegen.setLong(5, timeout.erstelltAm());
                    einfuegen.setLong(6, timeout.ablaufAm());
                    einfuegen.addBatch();
                }
                einfuegen.executeBatch();
            }
            verbindung.commit();
        } catch (Exception e) {
            try {
                if (verbindung != null) {
                    verbindung.rollback();
                }
            } catch (Exception rollbackFehler) {
                LOG.debug("Rollback fehlgeschlagen.", rollbackFehler);
            }
            LOG.error("IP-Timeouts konnten nicht nach MySQL geschrieben werden.", e);
            throw new IllegalStateException("IP-Timeouts konnten nicht nach MySQL geschrieben werden.", e);
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

    void upsertZeileNachMysql(IpTimeoutRecord timeout) {
        java.util.Objects.requireNonNull(timeout, "Der IP-Timeout darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement upsert = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_IP_TIMEOUTS
                                + " (ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am)"
                                + " VALUES (?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE grund = VALUES(grund),"
                                + " ausfuehrer = VALUES(ausfuehrer), erstellt_am = VALUES(erstellt_am),"
                                + " ablauf_am = VALUES(ablauf_am)")) {
            upsert.setString(1, timeout.ip());
            upsert.setString(2, timeout.scope().speicherwert());
            upsert.setString(3, timeout.grund());
            upsert.setString(4, timeout.ausfuehrer());
            upsert.setLong(5, timeout.erstelltAm());
            upsert.setLong(6, timeout.ablaufAm());
            upsert.executeUpdate();
        } catch (Exception e) {
            LOG.error("IP-Timeout-Zeile für {} konnte nicht nach MySQL geschrieben werden.", timeout.ip(), e);
            throw new IllegalStateException("IP-Timeout-Zeile konnte nicht nach MySQL geschrieben werden.", e);
        }
    }

    void loescheZeileNachMysql(String ip, ServerScope scope) {
        if (ip == null) {
            return;
        }
        java.util.Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_IP_TIMEOUTS + " WHERE ip = ? AND scope = ?")) {
            loeschen.setString(1, ip);
            loeschen.setString(2, scope.speicherwert());
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.error("IP-Timeout-Zeile für {} konnte nicht aus MySQL gelöscht werden.", ip, e);
            throw new IllegalStateException("IP-Timeout-Zeile konnte nicht aus MySQL gelöscht werden.", e);
        }
    }

    void loescheBatchNachMysql(java.util.Collection<IpStrafSchluessel> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "DELETE FROM " + MysqlSchema.T_IP_TIMEOUTS + " WHERE ip = ? AND scope = ?")) {
                for (IpStrafSchluessel key : keys) {
                    if (key == null) {
                        continue;
                    }
                    loeschen.setString(1, key.ip());
                    loeschen.setString(2, key.scope());
                    loeschen.addBatch();
                }
                loeschen.executeBatch();
            }
            verbindung.commit();
        } catch (Exception e) {
            try {
                if (verbindung != null) {
                    verbindung.rollback();
                }
            } catch (Exception rollbackFehler) {
                LOG.debug("Rollback fehlgeschlagen.", rollbackFehler);
            }
            LOG.error("IP-Timeout-Batch-Löschung ({} Zeilen) fehlgeschlagen.", keys.size(), e);
            throw new IllegalStateException("IP-Timeout-Batch-Löschung fehlgeschlagen.", e);
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

    private static IpTimeoutRecord zeileLesen(ResultSet ergebnis) throws Exception {
        return new IpTimeoutRecord(ergebnis.getString("ip"),
                ergebnis.getString("grund"),
                ergebnis.getString("ausfuehrer"),
                ergebnis.getLong("erstellt_am"),
                ergebnis.getLong("ablauf_am"),
                ServerScope.ausSpeicherwert(ergebnis.getString("scope")));
    }

    private Map<String, Object> konfigurationErstellen(java.util.Collection<IpTimeoutRecord> eintraege,
                                                      java.util.List<?> rohQuarantaeneListe) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> liste = new ArrayList<>();
        for (IpTimeoutRecord timeout : eintraege) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put(SCHLUESSEL_IP, timeout.ip());
            eintrag.put(SCHLUESSEL_GRUND, timeout.grund());
            eintrag.put(SCHLUESSEL_AUSFUEHRER, timeout.ausfuehrer());
            eintrag.put(SCHLUESSEL_ERSTELLT_AM, timeout.erstelltAm());
            eintrag.put(SCHLUESSEL_ABLAUF_AM, timeout.ablaufAm());
            eintrag.put(SCHLUESSEL_SCOPE, timeout.scope().speicherwert());
            liste.add(eintrag);
        }
        if (rohQuarantaeneListe != null) {
            for (Object roh : rohQuarantaeneListe) {
                if (roh == null) {
                    continue;
                }
                try {
                    liste.add(roh);
                } catch (Exception e) {
                    LOG.warn("Quarantäne-Eintrag konnte nicht zurückgeschrieben werden.", e);
                }
            }
        }
        root.put(SCHLUESSEL_LISTE, liste);
        return root;
    }

    private String normalisieren(String ip) {
        if (ip == null) {
            return null;
        }
        try {
            return IpBanRecord.kanonisieren(ip);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
