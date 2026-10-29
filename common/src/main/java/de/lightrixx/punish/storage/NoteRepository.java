package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.NoteRecord;
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

public final class NoteRepository {

    private static final Logger LOG = LoggerFactory.getLogger(NoteRepository.class);
    private static final int MAX_NOTIZEN_PRO_SPIELER = 200;
    private static final String UNTERORDNER = "notizen";
    private static final String DATEI_ENDUNG = ".yml";
    private static final String SCHLUESSEL_LISTE = "notizen";
    private static final String SCHLUESSEL_NUMMER = "nummer";
    private static final String SCHLUESSEL_SPIELER_NAME = "spieler-name";
    private static final String SCHLUESSEL_TEXT = "text";
    private static final String SCHLUESSEL_AUSFUEHRER = "ausfuehrer";
    private static final String SCHLUESSEL_ERSTELLT_AM = "erstellt-am";

    private volatile ConcurrentHashMap<UUID, List<NoteRecord>> notizen = new ConcurrentHashMap<>();

    private volatile ConcurrentHashMap<UUID, List<Object>> rohQuarantaene = new ConcurrentHashMap<>();
    private final File notizenOrdner;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public NoteRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.notizenOrdner = new File(datenOrdner, UNTERORDNER);
        this.mysql = null;
    }

    public NoteRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
        this.mysql = Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.notizenOrdner = null;
        this.yamlSpeicher = null;
    }

    public boolean istMysql() {
        return mysql != null;
    }

    public void laden() {
        try {
            boolean idle = ioExecutor.warteAufIdle(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!idle) {
                LOG.warn("Notiz-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Notiz-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = notizen.values().stream().filter(Objects::nonNull).mapToInt(liste -> Objects.requireNonNull(liste).size()).sum();
            File[] dateien = notizenOrdner.listFiles((verzeichnis, name) -> name != null && name.endsWith(DATEI_ENDUNG));
            if (dateien == null) {
                LOG.info("Es wurden keine Notiz-Dateien gefunden.");
                return;
            }
            ConcurrentHashMap<UUID, List<NoteRecord>> tmp = new ConcurrentHashMap<>();
            ConcurrentHashMap<UUID, List<Object>> tmpRoh = new ConcurrentHashMap<>();
            int gesamtAnzahl = 0;
            boolean ladeFehler = false;
            for (File datei : dateien) {
                try {
                    String dateiName = datei.getName();
                    String uuidText = dateiName.substring(0, dateiName.length() - DATEI_ENDUNG.length());
                    UUID spielerUuid = UUID.fromString(uuidText);
                    java.util.List<Object> quarListe = new java.util.ArrayList<>();
                    List<NoteRecord> liste = ladenDatei(datei, quarListe);
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
                    LOG.warn("Die Notiz-Datei {} konnte nicht geladen werden.",
                            datei.getName(), e);
                }
            }

            if (ladeFehler) {
                LOG.error("Notizen konnten nicht vollständig gelesen werden ({} im Memory, "
                        + "{} Dateien) – behalte alten Stand.", alteGroesse, dateien.length);
            } else {
                notizen = tmp;
                rohQuarantaene = tmpRoh;
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} Spieler mit korrupten Notiz-Einträgen in Quarantäne (bleiben auf Disk erhalten).",
                            tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} Notizen für {} Spieler aus den Dateien geladen.",
                    gesamtAnzahl, notizen.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public NoteRecord hinzufuegen(UUID spielerUuid, String spielerName, String text, String ausfuehrer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(spielerName, "Der Spielername darf nicht null sein.");
        Objects.requireNonNull(text, "Der Text darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            List<NoteRecord> neueListe = notizen.compute(spielerUuid, (uuid, alte) -> {
                List<NoteRecord> basis = (alte == null) ? List.of() : alte;
                int groessteGueltige = basis.stream()
                        .filter(eintrag -> eintrag != null)
                        .mapToInt(eintrag -> Objects.requireNonNull(eintrag).nummer()).max().orElse(0);
                int groessteQuarantaene = 0;
                for (Object roh : rohQuarantaene.getOrDefault(spielerUuid, List.of())) {
                    if (roh instanceof java.util.Map<?, ?> karte
                            && karte.get(SCHLUESSEL_NUMMER) instanceof Number zahl) {
                        groessteQuarantaene = Math.max(groessteQuarantaene, zahl.intValue());
                    }
                }
                int neueNummer = Math.max(groessteGueltige, groessteQuarantaene) + 1;
                NoteRecord neu = new NoteRecord(neueNummer, spielerName, text, ausfuehrer,
                        System.currentTimeMillis());
                List<NoteRecord> erweitert = new ArrayList<>(basis);
                erweitert.add(neu);
                while (erweitert.size() > MAX_NOTIZEN_PRO_SPIELER) {
                    erweitert.remove(0);
                }
                return List.copyOf(erweitert);
            });
            if (neueListe.size() >= MAX_NOTIZEN_PRO_SPIELER) {
                LOG.warn("Notiz-Limit erreicht für {} ({} Einträge, älteste verdrängt).", spielerUuid, neueListe.size());
            }
            persistiere(spielerUuid);
            return Objects.requireNonNull(neueListe.getLast());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<NoteRecord> entfernen(UUID spielerUuid, int nummer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            AtomicReference<NoteRecord> entfernt = new AtomicReference<>();
            notizen.computeIfPresent(spielerUuid, (uuid, alte) -> {
                List<NoteRecord> verbleibend = new ArrayList<>();
                for (NoteRecord notiz : alte) {
                    if (notiz == null) {
                        continue;
                    }
                    if (notiz.nummer() == nummer) {
                        entfernt.set(notiz);
                    } else {
                        verbleibend.add(notiz);
                    }
                }
                return verbleibend.isEmpty() ? null : List.copyOf(verbleibend);
            });
            NoteRecord entfernter = entfernt.get();
            if (entfernter != null) {
                persistiere(spielerUuid);
            }
            return Optional.ofNullable(entfernter);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public Optional<NoteRecord> bearbeiten(UUID spielerUuid, int nummer, String neuerText) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(neuerText, "Der neue Text darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        try {
            AtomicReference<NoteRecord> bearbeitet = new AtomicReference<>();
            notizen.computeIfPresent(spielerUuid, (uuid, alte) -> {
                List<NoteRecord> neuListe = new ArrayList<>();
                for (NoteRecord notiz : alte) {
                    if (notiz == null) {
                        continue;
                    }
                    if (notiz.nummer() == nummer) {
                        NoteRecord aktualisiert = notiz.mitText(neuerText);
                        bearbeitet.set(aktualisiert);
                        neuListe.add(aktualisiert);
                    } else {
                        neuListe.add(notiz);
                    }
                }
                return List.copyOf(neuListe);
            });
            NoteRecord bearbeiteter = bearbeitet.get();
            if (bearbeiteter != null) {
                persistiere(spielerUuid);
            }
            return Optional.ofNullable(bearbeiteter);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public List<NoteRecord> finden(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<NoteRecord> liste = notizen.get(spielerUuid);
        return liste == null ? List.of() : List.copyOf(liste);
    }

    public int anzahl(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        List<NoteRecord> liste = notizen.get(spielerUuid);
        return liste == null ? 0 : liste.size();
    }

    public java.util.Map<UUID, List<NoteRecord>> alle() {
        java.util.Map<UUID, List<NoteRecord>> kopie = new java.util.HashMap<>();
        notizen.forEach((uuid, liste) -> {
            if (uuid != null && liste != null && !liste.isEmpty()) {
                kopie.put(uuid, List.copyOf(liste));
            }
        });
        return java.util.Map.copyOf(kopie);
    }

    public int migrationImport(java.util.Map<UUID, List<NoteRecord>> alle) {
        Objects.requireNonNull(alle, "Die Notiz-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<UUID, List<NoteRecord>> neu = new ConcurrentHashMap<>();
            int anzahl = 0;
            for (java.util.Map.Entry<UUID, List<NoteRecord>> eintrag : alle.entrySet()) {
                if (eintrag.getKey() == null || eintrag.getValue() == null) {
                    continue;
                }
                List<NoteRecord> kopie = List.copyOf(eintrag.getValue());
                if (!kopie.isEmpty()) {
                    neu.put(eintrag.getKey(), kopie);
                    anzahl += kopie.size();
                }
            }
            notizen = neu;
            for (java.util.Map.Entry<UUID, List<NoteRecord>> eintrag : neu.entrySet()) {
                final UUID uuid = eintrag.getKey();
                final List<NoteRecord> snapshot = eintrag.getValue();
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
            int alteGroesse = notizen.values().stream().filter(Objects::nonNull).mapToInt(List::size).sum();
            ConcurrentHashMap<UUID, List<NoteRecord>> tmp = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT uuid, nummer, spieler_name, text, ausfuehrer, erstellt_am"
                                 + " FROM " + MysqlSchema.T_NOTIZEN + " ORDER BY uuid, nummer")) {
                while (ergebnis.next()) {
                    try {
                        UUID uuid = UUID.fromString(ergebnis.getString("uuid"));
                        NoteRecord notiz = new NoteRecord(ergebnis.getInt("nummer"),
                                ergebnis.getString("spieler_name"),
                                ergebnis.getString("text"),
                                ergebnis.getString("ausfuehrer"),
                                ergebnis.getLong("erstellt_am"));
                        tmp.computeIfAbsent(uuid, k -> new ArrayList<>()).add(notiz);
                    } catch (Exception e) {
                        LOG.error("Eine Notiz-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Notizen konnten nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            ConcurrentHashMap<UUID, List<NoteRecord>> fertig = new ConcurrentHashMap<>();
            int gesamt = 0;
            for (java.util.Map.Entry<UUID, List<NoteRecord>> eintrag : tmp.entrySet()) {
                if (!eintrag.getValue().isEmpty()) {
                    fertig.put(eintrag.getKey(), List.copyOf(eintrag.getValue()));
                    gesamt += eintrag.getValue().size();
                }
            }
            notizen = fertig;
            LOG.info("Es wurden {} Notizen für {} Spieler aus MySQL geladen.", gesamt, notizen.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereSpielerNachMysql(UUID spielerUuid, List<NoteRecord> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "DELETE FROM " + MysqlSchema.T_NOTIZEN + " WHERE uuid = ?")) {
                loeschen.setString(1, spielerUuid.toString());
                loeschen.executeUpdate();
            }
            if (snapshot != null && !snapshot.isEmpty()) {
                try (PreparedStatement einfuegen = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_NOTIZEN
                                + " (uuid, nummer, spieler_name, text, ausfuehrer, erstellt_am)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (NoteRecord notiz : snapshot) {
                        if (notiz == null) {
                            continue;
                        }
                        einfuegen.setString(1, spielerUuid.toString());
                        einfuegen.setInt(2, notiz.nummer());
                        einfuegen.setString(3, notiz.spielerName());
                        einfuegen.setString(4, notiz.text());
                        einfuegen.setString(5, notiz.ausfuehrer());
                        einfuegen.setLong(6, notiz.erstelltAm());
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
            LOG.error("Notizen für {} konnten nicht nach MySQL geschrieben werden.", spielerUuid, e);
            throw new IllegalStateException("Notizen konnten nicht nach MySQL geschrieben werden.", e);
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
        List<NoteRecord> momentan = notizen.get(spielerUuid);
        final List<NoteRecord> snapshot = (momentan == null) ? null : List.copyOf(momentan);
        if (mysql != null) {
            final List<NoteRecord> effektiv = (snapshot == null) ? List.of() : snapshot;
            ioExecutor.ausfuehren(() -> speichereSpielerNachMysql(spielerUuid, effektiv));
            return;
        }
        java.util.List<Object> roh = rohQuarantaene.get(spielerUuid);
        final java.util.List<Object> rohSnapshot = (roh == null) ? java.util.List.of() : List.copyOf(roh);
        final File datei = dateiFuer(spielerUuid);
        if ((snapshot == null || snapshot.isEmpty()) && rohSnapshot.isEmpty()) {
            ioExecutor.ausfuehrenMitSchluessel(datei.getPath(), () -> sichereLoeschung(datei));
        } else {
            final java.util.List<NoteRecord> effektiv =
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
                LOG.debug("Notiz-Datei {} als {} gesichert.", datei.getName(), backup.getFileName());
                rotiereDeletedBackups(datei.getParentFile(), datei.getName());
            } catch (java.io.IOException e) {
                if (!datei.delete()) {
                    LOG.warn("Die Notiz-Datei {} konnte nicht gelöscht werden.", datei.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOG.warn("Sichere Löschung von {} fehlgeschlagen.", datei, e);
        }
    }

    private File dateiFuer(UUID spielerUuid) {
        return new File(notizenOrdner, spielerUuid + DATEI_ENDUNG);
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

    private List<NoteRecord> ladenDatei(File datei, java.util.List<Object> quarantaene) {
        YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
        if (ladeStatus.fehler()) {
            return null;
        }
        Map<String, Object> daten = ladeStatus.daten();
        if (daten.containsKey(SCHLUESSEL_LISTE) && !(daten.get(SCHLUESSEL_LISTE) instanceof java.util.List)) {
            LOG.error("Die Notiz-Datei {} hat einen unbrauchbaren '{}'-Abschnitt.",
                    datei.getName(), SCHLUESSEL_LISTE);
            return null;
        }
        java.util.List<Object> rohEintraege = Karten.liste(daten, SCHLUESSEL_LISTE);
        List<NoteRecord> ergebnis = new ArrayList<>();
        for (Object rohObj : rohEintraege) {
            Map<String, Object> eintrag = Karten.abschnitt(rohObj);
            if (eintrag == null) {
                LOG.error("Ein Notiz-Eintrag in {} ist korrupt (kein Map) und wird QUARANTIERT (nicht gelöscht).",
                        datei.getName());
                if (rohObj != null) {
                    quarantaene.add(rohObj);
                }
                continue;
            }
            try {
                int nummer = eintrag.get(SCHLUESSEL_NUMMER) instanceof Number zahl ? zahl.intValue() : 0;
                String spielerName = eintrag.get(SCHLUESSEL_SPIELER_NAME) instanceof String wert ? wert : "Unbekannt";

                Object textRoh = eintrag.get(SCHLUESSEL_TEXT);
                if (textRoh == null) {
                    textRoh = eintrag.get("grund");
                }
                String text = textRoh instanceof String wert ? wert : "";
                String ausfuehrer = eintrag.get(SCHLUESSEL_AUSFUEHRER) instanceof String wert ? wert : "Konsole";
                long erstelltAm = eintrag.get(SCHLUESSEL_ERSTELLT_AM) instanceof Number zahl ? zahl.longValue() : 0L;
                ergebnis.add(new NoteRecord(nummer, spielerName, text, ausfuehrer, erstelltAm));
            } catch (Exception e) {
                LOG.error("Ein Notiz-Eintrag in {} ist korrupt und wird QUARANTIERT (nicht gelöscht). "
                                + "Bitte YAML prüfen/fixen – Eintrag wird bei jedem Save unverändert "
                                + "zurückgeschrieben.",
                        datei.getName(), e);
                quarantaene.add(rohObj);
            }
        }
        return ergebnis;
    }

    private Map<String, Object> konfigurationErstellen(List<NoteRecord> liste, java.util.List<?> rohQuarantaeneListe) {
        Map<String, Object> daten = new LinkedHashMap<>();
        List<Object> eintraege = new ArrayList<>();
        for (NoteRecord notiz : liste) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put(SCHLUESSEL_NUMMER, notiz.nummer());
            eintrag.put(SCHLUESSEL_SPIELER_NAME, notiz.spielerName());
            eintrag.put(SCHLUESSEL_TEXT, notiz.text());
            eintrag.put(SCHLUESSEL_AUSFUEHRER, notiz.ausfuehrer());
            eintrag.put(SCHLUESSEL_ERSTELLT_AM, notiz.erstelltAm());
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
