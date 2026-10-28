package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpTimeoutRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.ZeitFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class IpTimeoutService {

    private static final Logger LOG = LoggerFactory.getLogger(IpTimeoutService.class);

    private final IpTimeoutRepository repository;
    private final Konfiguration configManager;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final AuftragsPlaner taskScheduler;
    private final ProtectionService protectionService;
    private final SpielerVerzeichnis verzeichnis;
    private volatile de.lightrixx.punish.storage.PlayerCache playerCache;

    public void mitPlayerCache(de.lightrixx.punish.storage.PlayerCache playerCache) {
        this.playerCache = playerCache;
    }
    private final BanKickHelper kickHelper;
    private final MiniMessage miniMessage = MiniMessageUtil.miniMessage();

    public IpTimeoutService(IpTimeoutRepository repository,
                            Konfiguration configManager,
                            HistoryService historyService,
                            BroadcastService broadcastService,
                            LogWriter logWriter,
                            AuftragsPlaner taskScheduler,
                            ProtectionService protectionService,
                            BanKickHelper kickHelper,
                            SpielerVerzeichnis verzeichnis) {
        this.repository = Objects.requireNonNull(repository, "Das IpTimeoutRepository darf nicht null sein.");
        this.configManager = Objects.requireNonNull(configManager, "Die Konfiguration darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.taskScheduler = taskScheduler;
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.kickHelper = Objects.requireNonNull(kickHelper, "Der BanKickHelper darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    private boolean istIpDurchOnlineSchutzBlockiert(String ip) {
        if (IpSchutzHelper.istIpDurchOnlineSchutzBlockiert(ip, protectionService, verzeichnis,
                ProtectionService.IgnoreTyp.TIMEOUT)) {
            return true;
        }
        de.lightrixx.punish.storage.PlayerCache cache = this.playerCache;
        if (cache != null) {
            return IpSchutzHelper.istIpDurchOfflineSchutzBlockiert(ip, protectionService, cache,
                    ProtectionService.IgnoreTyp.TIMEOUT);
        }
        return false;
    }

    public boolean timeoutVerhaengen(String ip, long dauerMillis, String grund, String ausfuehrer) {
        return timeoutVerhaengen(ip, dauerMillis, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean timeoutVerhaengen(String ip, long dauerMillis, String grund, String ausfuehrer,
                                     ServerScope scope, String herkunft) {
        return timeoutVerhaengen(ip, dauerMillis, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean timeoutVerhaengen(String ip, long dauerMillis, String grund, String ausfuehrer,
                                     ServerScope scope, String herkunft, boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            LOG.warn("ipTimeout für '{}' abgewiesen: ungültige IP.", ip);
            return false;
        }
        if (istIpDurchOnlineSchutzBlockiert(kanonisch)) {
            LOG.warn("ipTimeout für {} abgewiesen: geschützt.", kanonisch);
            return false;
        }
        if (dauerMillis <= 0) {
            return false;
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.debug("IP-Timeout für {}: Dauer {} ms überläuft die Ablaufrechnung.", kanonisch, dauerMillis);
            return false;
        }
        IpTimeoutRecord record;
        try {
            record = new IpTimeoutRecord(kanonisch, grund, ausfuehrer, jetzt, ablaufAm, wirksam);
        } catch (Exception e) {
            LOG.warn("IP-Timeout für {} (Dauer {} ms) konnte nicht erzeugt werden.", kanonisch, dauerMillis, e);
            return false;
        }
        if (!repository.timeoutVerhaengenWennFrei(record)) {
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

        long ablaufFuerWirkung = ablaufAm;
        Runnable wirkung = () -> {
            kickHelper.onlineSpielerMitIpKickenMitScreen(kanonisch, grund, ausfuehrer,
                    dauerMillis, ablaufFuerWirkung, MessageKeys.SCREEN_IP_TIMEOUT,
                    ProtectionService.IgnoreTyp.TIMEOUT);
            try {
                historyService.hinzufuegen(HistoryType.IP_TIMEOUT, ausfuehrer, kanonisch, grund, ablaufFuerWirkung,
                        wirksam, herkunftNorm);
            } catch (Exception e) {
                LOG.warn("IP-Timeout-History für {} konnte nicht geschrieben werden.", kanonisch, e);
            }
            broadcastService.senden(MessageKeys.BROADCAST_IP_TIMEOUT, platzhalter, silent);
            logWriter.schreiben(HistoryType.IP_TIMEOUT, ausfuehrer, kanonisch,
                    "Grund: " + grund + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                            + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufFuerWirkung),
                    wirksam, herkunftNorm);
        };

        if (verzeichnis.darfDirektAusfuehren()) {
            wirkung.run();
            return true;
        }
        if (taskScheduler != null && !taskScheduler.istGeschlossen()) {
            try {
                taskScheduler.aufgabe(wirkung);
            } catch (Exception e) {
                LOG.warn("IP-Timeout-Wirkung für {} konnte nicht geplant werden (Timeout bleibt aktiv).",
                        kanonisch, e);
            }
            return true;
        }
        LOG.warn("timeoutVerhaengen (IP) für {} außerhalb des Primary-Threads ohne Scheduler – "
                + "Timeout gespeichert, Kick/Meldung entfallen.", kanonisch);
        return true;
    }

    public boolean timeoutAufheben(String ip, String ausfuehrer, ServerScope scope, String herkunft) {
        return timeoutAufheben(ip, ausfuehrer, scope, herkunft, false);
    }

    public boolean timeoutAufheben(String ip, String ausfuehrer, ServerScope scope, String herkunft,
                                   boolean silent) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            return false;
        }
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        Optional<IpTimeoutRecord> aktuell = repository.finden(kanonisch, scope);
        if (aktuell.isPresent() && aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
            repository.timeoutAufhebenWenn(kanonisch, scope, b -> b.equals(aktuell.get()));
            try {
                historyService.hinzufuegen(HistoryType.IP_UNTIMEOUT, "System", kanonisch,
                        "Abgelaufen (Grund war: " + aktuell.get().grund() + ")", null, scope, herkunftNorm);
            } catch (Exception e) {
                LOG.debug("Ablauf-History für IP-Timeout {} fehlgeschlagen.", kanonisch, e);
            }
            return false;
        }
        if (aktuell.isEmpty()) {
            return false;
        }
        Optional<IpTimeoutRecord> entfernt = repository.timeoutAufhebenWenn(kanonisch, scope,
                b -> b.equals(aktuell.get()));
        if (entfernt.isEmpty()) {
            return false;
        }
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("ip", kanonisch);
        platzhalter.put("spieler", kanonisch);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("scope", entfernt.get().scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);
        try {
            historyService.hinzufuegen(HistoryType.IP_UNTIMEOUT, ausfuehrer, kanonisch,
                    "IP-Timeout aufgehoben (Grund war: " + entfernt.get().grund() + ")", null,
                    entfernt.get().scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("IP-Untimeout-History für {} fehlgeschlagen.", kanonisch, e);
        }
        broadcastService.senden(MessageKeys.BROADCAST_IP_UNTIMEOUT, platzhalter, silent);
        logWriter.schreiben(HistoryType.IP_UNTIMEOUT, ausfuehrer, kanonisch,
                "Vorheriger Grund: " + entfernt.get().grund(), entfernt.get().scope(), herkunftNorm);
        return true;
    }

    public Optional<IpTimeoutRecord> bearbeiten(String ip, long dauerMillis, String grund, String ausfuehrer,
                                                ServerScope scopeSelektor, String herkunft) {
        return bearbeiten(ip, dauerMillis, grund, ausfuehrer, scopeSelektor, herkunft, false);
    }

    public Optional<IpTimeoutRecord> bearbeiten(String ip, long dauerMillis, String grund, String ausfuehrer,
                                                ServerScope scopeSelektor, String herkunft, boolean silent) {
        Objects.requireNonNull(scopeSelektor, "Der Scope-Selektor darf nicht null sein.");
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(ip);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (istIpDurchOnlineSchutzBlockiert(kanonisch)) {
            return Optional.empty();
        }
        if (dauerMillis <= 0) {
            return Optional.empty();
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
        Optional<IpTimeoutRecord> alt = repository.findeAbdeckend(kanonisch, scopeSelektor, jetzt);
        if (alt.isEmpty()) {
            return Optional.empty();
        }
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        IpTimeoutRecord neu = new IpTimeoutRecord(kanonisch,
                grund != null ? grund : alt.get().grund(),
                ausfuehrer, jetzt, ablaufAm, alt.get().scope());
        Optional<IpTimeoutRecord> vorher = repository.ersetzenWenn(
                kanonisch, alt.get().scope(), t -> t.equals(alt.get()), neu);
        if (vorher.isEmpty()) {
            return Optional.empty();
        }

        if (verzeichnis.darfDirektAusfuehren()) {
            kickHelper.onlineSpielerMitIpKickenMitScreen(kanonisch, neu.grund(), ausfuehrer,
                    dauerMillis, ablaufAm, MessageKeys.SCREEN_IP_TIMEOUT,
                    ProtectionService.IgnoreTyp.TIMEOUT);
        } else if (taskScheduler != null && !taskScheduler.istGeschlossen()) {
            try {
                taskScheduler.aufgabe(() -> kickHelper.onlineSpielerMitIpKickenMitScreen(
                        kanonisch, neu.grund(), ausfuehrer, dauerMillis, ablaufAm,
                        MessageKeys.SCREEN_IP_TIMEOUT, ProtectionService.IgnoreTyp.TIMEOUT));
            } catch (IllegalStateException e) {
                LOG.warn("IP-Timeout-Edit-Kick für {} konnte nach Shutdown nicht geplant werden.", kanonisch);
            }
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
        try {
            historyService.hinzufuegen(HistoryType.IP_TIMEOUT_EDIT, ausfuehrer, kanonisch,
                    neu.grund(), ablaufAm, neu.scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("IP-Timeout-Edit-History für {} fehlgeschlagen.", kanonisch, e);
        }
        broadcastService.senden(MessageKeys.BROADCAST_IP_TIMEOUT_EDIT, platzhalter, silent);
        logWriter.schreiben(HistoryType.IP_TIMEOUT_EDIT, ausfuehrer, kanonisch,
                "Grund: " + neu.grund() + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm)
                        + ", Vorheriger Grund: " + vorher.get().grund(),
                neu.scope(), herkunftNorm);
        return Optional.of(neu);
    }

    public boolean istAbgedeckt(String ip, ServerScope ziel) {
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return repository.istAbgedeckt(ip, ziel, System.currentTimeMillis());
    }

    public Optional<IpTimeoutRecord> finden(String ip, ServerScope scope) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return repository.finden(ip, scope);
    }

    public Optional<IpTimeoutRecord> findeAbdeckend(String ip, ServerScope ziel) {
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return repository.findeAbdeckend(ip, ziel, System.currentTimeMillis());
    }

    public Optional<IpTimeoutRecord> findeWirksamOhneCleanup(String ip, String aktuellerServer, long jetztMillis) {
        IpTimeoutRecord exakt = null;
        IpTimeoutRecord abdeckend = null;
        for (IpTimeoutRecord kandidat : repository.alleFuer(ip)) {
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

    public List<IpTimeoutRecord> findeAktiveTimeoutsFuerTab() {
        long jetzt = System.currentTimeMillis();
        return repository.alle().stream()
                .filter(t -> t != null && t.istAktiv(jetzt))
                .toList();
    }

    public int bereinigeAbgelaufeneFuerIp(String ip, String ausfuehrer) {
        int anzahl = 0;
        for (IpTimeoutRecord kandidat : List.copyOf(repository.alleFuer(ip))) {
            if (kandidat == null || !kandidat.istAbgelaufen(System.currentTimeMillis())) {
                continue;
            }
            Optional<IpTimeoutRecord> entfernt = repository.timeoutAufheben(ip, kandidat.scope());
            if (entfernt.isEmpty()) {
                continue;
            }
            IpTimeoutRecord rec = entfernt.get();
            try {
                historyService.hinzufuegen(HistoryType.IP_UNTIMEOUT, ausfuehrer, rec.ip(),
                        "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                        rec.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Ablauf-History für IP-Timeout {} fehlgeschlagen.", rec.ip(), e);
            }
            try {
                logWriter.schreiben(HistoryType.IP_UNTIMEOUT, ausfuehrer, rec.ip(),
                        "Abgelaufen. Vorheriger Grund: " + rec.grund(),
                        rec.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Ablauf-Log für IP-Timeout {} fehlgeschlagen.", rec.ip(), e);
            }
            anzahl++;
        }
        return anzahl;
    }

    public int bereinigeAbgelaufeneMitHistory(String ausfuehrer) {
        long jetzt = System.currentTimeMillis();
        List<IpTimeoutRecord> kandidaten;
        try {
            kandidaten = repository.alle().stream()
                    .filter(t -> t != null && t.istAbgelaufen(jetzt))
                    .toList();
        } catch (Exception e) {
            LOG.warn("Ablauf-Scan für IP-Timeout-Cleanup fehlgeschlagen.", e);
            return 0;
        }
        int anzahl = 0;
        for (IpTimeoutRecord kandidat : kandidaten) {
            try {
                Optional<IpTimeoutRecord> aktuell = repository.finden(kandidat.ip(), kandidat.scope());
                if (aktuell.isEmpty() || !aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
                    continue;
                }
                Optional<IpTimeoutRecord> entfernt = repository.timeoutAufheben(kandidat.ip(), kandidat.scope());
                if (entfernt.isEmpty()) {
                    continue;
                }
                IpTimeoutRecord rec = entfernt.get();
                try {
                    historyService.hinzufuegen(HistoryType.IP_UNTIMEOUT, ausfuehrer, rec.ip(),
                            "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                            rec.scope(), ServerScope.UNBEKANNT_WERT);
                } catch (Exception e) {
                    LOG.warn("Ablauf-History für IP-Timeout {} fehlgeschlagen.", rec.ip(), e);
                }
                logWriter.schreiben(HistoryType.IP_UNTIMEOUT, ausfuehrer, rec.ip(),
                        "Abgelaufen. Vorheriger Grund: " + rec.grund(), rec.scope(), ServerScope.UNBEKANNT_WERT);
                anzahl++;
            } catch (Exception e) {
                LOG.warn("IP-Timeout-Ablauf-Cleanup fehlgeschlagen.", e);
            }
        }
        return anzahl;
    }

    public Component baueIpTimeoutScreen(IpTimeoutRecord timeout, long jetztMillis) {
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("ip", timeout.ip());
        platzhalter.put("spieler", timeout.ip());
        platzhalter.put("ausfuehrer", timeout.ausfuehrer());
        platzhalter.put("grund", de.lightrixx.punish.command.ScopeParser.saeubereGrund(timeout.grund(), sichereBekannteServer()));
        platzhalter.put("dauer", ZeitFormat.formatRestzeit(timeout.verbleibendeMillis(jetztMillis)));
        platzhalter.put("verbleibend", ZeitFormat.formatRestzeit(timeout.verbleibendeMillis(jetztMillis)));
        platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(timeout.ablaufAm()));
        platzhalter.put("scope", timeout.scope().anzeige());

        String vorlage;
        try {
            vorlage = configManager.screen(MessageKeys.SCREEN_IP_TIMEOUT);
        } catch (Exception e) {
            vorlage = null;
        }
        if (vorlage == null || vorlage.isBlank() || vorlage.equals("<red>Zugriff verweigert.")) {
            try {
                vorlage = configManager.screen(MessageKeys.SCREEN_TIMEOUT);
            } catch (Exception e) {
                vorlage = "<yellow>Du befindest dich im Timeout.";
            }
        }
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            return miniMessage.deserialize(ersetzt);
        } catch (Exception e) {
            LOG.warn("Ungültiges MiniMessage-Format im IP-Timeout-Screen, nutze Plain-Text-Fallback.", e);
            return Component.text(ersetzt);
        }
    }

    private java.util.List<String> sichereBekannteServer() {
        try {
            java.util.List<String> server = configManager.bekannteServer();
            return server == null ? java.util.List.of() : server;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
