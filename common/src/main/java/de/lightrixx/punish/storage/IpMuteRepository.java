package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpMuteRecord;
import de.lightrixx.punish.model.IpStrafSchluessel;
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

public final class IpMuteRepository {

    private static final Logger LOG = LoggerFactory.getLogger(IpMuteRepository.class);
    private static final String DATEI_NAME = "ipmutes.yml";
    private static final String SCHLUESSEL_LISTE = "ip-mutes";
    private static final String SCHLUESSEL_IP = "ip";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";
    private static final String SCHLUESSEL_ABLAUF_AM = "ablauf-am";
    private static final String SCHLUESSEL_SCOPE = "scope";

    private volatile ConcurrentHashMap<IpStrafSchluessel, IpMuteRecord> mutes = new ConcurrentHashMap<>();

    private volatile java.util.List<Object> rohQuarantaeneListe = java.util.List.of();
    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public IpMuteRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(datenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public IpMuteRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
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
                LOG.warn("IP-Mute-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor IP-Mute-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = mutes.size();
            YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
            Map<String, Object> daten = ladeStatus.daten();
            ConcurrentHashMap<IpStrafSchluessel, IpMuteRecord> tmp = new ConcurrentHashMap<>();
            java.util.List<Object> tmpRoh = new java.util.ArrayList<>();
            java.util.List<?> rohListe = Karten.liste(daten, SCHLUESSEL_LISTE);
            if (rohListe == null) {
                rohListe = java.util.List.of();
            }
            for (Object rohObj : rohListe) {
                Map<String, Object> eintrag = Karten.abschnitt(rohObj);
                if (eintrag == null) {
                    LOG.error("Ein IP-Mute-Eintrag ist korrupt (kein Map) und wird QUARANTIERT (nicht gelöscht).");
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
                            LOG.info("Abgelaufener IP-Mute-Eintrag für {} wird verworfen (kein gültiges erstellt-am).",
                                    ip);
                            continue;
                        }
                        LOG.warn("IP-Mute-Eintrag hat ungültiges erstellt-am, heile auf jetzt.");
                        erstelltAm = System.currentTimeMillis();
                    }
                    if (ablaufAm <= 0) {
                        LOG.error("IP-Mute-Eintrag hat fehlendes/ungültiges ablauf-am und wird QUARANTIERT (nicht geheilt).");
                        tmpRoh.add(rohObj);
                        continue;
                    }
                    IpMuteRecord mute = new IpMuteRecord(ip, grund, ausfuehrer, erstelltAm, ablaufAm, scope);
                    IpStrafSchluessel key = IpStrafSchluessel.von(mute.ip(), scope);
                    if (tmp.put(key, mute) != null) {
                        LOG.warn("Doppelter IP-Mute-Eintrag für {} (Scope '{}') – letzter gewinnt.",
                                mute.ip(), scope.speicherwert());
                    }
                } catch (Exception e) {
                    LOG.error("Ein IP-Mute-Eintrag ist korrupt und wird QUARANTIERT (nicht gelöscht).", e);
                    tmpRoh.add(rohObj);
                }
            }

