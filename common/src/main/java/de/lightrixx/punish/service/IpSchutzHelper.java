package de.lightrixx.punish.service;

import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.IpBanRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public final class IpSchutzHelper {

    private static final Logger LOG = LoggerFactory.getLogger(IpSchutzHelper.class);

    private IpSchutzHelper() {
    }

    public static boolean istIpDurchOnlineSchutzBlockiert(String ip, ProtectionService protectionService,
                                                           SpielerVerzeichnis verzeichnis) {
        return istIpDurchOnlineSchutzBlockiert(ip, protectionService, verzeichnis, null);
    }

    public static boolean istIpDurchOnlineSchutzBlockiert(String ip, ProtectionService protectionService,
                                                           SpielerVerzeichnis verzeichnis,
                                                           ProtectionService.IgnoreTyp typ) {
        if (ip == null) {
            return false;
        }
        if (protectionService == null || verzeichnis == null) {
            LOG.warn("IP-Schutzcheck ohne Protection/Verzeichnis, blocke sicherheitshalber.");
            return true;
        }
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.trace("IP-Schutzcheck: ungültige IP.", e);
            return false;
        }
        try {
            for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                if (online == null) {
                    continue;
                }
                String spielerIp = online.ip();
                if (spielerIp == null || spielerIp.isBlank()
                        || "unbekannt".equalsIgnoreCase(spielerIp.trim())) {
                    continue;
                }
                try {
                    boolean geschuetzt = typ == null
                            ? protectionService.istOnlineGeschuetzt(online)
                            : protectionService.istOnlineGeschuetzt(online, typ);
                    if (kanonisch.equals(IpBanRecord.kanonisieren(spielerIp.trim()))
                            && geschuetzt) {
                        return true;
                    }
                } catch (Exception e) {
                    LOG.warn("IP-Schutzcheck für Online-Spieler fehlgeschlagen, blocke sicherheitshalber.", e);
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.warn("Online-IP-Schutzcheck fehlgeschlagen, blocke sicherheitshalber.", e);
            return true;
        }
        return false;
    }

    public static boolean istIpDurchSchutzBlockiert(String ip, ProtectionService protectionService,
                                                       SpielerVerzeichnis verzeichnis,
                                                       de.lightrixx.punish.storage.PlayerCache playerCache,
                                                       ProtectionService.IgnoreTyp typ) {
        if (istIpDurchOnlineSchutzBlockiert(ip, protectionService, verzeichnis, typ)) {
            return true;
        }
        return istIpDurchOfflineSchutzBlockiert(ip, protectionService, playerCache, typ);
    }

    public static boolean istIpDurchOfflineSchutzBlockiert(String ip, ProtectionService protectionService,
                                                           de.lightrixx.punish.storage.PlayerCache playerCache,
                                                           ProtectionService.IgnoreTyp typ) {
        if (ip == null) {
            return false;
        }
        if (protectionService == null || playerCache == null) {
            LOG.warn("Offline-IP-Schutzcheck ohne Protection/Cache, blocke sicherheitshalber.");
            return true;
        }
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.trace("Offline-IP-Schutzcheck: ungültige IP.", e);
            return false;
        }
        try {
            java.util.List<de.lightrixx.punish.model.CachedPlayer> kandidaten =
                    playerCache.mitKanonischerIp(kanonisch);
            if (kandidaten == null || kandidaten.isEmpty()) {
                return false;
            }
            for (de.lightrixx.punish.model.CachedPlayer kandidat : kandidaten) {
                if (kandidat == null || kandidat.uuid() == null) {
                    continue;
                }
                try {
                    boolean geschuetzt = typ == null
                            ? protectionService.istGeschuetzt(kandidat.uuid())
                            : protectionService.istGeschuetzt(kandidat.uuid(), typ);
                    if (geschuetzt) {
                        LOG.debug("Offline-IP-Schutz: {} teilt IP mit geschütztem Spieler {}.",
                                kanonisch, kandidat.uuid());
                        return true;
                    }
                } catch (Exception e) {
                    LOG.warn("Offline-IP-Schutzcheck für {} fehlgeschlagen, blocke sicherheitshalber.",
                            kandidat.uuid(), e);
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.warn("Offline-IP-Schutzcheck fehlgeschlagen, blocke sicherheitshalber.", e);
            return true;
        }
        return false;
    }

    public static String kanonisiereOderNull(String ip) {
        Objects.requireNonNull(ip, "Die IP darf nicht null sein.");
        try {
            return IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.trace("IP konnte nicht kanonisiert werden.", e);
            return null;
        }
    }
}
