package de.lightrixx.punish.bridge;

import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.net.Protokoll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class IpMuteCache {

    private static final Logger LOG = LoggerFactory.getLogger(IpMuteCache.class);

    private volatile ConcurrentHashMap<String, ConcurrentHashMap<String, Protokoll.IpMuteEintrag>> mutes =
            new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean jeSynchronisiert =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong syncGeneration =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Protokoll.IpMuteEintrag>> staged =
            new ConcurrentHashMap<>();

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
        ConcurrentHashMap<String, ConcurrentHashMap<String, Protokoll.IpMuteEintrag>> neu = new ConcurrentHashMap<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Protokoll.IpMuteEintrag>> eintrag : staged.entrySet()) {
            neu.put(eintrag.getKey(), new ConcurrentHashMap<>(eintrag.getValue()));
        }
        mutes = neu;
        staged.clear();
        jeSynchronisiert.set(true);
        LOG.debug("IP-Mute-Cache ersetzt ({} Einträge).", anzahl(mutes));
    }

    public void ersetzeAlle(Collection<Protokoll.IpMuteEintrag> eintraege) {
        if (syncGeneration.get() != 0L) {
            staged.clear();
            if (eintraege != null) {
                for (Protokoll.IpMuteEintrag eintrag : eintraege) {
                    if (eintrag != null && eintrag.ip() != null && !eintrag.ip().isBlank()) {
                        eintragen(staged, eintrag);
                    }
                }
            }
            return;
        }
        ConcurrentHashMap<String, ConcurrentHashMap<String, Protokoll.IpMuteEintrag>> neu = new ConcurrentHashMap<>();
        if (eintraege != null) {
            for (Protokoll.IpMuteEintrag eintrag : eintraege) {
                if (eintrag != null && eintrag.ip() != null && !eintrag.ip().isBlank()) {
                    eintragen(neu, eintrag);
                }
            }
        }
        mutes = neu;
        jeSynchronisiert.set(true);
        LOG.debug("IP-Mute-Cache ersetzt ({} Einträge).", anzahl(mutes));
    }

    public boolean istJeSynchronisiert() {
        return jeSynchronisiert.get();
    }

    public void setze(Protokoll.IpMuteEintrag eintrag) {
        if (eintrag == null || eintrag.ip() == null || eintrag.ip().isBlank()) {
            return;
        }
        if (syncGeneration.get() != 0L) {
            eintragen(staged, eintrag);
            return;
        }
        eintragen(mutes, eintrag);
    }

    public void entferne(Collection<String> ips) {
        if (ips == null) {
            return;
        }
        for (String ip : ips) {
            if (ip != null && !ip.isBlank()) {
                String basis = schluesselFuer(ip);
                mutes.remove(basis);
                staged.remove(basis);
            }
        }
    }

    public void leeren() {
        mutes.clear();
        staged.clear();
        syncGeneration.set(0L);
        jeSynchronisiert.set(false);
    }

    public Optional<Protokoll.IpMuteEintrag> findeAktiv(String ip, String aktuellerServer, long jetzt) {
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        String gesucht = schluesselFuer(ip);
        ConcurrentHashMap<String, Protokoll.IpMuteEintrag> kandidaten = mutes.get(gesucht);
        if (kandidaten == null) {
            return Optional.empty();
        }
        Protokoll.IpMuteEintrag serverTreffer = null;
        for (Protokoll.IpMuteEintrag kandidat : kandidaten.values()) {
            if (kandidat == null || kandidat.ip() == null || !kandidat.istAktiv(jetzt)) {
                continue;
            }
            ServerScope scope = ServerScope.ausSpeicherwert(kandidat.scope());
            if (!scope.giltFuer(aktuellerServer)) {
                continue;
            }
            if (scope.typ() == ServerScope.Typ.GLOBAL || scope.typ() == ServerScope.Typ.UNBEKANNT) {
                return Optional.of(kandidat);
            }
            if (serverTreffer == null) {
                serverTreffer = kandidat;
            }
        }
        return Optional.ofNullable(serverTreffer);
    }

    private static String scopeNorm(String scope) {
        return scope == null || scope.isBlank() ? "unbekannt"
                : scope.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String schluesselFuer(String ip) {
        try {
            return de.lightrixx.punish.model.IpBanRecord.kanonisieren(ip.trim())
                    .toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return ip.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static void eintragen(ConcurrentHashMap<String, ConcurrentHashMap<String, Protokoll.IpMuteEintrag>> ziel,
                                  Protokoll.IpMuteEintrag eintrag) {
        ziel.computeIfAbsent(schluesselFuer(eintrag.ip()), k -> new ConcurrentHashMap<>())
                .put(scopeNorm(eintrag.scope()), eintrag);
    }

    private static int anzahl(ConcurrentHashMap<String, ConcurrentHashMap<String, Protokoll.IpMuteEintrag>> quelle) {
        if (quelle == null) {
            return 0;
        }
        int summe = 0;
        for (ConcurrentHashMap<String, Protokoll.IpMuteEintrag> jeIp : quelle.values()) {
            if (jeIp != null) {
                summe += jeIp.size();
            }
        }
        return summe;
    }

    public List<Protokoll.IpMuteEintrag> alle() {
        List<Protokoll.IpMuteEintrag> ergebnis = new ArrayList<>();
        for (ConcurrentHashMap<String, Protokoll.IpMuteEintrag> jeIp : mutes.values()) {
            ergebnis.addAll(jeIp.values());
        }
        return ergebnis;
    }

    public int groesse() {
        return anzahl(mutes);
    }
}
