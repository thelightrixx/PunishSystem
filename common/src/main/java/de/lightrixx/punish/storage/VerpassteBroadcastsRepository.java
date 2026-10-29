package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.VerpassteNachricht;
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
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public final class VerpassteBroadcastsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(VerpassteBroadcastsRepository.class);
    private static final String DATEI_NAME = "missed_broadcasts.yml";

    public static final int MAX_ABRUF = 5000;

    private final ConcurrentSkipListMap<Long, VerpassteNachricht> eintraege = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<UUID, Long> cursor = new ConcurrentHashMap<>();
    private final AtomicLong zaehler = new AtomicLong(0);

    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public VerpassteBroadcastsRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Der YAML-Speicher darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(datenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public VerpassteBroadcastsRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
        this.mysql = Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = null;
        this.yamlSpeicher = null;
    }

    public boolean istMysql() {
        return mysql != null;
    }

    public VerpassteNachricht ablegen(String typ, String text) {
        if (typ == null || typ.isBlank()) {
            throw new IllegalArgumentException("Der Broadcast-Typ darf nicht leer sein.");
        }
        String sichererText = text == null ? "" : text;
        long id = zaehler.incrementAndGet();
        long jetzt = System.currentTimeMillis();
        VerpassteNachricht eintrag = new VerpassteNachricht(id, typ.trim(), sichererText, jetzt);
        StoreLock.LOCK.writeLock().lock();
        try {
            eintraege.put(id, eintrag);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
        if (mysql != null) {
            ioExecutor.ausfuehren(() -> speichereEintragNachMysql(eintrag));
        } else {
            speichereAsync();
        }
        return eintrag;
    }

    public List<VerpassteNachricht> findeNach(long zeitstempel, int limit) {
        int cap = Math.min(Math.max(limit, 0), MAX_ABRUF);
        if (cap <= 0) {
            return List.of();
        }
        List<VerpassteNachricht> ergebnis = new ArrayList<>(Math.min(cap, 256));
        for (VerpassteNachricht eintrag : eintraege.values()) {
            if (eintrag == null) {
                continue;
            }
            if (eintrag.zeitstempel() > zeitstempel) {
                ergebnis.add(eintrag);
                if (ergebnis.size() >= cap) {
                    break;
                }
            }
        }
        return List.copyOf(ergebnis);
    }

    public Optional<Long> cursorFuer(UUID uuid) {
        Objects.requireNonNull(uuid, "Die Spieler-UUID darf nicht null sein.");
        return Optional.ofNullable(cursor.get(uuid));
    }

    public void cursorSetzen(UUID uuid, long zeitstempel) {
        Objects.requireNonNull(uuid, "Die Spieler-UUID darf nicht null sein.");
        if (zeitstempel <= 0) {
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            cursor.merge(uuid, zeitstempel, Math::max);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
        if (mysql != null) {
            long stand = cursor.getOrDefault(uuid, zeitstempel);
            ioExecutor.ausfuehren(() -> speichereCursorNachMysql(uuid, stand));
        } else {
            speichereAsync();
        }
    }

    public int beschneidenAelterAls(long grenze) {
        List<Long> opfer = new ArrayList<>();
        for (Map.Entry<Long, VerpassteNachricht> eintrag : eintraege.entrySet()) {
            VerpassteNachricht nachricht = eintrag.getValue();
            if (nachricht != null && nachricht.zeitstempel() < grenze) {
                opfer.add(eintrag.getKey());
            }
        }
        if (!opfer.isEmpty()) {
            if (mysql != null) {
                ioExecutor.ausfuehren(() -> loescheAelterAlsNachMysql(grenze));
            }
            for (Long id : opfer) {
                eintraege.remove(id);
            }
            if (mysql == null) {
                speichereAsync();
            }
            LOG.info("Verpasste Broadcasts beschnitten: {} Einträge älter als {} entfernt.",
                    opfer.size(), grenze);
        }
        return opfer.size();
    }

    public int anzahl() {
        return eintraege.size();
    }

    public List<VerpassteNachricht> momentaufnahme() {
        return List.copyOf(eintraege.values());
    }

    public Map<UUID, Long> cursorMomentaufnahme() {
        return Map.copyOf(cursor);
    }

    public void laden() {
        try {
            boolean idle = ioExecutor.warteAufIdle(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!idle) {
                LOG.warn("Missed-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Missed-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            Map<String, Object> daten = yamlSpeicher.laden(datei);
            List<?> rohEintraege = Karten.liste(daten, "eintraege");
            ConcurrentSkipListMap<Long, VerpassteNachricht> tmp = new ConcurrentSkipListMap<>();
            long maximaleId = 0L;
            for (Object rohObj : rohEintraege) {
                Map<String, Object> roh = Karten.abschnitt(rohObj);
                if (roh == null) {
                    continue;
                }
                try {
                    long id = Karten.zahl(roh, "id", 0L);
                    String typ = Karten.text(roh, "typ", "");
                    Object textRoh = roh.get("text");
                    String text = textRoh == null ? "" : String.valueOf(textRoh);
                    long zeitstempel = Karten.zahl(roh, "zeitstempel", 0L);
                    VerpassteNachricht eintrag = new VerpassteNachricht(id, typ, text, zeitstempel);
                    tmp.put(id, eintrag);
                    if (id > maximaleId) {
                        maximaleId = id;
                    }
                } catch (IllegalArgumentException e) {
                    LOG.warn("Korrupter Missed-Eintrag wird übersprungen.", e);
                }
            }
            Map<String, Object> rohCursor = Karten.abschnitt(daten, "cursor");
            ConcurrentHashMap<UUID, Long> tmpCursor = new ConcurrentHashMap<>();
            if (rohCursor != null) {
                for (Map.Entry<String, Object> paar : rohCursor.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(paar.getKey());
                        long stand = paar.getValue() instanceof Number nummer
                                ? nummer.longValue() : 0L;
                        if (stand > 0) {
                            tmpCursor.put(uuid, stand);
                        }
                    } catch (IllegalArgumentException e) {
                        LOG.warn("Korrupter Missed-Cursor '{}' wird übersprungen.", paar.getKey());
                    }
                }
            }
            if (tmp.isEmpty() && tmpCursor.isEmpty() && (!eintraege.isEmpty() || !cursor.isEmpty())) {
                LOG.error("Missed-Reload lieferte 0 Einträge bei {} im Memory – "
                        + "halte Memory (korrupte/leere Datei?), kein Wipe. Datei: {}",
                        eintraege.size(), datei.getAbsolutePath());
                return;
            }
            eintraege.clear();
            eintraege.putAll(tmp);
            cursor.clear();
            cursor.putAll(tmpCursor);
            zaehler.set(Math.max(zaehler.get(), maximaleId));
            LOG.info("Es wurden {} verpasste Broadcasts geladen ({} Cursor).",
                    eintraege.size(), cursor.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public int migrationImport(List<VerpassteNachricht> alle, Map<UUID, Long> alleCursor) {
        Objects.requireNonNull(alle, "Die Nachrichten-Liste darf nicht null sein.");
        Objects.requireNonNull(alleCursor, "Die Cursor-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            List<VerpassteNachricht> snapshot = new ArrayList<>();
            Map<UUID, Long> cursorSnapshot = new LinkedHashMap<>();
            long maximaleId = 0L;
            for (VerpassteNachricht eintrag : alle) {
                if (eintrag == null) {
                    continue;
                }
                snapshot.add(eintrag);
                eintraege.put(eintrag.id(), eintrag);
                if (eintrag.id() > maximaleId) {
                    maximaleId = eintrag.id();
                }
            }
            for (Map.Entry<UUID, Long> paar : alleCursor.entrySet()) {
                if (paar.getKey() == null || paar.getValue() == null || paar.getValue() <= 0) {
                    continue;
                }
                cursorSnapshot.put(paar.getKey(), paar.getValue());
                cursor.merge(paar.getKey(), paar.getValue(), Math::max);
            }
            final long gefundeneMaxId = maximaleId;
            zaehler.updateAndGet(aktuell -> Math.max(aktuell, gefundeneMaxId));
            final List<VerpassteNachricht> kopie = List.copyOf(snapshot);
            final Map<UUID, Long> cursorKopie = Map.copyOf(cursorSnapshot);
            ioExecutor.ausfuehren(() -> speichereAlleNachMysql(kopie, cursorKopie));
            return kopie.size();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereAsync() {
        final List<VerpassteNachricht> snapshot = momentaufnahme();
        final Map<String, Object> daten = konfigurationErstellen(snapshot, cursorMomentaufnahme());
        final File ziel = datei;
        final YamlSpeicher speicher = yamlSpeicher;
        ioExecutor.ausfuehren(() -> speicher.speichern(daten, ziel));
    }

    private Map<String, Object> konfigurationErstellen(List<VerpassteNachricht> liste,
                                                       Map<UUID, Long> cursorStand) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> rohEintraege = new ArrayList<>();
        for (VerpassteNachricht eintrag : liste) {
            if (eintrag == null) {
                continue;
            }
            Map<String, Object> roh = new LinkedHashMap<>();
            roh.put("id", eintrag.id());
            roh.put("typ", eintrag.typ());
            roh.put("text", eintrag.text());
            roh.put("zeitstempel", eintrag.zeitstempel());
            rohEintraege.add(roh);
        }
        root.put("eintraege", rohEintraege);
        Map<String, Object> rohCursor = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> paar : cursorStand.entrySet()) {
            if (paar.getKey() == null || paar.getValue() == null || paar.getValue() <= 0) {
                continue;
            }
            rohCursor.put(paar.getKey().toString(), paar.getValue());
        }
        root.put("cursor", rohCursor);
        return root;
    }

    private void speichereEintragNachMysql(VerpassteNachricht eintrag) {
        try (Connection verbindung = mysql.verbindung();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "INSERT INTO " + MysqlSchema.T_MISSED_BROADCASTS
                             + " (id, typ, text, zeitstempel) VALUES (?, ?, ?, ?)"
                             + " ON DUPLICATE KEY UPDATE typ = VALUES(typ),"
                             + " text = VALUES(text), zeitstempel = VALUES(zeitstempel)")) {
            einfuegen.setLong(1, eintrag.id());
            einfuegen.setString(2, eintrag.typ());
            einfuegen.setString(3, eintrag.text());
            einfuegen.setLong(4, eintrag.zeitstempel());
            einfuegen.executeUpdate();
        } catch (Exception e) {
            LOG.error("Verpasster Broadcast {} konnte nicht nach MySQL geschrieben werden.",
                    eintrag.id(), e);
            throw new IllegalStateException("Verpasster Broadcast konnte nicht nach MySQL "
                    + "geschrieben werden: " + eintrag.id(), e);
        }
    }

    private void speichereCursorNachMysql(UUID uuid, long stand) {
        try (Connection verbindung = mysql.verbindung();
             PreparedStatement upsert = verbindung.prepareStatement(
                     "INSERT INTO " + MysqlSchema.T_MISSED_CURSOR
                             + " (uuid, zuletzt_nachgeholt) VALUES (?, ?)"
                             + " ON DUPLICATE KEY UPDATE zuletzt_nachgeholt = GREATEST("
                             + "zuletzt_nachgeholt, VALUES(zuletzt_nachgeholt))")) {
            upsert.setString(1, uuid.toString());
            upsert.setLong(2, stand);
            upsert.executeUpdate();
        } catch (Exception e) {
            LOG.error("Missed-Cursor für {} konnte nicht nach MySQL geschrieben werden.", uuid, e);
            throw new IllegalStateException("Missed-Cursor konnte nicht nach MySQL "
                    + "geschrieben werden: " + uuid, e);
        }
    }

    private void loescheAelterAlsNachMysql(long grenze) {
        try (Connection verbindung = mysql.verbindung();
             PreparedStatement loeschen = verbindung.prepareStatement(
                     "DELETE FROM " + MysqlSchema.T_MISSED_BROADCASTS + " WHERE zeitstempel < ?")) {
            loeschen.setLong(1, grenze);
            loeschen.executeUpdate();
        } catch (Exception e) {
            LOG.warn("Missed-Beschnitt nach MySQL fehlgeschlagen.", e);
            throw new IllegalStateException("Missed-Beschnitt nach MySQL fehlgeschlagen.", e);
        }
    }

    private void speichereAlleNachMysql(List<VerpassteNachricht> snapshot, Map<UUID, Long> cursorSnapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_MISSED_BROADCASTS
                            + " (id, typ, text, zeitstempel) VALUES (?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE typ = VALUES(typ),"
                            + " text = VALUES(text), zeitstempel = VALUES(zeitstempel)")) {
                for (VerpassteNachricht eintrag : snapshot) {
                    if (eintrag == null) {
                        continue;
                    }
                    einfuegen.setLong(1, eintrag.id());
                    einfuegen.setString(2, eintrag.typ());
                    einfuegen.setString(3, eintrag.text());
                    einfuegen.setLong(4, eintrag.zeitstempel());
                    einfuegen.addBatch();
                }
                einfuegen.executeBatch();
            }
            if (!cursorSnapshot.isEmpty()) {
                try (PreparedStatement cursorUpsert = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_MISSED_CURSOR
                                + " (uuid, zuletzt_nachgeholt) VALUES (?, ?)"
                                + " ON DUPLICATE KEY UPDATE zuletzt_nachgeholt = GREATEST("
                                + "zuletzt_nachgeholt, VALUES(zuletzt_nachgeholt))")) {
                    for (Map.Entry<UUID, Long> paar : cursorSnapshot.entrySet()) {
                        cursorUpsert.setString(1, paar.getKey().toString());
                        cursorUpsert.setLong(2, paar.getValue());
                        cursorUpsert.addBatch();
                    }
                    cursorUpsert.executeBatch();
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
            LOG.error("Missed-Migration konnte nicht nach MySQL geschrieben werden ({} Einträge).",
                    snapshot.size(), e);
            throw new IllegalStateException("Missed-Migration konnte nicht nach MySQL "
                    + "geschrieben werden.", e);
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

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = eintraege.size();
            ConcurrentSkipListMap<Long, VerpassteNachricht> tmp = new ConcurrentSkipListMap<>();
            ConcurrentHashMap<UUID, Long> tmpCursor = new ConcurrentHashMap<>();
            long maximaleId = 0L;
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT id, typ, text, zeitstempel FROM " + MysqlSchema.T_MISSED_BROADCASTS
                                 + " ORDER BY id")) {
                while (ergebnis.next()) {
                    try {
                        long id = ergebnis.getLong("id");
                        String typ = ergebnis.getString("typ");
                        String text = ergebnis.getString("text");
                        long zeitstempel = ergebnis.getLong("zeitstempel");
                        VerpassteNachricht eintrag = new VerpassteNachricht(id, typ,
                                text == null ? "" : text, zeitstempel);
                        tmp.put(id, eintrag);
                        if (id > maximaleId) {
                            maximaleId = id;
                        }
                    } catch (IllegalArgumentException e) {
                        LOG.error("Eine Missed-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Verpasste Broadcasts konnten nicht aus MySQL geladen werden – "
                        + "Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT uuid, zuletzt_nachgeholt FROM " + MysqlSchema.T_MISSED_CURSOR)) {
                while (ergebnis.next()) {
                    try {
                        UUID uuid = UUID.fromString(ergebnis.getString("uuid"));
                        long stand = ergebnis.getLong("zuletzt_nachgeholt");
                        if (stand > 0) {
                            tmpCursor.put(uuid, stand);
                        }
                    } catch (IllegalArgumentException e) {
                        LOG.error("Ein Missed-Cursor ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Missed-Cursor konnten nicht aus MySQL geladen werden – "
                        + "Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            eintraege.clear();
            eintraege.putAll(tmp);
            cursor.clear();
            cursor.putAll(tmpCursor);
            final long gefundeneMaxId = maximaleId;
            zaehler.updateAndGet(aktuell -> Math.max(aktuell, gefundeneMaxId));
            LOG.info("Es wurden {} verpasste Broadcasts aus MySQL geladen ({} Cursor).",
                    eintraege.size(), cursor.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }
}
