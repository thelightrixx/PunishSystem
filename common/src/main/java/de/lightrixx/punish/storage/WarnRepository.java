package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.WarnRecord;
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

public final class WarnRepository {

    private static final Logger LOG = LoggerFactory.getLogger(WarnRepository.class);
    private static final String UNTERORDNER = "warns";
    private static final String DATEI_ENDUNG = ".yml";
    private static final String SCHLUESSEL_LISTE = "warns";
    private static final String SCHLUESSEL_NUMMER = "nummer";
    private static final String SCHLUESSEL_SPIELER_NAME = "spieler-name";
    private static final String SCHLUESSEL_GRUND = "grund";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";

    private volatile ConcurrentHashMap<UUID, List<WarnRecord>> warns = new ConcurrentHashMap<>();

    private volatile ConcurrentHashMap<UUID, List<Object>> rohQuarantaene = new ConcurrentHashMap<>();
    private final File warnsOrdner;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public WarnRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Der YAML-Speicher darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.warnsOrdner = new File(datenOrdner, UNTERORDNER);
        this.mysql = null;
    }

    public WarnRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
        this.mysql = Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.warnsOrdner = null;
        this.yamlSpeicher = null;
    }

    public boolean istMysql() {
        return mysql != null;
    }

    public void laden() {
        try {
            boolean idle = ioExecutor.warteAufIdle(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!idle) {
                LOG.warn("Warn-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Warn-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = warns.values().stream().filter(Objects::nonNull).mapToInt(liste -> Objects.requireNonNull(liste).size()).sum();
            File[] dateien = warnsOrdner.listFiles((verzeichnis, name) -> name != null && name.endsWith(DATEI_ENDUNG));
            if (dateien == null) {
                LOG.info("Es wurden keine Warn-Dateien gefunden.");
                return;
            }
            ConcurrentHashMap<UUID, List<WarnRecord>> tmp = new ConcurrentHashMap<>();
            ConcurrentHashMap<UUID, List<Object>> tmpRoh = new ConcurrentHashMap<>();
            int gesamtAnzahl = 0;
            boolean ladeFehler = false;
            for (File datei : dateien) {
                try {
                    String dateiName = datei.getName();
                    String uuidText = dateiName.substring(0, dateiName.length() - DATEI_ENDUNG.length());
                    UUID spielerUuid = UUID.fromString(uuidText);
                    java.util.List<Object> quarListe = new java.util.ArrayList<>();
                    List<WarnRecord> liste = ladenDatei(datei, quarListe);
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
                    LOG.warn("Die Warn-Datei {} konnte nicht geladen werden.",
                            datei.getName(), e);
                }
            }
            if (ladeFehler) {
                LOG.error("Warns konnten nicht vollständig gelesen werden ({} im Memory, "
                        + "{} Dateien) – behalte alten Stand.", alteGroesse, dateien.length);
            } else {
                warns = tmp;
                rohQuarantaene = tmpRoh;
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} Spieler mit korrupten Warn-Einträgen in Quarantäne (bleiben auf Disk erhalten).",
                            tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} Warns für {} Spieler aus den Dateien geladen.",
                    gesamtAnzahl, warns.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public record HinzufuegeErgebnis(WarnRecord record, int anzahl) {
    }

    public WarnRecord hinzufuegen(UUID spielerUuid, String spielerName, String grund, String ausfuehrer) {
        return hinzufuegenMitAnzahl(spielerUuid, spielerName, grund, ausfuehrer).record();
    }

    public HinzufuegeErgebnis hinzufuegenMitAnzahl(UUID spielerUuid, String spielerName, String grund, String ausfuehrer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(spielerName, "Der Spielername darf nicht null sein.");
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            List<WarnRecord> neueListe = warns.compute(spielerUuid, (uuid, alte) -> {
                List<WarnRecord> basis = (alte == null) ? List.of() : alte;
                int neueNummer = basis.stream()
                        .filter(eintrag -> eintrag != null)
                        .mapToInt(eintrag -> Objects.requireNonNull(eintrag).nummer()).max().orElse(0) + 1;
                WarnRecord neu = new WarnRecord(neueNummer, spielerName, grund, ausfuehrer,
                        System.currentTimeMillis());
                List<WarnRecord> erweitert = new ArrayList<>(basis);
                erweitert.add(neu);
                return List.copyOf(erweitert);
            });
            WarnRecord neu = Objects.requireNonNull(neueListe.getLast());
            if (mysql != null) {
                final WarnRecord snapshot = neu;
                ioExecutor.ausfuehren(() -> upsertZeileNachMysql(spielerUuid, snapshot));
            } else {
                persistiere(spielerUuid);
            }
            return new HinzufuegeErgebnis(neu, neueListe.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<WarnRecord> entfernen(UUID spielerUuid, int nummer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            AtomicReference<WarnRecord> entfernt = new AtomicReference<>();
            warns.computeIfPresent(spielerUuid, (uuid, alte) -> {
                List<WarnRecord> verbleibend = new ArrayList<>();
                for (WarnRecord warn : alte) {
                    if (warn == null) {
                        continue;
                    }
                    if (warn.nummer() == nummer) {
                        entfernt.set(warn);
                    } else {
                        verbleibend.add(warn);
                    }
                }
                return verbleibend.isEmpty() ? null : List.copyOf(verbleibend);
            });
            WarnRecord entfernter = entfernt.get();
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

    public Optional<WarnRecord> bearbeiten(UUID spielerUuid, int nummer, String neuerGrund) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(neuerGrund, "Der neue Grund darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            AtomicReference<WarnRecord> bearbeitet = new AtomicReference<>();
            warns.computeIfPresent(spielerUuid, (uuid, alte) -> {
                List<WarnRecord> neuListe = new ArrayList<>();
                for (WarnRecord warn : alte) {
                    if (warn == null) {
                        continue;
                    }
                    if (warn.nummer() == nummer) {
                        WarnRecord aktualisiert = warn.mitGrund(neuerGrund);
                        bearbeitet.set(aktualisiert);
                        neuListe.add(aktualisiert);
                    } else {
                        neuListe.add(warn);
                    }
                }
                return List.copyOf(neuListe);
            });
            WarnRecord bearbeiteter = bearbeitet.get();
            if (bearbeiteter != null) {
                if (mysql != null) {
                    final WarnRecord snapshot = bearbeiteter;
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

    public List<WarnRecord> finden(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<WarnRecord> liste = warns.get(spielerUuid);
        return liste == null ? List.of() : List.copyOf(liste);
    }

    public int anzahl(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<WarnRecord> liste = warns.get(spielerUuid);
        return liste == null ? 0 : liste.size();
    }

    public java.util.Map<UUID, List<WarnRecord>> alle() {
        java.util.Map<UUID, List<WarnRecord>> kopie = new java.util.HashMap<>();
        warns.forEach((uuid, liste) -> {
            if (uuid != null && liste != null && !liste.isEmpty()) {
                kopie.put(uuid, List.copyOf(liste));
            }
        });
        return java.util.Map.copyOf(kopie);
    }

    public int migrationImport(java.util.Map<UUID, List<WarnRecord>> alle) {
        Objects.requireNonNull(alle, "Die Warn-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<UUID, List<WarnRecord>> neu = new ConcurrentHashMap<>();
            int anzahl = 0;
            for (java.util.Map.Entry<UUID, List<WarnRecord>> eintrag : alle.entrySet()) {
                if (eintrag.getKey() == null || eintrag.getValue() == null) {
                    continue;
                }
                List<WarnRecord> kopie = List.copyOf(eintrag.getValue());
                if (!kopie.isEmpty()) {
                    neu.put(eintrag.getKey(), kopie);
                    anzahl += kopie.size();
                }
            }
            warns = neu;
            for (java.util.Map.Entry<UUID, List<WarnRecord>> eintrag : neu.entrySet()) {
                final UUID uuid = eintrag.getKey();
                final List<WarnRecord> snapshot = eintrag.getValue();
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
            int alteGroesse = warns.values().stream().filter(Objects::nonNull).mapToInt(List::size).sum();
            ConcurrentHashMap<UUID, List<WarnRecord>> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT uuid, nummer, spieler_name, grund, ausfuehrer, erstellt_am"
                                 + " FROM " + MysqlSchema.T_WARNS + " ORDER BY uuid, nummer")) {
                while (ergebnis.next()) {
                    try {
                        UUID uuid = UUID.fromString(ergebnis.getString("uuid"));
                        WarnRecord warn = new WarnRecord(ergebnis.getInt("nummer"),
                                ergebnis.getString("spieler_name"),
                                ergebnis.getString("grund"),
                                ergebnis.getString("ausfuehrer"),
                                ergebnis.getLong("erstellt_am"));
                        tmp.computeIfAbsent(uuid, k -> new ArrayList<>()).add(warn);
                    } catch (Exception e) {
                        LOG.error("Eine Warn-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Warns konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            ConcurrentHashMap<UUID, List<WarnRecord>> fertig = new ConcurrentHashMap<>();
            int gesamt = 0;
            for (java.util.Map.Entry<UUID, List<WarnRecord>> eintrag : tmp.entrySet()) {
                if (!eintrag.getValue().isEmpty()) {
                    fertig.put(eintrag.getKey(), List.copyOf(eintrag.getValue()));
                    gesamt += eintrag.getValue().size();
                }
            }
            warns = fertig;
            LOG.info("Es wurden {} Warns für {} Spieler aus MySQL geladen.", gesamt, warns.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    void upsertZeileNachMysql(UUID spielerUuid, WarnRecord warn) {
        java.util.Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        java.util.Objects.requireNonNull(warn, "Der Warn darf nicht null sein.");
        java.sql.Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (java.sql.PreparedStatement upsert = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_WARNS
                            + " (uuid, nummer, spieler_name, grund, ausfuehrer, erstellt_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE spieler_name = VALUES(spieler_name),"
                            + " grund = VALUES(grund), ausfuehrer = VALUES(ausfuehrer),"
                            + " erstellt_am = VALUES(erstellt_am)")) {
                upsert.setString(1, spielerUuid.toString());
                upsert.setInt(2, warn.nummer());
                upsert.setString(3, warn.spielerName());
                upsert.setString(4, warn.grund());
                upsert.setString(5, warn.ausfuehrer());
                upsert.setLong(6, warn.erstelltAm());
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
            LOG.error("Warn-Zeile für {} konnte nicht nach MySQL geschrieben werden.", spielerUuid, e);
            throw new IllegalStateException("Warn-Zeile konnte nicht nach MySQL geschrieben werden.", e);
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
                        "DELETE FROM " + MysqlSchema.T_WARNS + " WHERE uuid = ? AND nummer = ?")) {
            loeschen.setString(1, spielerUuid.toString());
            loeschen.setInt(2, nummer);
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.error("Warn-Zeile für {} konnte nicht aus MySQL gelöscht werden.", spielerUuid, e);
            throw new IllegalStateException("Warn-Zeile konnte nicht aus MySQL gelöscht werden.", e);
        }
    }

    private void speichereSpielerNachMysql(UUID spielerUuid, List<WarnRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "DELETE FROM " + MysqlSchema.T_WARNS + " WHERE uuid = ?")) {
                loeschen.setString(1, spielerUuid.toString());
                loeschen.executeUpdate();
            }
            if (snapshot != null && !snapshot.isEmpty()) {
                try (PreparedStatement einfuegen = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_WARNS
                                + " (uuid, nummer, spieler_name, grund, ausfuehrer, erstellt_am)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (WarnRecord warn : snapshot) {
                        if (warn == null) {
                            continue;
                        }
                        einfuegen.setString(1, spielerUuid.toString());
                        einfuegen.setInt(2, warn.nummer());
                        einfuegen.setString(3, warn.spielerName());
                        einfuegen.setString(4, warn.grund());
                        einfuegen.setString(5, warn.ausfuehrer());
                        einfuegen.setLong(6, warn.erstelltAm());
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
            LOG.error("Warns für {} konnten nicht nach MySQL geschrieben werden.", spielerUuid, e);
            throw new IllegalStateException("Warns konnten nicht nach MySQL geschrieben werden.", e);
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
        List<WarnRecord> momentan = warns.get(spielerUuid);
        final List<WarnRecord> snapshot = (momentan == null) ? null : List.copyOf(momentan);
        if (mysql != null) {
            final List<WarnRecord> effektiv = (snapshot == null) ? List.of() : snapshot;
            ioExecutor.ausfuehren(() -> speichereSpielerNachMysql(spielerUuid, effektiv));
            return;
        }
        java.util.List<Object> roh = rohQuarantaene.get(spielerUuid);
        final java.util.List<Object> rohSnapshot = (roh == null) ? java.util.List.of() : List.copyOf(roh);
        final File datei = dateiFuer(spielerUuid);
        if ((snapshot == null || snapshot.isEmpty()) && rohSnapshot.isEmpty()) {
            ioExecutor.ausfuehren(() -> sichereLoeschung(datei));
        } else {
            final java.util.List<WarnRecord> effektiv =
                    (snapshot == null) ? java.util.List.of() : snapshot;
            final Map<String, Object> daten = konfigurationErstellen(effektiv, rohSnapshot);
            ioExecutor.ausfuehrenMitSchluessel(datei.getPath(), () -> yamlSpeicher.speichern(daten, datei));
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
                LOG.debug("Warn-Datei {} als {} gesichert.", datei.getName(), backup.getFileName());
                rotiereDeletedBackups(datei.getParentFile(), datei.getName());
            } catch (java.io.IOException e) {
                if (!datei.delete()) {
                    LOG.warn("Die Warn-Datei {} konnte nicht gelöscht werden.", datei.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOG.warn("Sichere Löschung von {} fehlgeschlagen.", datei, e);
        }
    }

    private File dateiFuer(UUID spielerUuid) {
        return new File(warnsOrdner, spielerUuid + DATEI_ENDUNG);
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

    private List<WarnRecord> ladenDatei(File datei, java.util.List<Object> quarantaene) {
        YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
        if (ladeStatus.fehler()) {
            return null;
        }
        Map<String, Object> daten = ladeStatus.daten();
        if (daten.containsKey(SCHLUESSEL_LISTE) && !(daten.get(SCHLUESSEL_LISTE) instanceof java.util.List)) {
            LOG.error("Die Warn-Datei {} hat einen unbrauchbaren '{}'-Abschnitt.",
                    datei.getName(), SCHLUESSEL_LISTE);
            return null;
        }
        java.util.List<Object> rohEintraege = Karten.liste(daten, SCHLUESSEL_LISTE);
        List<WarnRecord> ergebnis = new ArrayList<>();
        for (Object rohObj : rohEintraege) {
            Map<String, Object> eintrag = Karten.abschnitt(rohObj);
            if (eintrag == null) {
                LOG.error("Ein Warn-Eintrag in {} ist korrupt (kein Map) und wird QUARANTIERT (nicht gelöscht).",
                        datei.getName());
                if (rohObj != null) {
                    quarantaene.add(rohObj);
                }
                continue;
            }
            try {
                int nummer = Math.toIntExact(Karten.zahl(eintrag, SCHLUESSEL_NUMMER, 0L));
                String spielerName = Karten.text(eintrag, SCHLUESSEL_SPIELER_NAME, "Unbekannt");
                Object grundRoh = eintrag.get(SCHLUESSEL_GRUND);
                if (grundRoh == null) {
                    grundRoh = eintrag.get("text");
                }
                String grund = grundRoh instanceof String wert ? wert : "";
                String ausfuehrer = Karten.text(eintrag, SCHLUESSEL_AUSFUEHRER, "Konsole");
                long erstelltAm = Karten.zahl(eintrag, SCHLUESSEL_ERSTELLT_AM, 0L);
                ergebnis.add(new WarnRecord(nummer, spielerName, grund, ausfuehrer, erstelltAm));
            } catch (Exception e) {
                LOG.error("Ein Warn-Eintrag in {} ist korrupt und wird QUARANTIERT (nicht gelöscht). "
                                + "Bitte YAML prüfen/fixen – Eintrag wird bei jedem Save unverändert "
                                + "zurückgeschrieben.",
                        datei.getName(), e);
                quarantaene.add(rohObj);
            }
        }
        return ergebnis;
    }

    private Map<String, Object> konfigurationErstellen(List<WarnRecord> liste, java.util.List<?> rohQuarantaeneListe) {
        Map<String, Object> daten = new LinkedHashMap<>();
        List<Object> eintraege = new ArrayList<>();
        for (WarnRecord warn : liste) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put(SCHLUESSEL_NUMMER, warn.nummer());
            eintrag.put(SCHLUESSEL_SPIELER_NAME, warn.spielerName());
            eintrag.put(SCHLUESSEL_GRUND, warn.grund());
            eintrag.put(SCHLUESSEL_AUSFUEHRER, warn.ausfuehrer());
            eintrag.put(SCHLUESSEL_ERSTELLT_AM, warn.erstelltAm());
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
