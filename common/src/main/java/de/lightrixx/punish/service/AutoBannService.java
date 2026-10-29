package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.GeplanteAufgabe;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.util.TimeParser;
import de.lightrixx.punish.util.ZeitFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AutoBannService {

    private static final Logger LOG = LoggerFactory.getLogger(AutoBannService.class);
    private static final long STANDARD_AUTO_BANN_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final String SYSTEM_AUSFUEHRER = "System";

    private final AuftragsPlaner taskScheduler;
    private final BanRepository banRepository;
    private final Konfiguration configManager;
    private final TimeParser timeParser;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final ProtectionService protectionService;
    private final VerstossRepository verstossRepository;
    private final MessageService messageService;
    private final BanKickHelper kickHelper;
    private final SpielerVerzeichnis verzeichnis;
    private final ConcurrentHashMap<UUID, GeplanteAufgabe> autoBannTasks = new ConcurrentHashMap<>();

    public AutoBannService(AuftragsPlaner taskScheduler,
                           BanRepository banRepository,
                           Konfiguration configManager,
                           TimeParser timeParser,
                           HistoryService historyService,
                           BroadcastService broadcastService,
                           LogWriter logWriter,
                           ProtectionService protectionService,
                           VerstossRepository verstossRepository,
                           MessageService messageService,
                           BanKickHelper kickHelper,
                           SpielerVerzeichnis verzeichnis) {
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "Der AuftragsPlaner darf nicht null sein.");
        this.banRepository = Objects.requireNonNull(banRepository, "Das BanRepository darf nicht null sein.");
        this.configManager = Objects.requireNonNull(configManager, "Der ConfigManager darf nicht null sein.");
        this.timeParser = Objects.requireNonNull(timeParser, "Der TimeParser darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.verstossRepository = Objects.requireNonNull(verstossRepository, "Das VerstossRepository darf nicht null sein.");
        this.messageService = Objects.requireNonNull(messageService, "Der MessageService darf nicht null sein.");
        this.kickHelper = Objects.requireNonNull(kickHelper, "Der BanKickHelper darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    public boolean autoBann(UUID zielUuid, String zielName, String ausfuehrer,
                            int echtePunkte, int echtesMaximum) {
        try {
            if (protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.BAN)) {
                LOG.warn("autoBann für {} abgewiesen: Ziel ist (inzwischen) geschützt.", zielUuid);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für Auto-Bann {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return false;
        }

        Konfiguration.LimitSnapshot limits = configManager.limitSnapshot();
        String konfigurierteDauer = limits.autoBannDauer();
        ParseResult parseResult = timeParser.parsen(konfigurierteDauer);
        long dauerMillis = switch (parseResult) {
            case ParseResult.Geltzeit(long millis) -> millis;
            case ParseResult.Permanent() -> {
                LOG.warn("Auto-Bann-Dauer '{}' ist 'perm'/'permanent' - nicht sinnvoll für Auto-Bann, nutze Standard {}",
                        konfigurierteDauer, ZeitFormat.formatRestzeit(STANDARD_AUTO_BANN_MILLIS));
                yield STANDARD_AUTO_BANN_MILLIS;
            }
            case ParseResult.Ungueltig() -> {
                LOG.warn("Ungültige Auto-Bann-Dauer '{}' in der Konfiguration, nutze Standard {}",
                        konfigurierteDauer, ZeitFormat.formatRestzeit(STANDARD_AUTO_BANN_MILLIS));
                yield STANDARD_AUTO_BANN_MILLIS;
            }
        };

        long jetzt = System.currentTimeMillis();
        long ablaufAm;
        try {
            ablaufAm = Math.addExact(jetzt, dauerMillis);
        } catch (ArithmeticException e) {
            LOG.warn("Auto-Bann-Dauer führte zu Überlauf (jetzt={}, dauer={}), nutze Standard", jetzt, dauerMillis, e);
            dauerMillis = STANDARD_AUTO_BANN_MILLIS;
            try {
                ablaufAm = Math.addExact(jetzt, dauerMillis);
            } catch (ArithmeticException e2) {
                LOG.error("Auch Standard-Auto-Bann-Dauer führt zu Überlauf – breche ab.", e2);
                return false;
            }
        }

        String grund = configManager.standardGrund(MessageKeys.DEFAULT_AUTO_BANN_GRUND);
        BanRecord record;
        try {
            record = new BanRecord(zielUuid, zielName, grund, ausfuehrer, jetzt, ablaufAm, true);
        } catch (Exception e) {
            LOG.warn("Auto-Bann für {} ({}) konnte nicht erzeugt werden.", zielUuid, zielName, e);
            return false;
        }

        boolean irgendwoAktiv = banRepository.alleFuer(zielUuid).stream()
                .anyMatch(b -> b != null && b.istAktiv(jetzt));
        if (irgendwoAktiv) {
            LOG.info("autoBann für {} verworfen: bereits aktiv gebannt (kein Override).", zielUuid);
            return false;
        }
        if (!banRepository.bannenWennFrei(record)) {
            LOG.info("autoBann für {} verworfen: bereits aktiv gebannt (kein Override).", zielUuid);
            return false;
        }

        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                zielName, ausfuehrer, grund,
                ZeitFormat.formatRestzeit(dauerMillis),
                ZeitFormat.formatRestzeit(dauerMillis),
                ZeitFormat.formatZeitpunkt(ablaufAm), null);
        platzhalter.put("anzahl", String.valueOf(echtePunkte));
        platzhalter.put("punkte", String.valueOf(echtePunkte));
        platzhalter.put("max", String.valueOf(echtesMaximum));

        try {
            historyService.hinzufuegen(HistoryType.AUTO_BANN, ausfuehrer, zielUuid, zielName, grund, ablaufAm);
        } catch (Exception e) {
            LOG.warn("AutoBann-History für {} konnte nicht geschrieben werden (Ban bleibt aktiv).", zielUuid, e);
        }
        kickHelper.spielerKicken(zielUuid, MessageKeys.SCREEN_BANN_AUTO, platzhalter);
        broadcastService.senden(MessageKeys.BROADCAST_AUTO_BANN, platzhalter);
        logWriter.schreiben(HistoryType.AUTO_BANN, ausfuehrer, zielName,
                "Grund: " + grund + ", Dauer: " + ZeitFormat.formatRestzeit(dauerMillis));
        planeAutoBannAblauf(record);
        return true;
    }

    public boolean autoPardonAufheben(UUID zielUuid, String zielName, String ausfuehrer) {

        Optional<BanRecord> entfernt = banRepository.entbannenWenn(
                zielUuid, ServerScope.unbekannt(),
                bann -> bann.autoBann() && bann.istAktiv(System.currentTimeMillis()));
        if (entfernt.isEmpty()) {
            return false;
        }

        abbrechenAutoBannTask(zielUuid);

        String effektiverName = zielName != null ? zielName : entfernt.get().spielerName();
        Map<String, String> platzhalter = BanKickHelper.platzhalter(effektiverName, ausfuehrer, "", "", "", "", null);

        try {
            historyService.hinzufuegen(HistoryType.AUTO_PARDON, ausfuehrer, zielUuid, effektiverName,
                    "Aufgehoben (Auto-Bann, Grund war: " + entfernt.get().grund() + ")", null);
        } catch (Exception e) {
            LOG.warn("Auto-Pardon-History für {} konnte nicht geschrieben werden (Aufhebung bleibt wirksam).",
                    zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_AUTO_PARDON, platzhalter);
        logWriter.schreiben(HistoryType.AUTO_PARDON, ausfuehrer, effektiverName,
                "Auto-Bann durch Verstoß-Entfernung aufgehoben");
        return true;
    }

    public void planeAlleAktivenAutoBanns() {
        long jetzt = System.currentTimeMillis();
        for (BanRecord bann : banRepository.alle()) {
            if (!bann.autoBann() || bann.ablaufAm() == null) {
                continue;
            }
            if (bann.istAktiv(jetzt)) {
                planeAutoBannAblauf(bann);
            } else {

                behandleAutoBannAblauf(bann.spielerUuid(), bann.spielerName());
            }
        }
    }

    public void rescheduleAutoBanns() {
        for (UUID uuid : List.copyOf(autoBannTasks.keySet())) {
            abbrechenAutoBannTask(uuid);
        }
        planeAlleAktivenAutoBanns();
    }

    public void shutdown() {
        for (UUID uuid : List.copyOf(autoBannTasks.keySet())) {
            abbrechenAutoBannTask(uuid);
        }
        autoBannTasks.clear();
    }

    public int bereinigeAbgelaufeneMitZeremonie(long jetzt) {
        List<BanRecord> abgelaufeneAutos;
        try {
            abgelaufeneAutos = banRepository.alle().stream()
                    .filter(b -> b != null && b.autoBann() && b.istAbgelaufen(jetzt))
                    .toList();
        } catch (Exception e) {
            LOG.warn("Auto-Bann-Scan für Cleanup fehlgeschlagen, falle auf stillen Cleanup zurück.", e);
            abgelaufeneAutos = List.of();
        }
        int zeremonie = 0;
        for (BanRecord b : abgelaufeneAutos) {
            try {

                int vor = zeremonie;
                behandleAutoBannAblauf(b.spielerUuid(), b.spielerName());

                if (banRepository.finden(b.spielerUuid(), b.scope()).isEmpty()) {
                    zeremonie = vor + 1;
                } else {
                    zeremonie = vor;
                }
            } catch (Exception e) {
                LOG.warn("Auto-Bann-Cleanup für {} fehlgeschlagen.", b.spielerUuid(), e);
            }
        }
        int still = 0;
        try {
            still = banRepository.entferneAbgelaufene(jetzt);
        } catch (Exception e) {
            LOG.warn("Stiller Ban-Cleanup fehlgeschlagen.", e);
        }
        for (UUID uuid : List.copyOf(autoBannTasks.keySet())) {
            if (banRepository.alleFuer(uuid).stream().noneMatch(b -> b != null && b.autoBann())) {
                abbrechenAutoBannTask(uuid);
            }
        }
        return zeremonie + still;
    }

    public void behandleAutoBannAblauf(UUID zielUuid, String zielName) {
        behandleAutoBannAblaufIntern(zielUuid, zielName, false);
    }

    public void behandleAutoBannAblaufNachLogin(UUID zielUuid, String zielName) {
        behandleAutoBannAblaufIntern(zielUuid, zielName, true);
    }

    public void abbrechen(UUID zielUuid) {
        abbrechenAutoBannTask(zielUuid);
    }

    private void behandleAutoBannAblaufIntern(UUID zielUuid, String zielName, boolean loginKontext) {
        java.util.Set<Integer> snapshotNummern;
        try {
            snapshotNummern = new java.util.HashSet<>();
            for (de.lightrixx.punish.model.VerstossRecord record : verstossRepository.finden(zielUuid)) {
                if (record != null) {
                    snapshotNummern.add(record.nummer());
                }
            }
        } catch (Exception e) {
            snapshotNummern = null;
            LOG.error("Verstoß-Snapshot für Auto-Bann-Ablauf {} fehlgeschlagen – "
                    + "Verstoß-Reset wird abgebrochen (kein vollständiger Lösch-Fallback).",
                    zielUuid, e);
        }
        abbrechenAutoBannTask(zielUuid);
        long jetzt = System.currentTimeMillis();
        Optional<BanRecord> entfernt = banRepository.entbannenWenn(
                zielUuid, ServerScope.unbekannt(),
                bann -> bann.autoBann() && bann.istAbgelaufen(jetzt));
        if (entfernt.isEmpty()) {
            return;
        }

        boolean resetOk = true;
        java.util.List<de.lightrixx.punish.model.VerstossRecord> zurueckgesetzteVerstoesse =
                java.util.List.of();
        try {
            if (snapshotNummern == null) {
                resetOk = false;
                LOG.error("Verstoß-Reset nach Auto-Bann-Ablauf für {} abgebrochen "
                        + "(Snapshot nicht lesbar – bestehende Verstöße bleiben erhalten).", zielUuid);
            } else {
                zurueckgesetzteVerstoesse = verstossRepository.zuruecksetzenNur(zielUuid, snapshotNummern);
            }
        } catch (Exception e) {
            resetOk = false;
            LOG.error("Verstoß-Reset nach Auto-Bann-Ablauf für {} fehlgeschlagen "
                    + "(Re-Bann-Loop-Risiko – manuell /verstoss clear prüfen!).", zielUuid, e);
        }

        Map<String, String> platzhalter = BanKickHelper.platzhalter(
                zielName, SYSTEM_AUSFUEHRER, "", "", "", "", null);

        try {
            historyService.hinzufuegen(HistoryType.AUTO_BANN_EXPIRED, SYSTEM_AUSFUEHRER,
                    zielUuid, zielName,
                    resetOk ? "" : "WARNUNG: Verstoß-Reset fehlgeschlagen (manuell prüfen!)", null);
        } catch (Exception e) {
            LOG.warn("Auto-Bann-Ablauf-History für {} konnte nicht geschrieben werden.", zielUuid, e);
        }

        if (!zurueckgesetzteVerstoesse.isEmpty()) {
            try {
                historyService.hinzufuegen(HistoryType.RESET, SYSTEM_AUSFUEHRER,
                        zielUuid, zielName,
                        configManager.standardGrund(MessageKeys.DEFAULT_AUTO_BANN_RESET_GRUND), null);
            } catch (Exception e) {
                LOG.warn("Auto-Bann-Reset-History für {} konnte nicht geschrieben werden.", zielUuid, e);
            }
        }

        broadcastService.senden(MessageKeys.BROADCAST_AUTO_BANN_ABGELAUFEN, platzhalter);
        logWriter.schreiben(HistoryType.AUTO_BANN_EXPIRED, SYSTEM_AUSFUEHRER, zielName,
                resetOk ? "Auto-Bann abgelaufen, Verstöße zurückgesetzt"
                        : "Auto-Bann abgelaufen, VERSTOSS-RESET FEHLGESCHLAGEN (manuell prüfen!)");

        long infoDelayTicks = loginKontext ? 40L : 0L;
        planeInfo(zielUuid, infoDelayTicks);
    }

    private void planeAutoBannAblauf(BanRecord bann) {
        if (!bann.autoBann() || bann.ablaufAm() == null) {
            return;
        }
        long jetzt = System.currentTimeMillis();
        long delayMillis = Math.max(1L, bann.ablaufAm() - jetzt + 1L);
        long delayTicks = Math.max(1L, (delayMillis + 49L) / 50L);
        planeAutoBannAblauf(bann, delayTicks);
    }

    private void planeAutoBannAblauf(BanRecord bann, long delayTicks) {
        UUID uuid = bann.spielerUuid();
        abbrechenAutoBannTask(uuid);
        if (taskScheduler.istGeschlossen()) {
            return;
        }
        GeplanteAufgabe task;
        try {
            task = taskScheduler.spaeter(
                    () -> {
                        autoBannTasks.remove(uuid);
                        behandleAutoBannAblauf(uuid, bann.spielerName());
                    },
                    delayTicks);
        } catch (IllegalStateException e) {
            LOG.debug("AutoBann-Ablauf nach Shutdown verworfen für {}", uuid);
            return;
        }
        autoBannTasks.put(uuid, task);
    }

    private void abbrechenAutoBannTask(UUID uuid) {
        GeplanteAufgabe bestehend = autoBannTasks.remove(uuid);
        if (bestehend != null) {
            bestehend.abbrechen();
        }
    }

    private void planeInfo(UUID uuid, long delayTicks) {

        if (taskScheduler.istGeschlossen()) {
            return;
        }
        try {
            if (delayTicks <= 0L) {
                taskScheduler.aufgabe(() -> sendeAutoBannAbgelaufenInfo(uuid));
            } else {
                taskScheduler.spaeter(() -> sendeAutoBannAbgelaufenInfo(uuid), delayTicks);
            }
        } catch (IllegalStateException e) {
            LOG.debug("AutoBann-Info nach Shutdown verworfen für {}", uuid);
        }
    }

    private void sendeAutoBannAbgelaufenInfo(UUID uuid) {
        messageService.anSpieler(uuid, MessageKeys.AUTO_BANN_ABGELAUFEN_INFO, Map.of(), verzeichnis);
    }
}
