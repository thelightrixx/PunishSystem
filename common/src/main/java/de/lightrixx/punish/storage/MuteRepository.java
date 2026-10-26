package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.StrafSchluessel;
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

public final class MuteRepository {

    private static final Logger LOG = LoggerFactory.getLogger(MuteRepository.class);
    private static final String DATEI_NAME = "mutes.yml";
    private static final String SCHLUESSEL_SPIELER_NAME = "spieler-name";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";
    private static final String SCHLUESSEL_ABLAUF_AM = "ablauf-am";
    private static final String SCHLUESSEL_SCOPE = "scope";

    private volatile ConcurrentHashMap<StrafSchluessel, MuteRecord> mutes = new ConcurrentHashMap<>();

    private volatile ConcurrentHashMap<String, Object> rohQuarantaene = new ConcurrentHashMap<>();
    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public MuteRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(datenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public MuteRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
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
                LOG.warn("Mute-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Mute-Reload übersprungen.", e);
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
            java.util.concurrent.ConcurrentHashMap<StrafSchluessel, MuteRecord> tmp = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.ConcurrentHashMap<String, Object> tmpRoh = new java.util.concurrent.ConcurrentHashMap<>();
            for (String schluessel : daten.keySet()) {
                Map<String, Object> abschnitt = Karten.abschnitt(daten, schluessel);
                if (abschnitt == null) {
                    LOG.error("Der Mute-Eintrag '{}' ist korrupt und wird QUARANTIERT (nicht gelöscht). "
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
                            LOG.info("Abgelaufener Mute-Eintrag '{}' wird verworfen (kein gültiges erstellt-am).",
                                    schluessel);
                            continue;
                        }
                        LOG.warn("Mute-Eintrag '{}' hat ungültiges erstellt-am ({}), heile auf jetzt.",
                                schluessel, erstelltAm);
                        erstelltAm = System.currentTimeMillis();
                    }
                    if (ablaufAm <= 0) {
                        LOG.error("Mute-Eintrag '{}' hat fehlendes/ungültiges ablauf-am und wird QUARANTIERT (nicht geheilt).",
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
                        LOG.warn("Mute-Eintrag '{}': Schlüssel-Scope '{}' weicht vom Feld-Scope '{}' ab – "
                                        + "Schlüssel gewinnt.",
                                schluessel, schluesselScope.speicherwert(), scope.speicherwert());
                        scope = schluesselScope;
                    }
                    MuteRecord mute = new MuteRecord(spielerUuid, spielerName, grund, ausfuehrer,
                            erstelltAm, ablaufAm, scope);
                    StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
                    if (tmp.put(key, mute) != null) {
                        LOG.warn("Doppelter Mute-Eintrag für {} (Scope '{}') – letzter gewinnt.",
                                spielerUuid, scope.speicherwert());
                    }
                } catch (Exception e) {
                    LOG.error("Der Mute-Eintrag '{}' ist korrupt und wird QUARANTIERT (nicht gelöscht). "
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
                LOG.error("mutes.yml konnte nicht gelesen werden ({} Einträge im Memory) – " +
                        "behalte alten Stand (Fail-Closed). Prüfe .corrupt-Backup.", alteGroesse);
            } else {
                mutes = tmp;
                rohQuarantaene = tmpRoh;
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} korrupte Mute-Einträge in Quarantäne (bleiben auf Disk erhalten).", tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} Mutes aus der Datei geladen.", mutes.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public int entferneAbgelaufene(long jetztMillis) {
        StoreLock.LOCK.writeLock().lock();
        try {
            java.util.Map<StrafSchluessel, MuteRecord> abgelaufen = new java.util.HashMap<>();
            mutes.forEach((key, record) -> {
                if (record.istAbgelaufen(jetztMillis)) {
                    abgelaufen.put(key, record);
                }
            });
            int entfernt = 0;
            for (java.util.Map.Entry<StrafSchluessel, MuteRecord> eintrag : abgelaufen.entrySet()) {
                if (mutes.remove(eintrag.getKey(), eintrag.getValue())) {
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
                LOG.info("Es wurden {} abgelaufene Mutes bereinigt.", entfernt);
            }
            return entfernt;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public boolean mutenWennFrei(MuteRecord mute) {
        Objects.requireNonNull(mute, "Der Mute darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            long jetzt = System.currentTimeMillis();
            for (Map.Entry<StrafSchluessel, MuteRecord> eintrag : mutes.entrySet()) {
                MuteRecord bestehend = eintrag.getValue();
                if (bestehend != null && eintrag.getKey().uuid().equals(mute.spielerUuid())
                        && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(mute.scope())) {
                    return false;
                }
            }
            mutes.put(StrafSchluessel.von(mute.spielerUuid(), mute.scope()), mute);
            if (mysql != null) {
                final MuteRecord snapshot = mute;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
            } else {
                speichern();
            }
            return true;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public java.util.Collection<MuteRecord> mutenAlleWennFrei(java.util.Collection<MuteRecord> neueMutes) {
        Objects.requireNonNull(neueMutes, "Die Mute-Liste darf nicht null sein.");
        if (neueMutes.isEmpty()) {
            return List.of();
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            List<MuteRecord> gesetzt = new ArrayList<>();
            long jetzt = System.currentTimeMillis();
            for (MuteRecord mute : neueMutes) {
                Objects.requireNonNull(mute, "Ein Mute in der Liste darf nicht null sein.");
                boolean blockiert = false;
                for (Map.Entry<StrafSchluessel, MuteRecord> eintrag : mutes.entrySet()) {
                    MuteRecord bestehend = eintrag.getValue();
                    if (bestehend != null && eintrag.getKey().uuid().equals(mute.spielerUuid())
                            && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(mute.scope())) {
                        blockiert = true;
                        break;
                    }
                }
                if (!blockiert) {
                    mutes.put(StrafSchluessel.von(mute.spielerUuid(), mute.scope()), mute);
                    gesetzt.add(mute);
                }
            }
            if (!gesetzt.isEmpty()) {
                if (mysql != null) {
                    final java.util.List<MuteRecord> batch = java.util.List.copyOf(gesetzt);
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

    public Optional<MuteRecord> entmuten(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            MuteRecord entfernt = mutes.remove(StrafSchluessel.von(spielerUuid, scope));
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

    public Optional<MuteRecord> entmutenWenn(UUID spielerUuid, ServerScope scope,
                                             java.util.function.Predicate<MuteRecord> bedingung) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
            MuteRecord bestehend = mutes.get(key);
            if (bestehend != null && bedingung.test(bestehend) && mutes.remove(key, bestehend)) {
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

    public Optional<MuteRecord> finden(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return Optional.ofNullable(mutes.get(StrafSchluessel.von(spielerUuid, scope)));
    }

    public List<MuteRecord> alleFuer(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<MuteRecord> treffer = new ArrayList<>();
        for (Map.Entry<StrafSchluessel, MuteRecord> eintrag : mutes.entrySet()) {
            if (eintrag.getKey().uuid().equals(spielerUuid) && eintrag.getValue() != null) {
                treffer.add(eintrag.getValue());
            }
        }
        return List.copyOf(treffer);
    }

    public Optional<MuteRecord> findeAbdeckend(UUID spielerUuid, ServerScope ziel, long jetztMillis) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        MuteRecord exakt = mutes.get(StrafSchluessel.von(spielerUuid, ziel));
        if (exakt != null && exakt.istAktiv(jetztMillis)) {
            return Optional.of(exakt);
        }
        MuteRecord global = mutes.get(StrafSchluessel.von(spielerUuid, ServerScope.global()));
        if (global != null && global.istAktiv(jetztMillis) && global.scope().decktAb(ziel)) {
            return Optional.of(global);
        }
        MuteRecord unbekannt = mutes.get(StrafSchluessel.von(spielerUuid, ServerScope.unbekannt()));
        if (unbekannt != null && unbekannt.istAktiv(jetztMillis) && unbekannt.scope().decktAb(ziel)) {
            return Optional.of(unbekannt);
        }
        MuteRecord abdeckend = null;
        MuteRecord treffer = null;
        for (Map.Entry<StrafSchluessel, MuteRecord> eintrag : mutes.entrySet()) {
            if (!eintrag.getKey().uuid().equals(spielerUuid)) {
                continue;
            }
            MuteRecord kandidat = eintrag.getValue();
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

    public Optional<MuteRecord> ersetzenWenn(UUID spielerUuid, ServerScope scope,
                                             java.util.function.Predicate<MuteRecord> bedingung,
                                             MuteRecord neu) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        Objects.requireNonNull(neu, "Der neue Mute darf nicht null sein.");
        if (!StrafSchluessel.von(spielerUuid, scope).equals(StrafSchluessel.von(neu.spielerUuid(), neu.scope()))) {
            throw new IllegalArgumentException("Scope-Wechsel per Edit ist nicht erlaubt.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
            MuteRecord bestehend = mutes.get(key);
            if (bestehend != null && bedingung.test(bestehend)
                    && mutes.replace(key, bestehend, neu)) {
                if (mysql != null) {
                    final MuteRecord snapshot = neu;
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

    public java.util.Collection<MuteRecord> alle() {
        return List.copyOf(mutes.values());
    }

    private void speichern() {
        java.util.Collection<MuteRecord> snapshot = List.copyOf(mutes.values());
        if (mysql != null) {
            List<MuteRecord> kopie = List.copyOf(snapshot);
            ioExecutor.ausfuehren(() -> speichereNachMysql(kopie));
            return;
        }
        java.util.Map<String, Object> rohSnapshot = new java.util.HashMap<>(rohQuarantaene);
        Map<String, Object> root = konfigurationErstellen(snapshot, rohSnapshot);
        ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> yamlSpeicher.speichern(root, datei));
    }

    public int migrationImport(Collection<MuteRecord> alle) {
        Objects.requireNonNull(alle, "Die Mute-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<StrafSchluessel, MuteRecord> neu = new ConcurrentHashMap<>();
            for (MuteRecord mute : alle) {
                if (mute == null) {
                    continue;
                }
                neu.put(StrafSchluessel.von(mute.spielerUuid(), mute.scope()), mute);
            }
            mutes = neu;
            List<MuteRecord> snapshot = List.copyOf(neu.values());
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
            ConcurrentHashMap<StrafSchluessel, MuteRecord> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am"
                                 + " FROM " + MysqlSchema.T_MUTES)) {
                while (ergebnis.next()) {
                    try {
                        MuteRecord mute = zeileLesen(ergebnis);
                        tmp.put(StrafSchluessel.von(mute.spielerUuid(), mute.scope()), mute);
                    } catch (Exception e) {
                        LOG.error("Eine Mute-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Mutes konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            mutes = tmp;
            LOG.info("Es wurden {} Mutes aus MySQL geladen.", mutes.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereNachMysql(Collection<MuteRecord> snapshot) {
        speichereBatchNachMysql(snapshot);
    }

    void speichereBatchNachMysql(Collection<MuteRecord> snapshot) {
        speichereVollNachMysql(snapshot);
    }

    void upsertZeileNachMysql(MuteRecord mute) {
        java.util.Objects.requireNonNull(mute, "Der Mute darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement upsert = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_MUTES
                                + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE spieler_name = VALUES(spieler_name),"
                                + " grund = VALUES(grund), ausfuehrer = VALUES(ausfuehrer),"
                                + " erstellt_am = VALUES(erstellt_am), ablauf_am = VALUES(ablauf_am)")) {
            upsert.setString(1, mute.spielerUuid().toString());
            upsert.setString(2, mute.scope().speicherwert());
            upsert.setString(3, mute.spielerName());
            upsert.setString(4, mute.grund());
            upsert.setString(5, mute.ausfuehrer());
            upsert.setLong(6, mute.erstelltAm());
            upsert.setLong(7, mute.ablaufAm());
            upsert.executeUpdate();
        } catch (Exception e) {
            LOG.error("Mute-Zeile für {} konnte nicht nach MySQL geschrieben werden.",
                    mute.spielerUuid(), e);
            throw new IllegalStateException("Mute-Zeile konnte nicht nach MySQL geschrieben werden.", e);
        }
    }

    void loescheZeileNachMysql(java.util.UUID spielerUuid, ServerScope scope) {
        java.util.Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        java.util.Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_MUTES + " WHERE uuid = ? AND scope = ?")) {
            loeschen.setString(1, spielerUuid.toString());
            loeschen.setString(2, scope.speicherwert());
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.error("Mute-Zeile für {} konnte nicht aus MySQL gelöscht werden.", spielerUuid, e);
            throw new IllegalStateException("Mute-Zeile konnte nicht aus MySQL gelöscht werden.", e);
        }
    }

    void loescheBatchNachMysql(java.util.Collection<StrafSchluessel> keys) {
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
                    "DELETE FROM " + MysqlSchema.T_MUTES + " WHERE uuid = ? AND scope = ?")) {
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
            LOG.error("Mute-Batch-Löschung ({} Zeilen) fehlgeschlagen.", keys.size(), e);
            throw new IllegalStateException("Mute-Batch-Löschung fehlgeschlagen.", e);
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

    void upsertBatchNachMysql(java.util.Collection<MuteRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement upsert = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_MUTES
                            + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE spieler_name = VALUES(spieler_name),"
                            + " grund = VALUES(grund), ausfuehrer = VALUES(ausfuehrer),"
                            + " erstellt_am = VALUES(erstellt_am), ablauf_am = VALUES(ablauf_am)")) {
                for (MuteRecord mute : records) {
                    if (mute == null) {
                        continue;
                    }
                    upsert.setString(1, mute.spielerUuid().toString());
                    upsert.setString(2, mute.scope().speicherwert());
                    upsert.setString(3, mute.spielerName());
                    upsert.setString(4, mute.grund());
                    upsert.setString(5, mute.ausfuehrer());
                    upsert.setLong(6, mute.erstelltAm());
                    upsert.setLong(7, mute.ablaufAm());
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
            LOG.error("Mute-Batch-Upsert ({} Zeilen) fehlgeschlagen.", records.size(), e);
            throw new IllegalStateException("Mute-Batch-Upsert fehlgeschlagen.", e);
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

    void speichereVollNachMysql(Collection<MuteRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (Statement loeschen = verbindung.createStatement()) {
                loeschen.execute("DELETE FROM " + MysqlSchema.T_MUTES);
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_MUTES
                            + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (MuteRecord mute : snapshot) {
                    if (mute == null) {
                        continue;
                    }
                    einfuegen.setString(1, mute.spielerUuid().toString());
                    einfuegen.setString(2, mute.scope().speicherwert());
                    einfuegen.setString(3, mute.spielerName());
                    einfuegen.setString(4, mute.grund());
                    einfuegen.setString(5, mute.ausfuehrer());
                    einfuegen.setLong(6, mute.erstelltAm());
                    einfuegen.setLong(7, mute.ablaufAm());
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
            LOG.error("Mutes konnten nicht nach MySQL geschrieben werden.", e);
            throw new IllegalStateException("Mutes konnten nicht nach MySQL geschrieben werden.", e);
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

    private static MuteRecord zeileLesen(ResultSet ergebnis) throws Exception {
        return new MuteRecord(UUID.fromString(ergebnis.getString("uuid")),
                ergebnis.getString("spieler_name"),
                ergebnis.getString("grund"),
                ergebnis.getString("ausfuehrer"),
                ergebnis.getLong("erstellt_am"),
                ergebnis.getLong("ablauf_am"),
                ServerScope.ausSpeicherwert(ergebnis.getString("scope")));
    }

    private Map<String, Object> konfigurationErstellen(java.util.Collection<MuteRecord> eintraege,
                                                      java.util.Map<String, Object> rohQuarantaene) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (MuteRecord mute : eintraege) {
            String pfad = mute.spielerUuid() + "|" + mute.scope().speicherwert();
            Map<String, Object> abschnitt = new LinkedHashMap<>();
            abschnitt.put(SCHLUESSEL_SPIELER_NAME, mute.spielerName());
            abschnitt.put(SCHLUESSEL_GRUND, mute.grund());
            abschnitt.put(SCHLUESSEL_AUSFUEHRER, mute.ausfuehrer());
            abschnitt.put(SCHLUESSEL_ERSTELLT_AM, mute.erstelltAm());
            abschnitt.put(SCHLUESSEL_ABLAUF_AM, mute.ablaufAm());
            abschnitt.put(SCHLUESSEL_SCOPE, mute.scope().speicherwert());
            root.put(pfad, abschnitt);
        }
        if (rohQuarantaene != null) {
            for (java.util.Map.Entry<String, Object> roh : rohQuarantaene.entrySet()) {
                if (roh.getKey() == null || root.containsKey(roh.getKey())) {
                    continue;
                }
                try {
                    root.put(roh.getKey(), roh.getValue());
                } catch (Exception e) {
                    LOG.warn("Quarantäne-Eintrag '{}' konnte nicht zurückgeschrieben werden.", roh.getKey(), e);
                }
            }
        }
        return root;
    }
}
