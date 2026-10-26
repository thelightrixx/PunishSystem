package de.lightrixx.punish.bridge.papi;

import de.lightrixx.punish.bridge.KanalClient;
import de.lightrixx.punish.net.Protokoll;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached Proxy-Strafwerte für PlaceholderAPI (TTL 30s, async Refresh).
 * PAPI ruft synchron und oft auf – niemals auf dem Main-Thread blockieren:
 * frische/stale Werte sofort liefern, Refresh nebenbei anstoßen.
 */
public final class PlatzhalterCache {

    private static final Logger LOG = LoggerFactory.getLogger(PlatzhalterCache.class);
    private static final long TTL_MILLIS = 30L * 1000L;
    private static final long TIMEOUT_MILLIS = 2500L;
    private static final int MAX_EINTRAEGE = 1000;

    private record Eintrag(Protokoll.PlatzhalterAntwort antwort, long zeit) {
    }

    private final Plugin plugin;
    private final KanalClient kanal;
    private final ConcurrentHashMap<UUID, Eintrag> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> laufendeRefreshs = new ConcurrentHashMap<>();

    public PlatzhalterCache(Plugin plugin, KanalClient kanal) {
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
        this.kanal = Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
    }

    public Protokoll.PlatzhalterAntwort aktuelle(Player betrachter, String zielName) {
        UUID schluessel = schluesselFuer(betrachter, zielName);
        if (schluessel == null) {
            return null;
        }
        Eintrag eintrag = cache.get(schluessel);
        long jetzt = System.currentTimeMillis();
        if (eintrag != null && jetzt - eintrag.zeit() < TTL_MILLIS) {
            return eintrag.antwort();
        }
        if (eintrag != null) {
            cache.remove(schluessel, eintrag);
        }
        begrenze(jetzt);
        aktualisierenAsync(betrachter, zielName, schluessel);
        return eintrag == null ? null : eintrag.antwort();
    }

    private void begrenze(long jetzt) {
        if (cache.size() <= MAX_EINTRAEGE) {
            return;
        }
        raeumeAbgelaufene(jetzt);
        while (cache.size() > MAX_EINTRAEGE) {
            UUID aeltester = null;
            long aelteste = Long.MAX_VALUE;
            for (var eintrag : cache.entrySet()) {
                long zeit = eintrag.getValue() == null ? 0L : eintrag.getValue().zeit();
                if (zeit < aelteste) {
                    aelteste = zeit;
                    aeltester = eintrag.getKey();
                }
            }
            if (aeltester == null) {
                return;
            }
            cache.remove(aeltester);
        }
    }

    private void raeumeAbgelaufene(long jetzt) {
        try {
            cache.entrySet().removeIf(e -> e == null || e.getValue() == null
                    || jetzt - e.getValue().zeit() >= TTL_MILLIS);
        } catch (Exception e) {
            LOG.debug("Platzhalter-Eviction fehlgeschlagen.", e);
        }
    }

    public void aktualisierenAsync(Player betrachter, String zielName, UUID schluessel) {
        long jetzt = System.currentTimeMillis();
        Long letzter = laufendeRefreshs.get(schluessel);
        if (letzter != null && jetzt - letzter < 5000L) {
            return;
        }
        laufendeRefreshs.put(schluessel, jetzt);
        String ziel = zielName == null || zielName.isBlank()
                ? (betrachter == null ? "" : betrachter.getName())
                : zielName.trim();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    long id = kanal.naechsteGuiId();
                    UUID als = betrachter == null ? null : betrachter.getUniqueId();
                    var antwort = kanal.fragePlatzhalterAn(
                            new Protokoll.PlatzhalterAnfrage(id, als, ziel), TIMEOUT_MILLIS);
                    if (antwort != null && antwort.erfolg()) {
                        long jetztPut = System.currentTimeMillis();
                        begrenze(jetztPut);
                        cache.put(schluessel, new Eintrag(antwort, jetztPut));
                    }
                } catch (Exception e) {
                    LOG.debug("Platzhalter-Refresh fehlgeschlagen.", e);
                } finally {
                    laufendeRefreshs.remove(schluessel);
                }
            });
        } catch (Exception e) {
            laufendeRefreshs.remove(schluessel);
            LOG.debug("Platzhalter-Refresh konnte nicht geplant werden.", e);
        }
    }

    public void vergessen(UUID spielerUuid) {
        if (spielerUuid != null) {
            cache.remove(spielerUuid);
        }
    }

    private static UUID schluesselFuer(Player betrachter, String zielName) {
        if (zielName != null && !zielName.isBlank()) {
            return UUID.nameUUIDFromBytes(
                    ("punish-papi:" + zielName.trim().toLowerCase(Locale.ROOT))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return betrachter == null ? null : betrachter.getUniqueId();
    }
}