            if (ladeStatus.fehler()) {
                LOG.error("ipmutes.yml konnte nicht gelesen werden ({} Einträge im Memory) – "
                        + "behalte alten Stand (Fail-Closed).", alteGroesse);
            } else {
                mutes = tmp;
                rohQuarantaeneListe = java.util.List.copyOf(tmpRoh);
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} korrupte IP-Mute-Einträge in Quarantäne (bleiben auf Disk erhalten).", tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} IP-Mutes aus der Datei geladen.", mutes.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public boolean mutenWennFrei(IpMuteRecord mute) {
        Objects.requireNonNull(mute, "Der IP-Mute darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            long jetzt = System.currentTimeMillis();
            for (java.util.Map.Entry<IpStrafSchluessel, IpMuteRecord> eintrag : mutes.entrySet()) {
                IpMuteRecord bestehend = eintrag.getValue();
                if (bestehend != null && eintrag.getKey().ip().equals(mute.ip())
                        && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(mute.scope())) {
                    return false;
                }
            }
            mutes.put(IpStrafSchluessel.von(mute.ip(), mute.scope()), mute);
            if (mysql != null) {
                final IpMuteRecord snapshot = mute;
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
            java.util.Map<IpStrafSchluessel, IpMuteRecord> abgelaufen = new java.util.HashMap<>();
            mutes.forEach((key, record) -> {
                if (record.istAbgelaufen(jetztMillis)) {
                    abgelaufen.put(key, record);
                }
            });
            int entfernt = 0;
            for (java.util.Map.Entry<IpStrafSchluessel, IpMuteRecord> eintrag : abgelaufen.entrySet()) {
                if (mutes.remove(eintrag.getKey(), eintrag.getValue())) {
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
                LOG.info("Es wurden {} abgelaufene IP-Mutes bereinigt.", entfernt);
            }
            return entfernt;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<IpMuteRecord> entmuten(String ip, ServerScope scope) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            IpMuteRecord entfernt = mutes.remove(IpStrafSchluessel.von(normalisiert, scope));
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

    public Optional<IpMuteRecord> entmutenWenn(String ip, ServerScope scope,
                                               java.util.function.Predicate<IpMuteRecord> bedingung) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            IpStrafSchluessel key = IpStrafSchluessel.von(normalisiert, scope);
            IpMuteRecord bestehend = mutes.get(key);
            if (bestehend != null && bedingung.test(bestehend) && mutes.remove(key, bestehend)) {
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

    public Optional<IpMuteRecord> ersetzenWenn(String ip, ServerScope scope,
                                              java.util.function.Predicate<IpMuteRecord> bedingung,
                                              IpMuteRecord neu) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        Objects.requireNonNull(neu, "Der neue IP-Mute darf nicht null sein.");
        if (!normalisiert.equals(neu.ip())) {
            throw new IllegalArgumentException("Der neue IP-Mute passt nicht zum Schlüssel " + normalisiert);
        }
        if (!IpStrafSchluessel.von(normalisiert, scope)
                .equals(IpStrafSchluessel.von(neu.ip(), neu.scope()))) {
            throw new IllegalArgumentException("Scope-Wechsel per Edit ist nicht erlaubt.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            IpStrafSchluessel key = IpStrafSchluessel.von(normalisiert, scope);
            IpMuteRecord bestehend = mutes.get(key);
            if (bestehend != null && bedingung.test(bestehend)
                    && mutes.replace(key, bestehend, neu)) {
                if (mysql != null) {
                    final IpMuteRecord snapshot = neu;
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

    public java.util.Collection<IpMuteRecord> alle() {
        return List.copyOf(mutes.values());
    }

    public List<IpMuteRecord> alleFuer(String ip) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return List.of();
        }
        List<IpMuteRecord> treffer = new ArrayList<>();
        for (java.util.Map.Entry<IpStrafSchluessel, IpMuteRecord> eintrag : mutes.entrySet()) {
            if (eintrag.getKey().ip().equals(normalisiert) && eintrag.getValue() != null) {
                treffer.add(eintrag.getValue());
            }
        }
        return List.copyOf(treffer);
    }

    public Optional<IpMuteRecord> finden(String ip, ServerScope scope) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return Optional.ofNullable(mutes.get(IpStrafSchluessel.von(normalisiert, scope)));
    }

    public Optional<IpMuteRecord> findeAbdeckend(String ip, ServerScope ziel, long jetztMillis) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        IpMuteRecord exakt = null;
        IpMuteRecord abdeckend = null;
        for (java.util.Map.Entry<IpStrafSchluessel, IpMuteRecord> eintrag : mutes.entrySet()) {
            if (!eintrag.getKey().ip().equals(normalisiert)) {
                continue;
            }
            IpMuteRecord kandidat = eintrag.getValue();
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
        java.util.Collection<IpMuteRecord> snapshot = List.copyOf(mutes.values());
        if (mysql != null) {
            List<IpMuteRecord> kopie = List.copyOf(snapshot);
            ioExecutor.ausfuehren(() -> speichereNachMysql(kopie));
            return;
        }
        java.util.List<Object> rohSnapshot = java.util.List.copyOf(rohQuarantaeneListe);
        Map<String, Object> root = konfigurationErstellen(snapshot, rohSnapshot);
        ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> yamlSpeicher.speichern(root, datei));
    }

    public int migrationImport(Collection<IpMuteRecord> alle) {
        Objects.requireNonNull(alle, "Die IP-Mute-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<IpStrafSchluessel, IpMuteRecord> neu = new ConcurrentHashMap<>();
            for (IpMuteRecord mute : alle) {
                if (mute == null) {
                    continue;
                }
                neu.put(IpStrafSchluessel.von(mute.ip(), mute.scope()), mute);
            }
            mutes = neu;
            List<IpMuteRecord> snapshot = List.copyOf(neu.values());
            ioExecutor.ausfuehren(() -> speichereNachMysql(snapshot));
            return neu.size();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = mutes.size();
            ConcurrentHashMap<IpStrafSchluessel, IpMuteRecord> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am"
                                 + " FROM " + MysqlSchema.T_IP_MUTES)) {
                while (ergebnis.next()) {
                    try {
                        IpMuteRecord mute = zeileLesen(ergebnis);
                        tmp.put(IpStrafSchluessel.von(mute.ip(), mute.scope()), mute);
                    } catch (Exception e) {
                        LOG.error("Eine IP-Mute-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("IP-Mutes konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            mutes = tmp;
            LOG.info("Es wurden {} IP-Mutes aus MySQL geladen.", mutes.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereNachMysql(Collection<IpMuteRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (Statement loeschen = verbindung.createStatement()) {
                loeschen.execute("DELETE FROM " + MysqlSchema.T_IP_MUTES);
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_IP_MUTES
                            + " (ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                for (IpMuteRecord mute : snapshot) {
                    if (mute == null) {
                        continue;
                    }
                    einfuegen.setString(1, mute.ip());
                    einfuegen.setString(2, mute.scope().speicherwert());
                    einfuegen.setString(3, mute.grund());
                    einfuegen.setString(4, mute.ausfuehrer());
                    einfuegen.setLong(5, mute.erstelltAm());
                    einfuegen.setLong(6, mute.ablaufAm());
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
            LOG.error("IP-Mutes konnten nicht nach MySQL geschrieben werden.", e);
            throw new IllegalStateException("IP-Mutes konnten nicht nach MySQL geschrieben werden.", e);
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

    void upsertZeileNachMysql(IpMuteRecord mute) {
        java.util.Objects.requireNonNull(mute, "Der IP-Mute darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement upsert = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_IP_MUTES
                                + " (ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am)"
                                + " VALUES (?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE grund = VALUES(grund),"
                                + " ausfuehrer = VALUES(ausfuehrer), erstellt_am = VALUES(erstellt_am),"
                                + " ablauf_am = VALUES(ablauf_am)")) {
            upsert.setString(1, mute.ip());
            upsert.setString(2, mute.scope().speicherwert());
            upsert.setString(3, mute.grund());
            upsert.setString(4, mute.ausfuehrer());
            upsert.setLong(5, mute.erstelltAm());
            upsert.setLong(6, mute.ablaufAm());
            upsert.executeUpdate();
        } catch (Exception e) {
            LOG.error("IP-Mute-Zeile für {} konnte nicht nach MySQL geschrieben werden.", mute.ip(), e);
            throw new IllegalStateException("IP-Mute-Zeile konnte nicht nach MySQL geschrieben werden.", e);
        }
    }

    void loescheZeileNachMysql(String ip, ServerScope scope) {
        if (ip == null) {
            return;
        }
        java.util.Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_IP_MUTES + " WHERE ip = ? AND scope = ?")) {
            loeschen.setString(1, ip);
            loeschen.setString(2, scope.speicherwert());
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.error("IP-Mute-Zeile für {} konnte nicht aus MySQL gelöscht werden.", ip, e);
            throw new IllegalStateException("IP-Mute-Zeile konnte nicht aus MySQL gelöscht werden.", e);
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
                    "DELETE FROM " + MysqlSchema.T_IP_MUTES + " WHERE ip = ? AND scope = ?")) {
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
            LOG.error("IP-Mute-Batch-Löschung ({} Zeilen) fehlgeschlagen.", keys.size(), e);
            throw new IllegalStateException("IP-Mute-Batch-Löschung fehlgeschlagen.", e);
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

    private static IpMuteRecord zeileLesen(ResultSet ergebnis) throws Exception {
        return new IpMuteRecord(ergebnis.getString("ip"),
                ergebnis.getString("grund"),
                ergebnis.getString("ausfuehrer"),
                ergebnis.getLong("erstellt_am"),
                ergebnis.getLong("ablauf_am"),
                ServerScope.ausSpeicherwert(ergebnis.getString("scope")));
    }

    private Map<String, Object> konfigurationErstellen(java.util.Collection<IpMuteRecord> eintraege,
                                                      java.util.List<?> rohQuarantaeneListe) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> liste = new ArrayList<>();
        for (IpMuteRecord mute : eintraege) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put(SCHLUESSEL_IP, mute.ip());
            eintrag.put(SCHLUESSEL_GRUND, mute.grund());
            eintrag.put(SCHLUESSEL_AUSFUEHRER, mute.ausfuehrer());
            eintrag.put(SCHLUESSEL_ERSTELLT_AM, mute.erstelltAm());
            eintrag.put(SCHLUESSEL_ABLAUF_AM, mute.ablaufAm());
            eintrag.put(SCHLUESSEL_SCOPE, mute.scope().speicherwert());
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
