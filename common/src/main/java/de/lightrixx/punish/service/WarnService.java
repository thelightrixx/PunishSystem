package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.WarnRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class WarnService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WarnService.class);

    private final WarnRepository warnRepository;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final MessageService messageService;
    private final AuftragsPlaner taskScheduler;
    private final SpielerVerzeichnis verzeichnis;

    private volatile Konfiguration configManager;
    private volatile VerstossService verstossService;
    private volatile ProtectionService protectionService;

    public WarnService(WarnRepository warnRepository) {
        this(warnRepository, null, null, null, null, null, null);
    }

    public WarnService(WarnRepository warnRepository, BroadcastService broadcastService) {
        this(warnRepository, null, broadcastService, null, null, null, null);
    }

    public WarnService(WarnRepository warnRepository,
                       HistoryService historyService,
                       BroadcastService broadcastService,
                       LogWriter logWriter,
                       MessageService messageService,
                       AuftragsPlaner taskScheduler,
                       SpielerVerzeichnis verzeichnis) {
        this.warnRepository = Objects.requireNonNull(warnRepository,
                "Das WarnRepository darf nicht null sein.");
        this.historyService = historyService;
        this.broadcastService = broadcastService;
        this.logWriter = logWriter;
        this.messageService = messageService;
        this.taskScheduler = taskScheduler;
        this.verzeichnis = verzeichnis;
    }

    public WarnRecord hinzufuegen(UUID zielUuid, String zielName, String grund, String ausfuehrer) {
        return hinzufuegen(zielUuid, zielName, grund, ausfuehrer, false);
    }

    public WarnRecord hinzufuegen(UUID zielUuid, String zielName, String grund, String ausfuehrer,
                                   boolean silent) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        ProtectionService schutz = this.protectionService;
        if (schutz != null) {
            try {
                if (schutz.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.WARN)) {
                    LOG.warn("warn-hinzufuegen für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                    throw new IllegalStateException("Warn für geschütztes Ziel ist nicht erlaubt.");
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                LOG.warn("Schutzcheck für Warn-Add {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
                throw new IllegalStateException("Warn bei unsicherem Schutzstatus ist nicht erlaubt.");
            }
        }
        WarnRepository.HinzufuegeErgebnis ergebnis = warnRepository.hinzufuegenMitAnzahl(zielUuid, zielName, grund, ausfuehrer);
        WarnRecord record = ergebnis.record();
        int neueAnzahl = ergebnis.anzahl();

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", zielName);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        platzhalter.put("id", String.valueOf(record.nummer()));
        platzhalter.put("anzahl", String.valueOf(neueAnzahl));

        zielInformieren(zielUuid, MessageKeys.ZIEL_WARN, platzhalter);

        if (historyService != null) {
            try {
                historyService.hinzufuegen(HistoryType.WARN_ADD, ausfuehrer, zielUuid, zielName, grund, null);
            } catch (Exception e) {
                LOG.warn("Warn-Add-History für {} konnte nicht geschrieben werden (Warn bleibt bestehen).",
                        zielUuid, e);
            }
        }
        broadcasten(MessageKeys.BROADCAST_WARN_ADD, platzhalter, silent);
        if (logWriter != null) {
            logWriter.schreiben(HistoryType.WARN_ADD, ausfuehrer, zielName,
                    "Grund: " + grund + ", Nummer: " + record.nummer());
        }
        loeseWarnEskalationAus(zielUuid, zielName, grund, ausfuehrer, silent, neueAnzahl);
        return record;
    }

    public void mitEskalation(Konfiguration configManager, VerstossService verstossService) {
        this.configManager = configManager;
        this.verstossService = verstossService;
    }

    public void mitSchutz(ProtectionService protectionService) {
        this.protectionService = protectionService;
    }

    private void loeseWarnEskalationAus(UUID zielUuid, String zielName, String warnGrund,
                                        String ausfuehrer, boolean silent, int anzahl) {
        Konfiguration config = this.configManager;
        VerstossService verstoesse = this.verstossService;
        if (config == null || verstoesse == null) {
            return;
        }
        try {
            if (!config.warnEskalationAktiv()) {
                return;
            }
            Optional<VerstossStufe> stufe = config.warnEskalationsStufeFuer(anzahl);
            if (stufe.isEmpty()) {
                holeAusstehendeEskalationNach(zielUuid, zielName, warnGrund, ausfuehrer, silent);
                return;
            }
            VerstossStufe effektiveStufe = stufe.get();
            try {
                feuereEskalation(config, verstoesse, zielUuid, zielName, warnGrund, ausfuehrer,
                        silent, anzahl, effektiveStufe);
                AusstehendeEskalation ausstehend = ausstehende.get(zielUuid);
                if (ausstehend != null && ausstehend.schwelle() <= anzahl) {
                    ausstehende.remove(zielUuid);
                }
            } catch (IllegalStateException e) {
                merkeAusstehende(zielUuid, anzahl, effektiveStufe);
                LOG.info("Warn-Eskalation für {} vorgemerkt (Warn bleibt bestehen): {}",
                        zielUuid, e.getMessage());
            }
        } catch (Exception e) {
            LOG.warn("Warn-Eskalation für {} fehlgeschlagen (Warn bleibt bestehen).",
                    zielUuid, e);
        }
    }

    private record AusstehendeEskalation(int schwelle, VerstossStufe stufe) {
    }

    private final java.util.concurrent.ConcurrentHashMap<UUID, AusstehendeEskalation> ausstehende =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_AUSSTEHENDE = 1000;
    private final java.util.concurrent.atomic.AtomicLong verworfeneEskalationen = new java.util.concurrent.atomic.AtomicLong();

    public long anzahlVerworfenerEskalationen() {
        return verworfeneEskalationen.get();
    }

    private void merkeAusstehende(UUID zielUuid, int anzahl, VerstossStufe stufe) {
        try {
            if (ausstehende.size() >= MAX_AUSSTEHENDE) {
                long verworfen = verworfeneEskalationen.incrementAndGet();
                LOG.warn("Ausstehende Warn-Eskalationen voll ({}), Eskalation für {} verworfen (gesamt verworfen: {}).",
                        MAX_AUSSTEHENDE, zielUuid, verworfen);
                return;
            }
            AusstehendeEskalation bisher = ausstehende.get(zielUuid);
            if (bisher == null || anzahl < bisher.schwelle()) {
                ausstehende.put(zielUuid, new AusstehendeEskalation(anzahl, stufe));
            }
        } catch (Exception e) {
            LOG.debug("Ausstehende Eskalation konnte nicht gemerkt werden.", e);
        }
    }

    private void holeAusstehendeEskalationNach(UUID zielUuid, String zielName, String warnGrund,
                                              String ausfuehrer, boolean silent) {
        AusstehendeEskalation ausstehend;
        try {
            ausstehend = ausstehende.get(zielUuid);
        } catch (Exception e) {
            return;
        }
        if (ausstehend == null) {
            return;
        }
        Konfiguration config = this.configManager;
        VerstossService verstoesse = this.verstossService;
        if (config == null || verstoesse == null) {
            return;
        }
        try {
            if (!config.warnEskalationAktiv()) {
                return;
            }
            feuereEskalation(config, verstoesse, zielUuid, zielName, warnGrund, ausfuehrer,
                    silent, ausstehend.schwelle(), ausstehend.stufe());
            ausstehende.remove(zielUuid);
            LOG.info("Ausstehende Warn-Eskalation für {} nachgeholt (Schwelle {}).",
                    zielName, ausstehend.schwelle());
        } catch (IllegalStateException e) {
            LOG.debug("Ausstehende Warn-Eskalation für {} weiter blockiert: {}",
                    zielUuid, e.getMessage());
        } catch (Exception e) {
            LOG.warn("Ausstehende Warn-Eskalation für {} fehlgeschlagen.", zielUuid, e);
        }
    }

    private void feuereEskalation(Konfiguration config, VerstossService verstoesse, UUID zielUuid,
                                 String zielName, String warnGrund, String ausfuehrer,
                                 boolean silent, int anzahl, VerstossStufe effektiveStufe) {
        String grund = eskalationsGrund(config, warnGrund, anzahl, effektiveStufe);
        VerstossRecord verstoss = verstoesse.hinzufuegen(
                zielUuid, zielName, grund, ausfuehrer, effektiveStufe, true);
        Map<String, String> eskalationsPlatzhalter = new HashMap<>();
        eskalationsPlatzhalter.put("spieler", zielName);
        eskalationsPlatzhalter.put("ausfuehrer", ausfuehrer);
        eskalationsPlatzhalter.put("grund", grund);
        eskalationsPlatzhalter.put("warn-grund", warnGrund == null ? "" : warnGrund);
        eskalationsPlatzhalter.put("anzahl", String.valueOf(anzahl));
        eskalationsPlatzhalter.put("stufe", effektiveStufe.schluessel());
        eskalationsPlatzhalter.put("id", String.valueOf(verstoss.nummer()));
        if (istEskalationsBroadcastAktiv(config)) {
            broadcasten(MessageKeys.BROADCAST_WARN_ESKALATION, eskalationsPlatzhalter, silent);
        } else {
            broadcasten(MessageKeys.BROADCAST_VERSTOSS_ADD, eskalationsPlatzhalter, silent);
        }
        LOG.info("Warn-Eskalation: {} ({} Warns) erhielt automatisch einen {}-Verstoß.",
                zielName, anzahl, effektiveStufe.schluessel());
    }

    private boolean istEskalationsBroadcastAktiv(Konfiguration config) {
        try {
            if (!config.broadcastAktiv(MessageKeys.BROADCAST_WARN_ESKALATION)) {
                return false;
            }
            String text = config.broadcastNachricht(MessageKeys.BROADCAST_WARN_ESKALATION);
            return text != null && !text.isBlank();
        } catch (Exception e) {
            LOG.debug("Eskalations-Broadcast-Check fehlgeschlagen, nutze Standard-Verstoß-Broadcast.", e);
            return false;
        }
    }

    private String eskalationsGrund(Konfiguration config, String warnGrund, int anzahl,
                                    VerstossStufe stufe) {
        String template = null;
        try {
            template = config.standardGrund(MessageKeys.DEFAULT_WARN_ESKALATION_GRUND);
        } catch (Exception e) {
            LOG.debug("Eskalations-Grund-Template nicht lesbar, nutze Fallback.", e);
        }
        if (template == null || template.isBlank()) {
            template = "Automatische Eskalation bei %anzahl% Verwarnungen (Auslöser: %grund%)";
        }
        return template.replace("%anzahl%", String.valueOf(anzahl))
                .replace("%stufe%", stufe.schluessel())
                .replace("%grund%", warnGrund == null ? "" : warnGrund);
    }

    public Optional<WarnRecord> entfernen(UUID zielUuid, String zielName, int nummer, String ausfuehrer) {
        return entfernen(zielUuid, zielName, nummer, ausfuehrer, false);
    }

    public Optional<WarnRecord> entfernen(UUID zielUuid, String zielName, int nummer, String ausfuehrer,
                                          boolean silent) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Optional<WarnRecord> entfernt = warnRepository.entfernen(zielUuid, nummer);
        entfernt.ifPresent(warn -> {
            Map<String, String> platzhalter = new HashMap<>();
            platzhalter.put("spieler", zielName);
            platzhalter.put("ausfuehrer", ausfuehrer);
            platzhalter.put("id", String.valueOf(nummer));
            platzhalter.put("anzahl", String.valueOf(warnRepository.anzahl(zielUuid)));
            zielInformieren(zielUuid, MessageKeys.ZIEL_WARN_ENTFERNT, platzhalter);
            if (historyService != null) {
                try {
                    historyService.hinzufuegen(HistoryType.WARN_REMOVE, ausfuehrer, zielUuid, zielName,
                            "Entfernt (Grund war: " + warn.grund() + ")", null);
                } catch (Exception e) {
                    LOG.warn("Warn-Remove-History für {} konnte nicht geschrieben werden.", zielUuid, e);
                }
            }
            broadcasten(MessageKeys.BROADCAST_WARN_DELETE, platzhalter, silent);
            if (logWriter != null) {
                logWriter.schreiben(HistoryType.WARN_REMOVE, ausfuehrer, zielName, "Nummer: " + nummer);
            }
        });
        return entfernt;
    }

    public Optional<WarnRecord> bearbeiten(UUID zielUuid, String zielName, int nummer,
                                           String neuerGrund, String ausfuehrer) {
        return bearbeiten(zielUuid, zielName, nummer, neuerGrund, ausfuehrer, false);
    }

    public Optional<WarnRecord> bearbeiten(UUID zielUuid, String zielName, int nummer,
                                           String neuerGrund, String ausfuehrer, boolean silent) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        Objects.requireNonNull(neuerGrund, "Der neue Grund darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Optional<WarnRecord> bearbeitet = warnRepository.bearbeiten(zielUuid, nummer, neuerGrund);
        bearbeitet.ifPresent(warn -> {
            Map<String, String> platzhalter = new HashMap<>();
            platzhalter.put("spieler", zielName);
            platzhalter.put("ausfuehrer", ausfuehrer);
            platzhalter.put("grund", neuerGrund);
            platzhalter.put("id", String.valueOf(nummer));
            zielInformieren(zielUuid, MessageKeys.ZIEL_WARN_BEARBEITET, platzhalter);
            if (historyService != null) {
                try {
                    historyService.hinzufuegen(HistoryType.WARN_EDIT, ausfuehrer, zielUuid, zielName, neuerGrund, null);
                } catch (Exception e) {
                    LOG.warn("Warn-Edit-History für {} konnte nicht geschrieben werden (Edit bleibt wirksam).",
                            zielUuid, e);
                }
            }
            broadcasten(MessageKeys.BROADCAST_WARN_EDIT, platzhalter, silent);
            if (logWriter != null) {
                logWriter.schreiben(HistoryType.WARN_EDIT, ausfuehrer, zielName,
                        "Nummer: " + nummer + ", Neuer Grund: " + neuerGrund);
            }
        });
        return bearbeitet;
    }

    public List<WarnRecord> findeWarns(UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        return warnRepository.finden(zielUuid);
    }

    public int anzahl(UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        return warnRepository.anzahl(zielUuid);
    }

    private void broadcasten(String typ, Map<String, String> platzhalter, boolean silent) {
        if (broadcastService == null) {
            return;
        }
        try {
            broadcastService.senden(typ, platzhalter, silent);
        } catch (Exception e) {
            LOG.warn("Warn-Broadcast '{}' fehlgeschlagen (Warn bleibt wirksam).", typ, e);
        }
    }

    private void zielInformieren(UUID zielUuid, String schluessel, Map<String, String> platzhalter) {
        if (messageService == null || verzeichnis == null) {
            return;
        }
        if (!verzeichnis.darfDirektAusfuehren()) {
            if (taskScheduler == null) {
                return;
            }
            try {
                taskScheduler.aufgabe(() -> zielInformieren(zielUuid, schluessel, platzhalter));
            } catch (IllegalStateException e) {
                LOG.debug("Ziel-Info nach Shutdown verworfen für {}", zielUuid);
            }
            return;
        }
        try {
            messageService.anSpieler(zielUuid, schluessel, platzhalter, verzeichnis);
        } catch (Exception e) {
            LOG.debug("Ziel-Info für {} konnte nicht gesendet werden.", zielUuid, e);
        }
    }
}
