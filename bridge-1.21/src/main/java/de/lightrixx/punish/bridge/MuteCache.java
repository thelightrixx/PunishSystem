package de.lightrixx.punish.bridge;

import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.net.Protokoll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MuteCache {

    private static final Logger LOG = LoggerFactory.getLogger(MuteCache.class);

    private volatile ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.MuteEintrag>> mutes =
            new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean jeSynchronisiert =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong syncGeneration =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.MuteEintrag>> staged =
            new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong letzteSyncMillis =
            new java.util.concurrent.atomic.AtomicLong(0L);

    private static String scopeNorm(String scope) {
        return scope == null || scope.isBlank() ? "unbekannt"
                : scope.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void eintragen(ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.MuteEintrag>> ziel,
                                  Protokoll.MuteEintrag eintrag) {
        ziel.computeIfAbsent(eintrag.uuid(), k -> new ConcurrentHashMap<>())
                .put(scopeNorm(eintrag.scope()), eintrag);
    }

    private static int anzahl(ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.MuteEintrag>> quelle) {
        if (quelle == null) {
            return 0;
        }
        int summe = 0;
        for (ConcurrentHashMap<String, Protokoll.MuteEintrag> jeSpieler : quelle.values()) {
            if (jeSpieler != null) {
                summe += jeSpieler.size();
            }
        }
        return summe;
    }

    /**
     * Gibt einen angefangenen Chunk-Sync auf (Verbindungsabbruch, Proxy-Neustart). Der zuletzt
     * angewandte Stand bleibt erhalten; ohne das Aufgeben würde jeder spätere Voll-Sync nur
     * gestaged und nie angewandt.
     */
    public void syncVerwerfen() {
        staged.clear();
        syncGeneration.set(0L);
    }

    public void syncBeginn(long generation) {
        if (generation <= 0) {
            return;
        }
        if (syncGeneration.get() == generation) {
            return;
        }
        staged.clear();
        syncGeneration.set(generation);
    }

    public void syncEnde(long generation) {
        if (generation <= 0 || !syncGeneration.compareAndSet(generation, 0L)) {
            return;
        }
        ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.MuteEintrag>> neu = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, ConcurrentHashMap<String, Protokoll.MuteEintrag>> eintrag : staged.entrySet()) {
            neu.put(eintrag.getKey(), new ConcurrentHashMap<>(eintrag.getValue()));
        }
        mutes = neu;
        staged.clear();
        jeSynchronisiert.set(true);
        letzteSyncMillis.set(System.currentTimeMillis());
        LOG.debug("Mute-Cache ersetzt ({} Einträge).", anzahl(mutes));
    }

    public void ersetzeAlle(Collection<Protokoll.MuteEintrag> eintraege) {
        if (syncGeneration.get() != 0L) {
            staged.clear();
            if (eintraege != null) {
                for (Protokoll.MuteEintrag eintrag : eintraege) {
                    if (eintrag != null && eintrag.uuid() != null) {
                        eintragen(staged, eintrag);
                    }
                }
            }
            return;
        }
        ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.MuteEintrag>> neu = new ConcurrentHashMap<>();
        if (eintraege != null) {
            for (Protokoll.MuteEintrag eintrag : eintraege) {
                if (eintrag != null && eintrag.uuid() != null) {
                    eintragen(neu, eintrag);
                }
            }
        }
        mutes = neu;
        jeSynchronisiert.set(true);
        letzteSyncMillis.set(System.currentTimeMillis());
        LOG.debug("Mute-Cache ersetzt ({} Einträge).", anzahl(mutes));
    }

    public boolean istJeSynchronisiert() {
        return jeSynchronisiert.get();
    }

    public long letzteSyncMillis() {
        return letzteSyncMillis.get();
    }

    public void setze(Protokoll.MuteEintrag eintrag) {
        if (eintrag == null || eintrag.uuid() == null) {
            return;
        }
        if (syncGeneration.get() != 0L) {
            eintragen(staged, eintrag);
            return;
        }
        eintragen(mutes, eintrag);
    }

    public void entferne(Collection<UUID> uuids) {
        if (uuids == null) {
            return;
        }
        for (UUID uuid : uuids) {
            if (uuid != null) {
                mutes.remove(uuid);
                staged.remove(uuid);
            }
        }
    }

    public void leeren() {
        mutes.clear();
        staged.clear();
        syncGeneration.set(0L);
        jeSynchronisiert.set(false);
        letzteSyncMillis.set(0L);
    }

    public Optional<Protokoll.MuteEintrag> findeAktiv(UUID spielerUuid, String aktuellerServer, long jetzt) {
        Objects.requireNonNull(spielerUuid, "Die UUID darf nicht null sein.");
        ConcurrentHashMap<String, Protokoll.MuteEintrag> desSpieler = mutes.get(spielerUuid);
        if (desSpieler == null) {
            return Optional.empty();
        }
        Protokoll.MuteEintrag serverTreffer = null;
        for (Protokoll.MuteEintrag eintrag : desSpieler.values()) {
            if (eintrag == null || !spielerUuid.equals(eintrag.uuid()) || !eintrag.istAktiv(jetzt)) {
                continue;
            }
            ServerScope scope = ServerScope.ausSpeicherwert(eintrag.scope());
            if (!scope.giltFuer(aktuellerServer)) {
                continue;
            }
            if (scope.typ() == ServerScope.Typ.GLOBAL || scope.typ() == ServerScope.Typ.UNBEKANNT) {
                return Optional.of(eintrag);
            }
            if (serverTreffer == null) {
                serverTreffer = eintrag;
            }
        }
        return Optional.ofNullable(serverTreffer);
    }

    public List<Protokoll.MuteEintrag> alle() {
        List<Protokoll.MuteEintrag> ergebnis = new ArrayList<>();
        for (ConcurrentHashMap<String, Protokoll.MuteEintrag> desSpieler : mutes.values()) {
            ergebnis.addAll(desSpieler.values());
        }
        return ergebnis;
    }

    public int groesse() {
        return anzahl(mutes);
    }
}
