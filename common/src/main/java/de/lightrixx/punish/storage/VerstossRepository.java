package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class VerstossRepository {

    private static final Logger LOG = LoggerFactory.getLogger(VerstossRepository.class);
    private static final String UNTERORDNER = "verstoesse";
    private static final String DATEI_ENDUNG = ".yml";
    private static final String SCHLUESSEL_LISTE = "verstoesse";
    private static final String SCHLUESSEL_NUMMER = "nummer";
    private static final String SCHLUESSEL_SPIELER_NAME = "spieler-name";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";
    private static final String SCHLUESSEL_STUFE = "stufe";

    private volatile ConcurrentHashMap<UUID, List<VerstossRecord>> verstoesse = new ConcurrentHashMap<>();

    private volatile ConcurrentHashMap<UUID, List<Object>> rohQuarantaene = new ConcurrentHashMap<>();
    private final File verstoesseOrdner;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public VerstossRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.verstoesseOrdner = new File(datenOrdner, UNTERORDNER);
        this.mysql = null;
    }

    public VerstossRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
        this.mysql = Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.verstoesseOrdner = null;
        this.yamlSpeicher = null;
    }

    public boolean istMysql() {
        return mysql != null;
    }

    public void laden() {
        try {
            boolean idle = ioExecutor.warteAufIdle(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!idle) {
                LOG.warn("Verstoss-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Verstoss-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = verstoesse.values().stream().filter(Objects::nonNull).mapToInt(liste -> Objects.requireNonNull(liste).size()).sum();
            File[] dateien = verstoesseOrdner.listFiles((verzeichnis, name) -> name != null && name.endsWith(DATEI_ENDUNG));
            if (dateien == null) {
                LOG.info("Es wurden keine Verstoß-Dateien gefunden.");
                return;
            }
            ConcurrentHashMap<UUID, List<VerstossRecord>> tmp = new ConcurrentHashMap<>();
            ConcurrentHashMap<UUID, List<Object>> tmpRoh = new ConcurrentHashMap<>();
            int gesamtAnzahl = 0;
            boolean ladeFehler = false;
            for (File datei : dateien) {
                try {
                    String dateiName = datei.getName();
                    String uuidText = dateiName.substring(0, dateiName.length() - DATEI_ENDUNG.length());
                    UUID spielerUuid = UUID.fromString(uuidText);
                    java.util.List<Object> quarListe = new java.util.ArrayList<>();
                    List<VerstossRecord> liste = ladenDatei(datei, quarListe);
                    if (liste == null) {
                        ladeFehler = true;
                        continue;
                    }
                    if (!quarListe.isEmpty()) {
                        tmpRoh.put(spielerUuid, java.util.List.copyOf(quarListe));
                    }
                    if (!liste.isEmpty()) {
                        tmp.put(spielerUuid, List.copyOf(liste));
                        gesamtAnzahl += liste.size();
                    }
                } catch (Exception e) {
                    ladeFehler = true;
                    LOG.warn("Die Verstoß-Datei {} konnte nicht geladen werden.",
                            datei.getName(), e);
                }
            }
            if (ladeFehler) {
                LOG.error("Verstöße konnten nicht vollständig gelesen werden ({} im Memory, "
                        + "{} Dateien) – behalte alten Stand.", alteGroesse, dateien.length);
            } else {
                verstoesse = tmp;
                rohQuarantaene = tmpRoh;
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} Spieler mit korrupten Verstoß-Einträgen in Quarantäne (bleiben auf Disk erhalten).",
                            tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} Verstöße für {} Spieler aus den Dateien geladen.",
                    gesamtAnzahl, verstoesse.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public VerstossRecord hinzufuegen(UUID spielerUuid, String spielerName, String grund, String ausfuehrer) {
        return hinzufuegen(spielerUuid, spielerName, grund, ausfuehrer, VerstossStufe.standard());
    }

    public VerstossRecord hinzufuegen(UUID spielerUuid, String spielerName, String grund, String ausfuehrer,
                                      VerstossStufe stufe) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(spielerName, "Der Spielername darf nicht null sein.");
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        VerstossStufe effektiveStufe = stufe == null ? VerstossStufe.standard() : stufe;
        StoreLock.LOCK.writeLock().lock();
        try {
            List<VerstossRecord> neueListe = verstoesse.compute(spielerUuid, (uuid, alte) -> {
                List<VerstossRecord> basis = (alte == null) ? List.of() : alte;
                int neueNummer = basis.stream()
                        .filter(eintrag -> eintrag != null)
                        .mapToInt(eintrag -> Objects.requireNonNull(eintrag).nummer()).max().orElse(0) + 1;
                VerstossRecord neu = new VerstossRecord(neueNummer, spielerName, grund, ausfuehrer,
                        System.currentTimeMillis(), effektiveStufe);
                List<VerstossRecord> erweitert = new ArrayList<>(basis);
                erweitert.add(neu);
                return List.copyOf(erweitert);
            });
            VerstossRecord neuRecord = Objects.requireNonNull(neueListe.getLast());
            if (mysql != null) {
                final VerstossRecord snapshot = neuRecord;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(spielerUuid, snapshot));
            } else {
                persistiere(spielerUuid);
            }
            return neuRecord;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<VerstossRecord> entfernen(UUID spielerUuid, int nummer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            AtomicReference<VerstossRecord> entfernt = new AtomicReference<>();
            verstoesse.computeIfPresent(spielerUuid, (uuid, alte) -> {
                List<VerstossRecord> verbleibend = new ArrayList<>();
                for (VerstossRecord verstoss : alte) {
                    if (verstoss == null) {
                        continue;
                    }
                    if (verstoss.nummer() == nummer) {
                        entfernt.set(verstoss);
                    } else {
                        verbleibend.add(verstoss);
                    }
                }
                return verbleibend.isEmpty() ? null : List.copyOf(verbleibend);
            });
            VerstossRecord entfernter = entfernt.get();
            if (entfernter != null) {
                if (mysql != null) {
                    ioExecutor.ausfuehren(() -> loescheZeileNachMysql(spielerUuid, nummer));
                } else {
                    persistiere(spielerUuid);
                }
            }
            return Optional.ofNullable(entfernter);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<VerstossRecord> bearbeiten(UUID spielerUuid, int nummer, String neuerGrund) {
        return bearbeiten(spielerUuid, nummer, neuerGrund, null);
    }

    public Optional<VerstossRecord> bearbeiten(UUID spielerUuid, int nummer, String neuerGrund,
                                              VerstossStufe neueStufe) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(neuerGrund, "Der neue Grund darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            AtomicReference<VerstossRecord> bearbeitet = new AtomicReference<>();
            verstoesse.computeIfPresent(spielerUuid, (uuid, alte) -> {
                List<VerstossRecord> neuListe = new ArrayList<>();
                for (VerstossRecord verstoss : alte) {
                    if (verstoss == null) {
                        continue;
                    }
                    if (verstoss.nummer() == nummer) {
                        VerstossRecord aktualisiert = verstoss.mitGrund(neuerGrund);
                        if (neueStufe != null) {
                            aktualisiert = aktualisiert.mitStufe(neueStufe);
                        }
                        bearbeitet.set(aktualisiert);
                        neuListe.add(aktualisiert);
                    } else {
                        neuListe.add(verstoss);
                    }
                }
                return List.copyOf(neuListe);
            });
            VerstossRecord bearbeiteter = bearbeitet.get();
            if (bearbeiteter != null) {
                if (mysql != null) {
                    final VerstossRecord snapshot = bearbeiteter;
                    ioExecutor.ausfuehren(() -> upsertZeileNachMysql(spielerUuid, snapshot));
                } else {
                    persistiere(spielerUuid);
                }
            }
            return Optional.ofNullable(bearbeiteter);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public List<VerstossRecord> zuruecksetzen(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            List<VerstossRecord> entfernt = verstoesse.remove(spielerUuid);
            if (entfernt != null && !entfernt.isEmpty()) {
                persistiere(spielerUuid);
            }
            return entfernt == null ? List.of() : List.copyOf(entfernt);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public List<VerstossRecord> zuruecksetzenNur(UUID spielerUuid, java.util.Set<Integer> nummern) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        if (nummern == null || nummern.isEmpty()) {
            return List.of();
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            List<VerstossRecord> alte = verstoesse.get(spielerUuid);
            if (alte == null || alte.isEmpty()) {
                return List.of();
            }
            List<VerstossRecord> entfernt = new ArrayList<>();
            List<VerstossRecord> verbleibend = new ArrayList<>();
            for (VerstossRecord record : alte) {
                if (record != null && nummern.contains(record.nummer())) {
                    entfernt.add(record);
                } else if (record != null) {
                    verbleibend.add(record);
                }
            }
            if (entfernt.isEmpty()) {
                return List.of();
            }
            if (verbleibend.isEmpty()) {
                verstoesse.remove(spielerUuid);
            } else {
                verstoesse.put(spielerUuid, List.copyOf(verbleibend));
            }
            persistiere(spielerUuid);
            return List.copyOf(entfernt);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public List<VerstossRecord> entferneVerfallene(UUID spielerUuid,
                                                  java.util.function.Function<VerstossRecord, Long> dauerMillisProVerstoss,
                                                  long jetztMillis) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(dauerMillisProVerstoss, "Die Dauern-Funktion darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            List<VerstossRecord> alte = verstoesse.get(spielerUuid);
            if (alte == null || alte.isEmpty()) {
                return List.of();
            }
            List<VerstossRecord> verfallen = new ArrayList<>();
            List<VerstossRecord> verbleibend = new ArrayList<>();
            for (VerstossRecord verstoss : alte) {
                if (verstoss == null) {
                    continue;
                }
                if (istVerfallen(verstoss, dauerMillisProVerstoss, jetztMillis)) {
                    verfallen.add(verstoss);
                } else {
                    verbleibend.add(verstoss);
                }
            }
            if (verfallen.isEmpty()) {
                return List.of();
            }
            if (verbleibend.isEmpty()) {
                verstoesse.remove(spielerUuid);
            } else {
                verstoesse.put(spielerUuid, List.copyOf(verbleibend));
            }
            persistiere(spielerUuid);
            return List.copyOf(verfallen);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private static boolean istVerfallen(VerstossRecord verstoss,
                                        java.util.function.Function<VerstossRecord, Long> dauerMillisProVerstoss,
                                        long jetztMillis) {
        long dauer;
        try {
            Long roh = dauerMillisProVerstoss.apply(verstoss);
            if (roh == null) {
                return false;
            }
            dauer = roh;
        } catch (Exception e) {
            LOG.warn("Verfallsdauer für Verstoß #{} konnte nicht bestimmt werden, behalte ihn.",
                    verstoss.nummer(), e);
            return false;
        }
        if (dauer <= 0) {
            return false;
        }
        long ablauf;
        try {
            ablauf = Math.addExact(verstoss.erstelltAm(), dauer);
        } catch (ArithmeticException e) {
            LOG.warn("Verfallszeitpunkt für Verstoß #{} läuft über, behalte ihn.",
                    verstoss.nummer(), e);
            return false;
        }
        return ablauf <= jetztMillis;
    }

    public List<VerstossRecord> finden(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<VerstossRecord> liste = verstoesse.get(spielerUuid);
        return liste == null ? List.of() : List.copyOf(liste);
    }

    public int anzahl(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<VerstossRecord> liste = verstoesse.get(spielerUuid);
        return liste == null ? 0 : liste.size();
    }

    public java.util.Map<UUID, List<VerstossRecord>> alle() {
        java.util.Map<UUID, List<VerstossRecord>> kopie = new java.util.HashMap<>();
        verstoesse.forEach((uuid, liste) -> {
            if (uuid != null && liste != null && !liste.isEmpty()) {
                kopie.put(uuid, List.copyOf(liste));
            }
        });
        return java.util.Map.copyOf(kopie);
    }

    public int migrationImport(java.util.Map<UUID, List<VerstossRecord>> alle) {
        Objects.requireNonNull(alle, "Die Verstoß-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<UUID, List<VerstossRecord>> neu = new ConcurrentHashMap<>();
            int anzahl = 0;
            for (java.util.Map.Entry<UUID, List<VerstossRecord>> eintrag : alle.entrySet()) {
                if (eintrag.getKey() == null || eintrag.getValue() == null) {
                    continue;
                }
                List<VerstossRecord> kopie = List.copyOf(eintrag.getValue());
                if (!kopie.isEmpty()) {
                    neu.put(eintrag.getKey(), kopie);
                    anzahl += kopie.size();
                }
            }
            verstoesse = neu;
            for (java.util.Map.Entry<UUID, List<VerstossRecord>> eintrag : neu.entrySet()) {
                final UUID uuid = eintrag.getKey();
                final List<VerstossRecord> snapshot = eintrag.getValue();
                ioExecutor.ausfuehren(() -> speichereSpielerNachMysql(uuid, snapshot));
            }
            return anzahl;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = verstoesse.values().stream().filter(Objects::nonNull).mapToInt(List::size).sum();
            ConcurrentHashMap<UUID, List<VerstossRecord>> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT uuid, nummer, spieler_name, grund, ausfuehrer, erstellt_am, stufe"
                                 + " FROM " + MysqlSchema.T_VERSTOESSE + " ORDER BY uuid, nummer")) {
                while (ergebnis.next()) {
                    try {
                        UUID uuid = UUID.fromString(ergebnis.getString("uuid"));
                        VerstossRecord verstoss = new VerstossRecord(ergebnis.getInt("nummer"),
                                ergebnis.getString("spieler_name"),
                                ergebnis.getString("grund"),
                                ergebnis.getString("ausfuehrer"),
                                ergebnis.getLong("erstellt_am"),
                                VerstossStufe.parseOderStandard(ergebnis.getString("stufe")));
                        tmp.computeIfAbsent(uuid, k -> new ArrayList<>()).add(verstoss);
                    } catch (Exception e) {
                        LOG.error("Eine Verstoß-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Verstöße konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            ConcurrentHashMap<UUID, List<VerstossRecord>> fertig = new ConcurrentHashMap<>();
            int gesamt = 0;
            for (java.util.Map.Entry<UUID, List<VerstossRecord>> eintrag : tmp.entrySet()) {
                if (!eintrag.getValue().isEmpty()) {
                    fertig.put(eintrag.getKey(), List.copyOf(eintrag.getValue()));
                    gesamt += eintrag.getValue().size();
                }
            }
            verstoesse = fertig;
            LOG.info("Es wurden {} Verstöße für {} Spieler aus MySQL geladen.", gesamt, verstoesse.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    void upsertZeileNachMysql(UUID spielerUuid, VerstossRecord verstoss) {
        java.util.Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        java.util.Objects.requireNonNull(verstoss, "Der Verstoß darf nicht null sein.");
        java.sql.Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (java.sql.PreparedStatement upsert = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_VERSTOESSE
                            + " (uuid, nummer, spieler_name, grund, ausfuehrer, erstellt_am, stufe)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE spieler_name = VALUES(spieler_name),"
                            + " grund = VALUES(grund), ausfuehrer = VALUES(ausfuehrer),"
                            + " erstellt_am = VALUES(erstellt_am), stufe = VALUES(stufe)")) {
                upsert.setString(1, spielerUuid.toString());
                upsert.setInt(2, verstoss.nummer());
                upsert.setString(3, verstoss.spielerName());
                upsert.setString(4, verstoss.grund());
                upsert.setString(5, verstoss.ausfuehrer());
                upsert.setLong(6, verstoss.erstelltAm());
                upsert.setString(7, verstoss.stufe().schluessel());
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
            LOG.error("Verstoß-Zeile für {} konnte nicht nach MySQL geschrieben werden.", spielerUuid, e);
            throw new IllegalStateException("Verstoß-Zeile konnte nicht nach MySQL geschrieben werden.", e);
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

    void loescheZeileNachMysql(UUID spielerUuid, int nummer) {
        java.util.Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        try (java.sql.Connection verbindung = mysql.verbindung();
                java.sql.PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_VERSTOESSE + " WHERE uuid = ? AND nummer = ?")) {
            loeschen.setString(1, spielerUuid.toString());
            loeschen.setInt(2, nummer);
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.error("Verstoß-Zeile für {} konnte nicht aus MySQL gelöscht werden.", spielerUuid, e);
            throw new IllegalStateException("Verstoß-Zeile konnte nicht aus MySQL gelöscht werden.", e);
        }
    }

    private void speichereSpielerNachMysql(UUID spielerUuid, List<VerstossRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "DELETE FROM " + MysqlSchema.T_VERSTOESSE + " WHERE uuid = ?")) {
                loeschen.setString(1, spielerUuid.toString());
                loeschen.executeUpdate();
            }
            if (snapshot != null && !snapshot.isEmpty()) {
                try (PreparedStatement einfuegen = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_VERSTOESSE
                                + " (uuid, nummer, spieler_name, grund, ausfuehrer, erstellt_am, stufe)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (VerstossRecord verstoss : snapshot) {
                        if (verstoss == null) {
                            continue;
                        }
                        einfuegen.setString(1, spielerUuid.toString());
                        einfuegen.setInt(2, verstoss.nummer());
                        einfuegen.setString(3, verstoss.spielerName());
                        einfuegen.setString(4, verstoss.grund());
                        einfuegen.setString(5, verstoss.ausfuehrer());
                        einfuegen.setLong(6, verstoss.erstelltAm());
                        einfuegen.setString(7, verstoss.stufe().schluessel());
                        einfuegen.addBatch();
                    }
                    einfuegen.executeBatch();
                }
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
            LOG.error("Verstöße für {} konnten nicht nach MySQL geschrieben werden.", spielerUuid, e);
            throw new IllegalStateException("Verstöße konnten nicht nach MySQL geschrieben werden.", e);
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

    private void persistiere(UUID spielerUuid) {
        List<VerstossRecord> momentan = verstoesse.get(spielerUuid);
        final List<VerstossRecord> snapshot = (momentan == null) ? null : List.copyOf(momentan);
        if (mysql != null) {
            final List<VerstossRecord> effektiv = (snapshot == null) ? List.of() : snapshot;
            ioExecutor.ausfuehren(() -> speichereSpielerNachMysql(spielerUuid, effektiv));
            return;
        }
        java.util.List<Object> roh = rohQuarantaene.get(spielerUuid);
        final java.util.List<Object> rohSnapshot = (roh == null) ? java.util.List.of() : List.copyOf(roh);
        final File datei = dateiFuer(spielerUuid);
        if ((snapshot == null || snapshot.isEmpty()) && rohSnapshot.isEmpty()) {
            ioExecutor.ausfuehren(() -> sichereLoeschung(datei));
        } else {
            final java.util.List<VerstossRecord> effektiv =
                    (snapshot == null) ? java.util.List.of() : snapshot;
            final Map<String, Object> daten = konfigurationErstellen(effektiv, rohSnapshot);
            ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> yamlSpeicher.speichern(daten, datei));
        }
    }

    private void sichereLoeschung(File datei) {
        try {
            if (!datei.exists()) {
                return;
            }

            java.nio.file.Path quelle = datei.toPath();
            java.nio.file.Path backup = quelle.resolveSibling(
                    datei.getName() + ".deleted-" + System.currentTimeMillis() + ".bak");
            try {
                java.nio.file.Files.move(quelle, backup,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOG.debug("Verstoß-Datei {} als {} gesichert.", datei.getName(), backup.getFileName());
                rotiereDeletedBackups(datei.getParentFile(), datei.getName());
            } catch (java.io.IOException e) {
                if (!datei.delete()) {
                    LOG.warn("Die Verstoß-Datei {} konnte nicht gelöscht werden.", datei.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOG.warn("Sichere Löschung von {} fehlgeschlagen.", datei, e);
        }
    }

    private File dateiFuer(UUID spielerUuid) {
        return new File(verstoesseOrdner, spielerUuid + DATEI_ENDUNG);
    }

    private static void rotiereDeletedBackups(File ordner, String basisName) {
        try {
            if (ordner == null) {
                return;
            }
            File[] backups = ordner.listFiles((dir, name) ->
                    name.startsWith(basisName + ".deleted-") && name.endsWith(".bak"));
            if (backups == null || backups.length <= 20) {
                return;
            }
            java.util.Arrays.sort(backups,
                    java.util.Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < backups.length - 20; i++) {
                try {
                    if (!backups[i].delete()) {
                        break;
                    }
                } catch (Exception e) {
                    LOG.debug("Backup-Rotation: alte Datei konnte nicht gelöscht werden.", e);
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("Backup-Rotation fehlgeschlagen.", e);
        }
    }

    private List<VerstossRecord> ladenDatei(File datei, java.util.List<Object> quarantaene) {
        YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
        if (ladeStatus.fehler()) {
            return null;
        }
        Map<String, Object> daten = ladeStatus.daten();
        java.util.List<Object> rohEintraege = Karten.liste(daten, SCHLUESSEL_LISTE);
        List<VerstossRecord> ergebnis = new ArrayList<>();
        for (Object rohObj : rohEintraege) {
            Map<String, Object> eintrag = Karten.abschnitt(rohObj);
            if (eintrag == null) {
                LOG.error("Ein Verstoß-Eintrag in {} ist korrupt (kein Map) und wird QUARANTIERT (nicht gelöscht).",
                        datei.getName());
                if (rohObj != null) {
                    quarantaene.add(rohObj);
                }
                continue;
            }
            try {
                int nummer = Math.toIntExact(Karten.zahl(eintrag, SCHLUESSEL_NUMMER, 0L));
                String spielerName = Karten.text(eintrag, SCHLUESSEL_SPIELER_NAME, "Unbekannt");
                String grund = Karten.text(eintrag, SCHLUESSEL_GRUND, "");
                String ausfuehrer = Karten.text(eintrag, SCHLUESSEL_AUSFUEHRER, "Konsole");
                long erstelltAm = Karten.zahl(eintrag, SCHLUESSEL_ERSTELLT_AM, 0L);
                VerstossStufe stufe = leseStufeTolerant(eintrag, datei.getName());
                ergebnis.add(new VerstossRecord(nummer, spielerName, grund, ausfuehrer, erstelltAm, stufe));
            } catch (Exception e) {
                LOG.error("Ein Verstoß-Eintrag in {} ist korrupt und wird QUARANTIERT (nicht gelöscht). "
                                + "Bitte YAML prüfen/fixen – Eintrag wird bei jedem Save unverändert "
                                + "zurückgeschrieben.",
                        datei.getName(), e);
                quarantaene.add(rohObj);
            }
        }
        return ergebnis;
    }

    private static VerstossStufe leseStufeTolerant(Map<String, Object> eintrag, String dateiName) {
        Object roh = eintrag.get(SCHLUESSEL_STUFE);
        if (roh == null || String.valueOf(roh).isBlank()) {
            LOG.warn("Verstoß-Eintrag in {} ohne Stufe (alter Datenbestand) – nutze Standard-Stufe.", dateiName);
            return VerstossStufe.standard();
        }
        String text = String.valueOf(roh);
        var parsed = VerstossStufe.parse(text);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Unbekannte Verstoß-Stufe: " + text);
        }
        return parsed.get();
    }

    private Map<String, Object> konfigurationErstellen(List<VerstossRecord> liste, java.util.List<?> rohQuarantaeneListe) {
        Map<String, Object> daten = new LinkedHashMap<>();
        List<Object> eintraege = new ArrayList<>();
        for (VerstossRecord verstoss : liste) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put(SCHLUESSEL_NUMMER, verstoss.nummer());
            eintrag.put(SCHLUESSEL_SPIELER_NAME, verstoss.spielerName());
            eintrag.put(SCHLUESSEL_GRUND, verstoss.grund());
            eintrag.put(SCHLUESSEL_AUSFUEHRER, verstoss.ausfuehrer());
            eintrag.put(SCHLUESSEL_ERSTELLT_AM, verstoss.erstelltAm());
            eintrag.put(SCHLUESSEL_STUFE, verstoss.stufe().schluessel());
            eintraege.add(eintrag);
        }
        if (rohQuarantaeneListe != null) {
            for (Object roh : rohQuarantaeneListe) {
                if (roh == null) {
                    continue;
                }
                try {
                    eintraege.add(roh);
                } catch (Exception e) {
                    LOG.warn("Quarantäne-Eintrag in {} konnte nicht zurückgeschrieben werden.", SCHLUESSEL_LISTE, e);
                }
            }
        }
        daten.put(SCHLUESSEL_LISTE, eintraege);
        return daten;
    }
}
