package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class HistoryRepository {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryRepository.class);

    private static final int MAX_EINTRAEGE_PRO_ZIEL = 1000;

    private volatile ConcurrentHashMap<UUID, List<HistoryEntry>> eintraege = new ConcurrentHashMap<>();

    private volatile ConcurrentHashMap<UUID, List<Object>> rohQuarantaene = new ConcurrentHashMap<>();
    private final AtomicLong zaehler = new AtomicLong(0);

    private final File historyOrdner;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final MysqlVerbindung mysql;

    public HistoryRepository(File datenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor) {
        Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Das YAML-Werkzeug darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.historyOrdner = new File(datenOrdner, "history");
        this.mysql = null;
    }

    public HistoryRepository(MysqlVerbindung mysql, IoExecutor ioExecutor) {
        this.mysql = Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.historyOrdner = null;
        this.yamlSpeicher = null;
    }

    public boolean istMysql() {
        return mysql != null;
    }

    public void laden() {
        boolean idle = false;
        try {
            idle = ioExecutor.warteAufIdle(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor History-Reload übersprungen.", e);
        }
        if (!idle) {
            LOG.warn("History-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich, lade trotzdem.",
                    ioExecutor.anzahlAusstehend());
        }
        if (mysql != null) {
            ladeAusMysql();
            return;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = eintraege.values().stream().filter(Objects::nonNull).mapToInt(liste -> Objects.requireNonNull(liste).size()).sum();
            ConcurrentHashMap<UUID, List<HistoryEntry>> tmp = new ConcurrentHashMap<>();
            ConcurrentHashMap<UUID, List<Object>> tmpRoh = new ConcurrentHashMap<>();
            long maximaleId = 0;
            boolean ladeFehler = false;

            File[] dateien = historyOrdner.listFiles((verzeichnis, name) -> name != null && name.endsWith(".yml"));
            if (dateien == null) {
                LOG.info("Es wurden keine History-Dateien gefunden.");
                return;
            }
            for (File datei : dateien) {
                try {
                    String dateiName = datei.getName();
                    String uuidText = dateiName.substring(0, dateiName.length() - ".yml".length());
                    UUID schluessel = UUID.fromString(uuidText);
                    java.util.List<Object> quarListe = new java.util.ArrayList<>();
                    List<HistoryEntry> liste = ladenDatei(datei, quarListe);
                    if (liste == null) {
                        ladeFehler = true;
                        continue;
                    }
                    if (!quarListe.isEmpty()) {
                        tmpRoh.put(schluessel, List.copyOf(quarListe));
                    }
                    if (!liste.isEmpty()) {
                        tmp.put(schluessel, List.copyOf(liste));
                        for (HistoryEntry eintrag : liste) {
                            if (eintrag != null && eintrag.id() > maximaleId) {
                                maximaleId = eintrag.id();
                            }
                        }
                    }
                } catch (Exception e) {
                    ladeFehler = true;
                    LOG.warn("Die History-Datei {} konnte nicht geladen werden.",
                            datei.getName(), e);
                }
            }

            long finaleMax = maximaleId;
            zaehler.updateAndGet(aktuell -> Math.max(aktuell, finaleMax));
            if (ladeFehler) {
                LOG.error("History konnte nicht vollständig gelesen werden ({} Einträge im Memory, "
                        + "{} Dateien) – behalte alten Stand.", alteGroesse, dateien.length);
            } else {
                eintraege = tmp;
                rohQuarantaene = tmpRoh;
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} History-Schlüssel mit korrupten Einträgen in Quarantäne (bleiben auf Disk erhalten).",
                            tmpRoh.size());
                }
            }
            int gesamt = eintraege.values().stream()
                    .filter(list -> list != null)
                    .mapToInt(list -> list.size()).sum();
            LOG.info("Es wurden {} History-Einträge geladen. Die nächste ID ist {}.", gesamt, zaehler.get() + 1);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public void hinzufuegen(HistoryEntry eintrag, UUID schluessel) {
        Objects.requireNonNull(eintrag, "Der History-Eintrag darf nicht null sein.");
        Objects.requireNonNull(schluessel, "Der Schlüssel darf nicht null sein.");
        hinzufuegenIntern(eintrag, schluessel);
    }

    public void hinzufuegen(HistoryEntry eintrag) {
        Objects.requireNonNull(eintrag, "Der History-Eintrag darf nicht null sein.");
        UUID schluessel = schluesselFuer(eintrag.ziel());
        hinzufuegenIntern(eintrag, schluessel);
    }

    private void hinzufuegenIntern(HistoryEntry eintrag, UUID schluessel) {

        boolean istAufhebung = eintrag.typ().istAufhebung();

        StoreLock.LOCK.writeLock().lock();
        try {
            final List<HistoryEntry> altSnapshot = List.copyOf(
                    eintraege.getOrDefault(schluessel, List.of()).stream()
                            .filter(java.util.Objects::nonNull).toList());
            eintraege.compute(schluessel, (schluesselWert, alte) -> {
                List<HistoryEntry> basis = (alte == null) ? List.of() : alte;
                List<HistoryEntry> aktualisiert = new ArrayList<>(basis.size() + 1);

                for (HistoryEntry bestehend : basis) {
                    if (bestehend == null) {
                        continue;
                    }
                    if (istAufhebung && wirdAufgehobenVon(bestehend, eintrag.typ(), eintrag.scope())) {
                        aktualisiert.add(bestehend.mitAktiv(false));
                    } else {
                        aktualisiert.add(bestehend);
                    }
                }

                aktualisiert.add(eintrag);

                return List.copyOf(aktualisiert);
            });
            if (mysql != null) {
                final List<HistoryEntry> neuListe = eintraege.getOrDefault(schluessel, List.of());
                final java.util.Set<Long> deaktiviert = deaktivierteIds(altSnapshot, neuListe);
                final HistoryEntry neuEintrag = eintrag;
                ioExecutor.ausfuehren(() -> speichereEintragInkrementellNachMysql(schluessel, neuEintrag, deaktiviert));
            }
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }

        behandleUeberschuss(schluessel);
        if (mysql == null) {
            persistiere(schluessel);
        }
    }

    public void hinzufuegenNeu(HistoryType typ, String ausfuehrer, UUID zielUuid,
                               String zielName, String grund, Long ablauf) {
        hinzufuegenNeu(typ, ausfuehrer, zielUuid, zielName, grund, ablauf,
                ServerScope.UNBEKANNT_WERT, ServerScope.UNBEKANNT_WERT);
    }

    public void hinzufuegenNeu(HistoryType typ, String ausfuehrer, UUID zielUuid,
                               String zielName, String grund, Long ablauf,
                               String scope, String herkunft) {
        Objects.requireNonNull(typ, "Der History-Typ darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        StoreLock.LOCK.writeLock().lock();
        HistoryEntry eintrag;
        try {
            long id = zaehler.incrementAndGet();
            long zeitstempel = System.currentTimeMillis();
            Long sichererAblauf = ablauf;
            if (sichererAblauf != null && sichererAblauf <= zeitstempel) {
                sichererAblauf = zeitstempel + 1L;
            }
            eintrag = new HistoryEntry(id, typ, ausfuehrer, zielName,
                    grund == null ? "" : grund, zeitstempel, sichererAblauf, true,
                    scope, herkunft);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
        hinzufuegenIntern(eintrag, zielUuid);
    }

    public void hinzufuegenNeu(HistoryType typ, String ausfuehrer, String ziel,
                               String grund, Long ablauf) {
        hinzufuegenNeu(typ, ausfuehrer, ziel, grund, ablauf,
                ServerScope.UNBEKANNT_WERT, ServerScope.UNBEKANNT_WERT);
    }

    public void hinzufuegenNeu(HistoryType typ, String ausfuehrer, String ziel,
                               String grund, Long ablauf, String scope, String herkunft) {
        Objects.requireNonNull(typ, "Der History-Typ darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Objects.requireNonNull(ziel, "Das Ziel darf nicht null sein.");
        UUID schluessel = schluesselFuer(ziel);
        StoreLock.LOCK.writeLock().lock();
        HistoryEntry eintrag;
        try {
            long id = zaehler.incrementAndGet();
            long zeitstempel = System.currentTimeMillis();
            Long sichererAblauf = ablauf;
            if (sichererAblauf != null && sichererAblauf <= zeitstempel) {
                sichererAblauf = zeitstempel + 1L;
            }
            eintrag = new HistoryEntry(id, typ, ausfuehrer, ziel,
                    grund == null ? "" : grund, zeitstempel, sichererAblauf, true,
                    scope, herkunft);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
        hinzufuegenIntern(eintrag, schluessel);
    }

    public List<HistoryEntry> finden(UUID schluessel, int limit) {
        Objects.requireNonNull(schluessel, "Der Schlüssel darf nicht null sein.");
        if (limit <= 0) return List.of();
        List<HistoryEntry> liste = eintraege.get(schluessel);
        if (liste == null || liste.isEmpty()) return List.of();
        return extrahiereNeueste(liste, limit);
    }

    public List<HistoryEntry> findeAlle(int limit) {
        if (limit <= 0) return List.of();
        int cap = Math.min(limit, 5000);
        PriorityQueue<HistoryEntry> queue = new PriorityQueue<>(
                Math.max(11, cap + 1), Comparator.comparingLong((HistoryEntry entry) -> Objects.requireNonNull(entry).zeitstempel())
                        .thenComparingLong(eintrag -> Objects.requireNonNull(eintrag).id()));
        for (List<HistoryEntry> liste : eintraege.values()) {
            if (liste == null) {
                continue;
            }
            for (HistoryEntry eintrag : liste) {
                if (eintrag == null) {
                    continue;
                }
                queue.offer(eintrag);
                if (queue.size() > cap) queue.poll();
            }
        }
        return umkehren(queue);
    }

    private List<HistoryEntry> extrahiereNeueste(List<HistoryEntry> liste, int limit) {
        int cap = Math.min(limit, 5000);
        PriorityQueue<HistoryEntry> queue = new PriorityQueue<>(
                Math.max(11, cap + 1), Comparator.comparingLong((HistoryEntry entry) -> Objects.requireNonNull(entry).zeitstempel())
                        .thenComparingLong(eintrag -> Objects.requireNonNull(eintrag).id()));
        for (HistoryEntry eintrag : liste) {
            if (eintrag == null) {
                continue;
            }
            queue.offer(eintrag);
            if (queue.size() > cap) queue.poll();
        }
        return umkehren(queue);
    }

    private UUID schluesselFuer(String ziel) {
        try {
            return UUID.fromString(ziel);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(ziel.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean scopePasstFuerAufhebung(String bestehendScope, String aufhebungScope) {
        String aufhebung = aufhebungScope == null ? "" : aufhebungScope.trim().toLowerCase(java.util.Locale.ROOT);

        if (aufhebung.isEmpty()
                || aufhebung.equals(de.lightrixx.punish.model.ServerScope.GLOBAL_WERT)
                || aufhebung.equals(de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT)) {
            return true;
        }
        String bestehend = bestehendScope == null ? "" : bestehendScope.trim().toLowerCase(java.util.Locale.ROOT);
        return bestehend.equals(aufhebung);
    }

    private boolean wirdAufgehobenVon(HistoryEntry bestehend, HistoryType aufhebung,
                                      String aufhebungScope) {
        if (!bestehend.aktiv()) return false;
        if (!scopePasstFuerAufhebung(bestehend.scope(), aufhebungScope)) return false;
        return switch (aufhebung) {
            case PARDON, PARDON_ALL -> bestehend.typ() == HistoryType.BAN
                    || bestehend.typ() == HistoryType.BAN_EDIT
                    || bestehend.typ() == HistoryType.PERMABANN
                    || bestehend.typ() == HistoryType.AUTO_BANN
                    || bestehend.typ() == HistoryType.BAN_ALL;
            case BAN_EDIT -> bestehend.typ() == HistoryType.BAN
                    || bestehend.typ() == HistoryType.BAN_EDIT
                    || bestehend.typ() == HistoryType.PERMABANN
                    || bestehend.typ() == HistoryType.BAN_ALL;
            case MUTE_EDIT -> bestehend.typ() == HistoryType.MUTE
                    || bestehend.typ() == HistoryType.MUTE_EDIT;
            case TIMEOUT_EDIT -> bestehend.typ() == HistoryType.TIMEOUT
                    || bestehend.typ() == HistoryType.TIMEOUT_EDIT;
            case IP_BAN_EDIT -> bestehend.typ() == HistoryType.BAN_IP
                    || bestehend.typ() == HistoryType.IP_BAN_EDIT;
            case UNMUTE, UNMUTE_ALL -> bestehend.typ() == HistoryType.MUTE
                    || bestehend.typ() == HistoryType.MUTE_EDIT
                    || bestehend.typ() == HistoryType.MUTE_ALL;
            case UNTIMEOUT, UNTIMEOUT_ALL -> bestehend.typ() == HistoryType.TIMEOUT
                    || bestehend.typ() == HistoryType.TIMEOUT_EDIT
                    || bestehend.typ() == HistoryType.TIMEOUT_ALL;
            case UNFREEZE -> bestehend.typ() == HistoryType.FREEZE;
            case UNBAN_IP -> bestehend.typ() == HistoryType.BAN_IP
                    || bestehend.typ() == HistoryType.IP_BAN_EDIT;
            case IP_UNMUTE -> bestehend.typ() == HistoryType.IP_MUTE
                    || bestehend.typ() == HistoryType.IP_MUTE_EDIT;
            case IP_MUTE_EDIT -> bestehend.typ() == HistoryType.IP_MUTE
                    || bestehend.typ() == HistoryType.IP_MUTE_EDIT;
            case IP_UNTIMEOUT -> bestehend.typ() == HistoryType.IP_TIMEOUT
                    || bestehend.typ() == HistoryType.IP_TIMEOUT_EDIT;
            case IP_TIMEOUT_EDIT -> bestehend.typ() == HistoryType.IP_TIMEOUT
                    || bestehend.typ() == HistoryType.IP_TIMEOUT_EDIT;
            case AUTO_PARDON, AUTO_BANN_EXPIRED -> bestehend.typ() == HistoryType.AUTO_BANN;
            case RESET -> bestehend.typ() == HistoryType.AUTO_BANN
                    || bestehend.typ() == HistoryType.VERSTOSS_ADD
                    || bestehend.typ() == HistoryType.VERSTOSS_EDIT
                    || bestehend.typ() == HistoryType.VERSTOSS_REMOVE;
            default -> false;
        };
    }

    public Map<UUID, List<HistoryEntry>> alleSchluessel() {
        Map<UUID, List<HistoryEntry>> kopie = new java.util.HashMap<>();
        eintraege.forEach((schluessel, liste) -> {
            if (schluessel != null && liste != null && !liste.isEmpty()) {
                kopie.put(schluessel, List.copyOf(liste));
            }
        });
        return Map.copyOf(kopie);
    }

    public Map<UUID, List<HistoryEntry>> archivEintraege() {
        if (historyOrdner == null) {
            return Map.of();
        }
        File archivOrdner = new File(historyOrdner, "archive");
        File[] dateien = archivOrdner.listFiles((verzeichnis, name) -> name != null && name.endsWith(".yml"));
        if (dateien == null || dateien.length == 0) {
            return Map.of();
        }
        Map<UUID, List<HistoryEntry>> ergebnis = new LinkedHashMap<>();
        for (File datei : dateien) {
            String name = datei.getName();
            if (name.length() < 36) {
                LOG.warn("History-Archivdatei {} trägt keine gültige UUID und wird übersprungen.", name);
                continue;
            }
            try {
                UUID schluessel = UUID.fromString(name.substring(0, 36));
                List<HistoryEntry> gelesen = ladenDatei(datei, new ArrayList<>());
                if (gelesen == null) {
                    LOG.warn("History-Archivdatei {} ist korrupt und wird übersprungen.", name);
                    continue;
                }
                if (gelesen.isEmpty()) {
                    continue;
                }
                ergebnis.merge(schluessel, gelesen, (links, rechts) -> {
                    List<HistoryEntry> zusammen = new ArrayList<>(links.size() + rechts.size());
                    zusammen.addAll(links);
                    zusammen.addAll(rechts);
                    return zusammen;
                });
            } catch (Exception e) {
                LOG.warn("History-Archivdatei {} konnte für die Migration nicht gelesen werden.", name, e);
            }
        }
        Map<UUID, List<HistoryEntry>> kopie = new LinkedHashMap<>();
        ergebnis.forEach((schluessel, liste) -> kopie.put(schluessel, List.copyOf(liste)));
        return Map.copyOf(kopie);
    }

    public int migrationImport(Map<UUID, List<HistoryEntry>> alle) {
        Objects.requireNonNull(alle, "Die History-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<UUID, List<HistoryEntry>> neu = new ConcurrentHashMap<>();
            long maxId = 0L;
            int anzahl = 0;
            for (Map.Entry<UUID, List<HistoryEntry>> eintrag : alle.entrySet()) {
                if (eintrag.getKey() == null || eintrag.getValue() == null
                        || eintrag.getValue().isEmpty()) {
                    continue;
                }
                List<HistoryEntry> kopie = List.copyOf(eintrag.getValue());
                neu.put(eintrag.getKey(), kopie);
                anzahl += kopie.size();
                for (HistoryEntry history : kopie) {
                    if (history != null && history.id() > maxId) {
                        maxId = history.id();
                    }
                }
            }
            eintraege = neu;
            zaehler.set(Math.max(zaehler.get(), maxId));
            for (Map.Entry<UUID, List<HistoryEntry>> eintrag : neu.entrySet()) {
                final UUID schluessel = eintrag.getKey();
                final List<HistoryEntry> snapshot = eintrag.getValue();
                ioExecutor.ausfuehren(() -> speichereShardNachMysql(schluessel, snapshot));
            }
            return anzahl;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public int migrationImportArchiv(Map<UUID, List<HistoryEntry>> alle) {
        Objects.requireNonNull(alle, "Das History-Archiv darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        if (alle.isEmpty()) {
            return 0;
        }
        int anzahl = 0;
        try (Connection verbindung = mysql.verbindung();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "INSERT IGNORE INTO " + MysqlSchema.T_HISTORY_ARCHIV
                             + " (id, schluessel, typ, ausfuehrer, ziel, grund, zeitstempel,"
                             + " ablauf, aktiv, scope, herkunft, archiviert_am)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            long jetzt = System.currentTimeMillis();
            for (Map.Entry<UUID, List<HistoryEntry>> eintrag : alle.entrySet()) {
                if (eintrag.getKey() == null || eintrag.getValue() == null) {
                    continue;
                }
                for (HistoryEntry history : eintrag.getValue()) {
                    if (history == null) {
                        continue;
                    }
                    einfuegen.setLong(1, history.id());
                    einfuegen.setString(2, eintrag.getKey().toString());
                    einfuegen.setString(3, history.typ().name());
                    einfuegen.setString(4, history.ausfuehrer());
                    einfuegen.setString(5, history.ziel());
                    einfuegen.setString(6, history.grund());
                    einfuegen.setLong(7, history.zeitstempel());
                    if (history.ablauf() == null) {
                        einfuegen.setNull(8, Types.BIGINT);
                    } else {
                        einfuegen.setLong(8, history.ablauf());
                    }
                    einfuegen.setBoolean(9, history.aktiv());
                    einfuegen.setString(10, history.scope());
                    einfuegen.setString(11, history.herkunft());
                    einfuegen.setLong(12, jetzt);
                    einfuegen.addBatch();
                    anzahl++;
                }
            }
            einfuegen.executeBatch();
            return anzahl;
        } catch (Exception e) {
            LOG.error("YAML-History-Archiv konnte nicht nach MySQL migriert werden ({} Einträge).",
                    anzahl, e);
            throw new IllegalStateException("Migration des History-Archivs fehlgeschlagen.", e);
        }
    }

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            int alteGroesse = eintraege.values().stream().filter(Objects::nonNull).mapToInt(List::size).sum();
            ConcurrentHashMap<UUID, List<HistoryEntry>> tmp = new ConcurrentHashMap<>();
            long maximaleId = 0L;
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT id, schluessel, typ, ausfuehrer, ziel, grund, zeitstempel, ablauf,"
                                 + " aktiv, scope, herkunft FROM " + MysqlSchema.T_HISTORY
                                 + " ORDER BY schluessel, id")) {
                while (ergebnis.next()) {
                    try {
                        HistoryEntry eintrag = zeileLesen(ergebnis);
                        UUID schluessel = UUID.fromString(ergebnis.getString("schluessel"));
                        tmp.computeIfAbsent(schluessel, k -> new ArrayList<>()).add(eintrag);
                        if (eintrag.id() > maximaleId) {
                            maximaleId = eintrag.id();
                        }
                    } catch (Exception e) {
                        LOG.error("Eine History-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("History konnte nicht aus MySQL geladen werden – Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            ConcurrentHashMap<UUID, List<HistoryEntry>> fertig = new ConcurrentHashMap<>();
            int gesamt = 0;
            for (Map.Entry<UUID, List<HistoryEntry>> eintrag : tmp.entrySet()) {
                if (!eintrag.getValue().isEmpty()) {
                    fertig.put(eintrag.getKey(), List.copyOf(eintrag.getValue()));
                    gesamt += eintrag.getValue().size();
                }
            }
            long finaleMax = maximaleId;
            zaehler.updateAndGet(aktuell -> Math.max(aktuell, finaleMax));
            eintraege = fertig;
            LOG.info("Es wurden {} History-Einträge aus MySQL geladen. Die nächste ID ist {}.",
                    gesamt, zaehler.get() + 1);
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    public static final long IP_RETENTION_MILLIS_DEFAULT = 90L * 24L * 60L * 60L * 1000L;

    public int anonymisiereAbgelaufeneIps(long jetztMillis, long fristMillis) {
        if (fristMillis <= 0) {
            return 0;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int geaendert = 0;
            java.util.Map<UUID, List<HistoryEntry>> betroffen = new java.util.HashMap<>();
            for (Map.Entry<UUID, List<HistoryEntry>> eintrag : eintraege.entrySet()) {
                if (eintrag.getKey() == null || eintrag.getValue() == null) {
                    continue;
                }
                List<HistoryEntry> neuListe = new ArrayList<>(eintrag.getValue().size());
                boolean shardGeaendert = false;
                for (HistoryEntry history : eintrag.getValue()) {
                    if (history == null) {
                        continue;
                    }
                    if (istIpEintrag(history) && jetztMillis - history.zeitstempel() > fristMillis
                            && !history.ziel().equals("anonymisiert (DSGVO)")) {
                        neuListe.add(new HistoryEntry(history.id(), history.typ(), history.ausfuehrer(),
                                "anonymisiert (DSGVO)", history.grund(), history.zeitstempel(),
                                history.ablauf(), history.aktiv(), history.scope(), history.herkunft()));
                        shardGeaendert = true;
                        geaendert++;
                    } else {
                        neuListe.add(history);
                    }
                }
                if (shardGeaendert) {
                    betroffen.put(eintrag.getKey(), List.copyOf(neuListe));
                }
            }
            for (Map.Entry<UUID, List<HistoryEntry>> eintrag : betroffen.entrySet()) {
                eintraege.put(eintrag.getKey(), eintrag.getValue());
                persistiere(eintrag.getKey());
            }
            if (geaendert > 0) {
                LOG.info("History-IP-Retention: {} Einträge (> {} Tage) anonymisiert.",
                        geaendert, fristMillis / (24L * 60L * 60L * 1000L));
            }
            return geaendert;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    static boolean istIpEintrag(HistoryEntry eintrag) {
        if (eintrag == null || eintrag.ziel() == null || eintrag.ziel().isBlank()) {
            return false;
        }
        String ziel = eintrag.ziel().trim();
        if (ziel.equals("anonymisiert (DSGVO)")) {
            return false;
        }
        try {
            de.lightrixx.punish.model.IpBanRecord.kanonisieren(ziel);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public int loescheIpEintraege(String ip) {
        if (ip == null || ip.isBlank()) {
            return 0;
        }
        String kanonisch;
        try {
            kanonisch = de.lightrixx.punish.model.IpBanRecord.kanonisieren(ip.trim());
        } catch (Exception e) {
            return 0;
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            int entfernt = 0;
            java.util.Map<UUID, List<HistoryEntry>> betroffen = new java.util.HashMap<>();
            java.util.List<Long> idsFuerMysql = new java.util.ArrayList<>();
            for (Map.Entry<UUID, List<HistoryEntry>> eintrag : eintraege.entrySet()) {
                if (eintrag.getKey() == null || eintrag.getValue() == null) {
                    continue;
                }
                List<HistoryEntry> verbleibend = new ArrayList<>();
                for (HistoryEntry history : eintrag.getValue()) {
                    if (history == null) {
                        continue;
                    }
                    boolean match = false;
                    try {
                        match = kanonisch.equals(de.lightrixx.punish.model.IpBanRecord.kanonisieren(history.ziel().trim()));
                    } catch (Exception ex) {
                        match = history.ziel() != null && history.ziel().trim().equalsIgnoreCase(kanonisch);
                    }
                    if (match) {
                        entfernt++;
                        idsFuerMysql.add(history.id());
                    } else {
                        verbleibend.add(history);
                    }
                }
                if (verbleibend.size() != eintrag.getValue().size()) {
                    betroffen.put(eintrag.getKey(), List.copyOf(verbleibend));
                }
            }
            for (Map.Entry<UUID, List<HistoryEntry>> eintrag : betroffen.entrySet()) {
                if (eintrag.getValue().isEmpty()) {
                    eintraege.remove(eintrag.getKey());
                } else {
                    eintraege.put(eintrag.getKey(), eintrag.getValue());
                }
                persistiere(eintrag.getKey());
            }
            if (mysql != null && !idsFuerMysql.isEmpty()) {
                final List<Long> ids = List.copyOf(idsFuerMysql);
                ioExecutor.ausfuehren(() -> loescheIdsNachMysql(ids));
            }
            if (entfernt > 0) {
                LOG.info("History-IP-Löschung für {}: {} Einträge entfernt.", kanonisch, entfernt);
            }
            return entfernt;
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    void loescheIdsNachMysql(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try (Connection verbindung = mysql.verbindung();
                PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_HISTORY + " WHERE id = ?")) {
            for (Long id : ids) {
                if (id == null) {
                    continue;
                }
                loeschen.setLong(1, id);
                loeschen.addBatch();
            }
            loeschen.executeBatch();
        } catch (Exception e) {
            LOG.warn("History-IP-Löschung nach MySQL fehlgeschlagen ({} IDs).", ids.size(), e);
            throw new IllegalStateException("History-IP-Löschung nach MySQL fehlgeschlagen ("
                    + ids.size() + " IDs).", e);
        }
    }

    static java.util.Set<Long> deaktivierteIds(List<HistoryEntry> alt, List<HistoryEntry> neu) {        if (alt == null || alt.isEmpty() || neu == null || neu.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Map<Long, Boolean> altAktiv = new java.util.HashMap<>();
        for (HistoryEntry eintrag : alt) {
            if (eintrag != null) {
                altAktiv.put(eintrag.id(), eintrag.aktiv());
            }
        }
        java.util.Set<Long> deaktiviert = new java.util.HashSet<>();
        for (HistoryEntry eintrag : neu) {
            if (eintrag == null) {
                continue;
            }
            Boolean vorher = altAktiv.get(eintrag.id());
            if (Boolean.TRUE.equals(vorher) && !eintrag.aktiv()) {
                deaktiviert.add(eintrag.id());
            }
        }
        return java.util.Set.copyOf(deaktiviert);
    }

    void speichereEintragInkrementellNachMysql(UUID schluessel, HistoryEntry neu,
                                              java.util.Collection<Long> deaktivierteIds) {
        java.util.Objects.requireNonNull(schluessel, "Der Schlüssel darf nicht null sein.");
        java.util.Objects.requireNonNull(neu, "Der History-Eintrag darf nicht null sein.");
        java.sql.Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            if (deaktivierteIds != null && !deaktivierteIds.isEmpty()) {
                try (java.sql.PreparedStatement update = verbindung.prepareStatement(
                        "UPDATE " + MysqlSchema.T_HISTORY + " SET aktiv = 0 WHERE id = ?")) {
                    for (Long id : deaktivierteIds) {
                        if (id == null) {
                            continue;
                        }
                        update.setLong(1, id);
                        update.addBatch();
                    }
                    update.executeBatch();
                }
            }
            try (java.sql.PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_HISTORY
                            + " (id, schluessel, typ, ausfuehrer, ziel, grund, zeitstempel,"
                            + " ablauf, aktiv, scope, herkunft)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE typ = VALUES(typ), ausfuehrer = VALUES(ausfuehrer),"
                            + " ziel = VALUES(ziel), grund = VALUES(grund), zeitstempel = VALUES(zeitstempel),"
                            + " ablauf = VALUES(ablauf), aktiv = VALUES(aktiv),"
                            + " scope = VALUES(scope), herkunft = VALUES(herkunft)")) {
                einfuegen.setLong(1, neu.id());
                einfuegen.setString(2, schluessel.toString());
                einfuegen.setString(3, neu.typ().name());
                einfuegen.setString(4, neu.ausfuehrer());
                einfuegen.setString(5, neu.ziel());
                einfuegen.setString(6, neu.grund());
                einfuegen.setLong(7, neu.zeitstempel());
                if (neu.ablauf() == null) {
                    einfuegen.setNull(8, Types.BIGINT);
                } else {
                    einfuegen.setLong(8, neu.ablauf());
                }
                einfuegen.setBoolean(9, neu.aktiv());
                einfuegen.setString(10, neu.scope());
                einfuegen.setString(11, neu.herkunft());
                einfuegen.executeUpdate();
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
            LOG.error("History-Eintrag {} für {} konnte nicht inkrementell nach MySQL geschrieben werden.",
                    neu.id(), schluessel, e);
            throw new IllegalStateException("History-Eintrag konnte nicht nach MySQL geschrieben werden.", e);
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

    private void speichereShardNachMysql(UUID schluessel, List<HistoryEntry> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "DELETE FROM " + MysqlSchema.T_HISTORY + " WHERE schluessel = ?")) {
                loeschen.setString(1, schluessel.toString());
                loeschen.executeUpdate();
            }
            if (snapshot != null && !snapshot.isEmpty()) {
                try (PreparedStatement einfuegen = verbindung.prepareStatement(
                        "INSERT INTO " + MysqlSchema.T_HISTORY
                                + " (id, schluessel, typ, ausfuehrer, ziel, grund, zeitstempel,"
                                + " ablauf, aktiv, scope, herkunft)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (HistoryEntry eintrag : snapshot) {
                        if (eintrag == null) {
                            continue;
                        }
                        einfuegen.setLong(1, eintrag.id());
                        einfuegen.setString(2, schluessel.toString());
                        einfuegen.setString(3, eintrag.typ().name());
                        einfuegen.setString(4, eintrag.ausfuehrer());
                        einfuegen.setString(5, eintrag.ziel());
                        einfuegen.setString(6, eintrag.grund());
                        einfuegen.setLong(7, eintrag.zeitstempel());
                        if (eintrag.ablauf() == null) {
                            einfuegen.setNull(8, Types.BIGINT);
                        } else {
                            einfuegen.setLong(8, eintrag.ablauf());
                        }
                        einfuegen.setBoolean(9, eintrag.aktiv());
                        einfuegen.setString(10, eintrag.scope());
                        einfuegen.setString(11, eintrag.herkunft());
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
            LOG.error("History für {} konnte nicht nach MySQL geschrieben werden.", schluessel, e);
            throw new IllegalStateException("History konnte nicht nach MySQL geschrieben werden.", e);
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

    private boolean archiviereNachMysql(UUID schluessel, List<HistoryEntry> snapshot) {
        Connection verbindung = null;
        boolean autoCommit = true;
        try {
            verbindung = mysql.verbindung();
            autoCommit = verbindung.getAutoCommit();
            verbindung.setAutoCommit(false);
            try (PreparedStatement einfuegen = verbindung.prepareStatement(
                    "INSERT IGNORE INTO " + MysqlSchema.T_HISTORY_ARCHIV
                            + " (id, schluessel, typ, ausfuehrer, ziel, grund, zeitstempel,"
                            + " ablauf, aktiv, scope, herkunft, archiviert_am)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                long jetzt = System.currentTimeMillis();
                for (HistoryEntry eintrag : snapshot) {
                    if (eintrag == null) {
                        continue;
                    }
                    einfuegen.setLong(1, eintrag.id());
                    einfuegen.setString(2, schluessel.toString());
                    einfuegen.setString(3, eintrag.typ().name());
                    einfuegen.setString(4, eintrag.ausfuehrer());
                    einfuegen.setString(5, eintrag.ziel());
                    einfuegen.setString(6, eintrag.grund());
                    einfuegen.setLong(7, eintrag.zeitstempel());
                    if (eintrag.ablauf() == null) {
                        einfuegen.setNull(8, Types.BIGINT);
                    } else {
                        einfuegen.setLong(8, eintrag.ablauf());
                    }
                    einfuegen.setBoolean(9, eintrag.aktiv());
                    einfuegen.setString(10, eintrag.scope());
                    einfuegen.setString(11, eintrag.herkunft());
                    einfuegen.setLong(12, jetzt);
                    einfuegen.addBatch();
                }
                einfuegen.executeBatch();
            }
            try (PreparedStatement loeschen = verbindung.prepareStatement(
                    "DELETE FROM " + MysqlSchema.T_HISTORY + " WHERE id = ?")) {
                for (HistoryEntry eintrag : snapshot) {
                    if (eintrag == null) {
                        continue;
                    }
                    loeschen.setLong(1, eintrag.id());
                    loeschen.addBatch();
                }
                loeschen.executeBatch();
            }
            verbindung.commit();
            return true;
        } catch (Exception e) {
            try {
                if (verbindung != null) {
                    verbindung.rollback();
                }
            } catch (Exception rollbackFehler) {
                LOG.debug("Rollback der History-Archivierung fehlgeschlagen.", rollbackFehler);
            }
            LOG.warn("History-Archivierung für {} nach MySQL fehlgeschlagen ({} Einträge).",
                    schluessel, snapshot.size(), e);
            return false;
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

    private static HistoryEntry zeileLesen(ResultSet ergebnis) throws Exception {
        HistoryType typ;
        try {
            typ = HistoryType.valueOf(ergebnis.getString("typ"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unbekannter History-Typ.", e);
        }
        Object ablaufRoh = ergebnis.getObject("ablauf");
        Long ablauf = ablaufRoh instanceof Number zahl ? zahl.longValue() : null;
        return new HistoryEntry(ergebnis.getLong("id"),
                typ,
                ergebnis.getString("ausfuehrer"),
                ergebnis.getString("ziel"),
                ergebnis.getString("grund"),
                ergebnis.getLong("zeitstempel"),
                ablauf,
                ergebnis.getBoolean("aktiv"),
                ergebnis.getString("scope"),
                ergebnis.getString("herkunft"));
    }

    private void behandleUeberschuss(UUID schluessel) {
        List<HistoryEntry> momentan = eintraege.get(schluessel);
        if (momentan == null || momentan.size() <= MAX_EINTRAEGE_PRO_ZIEL) {
            return;
        }
        if (mysql != null) {
            ioExecutor.ausfuehren(() -> verarbeiteUeberschussNachMysql(schluessel));
            return;
        }
        final List<HistoryEntry> snapshot = List.copyOf(
                momentan.subList(0, momentan.size() - MAX_EINTRAEGE_PRO_ZIEL));
        if (!archiviereUeberschussDauerhaft(schluessel, snapshot)) {
            LOG.error("History-Archivierung für {} fehlgeschlagen – {} Einträge bleiben entgegen dem Limit erhalten.",
                    schluessel, snapshot.size());
            return;
        }
        kuerzeArchivierteEintraege(schluessel, snapshot);
        LOG.warn("History für {} überschreitet {} Einträge – {} älteste Einträge nach history/archive/ ausgelagert.",
                schluessel, MAX_EINTRAEGE_PRO_ZIEL, snapshot.size());
    }

    private void verarbeiteUeberschussNachMysql(UUID schluessel) {
        List<HistoryEntry> momentan = eintraege.get(schluessel);
        if (momentan == null || momentan.size() <= MAX_EINTRAEGE_PRO_ZIEL) {
            return;
        }
        List<HistoryEntry> ueberschuss = List.copyOf(
                momentan.subList(0, momentan.size() - MAX_EINTRAEGE_PRO_ZIEL));
        if (archiviereNachMysql(schluessel, ueberschuss)) {
            kuerzeArchivierteEintraege(schluessel, ueberschuss);
            LOG.warn("History für {} überschreitet {} Einträge – {} älteste Einträge ins MySQL-Archiv ausgelagert.",
                    schluessel, MAX_EINTRAEGE_PRO_ZIEL, ueberschuss.size());
        } else {
            LOG.error("History-Archivierung für {} nach MySQL fehlgeschlagen – {} Einträge bleiben entgegen dem Limit erhalten.",
                    schluessel, ueberschuss.size());
        }
    }

    private void kuerzeArchivierteEintraege(UUID schluessel, List<HistoryEntry> archiviert) {
        StoreLock.LOCK.writeLock().lock();
        try {
            eintraege.computeIfPresent(schluessel, (k, liste) -> {
                if (liste == null) {
                    return null;
                }
                int passend = 0;
                while (passend < archiviert.size() && passend < liste.size()) {
                    HistoryEntry element = liste.get(passend);
                    if (element == null || element.id() != archiviert.get(passend).id()) {
                        break;
                    }
                    passend++;
                }
                if (passend != archiviert.size()) {
                    return liste;
                }
                return List.copyOf(liste.subList(passend, liste.size()));
            });
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private boolean archiviereUeberschussDauerhaft(UUID schluessel, List<HistoryEntry> zuArchivieren) {
        final List<HistoryEntry> snapshot = List.copyOf(zuArchivieren);
        try {
            File archivOrdner = new File(historyOrdner, "archive");
            if (!archivOrdner.exists() && !archivOrdner.mkdirs() && !archivOrdner.exists()) {
                LOG.warn("History-Archivordner {} konnte nicht angelegt werden.", archivOrdner);
                return false;
            }
            String dateiName = schluessel + "-" + System.currentTimeMillis()
                    + "-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".yml";
            File archivDatei = new File(archivOrdner, dateiName);
            yamlSpeicher.speichern(konfigurationErstellen(snapshot, java.util.List.of()), archivDatei);
            if (!archivDatei.exists()) {
                LOG.warn("History-Archivierung für {} wurde nicht bestätigt (Archivdatei fehlt).", schluessel);
                return false;
            }
            rotiereArchiv(archivOrdner);
            return true;
        } catch (Exception e) {
            LOG.warn("History-Archivierung für {} fehlgeschlagen ({} Einträge).", schluessel, snapshot.size(), e);
            return false;
        }
    }

    private static void rotiereArchiv(File archivOrdner) {
        try {
            File[] dateien = archivOrdner.listFiles((dir, name) -> name.endsWith(".yml"));
            if (dateien == null || dateien.length <= 200) {
                return;
            }
            java.util.Arrays.sort(dateien,
                    java.util.Comparator.comparingLong(File::lastModified));
            int zuLoeschen = dateien.length - 200;
            for (int i = 0; i < zuLoeschen; i++) {
                try {
                    if (!dateien[i].delete()) {
                        LOG.warn("Alte History-Archivdatei {} konnte nicht gelöscht werden.",
                                dateien[i].getName());
                        break;
                    }
                } catch (Exception e) {
                    LOG.warn("History-Archiv-Rotation fehlgeschlagen.", e);
                    break;
                }
            }
            LOG.info("History-Archiv rotiert: {} alte Dateien gelöscht.", zuLoeschen);
        } catch (Exception e) {
            LOG.warn("History-Archiv-Rotation fehlgeschlagen.", e);
        }
    }

    private void persistiere(UUID schluessel) {
        StoreLock.LOCK.writeLock().lock();
        try {
            List<HistoryEntry> momentan = eintraege.get(schluessel);
            final List<HistoryEntry> snapshot = (momentan == null) ? null : List.copyOf(momentan);
            if (mysql != null) {
                final List<HistoryEntry> effektiv = (snapshot == null) ? List.of() : snapshot;
                ioExecutor.ausfuehren(() -> speichereShardNachMysql(schluessel, effektiv));
                return;
            }
            java.util.List<Object> roh = rohQuarantaene.get(schluessel);
            final java.util.List<Object> rohSnapshot = (roh == null) ? java.util.List.of() : List.copyOf(roh);
            final File datei = dateiFuer(schluessel);
            if ((snapshot == null || snapshot.isEmpty()) && rohSnapshot.isEmpty()) {
                ioExecutor.ausfuehrenMitSchluessel(datei.getPath(), () -> sichereLoeschung(datei));
                return;
            }
            final java.util.List<HistoryEntry> effektiv =
                    (snapshot == null) ? java.util.List.of() : snapshot;
            final Map<String, Object> konfiguration = konfigurationErstellen(effektiv, rohSnapshot);
            ioExecutor.ausfuehrenMitSchluessel(datei.getPath(), () -> yamlSpeicher.speichern(konfiguration, datei));
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private File dateiFuer(UUID schluessel) {
        return new File(historyOrdner, schluessel + ".yml");
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
                LOG.debug("History-Datei {} als {} gesichert.", datei.getName(), backup.getFileName());
                rotiereDeletedBackups(datei.getParentFile(), datei.getName());
            } catch (java.io.IOException e) {
                if (!datei.delete()) {
                    LOG.warn("Die History-Datei {} konnte nicht gelöscht werden.", datei.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOG.warn("Sichere Löschung von {} fehlgeschlagen.", datei, e);
        }
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

    private List<HistoryEntry> ladenDatei(File datei, java.util.List<Object> quarantaene) {
        YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
        if (ladeStatus.fehler()) {
            return null;
        }
        Map<String, Object> daten = ladeStatus.daten();
        java.util.List<?> rohEintraege = Karten.liste(daten, "eintraege");
        if (rohEintraege == null) {
            rohEintraege = java.util.List.of();
        }
        List<HistoryEntry> ergebnis = new ArrayList<>();

        for (Object rohObj : rohEintraege) {
            Map<String, Object> roh = Karten.abschnitt(rohObj);
            if (roh == null) {
                LOG.error("Ein History-Eintrag in {} ist korrupt (kein Map) und wird QUARANTIERT (nicht gelöscht).",
                        datei.getName());
                if (rohObj != null) {
                    quarantaene.add(rohObj);
                }
                continue;
            }
            try {
                long id = roh.get("id") instanceof Number zahl ? zahl.longValue() : 0;
                String typName = roh.get("typ") instanceof String wert ? wert : null;
                HistoryType typ;
                try {
                    typ = HistoryType.valueOf(typName);
                } catch (Exception ex) {
                    LOG.error("Unbekannter History-Typ '{}' in {} – Eintrag wird QUARANTIERT (nicht gelöscht, Downgrade-Schutz).",
                            typName, datei.getName());
                    quarantaene.add(rohObj);
                    continue;
                }
                String ausfuehrer = roh.get("ausfuehrer") instanceof String wert ? wert : "Unbekannt";
                String ziel = roh.get("ziel") instanceof String wert ? wert : "";
                String grund = roh.get("grund") instanceof String wert ? wert : "";
                long zeitstempel = roh.get("zeitstempel") instanceof Number zahl ? zahl.longValue() : 0L;
                Long ablauf = roh.get("ablauf") instanceof Number zahl ? zahl.longValue() : null;
                boolean aktiv = toBoolean(roh.get("aktiv"), true);

                String scope = roh.get("scope") instanceof String wert ? wert : ServerScope.UNBEKANNT_WERT;
                String herkunft = roh.get("herkunft") instanceof String wert ? wert : ServerScope.UNBEKANNT_WERT;
                ergebnis.add(new HistoryEntry(id, typ, ausfuehrer, ziel, grund, zeitstempel, ablauf, aktiv,
                        scope, herkunft));
            } catch (Exception e) {
                LOG.error("Ein History-Eintrag in {} ist korrupt und wird QUARANTIERT (nicht gelöscht). "
                                + "Bitte YAML prüfen/fixen – Eintrag wird bei jedem Save unverändert "
                                + "zurückgeschrieben.",
                        datei.getName(), e);
                quarantaene.add(rohObj);
            }
        }
        return ergebnis;
    }

    private Map<String, Object> konfigurationErstellen(List<HistoryEntry> liste, java.util.List<?> rohQuarantaeneListe) {
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        List<Object> rohEintraege = new ArrayList<>();
        for (HistoryEntry eintrag : liste) {
            if (eintrag == null) {
                continue;
            }
            Map<String, Object> roh = new java.util.LinkedHashMap<>();
            roh.put("id", eintrag.id());
            roh.put("typ", eintrag.typ().name());
            roh.put("ausfuehrer", eintrag.ausfuehrer());
            roh.put("ziel", eintrag.ziel());
            roh.put("grund", eintrag.grund());
            roh.put("zeitstempel", eintrag.zeitstempel());
            if (eintrag.ablauf() != null) roh.put("ablauf", eintrag.ablauf());
            roh.put("aktiv", eintrag.aktiv());
            roh.put("scope", eintrag.scope());
            roh.put("herkunft", eintrag.herkunft());
            rohEintraege.add(roh);
        }
        if (rohQuarantaeneListe != null) {
            for (Object roh : rohQuarantaeneListe) {
                if (roh == null) {
                    continue;
                }
                try {
                    rohEintraege.add(roh);
                } catch (Exception e) {
                    LOG.warn("Quarantäne-Eintrag konnte nicht zurückgeschrieben werden.", e);
                }
            }
        }
        root.put("eintraege", rohEintraege);
        return root;
    }

    private List<HistoryEntry> umkehren(PriorityQueue<HistoryEntry> queue) {
        List<HistoryEntry> ergebnis = new ArrayList<>(queue.size());
        while (!queue.isEmpty()) {
            HistoryEntry naechster = queue.poll();
            if (naechster != null) {
                ergebnis.add(naechster);
            }
        }
        List<HistoryEntry> umgekehrt = new ArrayList<>(ergebnis.size());
        for (int i = ergebnis.size() - 1; i >= 0; i--) umgekehrt.add(ergebnis.get(i));
        return umgekehrt;
    }

    private static boolean toBoolean(Object roh, boolean standard) {
        if (roh instanceof Boolean b) {
            return b;
        }
        if (roh instanceof Number n) {
            return n.intValue() != 0;
        }
        if (roh instanceof String s) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("true") || t.equals("yes") || t.equals("on") || t.equals("1")) {
                return true;
            }
            if (t.equals("false") || t.equals("no") || t.equals("off") || t.equals("0")) {
                return false;
            }
        }
        return standard;
    }
}
