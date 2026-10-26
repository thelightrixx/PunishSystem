package de.lightrixx.punish.storage;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerCache {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerCache.class);
    private static final String DATEI_NAME = "player_cache.yml";
    private static final String SCHLUESSEL_NAME = "name";
    private static final String SCHLUESSEL_LETZTE_IP = "letzte-ip";
    private static final String SCHLUESSEL_ZULETZT_GESEHEN = "zuletzt-gesehen";

    private final ConcurrentHashMap<UUID, CachedPlayer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> namenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.Set<UUID>> ipIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<UUID, IpHistorienEintrag>> ipHistorie =
            new ConcurrentHashMap<>();

    public record IpHistorienEintrag(String ip, UUID uuid, String name, long zuletztGesehen) {
        public IpHistorienEintrag {
            Objects.requireNonNull(ip, "Die IP darf nicht null sein.");
            Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
            if (name == null || name.isBlank()) {
                name = "Unbekannt";
            }
        }

        CachedPlayer alsSpieler() {
            return new CachedPlayer(uuid, name, ip, zuletztGesehen);
        }
    }

    private final ConcurrentHashMap<UUID, Long> letztePersistierung = new ConcurrentHashMap<>();
    private static final long PERSISTENZ_THROTTLE_MILLIS = 10L * 60L * 1000L;

    private final ConcurrentHashMap<String, Long> negativCache = new ConcurrentHashMap<>();
    private static final long NEGATIV_TTL_MILLIS = 60L * 1000L;
    private static final int MAX_CACHE_GROESSE = 25000;
    private static final long NAMENS_TTL_MILLIS = 37L * 24L * 60L * 60L * 1000L;

    private static boolean istNamensZuordnungVeraltet(CachedPlayer spieler) {
        try {
            return spieler != null
                    && System.currentTimeMillis() - spieler.zuletztGesehen() > NAMENS_TTL_MILLIS;
        } catch (Exception e) {
            return false;
        }
    }

    private volatile boolean allowNeverSeen = false;

    public void setAllowNeverSeen(boolean allowNeverSeen) {
        this.allowNeverSeen = allowNeverSeen;
    }

    public boolean istAllowNeverSeen() {
        return allowNeverSeen;
    }

    private volatile ConcurrentHashMap<String, Object> rohQuarantaene = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong revalidierungenAngestossen =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile java.util.function.Consumer<String> revalidierungsBeobachter;

    public long anzahlRevalidierungenAngestossen() {
        return revalidierungenAngestossen.get();
    }

    public void setRevalidierungsBeobachter(java.util.function.Consumer<String> beobachter) {
        this.revalidierungsBeobachter = beobachter;
    }

    public static final long IP_RETENTION_MILLIS_DEFAULT = 90L * 24L * 60L * 60L * 1000L;
    private volatile long ipRetentionMillis = IP_RETENTION_MILLIS_DEFAULT;

    public void setIpRetentionTage(int tage) {
        if (tage < 1 || tage > 3650) {
            this.ipRetentionMillis = IP_RETENTION_MILLIS_DEFAULT;
            return;
        }
        this.ipRetentionMillis = (long) tage * 24L * 60L * 60L * 1000L;
    }

    public int sweepAbgelaufeneIps() {
        return sweepAbgelaufeneIps(System.currentTimeMillis(), ipRetentionMillis);
    }

    public int sweepAbgelaufeneIps(long jetztMillis, long fristMillis) {
        if (fristMillis <= 0) {
            return 0;
        }
        java.util.List<CachedPlayer> persistieren = new java.util.ArrayList<>();
        int anonymisiert = 0;
        synchronized (cacheLock) {
            for (CachedPlayer alt : java.util.List.copyOf(cache.values())) {
                if (alt == null || alt.letzteIp() == null
                        || alt.letzteIp().isBlank()
                        || "unbekannt".equalsIgnoreCase(alt.letzteIp().trim())) {
                    continue;
                }
                try {
                    if (jetztMillis - alt.zuletztGesehen() <= fristMillis) {
                        continue;
                    }
                    CachedPlayer neu = new CachedPlayer(alt.uuid(), alt.name(), "unbekannt",
                            alt.zuletztGesehen());
                    if (!cache.replace(alt.uuid(), alt, neu)) {
                        continue;
                    }
                    ipIndexEntfernen(alt);
                    negativCache.remove(alt.name().toLowerCase(java.util.Locale.ROOT));
                    anonymisiert++;
                    if (mysql != null) {
                        persistieren.add(neu);
                    }
                } catch (Exception e) {
                    LOG.debug("IP-Retention für {} fehlgeschlagen.", alt.uuid(), e);
                }
            }
        }
        if (!persistieren.isEmpty()) {
            for (CachedPlayer snapshot : persistieren) {
                ioExecutor.ausfuehren(() -> speichereSpielerNachMysql(snapshot, java.util.List.of()));
            }
        } else if (anonymisiert > 0) {
            speichern();
        }
        if (anonymisiert > 0) {
            LOG.info("IP-Retention: {} abgelaufene IPs (> {} Tage) anonymisiert.",
                    anonymisiert, fristMillis / (24L * 60L * 60L * 1000L));
        }
        sweepIpHistorie(jetztMillis, fristMillis);
        return anonymisiert;
    }

    private void sweepIpHistorie(long jetztMillis, long fristMillis) {
        if (ipHistorie.isEmpty()) {
            return;
        }
        long grenze = jetztMillis - fristMillis;
        int entfernt = 0;
        for (Map.Entry<String, ConcurrentHashMap<UUID, IpHistorienEintrag>> eintrag : ipHistorie.entrySet()) {
            ConcurrentHashMap<UUID, IpHistorienEintrag> uuids = eintrag.getValue();
            int vorher = uuids.size();
            uuids.entrySet().removeIf(kandidat -> kandidat.getValue() == null
                    || kandidat.getValue().zuletztGesehen() < grenze);
            entfernt += vorher - uuids.size();
            if (uuids.isEmpty()) {
                ipHistorie.remove(eintrag.getKey(), uuids);
            }
        }
        if (entfernt <= 0) {
            return;
        }
        LOG.info("IP-Retention: {} abgelaufene IP-Historien-Einträge entfernt.", entfernt);
        if (mysql != null) {
            ioExecutor.ausfuehren(() -> {
                try (Connection verbindung = mysql.verbindung();
                     PreparedStatement loeschen = verbindung.prepareStatement(
                             "DELETE FROM " + MysqlSchema.T_IP_HISTORIE + " WHERE zuletzt_gesehen < ?")) {
                    loeschen.setLong(1, grenze);
                    loeschen.executeUpdate();
                } catch (Exception e) {
                    LOG.warn("Abgelaufene IP-Historie konnte nicht aus MySQL entfernt werden.", e);
                }
            });
        }
    }
    private final Object cacheLock = new Object();
    private final File datei;
    private final YamlSpeicher yamlSpeicher;
    private final IoExecutor ioExecutor;
    private final OfflineVerzeichnis offlineVerzeichnis;
    private final MysqlVerbindung mysql;

    public PlayerCache(File pluginDatenOrdner, YamlSpeicher yamlSpeicher, IoExecutor ioExecutor,
                       OfflineVerzeichnis offlineVerzeichnis) {
        Objects.requireNonNull(pluginDatenOrdner, "Der Datenordner darf nicht null sein.");
        this.yamlSpeicher = Objects.requireNonNull(yamlSpeicher, "Der YAML-Speicher darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.offlineVerzeichnis = Objects.requireNonNull(offlineVerzeichnis,
                "Das Offline-Verzeichnis darf nicht null sein.");
        this.datei = new File(pluginDatenOrdner, DATEI_NAME);
        this.mysql = null;
    }

    public PlayerCache(MysqlVerbindung mysql, IoExecutor ioExecutor,
                       OfflineVerzeichnis offlineVerzeichnis) {
        this.mysql = Objects.requireNonNull(mysql, "Die MySQL-Verbindung darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.offlineVerzeichnis = Objects.requireNonNull(offlineVerzeichnis,
                "Das Offline-Verzeichnis darf nicht null sein.");
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
                LOG.warn("Cache-Reload ohne Idle-Barriere (Queue={}) – stale-Save-Race möglich.",
                        ioExecutor.anzahlAusstehend());
            }
        } catch (Exception e) {
            LOG.debug("Idle-Wait vor Cache-Reload übersprungen.", e);
        }
        if (mysql != null) {
            ladeAusMysql();
            sweepAbgelaufeneIps();
            return;
        }
        Map<String, Object> daten;
        StoreLock.LOCK.writeLock().lock();
        try {
            YamlSpeicher.LadeStatus ladeStatus = YamlSpeicher.status(yamlSpeicher, datei);
            daten = ladeStatus.daten();
            ConcurrentHashMap<UUID, CachedPlayer> tmpCache = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, UUID> tmpNamen = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Object> tmpRoh = new ConcurrentHashMap<>();
            for (String schluessel : daten.keySet()) {
                try {
                    UUID uuid = UUID.fromString(schluessel);
                    Map<String, Object> abschnitt = Karten.abschnitt(daten, schluessel);
                    if (abschnitt == null) {
                        throw new IllegalArgumentException("Kein Abschnitt für Schlüssel " + schluessel);
                    }
                    String name = Karten.text(abschnitt, SCHLUESSEL_NAME, "Unbekannt");
                    String letzteIp = Karten.text(abschnitt, SCHLUESSEL_LETZTE_IP, "unbekannt");
                    if (letzteIp == null || letzteIp.isBlank()) {
                        letzteIp = "unbekannt";
                    }
                    long zuletztGesehen = Karten.zahl(abschnitt, SCHLUESSEL_ZULETZT_GESEHEN, 0L);
                    if (zuletztGesehen <= 0) {
                        zuletztGesehen = System.currentTimeMillis();
                    }
                    CachedPlayer spieler = new CachedPlayer(uuid, name, letzteIp, zuletztGesehen);
                    tmpCache.put(uuid, spieler);
                    tmpNamen.put(name.toLowerCase(Locale.ROOT), spieler.uuid());
                } catch (Exception e) {
                    LOG.error("Der Cache-Eintrag '{}' ist korrupt und wird QUARANTIERT (nicht gelöscht). "
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
            synchronized (cacheLock) {
                if (ladeStatus.fehler()) {
                    LOG.error("PlayerCache-Reload konnte die Datei nicht lesen ({} Einträge im Memory) – "
                                    + "halte Memory (korrupte Datei?), kein Wipe. Datei: {}",
                            cache.size(), datei.getAbsolutePath());
                    return;
                }

                cache.putAll(tmpCache);
                cache.keySet().removeIf(uuid -> !tmpCache.containsKey(uuid));
                namenCache.putAll(tmpNamen);
                namenCache.keySet().removeIf(name -> !tmpNamen.containsKey(name));
                rohQuarantaene = tmpRoh;
                ipIndexNeuBauen();
                if (!tmpRoh.isEmpty()) {
                    LOG.warn("{} korrupte PlayerCache-Einträge in Quarantäne (bleiben auf Disk erhalten).",
                            tmpRoh.size());
                }
            }
            LOG.info("Es wurden {} Spieler aus dem Player-Cache geladen.", cache.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
        sweepAbgelaufeneIps();
    }

    public void aktualisieren(CachedPlayer spieler) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        boolean dirty;
        boolean evictNoetig;
        synchronized (cacheLock) {
            UUID uuid = spieler.uuid();
            String neuerNameLower = spieler.name().toLowerCase(Locale.ROOT);

            UUID fremderBesitzer = namenCache.get(neuerNameLower);
            if (fremderBesitzer != null && !fremderBesitzer.equals(uuid)) {
                LOG.warn("Name '{}' wechselt von {} zu {} (Namenswiederverwendung). Alter Eintrag wird überschrieben.",
                        spieler.name(), fremderBesitzer, uuid);
            }
            CachedPlayer alt = cache.get(uuid);
            if (alt != null) {
                String alterNameLower = alt.name().toLowerCase(Locale.ROOT);
                if (!alterNameLower.equals(neuerNameLower)) {
                    namenCache.remove(alterNameLower, alt.uuid());
                }
            }

            dirty = alt == null
                    || !alt.name().equals(spieler.name())
                    || !Objects.equals(alt.letzteIp(), spieler.letzteIp());
            cache.put(uuid, spieler);
            namenCache.put(neuerNameLower, uuid);
            if (alt != null) {
                ipIndexEntfernen(alt);
            }
            ipIndexEintragen(spieler);
            ipHistorieEintragen(new IpHistorienEintrag(spieler.letzteIp(), uuid,
                    spieler.name(), spieler.zuletztGesehen()));

            negativCache.remove(neuerNameLower);
            if (negativCache.size() > 5000) {
                negativCache.clear();
            }
            evictNoetig = cache.size() > MAX_CACHE_GROESSE;
        }
        List<UUID> opfer = evictNoetig ? opferWaehlen() : List.of();
        List<UUID> entfernt = opfer.isEmpty() ? List.of() : evictOpfer(opfer);
        StoreLock.LOCK.writeLock().lock();
        try {
            if (!dirty) {
                Long zuletzt = letztePersistierung.get(spieler.uuid());
                long jetzt = System.currentTimeMillis();
                if (zuletzt != null && jetzt - zuletzt < PERSISTENZ_THROTTLE_MILLIS) {
                    return;
                }
                letztePersistierung.put(spieler.uuid(), jetzt);
            } else {
                letztePersistierung.put(spieler.uuid(), System.currentTimeMillis());
            }
            if (mysql != null) {
                final CachedPlayer snapshotSpieler = spieler;
                final List<UUID> opferKopie = List.copyOf(entfernt);
                ioExecutor.ausfuehren(() -> speichereSpielerNachMysql(snapshotSpieler, opferKopie));
                return;
            }
            speichern();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private List<UUID> opferWaehlen() {
        try {
            int ueberhang = cache.size() - MAX_CACHE_GROESSE;
            if (ueberhang <= 0) {
                return List.of();
            }
            List<Map.Entry<UUID, CachedPlayer>> snapshot = new ArrayList<>(cache.entrySet());
            snapshot.sort(java.util.Comparator.comparingLong(e -> {
                try {
                    CachedPlayer c = e.getValue();
                    return c == null ? Long.MAX_VALUE : c.zuletztGesehen();
                } catch (Exception ex) {
                    return Long.MAX_VALUE;
                }
            }));
            List<UUID> opfer = new ArrayList<>();
            for (Map.Entry<UUID, CachedPlayer> eintrag : snapshot) {
                if (opfer.size() >= ueberhang + 100) {
                    break;
                }
                if (eintrag == null || eintrag.getKey() == null) {
                    continue;
                }
                opfer.add(eintrag.getKey());
            }
            return opfer;
        } catch (Exception e) {
            LOG.warn("PlayerCache-Eviction-Auswahl fehlgeschlagen.", e);
            return List.of();
        }
    }

    private List<UUID> evictOpfer(List<UUID> opfer) {
        List<UUID> entfernt = new ArrayList<>();
        try {
            long schonfrist = System.currentTimeMillis() - 60000L;
            synchronized (cacheLock) {
                for (UUID uuid : opfer) {
                    if (uuid == null) {
                        continue;
                    }
                    CachedPlayer alt = cache.get(uuid);
                    if (alt == null || alt.zuletztGesehen() >= schonfrist) {
                        continue;
                    }
                    if (cache.remove(uuid) != null) {
                        try {
                            namenCache.remove(alt.name().toLowerCase(Locale.ROOT), uuid);
                        } catch (Exception ex) {
                            LOG.trace("NamenCache-Eviction übersprungen.", ex);
                        }
                        ipIndexEntfernen(alt);
                        letztePersistierung.remove(uuid);
                        entfernt.add(uuid);
                    }
                }
                if (!entfernt.isEmpty()) {
                    LOG.warn("PlayerCache-Eviction: {} älteste Einträge verdrängt (Cap {}).",
                            entfernt.size(), MAX_CACHE_GROESSE);
                }
            }
        } catch (Exception e) {
            LOG.warn("PlayerCache-Eviction fehlgeschlagen.", e);
        }
        return List.copyOf(entfernt);
    }

    public Optional<CachedPlayer> finden(UUID uuid) {
        Objects.requireNonNull(uuid, "Die Spieler-UUID darf nicht null sein.");
        return Optional.ofNullable(cache.get(uuid));
    }

    public Optional<CachedPlayer> findenNachName(String name) {
        Objects.requireNonNull(name, "Der Spielername darf nicht null sein.");
        String gesuchterName = name.trim().toLowerCase(Locale.ROOT);
        if (gesuchterName.isEmpty()) {
            return Optional.empty();
        }
        UUID uuid = namenCache.get(gesuchterName);
        if (uuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(uuid));
    }

    public boolean istFrischeNamensZuordnung(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Optional<CachedPlayer> gecacht = findenNachName(name);
        return gecacht.isPresent() && !istNamensZuordnungVeraltet(gecacht.get());
    }

    public List<CachedPlayer> findeNachIp(String ip) {
        if (ip == null || ip.isBlank() || "unbekannt".equalsIgnoreCase(ip.trim())) {
            return List.of();
        }
        String gesucht = ip.trim();
        String kanonisch = kanonischeIpAus(gesucht);
        if (kanonisch != null) {
            return mitKanonischerIp(kanonisch);
        }
        List<CachedPlayer> treffer = new ArrayList<>();
        for (CachedPlayer spieler : cache.values()) {
            if (spieler == null || spieler.letzteIp() == null) {
                continue;
            }
            String gespeichert = spieler.letzteIp().trim();
            if (gespeichert.equalsIgnoreCase("unbekannt") || gespeichert.isBlank()) {
                continue;
            }
            if (gespeichert.equalsIgnoreCase(gesucht)) {
                treffer.add(spieler);
            }
        }
        return treffer;
    }

    public List<CachedPlayer> mitKanonischerIp(String kanonischeIp) {
        if (kanonischeIp == null || kanonischeIp.isBlank() || "unbekannt".equalsIgnoreCase(kanonischeIp)) {
            return List.of();
        }
        java.util.Set<UUID> uuids = ipIndex.get(kanonischeIp);
        if (uuids == null || uuids.isEmpty()) {
            return List.of();
        }
        List<CachedPlayer> treffer = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) {
            CachedPlayer spieler = cache.get(uuid);
            if (spieler != null) {
                treffer.add(spieler);
            }
        }
        return treffer;
    }

    public List<CachedPlayer> findeNachIpMitHistorie(String ip) {
        if (mysql == null) {
            return findeNachIp(ip);
        }
        String kanonisch = kanonischeIpAus(ip);
        if (kanonisch == null) {
            return List.of();
        }
        Map<UUID, CachedPlayer> treffer = new LinkedHashMap<>();
        for (CachedPlayer spieler : mitKanonischerIp(kanonisch)) {
            treffer.put(spieler.uuid(), spieler);
        }
        ConcurrentHashMap<UUID, IpHistorienEintrag> eintraege = ipHistorie.get(kanonisch);
        if (eintraege != null) {
            for (IpHistorienEintrag eintrag : eintraege.values()) {
                CachedPlayer aktuell = cache.get(eintrag.uuid());
                treffer.putIfAbsent(eintrag.uuid(), aktuell != null ? aktuell : eintrag.alsSpieler());
            }
        }
        List<CachedPlayer> ergebnis = new ArrayList<>(treffer.values());
        ergebnis.sort(java.util.Comparator.comparingLong(CachedPlayer::zuletztGesehen).reversed());
        return ergebnis;
    }

    public List<String> historischeIpsFuer(UUID uuid) {
        Objects.requireNonNull(uuid, "Die Spieler-UUID darf nicht null sein.");
        java.util.Set<String> ips = new java.util.LinkedHashSet<>();
        CachedPlayer aktuell = cache.get(uuid);
        if (aktuell != null) {
            String kanonisch = kanonischeIpAus(aktuell.letzteIp());
            if (kanonisch != null) {
                ips.add(kanonisch);
            }
        }
        for (Map.Entry<String, ConcurrentHashMap<UUID, IpHistorienEintrag>> eintrag : ipHistorie.entrySet()) {
            if (eintrag.getValue().containsKey(uuid)) {
                ips.add(eintrag.getKey());
            }
        }
        return List.copyOf(ips);
    }

    public void loescheIpHistorie(String ip) {
        String kanonisch = kanonischeIpAus(ip);
        if (kanonisch == null) {
            return;
        }
        ipHistorie.remove(kanonisch);
        if (mysql != null) {
            ioExecutor.ausfuehren(() -> {
                try (Connection verbindung = mysql.verbindung();
                     PreparedStatement loeschen = verbindung.prepareStatement(
                             "DELETE FROM " + MysqlSchema.T_IP_HISTORIE + " WHERE ip = ?")) {
                    loeschen.setString(1, kanonisch);
                    loeschen.executeUpdate();
                } catch (Exception e) {
                    LOG.warn("IP-Historie für '{}' konnte nicht gelöscht werden.", kanonisch, e);
                }
            });
        }
    }

    private void ipHistorieEintragen(IpHistorienEintrag eintrag) {
        if (eintrag == null) {
            return;
        }
        String kanonisch = kanonischeIpAus(eintrag.ip());
        if (kanonisch == null) {
            return;
        }
        ipHistorie.computeIfAbsent(kanonisch, k -> new ConcurrentHashMap<>())
                .merge(eintrag.uuid(), eintrag, (alt, neu) ->
                        alt.zuletztGesehen() >= neu.zuletztGesehen() ? alt : neu);
    }

    private void ipIndexNeuBauen() {
        ipIndex.clear();
        for (CachedPlayer spieler : cache.values()) {
            ipIndexEintragen(spieler);
        }
    }

    private void ipIndexEintragen(CachedPlayer spieler) {
        if (spieler == null) {
            return;
        }
        String kanonisch = kanonischeIpAus(spieler.letzteIp());
        if (kanonisch == null) {
            return;
        }
        ipIndex.computeIfAbsent(kanonisch, k -> ConcurrentHashMap.newKeySet()).add(spieler.uuid());
    }

    private void ipIndexEntfernen(CachedPlayer spieler) {
        if (spieler == null) {
            return;
        }
        String kanonisch = kanonischeIpAus(spieler.letzteIp());
        if (kanonisch == null) {
            return;
        }
        java.util.Set<UUID> uuids = ipIndex.get(kanonisch);
        if (uuids != null) {
            uuids.remove(spieler.uuid());
        }
    }

    private static String kanonischeIpAus(String roh) {
        if (roh == null || roh.isBlank() || "unbekannt".equalsIgnoreCase(roh.trim())) {
            return null;
        }
        try {
            return de.lightrixx.punish.model.IpBanRecord.kanonisieren(roh);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Optional<CachedPlayer> findeExternOffline(String name) {
        if (name == null || !istValiderName(name.trim())) {
            return Optional.empty();
        }
        String gesuchterName = name.trim();

        Optional<CachedPlayer> gecacht = findenNachName(gesuchterName);
        if (gecacht.isPresent()) {
            if (!istNamensZuordnungVeraltet(gecacht.get())) {
                return gecacht;
            }
            boolean mainThread;
            try {
                mainThread = offlineVerzeichnis.istMainThread();
            } catch (Exception e) {
                mainThread = true;
            }
            if (mainThread) {
                revalidierungAnstossen(gesuchterName, gecacht.get().uuid());
                return gecacht;
            }
            try {
                Optional<UUID> mojangUuid = mojangUuidFuer(gesuchterName);
                if (mojangUuid.isPresent() && !mojangUuid.get().equals(gecacht.get().uuid())) {
                    CachedPlayer neu = new CachedPlayer(mojangUuid.get(), gesuchterName, "unbekannt",
                            System.currentTimeMillis());
                    try {
                        aktualisieren(neu);
                    } catch (Exception e) {
                        LOG.debug("Auto-Cache (Mojang-Korrektur) für '{}' fehlgeschlagen.", gesuchterName, e);
                    }
                    LOG.info("Veraltete Namens-Zuordnung korrigiert: '{}' gehörte {}, gehört laut Mojang jetzt {}.",
                            gesuchterName, gecacht.get().uuid(), mojangUuid.get());
                    return Optional.of(neu);
                }
            } catch (Exception e) {
                LOG.debug("Mojang-Verifikation für '{}' fehlgeschlagen, nutze Cache-Eintrag.", gesuchterName, e);
            }
            return gecacht;
        }

        String negativKey = gesuchterName.toLowerCase(Locale.ROOT);
        Long negativSeit = negativCache.get(negativKey);
        if (negativSeit != null) {
            if (System.currentTimeMillis() - negativSeit < NEGATIV_TTL_MILLIS) {
                return Optional.empty();
            }
            negativCache.remove(negativKey, negativSeit);
        }
        OfflineVerzeichnis.ExternSpieler offline;
        try {
            Optional<OfflineVerzeichnis.ExternSpieler> gefunden =
                    offlineVerzeichnis.findeNachName(gesuchterName);
            if (gefunden.isEmpty()) {

                return findeExternFallback(gesuchterName, negativKey);
            }
            offline = gefunden.get();
        } catch (Exception e) {

            LOG.debug("Externer Offline-Lookup für '{}' fehlgeschlagen.", gesuchterName, e);
            return findeExternFallback(gesuchterName, negativKey);
        }
        boolean bekannt;
        try {
            bekannt = offline.hatGespielt() || offline.online()
                    || istMojangProfil(offline.uuid(), offline.onlineModus(), offline.name());
        } catch (Exception e) {
            LOG.debug("Bekanntheits-Check für '{}' fehlgeschlagen.", gesuchterName, e);
            return Optional.empty();
        }
        if (!bekannt) {

            if (!offlineVerzeichnis.istMainThread()) {
                try {
                    Optional<UUID> mojangUuid = mojangUuidFuer(gesuchterName);
                    if (mojangUuid.isPresent()) {
                        CachedPlayer mojangSpieler = new CachedPlayer(
                                mojangUuid.get(), gesuchterName, "unbekannt", System.currentTimeMillis());
                        try {
                            aktualisieren(mojangSpieler);
                        } catch (Exception e) {
                            LOG.debug("Auto-Cache (Mojang) für '{}' fehlgeschlagen.", gesuchterName, e);
                        }
                        return Optional.of(mojangSpieler);
                    }
                } catch (Exception e) {
                    LOG.debug("Mojang-Lookup für '{}' fehlgeschlagen, nutze Vorab-Eintrag.", gesuchterName, e);
                }
            }
            if (!allowNeverSeen) {
                negativCache.put(negativKey, System.currentTimeMillis());
                return Optional.empty();
            }
            LOG.info("Vorab-Eintrag für nie gesehenen Spieler '{}' angelegt "
                    + "(allow-never-seen; Migration beim ersten Join).", gesuchterName);
        }
        UUID uuid = offline.uuid();
        String echterName = offline.name();
        if (uuid == null) {
            return Optional.empty();
        }
        String finalerName = (echterName == null || echterName.isBlank()) ? gesuchterName : echterName;
        long zuletztGesehen = System.currentTimeMillis();
        try {
            long lastPlayed = offline.zuletztGesehen();
            if (lastPlayed > 0) {
                zuletztGesehen = lastPlayed;
            }
        } catch (Exception e) {
            LOG.debug("Zuletzt-gesehen konnte nicht gelesen werden.", e);
        }
        CachedPlayer spieler = new CachedPlayer(uuid, finalerName, "unbekannt", zuletztGesehen);
        try {
            aktualisieren(spieler);
        } catch (Exception e) {
            LOG.debug("Auto-Cache für '{}' fehlgeschlagen (nur transient).", finalerName, e);
        }
        return Optional.of(spieler);
    }

    private Optional<CachedPlayer> findeExternFallback(String gesuchterName, String negativKey) {

        boolean mainThread;
        try {
            mainThread = offlineVerzeichnis.istMainThread();
        } catch (Exception e) {
            mainThread = true;
        }
        if (!mainThread) {
            try {
                Optional<UUID> mojangUuid = mojangUuidFuer(gesuchterName);
                if (mojangUuid.isPresent()) {
                    CachedPlayer mojangSpieler = new CachedPlayer(
                            mojangUuid.get(), gesuchterName, "unbekannt", System.currentTimeMillis());
                    try {
                        aktualisieren(mojangSpieler);
                    } catch (Exception e) {
                        LOG.debug("Auto-Cache (Mojang-Fallback) für '{}' fehlgeschlagen.", gesuchterName, e);
                    }
                    return Optional.of(mojangSpieler);
                }
            } catch (Exception e) {
                LOG.debug("Mojang-Fallback für '{}' fehlgeschlagen.", gesuchterName, e);
            }
        }
        if (!allowNeverSeen) {
            negativCache.put(negativKey, System.currentTimeMillis());
            return Optional.empty();
        }

        UUID offlineUuid = offlineUuidFuer(gesuchterName);
        CachedPlayer vorab = new CachedPlayer(offlineUuid, gesuchterName, "unbekannt", System.currentTimeMillis());
        try {
            aktualisieren(vorab);
        } catch (Exception e) {
            LOG.debug("Auto-Cache (Vorab-Fallback) für '{}' fehlgeschlagen.", gesuchterName, e);
        }
        LOG.info("Vorab-Eintrag (Fallback) für nie gesehenen Spieler '{}' angelegt "
                + "(allow-never-seen; Migration beim ersten Join).", gesuchterName);
        return Optional.of(vorab);
    }

    private void revalidierungAnstossen(String name, UUID alteUuid) {
        if (name == null || name.isBlank()) {
            return;
        }
        revalidierungenAngestossen.incrementAndGet();
        LOG.debug("Stale Namens-Zuordnung für '{}' (>37d), stoße async Revalidierung an (Rückgabe bleibt gecacht).",
                name);
        java.util.function.Consumer<String> beobachter = this.revalidierungsBeobachter;
        if (beobachter != null) {
            try {
                beobachter.accept(name);
            } catch (Exception e) {
                LOG.trace("Revalidierungs-Beobachter fehlgeschlagen.", e);
            }
        }
        final String gesuchterName = name;
        try {
            ioExecutor.ausfuehren(() -> {
                try {
                    Optional<UUID> mojangUuid = mojangUuidFuer(gesuchterName);
                    if (mojangUuid.isPresent() && !mojangUuid.get().equals(alteUuid)) {
                        CachedPlayer neu = new CachedPlayer(mojangUuid.get(), gesuchterName, "unbekannt",
                                System.currentTimeMillis());
                        try {
                            aktualisieren(neu);
                        } catch (Exception e) {
                            LOG.debug("Async-Revalidierung (Mojang-Korrektur) für '{}' fehlgeschlagen.",
                                    gesuchterName, e);
                        }
                        LOG.debug("Stale Namens-Zuordnung async korrigiert: '{}' {} -> {}.",
                                gesuchterName, alteUuid, mojangUuid.get());
                    }
                } catch (Exception e) {
                    LOG.debug("Async-Revalidierung für '{}' fehlgeschlagen, Cache bleibt.", gesuchterName, e);
                }
            });
        } catch (Exception e) {
            LOG.debug("Revalidierung konnte nicht angestoßen werden.", e);
        }
    }

    static int anonymisiereAbgelaufeneIn(java.util.concurrent.ConcurrentHashMap<UUID, CachedPlayer> karte,
                                          long jetztMillis, long fristMillis) {
        if (karte == null || karte.isEmpty() || fristMillis <= 0) {
            return 0;
        }
        int anonymisiert = 0;
        for (Map.Entry<UUID, CachedPlayer> eintrag : karte.entrySet()) {
            CachedPlayer alt = eintrag.getValue();
            if (alt == null || alt.letzteIp() == null || alt.letzteIp().isBlank()
                    || "unbekannt".equalsIgnoreCase(alt.letzteIp().trim())) {
                continue;
            }
            try {
                if (jetztMillis - alt.zuletztGesehen() > fristMillis) {
                    karte.put(alt.uuid(), new CachedPlayer(alt.uuid(), alt.name(), "unbekannt",
                            alt.zuletztGesehen()));
                    anonymisiert++;
                }
            } catch (Exception e) {
                continue;
            }
        }
        return anonymisiert;
    }

    static UUID offlineUuidFuer(String name) {        String getrimmt = name == null ? "unbekannt" : name.trim();
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + getrimmt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static boolean istVorabPlatzhalter(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return false;
        }
        try {
            if (offlineUuidFuer(name).equals(uuid)) {
                return true;
            }
            String klein = name.trim().toLowerCase(java.util.Locale.ROOT);
            return offlineUuidFuer(klein).equals(uuid);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean istMojangProfil(UUID uuid, boolean onlineModus, String name) {
        if (uuid == null) {
            return false;
        }
        if (!onlineModus) {
            return false;
        }
        if (uuid.version() != 4) {
            return false;
        }
        return name != null && !name.isBlank();
    }

    public static boolean istValiderName(String name) {
        if (name == null) {
            return false;
        }
        if (name.matches("[A-Za-z0-9_]{3,16}")) {
            return true;
        }
        return name.matches("\\.[A-Za-z0-9_]{2,15}");
    }

    static final int MOJANG_MAX_FEHLER = 5;
    static final long MOJANG_PAUSE_MILLIS = 5L * 60L * 1000L;
    private static final java.util.concurrent.atomic.AtomicInteger mojangFehler =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long mojangOffenBis;
    private static final java.util.concurrent.atomic.AtomicLong mojangHttpVersuche =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong mojangGeblockt =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile java.util.function.Function<String, Optional<UUID>> mojangStub;

    public static void setMojangStubForTests(java.util.function.Function<String, Optional<UUID>> stub) {
        mojangStub = stub;
    }

    public static void resetMojangCircuitForTests() {
        mojangFehler.set(0);
        mojangOffenBis = 0L;
        mojangHttpVersuche.set(0);
        mojangGeblockt.set(0);
    }

    public static long mojangHttpVersuche() {
        return mojangHttpVersuche.get();
    }

    public static long mojangGeblockteAufrufe() {
        return mojangGeblockt.get();
    }

    public static int mojangFehlerZaehler() {
        return mojangFehler.get();
    }

    public static String mojangCircuitStatus() {
        long offenBis = mojangOffenBis;
        long jetzt = System.currentTimeMillis();
        if (offenBis > jetzt) {
            return "OFFEN bis " + offenBis + " (Fehler=" + mojangFehler.get()
                    + ", geblockt=" + mojangGeblockt.get() + ")";
        }
        if (mojangFehler.get() > 0) {
            return "HALB-OFFEN/BEREIT (Fehler=" + mojangFehler.get() + ")";
        }
        return "GESCHLOSSEN (Fehler=0)";
    }

    public static boolean mojangCircuitOffen() {
        return mojangOffenBis > System.currentTimeMillis();
    }

    private static boolean mojangCircuitAbweisen() {
        long offenBis = mojangOffenBis;
        if (offenBis <= 0) {
            return false;
        }
        long jetzt = System.currentTimeMillis();
        if (jetzt < offenBis) {
            mojangGeblockt.incrementAndGet();
            LOG.debug("Mojang-Circuit offen (noch {}ms), überspringe HTTP.", offenBis - jetzt);
            return true;
        }
        return false;
    }

    private static void mojangErfolg() {
        if (mojangFehler.getAndSet(0) != 0) {
            LOG.debug("Mojang-Circuit geschlossen (Halbversuch erfolgreich).");
        }
        mojangOffenBis = 0L;
    }

    private static void mojangFehler(String login, Exception e) {
        int fehler = mojangFehler.incrementAndGet();
        LOG.debug("Mojang-Lookup für '{}' fehlgeschlagen ({}/{}).", login, fehler, MOJANG_MAX_FEHLER, e);
        if (fehler >= MOJANG_MAX_FEHLER) {
            mojangOffenBis = System.currentTimeMillis() + MOJANG_PAUSE_MILLIS;
            LOG.warn("Mojang-Circuit öffnet nach {} Fehlern für 5 Minuten (kein HTTP, danach Halbversuch).",
                    fehler);
        }
    }

    static final int MOJANG_ANTWORT_MAX_BYTES = 8192;

    static String leseBegrenzt(java.io.InputStream in, int maxBytes) throws java.io.IOException {
        java.io.ByteArrayOutputStream ausgabe = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[1024];
        int gelesen;
        while ((gelesen = in.read(block)) > 0) {
            if (ausgabe.size() + gelesen > maxBytes) {
                throw new java.io.IOException("Mojang-Antwort zu groß (> " + maxBytes + " Bytes).");
            }
            ausgabe.write(block, 0, gelesen);
        }
        return ausgabe.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Optional<UUID> mojangUuidFuer(String name) {
        if (name == null || !istValiderName(name.trim())) {
            return Optional.empty();
        }
        String login = name.trim();
        if (mojangCircuitAbweisen()) {
            return Optional.empty();
        }
        java.util.function.Function<String, Optional<UUID>> stub = mojangStub;
        if (stub != null) {
            mojangHttpVersuche.incrementAndGet();
            try {
                Optional<UUID> ergebnis = stub.apply(login);
                if (ergebnis != null) {
                    mojangErfolg();
                    return ergebnis;
                }
                mojangErfolg();
                return Optional.empty();
            } catch (Exception e) {
                mojangFehler(login, e);
                return Optional.empty();
            }
        }
        mojangHttpVersuche.incrementAndGet();
        java.net.HttpURLConnection verbindung = null;
        try {
            java.net.URL url = new java.net.URI(
                    "https://api.mojang.com/users/profiles/minecraft/" + login).toURL();
            verbindung = (java.net.HttpURLConnection) url.openConnection();
            verbindung.setRequestMethod("GET");
            verbindung.setConnectTimeout(2500);
            verbindung.setReadTimeout(2500);
            verbindung.setRequestProperty("User-Agent", "PunishSystem/vorab-lookup");
            verbindung.setInstanceFollowRedirects(false);
            int status = verbindung.getResponseCode();
            if (status == 204 || status == 404) {
                mojangErfolg();
                return Optional.empty();
            }
            if (status < 200 || status >= 300) {
                mojangFehler(login, new IllegalStateException("HTTP " + status));
                return Optional.empty();
            }
            String body;
            try (java.io.InputStream in = verbindung.getInputStream()) {
                body = leseBegrenzt(in, MOJANG_ANTWORT_MAX_BYTES);
            }

            int idIndex = body.indexOf("\"id\"");
            if (idIndex < 0) {
                mojangErfolg();
                return Optional.empty();
            }
            int doppelpunkt = body.indexOf(':', idIndex);
            int anfang = body.indexOf('"', doppelpunkt);
            int ende = body.indexOf('"', anfang + 1);
            if (anfang < 0 || ende <= anfang + 1) {
                mojangErfolg();
                return Optional.empty();
            }
            String roh = body.substring(anfang + 1, ende).trim();
            if (roh.length() != 32 || !roh.matches("[0-9a-fA-F]{32}")) {
                mojangErfolg();
                return Optional.empty();
            }
            String dashed = roh.substring(0, 8) + "-" + roh.substring(8, 12) + "-"
                    + roh.substring(12, 16) + "-" + roh.substring(16, 20) + "-" + roh.substring(20);
            mojangErfolg();
            return Optional.of(UUID.fromString(dashed));
        } catch (Exception e) {
            mojangFehler(login, e instanceof Exception ex ? ex : new IllegalStateException(e));
            return Optional.empty();
        } finally {
            if (verbindung != null) {
                verbindung.disconnect();
            }
        }
    }

    public Collection<CachedPlayer> alle() {
        return List.copyOf(cache.values());
    }

    public List<String> alleNamen() {

        List<String> namen = new ArrayList<>(cache.size());
        for (CachedPlayer spieler : cache.values()) {
            namen.add(spieler.name());
        }
        return namen.stream().distinct().toList();
    }

    public int migrationImport(Collection<CachedPlayer> alle) {
        Objects.requireNonNull(alle, "Die Spieler-Liste darf nicht null sein.");
        if (mysql == null) {
            throw new IllegalStateException("Migration nur im MySQL-Modus möglich.");
        }
        StoreLock.LOCK.writeLock().lock();
        try {
            List<CachedPlayer> snapshot = new ArrayList<>();
            for (CachedPlayer spieler : alle) {
                if (spieler == null) {
                    continue;
                }
                cache.put(spieler.uuid(), spieler);
                namenCache.put(spieler.name().toLowerCase(Locale.ROOT), spieler.uuid());
                ipIndexEintragen(spieler);
                ipHistorieEintragen(new IpHistorienEintrag(spieler.letzteIp(), spieler.uuid(),
                        spieler.name(), spieler.zuletztGesehen()));
                snapshot.add(spieler);
            }
            final List<CachedPlayer> kopie = List.copyOf(snapshot);
            ioExecutor.ausfuehren(() -> speichereAlleNachMysql(kopie));
            return kopie.size();
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void ladeAusMysql() {
        StoreLock.LOCK.writeLock().lock();
        try {
            ConcurrentHashMap<UUID, CachedPlayer> tmpCache = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, UUID> tmpNamen = new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT uuid, name, letzte_ip, zuletzt_gesehen FROM " + MysqlSchema.T_SPIELER)) {
                while (ergebnis.next()) {
                    try {
                        UUID uuid = UUID.fromString(ergebnis.getString("uuid"));
                        String name = ergebnis.getString("name");
                        String letzteIp = ergebnis.getString("letzte_ip");
                        if (letzteIp == null || letzteIp.isBlank()) {
                            letzteIp = "unbekannt";
                        }
                        long zuletztGesehen = ergebnis.getLong("zuletzt_gesehen");
                        if (zuletztGesehen <= 0) {
                            zuletztGesehen = System.currentTimeMillis();
                        }
                        CachedPlayer spieler = new CachedPlayer(uuid, name, letzteIp, zuletztGesehen);
                        tmpCache.put(uuid, spieler);
                        tmpNamen.put(name.toLowerCase(Locale.ROOT), uuid);
                    } catch (Exception e) {
                        LOG.error("Eine PlayerCache-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("PlayerCache konnte nicht aus MySQL geladen werden – "
                        + "Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            ConcurrentHashMap<String, ConcurrentHashMap<UUID, IpHistorienEintrag>> tmpHistorie =
                    new ConcurrentHashMap<>();
            try (Connection verbindung = mysql.verbindung();
                 Statement statement = verbindung.createStatement();
                 ResultSet ergebnis = statement.executeQuery(
                         "SELECT ip, uuid, name, zuletzt_gesehen FROM " + MysqlSchema.T_IP_HISTORIE)) {
                while (ergebnis.next()) {
                    try {
                        String ip = ergebnis.getString("ip");
                        UUID uuid = UUID.fromString(ergebnis.getString("uuid"));
                        String name = ergebnis.getString("name");
                        long zuletztGesehen = ergebnis.getLong("zuletzt_gesehen");
                        IpHistorienEintrag eintrag = new IpHistorienEintrag(ip, uuid, name, zuletztGesehen);
                        String kanonisch = kanonischeIpAus(eintrag.ip());
                        if (kanonisch == null) {
                            continue;
                        }
                        tmpHistorie.computeIfAbsent(kanonisch, k -> new ConcurrentHashMap<>())
                                .merge(uuid, eintrag, (alt, neu) ->
                                        alt.zuletztGesehen() >= neu.zuletztGesehen() ? alt : neu);
                    } catch (Exception e) {
                        LOG.error("Eine IP-Historien-Zeile ist ungültig und wird übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("IP-Historie konnte nicht aus MySQL geladen werden – "
                        + "Start/Reload verweigert (Fail-Closed, kein leerer Bestand).", e);
            }
            synchronized (cacheLock) {
                cache.putAll(tmpCache);
                cache.keySet().removeIf(uuid -> !tmpCache.containsKey(uuid));
                namenCache.putAll(tmpNamen);
                namenCache.keySet().removeIf(name -> !tmpNamen.containsKey(name));
                ipIndexNeuBauen();
                ipHistorie.clear();
                ipHistorie.putAll(tmpHistorie);
            }
            LOG.info("Es wurden {} Spieler aus MySQL geladen.", cache.size());
            LOG.info("Es wurden {} IP-Historien-Einträge aus MySQL geladen.", tmpHistorie.size());
        } finally {
            StoreLock.LOCK.writeLock().unlock();
        }
    }

    private void speichereSpielerNachMysql(CachedPlayer spieler, List<UUID> opfer) {
        try (Connection verbindung = mysql.verbindung()) {
            if (opfer != null && !opfer.isEmpty()) {
                try (PreparedStatement loeschen = verbindung.prepareStatement(
                        "DELETE FROM " + MysqlSchema.T_SPIELER + " WHERE uuid = ?")) {
                    for (UUID uuid : opfer) {
                        if (uuid == null || uuid.equals(spieler.uuid())) {
                            continue;
                        }
                        loeschen.setString(1, uuid.toString());
                        loeschen.addBatch();
                    }
                    loeschen.executeBatch();
                }
            }
            try (PreparedStatement upsert = verbindung.prepareStatement(
                    "INSERT INTO " + MysqlSchema.T_SPIELER
                            + " (uuid, name, letzte_ip, zuletzt_gesehen) VALUES (?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                            + " letzte_ip = VALUES(letzte_ip), zuletzt_gesehen = VALUES(zuletzt_gesehen)")) {
                upsert.setString(1, spieler.uuid().toString());
                upsert.setString(2, spieler.name());
                upsert.setString(3, spieler.letzteIp());
                upsert.setLong(4, spieler.zuletztGesehen());
                upsert.executeUpdate();
            }
            schreibeIpHistorie(verbindung, spieler);
        } catch (Exception e) {
            LOG.error("PlayerCache-Eintrag für {} konnte nicht nach MySQL geschrieben werden.",
                    spieler.uuid(), e);
            throw new IllegalStateException("PlayerCache-Schreibvorgang fehlgeschlagen: " + spieler.uuid(), e);
        }
    }

    private void schreibeIpHistorie(Connection verbindung, CachedPlayer spieler) throws Exception {
        String kanonisch = kanonischeIpAus(spieler.letzteIp());
        if (kanonisch == null) {
            return;
        }
        try (PreparedStatement upsert = verbindung.prepareStatement(
                "INSERT INTO " + MysqlSchema.T_IP_HISTORIE
                        + " (ip, uuid, name, zuletzt_gesehen) VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                        + " zuletzt_gesehen = GREATEST(zuletzt_gesehen, VALUES(zuletzt_gesehen))")) {
            upsert.setString(1, kanonisch);
            upsert.setString(2, spieler.uuid().toString());
            upsert.setString(3, spieler.name());
            upsert.setLong(4, spieler.zuletztGesehen());
            upsert.executeUpdate();
        }
    }

    private void speichereAlleNachMysql(List<CachedPlayer> snapshot) {
        try (Connection verbindung = mysql.verbindung();
             PreparedStatement upsert = verbindung.prepareStatement(
                     "INSERT INTO " + MysqlSchema.T_SPIELER
                             + " (uuid, name, letzte_ip, zuletzt_gesehen) VALUES (?, ?, ?, ?)"
                             + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                             + " letzte_ip = VALUES(letzte_ip), zuletzt_gesehen = VALUES(zuletzt_gesehen)");
             PreparedStatement historie = verbindung.prepareStatement(
                     "INSERT INTO " + MysqlSchema.T_IP_HISTORIE
                             + " (ip, uuid, name, zuletzt_gesehen) VALUES (?, ?, ?, ?)"
                             + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                             + " zuletzt_gesehen = GREATEST(zuletzt_gesehen, VALUES(zuletzt_gesehen))")) {
            for (CachedPlayer spieler : snapshot) {
                if (spieler == null) {
                    continue;
                }
                upsert.setString(1, spieler.uuid().toString());
                upsert.setString(2, spieler.name());
                upsert.setString(3, spieler.letzteIp());
                upsert.setLong(4, spieler.zuletztGesehen());
                upsert.addBatch();
                String kanonisch = kanonischeIpAus(spieler.letzteIp());
                if (kanonisch != null) {
                    historie.setString(1, kanonisch);
                    historie.setString(2, spieler.uuid().toString());
                    historie.setString(3, spieler.name());
                    historie.setLong(4, spieler.zuletztGesehen());
                    historie.addBatch();
                }
            }
            upsert.executeBatch();
            historie.executeBatch();
        } catch (Exception e) {
            LOG.error("PlayerCache konnte nicht nach MySQL geschrieben werden ({} Einträge).",
                    snapshot.size(), e);
            throw new IllegalStateException("PlayerCache-Batchschreibvorgang fehlgeschlagen ("
                    + snapshot.size() + " Einträge).", e);
        }
    }

    private void speichern() {
        ioExecutor.ausfuehrenMitSchluessel(datei.getName(), () -> {
            List<CachedPlayer> snapshot = List.copyOf(cache.values());
            java.util.Map<String, Object> rohSnapshot = new java.util.HashMap<>(rohQuarantaene);
            Map<String, Object> daten = konfigurationErstellen(snapshot, rohSnapshot);
            yamlSpeicher.speichern(daten, datei);
        });
    }

    private Map<String, Object> konfigurationErstellen(List<CachedPlayer> eintraege,
                                                      java.util.Map<String, Object> rohQuarantaene) {
        Map<String, Object> daten = new LinkedHashMap<>();
        for (CachedPlayer spieler : eintraege) {
            String pfad = spieler.uuid().toString();
            Map<String, Object> abschnitt = new LinkedHashMap<>();
            abschnitt.put(SCHLUESSEL_NAME, spieler.name());
            abschnitt.put(SCHLUESSEL_LETZTE_IP, spieler.letzteIp());
            abschnitt.put(SCHLUESSEL_ZULETZT_GESEHEN, spieler.zuletztGesehen());
            daten.put(pfad, abschnitt);
        }
        if (rohQuarantaene != null) {
            for (java.util.Map.Entry<String, Object> roh : rohQuarantaene.entrySet()) {
                if (roh.getKey() == null || daten.containsKey(roh.getKey())) {
                    continue;
                }
                try {
                    daten.put(roh.getKey(), roh.getValue());
                } catch (Exception e) {
                    LOG.warn("Quarantäne-Eintrag '{}' konnte nicht zurückgeschrieben werden.", roh.getKey(), e);
                }
            }
        }
        return daten;
    }
}
