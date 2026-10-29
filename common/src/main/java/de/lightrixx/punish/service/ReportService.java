package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.ReportRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportService {

    public record Ergebnis(boolean erfolg, ReportRecord report, long cooldownRestSekunden,
                           String fehler) {
    }

    public static final class ClaimKonflikt extends RuntimeException {
        private final String aktuellerBearbeiter;

        public ClaimKonflikt(String aktuellerBearbeiter) {
            super("Report bereits übernommen von " + aktuellerBearbeiter);
            this.aktuellerBearbeiter = aktuellerBearbeiter == null ? "" : aktuellerBearbeiter;
        }

        public String aktuellerBearbeiter() {
            return aktuellerBearbeiter;
        }
    }

    public static final class BereitsGeschlossen extends RuntimeException {
        private final ReportStatus status;

        public BereitsGeschlossen(ReportStatus status) {
            super("Report bereits geschlossen (" + status + ")");
            this.status = status;
        }

        public ReportStatus status() {
            return status;
        }
    }

    private static boolean istAbgeschlossen(ReportStatus status) {
        return status == ReportStatus.ABGELEHNT || status == ReportStatus.ERLEDIGT;
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ReportService.class);

    private final ReportRepository reportRepository;
    private final Konfiguration config;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final MessageService messageService;
    private final AuftragsPlaner taskScheduler;
    private final SpielerVerzeichnis verzeichnis;

    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public ReportService(ReportRepository reportRepository, Konfiguration config) {
        this(reportRepository, config, null, null, null, null, null, null);
    }

    public ReportService(ReportRepository reportRepository,
                         Konfiguration config,
                         HistoryService historyService,
                         BroadcastService broadcastService,
                         LogWriter logWriter,
                         MessageService messageService,
                         AuftragsPlaner taskScheduler,
                         SpielerVerzeichnis verzeichnis) {
        this.reportRepository = Objects.requireNonNull(reportRepository,
                "Das ReportRepository darf nicht null sein.");
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.historyService = historyService;
        this.broadcastService = broadcastService;
        this.logWriter = logWriter;
        this.messageService = messageService;
        this.taskScheduler = taskScheduler;
        this.verzeichnis = verzeichnis;
    }

    public long cooldownSekunden() {
        try {
            return Math.max(0L, config.reportCooldownSekunden());
        } catch (Exception e) {
            return 60L;
        }
    }

    public long cooldownRest(UUID reporterUuid) {
        if (reporterUuid == null) {
            return 0L;
        }
        Long letzter = cooldowns.get(reporterUuid);
        if (letzter == null) {
            return 0L;
        }
        long jetzt = System.currentTimeMillis();
        long vergangen = (jetzt - letzter) / 1000L;
        long cooldown = cooldownSekunden();
        if (vergangen > cooldown + 3600L) {
            cooldowns.remove(reporterUuid, letzter);
            return 0L;
        }
        return Math.max(0L, cooldown - vergangen);
    }

    private void evictAbgelaufeneCooldowns() {
        try {
            long jetzt = System.currentTimeMillis();
            long cooldown = cooldownSekunden();
            cooldowns.entrySet().removeIf(eintrag -> {
                if (eintrag == null || eintrag.getValue() == null) {
                    return true;
                }
                return (jetzt - eintrag.getValue()) / 1000L > cooldown + 3600L;
            });
            if (cooldowns.size() > 5000) {
                int ueberhang = cooldowns.size() - 4000;
                var nachAlter = new java.util.ArrayList<>(cooldowns.entrySet());
                nachAlter.sort(java.util.Map.Entry.comparingByValue(
                        java.util.Comparator.nullsFirst(Long::compareTo)));
                for (int i = 0; i < ueberhang && i < nachAlter.size(); i++) {
                    var eintrag = nachAlter.get(i);
                    if (eintrag != null && eintrag.getKey() != null) {
                        cooldowns.remove(eintrag.getKey(), eintrag.getValue());
                    }
                }
                LOG.warn("Report-Cooldowns teilgeräumt (Größen-Cap, {} älteste entfernt).", ueberhang);
            }
        } catch (Exception e) {
            LOG.trace("Cooldown-Eviction übersprungen.", e);
        }
    }

    public Ergebnis melden(UUID zielUuid, String zielName, UUID reporterUuid,
                           String reporterName, String grund, String server) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        Objects.requireNonNull(reporterName, "Der Reportername darf nicht null sein.");
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        String sauber = grund.trim().replaceAll("\\s+", " ");
        if (sauber.isBlank()) {
            return new Ergebnis(false, null, 0L, MessageKeys.KEIN_GRUND);
        }
        boolean gekuerzt = sauber.codePointCount(0, sauber.length()) > 256;
        if (gekuerzt) {
            sauber = sauber.substring(0, sauber.offsetByCodePoints(0, 256));
        }
        if (reporterUuid != null && reporterUuid.equals(zielUuid)) {
            return new Ergebnis(false, null, 0L, MessageKeys.REPORT_SELBST);
        }
        ReportRecord record;
        de.lightrixx.punish.storage.StoreLock.LOCK.writeLock().lock();
        try {
            if (reporterUuid != null) {
                long rest = cooldownRest(reporterUuid);
                if (rest > 0) {
                    return new Ergebnis(false, null, rest, MessageKeys.REPORT_COOLDOWN);
                }
            }
            int maxOffen;
            try {
                maxOffen = Math.max(1, config.reportMaxOffenProReporter());
            } catch (Exception e) {
                maxOffen = 3;
            }
            if (reporterUuid != null && reportRepository.zaehleOffeneVonReporter(reporterUuid) >= maxOffen) {
                return new Ergebnis(false, null, 0L, MessageKeys.REPORT_LIMIT);
            }
            long duplikatMinuten;
            try {
                duplikatMinuten = Math.max(0L, config.reportDuplikatSperreMinuten());
            } catch (Exception e) {
                duplikatMinuten = 10L;
            }
            if (reporterUuid != null && duplikatMinuten > 0) {
                long seit = System.currentTimeMillis() - duplikatMinuten * 60_000L;
                Optional<ReportRecord> duplikat = reportRepository.findeDuplikat(reporterUuid, zielUuid, seit);
                if (duplikat.isPresent()) {
                    return new Ergebnis(false, duplikat.get(), 0L, MessageKeys.REPORT_DUPLIKAT);
                }
            }
            try {
                record = reportRepository.hinzufuegen(zielUuid, zielName, reporterUuid,
                        reporterName, sauber, server);
            } catch (IllegalArgumentException e) {
                LOG.warn("Report für {} abgewiesen: {}", zielUuid, e.getMessage());
                return new Ergebnis(false, null, 0L, MessageKeys.KEIN_GRUND);
            } catch (IllegalStateException e) {
                LOG.warn("Report für {} abgewiesen (Limit): {}", zielUuid, e.getMessage());
                return new Ergebnis(false, null, 0L, MessageKeys.REPORT_LIMIT);
            }
            if (reporterUuid != null) {
                cooldowns.put(reporterUuid, System.currentTimeMillis());
                if (cooldowns.size() % 100 == 0) {
                    evictAbgelaufeneCooldowns();
                }
            }
        } finally {
            de.lightrixx.punish.storage.StoreLock.LOCK.writeLock().unlock();
        }
        if (gekuerzt) {
            sendeKuerzungsHinweis(reporterUuid);
        }
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("reporter", reporterName);
        platzhalter.put("ausfuehrer", reporterName);
        platzhalter.put("grund", sauber);
        platzhalter.put("id", String.valueOf(record.id()));
        platzhalter.put("server", record.server());
        if (historyService != null) {
            try {
                historyService.hinzufuegen(HistoryType.REPORT_ADD, reporterName,
                        zielUuid, zielName, sauber, null);
            } catch (Exception e) {
                LOG.warn("Report-History für {} konnte nicht geschrieben werden (Report bleibt bestehen).",
                        zielUuid, e);
            }
        }
        broadcasten(MessageKeys.BROADCAST_REPORT_NEU, platzhalter);
        if (logWriter != null) {
            logWriter.schreiben(HistoryType.REPORT_ADD, reporterName, zielName,
                    "Grund: " + sauber + ", ID: " + record.id());
        }
        return new Ergebnis(true, record, 0L, null);
    }

    public Optional<ReportRecord> schliessen(long id, ReportStatus status,
                                             String bearbeiter, String grund) {
        return schliessen(id, status, bearbeiter, grund, false);
    }

    public Optional<ReportRecord> schliessen(long id, ReportStatus status,
                                             String bearbeiter, String grund, boolean silent) {
        Objects.requireNonNull(status, "Der Status darf nicht null sein.");
        Objects.requireNonNull(bearbeiter, "Der Bearbeiter darf nicht null sein.");
        String saubererGrund = grund == null ? "" : grund.trim();
        if (status == ReportStatus.ANGENOMMEN) {
            Optional<ReportRecord> geschlossen = reportRepository.statusSetzenWenn(id, status,
                    bearbeiter, saubererGrund, alt -> istClaimFrei(alt, bearbeiter));
            if (geschlossen.isPresent()) {
                nachSchliessen(geschlossen.get(), id, bearbeiter, grund, silent);
                return geschlossen;
            }
            Optional<ReportRecord> aktuell = reportRepository.findenNachId(id);
            if (aktuell.isPresent() && aktuell.get().status() == ReportStatus.ANGENOMMEN) {
                String halter = aktuell.get().bearbeiter();
                if (halter != null && !halter.isBlank() && !halter.equals(bearbeiter)) {
                    throw new ClaimKonflikt(halter);
                }
            }
            return Optional.empty();
        }
        Optional<ReportRecord> aktuell = reportRepository.findenNachId(id);
        if (aktuell.isPresent() && istAbgeschlossen(aktuell.get().status())
                && aktuell.get().status() != status) {
            throw new BereitsGeschlossen(aktuell.get().status());
        }
        Optional<ReportRecord> geschlossen = reportRepository.statusSetzen(id, status, bearbeiter,
                saubererGrund);
        geschlossen.ifPresent(record -> nachSchliessen(record, id, bearbeiter, grund, silent));
        return geschlossen;
    }

    private static boolean istClaimFrei(ReportRecord alt, String bearbeiter) {
        if (alt == null || alt.status() != ReportStatus.ANGENOMMEN) {
            return true;
        }
        String halter = alt.bearbeiter();
        return halter == null || halter.isBlank() || halter.equals(bearbeiter);
    }

    private void nachSchliessen(ReportRecord record, long id, String bearbeiter, String grund,
                                boolean silent) {
            Map<String, String> platzhalter = new HashMap<>();
            platzhalter.put("spieler", record.zielName());
            platzhalter.put("reporter", record.reporterName());
            platzhalter.put("ausfuehrer", bearbeiter);
            platzhalter.put("bearbeiter", bearbeiter);
            platzhalter.put("grund", grund == null ? "" : grund.trim());
            platzhalter.put("id", String.valueOf(record.id()));
            platzhalter.put("status", record.status().name());
            platzhalter.put("server", record.server());
            if (historyService != null) {
                try {
                    historyService.hinzufuegen(HistoryType.REPORT_CLOSE, bearbeiter,
                            record.zielUuid(), record.zielName(),
                            record.status().name() + (grund == null || grund.isBlank()
                                    ? "" : ": " + grund.trim()), null);
                } catch (Exception e) {
                    LOG.warn("Report-Close-History für {} konnte nicht geschrieben werden.", id, e);
                }
            }
            broadcasten(MessageKeys.BROADCAST_REPORT_CLOSE, platzhalter, silent);
            if (logWriter != null) {
                logWriter.schreiben(HistoryType.REPORT_CLOSE, bearbeiter, record.zielName(),
                        "ID: " + id + ", Status: " + record.status().name());
            }
            reporterInformieren(record, bearbeiter, grund);
    }

    public Optional<ReportRecord> entfernen(long id, String bearbeiter) {
        Optional<ReportRecord> entfernt = reportRepository.entfernen(id);
        entfernt.ifPresent(record -> {
            if (historyService != null) {
                try {
                    historyService.hinzufuegen(HistoryType.REPORT_CLOSE,
                            bearbeiter == null ? "Konsole" : bearbeiter,
                            record.zielUuid(), record.zielName(),
                            "Report gelöscht, ID: " + id, null);
                } catch (Exception e) {
                    LOG.warn("Report-Delete-History für {} konnte nicht geschrieben werden.", id, e);
                }
            }
            if (logWriter != null) {
                logWriter.schreiben(HistoryType.REPORT_CLOSE,
                        bearbeiter == null ? "Konsole" : bearbeiter,
                        record.zielName(), "Report gelöscht, ID: " + id);
            }
        });
        return entfernt;
    }

    public Optional<ReportRecord> findenNachId(long id) {
        return reportRepository.findenNachId(id);
    }

    public List<ReportRecord> offene() {
        return reportRepository.offene();
    }

    public List<ReportRecord> alle() {
        return reportRepository.alle();
    }

    public int anzahlOffen() {
        return reportRepository.anzahlOffen();
    }

    private void sendeKuerzungsHinweis(UUID reporterUuid) {
        if (reporterUuid == null || messageService == null || verzeichnis == null) {
            return;
        }
        try {
            Map<String, String> platzhalter = Map.of("max", "256");
            Runnable senden = () -> {
                try {
                    messageService.anSpieler(reporterUuid, MessageKeys.GRUND_GEKUERZT,
                            platzhalter, verzeichnis);
                } catch (Exception e) {
                    LOG.debug("Kürzungs-Hinweis an Reporter {} konnte nicht gesendet werden.",
                            reporterUuid, e);
                }
            };
            if (!verzeichnis.darfDirektAusfuehren()) {
                if (taskScheduler == null) {
                    return;
                }
                taskScheduler.aufgabe(senden);
                return;
            }
            senden.run();
        } catch (Exception e) {
            LOG.debug("Kürzungs-Hinweis konnte nicht gesendet werden.", e);
        }
    }

    private void broadcasten(String typ, Map<String, String> platzhalter) {
        broadcasten(typ, platzhalter, false);
    }
    private void broadcasten(String typ, Map<String, String> platzhalter, boolean silent) {
        if (broadcastService == null) {
            return;
        }
        try {
            broadcastService.senden(typ, platzhalter, silent);
        } catch (Exception e) {
            LOG.warn("Report-Broadcast '{}' fehlgeschlagen (Report bleibt wirksam).", typ, e);
        }
    }

    private void reporterInformieren(ReportRecord record, String bearbeiter, String grund) {
        if (messageService == null || verzeichnis == null || record.reporterUuid() == null) {
            return;
        }
        Map<String, String> platzhalter = Map.of(
                "id", String.valueOf(record.id()),
                "spieler", record.zielName(),
                "ausfuehrer", bearbeiter == null ? "Konsole" : bearbeiter,
                "bearbeiter", bearbeiter == null ? "Konsole" : bearbeiter,
                "grund", grund == null ? "" : grund.trim(),
                "status", record.status().name());
        Runnable senden = () -> {
            try {
                messageService.anSpieler(record.reporterUuid(), MessageKeys.REPORT_BEARBEITET,
                        platzhalter, verzeichnis);
            } catch (Exception e) {
                LOG.debug("Reporter-Info für {} konnte nicht gesendet werden.", record.reporterUuid(), e);
            }
        };
        try {
            if (!verzeichnis.darfDirektAusfuehren()) {
                if (taskScheduler == null) {
                    return;
                }
                taskScheduler.aufgabe(senden);
                return;
            }
            senden.run();
        } catch (IllegalStateException e) {
            LOG.debug("Reporter-Info nach Shutdown verworfen für {}", record.reporterUuid());
        }
    }
}
