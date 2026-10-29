package de.lightrixx.punish.service;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.util.ZeitFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class IpBanService {

    private static final Logger LOG = LoggerFactory.getLogger(IpBanService.class);

    private final IpBanRepository ipBanRepository;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final BanKickHelper kickHelper;

    private final ProtectionService protectionService;
    private final SpielerVerzeichnis verzeichnis;
    private volatile de.lightrixx.punish.storage.PlayerCache playerCache;

    public void mitPlayerCache(de.lightrixx.punish.storage.PlayerCache playerCache) {
        this.playerCache = playerCache;
    }

    public IpBanService(IpBanRepository ipBanRepository,
                        HistoryService historyService,
                        BroadcastService broadcastService,
                        LogWriter logWriter,
                        BanKickHelper kickHelper,
                        ProtectionService protectionService,
                        SpielerVerzeichnis verzeichnis) {
        this.ipBanRepository = Objects.requireNonNull(ipBanRepository, "Das IpBanRepository darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.kickHelper = Objects.requireNonNull(kickHelper, "Der BanKickHelper darf nicht null sein.");
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    private boolean istIpDurchOnlineSchutzBlockiert(String ip) {
        if (IpSchutzHelper.istIpDurchOnlineSchutzBlockiert(ip, protectionService, verzeichnis,
                ProtectionService.IgnoreTyp.BAN)) {
            return true;
        }
        de.lightrixx.punish.storage.PlayerCache cache = this.playerCache;
        if (cache != null) {
            return IpSchutzHelper.istIpDurchOfflineSchutzBlockiert(ip, protectionService, cache,
                    ProtectionService.IgnoreTyp.BAN);
        }
        return false;
    }

    public boolean ipBannen(String ip, String grund, String ausfuehrer) {
        return ipBannen(ip, grund, ausfuehrer, ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean ipBannen(String ip, String grund, String ausfuehrer, ServerScope scope, String herkunft) {
        return ipBannen(ip, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean ipBannen(String ip, String grund, String ausfuehrer, ServerScope scope, String herkunft,
                            boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.warn("ipBannen für '{}' abgewiesen: ungültige IP.", ip);
            return false;
        }
        if (istIpDurchOnlineSchutzBlockiert(ip)) {
            LOG.warn("ipBannen für {} abgewiesen: Online-Spieler mit dieser IP ist geschützt.", ip);
            return false;
        }
        long jetzt = System.currentTimeMillis();
        var bestehender = ipBanRepository.finden(ip, wirksam)
                .filter(b -> b.istAktiv(jetzt));
        if (bestehender.isPresent()) {
            IpBanRecord neu = new IpBanRecord(bestehender.get().ip(), grund, ausfuehrer, jetzt, null,
                    bestehender.get().scope());
            var vorher = ipBanRepository.ersetzenWenn(neu.ip(), bestehender.get().scope(),
                    b -> b.equals(bestehender.get()), neu);
            if (vorher.isPresent()) {
                Map<String, String> editPlatzhalter = BanKickHelper.platzhalter(
                        "", ausfuehrer, grund, Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, neu.ip());
                editPlatzhalter.put("scope", neu.scope().anzeige());
                editPlatzhalter.put("herkunft", herkunftNorm);
                try {
                    historyService.hinzufuegen(HistoryType.IP_BAN_EDIT, ausfuehrer, neu.ip(), grund, null,
                            neu.scope(), herkunftNorm);
                } catch (Exception e) {
                    LOG.warn("IP-Bann-Eskalations-History für {} konnte nicht geschrieben werden.", ip, e);
                }
                kickHelper.onlineSpielerMitIpKicken(neu.ip(), grund, ausfuehrer,
                        ProtectionService.IgnoreTyp.BAN);
                broadcastService.senden(MessageKeys.BROADCAST_BAN_IP, editPlatzhalter, silent);
                logWriter.schreiben(HistoryType.IP_BAN_EDIT, ausfuehrer, neu.ip(),
                        "Auf permanent eskaliert. Vorheriger Grund: " + vorher.get().grund(),
                        neu.scope(), herkunftNorm);
                return true;
            }
            LOG.warn("IP-Permabann für {} nach paralleler Änderung abgebrochen (kein Resurrect nach Pardon).", ip);
            return false;
        }
        IpBanRecord record = new IpBanRecord(ip, grund, ausfuehrer, jetzt, null, wirksam);
        if (!ipBanRepository.bannenWennExaktFrei(record)) {
            LOG.warn("IP-Permabann für {} verworfen: parallel vergeben (kein Doppel-Eintrag, keine Doppel-History).", ip);
            return false;
        }

        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                "", ausfuehrer, grund, Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, record.ip());
        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        try {
            historyService.hinzufuegen(HistoryType.BAN_IP, ausfuehrer, record.ip(), grund, null,
                    wirksam, herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Perm-IP-Bann-History für {} konnte nicht geschrieben werden (Bann bleibt aktiv).", ip, e);
        }
        kickHelper.onlineSpielerMitIpKicken(record.ip(), grund, ausfuehrer,
                ProtectionService.IgnoreTyp.BAN);
        broadcastService.senden(MessageKeys.BROADCAST_BAN_IP, platzhalter, silent);
        logWriter.schreiben(HistoryType.BAN_IP, ausfuehrer, record.ip(), "Grund: " + grund,
                wirksam, herkunftNorm);
        return true;
    }

    public boolean ipBannenTemporaer(String ip, long dauerMillis, String grund, String ausfuehrer) {
        return ipBannenTemporaer(ip, dauerMillis, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean ipBannenTemporaer(String ip, long dauerMillis, String grund, String ausfuehrer,
                                     ServerScope scope, String herkunft) {
        return ipBannenTemporaer(ip, dauerMillis, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean ipBannenTemporaer(String ip, long dauerMillis, String grund, String ausfuehrer,
                                     ServerScope scope, String herkunft, boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.warn("ipBannenTemporaer für '{}' abgewiesen: ungültige IP.", ip);
            return false;
        }
        if (istIpDurchOnlineSchutzBlockiert(ip)) {
            LOG.warn("ipBannenTemporaer für {} abgewiesen: Online-Spieler mit dieser IP ist geschützt.", ip);
            return false;
        }
        if (dauerMillis <= 0) {
            LOG.warn("ipBannenTemporaer: ungültige Dauer {} für {} – abgelehnt.", dauerMillis, ip);
            return false;
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn("ipBannenTemporaer: Ablauf-Überlauf (jetzt={}, dauer={}) für {} – als DAUER_UNGUELTIG werten",
                    jetzt, dauerMillis, ip, e);
            return false;
        }
        IpBanRecord record = new IpBanRecord(ip, grund, ausfuehrer, jetzt, ablaufAm, wirksam);
        if (!ipBanRepository.bannenWennFrei(record)) {
            return false;
        }

        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                "", ausfuehrer, grund,
                ZeitFormat.formatRestzeit(dauerMillis),
                ZeitFormat.formatRestzeit(dauerMillis),
                ZeitFormat.formatZeitpunkt(ablaufAm), record.ip());
        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        try {
            historyService.hinzufuegen(HistoryType.BAN_IP, ausfuehrer, record.ip(), grund, ablaufAm,
                    wirksam, herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Temp-IP-Bann-History für {} konnte nicht geschrieben werden (Bann bleibt aktiv).", ip, e);
        }
        kickHelper.onlineSpielerMitIpKicken(record.ip(), grund, ausfuehrer, dauerMillis, ablaufAm,
                ProtectionService.IgnoreTyp.BAN);
        broadcastService.senden(MessageKeys.BROADCAST_BAN_IP, platzhalter, silent);
        logWriter.schreiben(HistoryType.BAN_IP, ausfuehrer, record.ip(),
                "Grund: " + grund + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm),
                wirksam, herkunftNorm);
        return true;
    }

    public Optional<IpBanRecord> ipBannBearbeiten(String ip, Long dauerMillis,
                                                  String grund, String ausfuehrer,
                                                  ServerScope scopeSelektor, String herkunft) {
        return ipBannBearbeiten(ip, dauerMillis, grund, ausfuehrer, scopeSelektor, herkunft, false);
    }

    public Optional<IpBanRecord> ipBannBearbeiten(String ip, Long dauerMillis,
                                                  String grund, String ausfuehrer,
                                                  ServerScope scopeSelektor, String herkunft, boolean silent) {
        Objects.requireNonNull(scopeSelektor, "Der Scope-Selektor darf nicht null sein.");
        if (istIpDurchOnlineSchutzBlockiert(ip)) {
            LOG.warn("ipBannBearbeiten für {} abgewiesen: Online-Spieler mit dieser IP ist geschützt.", ip);
            return Optional.empty();
        }
        if (dauerMillis != null && dauerMillis <= 0) {
            LOG.warn("ipBannBearbeiten: ungültige Dauer {} für {} – abgelehnt.", dauerMillis, ip);
            return Optional.empty();
        }
        long jetzt = System.currentTimeMillis();
        Long ablaufAm = null;
        if (dauerMillis != null) {
            try {
                ablaufAm = Math.addExact(jetzt, dauerMillis);
            } catch (ArithmeticException e) {
                LOG.warn("ipBannBearbeiten: Ablauf-Überlauf (jetzt={}, dauer={}) für {} – als DAUER_UNGUELTIG werten",
                        jetzt, dauerMillis, ip, e);
                return Optional.empty();
            }
        }

        Optional<IpBanRecord> alt = ipBanRepository.findeAbdeckend(ip, scopeSelektor, jetzt);
        if (alt.isEmpty()) {
            return Optional.empty();
        }
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        IpBanRecord neu = new IpBanRecord(alt.get().ip(),
                grund != null ? grund : alt.get().grund(),
                ausfuehrer, jetzt, ablaufAm, alt.get().scope());

        Optional<IpBanRecord> vorher = ipBanRepository.ersetzenWenn(
                neu.ip(), alt.get().scope(), bann -> bann.equals(alt.get()), neu);
        if (vorher.isEmpty()) {
            return Optional.empty();
        }

        String dauerText = dauerMillis != null
            ? ZeitFormat.formatRestzeit(dauerMillis.longValue())
            : Limits.PERMANENT_ANZEIGE;
        String ablaufText = ablaufAm != null ? ZeitFormat.formatZeitpunkt(ablaufAm.longValue()) : Limits.PERMANENT_ANZEIGE;
        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                "", ausfuehrer, neu.grund(), dauerText, dauerText, ablaufText, neu.ip());
        platzhalter.put("scope", neu.scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        try {
            historyService.hinzufuegen(HistoryType.IP_BAN_EDIT, ausfuehrer, neu.ip(),
                    neu.grund(), ablaufAm, neu.scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("IP-Bann-Edit-History für {} konnte nicht geschrieben werden (Bann bleibt aktiv).", ip, e);
        }
        kickHelper.onlineSpielerMitIpKicken(neu.ip(), neu.grund(), ausfuehrer, dauerMillis, ablaufAm,
                ProtectionService.IgnoreTyp.BAN);
        broadcastService.senden(MessageKeys.BROADCAST_BAN_IP, platzhalter, silent);
        logWriter.schreiben(HistoryType.IP_BAN_EDIT, ausfuehrer, neu.ip(),
                "Grund: " + neu.grund() + ", Dauer: " + dauerText + ", Ablauf: " + ablaufText
                        + ", Vorheriger Grund: " + vorher.get().grund(),
                neu.scope(), herkunftNorm);
        return Optional.of(neu);
    }

    public Optional<IpBanRecord> ipFinden(String ip, ServerScope scope) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return ipBanRepository.finden(ip, scope);
    }

    public Optional<IpBanRecord> ipFindeAbdeckend(String ip, ServerScope ziel) {
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return ipBanRepository.findeAbdeckend(ip, ziel, System.currentTimeMillis());
    }

    public java.util.List<IpBanRecord> findeAktiveIpBannsFuerTab() {
        long jetzt = System.currentTimeMillis();
        return ipBanRepository.alle().stream()
                .filter(b -> b != null && b.istAktiv(jetzt))
                .toList();
    }

    public Optional<IpBanRecord> ipEntbannen(String ip, String ausfuehrer,
                                             ServerScope scope, String herkunft) {
        return ipEntbannen(ip, ausfuehrer, scope, herkunft, false);
    }

    public Optional<IpBanRecord> ipEntbannen(String ip, String ausfuehrer,
                                             ServerScope scope, String herkunft, boolean silent) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        long jetzt = System.currentTimeMillis();
        Optional<IpBanRecord> aktiv = ipBanRepository.finden(ip, scope)
                .filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            ipBanRepository.finden(ip, scope).ifPresent(abgelaufen -> {
                if (abgelaufen.istAbgelaufen(jetzt)) {
                    Optional<IpBanRecord> weg = ipBanRepository.entbannenWenn(ip, scope,
                            b -> b.equals(abgelaufen));
                    if (weg.isPresent()) {
                        IpBanRecord rec = weg.get();
                        try {
                            historyService.hinzufuegen(HistoryType.UNBAN_IP, "System", rec.ip(),
                                    "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                                    rec.scope(), herkunft == null || herkunft.isBlank()
                                            ? ServerScope.UNBEKANNT_WERT
                                            : herkunft.trim().toLowerCase(java.util.Locale.ROOT));
                        } catch (Exception e) {
                            LOG.debug("Ablauf-History für IP {} konnte nicht geschrieben werden.", rec.ip(), e);
                        }
                    }
                }
            });
            return Optional.empty();
        }
        Optional<IpBanRecord> entfernt = ipBanRepository.entbannenWenn(ip, scope,
                b -> b.equals(aktiv.get()));
        if (entfernt.isEmpty()) {
            return Optional.empty();
        }
        IpBanRecord record = entfernt.get();
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                "", ausfuehrer, record.grund(), Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, record.ip());
        platzhalter.put("ip", record.ip());
        platzhalter.put("scope", record.scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);
        try {
            historyService.hinzufuegen(HistoryType.UNBAN_IP, ausfuehrer, record.ip(),
                    "IP-Bann aufgehoben (Grund war: " + record.grund() + ")", null,
                    record.scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Unban-IP-History für {} konnte nicht geschrieben werden (Unban bleibt wirksam).", record.ip(), e);
        }
        broadcastService.senden(MessageKeys.BROADCAST_UNBAN_IP, platzhalter, silent);
        logWriter.schreiben(HistoryType.UNBAN_IP, ausfuehrer, record.ip(),
                "Vorheriger Grund: " + record.grund(), record.scope(), herkunftNorm);
        return entfernt;
    }

    public int bereinigeAbgelaufeneMitHistory(String ausfuehrer) {
        long jetzt = System.currentTimeMillis();
        java.util.List<IpBanRecord> kandidaten;
        try {
            kandidaten = ipBanRepository.alle().stream()
                    .filter(b -> b != null && b.istAbgelaufen(jetzt))
                    .toList();
        } catch (Exception e) {
            LOG.warn("Ablauf-Scan für IP-Bann-Cleanup fehlgeschlagen.", e);
            return 0;
        }
        int anzahl = 0;
        for (IpBanRecord kandidat : kandidaten) {
            if (bereinigeAblauf(kandidat.ip(), ausfuehrer)) {
                anzahl++;
            }
        }
        return anzahl;
    }

    public boolean bereinigeAblauf(String ip, String ausfuehrer) {

        boolean einerEntfernt = false;
        for (IpBanRecord kandidat : List.copyOf(ipBanRepository.alleFuer(ip))) {
            if (kandidat == null || !kandidat.istAbgelaufen(System.currentTimeMillis())) {
                continue;
            }
            Optional<IpBanRecord> entfernt;
            try {
                entfernt = ipBanRepository.entbannenWenn(ip, kandidat.scope(),
                        b -> b.equals(kandidat) && b.istAbgelaufen(System.currentTimeMillis()));
            } catch (Exception e) {
                LOG.warn("Ablauf-Cleanup für IP {} konnte nicht entfernt werden.",
                        kandidat.ip(), e);
                continue;
            }
            if (entfernt.isEmpty()) {
                continue;
            }
            IpBanRecord rec = entfernt.get();
            try {
                historyService.hinzufuegen(HistoryType.UNBAN_IP, ausfuehrer, rec.ip(),
                        "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                        rec.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.warn("Ablauf-History für IP {} konnte nicht geschrieben werden.", rec.ip(), e);
            }
            logWriter.schreiben(HistoryType.UNBAN_IP, ausfuehrer, rec.ip(),
                    "Abgelaufen. Vorheriger Grund: " + rec.grund(), rec.scope(), ServerScope.UNBEKANNT_WERT);
            einerEntfernt = true;
        }
        return einerEntfernt;
    }

    public boolean istAktivFuer(String ip, String aktuellerServer) {
        long jetzt = System.currentTimeMillis();
        return ipBanRepository.alleFuer(ip).stream()
                .anyMatch(b -> b != null && b.istAktivFuer(jetzt, aktuellerServer));
    }

    public boolean istAbgedeckt(String ip, ServerScope ziel) {
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return ipBanRepository.istAbgedeckt(ip, ziel, System.currentTimeMillis());
    }

    public Optional<IpBanRecord> findeWirksam(String ip, String aktuellerServer) {
        long jetzt = System.currentTimeMillis();
        IpBanRecord exakt = null;
        IpBanRecord abdeckend = null;
        for (IpBanRecord kandidat : ipBanRepository.alleFuer(ip)) {
            if (kandidat == null || !kandidat.istAktivFuer(jetzt, aktuellerServer)) {
                continue;
            }
            if (aktuellerServer != null && !aktuellerServer.isBlank()
                    && kandidat.scope().typ() == ServerScope.Typ.SERVER
                    && kandidat.scope().server() != null
                    && kandidat.scope().server().equalsIgnoreCase(aktuellerServer.trim())) {
                exakt = kandidat;
                break;
            }
            if (abdeckend == null) {
                abdeckend = kandidat;
            }
        }
        return Optional.ofNullable(exakt != null ? exakt : abdeckend);
    }
}
