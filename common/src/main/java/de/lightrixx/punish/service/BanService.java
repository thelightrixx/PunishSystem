package de.lightrixx.punish.service;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.util.ZeitFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BanService {

    private static final Logger LOG = LoggerFactory.getLogger(BanService.class);

    private final BanRepository banRepository;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final ProtectionService protectionService;
    private final BanKickHelper kickHelper;
    private final SpielerVerzeichnis verzeichnis;

    public interface BanBeobachter {
        void banGeaendert();
    }

    private final java.util.Set<BanBeobachter> beobachter =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void beobachterHinzufuegen(BanBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.add(beobachter);
        }
    }

    public void beobachterEntfernen(BanBeobachter beobachter) {
        if (beobachter != null) {
            this.beobachter.remove(beobachter);
        }
    }

    private void benachrichtige() {
        if (beobachter.isEmpty()) {
            return;
        }
        for (BanBeobachter b : beobachter) {
            try {
                b.banGeaendert();
            } catch (Exception e) {
                LOG.debug("Ban-Beobachter fehlgeschlagen.", e);
            }
        }
    }

    public BanService(BanRepository banRepository,
                      HistoryService historyService,
                      BroadcastService broadcastService,
                      LogWriter logWriter,
                      ProtectionService protectionService,
                      BanKickHelper kickHelper,
                      SpielerVerzeichnis verzeichnis) {
        this.banRepository = Objects.requireNonNull(banRepository, "Das BanRepository darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.kickHelper = Objects.requireNonNull(kickHelper, "Der BanKickHelper darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    public boolean bannen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer) {
        return bannen(zielUuid, zielName, dauerMillis, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean bannen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer,
                          ServerScope scope, String herkunft) {
        return bannen(zielUuid, zielName, dauerMillis, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean bannen(UUID zielUuid, String zielName, long dauerMillis, String grund, String ausfuehrer,
                          ServerScope scope, String herkunft, boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);

        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.BAN)) {
                LOG.warn("bannen für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return false;
        }
        if (dauerMillis <= 0) {
            LOG.warn("bannen: ungültige Dauer {} für {} – abgelehnt.", dauerMillis, zielUuid);
            return false;
        }
        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn("bannen: Ablauf-Überlauf (jetzt={}, dauer={}) für {} – als DAUER_UNGUELTIG werten, nicht als BEREITS_GEBANNT",
                    jetzt, dauerMillis, zielUuid, e);
            return false;
        }

        BanRecord record;
        try {
            record = new BanRecord(zielUuid, zielName, grund, ausfuehrer, jetzt, ablaufAm, false, wirksam);
        } catch (Exception e) {
            LOG.warn("bannen für {} abgewiesen: ungültige Eingabedaten (Name/Grund).", zielUuid, e);
            return false;
        }
        if (!banRepository.bannenWennFrei(record)) {
            return false;
        }

        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                zielName, ausfuehrer, grund,
                ZeitFormat.formatRestzeit(dauerMillis),
                ZeitFormat.formatRestzeit(dauerMillis),
                ZeitFormat.formatZeitpunkt(ablaufAm), null);
        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        try {
            historyService.hinzufuegen(HistoryType.BAN, ausfuehrer, zielUuid, zielName, grund, ablaufAm,
                    wirksam, herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Ban-History für {} konnte nicht geschrieben werden (Ban bleibt aktiv).", zielUuid, e);
        }
        kickHelper.spielerKicken(zielUuid, MessageKeys.SCREEN_BANN, platzhalter);
        broadcastService.senden(MessageKeys.BROADCAST_BAN, platzhalter, silent);
        logWriter.schreiben(HistoryType.BAN, ausfuehrer, zielName,
                "Grund: " + grund + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm),
                wirksam, herkunftNorm);
        benachrichtige();
        return true;
    }

    public boolean permabann(UUID zielUuid, String zielName, String grund, String ausfuehrer) {
        return permabann(zielUuid, zielName, grund, ausfuehrer,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public boolean permabann(UUID zielUuid, String zielName, String grund, String ausfuehrer,
                             ServerScope scope, String herkunft) {
        return permabann(zielUuid, zielName, grund, ausfuehrer, scope, herkunft, false);
    }

    public boolean permabann(UUID zielUuid, String zielName, String grund, String ausfuehrer,
                             ServerScope scope, String herkunft, boolean silent) {
        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.BAN)) {
                LOG.warn("permabann für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return false;
        }
        long jetzt = System.currentTimeMillis();
        BanRecord record;
        try {
            record = new BanRecord(zielUuid, zielName, grund, ausfuehrer, jetzt, null, false, wirksam);
        } catch (Exception e) {
            LOG.warn("permabann für {} abgewiesen: ungültige Eingabedaten (Name/Grund).", zielUuid, e);
            return false;
        }
        if (!banRepository.bannenWennFrei(record)) {
            return false;
        }

        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                zielName, ausfuehrer, grund,
                Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, null);
        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        try {
            historyService.hinzufuegen(HistoryType.PERMABANN, ausfuehrer, zielUuid, zielName, grund, null,
                    wirksam, herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Permabann-History für {} konnte nicht geschrieben werden (Ban bleibt aktiv).", zielUuid, e);
        }
        kickHelper.spielerKicken(zielUuid, MessageKeys.SCREEN_BANN_PERMANENT, platzhalter);
        broadcastService.senden(MessageKeys.BROADCAST_PERMABANN, platzhalter, silent);
        logWriter.schreiben(HistoryType.PERMABANN, ausfuehrer, zielName,
                "Grund: " + grund + ", Dauer: Permanent", wirksam, herkunftNorm);
        benachrichtige();
        return true;
    }

    public boolean entbannen(UUID zielUuid, String zielName, String ausfuehrer,
                             ServerScope scope, String herkunft) {
        return entbannen(zielUuid, zielName, ausfuehrer, scope, herkunft, false);
    }

    public boolean entbannen(UUID zielUuid, String zielName, String ausfuehrer,
                             ServerScope scope, String herkunft, boolean silent) {
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        Optional<BanRecord> aktuell = banRepository.finden(zielUuid, scope);
        if (aktuell.isPresent() && aktuell.get().istAbgelaufen(System.currentTimeMillis())) {
            banRepository.entbannenWenn(zielUuid, scope, b -> b.equals(aktuell.get()));
            String ablaufHerkunft = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                    : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
            try {
                historyService.hinzufuegen(HistoryType.PARDON, "System", zielUuid, zielName,
                        "Abgelaufen (Grund war: " + aktuell.get().grund() + ")", null,
                        aktuell.get().scope(), ablaufHerkunft);
            } catch (Exception e) {
                LOG.debug("Ablauf-History für {} konnte nicht geschrieben werden.", zielUuid, e);
            }
            benachrichtige();
            return false;
        }
        if (aktuell.isEmpty()) {
            return false;
        }
        Optional<BanRecord> entfernt = banRepository.entbannenWenn(zielUuid, scope,
                b -> b.equals(aktuell.get()));
        if (entfernt.isEmpty()) {
            return false;
        }
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        ServerScope entfernterScope = entfernt.get().scope();

        boolean warAuto = entfernt.get().autoBann();
        Map<String, String> platzhalter = BanKickHelper.platzhalter(zielName, ausfuehrer, "", "", "", "", null);
        platzhalter.put("scope", entfernterScope.anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        try {
            historyService.hinzufuegen(HistoryType.PARDON, ausfuehrer, zielUuid, zielName,
                    warAuto
                            ? "Aufgehoben (Auto-Bann, Grund war: " + entfernt.get().grund()
                                    + " – Verstöße bleiben, nächster Verstoß = Re-Bann)"
                            : "Aufgehoben (Grund war: " + entfernt.get().grund() + ")",
                    null, entfernterScope, herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Pardon-History für {} konnte nicht geschrieben werden (Ban bleibt entbannt).", zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_PARDON, platzhalter, silent);
        logWriter.schreiben(HistoryType.PARDON, ausfuehrer, zielName,
                (warAuto ? "Auto-Bann manuell aufgehoben. " : "")
                        + "Vorheriger Grund: " + entfernt.get().grund()
                        + (warAuto ? " (Verstöße bleiben am Limit!)" : ""),
                entfernterScope, herkunftNorm);
        if (warAuto) {
            LOG.warn("Manueller Pardon eines Auto-Banns für {} – Verstöße bleiben bestehen, "
                    + "nächster Verstoß löst sofort Re-Bann aus.", zielName);
        }
        benachrichtige();
        return true;
    }

    public Optional<BanRecord> bearbeiten(UUID zielUuid, String zielName, long dauerMillis,
                                          String grund, String ausfuehrer,
                                          ServerScope scopeSelektor, String herkunft) {
        return bearbeiten(zielUuid, zielName, dauerMillis, grund, ausfuehrer, scopeSelektor, herkunft, false);
    }

    public Optional<BanRecord> bearbeiten(UUID zielUuid, String zielName, long dauerMillis,
                                          String grund, String ausfuehrer,
                                          ServerScope scopeSelektor, String herkunft, boolean silent) {
        Objects.requireNonNull(scopeSelektor, "Der Scope-Selektor darf nicht null sein.");

        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.BAN)) {
                LOG.warn("bearbeiten für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
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

        Optional<BanRecord> alt = banRepository.findeAbdeckend(zielUuid, scopeSelektor, jetzt)
                .filter(bann -> !bann.autoBann());
        if (alt.isEmpty()) {
            return Optional.empty();
        }
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        BanRecord neu = new BanRecord(zielUuid, zielName,
                grund != null ? grund : alt.get().grund(),
                ausfuehrer, jetzt, ablaufAm, false, alt.get().scope());

        Optional<BanRecord> vorher = banRepository.ersetzenWenn(
                zielUuid, alt.get().scope(), bann -> bann.equals(alt.get()), neu);
        if (vorher.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                zielName, ausfuehrer, neu.grund(),
                ZeitFormat.formatRestzeit(dauerMillis),
                ZeitFormat.formatRestzeit(dauerMillis),
                ZeitFormat.formatZeitpunkt(ablaufAm), null);
        platzhalter.put("scope", neu.scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);

        try {
            historyService.hinzufuegen(HistoryType.BAN_EDIT, ausfuehrer, zielUuid, zielName,
                    neu.grund(), ablaufAm, neu.scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Ban-Edit-History für {} konnte nicht geschrieben werden (Ban bleibt aktiv).", zielUuid, e);
        }
        kickHelper.spielerKicken(zielUuid, MessageKeys.SCREEN_BANN, platzhalter);
        broadcastService.senden(MessageKeys.BROADCAST_BAN_EDIT, platzhalter, silent);
        logWriter.schreiben(HistoryType.BAN_EDIT, ausfuehrer, zielName,
                "Grund: " + neu.grund() + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis)
                        + ", Ablauf: " + ZeitFormat.formatZeitpunkt(ablaufAm)
                        + ", Vorheriger Grund: " + vorher.get().grund(),
                neu.scope(), herkunftNorm);
        benachrichtige();
        return Optional.of(neu);
    }

    public Optional<BanRecord> bearbeitenPermanent(UUID zielUuid, String zielName,
                                                  String grund, String ausfuehrer,
                                                  ServerScope scopeSelektor, String herkunft, boolean silent) {
        Objects.requireNonNull(scopeSelektor, "Der Scope-Selektor darf nicht null sein.");
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.BAN)) {
                LOG.warn("bearbeiten-perm für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return Optional.empty();
        }
        long jetzt = System.currentTimeMillis();
        Optional<BanRecord> alt = banRepository.findeAbdeckend(zielUuid, scopeSelektor, jetzt)
                .filter(bann -> !bann.autoBann());
        if (alt.isEmpty()) {
            return Optional.empty();
        }
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        BanRecord neu = new BanRecord(zielUuid, zielName,
                grund != null ? grund : alt.get().grund(),
                ausfuehrer, jetzt, null, false, alt.get().scope());
        Optional<BanRecord> vorher = banRepository.ersetzenWenn(
                zielUuid, alt.get().scope(), bann -> bann.equals(alt.get()), neu);
        if (vorher.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                zielName, ausfuehrer, neu.grund(),
                Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, Limits.PERMANENT_ANZEIGE, null);
        platzhalter.put("scope", neu.scope().anzeige());
        platzhalter.put("herkunft", herkunftNorm);
        try {
            historyService.hinzufuegen(HistoryType.BAN_EDIT, ausfuehrer, zielUuid, zielName,
                    neu.grund(), null, neu.scope(), herkunftNorm);
        } catch (Exception e) {
            LOG.warn("Ban-Edit-History für {} konnte nicht geschrieben werden (Ban bleibt aktiv).", zielUuid, e);
        }
        kickHelper.spielerKicken(zielUuid, MessageKeys.SCREEN_BANN_PERMANENT, platzhalter);
        broadcastService.senden(MessageKeys.BROADCAST_BAN_EDIT, platzhalter, silent);
        logWriter.schreiben(HistoryType.BAN_EDIT, ausfuehrer, zielName,
                "Grund: " + neu.grund() + ", Dauer: Permanent"
                        + ", Vorheriger Grund: " + vorher.get().grund(),
                neu.scope(), herkunftNorm);
        benachrichtige();
        return Optional.of(neu);
    }

    public int entbannenAlle(String grund, String ausfuehrer) {
        return entbannenAlle(grund, ausfuehrer, ServerScope.UNBEKANNT_WERT);
    }

    public int entbannenAlle(String grund, String ausfuehrer, String herkunft) {
        return entbannenAlle(grund, ausfuehrer, herkunft, null);
    }

    public int entbannenAlle(String grund, String ausfuehrer, String herkunft, ServerScope scopeFilter) {
        return entbannenAlle(grund, ausfuehrer, herkunft, scopeFilter, false);
    }

    public int entbannenAlle(String grund, String ausfuehrer, String herkunft, ServerScope scopeFilter,
                             boolean silent) {
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        Collection<BanRecord> entfernt = scopeFilter == null
                ? banRepository.alleEntbannen()
                : banRepository.alleEntbannenNachScope(scopeFilter);
        for (BanRecord record : entfernt) {
            try {
                historyService.hinzufuegen(HistoryType.PARDON_ALL, ausfuehrer,
                        record.spielerUuid(), record.spielerName(), grund, null,
                        record.scope(), herkunftNorm);
            } catch (Exception e) {
                LOG.warn("PardonAll-History für {} konnte nicht geschrieben werden.", record.spielerUuid(), e);
            }
        }

        if (entfernt.isEmpty()) {
            LOG.info("pardon-all von '{}' ohne Treffer (Scope: {}).", ausfuehrer,
                    scopeFilter == null ? "alle" : scopeFilter.anzeige());
            return 0;
        }
        Map<String, String> platzhalter = BanKickHelper.platzhalter("", ausfuehrer, grund, "", "", "", null);
        platzhalter.put("anzahl", String.valueOf(entfernt.size()));
        if (scopeFilter != null) {
            platzhalter.put("scope", scopeFilter.anzeige());
        }
        broadcastService.senden(MessageKeys.BROADCAST_PARDON_ALL, platzhalter, silent);
        logWriter.schreiben(HistoryType.PARDON_ALL, ausfuehrer, "alle",
                "Anzahl: " + entfernt.size() + ", Grund: " + grund
                        + (scopeFilter == null ? "" : ", Scope: " + scopeFilter.anzeige()),
                scopeFilter == null ? ServerScope.UNBEKANNT_WERT : scopeFilter.speicherwert(), herkunftNorm);
        benachrichtige();
        return entfernt.size();
    }

    public MassenErgebnis banAlle(long dauerMillis, boolean permanent, String grund, String ausfuehrer, UUID ausfuehrerUuid) {
        return banAlle(dauerMillis, permanent, grund, ausfuehrer, ausfuehrerUuid,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public MassenErgebnis banAlle(long dauerMillis, boolean permanent, String grund, String ausfuehrer, UUID ausfuehrerUuid,
                       ServerScope scope, String herkunft) {
        return banAlle(dauerMillis, permanent, grund, ausfuehrer, ausfuehrerUuid, scope, herkunft, false);
    }

    public MassenErgebnis banAlle(long dauerMillis, boolean permanent, String grund, String ausfuehrer, UUID ausfuehrerUuid,
                       ServerScope scope, String herkunft, boolean silent) {
        if (!permanent && dauerMillis <= 0) {
            LOG.warn("banAlle: ungültige Dauer {} – Abbruch, kein Spieler gebannt", dauerMillis);
            return MassenErgebnis.leer();
        }
        long jetzt = System.currentTimeMillis();
        Long ablaufAm = null;
        if (!permanent) {
            try {
                ablaufAm = Math.addExact(jetzt, dauerMillis);
            } catch (ArithmeticException e) {
                LOG.warn("banAlle: Ablauf-Überlauf (jetzt={}, dauer={}) - Abbruch, kein Spieler gebannt", jetzt, dauerMillis, e);
                return MassenErgebnis.leer();
            }
        }

        ServerScope wirksam = scope == null ? ServerScope.unbekannt() : scope;
        String herkunftNorm = herkunft == null || herkunft.isBlank() ? ServerScope.UNBEKANNT_WERT
                : herkunft.trim().toLowerCase(java.util.Locale.ROOT);
        List<BanRecord> kandidaten = new ArrayList<>();
        for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
            if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                continue;
            }
            if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.BAN)) {
                continue;
            }
            try {
                kandidaten.add(new BanRecord(
                        online.uuid(), online.name(), grund, ausfuehrer, jetzt, ablaufAm, false, wirksam));
            } catch (Exception e) {
                LOG.warn("banAlle: Kandidat {} übersprungen (ungültiger Datensatz).", online.uuid(), e);
            }
        }

        int gekuerzt = 0;
        if (kandidaten.size() > Limits.BAN_ALL_MAX) {
            gekuerzt = kandidaten.size() - Limits.BAN_ALL_MAX;
            LOG.warn("banAlle: {} Kandidaten überschreiten das Limit {} – nur die ersten {} werden gebannt.",
                    kandidaten.size(), Limits.BAN_ALL_MAX, Limits.BAN_ALL_MAX);
            kandidaten = new ArrayList<>(kandidaten.subList(0, Limits.BAN_ALL_MAX));
        }

        Collection<BanRecord> gesetzt = banRepository.bannenAlleWennFrei(kandidaten);
        if (gesetzt.isEmpty()) {
            return new MassenErgebnis(0, gekuerzt);
        }

        for (BanRecord record : gesetzt) {
            try {
                historyService.hinzufuegen(HistoryType.BAN_ALL, ausfuehrer,
                        record.spielerUuid(), record.spielerName(), grund, ablaufAm,
                        record.scope(), herkunftNorm);
            } catch (Exception e) {
                LOG.warn("BAN_ALL-History für {} fehlgeschlagen (Ban bleibt aktiv).", record.spielerUuid(), e);
            }
        }

        String dauerText = permanent ? Limits.PERMANENT_ANZEIGE : ZeitFormat.formatRestzeit(dauerMillis);
        String verbleibendText = permanent ? Limits.PERMANENT_ANZEIGE : ZeitFormat.formatRestzeit(dauerMillis);
        String ablaufText = (permanent || ablaufAm == null) ? Limits.PERMANENT_ANZEIGE : ZeitFormat.formatZeitpunkt(ablaufAm);

        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                "", ausfuehrer, grund, dauerText, verbleibendText, ablaufText, null);
        platzhalter.put("anzahl", String.valueOf(gesetzt.size()));

        platzhalter.put("scope", wirksam.anzeige());
        platzhalter.put("herkunft", herkunftNorm);
        broadcastService.senden(MessageKeys.BROADCAST_BAN_ALL, platzhalter, silent);
        logWriter.schreiben(HistoryType.BAN_ALL, ausfuehrer, "alle",
                "Anzahl: " + gesetzt.size() + ", Grund: " + grund, wirksam, herkunftNorm);

        for (BanRecord record : gesetzt) {
            Map<String, String> einzel = new HashMap<>(platzhalter);
            einzel.put("spieler", record.spielerName());
            einzel.put("grund", grund);
            if (permanent || ablaufAm == null) {
                einzel.put("dauer", Limits.PERMANENT_ANZEIGE);
                einzel.put("verbleibend", Limits.PERMANENT_ANZEIGE);
                einzel.put("ablauf", Limits.PERMANENT_ANZEIGE);
            } else {
                einzel.put("dauer", dauerText);
                einzel.put("verbleibend", verbleibendText);
                einzel.put("ablauf", ablaufText);
            }
            try {
                kickHelper.spielerKicken(record.spielerUuid(), permanent ? MessageKeys.SCREEN_BANN_PERMANENT : MessageKeys.SCREEN_BANN, einzel);
            } catch (Exception e) {
                LOG.warn("Massen-Kick für {} fehlgeschlagen (Ban bleibt aktiv).", record.spielerUuid(), e);
            }
        }
        benachrichtige();
        return new MassenErgebnis(gesetzt.size(), gekuerzt);
    }

    public int zaehleBanAlleKandidaten(UUID ausfuehrerUuid) {
        int anzahl = 0;
        for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
            if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                continue;
            }
            if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.BAN)) {
                continue;
            }
            anzahl++;
        }
        return anzahl;
    }

    public boolean istAbgedeckt(UUID zielUuid, ServerScope ziel) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return banRepository.istAbgedeckt(zielUuid, ziel, System.currentTimeMillis());
    }

    public boolean istAktivGebanntFuer(UUID zielUuid, String aktuellerServer) {
        return banRepository.alleFuer(zielUuid).stream()
                .anyMatch(b -> b != null && b.istAktivFuer(System.currentTimeMillis(), aktuellerServer));
    }

    public Optional<BanRecord> findeAbdeckend(UUID zielUuid, ServerScope ziel) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(ziel, "Der Ziel-Scope darf nicht null sein.");
        return banRepository.findeAbdeckend(zielUuid, ziel, System.currentTimeMillis());
    }

    public Optional<BanRecord> findeWirksam(UUID zielUuid, String aktuellerServer) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        long jetzt = System.currentTimeMillis();
        BanRecord exakt = null;
        BanRecord abdeckend = null;
        for (BanRecord kandidat : banRepository.alleFuer(zielUuid)) {
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

    public java.util.Collection<BanRecord> alleBannsFuerTab() {
        return banRepository.alle();
    }

    public Optional<BanRecord> finden(UUID zielUuid, ServerScope scope) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(scope, "Der Scope darf nicht null sein.");
        return banRepository.finden(zielUuid, scope);
    }

    public Optional<BanRecord> findeVorabBannNachName(String name, UUID echteUuid) {
        if (name == null || name.isBlank() || echteUuid == null) {
            return Optional.empty();
        }
        long jetzt = System.currentTimeMillis();
        try {
            for (BanRecord kandidat : banRepository.alle()) {
                if (kandidat == null || !kandidat.istAktiv(jetzt) || kandidat.spielerUuid().equals(echteUuid)) {
                    continue;
                }
                if (kandidat.spielerName() != null && kandidat.spielerName().equalsIgnoreCase(name.trim())) {
                    return Optional.of(kandidat);
                }
            }
        } catch (Exception e) {
            LOG.debug("Vorab-Bann-Suche für '{}' fehlgeschlagen.", name, e);
        }
        return Optional.empty();
    }

    public Optional<BanRecord> migriereVorabBann(UUID vorabUuid, UUID echteUuid, String echterName) {
        Objects.requireNonNull(vorabUuid, "Die Vorab-UUID darf nicht null sein.");
        Objects.requireNonNull(echteUuid, "Die echte UUID darf nicht null sein.");
        if (vorabUuid.equals(echteUuid) || echterName == null || echterName.isBlank()) {
            return Optional.empty();
        }
        java.util.List<BanRecord> kandidaten = new java.util.ArrayList<>();
        for (BanRecord kandidat : banRepository.alleFuer(vorabUuid)) {
            if (kandidat != null && kandidat.istAktiv(System.currentTimeMillis())
                    && kandidat.spielerName() != null
                    && kandidat.spielerName().equalsIgnoreCase(echterName)) {
                kandidaten.add(kandidat);
            }
        }
        if (kandidaten.isEmpty()) {
            return Optional.empty();
        }
        Optional<BanRecord> ersterMigriert = Optional.empty();
        for (BanRecord vorab : kandidaten) {
            if (!de.lightrixx.punish.storage.PlayerCache.istVorabPlatzhalter(
                    vorabUuid, vorab.spielerName())) {
                LOG.warn("Vorab-Bann für '{}' wird NICHT migriert: {} ist kein nie-gesehener Platzhalter "
                        + "(Namenswiederverwendung, A-002).", echterName, vorabUuid);
                continue;
            }
            if (banRepository.findeAbdeckend(echteUuid, vorab.scope(),
                    System.currentTimeMillis()).isPresent()) {

                try {
                    banRepository.entbannen(vorabUuid, vorab.scope());
                } catch (Exception e) {
                    LOG.debug("Verwaister Vorab-Bann für {} konnte nicht aufgeräumt werden.", vorabUuid, e);
                }
                continue;
            }
            long jetzt = System.currentTimeMillis();
            Long vorabAblauf = vorab.ablaufAm();
            if (vorabAblauf != null && vorabAblauf <= jetzt) {
                continue;
            }
            BanRecord neu;
            try {
                neu = new BanRecord(echteUuid, echterName, vorab.grund(),
                        vorab.ausfuehrer(), vorab.erstelltAm(), vorabAblauf, vorab.autoBann(), vorab.scope());
            } catch (Exception e) {
                LOG.debug("Vorab-Bann für {} konnte nicht migriert werden (abgelaufen/ungültig).", echterName, e);
                continue;
            }
            if (!banRepository.bannenWennFrei(neu)) {
                continue;
            }
            try {
                banRepository.entbannen(vorabUuid, vorab.scope());
            } catch (Exception e) {
                LOG.warn("Vorab-Bann-Key {} konnte nach Migration nicht entfernt werden.", vorabUuid, e);
            }
            try {
                historyService.hinzufuegen(HistoryType.PARDON, "System", vorabUuid, vorab.spielerName(),
                        "Vorab-Bann auf " + echterName + " (" + echteUuid + ") migriert", null,
                        vorab.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Migrations-History (alt) fehlgeschlagen.", e);
            }
            try {
                historyService.hinzufuegen(HistoryType.BAN, vorab.ausfuehrer(), echteUuid, echterName,
                        vorab.grund() + " (Vorab-Bann migriert)", neu.ablaufAm(),
                        neu.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.debug("Migrations-History (neu) fehlgeschlagen.", e);
            }
            logWriter.schreiben(HistoryType.BAN, "System", echterName,
                    "Vorab-Bann von " + vorabUuid + " migriert. Grund: " + vorab.grund(),
                    neu.scope(), ServerScope.UNBEKANNT_WERT);
            LOG.info("Vorab-Bann für '{}' von {} auf {} migriert (Scope {}).", echterName, vorabUuid,
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

    public int bereinigeAbgelaufeneMitHistory(String ausfuehrer) {
        long jetzt = System.currentTimeMillis();
        List<BanRecord> kandidaten;
        try {
            kandidaten = banRepository.alle().stream()
                    .filter(b -> b != null && !b.autoBann() && b.istAbgelaufen(jetzt))
                    .toList();
        } catch (Exception e) {
            LOG.warn("Ablauf-Scan für Ban-Cleanup fehlgeschlagen.", e);
            return 0;
        }
        int anzahl = 0;
        for (BanRecord kandidat : kandidaten) {
            if (bereinigeAblauf(kandidat.spielerUuid(), kandidat.spielerName(), ausfuehrer)) {
                anzahl++;
            }
        }
        return anzahl;
    }

    public boolean bereinigeAblauf(UUID zielUuid, String zielName, String ausfuehrer) {

        boolean einerEntfernt = false;
        for (BanRecord kandidat : List.copyOf(banRepository.alleFuer(zielUuid))) {
            if (kandidat == null || kandidat.autoBann()
                    || !kandidat.istAbgelaufen(System.currentTimeMillis())) {
                continue;
            }
            Optional<BanRecord> entfernt;
            try {
                entfernt = banRepository.entbannenWenn(zielUuid, kandidat.scope(),
                        b -> !b.autoBann() && b.istAbgelaufen(System.currentTimeMillis()));
            } catch (Exception e) {
                LOG.warn("Ban-Ablauf-Cleanup für {} fehlgeschlagen.", zielUuid, e);
                continue;
            }
            if (entfernt.isEmpty()) {
                continue;
            }
            BanRecord rec = entfernt.get();
            try {
                historyService.hinzufuegen(HistoryType.PARDON, ausfuehrer, rec.spielerUuid(),
                        rec.spielerName(), "Abgelaufen (Grund war: " + rec.grund() + ")", null,
                        rec.scope(), ServerScope.UNBEKANNT_WERT);
            } catch (Exception e) {
                LOG.warn("Ablauf-History für {} konnte nicht geschrieben werden.", rec.spielerUuid(), e);
            }
            logWriter.schreiben(HistoryType.PARDON, ausfuehrer, rec.spielerName(),
                    "Abgelaufen. Vorheriger Grund: " + rec.grund(), rec.scope(), ServerScope.UNBEKANNT_WERT);
            einerEntfernt = true;
        }
        if (einerEntfernt) {
            benachrichtige();
        }
        return einerEntfernt;
    }
}
