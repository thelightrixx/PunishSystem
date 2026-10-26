package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.VerstossRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class VerstossService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(VerstossService.class);

    private final VerstossRepository verstossRepository;
    private final BanService banService;
    private final AutoBannService autoBannService;
    private final Konfiguration configManager;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final MessageService messageService;
    private final AuftragsPlaner taskScheduler;
    private final SpielerVerzeichnis verzeichnis;

    private final ProtectionService protectionService;

    public VerstossService(VerstossRepository verstossRepository,
                           BanService banService,
                           AutoBannService autoBannService,
                           Konfiguration configManager,
                           HistoryService historyService,
                           BroadcastService broadcastService,
                           LogWriter logWriter,
                           MessageService messageService,
                           AuftragsPlaner taskScheduler,
                           ProtectionService protectionService,
                           SpielerVerzeichnis verzeichnis) {
        this.verstossRepository = Objects.requireNonNull(verstossRepository,
                "Das VerstossRepository darf nicht null sein.");
        this.banService = Objects.requireNonNull(banService, "Der BanService darf nicht null sein.");
        this.autoBannService = Objects.requireNonNull(autoBannService, "Der AutoBannService darf nicht null sein.");
        this.configManager = Objects.requireNonNull(configManager, "Der ConfigManager darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService,
                "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.messageService = Objects.requireNonNull(messageService, "Der MessageService darf nicht null sein.");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "Der AuftragsPlaner darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
    }

    /**
     * Wird geworfen, wenn das Ziel geschützt ist oder der Schutzstatus nicht
     * sicher ermittelbar ist (fail-closed). Der Aufrufer kann beide Fälle über
     * den Typ erkennen, ohne die Fehlermeldung auswerten zu müssen.
     */
    public static final class ZielGeschuetztException extends IllegalStateException {

        public ZielGeschuetztException(String nachricht) {
            super(nachricht);
        }
    }

    public VerstossRecord hinzufuegen(UUID zielUuid, String zielName, String grund, String ausfuehrer) {
        return hinzufuegen(zielUuid, zielName, grund, ausfuehrer, null);
    }

    public VerstossRecord hinzufuegen(UUID zielUuid, String zielName, String grund, String ausfuehrer,
                                      VerstossStufe stufe) {
        return hinzufuegen(zielUuid, zielName, grund, ausfuehrer, stufe, false);
    }

    public VerstossRecord hinzufuegen(UUID zielUuid, String zielName, String grund, String ausfuehrer,
                                      VerstossStufe stufe, boolean silent) {
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.VERSTOSS)) {
                LOG.warn("verstoss-hinzufuegen für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                throw new ZielGeschuetztException("Verstoß-Add für geschütztes Ziel ist nicht erlaubt.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("Schutzcheck für Verstoß-Add {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            throw new ZielGeschuetztException("Verstoß-Add bei unsicherem Schutzstatus ist nicht erlaubt.");
        }
        VerstossStufe effektiveStufe = stufe == null ? standardStufe() : stufe;
        bereinigeVerfallene(zielUuid, zielName);

        try {

            var bestehender = banService.finden(zielUuid,
                    de.lightrixx.punish.model.ServerScope.unbekannt());
            if (bestehender.isPresent() && bestehender.get().autoBann()
                    && bestehender.get().istAbgelaufen(System.currentTimeMillis())) {
                autoBannService.behandleAutoBannAblauf(zielUuid, zielName);
            }
        } catch (Exception e) {
            LOG.warn(
                    "Auto-Bann-Vorab-Cleanup für {} fehlgeschlagen, zähle trotzdem.", zielUuid, e);
        }

        if (istAutoBannAktiv(zielUuid)) {
            throw new IllegalStateException(
                    "Verstoß-Add bei aktivem Auto-Bann ist nicht erlaubt.");
        }

        Konfiguration.LimitSnapshot snapshot = configManager.limitSnapshot();
        int maximum = snapshot.maxPunkte();
        int punkteVorher = -1;
        try {
            punkteVorher = punkteOhneCleanup(zielUuid);
        } catch (Exception e) {
            LOG.warn("Verstoß-Punkte für {} vor dem Speichern nicht lesbar.", zielUuid, e);
        }
        VerstossRecord record = verstossRepository.hinzufuegen(zielUuid, zielName, grund, ausfuehrer,
                effektiveStufe);
        int anzahl = -1;
        int punkte = -1;
        try {
            anzahl = verstossRepository.anzahl(zielUuid);
            punkte = punkteOhneCleanup(zielUuid);
        } catch (Exception e) {
            LOG.error("Verstoß-Zähler für {} nach dem Speichern nicht lesbar – "
                    + "Anzeige ohne Zähler, Auto-Bann-Prüfung mit Vorher-Stand.", zielUuid, e);
            if (punkteVorher >= 0) {
                punkte = punkteVorher + effektiveStufe.standardPunkte();
                LOG.warn("Auto-Bann-Prüfung für {} nutzt den Vorher-Stand ({} + {}).",
                        zielUuid, punkteVorher, effektiveStufe.standardPunkte());
            }
        }

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        platzhalter.put("stufe", effektiveStufe.schluessel());
        platzhalter.put("id", String.valueOf(record.nummer()));
        platzhalter.put("anzahl", anzahl < 0 ? "?" : String.valueOf(anzahl));
        platzhalter.put("punkte", punkte < 0 ? "?" : String.valueOf(punkte));
        platzhalter.put("max", String.valueOf(maximum));

        zielInformieren(zielUuid, MessageKeys.ZIEL_VERSTOSS, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.VERSTOSS_ADD, ausfuehrer, zielUuid, zielName,
                    "[" + effektiveStufe.schluessel() + "] " + grund, null);
        } catch (Exception e) {
            LOG.warn("Verstoß-Add-History für {} konnte nicht geschrieben werden (Verstoß bleibt bestehen).",
                    zielUuid, e);
        }

        broadcasten(MessageKeys.BROADCAST_VERSTOSS_ADD, platzhalter, silent);
        logWriter.schreiben(HistoryType.VERSTOSS_ADD, ausfuehrer, zielName,
                "Grund: " + grund + ", Nummer: " + record.nummer() + ", Stufe: "
                        + effektiveStufe.schluessel());

        if (punkte >= 0 && punkte >= maximum) {
            boolean autoErfolg = autoBannService.autoBann(zielUuid, zielName, ausfuehrer, punkte, maximum);
            if (!autoErfolg) {
                LOG.info(
                        "Auto-Bann für {} (Punkte {}/{}) nicht vollzogen (bereits aktiv/geschützt).",
                        zielUuid, punkte, maximum);
            }
        }
        return record;
    }

    public boolean entfernen(UUID zielUuid, String zielName, int nummer, String ausfuehrer) {
        return entfernen(zielUuid, zielName, nummer, ausfuehrer, false);
    }

    public boolean entfernen(UUID zielUuid, String zielName, int nummer, String ausfuehrer, boolean silent) {
        bereinigeVerfallene(zielUuid, zielName);
        var entfernt = verstossRepository.entfernen(zielUuid, nummer);
        if (entfernt.isEmpty()) {
            return false;
        }

        Konfiguration.LimitSnapshot snapshot = configManager.limitSnapshot();
        int anzahl = verstossRepository.anzahl(zielUuid);
        int punkte = punkteOhneCleanup(zielUuid);
        int maximum = snapshot.maxPunkte();

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("id", String.valueOf(nummer));
        platzhalter.put("stufe", entfernt.get().stufe().schluessel());
        platzhalter.put("anzahl", String.valueOf(anzahl));
        platzhalter.put("punkte", String.valueOf(punkte));
        platzhalter.put("max", String.valueOf(maximum));

        zielInformieren(zielUuid, MessageKeys.ZIEL_VERSTOSS_ENTFERNT, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.VERSTOSS_REMOVE, ausfuehrer, zielUuid, zielName,
                    "Entfernt (Nr. " + nummer + ", Stufe: " + entfernt.get().stufe().schluessel()
                            + ", Grund war: " + entfernt.get().grund() + ")", null);
        } catch (Exception e) {
            LOG.warn("Verstoß-Remove-History für {} konnte nicht geschrieben werden.", zielUuid, e);
        }

        broadcasten(MessageKeys.BROADCAST_VERSTOSS_REMOVE, platzhalter, silent);
        logWriter.schreiben(HistoryType.VERSTOSS_REMOVE, ausfuehrer, zielName,
                "Nummer: " + nummer + ", Stufe: " + entfernt.get().stufe().schluessel()
                        + ", Grund war: " + entfernt.get().grund());

        if (punkte < maximum && istAutoBannAktiv(zielUuid)) {
            autoBannService.autoPardonAufheben(zielUuid, zielName, ausfuehrer);
        }
        return true;
    }

    public boolean bearbeiten(UUID zielUuid, String zielName, int nummer, String neuerGrund, String ausfuehrer) {
        return bearbeiten(zielUuid, zielName, nummer, neuerGrund, null, ausfuehrer);
    }

    public boolean bearbeiten(UUID zielUuid, String zielName, int nummer, String neuerGrund,
                              VerstossStufe neueStufe, String ausfuehrer) {
        return bearbeiten(zielUuid, zielName, nummer, neuerGrund, neueStufe, ausfuehrer, false);
    }

    public boolean bearbeiten(UUID zielUuid, String zielName, int nummer, String neuerGrund,
                              VerstossStufe neueStufe, String ausfuehrer, boolean silent) {
        var bearbeitet = verstossRepository.bearbeiten(zielUuid, nummer, neuerGrund, neueStufe);
        if (bearbeitet.isEmpty()) {
            return false;
        }

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", neuerGrund);
        platzhalter.put("stufe", bearbeitet.get().stufe().schluessel());
        platzhalter.put("id", String.valueOf(nummer));

        zielInformieren(zielUuid, MessageKeys.ZIEL_VERSTOSS_BEARBEITET, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.VERSTOSS_EDIT, ausfuehrer, zielUuid, zielName,
                    "Bearbeitet (Nr. " + nummer + ", Stufe: " + bearbeitet.get().stufe().schluessel()
                            + ", Grund: " + neuerGrund + ")", null);
        } catch (Exception e) {
            LOG.warn("Verstoß-Edit-History für {} konnte nicht geschrieben werden (Edit bleibt wirksam).",
                    zielUuid, e);
        }

        broadcasten(MessageKeys.BROADCAST_VERSTOSS_EDIT, platzhalter, silent);
        logWriter.schreiben(HistoryType.VERSTOSS_EDIT, ausfuehrer, zielName,
                "Nummer: " + nummer + ", Stufe: " + bearbeitet.get().stufe().schluessel()
                        + ", Neuer Grund: " + neuerGrund);
        try {
            Konfiguration.LimitSnapshot snapshot = configManager.limitSnapshot();
            int maximum = snapshot.maxPunkte();
            int punkte = punkteOhneCleanup(zielUuid);
            if (punkte >= maximum) {
                boolean autoErfolg = autoBannService.autoBann(zielUuid, zielName, ausfuehrer, punkte, maximum);
                if (!autoErfolg) {
                    LOG.info("Auto-Bann nach Verstoß-Edit für {} (Punkte {}/{}) nicht vollzogen.",
                            zielUuid, punkte, maximum);
                }
            } else if (istAutoBannAktiv(zielUuid)) {
                autoBannService.autoPardonAufheben(zielUuid, zielName, ausfuehrer);
            }
        } catch (Exception e) {
            LOG.warn("Auto-Bann-Re-Evaluierung nach Verstoß-Edit für {} fehlgeschlagen.", zielUuid, e);
        }
        return true;
    }

    public boolean istAutoBannAktiv(UUID zielUuid) {

        var bann = banService.finden(zielUuid,
                de.lightrixx.punish.model.ServerScope.unbekannt());
        return bann.isPresent() && bann.get().autoBann() && bann.get().istAktiv(System.currentTimeMillis());
    }

    public void autoBannErneutPruefen(UUID zielUuid, String zielName, String ausfuehrer) {
        if (zielUuid == null) {
            return;
        }
        try {
            Konfiguration.LimitSnapshot snapshot = configManager.limitSnapshot();
            int maximum = snapshot.maxPunkte();
            int punkte = punkteOhneCleanup(zielUuid);
            if (punkte >= maximum) {
                boolean autoErfolg = autoBannService.autoBann(zielUuid,
                        zielName == null ? "" : zielName, ausfuehrer, punkte, maximum);
                if (!autoErfolg) {
                    LOG.info("Auto-Bann-Re-Evaluierung für {} (Punkte {}/{}) nicht vollzogen.",
                            zielUuid, punkte, maximum);
                }
            }
        } catch (Exception e) {
            LOG.warn("Auto-Bann-Re-Evaluierung für {} fehlgeschlagen.", zielUuid, e);
        }
    }

    public int autoBannErneutPruefenFuerAlle(String ausfuehrer) {
        int ausgeloest = 0;
        java.util.Map<UUID, java.util.List<VerstossRecord>> stand;
        try {
            stand = verstossRepository.alle();
        } catch (Exception e) {
            LOG.warn("Auto-Bann-Re-Evaluierung für alle fehlgeschlagen.", e);
            return 0;
        }
        int maximum;
        try {
            maximum = configManager.limitSnapshot().maxPunkte();
        } catch (Exception e) {
            LOG.warn("Auto-Bann-Re-Evaluierung für alle fehlgeschlagen (Limits unlesbar).", e);
            return 0;
        }
        for (var eintrag : stand.entrySet()) {
            if (eintrag == null || eintrag.getKey() == null) {
                continue;
            }
            UUID zielUuid = eintrag.getKey();
            try {
                if (punkteOhneCleanup(zielUuid) < maximum) {
                    continue;
                }
                String zielName = "";
                try {
                    for (VerstossRecord record : eintrag.getValue()) {
                        if (record != null && record.spielerName() != null
                                && !record.spielerName().isBlank()) {
                            zielName = record.spielerName();
                            break;
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("Verstoß-Name für Re-Evaluierung unlesbar.", e);
                }
                if (autoBannService.autoBann(zielUuid, zielName, ausfuehrer,
                        punkteOhneCleanup(zielUuid), maximum)) {
                    ausgeloest++;
                }
            } catch (Exception e) {
                LOG.debug("Auto-Bann-Re-Evaluierung für {} fehlgeschlagen.", zielUuid, e);
            }
        }
        return ausgeloest;
    }

    public int leeren(UUID zielUuid, String zielName, String ausfuehrer) {
        return leeren(zielUuid, zielName, ausfuehrer, false);
    }

    public int leeren(UUID zielUuid, String zielName, String ausfuehrer, boolean silent) {
        List<VerstossRecord> entfernt = verstossRepository.zuruecksetzen(zielUuid);
        if (entfernt.isEmpty()) {
            return 0;
        }

        int maximum = configManager.limitSnapshot().maxPunkte();

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("anzahl", String.valueOf(entfernt.size()));
        platzhalter.put("max", String.valueOf(maximum));

        zielInformieren(zielUuid, MessageKeys.ZIEL_VERSTOSS_CLEAR, platzhalter);

        try {
            historyService.hinzufuegen(HistoryType.RESET, ausfuehrer, zielUuid, zielName,
                    "Alle Verstöße gelöscht (" + entfernt.size() + ")", null);
        } catch (Exception e) {
            LOG.warn("Verstoß-Reset-History für {} konnte nicht geschrieben werden.", zielUuid, e);
        }

        broadcasten(MessageKeys.BROADCAST_VERSTOSS_CLEAR, platzhalter, silent);
        logWriter.schreiben(HistoryType.RESET, ausfuehrer, zielName,
                "Alle Verstöße gelöscht: " + entfernt.size());

        if (istAutoBannAktiv(zielUuid)) {
            autoBannService.autoPardonAufheben(zielUuid, zielName, ausfuehrer);
        }
        return entfernt.size();
    }

    public List<VerstossRecord> findeVerstoesse(UUID zielUuid) {
        bereinigeVerfallene(zielUuid, null);
        return verstossRepository.finden(zielUuid);
    }

    public int anzahl(UUID zielUuid) {
        bereinigeVerfallene(zielUuid, null);
        return verstossRepository.anzahl(zielUuid);
    }

    public int punkte(UUID zielUuid) {
        bereinigeVerfallene(zielUuid, null);
        return punkteOhneCleanup(zielUuid);
    }

    private int punkteOhneCleanup(UUID zielUuid) {
        int summe = 0;
        for (VerstossRecord record : verstossRepository.finden(zielUuid)) {
            if (record == null || record.stufe() == null) {
                continue;
            }
            try {
                summe = Math.addExact(summe, Math.max(0, configManager.verstossStufenPunkte(record.stufe())));
            } catch (Exception e) {
                LOG.warn("Punkte für Verstoß #{} konnten nicht bestimmt werden, zähle 0.",
                        record.nummer(), e);
            }
        }
        return summe;
    }

    private VerstossStufe standardStufe() {
        try {
            VerstossStufe stufe = configManager.verstossStandardStufe();
            return stufe == null ? VerstossStufe.standard() : stufe;
        } catch (Exception e) {
            LOG.warn("Standard-Stufe konnte nicht gelesen werden, nutze medium.", e);
            return VerstossStufe.standard();
        }
    }

    private void broadcasten(String typ, Map<String, String> platzhalter, boolean silent) {
        if (broadcastService == null) {
            return;
        }
        try {
            broadcastService.senden(typ, platzhalter, silent);
        } catch (Exception e) {
            LOG.warn("Verstoß-Broadcast '{}' fehlgeschlagen (Verstoß bleibt wirksam).", typ, e);
        }
    }

    private long dauerFuer(VerstossRecord record) {
        if (record == null || record.stufe() == null) {
            return 0L;
        }
        try {
            long millis = configManager.verstossStufenDauerMillis(record.stufe());
            return Math.max(0L, millis);
        } catch (Exception e) {
            LOG.warn("Verfallsdauer für Stufe {} konnte nicht gelesen werden, Verstoß #{} verfällt nicht.",
                    record.stufe(), record.nummer(), e);
            return 0L;
        }
    }

    public int bereinigeVerfallene(UUID zielUuid) {
        return bereinigeVerfallene(zielUuid, null);
    }

    public int bereinigeVerfallene(UUID zielUuid, String zielName) {
        Objects.requireNonNull(zielUuid, "Die Spieler-UUID darf nicht null sein.");
        long jetzt = System.currentTimeMillis();
        List<VerstossRecord> verfallen;
        try {
            verfallen = verstossRepository.entferneVerfallene(zielUuid, this::dauerFuer, jetzt);
        } catch (Exception e) {
            LOG.warn("Verfall-Cleanup für {} fehlgeschlagen, zähle mit Altbestand.", zielUuid, e);
            return 0;
        }
        if (verfallen.isEmpty()) {
            return 0;
        }
        String effektiverName = zielName;
        if (effektiverName == null || effektiverName.isBlank()) {
            effektiverName = verfallen.getFirst().spielerName();
        }
        LOG.info("Verfall: {} verfallene Verstöße von {} ({}) automatisch entfernt.",
                verfallen.size(), effektiverName, zielUuid);
        for (VerstossRecord record : verfallen) {
            try {
                historyService.hinzufuegen(HistoryType.VERSTOSS_REMOVE, "System", zielUuid,
                        record.spielerName(),
                        "Verfallen (Nr. " + record.nummer() + ", Stufe: " + record.stufe().schluessel()
                                + ", Grund war: " + record.grund() + ")", null);
            } catch (Exception e) {
                LOG.warn("Verfall-History für {} (#{} ) konnte nicht geschrieben werden.",
                        zielUuid, record.nummer(), e);
            }
            try {
                logWriter.schreiben(HistoryType.VERSTOSS_REMOVE, "System", record.spielerName(),
                        "Verstoß #" + record.nummer() + " verfallen (Stufe: "
                                + record.stufe().schluessel() + ", Grund war: " + record.grund() + ")");
            } catch (Exception e) {
                LOG.warn("Verfall-Log für {} (#{}) konnte nicht geschrieben werden.",
                        zielUuid, record.nummer(), e);
            }
        }
        return verfallen.size();
    }

    public int bereinigeAlleVerfallenen() {
        java.util.Set<UUID> spieler;
        try {
            spieler = verstossRepository.alle().keySet();
        } catch (Exception e) {
            LOG.warn("Globaler Verfall-Cleanup fehlgeschlagen (Spielerliste).", e);
            return 0;
        }
        int gesamt = 0;
        for (UUID uuid : List.copyOf(spieler)) {
            if (uuid == null) {
                continue;
            }
            try {
                gesamt += bereinigeVerfallene(uuid, null);
            } catch (Exception e) {
                LOG.warn("Globaler Verfall-Cleanup für {} fehlgeschlagen.", uuid, e);
            }
        }
        return gesamt;
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
