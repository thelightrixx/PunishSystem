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

/**
 * Fallback-Deny-Cache für Banns auf Backend-Servern (zweite Schicht hinter Proxy-Enforcement
 * + Firewall). Quelle bleibt der Proxy; der Cache greift nur bei Direktjoin ohne Proxy.
 */
public final class BanCache {

    private static final Logger LOG = LoggerFactory.getLogger(BanCache.class);

    private volatile ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.BanEintrag>> banns =
            new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean jeSynchronisiert =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong syncGeneration =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.BanEintrag>> staged =
            new ConcurrentHashMap<>();

    private static String scopeNorm(String scope) {
        return scope == null || scope.isBlank() ? "unbekannt"
                : scope.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void eintragen(ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.BanEintrag>> ziel,
                                  Protokoll.BanEintrag eintrag) {
        ziel.computeIfAbsent(eintrag.uuid(), k -> new ConcurrentHashMap<>())
                .put(scopeNorm(eintrag.scope()), eintrag);
    }

    private static int anzahl(ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.BanEintrag>> quelle) {
        if (quelle == null) {
            return 0;
        }
        int summe = 0;
        for (ConcurrentHashMap<String, Protokoll.BanEintrag> teil : quelle.values()) {
            if (teil != null) {
                summe += teil.size();
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
        ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.BanEintrag>> neu = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, ConcurrentHashMap<String, Protokoll.BanEintrag>> eintrag : staged.entrySet()) {
            neu.put(eintrag.getKey(), new ConcurrentHashMap<>(eintrag.getValue()));
        }
        banns = neu;
        staged.clear();
        jeSynchronisiert.set(true);
        LOG.debug("Ban-Cache ersetzt ({} Einträge).", anzahl(banns));
    }

    public void ersetzeAlle(Collection<Protokoll.BanEintrag> eintraege) {
        if (syncGeneration.get() != 0L) {
            staged.clear();
            if (eintraege != null) {
                for (Protokoll.BanEintrag eintrag : eintraege) {
                    if (eintrag != null && eintrag.uuid() != null) {
                        eintragen(staged, eintrag);
                    }
                }
            }
            return;
        }
        ConcurrentHashMap<UUID, ConcurrentHashMap<String, Protokoll.BanEintrag>> neu = new ConcurrentHashMap<>();
        if (eintraege != null) {
            for (Protokoll.BanEintrag eintrag : eintraege) {
                if (eintrag != null && eintrag.uuid() != null) {
                    eintragen(neu, eintrag);
                }
            }
        }
        banns = neu;
        jeSynchronisiert.set(true);
        LOG.debug("Ban-Cache ersetzt ({} Einträge).", anzahl(banns));
    }

    public boolean istJeSynchronisiert() {
        return jeSynchronisiert.get();
    }

    public void setze(Protokoll.BanEintrag eintrag) {
        if (eintrag == null || eintrag.uuid() == null) {
            return;
        }
        if (syncGeneration.get() != 0L) {
            eintragen(staged, eintrag);
            return;
        }
        eintragen(banns, eintrag);
    }

    public void entferne(Collection<UUID> uuids) {
        if (uuids == null) {
            return;
        }
        for (UUID uuid : uuids) {
            if (uuid != null) {
                banns.remove(uuid);
                staged.remove(uuid);
            }
        }
    }

    public void leeren() {
        banns.clear();
        staged.clear();
        syncGeneration.set(0L);
        jeSynchronisiert.set(false);
    }

    public Optional<Protokoll.BanEintrag> findeAktiv(UUID spielerUuid, String aktuellerServer, long jetzt) {
        Objects.requireNonNull(spielerUuid, "Die UUID darf nicht null sein.");
        ConcurrentHashMap<String, Protokoll.BanEintrag> desSpieler = banns.get(spielerUuid);
        if (desSpieler == null) {
            return Optional.empty();
        }
        Protokoll.BanEintrag serverTreffer = null;
        for (Protokoll.BanEintrag eintrag : desSpieler.values()) {
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

    public List<Protokoll.BanEintrag> alle() {
        List<Protokoll.BanEintrag> ergebnis = new ArrayList<>();
        for (ConcurrentHashMap<String, Protokoll.BanEintrag> desSpieler : banns.values()) {
            ergebnis.addAll(desSpieler.values());
        }
        return ergebnis;
    }

    public int groesse() {
        return anzahl(banns);
    }
}
