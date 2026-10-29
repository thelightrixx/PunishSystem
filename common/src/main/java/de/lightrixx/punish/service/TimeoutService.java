package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.TimeoutRecord;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.ZeitFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TimeoutService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TimeoutService.class);

    private final TimeoutRepository timeoutRepository;
    private final Konfiguration configManager;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final AuftragsPlaner taskScheduler;

    private final ProtectionService protectionService;
    private final SpielerVerzeichnis verzeichnis;
    private final MiniMessage miniMessage = MiniMessageUtil.miniMessage();

    public interface TimeoutBeobachter {
        void timeoutGeaendert();
    }

    private final java.util.Set<TimeoutBeobachter> beobachter =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void beobachterHinzufuegen(TimeoutBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.add(beobachter);
        }
    }

    public void beobachterEntfernen(TimeoutBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.remove(beobachter);
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

    private void benachrichtige() {
        if (beobachter.isEmpty()) {
            return;
        }
        for (TimeoutBeobachter b : beobachter) {
            try {
                b.timeoutGeaendert();
            } catch (Exception e) {
                LOG.debug("Timeout-Beobachter fehlgeschlagen.", e);
            }
        }
    }

    public TimeoutService(TimeoutRepository timeoutRepository,
                          Konfiguration configManager,
                          HistoryService historyService,
                          BroadcastService broadcastService,
                          LogWriter logWriter,
                          AuftragsPlaner taskScheduler,
                          ProtectionService protectionService,
                          SpielerVerzeichnis verzeichnis) {
        this.timeoutRepository = Objects.requireNonNull(timeoutRepository, "Das TimeoutRepository darf nicht null sein.");
        this.configManager = Objects.requireNonNull(configManager, "Die Konfiguration darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.taskScheduler = taskScheduler;
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    public boolean timeoutVerhaengen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer) {
        return timeoutVerhaengen(zielUuid, zielName, dauerMillis, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean timeoutVerhaengen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer,
                                     ServerScope scope, String herkunft) {
        return timeoutVerhaengen(zielUuid, zielName, dauerMillis, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean timeoutVerhaengen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer,
                                     ServerScope scope, String herkunft, boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.TIMEOUT)) {
                LOG.warn("timeout für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return false;
        }
        if (dauerMillis <= 0) {
            LOG.warn("timeout: ungültige Dauer {} für {} – abgelehnt.", dauerMillis, zielUuid);
            return false;
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn(
                    "timeout: Ablauf-Überlauf (jetzt={}, dauer={}) für {} – als DAUER_UNGUELTIG werten",
                    jetzt, dauerMillis, zielUuid, e);
            return false;
        }

        TimeoutRecord record;
        try {
            record = new TimeoutRecord(zielUuid, zielName, grund, ausfuehrer, jetzt, ablaufAm, wirksam);
        } catch (Exception e) {
            LOG.warn("timeout für {} abgewiesen: ungültige Eingabedaten (Name/Grund).", zielUuid, e);
            return false;
        }
        if (!timeoutRepository.timeoutVerhaengenWennFrei(record)) {
            return false;
        }

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        platzhalter.put("dauer", ZeitFormat.formatRestzeit(dauerMillis));
        platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(ablaufAm));
        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        Runnable wirkung = () -> {
            if (verzeichnis.onlineSpieler(zielUuid).isPresent()) {
                try {
                    verzeichnis.trennen(zielUuid, baueTimeoutScreen(record, jetzt));
                } catch (Exception e) {
                    LOG.warn("Kick für {} fehlgeschlagen (Timeout bleibt aktiv).", zielUuid, e);
                }
            }
            try {
                historyService.hinzufuegen(HistoryType.TIMEOUT, ausfuehrer, zielUuid, zielName, grund, ablaufAm,
                        wirksam, herkunftNorm);
            } catch (Exception e) {
                LOG.warn(
                        "Timeout-History für {} konnte nicht geschrieben werden (Timeout bleibt aktiv).", zielUuid, e);
            }
            broadcastService.senden(MessageKeys.BROADCAST_TIMEOUT, platzhalter, silent);
            logWriter.schreiben(HistoryType.TIMEOUT, ausfuehrer, zielName,
                    "Grund: " + grund + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                            + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm),
                    wirksam, herkunftNorm);
            benachrichtige();
        };

        if (verzeichnis.darfDirektAusfuehren()) {
            wirkung.run();
            return true;
        }
        if (taskScheduler != null && !taskScheduler.istGeschlossen()) {
            try {
                taskScheduler.aufgabe(wirkung);
            } catch (Exception e) {
                LOG.warn("Timeout-Wirkung für {} konnte nicht geplant werden (Timeout bleibt aktiv).",
                        zielUuid, e);
            }
            return true;
        }
        LOG.warn("timeoutVerhaengen für {} außerhalb des Primary-Threads ohne Scheduler – "
                + "Timeout gespeichert, Kick/Meldung entfallen.", zielUuid);
        return true;
    }

    public Optional<TimeoutRecord> findeWirksamOhneCleanup(UUID spielerUuid, String aktuellerServer,
                                                           long jetztMillis) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        TimeoutRecord exakt = null;
        TimeoutRecord abdeckend = null;
        for (TimeoutRecord kandidat : timeoutRepository.alleFuer(spielerUuid)) {
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

    public boolean istAbgedeckt(UUID spielerUuid, ServerScope ziel) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return timeoutRepository.istAbgedeckt(spielerUuid, ziel, System.currentTimeMillis());
    }

    public Optional<TimeoutRecord> finden(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return timeoutRepository.finden(spielerUuid, scope);
    }

    public Optional<TimeoutRecord> findeAbdeckend(UUID spielerUuid, ServerScope ziel) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return timeoutRepository.findeAbdeckend(spielerUuid, ziel, System.currentTimeMillis());
    }

    public boolean hatEintrag(UUID spielerUuid) {
        try {
            return !timeoutRepository.alleFuer(spielerUuid).isEmpty();
        } catch (Exception e) {
            LOG.warn("hatEintrag für {} fehlgeschlagen, werte als BELEGT (fail-closed).", spielerUuid, e);
            return true;
        }
    }

    public Optional<TimeoutRecord> findeVorabTimeoutNachName(String name, UUID echteUuid) {
        if (name == null || name.isBlank() || echteUuid == null) {
            return Optional.empty();
        }
        long jetzt = System.currentTimeMillis();
        try {
            for (TimeoutRecord kandidat : timeoutRepository.alle()) {
                if (kandidat == null || !kandidat.istAktiv(jetzt) || kandidat.spielerUuid().equals(echteUuid)) {
                    continue;
                }
                if (kandidat.spielerName() != null && kandidat.spielerName().equalsIgnoreCase(name.trim())) {
                    return Optional.of(kandidat);
                }
            }
        } catch (Exception e) {
            LOG.debug("Vorab-Timeout-Suche für '{}' fehlgeschlagen.", name, e);
        }
        return Optional.empty();
    }

    public Optional<TimeoutRecord> migriereVorabTimeout(UUID vorabUuid, UUID echteUuid, String echterName) {
        Objects.requireNonNull(vorabUuid, "Die Vorab-UUID darf nicht null sein.");
        Objects.requireNonNull(echteUuid, "Die echte UUID darf nicht null sein.");
        if (vorabUuid.equals(echteUuid) || echterName == null || echterName.isBlank()) {
            return Optional.empty();
        }
        java.util.List<TimeoutRecord> kandidaten = new java.util.ArrayList<>();
        for (TimeoutRecord kandidat : timeoutRepository.alleFuer(vorabUuid)) {
            if (kandidat != null && kandidat.istAktiv(System.currentTimeMillis())
                    && kandidat.spielerName() != null
                    && kandidat.spielerName().equalsIgnoreCase(echterName)) {
                kandidaten.add(kandidat);
            }
        }
        if (kandidaten.isEmpty()) {
            return Optional.empty();
        }
        Optional<TimeoutRecord> ersterMigriert = Optional.empty();
        for (TimeoutRecord vorab : kandidaten) {
            if (!de.lightrixx.punish.storage.PlayerCache.istVorabPlatzhalter(
                    vorabUuid, vorab.spielerName())) {
                LOG.warn("Vorab-Timeout für '{}' wird NICHT migriert: {} ist kein nie-gesehener Platzhalter "
                        + "(Namenswiederverwendung, A-002).", echterName, vorabUuid);
                continue;
            }
            if (timeoutRepository.findeAbdeckend(echteUuid, vorab.scope(),
                    System.currentTimeMillis()).isPresent()) {
                try {
                    timeoutRepository.timeoutAufheben(vorabUuid, vorab.scope());
                } catch (Exception e) {
                    LOG.debug("Verwaister Vorab-Timeout für {} konnte nicht aufgeräumt werden.", vorabUuid, e);
                }
                continue;
            }
            long jetzt = System.currentTimeMillis();
            if (vorab.ablaufAm() <= jetzt) {
                continue;
            }
            TimeoutRecord neu;
            try {
                neu = new TimeoutRecord(echteUuid, echterName, vorab.grund(),
                        vorab.ausfuehrer(), vorab.erstelltAm(), vorab.ablaufAm(), vorab.scope());
            } catch (Exception e) {
                LOG.debug("Vorab-Timeout für {} konnte nicht migriert werden (abgelaufen/ungültig).", echterName, e);
                continue;
            }
            if (!timeoutRepository.timeoutVerhaengenWennFrei(neu)) {
                continue;
            }
            try {
                timeoutRepository.timeoutAufheben(vorabUuid, vorab.scope());
            } catch (Exception e) {
                LOG.warn("Vorab-Timeout-Key {} konnte nach Migration nicht entfernt werden.", vorabUuid, e);
            }
            try {
                historyService.hinzufuegen(HistoryType.UNTIMEOUT, "System", vorabUuid, vorab.spielerName(),
                        "Vorab-Timeout auf " + echterName + " (" + echteUuid + ") migriert", null,
                        vorab.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Migrations-History (alt) fehlgeschlagen.", e);
            }
            try {
                historyService.hinzufuegen(HistoryType.TIMEOUT, vorab.ausfuehrer(), echteUuid, echterName,
                        vorab.grund() + " (Vorab-Timeout migriert)", neu.ablaufAm(),
                        neu.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Migrations-History (neu) fehlgeschlagen.", e);
            }
            LOG.info("Vorab-Timeout für '{}' von {} auf {} migriert (Scope {}).", echterName, vorabUuid,
                    echteUuid, vorab.scope().anzeige());
            if (ersterMigriert.isEmpty()) {
                ersterMigriert = Optional.of(neu);
            }
        }
        if (ersterMigriert.isPresent()) {
            benachrichtige();
        }
        return ersterMigriert;
    }

    public int bereinigeAbgelaufeneFuerUuid(UUID spielerUuid, String ausfuehrer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        int anzahl = 0;
        for (TimeoutRecord kandidat : List.copyOf(timeoutRepository.alleFuer(spielerUuid))) {
            if (kandidat == null || !kandidat.istAbgelaufen(System.currentTimeMillis())) {
                continue;
            }
            Optional<TimeoutRecord> entfernt = timeoutRepository.timeoutAufheben(
                    spielerUuid, kandidat.scope());
            if (entfernt.isEmpty()) {
                continue;
            }
            TimeoutRecord rec = entfernt.get();
            try {
                historyService.hinzufuegen(HistoryType.UNTIMEOUT, ausfuehrer, rec.spielerUuid(),
                        rec.spielerName(), "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                        rec.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Ablauf-History für {} konnte nicht geschrieben werden.", spielerUuid, e);
            }
            try {
                logWriter.schreiben(HistoryType.UNTIMEOUT, ausfuehrer, rec.spielerName(),
                        "Abgelaufen. Vorheriger Grund: " + rec.grund(),
                        rec.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Ablauf-Log für {} fehlgeschlagen.", spielerUuid, e);
            }
            anzahl++;
        }
        if (anzahl > 0) {
            benachrichtige();
        }
        return anzahl;
    }

    public java.util.List<TimeoutRecord> findeAktiveTimeoutsFuerTab() {
        long jetzt = System.currentTimeMillis();
        return timeoutRepository.alle().stream()
                .filter(t -> t != null && t.istAktiv(jetzt))
                .toList();
    }

    public boolean timeoutAufheben(UUID zielUuid, String zielName, String ausfuehrer,
                                   ServerScope scope, String herkunft) {
        return timeoutAufheben(zielUuid, zielName, ausfuehrer, scope, herkunft, false);
    }

    public boolean timeoutAufheben(UUID zielUuid, String zielName, String ausfuehrer,
                                   ServerScope scope, String herkunft, boolean silent) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        Optional<TimeoutRecord> aktuell = timeoutRepository.finden(zielUuid, scope);
        if (aktuell.isPresent() && aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
            timeoutRepository.timeoutAufhebenWenn(zielUuid, scope,
                    b -> b.equals(aktuell.get()));
            try {
                historyService.hinzufuegen(HistoryType.UNTIMEOUT, "System", zielUuid, zielName,
                        "Abgelaufen (Grund war: " + aktuell.get().grund() + ")", null,
                        scope, herkunftNorm);
            } catch (Exception e) {
                LOG.debug("Ablauf-History für {} konnte nicht geschrieben werden.", zielUuid, e);
            }
            benachrichtige();
            return false;
        }
        if (aktuell.isEmpty()) {
            return false;
        }
        Optional<TimeoutRecord> entfernt = timeoutRepository.timeoutAufhebenWenn(zielUuid, scope,
                b -> b.equals(aktuell.get()));
        if (entfernt.isEmpty()) {
            return false;
        }
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("scope", entfernt.get().scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);
        try {
            historyService.hinzufuegen(HistoryType.UNTIMEOUT, ausfuehrer, zielUuid, zielName,
                    "Timeout aufgehoben (Grund war: " + entfernt.get().grund() + ")", null,
                    entfernt.get().scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Untimeout-History für {} konnte nicht geschrieben werden (Aufhebung bleibt wirksam).", zielUuid, e);
        }
        broadcastService.senden(MessageKeys.BROADCAST_UNTIMEOUT, platzhalter, silent);
        logWriter.schreiben(HistoryType.UNTIMEOUT, ausfuehrer, zielName,
                "Vorheriger Grund: " + entfernt.get().grund(), entfernt.get().scope(), herkunftNorm);
        benachrichtige();
        return true;
    }

    public MassenErgebnis timeoutAlle(long dauerMillis, String grund, String ausfuehrer, UUID ausfuehrerUuid,
                           ServerScope scope, String herkunft) {
        return timeoutAlle(dauerMillis, grund, ausfuehrer, ausfuehrerUuid, scope, herkunft, false);
    }

    public MassenErgebnis timeoutAlle(long dauerMillis, String grund, String ausfuehrer, UUID ausfuehrerUuid,
                           ServerScope scope, String herkunft, boolean silent) {
        if (dauerMillis <= 0) {
            LOG.warn("timeoutAlle: ungültige Dauer {} – Abbruch, kein Spieler im Timeout", dauerMillis);
            return MassenErgebnis.leer();
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn("timeoutAlle: Ablauf-Überlauf (jetzt={}, dauer={}) - Abbruch, kein Spieler im Timeout", jetzt, dauerMillis, e);
            return MassenErgebnis.leer();
        }
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        List<TimeoutRecord> kandidaten = new ArrayList<>();
        for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
            if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                continue;
            }
            if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.TIMEOUT)) {
                continue;
            }
            try {
                kandidaten.add(new TimeoutRecord(
                        online.uuid(), online.name(), grund, ausfuehrer, jetzt, ablaufAm, wirksam));
            } catch (Exception e) {
                LOG.warn("timeoutAlle: ungültige Eingabedaten für {} – übersprungen.", online.uuid(), e);
            }
        }
        int gekuerzt = 0;
        if (kandidaten.size() > Limits.TIMEOUT_ALL_MAX) {
            gekuerzt = kandidaten.size() - Limits.TIMEOUT_ALL_MAX;
            LOG.warn("timeoutAlle: {} Kandidaten überschreiten das Limit {} – nur die ersten {} erhalten Timeout.",
                    kandidaten.size(), Limits.TIMEOUT_ALL_MAX, Limits.TIMEOUT_ALL_MAX);
            kandidaten = new ArrayList<>(kandidaten.subList(0, Limits.TIMEOUT_ALL_MAX));
        }
        List<TimeoutRecord> gesetzt;
        try {
            gesetzt = new ArrayList<>(timeoutRepository.timeoutAlleWennFrei(kandidaten));
        } catch (Exception e) {
            LOG.warn("timeoutAlle-Batch fehlgeschlagen (Teile ggf. gesetzt).", e);
            return MassenErgebnis.leer();
        }
        for (TimeoutRecord kandidat : kandidaten) {
            if (!gesetzt.contains(kandidat)) {
                LOG.debug("timeoutAlle für {} übersprungen (bereits im Timeout).", kandidat.spielerUuid());
            }
        }
        if (gesetzt.isEmpty()) {
            return new MassenErgebnis(0, gekuerzt);
        }
        for (TimeoutRecord record : gesetzt) {
            try {
                historyService.hinzufuegen(HistoryType.TIMEOUT_ALL, ausfuehrer,
                        record.spielerUuid(), record.spielerName(), grund, ablaufAm,
                        record.scope(), herkunftNorm);
            } catch (Exception e) {
                LOG.warn("TIMEOUT_ALL-History für {} fehlgeschlagen (Timeout bleibt aktiv).", record.spielerUuid(), e);
            }
        }
        String dauerText = ZeitFormat.formatRestzeit(dauerMillis);
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        platzhalter.put("dauer", dauerText);
        platzhalter.put("verbleibend", dauerText);
        platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(ablaufAm));
        platzhalter.put("anzahl", String.valueOf(gesetzt.size()));
        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);
        broadcastService.senden(MessageKeys.BROADCAST_TIMEOUT_ALL, platzhalter, silent);
        logWriter.schreiben(HistoryType.TIMEOUT_ALL, ausfuehrer, "alle",
                "Anzahl: " + gesetzt.size() + ", Grund: " + grund, wirksam, herkunftNorm);
        for (TimeoutRecord record : gesetzt) {
            try {
                verzeichnis.trennen(record.spielerUuid(), baueTimeoutScreen(record, jetzt));
            } catch (Exception e) {
                LOG.warn("Kick für {} fehlgeschlagen (Timeout bleibt aktiv).", record.spielerName(), e);
            }
            benachrichtige();
        }
        return new MassenErgebnis(gesetzt.size(), gekuerzt);
    }

    public int zaehleTimeoutAlleKandidaten(UUID ausfuehrerUuid) {
        int anzahl = 0;
        for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
            if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                continue;
            }
            if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.TIMEOUT)) {
                continue;
            }
            anzahl++;
        }
        return anzahl;
    }

    public int timeoutAufhebenAlle(String grund, String ausfuehrer, String herkunft) {
        return timeoutAufhebenAlle(grund, ausfuehrer, herkunft, null, false);
    }

    public int timeoutAufhebenAlle(String grund, String ausfuehrer, String herkunft, ServerScope scopeFilter,
                                   boolean silent) {
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        Collection<TimeoutRecord> alle;
        try {
            alle = List.copyOf(timeoutRepository.alle());
        } catch (Exception e) {
            LOG.warn("untimeout-all: Timeout-Scan fehlgeschlagen.", e);
            return 0;
        }
        List<TimeoutRecord> entfernt = new ArrayList<>();
        for (TimeoutRecord kandidat : alle) {
            if (kandidat == null) {
                continue;
            }
            if (scopeFilter != null && !kandidat.scope().equals(scopeFilter)) {
                continue;
            }
            try {
                Optional<TimeoutRecord> weg = timeoutRepository.timeoutAufheben(
                        kandidat.spielerUuid(), kandidat.scope());
                weg.ifPresent(entfernt::add);
            } catch (Exception e) {
                LOG.warn("untimeout-all für {} fehlgeschlagen, fahre mit Rest fort.", kandidat.spielerUuid(), e);
            }
        }
        for (TimeoutRecord record : entfernt) {
            try {
                historyService.hinzufuegen(HistoryType.UNTIMEOUT_ALL, ausfuehrer,
                        record.spielerUuid(), record.spielerName(), grund, null,
                        record.scope(), herkunftNorm);
            } catch (Exception e) {
                LOG.warn("UNTIMEOUT_ALL-History für {} konnte nicht geschrieben werden.", record.spielerUuid(), e);
            }
        }
        if (entfernt.isEmpty()) {
            LOG.info("untimeout-all von '{}' ohne Treffer (Scope: {}).", ausfuehrer,
                    scopeFilter == null ? "alle" : scopeFilter.anzeige());
            return 0;
        }
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        platzhalter.put("anzahl", String.valueOf(entfernt.size()));
        if (scopeFilter != null) {
            platzhalter.put("scope", scopeFilter.anzeige());
        }
        broadcastService.senden(MessageKeys.BROADCAST_UNTIMEOUT_ALL, platzhalter, silent);
        logWriter.schreiben(HistoryType.UNTIMEOUT_ALL, ausfuehrer, "alle",
                "Anzahl: " + entfernt.size() + ", Grund: " + grund
                        + (scopeFilter == null ? "" : ", Scope: " + scopeFilter.anzeige()),
                scopeFilter == null ? ServerScope.unbekannt() : scopeFilter, herkunftNorm);
        benachrichtige();
        return entfernt.size();
    }

    public Optional<TimeoutRecord> bearbeiten(UUID zielUuid, String zielName, long dauerMillis,
                                              String grund, String ausfuehrer,
                                              ServerScope scopeSelektor, String herkunft) {
        return bearbeiten(zielUuid, zielName, dauerMillis, grund, ausfuehrer, scopeSelektor, herkunft, false);
    }

    public Optional<TimeoutRecord> bearbeiten(UUID zielUuid, String zielName, long dauerMillis,
                                              String grund, String ausfuehrer,
                                              ServerScope scopeSelektor, String herkunft, boolean silent) {
        Objects.requireNonNull(scopeSelektor, "Der Scope-Selektor darf nicht null sein.");
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.TIMEOUT)) {
                LOG.warn("timeout-bearbeiten für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für Timeout-Edit {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return Optional.empty();
        }
        if (dauerMillis <= 0) {
            LOG.warn("bearbeiten: ungültige Dauer {} für {} – abgelehnt.", dauerMillis, zielUuid);
            return Optional.empty();
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn("bearbeiten: Ablauf-Überlauf (jetzt={}, dauer={}) für {} – als DAUER_UNGUELTIG werten",
                    jetzt, dauerMillis, zielUuid, e);
            return Optional.empty();
        }

        Optional<TimeoutRecord> alt = timeoutRepository.findeAbdeckend(zielUuid, scopeSelektor, jetzt);
        if (alt.isEmpty()) {
            return Optional.empty();
        }
        TimeoutRecord neu = new TimeoutRecord(zielUuid, zielName,
                grund != null ? grund : alt.get().grund(),
                ausfuehrer, jetzt, ablaufAm, alt.get().scope());

        Optional<TimeoutRecord> vorher = timeoutRepository.ersetzenWenn(
                zielUuid, alt.get().scope(), timeout -> timeout.equals(alt.get()), neu);
        if (vorher.isEmpty()) {
            return Optional.empty();
        }

        if (verzeichnis.darfDirektAusfuehren()) {
            if (verzeichnis.onlineSpieler(zielUuid).isPresent()) {
                try {
                    verzeichnis.trennen(zielUuid, baueTimeoutScreen(neu, jetzt));
                } catch (Exception e) {
                    LOG.warn("Kick für {} fehlgeschlagen (Timeout bleibt aktiv).", zielUuid, e);
                }
            }
        } else if (taskScheduler != null && !taskScheduler.istGeschlossen()) {
            final TimeoutRecord kickKopie = neu;
            final long kickJetzt = jetzt;
            try {
                taskScheduler.aufgabe(() -> {
                    if (verzeichnis.onlineSpieler(zielUuid).isPresent()) {
                        try {
                            verzeichnis.trennen(zielUuid, baueTimeoutScreen(kickKopie, kickJetzt));
                        } catch (Exception e) {
                            LOG.warn("Kick für {} fehlgeschlagen (Timeout bleibt aktiv).", zielUuid, e);
                        }
                    }
                });
            } catch (IllegalStateException e) {
                LOG.warn("Timeout-Edit-Kick für {} konnte nach Shutdown nicht geplant werden, "
                        + "wird beim nächsten Join wirksam.", zielUuid);
            }
        } else {
            LOG.warn("Timeout-Edit für {} außerhalb des Primary-Threads ohne Scheduler: "
                    + "Kick wird erst beim nächsten Join wirksam.", zielUuid);
        }

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", neu.grund());
        platzhalter.put("dauer", ZeitFormat.formatRestzeit(dauerMillis));
        platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(ablaufAm));
        platzhalter.put("scope", neu.scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        try {
            historyService.hinzufuegen(HistoryType.TIMEOUT_EDIT, ausfuehrer, zielUuid, zielName,
                    neu.grund(), ablaufAm, neu.scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Timeout-Edit-History für {} konnte nicht geschrieben werden (Timeout bleibt aktiv).",
                    zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_TIMEOUT_EDIT, platzhalter, silent);
        logWriter.schreiben(HistoryType.TIMEOUT_EDIT, ausfuehrer, zielName,
                "Grund: " + neu.grund() + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm)
                        + ", Vorheriger Grund: " + vorher.get().grund(),
                neu.scope(), herkunftNorm);
        benachrichtige();
        return Optional.of(neu);
    }

    public int bereinigeAbgelaufeneMitHistory(String ausfuehrer) {
        long jetzt = System.currentTimeMillis();
        java.util.List<TimeoutRecord> kandidaten;
        try {
            kandidaten = timeoutRepository.alle().stream()
                    .filter(t -> t != null && t.istAbgelaufen(jetzt))
                    .toList();
        } catch (Exception e) {
            LOG.warn("Ablauf-Scan für Timeout-Cleanup fehlgeschlagen.", e);
            return 0;
        }
        int anzahl = 0;
        for (TimeoutRecord kandidat : kandidaten) {
            try {
                Optional<TimeoutRecord> aktuell = timeoutRepository.finden(
                        kandidat.spielerUuid(), kandidat.scope());
                if (aktuell.isEmpty() || !aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
                    continue;
                }
                Optional<TimeoutRecord> entfernt = timeoutRepository.timeoutAufheben(
                        kandidat.spielerUuid(), kandidat.scope());
                if (entfernt.isEmpty()) {
                    continue;
                }
                TimeoutRecord rec = entfernt.get();
                try {
                    historyService.hinzufuegen(HistoryType.UNTIMEOUT, ausfuehrer, rec.spielerUuid(),
                            rec.spielerName(), "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                            rec.scope(), ServerScope.UNBEKANNT_WERT);
                } catch (Exception e) {
                    LOG.warn("Ablauf-History für {} konnte nicht geschrieben werden.", rec.spielerUuid(), e);
                }
                logWriter.schreiben(HistoryType.UNTIMEOUT, ausfuehrer, rec.spielerName(),
                        "Abgelaufen. Vorheriger Grund: " + rec.grund(), rec.scope(), ServerScope.UNBEKANNT_WERT);
                anzahl++;
            } catch (Exception e) {
                LOG.warn("Timeout-Ablauf-Cleanup fehlgeschlagen.", e);
            }
        }
        if (anzahl > 0) {
            benachrichtige();
        }
        return anzahl;
    }

    public Component baueTimeoutScreen(TimeoutRecord timeout, long jetztMillis) {
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", timeout.spielerName());
        platzhalter.put("ausfuehrer", timeout.ausfuehrer());
        platzhalter.put("grund", de.lightrixx.punish.command.ScopeParser.saeubereGrund(timeout.grund(), sichereBekannteServer()));
        platzhalter.put("dauer", ZeitFormat.formatRestzeit(timeout.verbleibendeMillis(jetztMillis)));
        platzhalter.put("verbleibend", ZeitFormat.formatRestzeit(timeout.verbleibendeMillis(jetztMillis)));
        platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(timeout.ablaufAm()));
        platzhalter.put("scope", timeout.scope().anzeige());

        String vorlage = configManager.screen(MessageKeys.SCREEN_TIMEOUT);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            return miniMessage.deserialize(ersetzt);
        } catch (Exception e) {
            LOG.warn("Ungültiges MiniMessage-Format im Timeout-Screen, nutze Plain-Text-Fallback.", e);
            return Component.text(ersetzt);
        }
    }
}
