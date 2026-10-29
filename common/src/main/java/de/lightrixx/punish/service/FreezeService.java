package de.lightrixx.punish.service;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.FreezeRecord;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class FreezeService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FreezeService.class);

    private final FreezeRepository freezeRepository;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final MessageService messageService;
    private final AuftragsPlaner taskScheduler;

    private final ProtectionService protectionService;

    public interface FreezeBeobachter {
        void freezeGeaendert(UUID spielerUuid);
    }

    private final java.util.Set<FreezeBeobachter> beobachter =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void beobachterHinzufuegen(FreezeBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.add(beobachter);
        }
    }

    public void beobachterEntfernen(FreezeBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.remove(beobachter);
        }
    }

    private void benachrichtige(UUID spielerUuid) {
        if (spielerUuid == null || beobachter.isEmpty()) {
            return;
        }
        for (FreezeBeobachter b : beobachter) {
            try {
                b.freezeGeaendert(spielerUuid);
            } catch (Exception e) {
                LOG.debug("Freeze-Beobachter fehlgeschlagen für {}.", spielerUuid, e);
            }
        }
    }

    private final SpielerVerzeichnis verzeichnis;

    public FreezeService(FreezeRepository freezeRepository,
                         HistoryService historyService,
                         BroadcastService broadcastService,
                         LogWriter logWriter,
                         MessageService messageService,
                         AuftragsPlaner taskScheduler,
                         ProtectionService protectionService,
                         SpielerVerzeichnis verzeichnis) {
        this.freezeRepository = Objects.requireNonNull(freezeRepository, "Das FreezeRepository darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.messageService = Objects.requireNonNull(messageService, "Der MessageService darf nicht null sein.");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "Der AuftragsPlaner darf nicht null sein.");
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    public boolean freezen(UUID zielUuid, String zielName, String grund, String ausfuehrer) {
        return freezen(zielUuid, zielName, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean freezen(UUID zielUuid, String zielName, String grund, String ausfuehrer,
                           ServerScope scope, String herkunft) {
        return freezen(zielUuid, zielName, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean freezen(UUID zielUuid, String zielName, String grund, String ausfuehrer,
                           ServerScope scope, String herkunft, boolean silent) {
        return freezen(zielUuid, zielName, 0L, true, grund, ausfuehrer, scope, herkunft, silent);
    }

    public boolean freezen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer) {
        return freezen(zielUuid, zielName, dauerMillis, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean freezen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer,
                           ServerScope scope, String herkunft) {
        return freezen(zielUuid, zielName, dauerMillis, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean freezen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer,
                           ServerScope scope, String herkunft, boolean silent) {
        return freezen(zielUuid, zielName, dauerMillis, false, grund, ausfuehrer, scope, herkunft, silent);
    }

    private boolean freezen(UUID zielUuid, String zielName, long dauerMillis, boolean permanent,
                            String grund, String ausfuehrer,
                            ServerScope scope, String herkunft, boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.FREEZE)) {
                LOG.warn("freezen für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return false;
        }
        if (!permanent && dauerMillis <= 0) {
            LOG.warn("freezen: ungültige Dauer {} für {} – abgelehnt.", dauerMillis, zielUuid);
            return false;
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        if (permanent) {
            ablaufAm = Long.MAX_VALUE;
        } else {
            try {
                ablaufAm = Math.addExact(jetzt, dauerMillis);
            } catch (ArithmeticException e) {
                LOG.warn(
                        "freezen: Ablauf-Überlauf (jetzt={}, dauer={}) für {} – als DAUER_UNGUELTIG werten",
                        jetzt, dauerMillis, zielUuid, e);
                return false;
            }
        }

        FreezeRecord record;
        try {
            record = new FreezeRecord(zielUuid, zielName, grund, ausfuehrer, jetzt, ablaufAm, wirksam);
        } catch (Exception e) {
            LOG.warn("freezen für {} abgewiesen: ungültige Eingabedaten (Name/Grund).", zielUuid, e);
            return false;
        }
        if (!freezeRepository.freezenWennFrei(record)) {
            return false;
        }

        String dauerText = permanent ? Limits.PERMANENT_ANZEIGE : ZeitFormat.formatRestzeit(dauerMillis);
        String ablaufText = permanent ? Limits.PERMANENT_ANZEIGE : ZeitFormat.formatZeitpunkt(ablaufAm);
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        platzhalter.put("dauer", dauerText);
        platzhalter.put("verbleibend", dauerText);
        platzhalter.put("ablauf", ablaufText);
        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        zielInformieren(zielUuid, MessageKeys.ZIEL_GEFROR, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.FREEZE, ausfuehrer, zielUuid, zielName, grund,
                    permanent ? null : ablaufAm, wirksam, herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Freeze-History für {} konnte nicht geschrieben werden (Freeze bleibt aktiv).", zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_FREEZE, platzhalter, silent);
        logWriter.schreiben(HistoryType.FREEZE, ausfuehrer, zielName,
                "Grund: " + grund + ", Dauer: " + dauerText
                        + ", Ablauf: " + ablaufText,
                wirksam, herkunftNorm);
        benachrichtige(zielUuid);
        return true;
    }

    public boolean entfreezen(UUID zielUuid, String zielName, String ausfuehrer,
                              ServerScope scope, String herkunft) {
        return entfreezen(zielUuid, zielName, ausfuehrer, scope, herkunft, false);
    }

    public boolean entfreezen(UUID zielUuid, String zielName, String ausfuehrer,
                              ServerScope scope, String herkunft, boolean silent) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Optional<FreezeRecord> aktuell = freezeRepository.finden(zielUuid, scope);
        if (aktuell.isPresent() && aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
            freezeRepository.entfreezenWenn(zielUuid, scope, b -> b.equals(aktuell.get()));
            try {
                historyService.hinzufuegen(HistoryType.UNFREEZE, "System", zielUuid, zielName,
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
        Optional<FreezeRecord> entfernt = freezeRepository.entfreezenWenn(zielUuid, scope,
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

        zielInformieren(zielUuid, MessageKeys.ZIEL_ENTFROR, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.UNFREEZE, ausfuehrer, zielUuid, zielName,
                    "Aufgehoben (Grund war: " + entfernt.get().grund() + ")", null,
                    entfernt.get().scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Unfreeze-History für {} konnte nicht geschrieben werden (Entfreeze bleibt wirksam).", zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_UNFREEZE, platzhalter, silent);
        logWriter.schreiben(HistoryType.UNFREEZE, ausfuehrer, zielName,
                "Vorheriger Grund: " + entfernt.get().grund(), entfernt.get().scope(), herkunftNorm);
        benachrichtige(zielUuid);
        return true;
    }

    public boolean istAbgedeckt(UUID spielerUuid, ServerScope ziel) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return freezeRepository.istAbgedeckt(spielerUuid, ziel, System.currentTimeMillis());
    }

    public boolean istIrgendwoGefroren(UUID spielerUuid) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        long jetzt = System.currentTimeMillis();
        return freezeRepository.alleFuer(spielerUuid).stream()
                .anyMatch(f -> f != null && f.istAktiv(jetzt));
    }

    public Optional<FreezeRecord> finden(UUID spielerUuid, ServerScope scope) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return freezeRepository.finden(spielerUuid, scope);
    }

    public Optional<FreezeRecord> findeAbdeckend(UUID spielerUuid, ServerScope ziel) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return freezeRepository.findeAbdeckend(spielerUuid, ziel, System.currentTimeMillis());
    }

    public boolean istGefrorenFuer(UUID spielerUuid, String aktuellerServer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        long jetzt = System.currentTimeMillis();
        return freezeRepository.alleFuer(spielerUuid).stream()
                .anyMatch(f -> f != null && f.istAktivFuer(jetzt, aktuellerServer));
    }

    public Optional<FreezeRecord> findeAktivenFreezeFuer(UUID spielerUuid, long jetztMillis, String aktuellerServer) {
        Objects.requireNonNull(spielerUuid, "Die Spieler-UUID darf nicht null sein.");
        FreezeRecord exakt = null;
        FreezeRecord abdeckend = null;
        for (FreezeRecord kandidat : freezeRepository.alleFuer(spielerUuid)) {
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

    public java.util.List<FreezeRecord> findeAktiveFreezesFuerTab() {
        long jetzt = System.currentTimeMillis();
        return freezeRepository.alle().stream()
                .filter(f -> f != null && f.istAktiv(jetzt))
                .toList();
    }

    public int bereinigeAbgelaufeneMitHistory(String ausfuehrer) {
        long jetzt = System.currentTimeMillis();
        java.util.List<FreezeRecord> kandidaten;
        try {
            kandidaten = freezeRepository.alle().stream()
                    .filter(f -> f != null && f.istAbgelaufen(jetzt))
                    .toList();
        } catch (Exception e) {
            LOG.warn("Ablauf-Scan für Freeze-Cleanup fehlgeschlagen.", e);
            return 0;
        }
        int anzahl = 0;
        for (FreezeRecord kandidat : kandidaten) {
            try {
                Optional<FreezeRecord> aktuell = freezeRepository.finden(
                        kandidat.spielerUuid(), kandidat.scope());
                if (aktuell.isEmpty() || !aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
                    continue;
                }
                Optional<FreezeRecord> entfernt = freezeRepository.entfreezen(
                        kandidat.spielerUuid(), kandidat.scope());
                if (entfernt.isEmpty()) {
                    continue;
                }
                FreezeRecord rec = entfernt.get();
                try {
                    historyService.hinzufuegen(HistoryType.UNFREEZE, ausfuehrer, rec.spielerUuid(),
                            rec.spielerName(), "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                            rec.scope(), ServerScope.UNBEKANNT_WERT);
                } catch (Exception e) {
                    LOG.warn("Ablauf-History für {} konnte nicht geschrieben werden.", rec.spielerUuid(), e);
                }
                logWriter.schreiben(HistoryType.UNFREEZE, ausfuehrer, rec.spielerName(),
                        "Abgelaufen. Vorheriger Grund: " + rec.grund(), rec.scope(), ServerScope.UNBEKANNT_WERT);
                benachrichtige(rec.spielerUuid());
                anzahl++;
            } catch (Exception e) {
                LOG.warn("Freeze-Ablauf-Cleanup fehlgeschlagen.", e);
            }
        }
        return anzahl;
    }

    private static String normiereHerkunft(String herkunft) {
        return herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
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
