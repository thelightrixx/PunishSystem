package de.lightrixx.punish.service;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MuteService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MuteService.class);

    private final MuteRepository muteRepository;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final MessageService messageService;
    private final AuftragsPlaner taskScheduler;

    private final ProtectionService protectionService;

    public interface MuteBeobachter {
        void muteGeaendert(UUID spielerUuid);
    }

    private final java.util.Set<MuteBeobachter> beobachter =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void beobachterHinzufuegen(MuteBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.add(beobachter);
        }
    }

    public void beobachterEntfernen(MuteBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.remove(beobachter);
        }
    }

    private void benachrichtige(UUID spielerUuid) {
        if (spielerUuid == null || beobachter.isEmpty()) {
            return;
        }
        for (MuteBeobachter b : beobachter) {
            try {
                b.muteGeaendert(spielerUuid);
            } catch (Exception e) {
                LOG.debug("Mute-Beobachter fehlgeschlagen für {}.", spielerUuid, e);
            }
        }
    }
    private final SpielerVerzeichnis verzeichnis;

    public MuteService(MuteRepository muteRepository,
                       HistoryService historyService,
                       BroadcastService broadcastService,
                       LogWriter logWriter,
                       MessageService messageService,
                       AuftragsPlaner taskScheduler,
                       ProtectionService protectionService,
                       SpielerVerzeichnis verzeichnis) {
        this.muteRepository = Objects.requireNonNull(muteRepository, "Das MuteRepository darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.messageService = Objects.requireNonNull(messageService, "Der MessageService darf nicht null sein.");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "Der AuftragsPlaner darf nicht null sein.");
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    public boolean muten(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer) {
        return muten(zielUuid, zielName, dauerMillis, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean muten(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer,
                         ServerScope scope, String herkunft) {
        return muten(zielUuid, zielName, dauerMillis, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean muten(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer,
                         ServerScope scope, String herkunft, boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.MUTE)) {
                LOG.warn("muten für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return false;
        }
        if (dauerMillis <= 0) {
            LOG.warn("muten: ungültige Dauer {} für {} – abgelehnt.", dauerMillis, zielUuid);
            return false;
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn(
                    "muten: Ablauf-Überlauf (jetzt={}, dauer={}) für {} – als DAUER_UNGUELTIG werten",
                    jetzt, dauerMillis, zielUuid, e);
            return false;
        }

        MuteRecord record;
        try {
            record = new MuteRecord(zielUuid, zielName, grund, ausfuehrer, jetzt, ablaufAm, wirksam);
        } catch (Exception e) {
            LOG.warn("muten für {} abgewiesen: ungültige Eingabedaten (Name/Grund).", zielUuid, e);
            return false;
        }
        if (!muteRepository.mutenWennFrei(record)) {
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

        zielInformieren(zielUuid, MessageKeys.ZIEL_GEMUTET, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.MUTE, ausfuehrer, zielUuid, zielName, grund, ablaufAm,
                    wirksam, herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Mute-History für {} konnte nicht geschrieben werden (Mute bleibt aktiv).", zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_MUTE, platzhalter, silent);
        logWriter.schreiben(HistoryType.MUTE, ausfuehrer, zielName,
                "Grund: " + grund + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm),
                wirksam, herkunftNorm);
        benachrichtige(zielUuid);
        return true;
    }

    public boolean entmuten(UUID zielUuid, String zielName, String ausfuehrer,
                            ServerScope scope, String herkunft) {
        return entmuten(zielUuid, zielName, ausfuehrer, scope, herkunft, false);
    }

    public boolean entmuten(UUID zielUuid, String zielName, String ausfuehrer,
                            ServerScope scope, String herkunft, boolean silent) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Optional<MuteRecord> aktuell = muteRepository.finden(zielUuid, scope);
        if (aktuell.isPresent() && aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
            muteRepository.entmutenWenn(zielUuid, scope, b -> b.equals(aktuell.get()));
            try {
                historyService.hinzufuegen(HistoryType.UNMUTE, "System", zielUuid, zielName,
                        "Abgelaufen (Grund war: " + aktuell.get().grund() + ")", null,
                        scope, normiereHerkunft(herkunft));
            } catch (Exception e) {
                LOG.debug("Ablauf-History für {} konnte nicht geschrieben werden.", zielUuid, e);
            }
            benachrichtige(zielUuid);
            return false;
        }
        if (aktuell.isEmpty()) {
            return false;
        }
        Optional<MuteRecord> entfernt = muteRepository.entmutenWenn(zielUuid, scope,
                b -> b.equals(aktuell.get()));
        if (entfernt.isEmpty()) {
            return false;
        }
        String herkunftNorm = normiereHerkunft(herkunft);

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("scope", entfernt.get().scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        zielInformieren(zielUuid, MessageKeys.ZIEL_ENTMUTET, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.UNMUTE, ausfuehrer, zielUuid, zielName,
                    "Aufgehoben (Grund war: " + entfernt.get().grund() + ")", null,
                    entfernt.get().scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Unmute-History für {} konnte nicht geschrieben werden (Entmutung bleibt wirksam).", zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_UNMUTE, platzhalter, silent);
        logWriter.schreiben(HistoryType.UNMUTE, ausfuehrer, zielName,
                "Vorheriger Grund: " + entfernt.get().grund(), entfernt.get().scope(), herkunftNorm);
        benachrichtige(zielUuid);
        return true;
    }

    public Optional<MuteRecord> bearbeiten(UUID zielUuid, String zielName, long dauerMillis,
                                           String grund, String ausfuehrer,
                                           ServerScope scopeSelektor, String herkunft) {
        return bearbeiten(zielUuid, zielName, dauerMillis, grund, ausfuehrer, scopeSelektor, herkunft, false);
    }

    public Optional<MuteRecord> bearbeiten(UUID zielUuid, String zielName, long dauerMillis,
                                           String grund, String ausfuehrer,
                                           ServerScope scopeSelektor, String herkunft, boolean silent) {
        Objects.requireNonNull(scopeSelektor, "Der Scope-Selektor darf nicht null sein.");
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.MUTE)) {
                LOG.warn("mute-bearbeiten für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für Mute-Edit {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
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

        Optional<MuteRecord> alt = muteRepository.findeAbdeckend(zielUuid, scopeSelektor, jetzt);
        if (alt.isEmpty()) {
            return Optional.empty();
        }
        String herkunftNorm = normiereHerkunft(herkunft);
        MuteRecord neu = new MuteRecord(zielUuid, zielName,
                grund != null ? grund : alt.get().grund(),
                ausfuehrer, jetzt, ablaufAm, alt.get().scope());

        Optional<MuteRecord> vorher = muteRepository.ersetzenWenn(
                zielUuid, alt.get().scope(), mute -> mute.equals(alt.get()), neu);
        if (vorher.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", neu.grund());
        platzhalter.put("dauer", ZeitFormat.formatRestzeit(dauerMillis));
        platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(ablaufAm));
        platzhalter.put("scope", neu.scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        zielInformieren(zielUuid, MessageKeys.ZIEL_MUTE_BEARBEITET, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.MUTE_EDIT, ausfuehrer, zielUuid, zielName,
                    neu.grund(), ablaufAm, neu.scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Mute-Edit-History für {} konnte nicht geschrieben werden (Mute bleibt aktiv).", zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_MUTE_EDIT, platzhalter, silent);
        logWriter.schreiben(HistoryType.MUTE_EDIT, ausfuehrer, zielName,
                "Grund: " + neu.grund() + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm)
                        + ", Vorheriger Grund: " + vorher.get().grund(),
                neu.scope(), herkunftNorm);
        benachrichtige(zielUuid);
        return Optional.of(neu);
    }

    public MassenErgebnis muteAlle(long dauerMillis, String grund, String ausfuehrer, UUID ausfuehrerUuid,
                        ServerScope scope, String herkunft) {
        return muteAlle(dauerMillis, grund, ausfuehrer, ausfuehrerUuid, scope, herkunft, false);
    }

    public MassenErgebnis muteAlle(long dauerMillis, String grund, String ausfuehrer, UUID ausfuehrerUuid,
                        ServerScope scope, String herkunft, boolean silent) {
        if (dauerMillis <= 0) {
            LOG.warn("muteAlle: ungültige Dauer {} – Abbruch, kein Spieler gemutet", dauerMillis);
            return MassenErgebnis.leer();
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn("muteAlle: Ablauf-Überlauf (jetzt={}, dauer={}) - Abbruch, kein Spieler gemutet", jetzt, dauerMillis, e);
            return MassenErgebnis.leer();
        }
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        List<MuteRecord> kandidaten = new ArrayList<>();
        for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
            if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                continue;
            }
            if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.MUTE)) {
                continue;
            }
            try {
                kandidaten.add(new MuteRecord(
                        online.uuid(), online.name(), grund, ausfuehrer, jetzt, ablaufAm, wirksam));
            } catch (Exception e) {
                LOG.warn("muteAlle: ungültige Eingabedaten für {} – übersprungen.", online.uuid(), e);
            }
        }
        int gekuerzt = 0;
        if (kandidaten.size() > Limits.MUTE_ALL_MAX) {
            gekuerzt = kandidaten.size() - Limits.MUTE_ALL_MAX;
            LOG.warn("muteAlle: {} Kandidaten überschreiten das Limit {} – nur die ersten {} werden gemutet.",
                    kandidaten.size(), Limits.MUTE_ALL_MAX, Limits.MUTE_ALL_MAX);
            kandidaten = new ArrayList<>(kandidaten.subList(0, Limits.MUTE_ALL_MAX));
        }
        List<MuteRecord> gesetzt;
        try {
            gesetzt = new ArrayList<>(muteRepository.mutenAlleWennFrei(kandidaten));
        } catch (Exception e) {
            LOG.warn("muteAlle-Batch fehlgeschlagen (Teile ggf. gesetzt).", e);
            return MassenErgebnis.leer();
        }
        for (MuteRecord kandidat : kandidaten) {
            if (!gesetzt.contains(kandidat)) {
                LOG.debug("muteAlle für {} übersprungen (bereits gemutet).", kandidat.spielerUuid());
            }
        }
        if (gesetzt.isEmpty()) {
            return new MassenErgebnis(0, gekuerzt);
        }
        for (MuteRecord record : gesetzt) {
            try {
                historyService.hinzufuegen(HistoryType.MUTE_ALL, ausfuehrer,
                        record.spielerUuid(), record.spielerName(), grund, ablaufAm,
                        record.scope(), herkunftNorm);
            } catch (Exception e) {
                LOG.warn("MUTE_ALL-History für {} fehlgeschlagen (Mute bleibt aktiv).", record.spielerUuid(), e);
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
        broadcastService.senden(MessageKeys.BROADCAST_MUTE_ALL, platzhalter, silent);
        logWriter.schreiben(HistoryType.MUTE_ALL, ausfuehrer, "alle",
                "Anzahl: " + gesetzt.size() + ", Grund: " + grund, wirksam, herkunftNorm);
        for (MuteRecord record : gesetzt) {
            Map<String, String> einzel = new HashMap<>(platzhalter);
            einzel.put("spieler", record.spielerName());
            zielInformieren(record.spielerUuid(), MessageKeys.ZIEL_GEMUTET, einzel);
            benachrichtige(record.spielerUuid());
        }
        return new MassenErgebnis(gesetzt.size(), gekuerzt);
    }

    public int zaehleMuteAlleKandidaten(UUID ausfuehrerUuid) {
        int anzahl = 0;
        for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
            if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                continue;
            }
            if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.MUTE)) {
                continue;
            }
            anzahl++;
        }
        return anzahl;
    }

    public int entmutenAlle(String grund, String ausfuehrer, String herkunft) {
        return entmutenAlle(grund, ausfuehrer, herkunft, null, false);
    }

    public int entmutenAlle(String grund, String ausfuehrer, String herkunft, ServerScope scopeFilter,
                            boolean silent) {
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        Collection<MuteRecord> alle;
        try {
            alle = List.copyOf(muteRepository.alle());
        } catch (Exception e) {
            LOG.warn("unmute-all: Mute-Scan fehlgeschlagen.", e);
            return 0;
        }
        List<MuteRecord> entfernt = new ArrayList<>();
        for (MuteRecord kandidat : alle) {
            if (kandidat == null) {
                continue;
            }
            if (scopeFilter != null && !kandidat.scope().equals(scopeFilter)) {
                continue;
            }
            try {
                Optional<MuteRecord> weg = muteRepository.entmuten(kandidat.spielerUuid(), kandidat.scope());
                weg.ifPresent(entfernt::add);
            } catch (Exception e) {
                LOG.warn("unmute-all für {} fehlgeschlagen, fahre mit Rest fort.", kandidat.spielerUuid(), e);
            }
        }
        for (MuteRecord record : entfernt) {
            try {
                historyService.hinzufuegen(HistoryType.UNMUTE_ALL, ausfuehrer,
                        record.spielerUuid(), record.spielerName(), grund, null,
                        record.scope(), herkunftNorm);
            } catch (Exception e) {
                LOG.warn("UNMUTE_ALL-History für {} konnte nicht geschrieben werden.", record.spielerUuid(), e);
            }
            benachrichtige(record.spielerUuid());
        }
        if (entfernt.isEmpty()) {
            LOG.info("unmute-all von '{}' ohne Treffer (Scope: {}).", ausfuehrer,
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
        broadcastService.senden(MessageKeys.BROADCAST_UNMUTE_ALL, platzhalter, silent);
        logWriter.schreiben(HistoryType.UNMUTE_ALL, ausfuehrer, "alle",
                "Anzahl: " + entfernt.size() + ", Grund: " + grund
                        + (scopeFilter == null ? "" : ", Scope: " + scopeFilter.anzeige()),
                scopeFilter == null ? ServerScope.unbekannt() : scopeFilter, herkunftNorm);
        return entfernt.size();
    }

    public boolean istAbgedeckt(UUID spielerUuid, ServerScope ziel) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return muteRepository.istAbgedeckt(spielerUuid, ziel, System.currentTimeMillis());
    }

    public boolean istIrgendwoGemutet(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        long jetzt = System.currentTimeMillis();
        return muteRepository.alleFuer(spielerUuid).stream()
                .anyMatch(m -> m != null && m.istAktiv(jetzt));
    }

    public Optional<MuteRecord> finden(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return muteRepository.finden(spielerUuid, scope);
    }

    public Optional<MuteRecord> findeAbdeckend(UUID spielerUuid, ServerScope ziel) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return muteRepository.findeAbdeckend(spielerUuid, ziel, System.currentTimeMillis());
    }

    public boolean istGemutetFuer(UUID spielerUuid, String aktuellerServer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        long jetzt = System.currentTimeMillis();
        return muteRepository.alleFuer(spielerUuid).stream()
                .anyMatch(m -> m != null && m.istAktivFuer(jetzt, aktuellerServer));
    }

    public Optional<MuteRecord> findeAktivenMuteFuer(UUID spielerUuid, long jetztMillis, String aktuellerServer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        MuteRecord exakt = null;
        MuteRecord abdeckend = null;
        for (MuteRecord kandidat : muteRepository.alleFuer(spielerUuid)) {
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

    private static String normiereHerkunft(String herkunft) {
        return herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public java.util.List<MuteRecord> findeAktiveMutesFuerTab() {
        long jetzt = System.currentTimeMillis();
        return muteRepository.alle().stream()
                .filter(m -> m != null && m.istAktiv(jetzt))
                .toList();
    }

    public Optional<MuteRecord> findeVorabMuteNachName(String name, UUID echteUuid) {
        if (name == null || name.isBlank() || echteUuid == null) {
            return Optional.empty();
        }
        long jetzt = System.currentTimeMillis();
        try {
            for (MuteRecord kandidat : muteRepository.alle()) {
                if (kandidat == null || !kandidat.istAktiv(jetzt) || kandidat.spielerUuid().equals(echteUuid)) {
                    continue;
                }
                if (kandidat.spielerName() != null && kandidat.spielerName().equalsIgnoreCase(name.trim())) {
                    return Optional.of(kandidat);
                }
            }
        } catch (Exception e) {
            LOG.debug("Vorab-Mute-Suche für '{}' fehlgeschlagen.", name, e);
        }
        return Optional.empty();
    }

    public Optional<MuteRecord> migriereVorabMute(UUID vorabUuid, UUID echteUuid, String echterName) {
        Objects.requireNonNull(vorabUuid, "Die Vorab-UUID darf nicht null sein.");
        Objects.requireNonNull(echteUuid, "Die echte UUID darf nicht null sein.");
        if (vorabUuid.equals(echteUuid) || echterName == null || echterName.isBlank()) {
            return Optional.empty();
        }
        java.util.List<MuteRecord> kandidaten = new java.util.ArrayList<>();
        for (MuteRecord kandidat : muteRepository.alleFuer(vorabUuid)) {
            if (kandidat != null && kandidat.istAktiv(System.currentTimeMillis())
                    && kandidat.spielerName() != null
                    && kandidat.spielerName().equalsIgnoreCase(echterName)) {
                kandidaten.add(kandidat);
            }
        }
        if (kandidaten.isEmpty()) {
            return Optional.empty();
        }
        Optional<MuteRecord> ersterMigriert = Optional.empty();
        for (MuteRecord vorab : kandidaten) {
            if (!de.lightrixx.punish.storage.PlayerCache.istVorabPlatzhalter(
                    vorabUuid, vorab.spielerName())) {
                LOG.warn("Vorab-Mute für '{}' wird NICHT migriert: {} ist kein nie-gesehener Platzhalter "
                        + "(Namenswiederverwendung, A-002).", echterName, vorabUuid);
                continue;
            }
            if (muteRepository.findeAbdeckend(echteUuid, vorab.scope(),
                    System.currentTimeMillis()).isPresent()) {
                try {
                    muteRepository.entmuten(vorabUuid, vorab.scope());
                } catch (Exception e) {
                    LOG.debug("Verwaister Vorab-Mute für {} konnte nicht aufgeräumt werden.", vorabUuid, e);
                }
                continue;
            }
            long jetzt = System.currentTimeMillis();
            if (vorab.ablaufAm() <= jetzt) {
                continue;
            }
            MuteRecord neu;
            try {
                neu = new MuteRecord(echteUuid, echterName, vorab.grund(),
                        vorab.ausfuehrer(), vorab.erstelltAm(), vorab.ablaufAm(), vorab.scope());
            } catch (Exception e) {
                LOG.debug("Vorab-Mute für {} konnte nicht migriert werden (abgelaufen/ungültig).", echterName, e);
                continue;
            }
            if (!muteRepository.mutenWennFrei(neu)) {
                continue;
            }
            try {
                muteRepository.entmuten(vorabUuid, vorab.scope());
            } catch (Exception e) {
                LOG.warn("Vorab-Mute-Key {} konnte nach Migration nicht entfernt werden.", vorabUuid, e);
            }
            try {
                historyService.hinzufuegen(HistoryType.UNMUTE, "System", vorabUuid, vorab.spielerName(),
                        "Vorab-Mute auf " + echterName + " (" + echteUuid + ") migriert", null,
                        vorab.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Migrations-History (alt) fehlgeschlagen.", e);
            }
            try {
                historyService.hinzufuegen(HistoryType.MUTE, vorab.ausfuehrer(), echteUuid, echterName,
                        vorab.grund() + " (Vorab-Mute migriert)", neu.ablaufAm(),
                        neu.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Migrations-History (neu) fehlgeschlagen.", e);
            }
            LOG.info("Vorab-Mute für '{}' von {} auf {} migriert (Scope {}).", echterName, vorabUuid,
                    echteUuid, vorab.scope().anzeige());
            benachrichtige(vorabUuid);
            benachrichtige(echteUuid);
            if (ersterMigriert.isEmpty()) {
                ersterMigriert = Optional.of(neu);
            }
        }
        return ersterMigriert;
    }

    public int bereinigeAbgelaufeneMitHistory(String ausfuehrer) {
        long jetzt = System.currentTimeMillis();
        java.util.List<MuteRecord> kandidaten;
        try {
            kandidaten = muteRepository.alle().stream()
                    .filter(m -> m != null && m.istAbgelaufen(jetzt))
                    .toList();
        } catch (Exception e) {
            LOG.warn("Ablauf-Scan für Mute-Cleanup fehlgeschlagen.", e);
            return 0;
        }
        int anzahl = 0;
        for (MuteRecord kandidat : kandidaten) {
            try {
                Optional<MuteRecord> aktuell = muteRepository.finden(
                        kandidat.spielerUuid(), kandidat.scope());
                if (aktuell.isEmpty() || !aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
                    continue;
                }
                Optional<MuteRecord> entfernt = muteRepository.entmuten(
                        kandidat.spielerUuid(), kandidat.scope());
                if (entfernt.isEmpty()) {
                    continue;
                }
                MuteRecord rec = entfernt.get();
                try {
                    historyService.hinzufuegen(HistoryType.UNMUTE, ausfuehrer, rec.spielerUuid(),
                            rec.spielerName(), "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                            rec.scope(), ServerScope.UNBEKANNT_WERT);
                } catch (Exception e) {
                    LOG.warn("Ablauf-History für {} konnte nicht geschrieben werden.", rec.spielerUuid(), e);
                }
                logWriter.schreiben(HistoryType.UNMUTE, ausfuehrer, rec.spielerName(),
                        "Abgelaufen. Vorheriger Grund: " + rec.grund(), rec.scope(), ServerScope.UNBEKANNT_WERT);
                benachrichtige(rec.spielerUuid());
                anzahl++;
            } catch (Exception e) {
                LOG.warn("Mute-Ablauf-Cleanup fehlgeschlagen.", e);
            }
        }
        return anzahl;
    }

    private void zielInformieren(UUID zielUuid, String schluessel, Map<String, String> platzhalter) {
        if (!verzeichnis.darfDirektAusfuehren()) {

            try {
                taskScheduler.aufgabe(() -> zielInformieren(zielUuid, schluessel, platzhalter));
            } catch (IllegalStateException e) {
                LOG.debug(
                        "Ziel-Info nach Shutdown verworfen für {}", zielUuid);
            }
            return;
        }
        messageService.anSpieler(zielUuid, schluessel, platzhalter, verzeichnis);
    }
}
