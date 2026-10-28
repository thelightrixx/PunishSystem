package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.BanRecord;
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
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class BanRepository {

    private static final Logger LOG = LoggerFactory.getLogger(BanRepository.class);
    private static final String DATEI_NAME = "bans.yml";
    private static final String SCHLUESSEL_SPIELER_NAME = "spieler-name";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";
    private static final String SCHLUESSEL_ABLAUF_AM = "ablauf-am";
    private static final String SCHLUESSEL_AUTO_BANN = "auto-bann";
    private static final String SCHLUESSEL_SCOPE = "scope";

    private volatile ConcurrentHashMap<StrafSchluessel, BanRecord> banns = new ConcurrentHashMap<>();

    private volatile ConcurrentHashMap<String, Object> rohQuarantaene = new ConcurrentHashMap<>();
    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public BanRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(datenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public BanRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
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
                LOG.warn("Ban-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Ban-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = banns.size();
            YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
            Map<String, Object> daten = ladeStatus.daten();
        java.util.concurrent.ConcurrentHashMap<StrafSchluessel, BanRecord> tmp = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, Object> tmpRoh = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.HashMap<StrafSchluessel, String> quelle = new java.util.HashMap<>();
        for (String schluessel : daten.keySet()) {
            Map<String, Object> abschnitt = Karten.abschnitt(daten, schluessel);
            if (abschnitt == null) {
                LOG.error("Der Bann-Eintrag '{}' ist korrupt und wird QUARANTIERT (nicht gelöscht). "
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
                    spielerUuid = UUID.fromString(schluessel.substring(0, trenner));
                    schluesselScope = ServerScope.ausSpeicherwert(schluessel.substring(trenner + 1));
                } else {
                    spielerUuid = UUID.fromString(schluessel);
                }
                String spielerName = Karten.text(abschnitt, SCHLUESSEL_SPIELER_NAME, "Unbekannt");
                String grund = Karten.text(abschnitt, SCHLUESSEL_GRUND, "");
                String ausfuehrer = Karten.text(abschnitt, SCHLUESSEL_AUSFUEHRER, "Konsole");
                long erstelltAm = Karten.zahl(abschnitt, SCHLUESSEL_ERSTELLT_AM, 0L);
                Long ablaufAm = Karten.zahlOderNull(abschnitt, SCHLUESSEL_ABLAUF_AM);
                boolean autoBann = Karten.wahr(abschnitt, SCHLUESSEL_AUTO_BANN, false);
                String scopeRoh = Karten.hat(abschnitt, SCHLUESSEL_SCOPE)
                        ? Karten.text(abschnitt, SCHLUESSEL_SCOPE, null)
                        : null;
                ServerScope scope = scopeRoh != null
                        ? ServerScope.ausSpeicherwert(scopeRoh)
                        : (schluesselScope != null ? schluesselScope : ServerScope.unbekannt());
                if (schluesselScope != null && !schluesselScope.equals(scope)) {
                    LOG.warn("Bann-Eintrag '{}': Schlüssel-Scope '{}' weicht vom Feld-Scope '{}' ab – "
                                    + "Schlüssel gewinnt (Eintrag wird beim nächsten Save geheilt).",
                            schluessel, schluesselScope.speicherwert(), scope.speicherwert());
                    scope = schluesselScope;
                }
                BanRecord bann = new BanRecord(
                        spielerUuid,
                        spielerName,
                        grund,
                        ausfuehrer,
                        erstelltAm,
                        ablaufAm,
                        autoBann,
                        scope);
                StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
                String vorherQuelle = quelle.put(key, schluessel);
                if (tmp.put(key, bann) != null) {
                    LOG.warn("Doppelter Bann-Eintrag für {} (Scope '{}') – letzter gewinnt, "
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
                LOG.error("Der Bann-Eintrag '{}' ist korrupt und wird QUARANTIERT (nicht gelöscht). "
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
        }            if (ladeStatus.fehler()) {
                LOG.error("bans.yml konnte nicht gelesen werden ({} Einträge im Memory) – " +
                        "behalte alten Stand (Fail-Closed). Prüfe .corrupt-Backup.", alteGroesse);
            } else {
            banns = tmp;
            rohQuarantaene = tmpRoh;
            if (!tmpRoh.isEmpty()) {
                LOG.warn("{} korrupte Bann-Einträge in Quarantäne (bleiben auf Disk erhalten).", tmpRoh.size());
            }
        }
        LOG.info("Es wurden {} Banns aus der Datei geladen.", banns.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public int entferneAbgelaufene(long jetztMillis) {
        StoreLock.LOCK.writeLock().lock();
        try {
            java.util.Map<StrafSchluessel, BanRecord> abgelaufen = new java.util.HashMap<>();
            banns.forEach((key, record) -> {
                if (record.istAbgelaufen(jetztMillis)) {
                    abgelaufen.put(key, record);
                }
            });
            int entfernt = 0;
            for (java.util.Map.Entry<StrafSchluessel, BanRecord> eintrag : abgelaufen.entrySet()) {

                if (banns.remove(eintrag.getKey(), eintrag.getValue())) {
                    entfernt++;
                }
            }
            if (entfernt > 0) {
                if (mysql != null) {
                    final List<StrafSchluessel> keys = List.copyOf(abgelaufen.keySet());
                    ioExecutor.ausfuehren(() -> loescheBatchNachMysql(keys));
                } else {
                    speichern();
                }
                LOG.info("Es wurden {} abgelaufene Banns bereinigt.", entfernt);
            }
            return entfernt;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public boolean bannenWennFrei(BanRecord bann) {
        Objects.requireNonNull(bann, "Der Bann darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            long jetzt = System.currentTimeMillis();
            for (Map.Entry<StrafSchluessel, BanRecord> eintrag : banns.entrySet()) {
                BanRecord bestehend = eintrag.getValue();
                if (bestehend != null && eintrag.getKey().uuid().equals(bann.spielerUuid())
                        && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(bann.scope())) {
                    return false;
                }
            }
            banns.put(StrafSchluessel.von(bann.spielerUuid(), bann.scope()), bann);
            if (mysql != null) {
                final BanRecord snapshot = bann;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
            } else {
                speichern();
            }
            return true;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Collection<BanRecord> bannenAlleWennFrei(Collection<BanRecord> neueBanns) {
        Objects.requireNonNull(neueBanns, "Die Bann-Liste darf nicht null sein.");
        if (neueBanns.isEmpty()) {
            return List.of();
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            List<BanRecord> gesetzt = new ArrayList<>();
            long jetzt = System.currentTimeMillis();
            for (BanRecord bann : neueBanns) {
                Objects.requireNonNull(bann, "Ein Bann in der Liste darf nicht null sein.");
                boolean blockiert = false;
                for (Map.Entry<StrafSchluessel, BanRecord> eintrag : banns.entrySet()) {
                    BanRecord bestehend = eintrag.getValue();
                    if (bestehend != null && eintrag.getKey().uuid().equals(bann.spielerUuid())
                            && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(bann.scope())) {
                        blockiert = true;
                        break;
                    }
                }
                if (!blockiert) {
                    banns.put(StrafSchluessel.von(bann.spielerUuid(), bann.scope()), bann);
                    gesetzt.add(bann);
                }
            }
            if (!gesetzt.isEmpty()) {
                if (mysql != null) {
                    final List<BanRecord> batch = List.copyOf(gesetzt);
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

    public Optional<BanRecord> entbannen(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            BanRecord entfernt = banns.remove(StrafSchluessel.von(spielerUuid, scope));
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

    public Optional<BanRecord> entbannenWenn(UUID spielerUuid, ServerScope scope, Predicate<BanRecord> bedingung) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
            BanRecord bestehend = banns.get(key);
            if (bestehend != null && bedingung.test(bestehend)
                    && banns.remove(key, bestehend)) {
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

    public Optional<BanRecord> ersetzenWenn(UUID spielerUuid, ServerScope scope,
                                           Predicate<BanRecord> bedingung, BanRecord neu) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        Objects.requireNonNull(neu, "Der neue Bann darf nicht null sein.");
        if (!spielerUuid.equals(neu.spielerUuid())) {
            throw new IllegalArgumentException("Der neue Bann passt nicht zum Schlüssel " + spielerUuid);
        }
        if (!StrafSchluessel.von(spielerUuid, scope).equals(StrafSchluessel.von(neu.spielerUuid(), neu.scope()))) {
            throw new IllegalArgumentException("Scope-Wechsel per Edit ist nicht erlaubt "
                    + "(Pardon + Neu-Bann nutzen).");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            StrafSchluessel key = StrafSchluessel.von(spielerUuid, scope);
            BanRecord bestehend = banns.get(key);
            if (bestehend != null && bedingung.test(bestehend)
                    && banns.replace(key, bestehend, neu)) {
                if (mysql != null) {
                    final BanRecord snapshot = neu;
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

    public Collection<BanRecord> alleEntbannen() {
        return alleEntbannenNachScope(null);
    }

    public Collection<BanRecord> alleEntbannenNachScope(ServerScope filter) {
        StoreLock.LOCK.writeLock().lock();
        try {
            if (banns.isEmpty()) {
                return List.of();
            }

            java.util.Map<StrafSchluessel, BanRecord> snap = new java.util.HashMap<>(banns);
            List<BanRecord> entfernt = new ArrayList<>(snap.size());
            for (java.util.Map.Entry<StrafSchluessel, BanRecord> eintrag : snap.entrySet()) {
                BanRecord record = eintrag.getValue();
                if (filter != null && (record == null || !record.scope().equals(filter))) {
                    continue;
                }
                if (banns.remove(eintrag.getKey(), eintrag.getValue())) {
                    entfernt.add(eintrag.getValue());
                }
            }
            if (!entfernt.isEmpty()) {
                if (mysql != null) {
                    final List<StrafSchluessel> keys = new ArrayList<>();
                    for (BanRecord record : entfernt) {
                        if (record != null) {
                            keys.add(StrafSchluessel.von(record.spielerUuid(), record.scope()));
                        }
                    }
                    ioExecutor.ausfuehren(() -> loescheBatchNachMysql(keys));
                } else {
                    speichern();
                }
            }
            return List.copyOf(entfernt);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<BanRecord> finden(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return Optional.ofNullable(banns.get(StrafSchluessel.von(spielerUuid, scope)));
    }

    public List<BanRecord> alleFuer(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<BanRecord> treffer = new ArrayList<>();
        for (Map.Entry<StrafSchluessel, BanRecord> eintrag : banns.entrySet()) {
            if (eintrag.getKey().uuid().equals(spielerUuid) && eintrag.getValue() != null) {
                treffer.add(eintrag.getValue());
            }
        }
        return List.copyOf(treffer);
    }

    public Optional<BanRecord> findeAbdeckend(UUID spielerUuid, ServerScope ziel, long jetztMillis) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        BanRecord exakt = banns.get(StrafSchluessel.von(spielerUuid, ziel));
        if (exakt != null && exakt.istAktiv(jetztMillis)) {
            return Optional.of(exakt);
        }
        BanRecord global = banns.get(StrafSchluessel.von(spielerUuid, ServerScope.global()));
        if (global != null && global.istAktiv(jetztMillis) && global.scope().decktAb(ziel)) {
            return Optional.of(global);
        }
        BanRecord unbekannt = banns.get(StrafSchluessel.von(spielerUuid, ServerScope.unbekannt()));
        if (unbekannt != null && unbekannt.istAktiv(jetztMillis) && unbekannt.scope().decktAb(ziel)) {
            return Optional.of(unbekannt);
        }
        BanRecord abdeckend = null;
        BanRecord treffer = null;
        for (Map.Entry<StrafSchluessel, BanRecord> eintrag : banns.entrySet()) {
            if (!eintrag.getKey().uuid().equals(spielerUuid)) {
                continue;
            }
            BanRecord kandidat = eintrag.getValue();
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

    public Collection<BanRecord> alle() {
        return List.copyOf(banns.values());
    }

    private void speichern() {
        Collection<BanRecord> snapshot = List.copyOf(banns.values());
        if (mysql != null) {
            List<BanRecord> kopie = List.copyOf(snapshot);
            ioExecutor.ausfuehren(() -> speichereNachMysql(kopie));
            return;
        }
        java.util.Map<String, Object> rohSnapshot = new java.util.HashMap<>(rohQuarantaene);
        Map<String, Object> root = konfigurationErstellen(snapshot, rohSnapshot);
        ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> yamlSpeicher.speichern(root, datei));
    }

    public int migrationImport(Collection<BanRecord> alle) {
        Objects.requireNonNull(alle, "Die Bann-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<StrafSchluessel, BanRecord> neu = new ConcurrentHashMap<>();
            for (BanRecord bann : alle) {
                if (bann == null) {
                    continue;
                }
                neu.put(StrafSchluessel.von(bann.spielerUuid(), bann.scope()), bann);
            }
            banns = neu;
            List<BanRecord> snapshot = List.copyOf(neu.values());
            ioExecutor.ausfuehren(() -> speichereNachMysql(snapshot));
            return neu.size();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = banns.size();
            ConcurrentHashMap<StrafSchluessel, BanRecord> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am, auto_bann"
                                 + " FROM " + MysqlSchema.T_BANS)) {
                while (ergebnis.next()) {
                    try {
                        BanRecord bann = zeileLesen(ergebnis);
                        tmp.put(StrafSchluessel.von(bann.spielerUuid(), bann.scope()), bann);
                    } catch (Exception e) {
                        LOG.error("Eine Bann-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Banns konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            banns = tmp;
            LOG.info("Es wurden {} Banns aus MySQL geladen.", banns.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereNachMysql(Collection<BanRecord> snapshot) {
        speichereBatchNachMysql(snapshot);
    }

    void speichereBatchNachMysql(Collection<BanRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (Statement loeschen = verbindung.createStatement()) {
                loeschen.execute("DELETE FROM " + MysqlSchema.T_BANS);
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_BANS
                            + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am, auto_bann)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (BanRecord bann : snapshot) {
                    if (bann == null) {
                        continue;
                    }
                    einfuegen.setString(1, bann.spielerUuid().toString());
                    einfuegen.setString(2, bann.scope().speicherwert());
                    einfuegen.setString(3, bann.spielerName());
                    einfuegen.setString(4, bann.grund());
                    einfuegen.setString(5, bann.ausfuehrer());
                    einfuegen.setLong(6, bann.erstelltAm());
                    if (bann.ablaufAm() == null) {
                        einfuegen.setNull(7, Types.BIGINT);
                    } else {
                        einfuegen.setLong(7, bann.ablaufAm());
                    }
                    einfuegen.setBoolean(8, bann.autoBann());
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
            LOG.error("Banns konnten nicht nach MySQL geschrieben werden.", e);
            throw new IllegalStateException("Banns konnten nicht nach MySQL geschrieben werden.", e);
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

    void upsertZeileNachMysql(BanRecord bann) {
        Objects.requireNonNull(bann, "Der Bann darf nicht null sein.");
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement upsert = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_BANS
                            + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am, auto_bann)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE spieler_name = VALUES(spieler_name),"
                            + " grund = VALUES(grund), ausfuehrer = VALUES(ausfuehrer),"
                            + " erstellt_am = VALUES(erstellt_am), ablauf_am = VALUES(ablauf_am),"
                            + " auto_bann = VALUES(auto_bann)")) {
                upsert.setString(1, bann.spielerUuid().toString());
                upsert.setString(2, bann.scope().speicherwert());
                upsert.setString(3, bann.spielerName());
                upsert.setString(4, bann.grund());
                upsert.setString(5, bann.ausfuehrer());
                upsert.setLong(6, bann.erstelltAm());
                if (bann.ablaufAm() == null) {
                    upsert.setNull(7, Types.BIGINT);
                } else {
                    upsert.setLong(7, bann.ablaufAm());
                }
                upsert.setBoolean(8, bann.autoBann());
                upsert.executeUpdate();
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
            LOG.error("Bann-Zeile für {} konnte nicht nach MySQL geschrieben werden.",
                    bann.spielerUuid(), e);
            throw new IllegalStateException("Bann-Zeile konnte nicht nach MySQL geschrieben werden.", e);
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

    void loescheZeileNachMysql(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_BANS + " WHERE uuid = ? AND scope = ?")) {
            loeschen.setString(1, spielerUuid.toString());
            loeschen.setString(2, scope.speicherwert());
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.error("Bann-Zeile für {} konnte nicht aus MySQL gelöscht werden.", spielerUuid, e);
            throw new IllegalStateException("Bann-Zeile konnte nicht aus MySQL gelöscht werden.", e);
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
                    "DELETE FROM " + MysqlSchema.T_BANS + " WHERE uuid = ? AND scope = ?")) {
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
            LOG.error("Bann-Batch-Löschung ({} Zeilen) fehlgeschlagen.", keys.size(), e);
            throw new IllegalStateException("Bann-Batch-Löschung fehlgeschlagen.", e);
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

    void upsertBatchNachMysql(java.util.Collection<BanRecord> records) {
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
                    "INSERT INTO " + MysqlSchema.T_BANS
                            + " (uuid, scope, spieler_name, grund, ausfuehrer, erstellt_am, ablauf_am, auto_bann)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE spieler_name = VALUES(spieler_name),"
                            + " grund = VALUES(grund), ausfuehrer = VALUES(ausfuehrer),"
                            + " erstellt_am = VALUES(erstellt_am), ablauf_am = VALUES(ablauf_am),"
                            + " auto_bann = VALUES(auto_bann)")) {
                for (BanRecord bann : records) {
                    if (bann == null) {
                        continue;
                    }
                    upsert.setString(1, bann.spielerUuid().toString());
                    upsert.setString(2, bann.scope().speicherwert());
                    upsert.setString(3, bann.spielerName());
                    upsert.setString(4, bann.grund());
                    upsert.setString(5, bann.ausfuehrer());
                    upsert.setLong(6, bann.erstelltAm());
                    if (bann.ablaufAm() == null) {
                        upsert.setNull(7, Types.BIGINT);
                    } else {
                        upsert.setLong(7, bann.ablaufAm());
                    }
                    upsert.setBoolean(8, bann.autoBann());
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
            LOG.error("Bann-Batch-Upsert ({} Zeilen) fehlgeschlagen.", records.size(), e);
            throw new IllegalStateException("Bann-Batch-Upsert fehlgeschlagen.", e);
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

    private static BanRecord zeileLesen(ResultSet ergebnis) throws Exception {        UUID uuid = UUID.fromString(ergebnis.getString("uuid"));
        ServerScope scope = ServerScope.ausSpeicherwert(ergebnis.getString("scope"));
        Object ablaufRoh = ergebnis.getObject("ablauf_am");
        Long ablauf = ablaufRoh instanceof Number zahl ? zahl.longValue() : null;
        return new BanRecord(uuid,
                ergebnis.getString("spieler_name"),
                ergebnis.getString("grund"),
                ergebnis.getString("ausfuehrer"),
                ergebnis.getLong("erstellt_am"),
                ablauf,
                ergebnis.getBoolean("auto_bann"),
                scope);
    }

    private Map<String, Object> konfigurationErstellen(Collection<BanRecord> eintraege,
                                                      java.util.Map<String, Object> rohQuarantaene) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (BanRecord bann : eintraege) {
            String pfad = bann.spielerUuid() + "|" + bann.scope().speicherwert();
            Map<String, Object> abschnitt = new LinkedHashMap<>();
            abschnitt.put(SCHLUESSEL_SPIELER_NAME, bann.spielerName());
            abschnitt.put(SCHLUESSEL_GRUND, bann.grund());
            abschnitt.put(SCHLUESSEL_AUSFUEHRER, bann.ausfuehrer());
            abschnitt.put(SCHLUESSEL_ERSTELLT_AM, bann.erstelltAm());
            if (bann.ablaufAm() != null) {
                abschnitt.put(SCHLUESSEL_ABLAUF_AM, bann.ablaufAm());
            }
            abschnitt.put(SCHLUESSEL_AUTO_BANN, bann.autoBann());
            abschnitt.put(SCHLUESSEL_SCOPE, bann.scope().speicherwert());
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
