package de.lightrixx.punish.velocity;

import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.Karten;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Neue Moderationsdaten verwenden dasselbe Speicher-Backend wie die Strafverwaltung. */
public final class ModerationsSpeicher {

    private final File datei;
    private final YamlSpeicher yaml;
    private final IoExecutor io;
    private final MysqlVerbindung mysql;
    private final String bereich;
    private final Map<Long, Map<String, Object>> daten = new LinkedHashMap<>();
    private long zaehler;

    public ModerationsSpeicher(File ordner, YamlSpeicher yaml, IoExecutor io,
                               MysqlVerbindung mysql, String bereich) {
        if (!List.of("faelle", "chatfilter-pruefung").contains(bereich)) {
            throw new IllegalArgumentException("Unbekannter Moderationsbereich.");
        }
        this.datei = new File(Objects.requireNonNull(ordner), bereich + ".yml");
        this.yaml = Objects.requireNonNull(yaml);
        this.io = Objects.requireNonNull(io);
        this.mysql = mysql;
        this.bereich = bereich;
    }

    public synchronized void laden() {
        if (mysql != null) {
            try (var verbindung = mysql.verbindung();
                 var sql = verbindung.prepareStatement("SELECT id,daten,zaehler FROM "
                         + MysqlSchema.T_MODERATION + " WHERE bereich=? ORDER BY id")) {
                sql.setString(1, bereich);
                boolean metaGeladen = false;
                try (var rs = sql.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        Map<String, Object> eintrag = dekodiere(rs.getString(2));
                        if (id == 0) {
                            metaGeladen = true;
                            zaehler = Math.max(zaehler, rs.getLong(3));
                        } else {
                            daten.put(id, Map.copyOf(eintrag));
                            zaehler = Math.max(zaehler, id);
                        }
                    }
                }
                if (metaGeladen || !daten.isEmpty()) {
                    return;
                }
                // Der Meta-Eintrag verhindert einen erneuten Import nach späteren Löschungen.
                var status = YamlSpeicher.status(yaml, datei);
                if (status.fehler()) {
                    throw new IllegalStateException("Moderationsdaten für den MySQL-Import unlesbar.");
                }
                ladeYaml(status.daten());
                verbindung.setAutoCommit(false);
                try {
                    for (var eintrag : daten.entrySet()) {
                        upsert(verbindung, eintrag.getKey(), eintrag.getValue());
                    }
                    schreibeZaehler(verbindung, zaehler);
                    verbindung.commit();
                } catch (Exception e) {
                    verbindung.rollback();
                    throw e;
                }
            } catch (Exception e) {
                throw new IllegalStateException("Moderationsspeicher '" + bereich + "' konnte nicht geladen werden.", e);
            }
        } else {
            var status = YamlSpeicher.status(yaml, datei);
            if (status.fehler()) {
                throw new IllegalStateException(datei.getName() + " konnte nicht gelesen werden.");
            }
            ladeYaml(status.daten());
        }
    }

    private void ladeYaml(Map<String, Object> roh) {
        if (!roh.isEmpty() && (!(roh.get("eintraege") instanceof List<?>) || !roh.containsKey("zaehler"))) {
            throw new IllegalStateException("Ungültige Struktur in " + datei.getName());
        }
        long neuZaehler = Karten.zahl(roh, "zaehler", 0L);
        Map<Long, Map<String, Object>> geladen = new LinkedHashMap<>();
        for (Object element : Karten.liste(roh, "eintraege")) {
            var eintrag = Karten.abschnitt(element);
            if (eintrag == null || Karten.zahl(eintrag, "id", 0L) < 1) {
                throw new IllegalStateException("Ungültiger Eintrag in " + datei.getName());
            }
            long id = Karten.zahl(eintrag, "id", 0L);
            if (geladen.putIfAbsent(id, Map.copyOf(eintrag)) != null) {
                throw new IllegalStateException("Doppelte ID in " + datei.getName());
            }
            neuZaehler = Math.max(neuZaehler, id);
        }
        daten.clear();
        daten.putAll(geladen);
        zaehler = neuZaehler;
    }

    public synchronized long naechsteId() {
        return ++zaehler;
    }

    public synchronized List<Map<String, Object>> alle() {
        return List.copyOf(daten.values());
    }

    public synchronized void speichern(long id, Map<String, Object> eintrag) {
        Map<String, Object> snapshot = new LinkedHashMap<>(eintrag);
        snapshot.put("id", id);
        snapshot = Map.copyOf(snapshot);
        daten.put(id, snapshot);
        zaehler = Math.max(zaehler, id);
        if (mysql == null) {
            persistiereYaml();
        } else {
            final var wert = snapshot;
            final long stand = zaehler;
            io.ausfuehrenMitSchluessel("moderation:" + bereich + ":" + id, () -> {
                try (var verbindung = mysql.verbindung()) {
                    verbindung.setAutoCommit(false);
                    try {
                        upsert(verbindung, id, wert);
                        schreibeZaehler(verbindung, stand);
                        verbindung.commit();
                    } catch (Exception e) {
                        verbindung.rollback();
                        throw e;
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("Moderationsdaten konnten nicht gespeichert werden (" + bereich + ").", e);
                }
            });
        }
    }

    public synchronized void entfernenWenn(Predicate<Map<String, Object>> bedingung) {
        var ids = daten.entrySet().stream().filter(e -> bedingung.test(e.getValue()))
                .map(Map.Entry::getKey).toList();
        if (ids.isEmpty()) {
            return;
        }
        ids.forEach(daten::remove);
        if (mysql == null) {
            persistiereYaml();
        } else {
            for (long id : ids) {
                io.ausfuehrenMitSchluessel("moderation:" + bereich + ":" + id, () -> {
                    try (var verbindung = mysql.verbindung();
                         var sql = verbindung.prepareStatement("DELETE FROM " + MysqlSchema.T_MODERATION
                                 + " WHERE bereich=? AND id=?")) {
                        sql.setString(1, bereich);
                        sql.setLong(2, id);
                        sql.executeUpdate();
                    } catch (Exception e) {
                        throw new IllegalStateException("Abgelaufene Prüfdaten konnten nicht gelöscht werden.", e);
                    }
                });
            }
        }
    }

    private void persistiereYaml() {
        var snapshot = Map.<String, Object>of("zaehler", zaehler, "eintraege", new ArrayList<>(daten.values()));
        io.ausfuehrenMitSchluessel("moderation:" + bereich, () -> yaml.speichern(snapshot, datei));
    }

    private void upsert(Connection verbindung, long id, Map<String, Object> wert) throws java.sql.SQLException {
        try (var sql = verbindung.prepareStatement("INSERT INTO " + MysqlSchema.T_MODERATION
                + " (bereich,id,daten) VALUES (?,?,?) ON DUPLICATE KEY UPDATE daten=VALUES(daten)")) {
            sql.setString(1, bereich);
            sql.setLong(2, id);
            sql.setString(3, new Yaml().dump(wert));
            sql.executeUpdate();
        }
    }

    private void schreibeZaehler(Connection verbindung, long stand) throws java.sql.SQLException {
        try (var sql = verbindung.prepareStatement("INSERT INTO " + MysqlSchema.T_MODERATION
                + " (bereich,id,daten,zaehler) VALUES (?,0,'{}',?)"
                + " ON DUPLICATE KEY UPDATE zaehler=GREATEST(zaehler,VALUES(zaehler))")) {
            sql.setString(1, bereich);
            sql.setLong(2, stand);
            sql.executeUpdate();
        }
    }

    static Map<String, Object> dekodiere(String text) {
        LoaderOptions optionen = new LoaderOptions();
        optionen.setAllowDuplicateKeys(false);
        optionen.setMaxAliasesForCollections(0);
        optionen.setCodePointLimit(2_000_000);
        Object roh = new Yaml(new SafeConstructor(optionen)).load(text);
        Map<String, Object> karte = Karten.abschnitt(roh);
        if (karte == null) {
            throw new IllegalStateException("Ungültige Moderationsdaten.");
        }
        return karte;
    }
}
