package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpMuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.util.ZeitFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class IpMuteService {

    private static final Logger LOG = LoggerFactory.getLogger(IpMuteService.class);

    private final IpMuteRepository repository;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final MessageService messageService;
    private final AuftragsPlaner taskScheduler;
    private final ProtectionService protectionService;
    private final SpielerVerzeichnis verzeichnis;
    private volatile de.lightrixx.punish.storage.PlayerCache playerCache;

    public void mitPlayerCache(de.lightrixx.punish.storage.PlayerCache playerCache) {
        this.playerCache = playerCache;
    }

    public interface IpMuteBeobachter {
        void ipMuteGeaendert();
    }

    private final java.util.Set<IpMuteBeobachter> beobachter =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void beobachterHinzufuegen(IpMuteBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.add(beobachter);
        }
    }

    public void beobachterEntfernen(IpMuteBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.remove(beobachter);
        }
    }

    private void benachrichtige() {
        if (beobachter.isEmpty()) {
            return;
        }
        for (IpMuteBeobachter b : beobachter) {
            try {
                b.ipMuteGeaendert();
            } catch (Exception e) {
                LOG.debug("IP-Mute-Beobachter fehlgeschlagen.", e);
            }
        }
    }

    public IpMuteService(IpMuteRepository repository,
                         HistoryService historyService,
                         BroadcastService broadcastService,
                         LogWriter logWriter,
                         MessageService messageService,
                         AuftragsPlaner taskScheduler,
                         ProtectionService protectionService,
                         SpielerVerzeichnis verzeichnis) {
        this.repository = Objects.requireNonNull(repository, "Das IpMuteRepository darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.messageService = Objects.requireNonNull(messageService, "Der MessageService darf nicht null sein.");
        this.taskScheduler = taskScheduler;
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    private boolean istIpDurchOnlineSchutzBlockiert(String ip) {
        if (IpSchutzHelper.istIpDurchOnlineSchutzBlockiert(ip, protectionService, verzeichnis,
                ProtectionService.IgnoreTyp.MUTE)) {
            return true;
        }
        de.lightrixx.punish.storage.PlayerCache cache = this.playerCache;
        if (cache != null) {
            return IpSchutzHelper.istIpDurchOfflineSchutzBlockiert(ip, protectionService, cache,
                    ProtectionService.IgnoreTyp.MUTE);
        }
        return false;
    }

    public boolean muten(String ip, long dauerMillis, String grund, String ausfuehrer) {
        return muten(ip, dauerMillis, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean muten(String ip, long dauerMillis, String grund, String ausfuehrer,
                         ServerScope scope, String herkunft) {
        return muten(ip, dauerMillis, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean muten(String ip, long dauerMillis, String grund, String ausfuehrer,
                         ServerScope scope, String herkunft, boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.warn("ipMuten für '{}' abgewiesen: ungültige IP.", ip);
            return false;
        }
        if (istIpDurchOnlineSchutzBlockiert(kanonisch)) {
            LOG.warn("ipMuten für {} abgewiesen: Online-Spieler mit dieser IP ist geschützt.", kanonisch);
            return false;
        }
        if (dauerMillis <= 0) {
            LOG.warn("ipMuten: ungültige Dauer {} für {} – abgelehnt.", dauerMillis, kanonisch);
            return false;
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn("ipMuten: Ablauf-Überlauf für {} – als DAUER_UNGUELTIG werten", kanonisch, e);
            return false;
        }
        IpMuteRecord record;
        try {
            record = new IpMuteRecord(kanonisch, grund, ausfuehrer, jetzt, ablaufAm, wirksam);
        } catch (Exception e) {
            LOG.warn("ipMuten für {} abgewiesen: ungültige Eingabedaten.", kanonisch, e);
            return false;
        }
        if (!repository.mutenWennFrei(record)) {
            return false;
        }

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("ip", kanonisch);
        platzhalter.put("spieler", kanonisch);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        platzhalter.put("dauer", ZeitFormat.formatRestzeit(dauerMillis));
        platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(ablaufAm));
        platzhalter.put("verbleibend", ZeitFormat.formatRestzeit(dauerMillis));
        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        zieleInformieren(kanonisch, MessageKeys.ZIEL_GEMUTET_IP, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.IP_MUTE, ausfuehrer, kanonisch, grund, ablaufAm,
                    wirksam, herkunftNorm);
        } catch (Exception e) {
            LOG.warn("IP-Mute-History für {} konnte nicht geschrieben werden (Mute bleibt aktiv).", kanonisch, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_IP_MUTE, platzhalter, silent);
        logWriter.schreiben(HistoryType.IP_MUTE, ausfuehrer, kanonisch,
                "Grund: " + grund + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm),
                wirksam, herkunftNorm);
        benachrichtige();
        return true;
    }

    public boolean entmuten(String ip, String ausfuehrer, ServerScope scope, String herkunft) {
        return entmuten(ip, ausfuehrer, scope, herkunft, false);
    }

    public boolean entmuten(String ip, String ausfuehrer, ServerScope scope, String herkunft, boolean silent) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.warn("ipUnmute für '{}' abgewiesen: ungültige IP.", ip);
            return false;
        }
        Optional<IpMuteRecord> aktuell = repository.finden(kanonisch, scope);
        if (aktuell.isPresent() && aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
            repository.entmutenWenn(kanonisch, scope, b -> b.equals(aktuell.get()));
            try {
                historyService.hinzufuegen(HistoryType.IP_UNMUTE, "System", kanonisch,
                        "Abgelaufen (Grund war: " + aktuell.get().grund() + ")", null,
                        scope, normiereHerkunft(herkunft));
            } catch (Exception e) {
                LOG.debug("Ablauf-History für IP-Mute {} konnte nicht geschrieben werden.", kanonisch, e);
            }
            benachrichtige();
            return false;
        }
        if (aktuell.isEmpty()) {
            return false;
        }
        Optional<IpMuteRecord> entfernt = repository.entmutenWenn(kanonisch, scope,
                b -> b.equals(aktuell.get()));
        if (entfernt.isEmpty()) {
            return false;
        }
        String herkunftNorm = normiereHerkunft(herkunft);
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("ip", kanonisch);
        platzhalter.put("spieler", kanonisch);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("scope", entfernt.get().scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        zieleInformieren(kanonisch, MessageKeys.ZIEL_ENTMUTET_IP, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.IP_UNMUTE, ausfuehrer, kanonisch,
                    "Aufgehoben (Grund war: " + entfernt.get().grund() + ")", null,
                    entfernt.get().scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("IP-Unmute-History für {} konnte nicht geschrieben werden.", kanonisch, e);
        }
        broadcastService.senden(MessageKeys.BROADCAST_IP_UNMUTE, platzhalter, silent);
        logWriter.schreiben(HistoryType.IP_UNMUTE, ausfuehrer, kanonisch,
                "Vorheriger Grund: " + entfernt.get().grund(), entfernt.get().scope(), herkunftNorm);
        benachrichtige();
        return true;
    }

    public Optional<IpMuteRecord> bearbeiten(String ip, long dauerMillis, String grund, String ausfuehrer,
                                             ServerScope scopeSelektor, String herkunft) {
        return bearbeiten(ip, dauerMillis, grund, ausfuehrer, scopeSelektor, herkunft, false);
    }

    public Optional<IpMuteRecord> bearbeiten(String ip, long dauerMillis, String grund, String ausfuehrer,
                                             ServerScope scopeSelektor, String herkunft, boolean silent) {
        Objects.requireNonNull(scopeSelektor, "Der Scope-Selektor darf nicht null sein.");
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.warn("ipMute-bearbeiten abgewiesen: ungültige IP.", e);
            return Optional.empty();
        }
        if (istIpDurchOnlineSchutzBlockiert(kanonisch)) {
            LOG.warn("ipMute-bearbeiten für {} abgewiesen: geschützt.", kanonisch);
            return Optional.empty();
        }
        if (dauerMillis <= 0) {
            LOG.warn("ipMute-bearbeiten für {} abgewiesen: ungültige Dauer {}.", kanonisch, dauerMillis);
            return Optional.empty();
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn("ipMute-bearbeiten für {} abgewiesen: Ablauf-Überlauf.", kanonisch, e);
            return Optional.empty();
        }
        Optional<IpMuteRecord> alt = repository.findeAbdeckend(kanonisch, scopeSelektor, jetzt);
        if (alt.isEmpty()) {
            LOG.warn("ipMute-bearbeiten für {} abgewiesen: kein abdeckender Eintrag.", kanonisch);
            return Optional.empty();
        }
        String herkunftNorm = normiereHerkunft(herkunft);
        IpMuteRecord neu = new IpMuteRecord(kanonisch,
                grund != null ? grund : alt.get().grund(),
                ausfuehrer, jetzt, ablaufAm, alt.get().scope());
        Optional<IpMuteRecord> vorher = repository.ersetzenWenn(
                kanonisch, alt.get().scope(), m -> m.equals(alt.get()), neu);
        if (vorher.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("ip", kanonisch);
        platzhalter.put("spieler", kanonisch);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", neu.grund());
        platzhalter.put("dauer", ZeitFormat.formatRestzeit(dauerMillis));
        platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(ablaufAm));
        platzhalter.put("verbleibend", ZeitFormat.formatRestzeit(dauerMillis));
        platzhalter.put("scope", neu.scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        zieleInformieren(kanonisch, MessageKeys.ZIEL_MUTE_BEARBEITET_IP, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.IP_MUTE_EDIT, ausfuehrer, kanonisch,
                    neu.grund(), ablaufAm, neu.scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("IP-Mute-Edit-History für {} konnte nicht geschrieben werden.", kanonisch, e);
        }
        broadcastService.senden(MessageKeys.BROADCAST_IP_MUTE_EDIT, platzhalter, silent);
        logWriter.schreiben(HistoryType.IP_MUTE_EDIT, ausfuehrer, kanonisch,
                "Grund: " + neu.grund() + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm)
                        + ", Vorheriger Grund: " + vorher.get().grund(),
                neu.scope(), herkunftNorm);
        benachrichtige();
        return Optional.of(neu);
    }

    public boolean istAbgedeckt(String ip, ServerScope ziel) {
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return repository.istAbgedeckt(ip, ziel, System.currentTimeMillis());
    }

    public Optional<IpMuteRecord> finden(String ip, ServerScope scope) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return repository.finden(ip, scope);
    }

    public Optional<IpMuteRecord> findeAbdeckend(String ip, ServerScope ziel) {
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return repository.findeAbdeckend(ip, ziel, System.currentTimeMillis());
    }

    public boolean istGemutetFuer(String ip, String aktuellerServer) {
        long jetzt = System.currentTimeMillis();
        return repository.alleFuer(ip).stream()
                .anyMatch(m -> m != null && m.istAktivFuer(jetzt, aktuellerServer));
    }

    public Optional<IpMuteRecord> findeAktivenMuteFuer(String ip, long jetztMillis, String aktuellerServer) {
        IpMuteRecord exakt = null;
        IpMuteRecord abdeckend = null;
        for (IpMuteRecord kandidat : repository.alleFuer(ip)) {
            if (kandidat == null || !kandidat.istAktivFuer(jetztMillis, aktuellerServer)) {
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

    public List<IpMuteRecord> findeAktiveMutesFuerTab() {
        long jetzt = System.currentTimeMillis();
        return repository.alle().stream()
                .filter(m -> m != null && m.istAktiv(jetzt))
                .toList();
    }

    public int bereinigeAbgelaufeneMitHistory(String ausfuehrer) {
        long jetzt = System.currentTimeMillis();
        List<IpMuteRecord> kandidaten;
        try {
            kandidaten = repository.alle().stream()
                    .filter(m -> m != null && m.istAbgelaufen(jetzt))
                    .toList();
        } catch (Exception e) {
            LOG.warn("Ablauf-Scan für IP-Mute-Cleanup fehlgeschlagen.", e);
            return 0;
        }
        int anzahl = 0;
        boolean geaendert = false;
        for (IpMuteRecord kandidat : kandidaten) {
            try {
                Optional<IpMuteRecord> aktuell = repository.finden(kandidat.ip(), kandidat.scope());
                if (aktuell.isEmpty() || !aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
                    continue;
                }
                Optional<IpMuteRecord> entfernt = repository.entmuten(kandidat.ip(), kandidat.scope());
                if (entfernt.isEmpty()) {
                    continue;
                }
                IpMuteRecord rec = entfernt.get();
                try {
                    historyService.hinzufuegen(HistoryType.IP_UNMUTE, ausfuehrer, rec.ip(),
                            "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                            rec.scope(), ServerScope.UNBEKANNT_WERT);
                } catch (Exception e) {
                    LOG.warn("Ablauf-History für IP-Mute {} konnte nicht geschrieben werden.", rec.ip(), e);
                }
                logWriter.schreiben(HistoryType.IP_UNMUTE, ausfuehrer, rec.ip(),
                        "Abgelaufen. Vorheriger Grund: " + rec.grund(), rec.scope(), ServerScope.UNBEKANNT_WERT);
                anzahl++;
                geaendert = true;
            } catch (Exception e) {
                LOG.warn("IP-Mute-Ablauf-Cleanup fehlgeschlagen.", e);
            }
        }
        if (geaendert) {
            benachrichtige();
        }
        return anzahl;
    }

    private static String normiereHerkunft(String herkunft) {
        return herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void zieleInformieren(String kanonischeIp, String schluessel, Map<String, String> platzhalter) {
        Runnable versand = () -> {
            try {
                for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                    if (online == null || online.ip() == null || online.ip().isBlank()
                            || "unbekannt".equalsIgnoreCase(online.ip().trim())) {
                        continue;
                    }
                    try {
                        if (!kanonischeIp.equals(IpBanRecord.kanonisieren(online.ip().trim()))) {
                            continue;
                        }
                    } catch (Exception ignored) {
                        continue;
                    }
                    try {
                        messageService.anSpieler(online.uuid(), schluessel, platzhalter, verzeichnis);
                    } catch (Exception e) {
                        LOG.debug("IP-Mute-Info an {} fehlgeschlagen.", online.name(), e);
                    }
                }
            } catch (Exception e) {
                LOG.debug("IP-Mute-Zielversand fehlgeschlagen.", e);
            }
        };
        if (!verzeichnis.darfDirektAusfuehren()) {
            if (taskScheduler != null) {
                try {
                    taskScheduler.aufgabe(versand);
                } catch (IllegalStateException e) {
                    LOG.debug("IP-Mute-Info nach Shutdown verworfen für {}", kanonischeIp);
                }
            }
            return;
        }
        versand.run();
    }
}
