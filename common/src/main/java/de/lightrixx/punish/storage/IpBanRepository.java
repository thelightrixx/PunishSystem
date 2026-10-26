package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.IpBanRecord;
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
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class IpBanRepository {

    private static final Logger LOG = LoggerFactory.getLogger(IpBanRepository.class);
    private static final String DATEI_NAME = "ipbans.yml";
    private static final String SCHLUESSEL_LISTE = "ip-banns";
    private static final String SCHLUESSEL_IP = "ip";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";
    private static final String SCHLUESSEL_ABLAUF_AM = "ablauf-am";
    private static final String SCHLUESSEL_SCOPE = "scope";

    private static Map<String, Object> rohKarte(IpBanRecord bann) {
        Map<String, Object> eintrag = new java.util.LinkedHashMap<>();
        eintrag.put(SCHLUESSEL_IP, bann.ip());
        eintrag.put(SCHLUESSEL_GRUND, bann.grund());
        eintrag.put(SCHLUESSEL_AUSFUEHRER, bann.ausfuehrer());
        eintrag.put(SCHLUESSEL_ERSTELLT_AM, bann.erstelltAm());
        if (bann.ablaufAm() != null) {
            eintrag.put(SCHLUESSEL_ABLAUF_AM, bann.ablaufAm());
        }
        eintrag.put(SCHLUESSEL_SCOPE, bann.scope().speicherwert());
        return eintrag;
    }

    private volatile ConcurrentHashMap<IpStrafSchluessel, IpBanRecord> ipBanns = new ConcurrentHashMap<>();

    private volatile java.util.List<Object> rohQuarantaeneListe = java.util.List.of();
    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public IpBanRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(datenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public IpBanRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
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
                LOG.warn("IP-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor IP-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = ipBanns.size();
            YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
            Map<String, Object> daten = ladeStatus.daten();
            ConcurrentHashMap<IpStrafSchluessel, IpBanRecord> tmp = new ConcurrentHashMap<>();
            java.util.List<Object> tmpRoh = new java.util.ArrayList<>();
            java.util.List<?> rohListe = Karten.liste(daten, SCHLUESSEL_LISTE);
            if (rohListe == null) {
                rohListe = java.util.List.of();
            }
            for (Object rohObj : rohListe) {
                Map<String, Object> eintrag = Karten.abschnitt(rohObj);
                if (eintrag == null) {
                    LOG.error("Ein IP-Bann-Eintrag ist korrupt (kein Map) und wird QUARANTIERT (nicht gelöscht). "
                                    + "Bitte YAML prüfen/fixen – Eintrag wird bei jedem Save unverändert "
                                    + "zurückgeschrieben.");
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
                    Long ablaufAm = Karten.zahlOderNull(eintrag, SCHLUESSEL_ABLAUF_AM);
                    String scopeRoh = eintrag.get(SCHLUESSEL_SCOPE) instanceof String wert ? wert : null;
                    ServerScope scope = ServerScope.ausSpeicherwert(scopeRoh);
                    IpBanRecord bann = new IpBanRecord(ip, grund, ausfuehrer, erstelltAm, ablaufAm, scope);
                    IpStrafSchluessel key = IpStrafSchluessel.von(bann.ip(), scope);
                    IpBanRecord vorher = tmp.put(key, bann);
                    if (vorher != null) {
                        LOG.warn("Doppelter IP-Bann-Eintrag für {} (Scope '{}') – letzter gewinnt, "
                                        + "der überschriebene Eintrag wird quarantänisiert.",
                                bann.ip(), scope.speicherwert());
                        tmpRoh.add(rohKarte(vorher));
                    }
                } catch (Exception e) {
                    LOG.error("Ein IP-Bann-Eintrag ist korrupt und wird QUARANTIERT (nicht gelöscht). "
                                    + "Bitte YAML prüfen/fixen – Eintrag wird bei jedem Save unverändert "
                                    + "zurückgeschrieben.", e);
                    tmpRoh.add(rohObj);
                }
            }

            if (ladeStatus.fehler()) {
                LOG.error("ipbans.yml konnte nicht gelesen werden ({} Einträge im Memory) – " +
                        "behalte alten Stand (Fail-Closed). Prüfe .corrupt-Backup.", alteGroesse);
            } else {
                ipBanns = tmp;
                rohQuarantaeneListe = java.util.List.copyOf(tmpRoh);
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} korrupte IP-Bann-Einträge in Quarantäne (bleiben auf Disk erhalten).", tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} IP-Banns aus der Datei geladen.", ipBanns.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public boolean bannenWennFrei(IpBanRecord bann) {
        Objects.requireNonNull(bann, "Der IP-Bann darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            long jetzt = System.currentTimeMillis();
            for (java.util.Map.Entry<IpStrafSchluessel, IpBanRecord> eintrag : ipBanns.entrySet()) {
                IpBanRecord bestehend = eintrag.getValue();
                if (bestehend != null && eintrag.getKey().ip().equals(bann.ip())
                        && bestehend.istAktiv(jetzt) && bestehend.scope().decktAb(bann.scope())) {
                    return false;
                }
            }
            ipBanns.put(IpStrafSchluessel.von(bann.ip(), bann.scope()), bann);
            if (mysql != null) {
                final IpBanRecord snapshot = bann;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
            } else {
                speichern();
            }
            return true;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public boolean bannenWennExaktFrei(IpBanRecord bann) {
        Objects.requireNonNull(bann, "Der IP-Bann darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            IpStrafSchluessel schluessel = IpStrafSchluessel.von(bann.ip(), bann.scope());
            IpBanRecord bestehend = ipBanns.get(schluessel);
            if (bestehend != null && bestehend.istAktiv(System.currentTimeMillis())) {
                return false;
            }
            ipBanns.put(schluessel, bann);
            if (mysql != null) {
                final IpBanRecord snapshot = bann;
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
            java.util.Map<IpStrafSchluessel, IpBanRecord> abgelaufen = new java.util.HashMap<>();
            ipBanns.forEach((key, record) -> {
                if (record.istAbgelaufen(jetztMillis)) {
                    abgelaufen.put(key, record);
                }
            });
            int entfernt = 0;
            for (java.util.Map.Entry<IpStrafSchluessel, IpBanRecord> eintrag : abgelaufen.entrySet()) {
                if (ipBanns.remove(eintrag.getKey(), eintrag.getValue())) {
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
                LOG.info("Es wurden {} abgelaufene IP-Banns bereinigt.", entfernt);
            }
            return entfernt;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public void bannen(IpBanRecord bann) {
        Objects.requireNonNull(bann, "Der IP-Bann darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            ipBanns.put(IpStrafSchluessel.von(bann.ip(), bann.scope()), bann);
            if (mysql != null) {
                final IpBanRecord snapshot = bann;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(snapshot));
            } else {
                speichern();
            }
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<IpBanRecord> entbannen(String ip, ServerScope scope) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            IpBanRecord entfernt = ipBanns.remove(IpStrafSchluessel.von(normalisiert, scope));
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

    public Optional<IpBanRecord> entbannenWenn(String ip, ServerScope scope,
                                               java.util.function.Predicate<IpBanRecord> bedingung) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            IpStrafSchluessel key = IpStrafSchluessel.von(normalisiert, scope);
            IpBanRecord bestehend = ipBanns.get(key);
            if (bestehend != null && bedingung.test(bestehend) && ipBanns.remove(key, bestehend)) {
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

    public Optional<IpBanRecord> ersetzenWenn(String ip, ServerScope scope,
                                              java.util.function.Predicate<IpBanRecord> bedingung,
                                              IpBanRecord neu) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Objects.requireNonNull(bedingung, "Die Bedingung darf nicht null sein.");
        Objects.requireNonNull(neu, "Der neue IP-Bann darf nicht null sein.");
        if (!normalisiert.equals(neu.ip())) {
            throw new IllegalArgumentException("Der neue IP-Bann passt nicht zum Schlüssel " + normalisiert);
        }
        if (!IpStrafSchluessel.von(normalisiert, scope)
                .equals(IpStrafSchluessel.von(neu.ip(), neu.scope()))) {
            throw new IllegalArgumentException("Scope-Wechsel per Edit ist nicht erlaubt.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            IpStrafSchluessel key = IpStrafSchluessel.von(normalisiert, scope);
            IpBanRecord bestehend = ipBanns.get(key);
            if (bestehend != null && bedingung.test(bestehend)
                    && ipBanns.replace(key, bestehend, neu)) {
                if (mysql != null) {
                    final IpBanRecord snapshot = neu;
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

    public java.util.Collection<IpBanRecord> alle() {
        return List.copyOf(ipBanns.values());
    }

    public List<IpBanRecord> alleFuer(String ip) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return List.of();
        }
        List<IpBanRecord> treffer = new ArrayList<>();
        for (java.util.Map.Entry<IpStrafSchluessel, IpBanRecord> eintrag : ipBanns.entrySet()) {
            if (eintrag.getKey().ip().equals(normalisiert) && eintrag.getValue() != null) {
                treffer.add(eintrag.getValue());
            }
        }
        return List.copyOf(treffer);
    }

    public Optional<IpBanRecord> finden(String ip, ServerScope scope) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return Optional.ofNullable(ipBanns.get(IpStrafSchluessel.von(normalisiert, scope)));
    }

    public Optional<IpBanRecord> findeAbdeckend(String ip, ServerScope ziel, long jetztMillis) {
        String normalisiert = normalisieren(ip);
        if (normalisiert == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        IpBanRecord exakt = null;
        IpBanRecord abdeckend = null;
        for (java.util.Map.Entry<IpStrafSchluessel, IpBanRecord> eintrag : ipBanns.entrySet()) {
            if (!eintrag.getKey().ip().equals(normalisiert)) {
                continue;
            }
            IpBanRecord kandidat = eintrag.getValue();
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
        java.util.Collection<IpBanRecord> snapshot = List.copyOf(ipBanns.values());
        if (mysql != null) {
            List<IpBanRecord> kopie = List.copyOf(snapshot);
            ioExecutor.ausfuehren(() -> speichereNachMysql(kopie));
            return;
        }
        java.util.List<Object> rohSnapshot = java.util.List.copyOf(rohQuarantaeneListe);
        Map<String, Object> root = konfigurationErstellen(snapshot, rohSnapshot);
        ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> yamlSpeicher.speichern(root, datei));
    }

    public int migrationImport(Collection<IpBanRecord> alle) {
        Objects.requireNonNull(alle, "Die IP-Bann-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<IpStrafSchluessel, IpBanRecord> neu = new ConcurrentHashMap<>();
            for (IpBanRecord bann : alle) {
                if (bann == null) {
                    continue;
                }
                neu.put(IpStrafSchluessel.von(bann.ip(), bann.scope()), bann);
            }
            ipBanns = neu;
            List<IpBanRecord> snapshot = List.copyOf(neu.values());
            ioExecutor.ausfuehren(() -> speichereNachMysql(snapshot));
            return neu.size();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = ipBanns.size();
            ConcurrentHashMap<IpStrafSchluessel, IpBanRecord> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am"
                                 + " FROM " + MysqlSchema.T_IP_BANS)) {
                while (ergebnis.next()) {
                    try {
                        IpBanRecord bann = zeileLesen(ergebnis);
                        tmp.put(IpStrafSchluessel.von(bann.ip(), bann.scope()), bann);
                    } catch (Exception e) {
                        LOG.error("Eine IP-Bann-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("IP-Banns konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            ipBanns = tmp;
            LOG.info("Es wurden {} IP-Banns aus MySQL geladen.", ipBanns.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereNachMysql(Collection<IpBanRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (Statement loeschen = verbindung.createStatement()) {
                loeschen.execute("DELETE FROM " + MysqlSchema.T_IP_BANS);
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_IP_BANS
                            + " (ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                for (IpBanRecord bann : snapshot) {
                    if (bann == null) {
                        continue;
                    }
                    einfuegen.setString(1, bann.ip());
                    einfuegen.setString(2, bann.scope().speicherwert());
                    einfuegen.setString(3, bann.grund());
                    einfuegen.setString(4, bann.ausfuehrer());
                    einfuegen.setLong(5, bann.erstelltAm());
                    if (bann.ablaufAm() == null) {
                        einfuegen.setNull(6, Types.BIGINT);
                    } else {
                        einfuegen.setLong(6, bann.ablaufAm());
                    }
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
            LOG.error("IP-Banns konnten nicht nach MySQL geschrieben werden.", e);
            throw new IllegalStateException("IP-Banns konnten nicht nach MySQL geschrieben werden.", e);
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

    void upsertZeileNachMysql(IpBanRecord bann) {
        java.util.Objects.requireNonNull(bann, "Der IP-Bann darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement upsert = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_IP_BANS
                                + " (ip, scope, grund, ausfuehrer, erstellt_am, ablauf_am)"
                                + " VALUES (?, ?, ?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE grund = VALUES(grund),"
                                + " ausfuehrer = VALUES(ausfuehrer), erstellt_am = VALUES(erstellt_am),"
                                + " ablauf_am = VALUES(ablauf_am)")) {
            upsert.setString(1, bann.ip());
            upsert.setString(2, bann.scope().speicherwert());
            upsert.setString(3, bann.grund());
            upsert.setString(4, bann.ausfuehrer());
            upsert.setLong(5, bann.erstelltAm());
            if (bann.ablaufAm() == null) {
                upsert.setNull(6, Types.BIGINT);
            } else {
                upsert.setLong(6, bann.ablaufAm());
            }
            upsert.executeUpdate();
        } catch (Exception e) {
            LOG.error("IP-Bann-Zeile für {} konnte nicht nach MySQL geschrieben werden.", bann.ip(), e);
            throw new IllegalStateException("IP-Bann-Zeile konnte nicht nach MySQL geschrieben werden.", e);
        }
    }

    void loescheZeileNachMysql(String ip, ServerScope scope) {
        if (ip == null) {
            return;
        }
        java.util.Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_IP_BANS + " WHERE ip = ? AND scope = ?")) {
            loeschen.setString(1, ip);
            loeschen.setString(2, scope.speicherwert());
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.error("IP-Bann-Zeile für {} konnte nicht aus MySQL gelöscht werden.", ip, e);
            throw new IllegalStateException("IP-Bann-Zeile konnte nicht aus MySQL gelöscht werden.", e);
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
                    "DELETE FROM " + MysqlSchema.T_IP_BANS + " WHERE ip = ? AND scope = ?")) {
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
            LOG.error("IP-Bann-Batch-Löschung ({} Zeilen) fehlgeschlagen.", keys.size(), e);
            throw new IllegalStateException("IP-Bann-Batch-Löschung fehlgeschlagen.", e);
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

    private static IpBanRecord zeileLesen(ResultSet ergebnis) throws Exception {
        Object ablaufRoh = ergebnis.getObject("ablauf_am");
        Long ablauf = ablaufRoh instanceof Number zahl ? zahl.longValue() : null;
        return new IpBanRecord(ergebnis.getString("ip"),
                ergebnis.getString("grund"),
                ergebnis.getString("ausfuehrer"),
                ergebnis.getLong("erstellt_am"),
                ablauf,
                ServerScope.ausSpeicherwert(ergebnis.getString("scope")));
    }

    private Map<String, Object> konfigurationErstellen(java.util.Collection<IpBanRecord> eintraege,
                                                      java.util.List<?> rohQuarantaeneListe) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> liste = new ArrayList<>();
        for (IpBanRecord bann : eintraege) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put(SCHLUESSEL_IP, bann.ip());
            eintrag.put(SCHLUESSEL_GRUND, bann.grund());
            eintrag.put(SCHLUESSEL_AUSFUEHRER, bann.ausfuehrer());
            eintrag.put(SCHLUESSEL_ERSTELLT_AM, bann.erstelltAm());
            if (bann.ablaufAm() != null) {
                eintrag.put(SCHLUESSEL_ABLAUF_AM, bann.ablaufAm());
            }
            eintrag.put(SCHLUESSEL_SCOPE, bann.scope().speicherwert());
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
