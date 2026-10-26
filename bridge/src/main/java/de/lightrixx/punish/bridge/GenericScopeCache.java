package de.lightrixx.punish.bridge;

import de.lightrixx.punish.model.ServerScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class GenericScopeCache<E> {

    private static final Logger LOG = LoggerFactory.getLogger(GenericScopeCache.class);

    private final ConcurrentHashMap<String, E> eintraege = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, E> staged = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean jeSynchronisiert =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong syncGeneration =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final Function<E, UUID> uuidVon;
    private final Function<E, String> scopeVon;
    private final Function<E, Long> ablaufVon;
    private final String name;

    public GenericScopeCache(String name, Function<E, UUID> uuidVon,
                             Function<E, String> scopeVon, Function<E, Long> ablaufVon) {
        this.name = Objects.requireNonNull(name, "Der Name darf nicht null sein.");
        this.uuidVon = Objects.requireNonNull(uuidVon, "uuidVon darf nicht null sein.");
        this.scopeVon = Objects.requireNonNull(scopeVon, "scopeVon darf nicht null sein.");
        this.ablaufVon = Objects.requireNonNull(ablaufVon, "ablaufVon darf nicht null sein.");
    }

    private static String schluesselFuer(UUID uuid, String scope) {
        String scopeNorm = scope == null || scope.isBlank() ? "unbekannt"
                : scope.trim().toLowerCase(java.util.Locale.ROOT);
        return uuid.toString().toLowerCase(java.util.Locale.ROOT) + "|" + scopeNorm;
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
        eintraege.clear();
        eintraege.putAll(staged);
        staged.clear();
        jeSynchronisiert.set(true);
        LOG.debug("{}-Cache ersetzt ({} Einträge).", name, eintraege.size());
    }

    public void ersetzeAlle(Collection<E> neu) {
        if (syncGeneration.get() != 0L) {
            staged.clear();
            if (neu != null) {
                for (E eintrag : neu) {
                    if (eintrag != null && uuidVon.apply(eintrag) != null) {
                        staged.put(schluesselFuer(uuidVon.apply(eintrag), scopeVon.apply(eintrag)), eintrag);
                    }
                }
            }
            return;
        }
        eintraege.clear();
        if (neu != null) {
            for (E eintrag : neu) {
                if (eintrag != null && uuidVon.apply(eintrag) != null) {
                    eintraege.put(schluesselFuer(uuidVon.apply(eintrag), scopeVon.apply(eintrag)), eintrag);
                }
            }
        }
        jeSynchronisiert.set(true);
        LOG.debug("{}-Cache ersetzt ({} Einträge).", name, eintraege.size());
    }

    public boolean istJeSynchronisiert() {
        return jeSynchronisiert.get();
    }

    public void setze(E eintrag) {
        if (eintrag == null || uuidVon.apply(eintrag) == null) {
            return;
        }
        String key = schluesselFuer(uuidVon.apply(eintrag), scopeVon.apply(eintrag));
        if (syncGeneration.get() != 0L) {
            staged.put(key, eintrag);
            return;
        }
        eintraege.put(key, eintrag);
    }

    public void entferne(Collection<UUID> uuids) {
        if (uuids == null) {
            return;
        }
        for (UUID uuid : uuids) {
            if (uuid != null) {
                String praefix = uuid.toString().toLowerCase(java.util.Locale.ROOT) + "|";
                eintraege.keySet().removeIf(k -> k != null && k.startsWith(praefix));
                staged.keySet().removeIf(k -> k != null && k.startsWith(praefix));
            }
        }
    }

    public void leeren() {
        eintraege.clear();
        staged.clear();
        syncGeneration.set(0L);
        jeSynchronisiert.set(false);
    }

    @SuppressWarnings("null")
    public Optional<E> findeAktiv(UUID spielerUuid, String aktuellerServer, long jetzt) {
        Objects.requireNonNull(spielerUuid, "Die UUID darf nicht null sein.");
        E serverTreffer = null;
        for (E eintrag : eintraege.values()) {
            if (eintrag == null || !spielerUuid.equals(uuidVon.apply(eintrag))) {
                continue;
            }
            Long ablauf = ablaufVon.apply(eintrag);
            boolean aktiv = ablauf != null && ablauf == Long.MAX_VALUE || ablauf != null && ablauf > jetzt;
            if (!aktiv) {
                continue;
            }
            ServerScope scope = ServerScope.ausSpeicherwert(scopeVon.apply(eintrag));
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

    public List<E> alle() {
        return new ArrayList<>(eintraege.values());
    }

    public int groesse() {
        return eintraege.size();
    }
}
