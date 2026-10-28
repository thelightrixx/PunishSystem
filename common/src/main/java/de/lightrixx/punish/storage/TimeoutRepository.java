package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.StrafSchluessel;
import de.lightrixx.punish.model.TimeoutRecord;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TimeoutRepository {

    private static final Logger LOG = LoggerFactory.getLogger(TimeoutRepository.class);
    private static final String DATEI_NAME = "timeouts.yml";
    private static final String SCHLUESSEL_SPIELER_NAME = "spieler-name";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";
    private static final String SCHLUESSEL_ABLAUF_AM = "ablauf-am";
    private static final String SCHLUESSEL_SCOPE = "scope";

    private volatile ConcurrentHashMap<StrafSchluessel, TimeoutRecord> timeouts = new ConcurrentHashMap<>();

    private volatile ConcurrentHashMap<String, Object> rohQuarantaene = new ConcurrentHashMap<>();
    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public TimeoutRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(datenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public TimeoutRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
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
                LOG.warn("Timeout-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Timeout-Reload übersprungen.", e);
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
            java.util.concurrent.ConcurrentHashMap<StrafSchluessel, TimeoutRecord> tmp = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.ConcurrentHashMap<String, Object> tmpRoh = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.HashMap<StrafSchluessel, String> quelle = new java.util.HashMap<>();
            for (String schluessel : daten.keySet()) {
                Map<String, Object> abschnitt = Karten.abschnitt(daten, schluessel);
                if (abschnitt == null) {
                    LOG.error("Der Timeout-Eintrag '{}' ist korrupt und wird QUARANTIERT (nicht gelöscht). "
                                    + "Bitte YAML prüfen/fixen – Eintrag wird bei jedem Save unverändert "
                                    + "zurückgeschrieben.",
                            schluessel);
                    try {
                        Object roh = daten.get(schluessel);
                        if (roh != null) {
                            tmpRoh.put(schluessel, roh);
                        }
                    } catch (Exception e) {
                        LOG.debug("Quarantäne-Sicherung für '{}' fehlgeschlagen.", schluessel, e);
                    }
                    continue;
                }
                try {

                    UUID spielerUuid;
                    ServerScope schluesselScope = null;
                    int trenner = schluessel.lastIndexOf('|');
                    if (trenner > 0) {
                        spielerUuid = UUID.fromString(schluessel.substring(0, trenner).trim());
                        schluesselScope = ServerScope.ausSpeicherwert(schluessel.substring(trenner + 1));
                    } else {
                        spielerUuid = UUID.fromString(schluessel.trim());
                    }
                    String spielerName = Karten.text(abschnitt, SCHLUESSEL_SPIELER_NAME, "Unbekannt");
                    String grund = Karten.text(abschnitt, SCHLUESSEL_GRUND, "");
                    String ausfuehrer = Karten.text(abschnitt, SCHLUESSEL_AUSFUEHRER, "Konsole");
                    long erstelltAm = Karten.zahl(abschnitt, SCHLUESSEL_ERSTELLT_AM, 0L);
                    long ablaufAm = Karten.zahl(abschnitt, SCHLUESSEL_ABLAUF_AM, 0L);
                    if (erstelltAm <= 0) {
                        if (ablaufAm > 0 && ablaufAm <= System.currentTimeMillis()) {
                            LOG.info("Abgelaufener Timeout-Eintrag '{}' wird verworfen (kein gültiges erstellt-am).",
                                    schluessel);
                            continue;
                        }
                        LOG.warn("Timeout-Eintrag '{}' hat ungültiges erstellt-am ({}), heile auf jetzt.",
                                schluessel, erstelltAm);
                        erstelltAm = System.currentTimeMillis();
                    }
                    if (ablaufAm <= 0) {
                        LOG.error("Timeout-Eintrag '{}' hat fehlendes/ungültiges ablauf-am und wird QUARANTIERT (nicht geheilt).",
                                schluessel);
                        try {
                            Object roh = daten.get(schluessel);
                            if (roh != null) {
                                tmpRoh.put(schluessel, roh);
                            }
                        } catch (Exception e) {
                            LOG.debug("Quarantäne-Sicherung für '{}' fehlgeschlagen.", schluessel, e);
                        }
                        continue;
                    }
                    String scopeRoh = Karten.hat(abschnitt, SCHLUESSEL_SCOPE)
                            ? Karten.text(abschnitt, SCHLUESSEL_SCOPE, null)
                            : null;
                    ServerScope scope = scopeRoh != null
                            ? ServerScope.ausSpeicherwert(scopeRoh)
                            : (schluesselScope != null ? schluesselScope : ServerScope.unbekannt());
                    if (schluesselScope != null && !schluesselScope.equals(scope)) {
                        LOG.warn("Timeout-Eintrag '{}': Schlüssel-Scope '{}' weicht vom Feld-Scope '{}' ab – "
                                        + "Schlüssel gewinnt.",
                                schluessel, schluesselScope.speicherwert(), scope.speicherwert());
                        scope = schluesselScope;
                    }
                    TimeoutRecord timeout = new TimeoutRecord(spielerUuid, spielerName, grund, ausfuehrer,
                            erstelltAm, ablaufAm, scope);
                    StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
                    String vorherQuelle = quelle.put(key, schluessel);
                    if (tmp.put(key, timeout) != null) {
                        LOG.warn("Doppelter Timeout-Eintrag für {} (Scope '{}') – letzter gewinnt, "
                                        + "der überschriebene Eintrag wird quarantänisiert.",
                                spielerUuid, scope.speicherwert());
                        if (vorherQuelle != null) {
                            Object altRoh = daten.get(vorherQuelle);
                            if (altRoh != null) {
                                tmpRoh.put(vorherQuelle, altRoh);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Der Timeout-Eintrag '{}' ist korrupt und wird QUARANTIERT (nicht gelöscht). "
                                    + "Bitte YAML prüfen/fixen – Eintrag wird bei jedem Save unverändert "
                                    + "zurückgeschrieben.",
                            schluessel, e);
                    try {
                        Object roh = daten.get(schluessel);
                        if (roh != null) {
                            tmpRoh.put(schluessel, roh);
                        }
                    } catch (Exception sichernFehler) {
                        LOG.debug("Quarantäne-Sicherung für '{}' fehlgeschlagen.", schluessel, sichernFehler);
                    }
                }
            }

            if (ladeStatus.fehler()) {
                LOG.error("timeouts.yml konnte nicht gelesen werden ({} Einträge im Memory) – " +
                        "behalte alten Stand (Fail-Closed). Prüfe .corrupt-Backup.", alteGroesse);
            } else {
                timeouts = tmp;
                rohQuarantaene = tmpRoh;
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} korrupte Timeout-Einträge in Quarantäne (bleiben auf Disk erhalten).", tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} Timeouts aus der Datei geladen.", timeouts.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public int entferneAbgelaufene(long jetztMillis) {
        StoreLock.LOCK.writeLock().lock();
        try {
            java.util.Map<StrafSchluessel, TimeoutRecord> abgelaufen = new java.util.HashMap<>();
            timeouts.forEach((key, record) -> {
                if (record.istAbgelaufen(jetztMillis)) {
                    abgelaufen.put(key, record);
                }
            });
            int entfernt = 0;
            for (java.util.Map.Entry<StrafSchluessel, TimeoutRecord> eintrag : abgelaufen.entrySet()) {
                if (timeouts.remove(eintrag.getKey(), eintrag.getValue())) {
                    entfernt++;
                }
            }
            if (entfernt > 0) {
                if (mysql != null) {
                    final java.util.List<StrafSchluessel> keys = java.util.List.copyOf(abgelaufen.keySet());
                    ioExecutor.ausfuehren(() -> loescheBatchNachMysql(keys));
                } else {
                    speichern();
                }
                LOG.info("Es wurden {} abgelaufene Timeouts bereinigt.", entfernt);
            }
            return entfernt;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public boolean timeoutVerhaengenWennFrei(TimeoutRecord timeout) {
        Objects.requireNonNull(timeout, "Der Timeout darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            long jetzt = System.currentTimeMillis();
            for (Map.Entry<StrafSchluessel, TimeoutRecord> eintrag : timeouts.entrySet()) {
                TimeoutRecord bestehend = eintrag.getValue();
                if (bestehend != null && eintrag.getKey().uuid().equals(timeout.spielerUuid())
                        && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(timeout.scope())) {
                    return false;
                }
            }
            timeouts.put(StrafSchluessel.von(timeout.spielerUuid(), timeout.scope()), timeout);
            if (mysql != null) {
                final TimeoutRecord snapshot = timeout;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
            } else {
                speichern();
            }
            return true;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public java.util.Collection<TimeoutRecord> timeoutAlleWennFrei(
            java.util.Collection<TimeoutRecord> neueTimeouts) {
        Objects.requireNonNull(neueTimeouts, "Die Timeout-Liste darf nicht null sein.");
        if (neueTimeouts.isEmpty()) {
            return List.of();
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            List<TimeoutRecord> gesetzt = new ArrayList<>();
            long jetzt = System.currentTimeMillis();
            for (TimeoutRecord timeout : neueTimeouts) {
                Objects.requireNonNull(timeout, "Ein Timeout in der Liste darf nicht null sein.");
                boolean blockiert = false;
                for (Map.Entry<StrafSchluessel, TimeoutRecord> eintrag : timeouts.entrySet()) {
                    TimeoutRecord bestehend = eintrag.getValue();
                    if (bestehend != null && eintrag.getKey().uuid().equals(timeout.spielerUuid())
                            && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(timeout.scope())) {
                        blockiert = true;
                        break;
                    }
                }
                if (!blockiert) {
                    timeouts.put(StrafSchluessel.von(timeout.spielerUuid(), timeout.scope()), timeout);
                    gesetzt.add(timeout);
                }
            }
            if (!gesetzt.isEmpty()) {
                if (mysql != null) {
                    final java.util.List<TimeoutRecord> batch = java.util.List.copyOf(gesetzt);
                    ioExecutor.ausfuehren(() -> upsertBatchNachMysql(batch));
                } else {
                    speichern();
                }
            }
            return List.copyOf(gesetzt);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<TimeoutRecord> timeoutAufheben(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            TimeoutRecord entfernt = timeouts.remove(StrafSchluessel.von(spielerUuid, scope));
            if (entfernt != null) {
                if (mysql != null) {
                    ioExecutor.ausfuehren(() -> loescheZeileNachMysql(spielerUuid, scope));
                } else {
                    speichern();
                }
            }
            return Optional.ofNullable(entfernt);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<TimeoutRecord> timeoutAufhebenWenn(UUID spielerUuid, ServerScope scope,
                                                      java.util.function.Predicate<TimeoutRecord> bedingung) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
            TimeoutRecord bestehend = timeouts.get(key);
            if (bestehend != null && bedingung.test(bestehend) && timeouts.remove(key, bestehend)) {
                if (mysql != null) {
                    ioExecutor.ausfuehren(() -> loescheZeileNachMysql(spielerUuid, scope));
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

    public Optional<TimeoutRecord> finden(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return Optional.ofNullable(timeouts.get(StrafSchluessel.von(spielerUuid, scope)));
    }

    public List<TimeoutRecord> alleFuer(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<TimeoutRecord> treffer = new ArrayList<>();
        for (Map.Entry<StrafSchluessel, TimeoutRecord> eintrag : timeouts.entrySet()) {
            if (eintrag.getKey().uuid().equals(spielerUuid) && eintrag.getValue() != null) {
                treffer.add(eintrag.getValue());
            }
        }
        return List.copyOf(treffer);
    }

    public Optional<TimeoutRecord> findeAbdeckend(UUID spielerUuid, ServerScope ziel, long jetztMillis) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        TimeoutRecord exakt = timeouts.get(StrafSchluessel.von(spielerUuid, ziel));
        if (exakt != null && exakt.istAktiv(jetztMillis)) {
            return Optional.of(exakt);
        }
        TimeoutRecord global = timeouts.get(StrafSchluessel.von(spielerUuid, ServerScope.global()));
        if (global != null && global.istAktiv(jetztMillis) && global.scope().decktAb(ziel)) {
            return Optional.of(global);
        }
        TimeoutRecord unbekannt = timeouts.get(StrafSchluessel.von(spielerUuid, ServerScope.unbekannt()));
        if (unbekannt != null && unbekannt.istAktiv(jetztMillis) && unbekannt.scope().decktAb(ziel)) {
            return Optional.of(unbekannt);
        }
        TimeoutRecord abdeckend = null;
        TimeoutRecord treffer = null;
        for (Map.Entry<StrafSchluessel, TimeoutRecord> eintrag : timeouts.entrySet()) {
            if (!eintrag.getKey().uuid().equals(spielerUuid)) {
                continue;
            }
            TimeoutRecord kandidat = eintrag.getValue();
            if (kandidat == null || !kandidat.istAktiv(jetztMillis)
                    || !kandidat.scope().decktAb(ziel)) {
                continue;
            }
            if (eintrag.getKey().scope().equals(ziel.speicherwert())) {
                treffer = kandidat;
                break;
            }
            if (abdeckend == null) {
                abdeckend = kandidat;
            }
        }
        return Optional.ofNullable(treffer != null ? treffer : abdeckend);
    }

    public boolean istAbgedeckt(UUID spielerUuid, ServerScope ziel, long jetztMillis) {
        return findeAbdeckend(spielerUuid, ziel, jetztMillis).isPresent();
    }

    public Optional<TimeoutRecord> ersetzenWenn(UUID spielerUuid, ServerScope scope,
                                                java.util.function.Predicate<TimeoutRecord> bedingung,
                                                TimeoutRecord neu) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        Objects.requireNonNull(neu, "Der neue Timeout darf nicht null sein.");
        if (!StrafSchluessel.von(spielerUuid, scope).equals(StrafSchluessel.von(neu.spielerUuid(), neu.scope()))) {
            throw new IllegalArgumentException("Scope-Wechsel per Edit ist nicht erlaubt.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
            TimeoutRecord bestehend = timeouts.get(key);
            if (bestehend != null && bedingung.test(bestehend)
                    && timeouts.replace(key, bestehend, neu)) {
                if (mysql != null) {
                    final TimeoutRecord snapshot = neu;
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

    public java.util.Collection<TimeoutRecord> alle() {
        return List.copyOf(timeouts.values());
    }

    private void speichern() {
        java.util.Collection<TimeoutRecord> snapshot = List.copyOf(timeouts.values());
        if (mysql != null) {
            List<TimeoutRecord> kopie = List.copyOf(snapshot);
            ioExecutor.ausfuehren(() -> speichereNachMysql(kopie));
            return;
        }
        java.util.Map<String, Object> rohSnapshot = new java.util.HashMap<>(rohQuarantaene);
        Map<String, Object> root = konfigurationErstellen(snapshot, rohSnapshot);
        ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> yamlSpeicher.speichern(root, datei));
    }

    public int migrationImport(Collection<TimeoutRecord> alle) {
        Objects.requireNonNull(alle, "Die Timeout-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<StrafSchluessel, TimeoutRecord> neu = new ConcurrentHashMap<>();
            for (TimeoutRecord timeout : alle) {
                if (timeout == null) {
                    continue;
                }
                neu.put(StrafSchluessel.von(timeout.spielerUuid(), timeout.scope()), timeout);
            }
            timeouts = neu;
            List<TimeoutRecord> snapshot = List.copyOf(neu.values());
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
            ConcurrentHashMap<StrafSchluessel, TimeoutRecord> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am"
                                 + " FROM " + MysqlSchema.T_TIMEOUTS)) {
                while (ergebnis.next()) {
                    try {
                        TimeoutRecord timeout = zeileLesen(ergebnis);
                        tmp.put(StrafSchluessel.von(timeout.spielerUuid(), timeout.scope()), timeout);
                    } catch (Exception e) {
                        LOG.error("Eine Timeout-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Timeouts konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            timeouts = tmp;
            LOG.info("Es wurden {} Timeouts aus MySQL geladen.", timeouts.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereNachMysql(Collection<TimeoutRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (Statement loeschen = verbindung.createStatement()) {
                loeschen.execute("DELETE FROM " + MysqlSchema.T_TIMEOUTS);
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_TIMEOUTS
                            + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (TimeoutRecord timeout : snapshot) {
                    if (timeout == null) {
                        continue;
                    }
                    einfuegen.setString(1, timeout.spielerUuid().toString());
                    einfuegen.setString(2, timeout.scope().speicherwert());
                    einfuegen.setString(3, timeout.spielerName());
                    einfuegen.setString(4, timeout.grund());
                    einfuegen.setString(5, timeout.ausfuehrer());
                    einfuegen.setLong(6, timeout.erstelltAm());
                    einfuegen.setLong(7, timeout.ablaufAm());
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
            LOG.error("Timeouts konnten nicht nach MySQL geschrieben werden.", e);
            throw new IllegalStateException("Timeouts konnten nicht nach MySQL geschrieben werden.", e);
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


    void upsertZeileNachMysql(TimeoutRecord eintrag) {
        java.util.Objects.requireNonNull(eintrag, "Der Timeout darf nicht null sein.");
        try (java.sql.Connection verbindung = mysql.verbindung();
                java.sql.PreparedStatement upsert = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_TIMEOUTS
                                + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE spieler_name = VALUES(spieler_name),"
                                + " grund = VALUES(grund), ausfuehrer = VALUES(ausfuehrer),"
                                + " erstellt_am = VALUES(erstellt_am), ablauf_am = VALUES(ablauf_am)")) {
            upsert.setString(1, eintrag.spielerUuid().toString());
            upsert.setString(2, eintrag.scope().speicherwert());
            upsert.setString(3, eintrag.spielerName());
            upsert.setString(4, eintrag.grund());
            upsert.setString(5, eintrag.ausfuehrer());
            upsert.setLong(6, eintrag.erstelltAm());
            upsert.setLong(7, eintrag.ablaufAm());
            upsert.executeUpdate();
        } catch (Exception e) {
            LOG.error("Timeout-Zeile für {} konnte nicht nach MySQL geschrieben werden.",
                    eintrag.spielerUuid(), e);
            throw new IllegalStateException("Timeout-Zeile konnte nicht nach MySQL geschrieben werden.", e);
        }
    }

    void loescheZeileNachMysql(java.util.UUID spielerUuid, ServerScope scope) {
        java.util.Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        java.util.Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        try (java.sql.Connection verbindung = mysql.verbindung();
                java.sql.PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_TIMEOUTS + " WHERE uuid = ? AND scope = ?")) {
            loeschen.setString(1, spielerUuid.toString());
            loeschen.setString(2, scope.speicherwert());
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.error("Timeout-Zeile für {} konnte nicht aus MySQL gelöscht werden.", spielerUuid, e);
            throw new IllegalStateException("Timeout-Zeile konnte nicht aus MySQL gelöscht werden.", e);
        }
    }

    void loescheBatchNachMysql(java.util.Collection<StrafSchluessel> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        java.sql.Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (java.sql.PreparedStatement loeschen = verbindung.prepareStatement(
                    "DELETE FROM " + MysqlSchema.T_TIMEOUTS + " WHERE uuid = ? AND scope = ?")) {
                for (StrafSchluessel key : keys) {
                    if (key == null) {
                        continue;
                    }
                    loeschen.setString(1, key.uuid().toString());
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
            LOG.error("Timeout-Batch-Löschung ({} Zeilen) fehlgeschlagen.", keys.size(), e);
            throw new IllegalStateException("Timeout-Batch-Löschung fehlgeschlagen.", e);
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

    void upsertBatchNachMysql(java.util.Collection<TimeoutRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        java.sql.Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (java.sql.PreparedStatement upsert = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_TIMEOUTS
                            + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE spieler_name = VALUES(spieler_name),"
                            + " grund = VALUES(grund), ausfuehrer = VALUES(ausfuehrer),"
                            + " erstellt_am = VALUES(erstellt_am), ablauf_am = VALUES(ablauf_am)")) {
                for (TimeoutRecord eintrag : records) {
                    if (eintrag == null) {
                        continue;
                    }
                    upsert.setString(1, eintrag.spielerUuid().toString());
                    upsert.setString(2, eintrag.scope().speicherwert());
                    upsert.setString(3, eintrag.spielerName());
                    upsert.setString(4, eintrag.grund());
                    upsert.setString(5, eintrag.ausfuehrer());
                    upsert.setLong(6, eintrag.erstelltAm());
                    upsert.setLong(7, eintrag.ablaufAm());
                    upsert.addBatch();
                }
                upsert.executeBatch();
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
            LOG.error("Timeout-Batch-Upsert ({} Zeilen) fehlgeschlagen.", records.size(), e);
            throw new IllegalStateException("Timeout-Batch-Upsert fehlgeschlagen.", e);
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

    private static TimeoutRecord zeileLesen(ResultSet ergebnis) throws Exception {
        return new TimeoutRecord(UUID.fromString(ergebnis.getString("uuid")),
                ergebnis.getString("spieler_name"),
                ergebnis.getString("grund"),
                ergebnis.getString("ausfuehrer"),
                ergebnis.getLong("erstellt_am"),
                ergebnis.getLong("ablauf_am"),
                ServerScope.ausSpeicherwert(ergebnis.getString("scope")));
    }

    private Map<String, Object> konfigurationErstellen(java.util.Collection<TimeoutRecord> eintraege,
                                                      java.util.Map<String, Object> rohQuarantaene) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (TimeoutRecord timeout : eintraege) {
            String pfad = timeout.spielerUuid() + "|" + timeout.scope().speicherwert();
            Map<String, Object> abschnitt = new LinkedHashMap<>();
            abschnitt.put(SCHLUESSEL_SPIELER_NAME, timeout.spielerName());
            abschnitt.put(SCHLUESSEL_GRUND, timeout.grund());
            abschnitt.put(SCHLUESSEL_AUSFUEHRER, timeout.ausfuehrer());
            abschnitt.put(SCHLUESSEL_ERSTELLT_AM, timeout.erstelltAm());
            abschnitt.put(SCHLUESSEL_ABLAUF_AM, timeout.ablaufAm());
            abschnitt.put(SCHLUESSEL_SCOPE, timeout.scope().speicherwert());
            root.put(pfad, abschnitt);
        }
        if (rohQuarantaene != null) {
            for (java.util.Map.Entry<String, Object> roh : rohQuarantaene.entrySet()) {
                if (roh.getKey() == null) {
                    continue;
                }
                try {
                    String ziel = roh.getKey();
                    if (root.containsKey(ziel)) {
                        ziel = "quarantaene|" + roh.getKey();
                        int nummer = 2;
                        while (root.containsKey(ziel)) {
                            ziel = "quarantaene" + nummer++ + "|" + roh.getKey();
                        }
                    }
                    root.put(ziel, roh.getValue());
                } catch (Exception e) {
                    LOG.warn("Quarantäne-Eintrag '{}' konnte nicht zurückgeschrieben werden.", roh.getKey(), e);
                }
            }
        }
        return root;
    }
}
