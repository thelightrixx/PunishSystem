package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.service.AutoBannService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.IpMuteService;
import de.lightrixx.punish.service.IpTimeoutService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.util.ZeitFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VelocityReloadBefehl extends PunishCommand {

    private static final Logger LOG = LoggerFactory.getLogger(VelocityReloadBefehl.class);

    private final VelocityKonfiguration konfiguration;
    private final BanRepository banRepository;
    private final IpBanRepository ipBanRepository;
    private final MuteRepository muteRepository;
    private final TimeoutRepository timeoutRepository;
    private final VerstossRepository verstossRepository;
    private final NoteRepository noteRepository;
    private final WarnRepository warnRepository;
    private final ReportRepository reportRepository;
    private final HistoryRepository historyRepository;
    private final AutoBannService autoBannService;
    private final MuteService muteService;
    private final TimeoutService timeoutService;
    private final IpBanService ipBanService;
    private final IpMuteRepository ipMuteRepository;
    private final IpTimeoutRepository ipTimeoutRepository;
    private final IpMuteService ipMuteService;
    private final IpTimeoutService ipTimeoutService;
    private final AtomicBoolean laeuft = new AtomicBoolean(false);
    private volatile BrueckenKanal brueckenKanal;
    private volatile de.lightrixx.punish.storage.IoExecutor ioExecutor;

    @SuppressWarnings("checkstyle:parameternumber")
    public VelocityReloadBefehl(MessageService messageService,
                                VelocityKonfiguration konfiguration,
                                ProtectionService protectionService,
                                PlayerCache playerCache,
                                AuftragsPlaner planer,
                                BanRepository banRepository,
                                IpBanRepository ipBanRepository,
                                MuteRepository muteRepository,
                                TimeoutRepository timeoutRepository,
                                 VerstossRepository verstossRepository,
                                 NoteRepository noteRepository,
                                 WarnRepository warnRepository,
                                 ReportRepository reportRepository,
                                 HistoryRepository historyRepository,
                                AutoBannService autoBannService,
                                MuteService muteService,
                                TimeoutService timeoutService,
                                IpBanService ipBanService) {
        this(messageService, konfiguration, protectionService, playerCache, planer,
                banRepository, ipBanRepository, muteRepository, timeoutRepository,
                verstossRepository, noteRepository, warnRepository, reportRepository,
                historyRepository, autoBannService, muteService, timeoutService, ipBanService,
                null, null, null, null);
    }

    @SuppressWarnings("checkstyle:parameternumber")
    public VelocityReloadBefehl(MessageService messageService,
                                VelocityKonfiguration konfiguration,
                                ProtectionService protectionService,
                                PlayerCache playerCache,
                                AuftragsPlaner planer,
                                BanRepository banRepository,
                                IpBanRepository ipBanRepository,
                                MuteRepository muteRepository,
                                TimeoutRepository timeoutRepository,
                                 VerstossRepository verstossRepository,
                                 NoteRepository noteRepository,
                                 WarnRepository warnRepository,
                                 ReportRepository reportRepository,
                                 HistoryRepository historyRepository,
                                AutoBannService autoBannService,
                                MuteService muteService,
                                TimeoutService timeoutService,
                                IpBanService ipBanService,
                                IpMuteRepository ipMuteRepository,
                                IpTimeoutRepository ipTimeoutRepository,
                                IpMuteService ipMuteService,
                                IpTimeoutService ipTimeoutService) {
        super(messageService, konfiguration, protectionService, playerCache, planer, null);
        this.konfiguration = Objects.requireNonNull(konfiguration, "Die Konfiguration darf nicht null sein.");
        this.banRepository = Objects.requireNonNull(banRepository, "Das BanRepository darf nicht null sein.");
        this.ipBanRepository = Objects.requireNonNull(ipBanRepository, "Das IpBanRepository darf nicht null sein.");
        this.muteRepository = Objects.requireNonNull(muteRepository, "Das MuteRepository darf nicht null sein.");
        this.timeoutRepository = Objects.requireNonNull(timeoutRepository, "Das TimeoutRepository darf nicht null sein.");
        this.verstossRepository = Objects.requireNonNull(verstossRepository, "Das VerstossRepository darf nicht null sein.");
        this.noteRepository = noteRepository;
        this.warnRepository = warnRepository;
        this.reportRepository = reportRepository;
        this.historyRepository = Objects.requireNonNull(historyRepository, "Das HistoryRepository darf nicht null sein.");
        this.autoBannService = Objects.requireNonNull(autoBannService, "Der AutoBannService darf nicht null sein.");
        this.muteService = muteService;
        this.timeoutService = timeoutService;
        this.ipBanService = ipBanService;
        this.ipMuteRepository = ipMuteRepository;
        this.ipTimeoutRepository = ipTimeoutRepository;
        this.ipMuteService = ipMuteService;
        this.ipTimeoutService = ipTimeoutService;
    }

    public void setBrueckenKanal(BrueckenKanal kanal) {
        this.brueckenKanal = kanal;
    }

    public void setIoExecutor(de.lightrixx.punish.storage.IoExecutor ioExecutor) {
        this.ioExecutor = ioExecutor;
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_RELOAD.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (!laeuft.compareAndSet(false, true)) {
            try {
                messageService.senden(sender, MessageKeys.RELOAD_LAEUFT_BEREITS);
            } catch (Exception e) {
                messageService.senden(sender, MessageKeys.RELOAD_FEHLER);
            }
            LOG.warn("Reload abgewiesen: es läuft bereits ein Reload.");
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean geladen =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            taskScheduler.aufgabeAsync(() -> {
                final java.util.List<String> teilfehler = new java.util.ArrayList<>();
                try {
                    de.lightrixx.punish.storage.IoExecutor io = ioExecutor;
                    if (io != null) {
                        boolean idle;
                        try {
                            idle = io.warteAufIdle(10L, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception e) {
                            idle = false;
                        }
                        if (!idle) {
                            LOG.error("Reload abgebrochen: Schreib-Queue nicht leer ({} ausstehend) – "
                                    + "später erneut versuchen, sonst droht Memory/File-Divergenz.",
                                    io.anzahlAusstehend());
                            try {
                                taskScheduler.aufgabe(() -> messageService.senden(sender,
                                        MessageKeys.RELOAD_FEHLER));
                            } catch (IllegalStateException ignored) {
                                LOG.debug("Reload-Fehlermeldung nach Shutdown verworfen.");
                            }
                            return;
                        }
                    }
                    konfiguration.laden();
                    String ladefehler = konfiguration.letzterLadefehler();
                    if (ladefehler != null && !ladefehler.isBlank()) {
                        LOG.error("Reload abgebrochen: Config-Dateien unlesbar/leer ({}). "
                                + "Die bisherige Konfiguration bleibt aktiv.", ladefehler);
                        final String detail = ladefehler;
                        try {
                            taskScheduler.aufgabe(() -> messageService.senden(sender,
                                    MessageKeys.RELOAD_TEILWEISE, Map.of("detail", detail)));
                        } catch (IllegalStateException ignored) {
                            LOG.debug("Reload-Fehlermeldung nach Shutdown verworfen.");
                        }
                        return;
                    }
                    ladeRepo(teilfehler, "Banns", banRepository::laden);
                    ladeRepo(teilfehler, "IP-Banns", ipBanRepository::laden);
                    ladeRepo(teilfehler, "Mutes", muteRepository::laden);
                    ladeRepo(teilfehler, "Timeouts", timeoutRepository::laden);
                    ladeRepo(teilfehler, "Verstöße", verstossRepository::laden);
                    if (noteRepository != null) {
                        ladeRepo(teilfehler, "Notizen", noteRepository::laden);
                    }
                    if (warnRepository != null) {
                        ladeRepo(teilfehler, "Warnungen", warnRepository::laden);
                    }
                    if (reportRepository != null) {
                        ladeRepo(teilfehler, "Reports", reportRepository::laden);
                    }
                    ladeRepo(teilfehler, "History", historyRepository::laden);
                    ladeRepo(teilfehler, "Player-Cache", playerCache::laden);
                    long jetzt = System.currentTimeMillis();
                    try {
                        autoBannService.bereinigeAbgelaufeneMitZeremonie(jetzt);
                    } catch (Exception e) {
                        LOG.warn("Auto-Bann-Cleanup beim Reload fehlgeschlagen.", e);
                    }
                    try {
                        if (muteService != null) {
                            muteService.bereinigeAbgelaufeneMitHistory("System");
                        } else {
                            muteRepository.entferneAbgelaufene(jetzt);
                        }
                    } catch (Exception e) {
                        LOG.warn("Mute-Cleanup beim Reload fehlgeschlagen.", e);
                    }
                    try {
                        if (timeoutService != null) {
                            timeoutService.bereinigeAbgelaufeneMitHistory("System");
                        } else {
                            timeoutRepository.entferneAbgelaufene(jetzt);
                        }
                    } catch (Exception e) {
                        LOG.warn("Timeout-Cleanup beim Reload fehlgeschlagen.", e);
                    }
                    try {
                        if (ipBanService != null) {
                            ipBanService.bereinigeAbgelaufeneMitHistory("System");
                        } else {
                            ipBanRepository.entferneAbgelaufene(jetzt);
                        }
                    } catch (Exception e) {
                        LOG.warn("IP-Bann-Cleanup beim Reload fehlgeschlagen.", e);
                    }
                    try {
                        if (ipMuteRepository != null) {
                            ipMuteRepository.laden();
                        }
                    } catch (Exception e) {
                        teilfehler.add("IP-Mutes");
                        LOG.warn("IP-Mute-Reload fehlgeschlagen.", e);
                    }
                    try {
                        if (ipTimeoutRepository != null) {
                            ipTimeoutRepository.laden();
                        }
                    } catch (Exception e) {
                        teilfehler.add("IP-Timeouts");
                        LOG.warn("IP-Timeout-Reload fehlgeschlagen.", e);
                    }
                    try {
                        if (ipMuteService != null) {
                            ipMuteService.bereinigeAbgelaufeneMitHistory("System");
                        } else if (ipMuteRepository != null) {
                            ipMuteRepository.entferneAbgelaufene(jetzt);
                        }
                    } catch (Exception e) {
                        LOG.warn("IP-Mute-Cleanup beim Reload fehlgeschlagen.", e);
                    }
                    try {
                        if (ipTimeoutService != null) {
                            ipTimeoutService.bereinigeAbgelaufeneMitHistory("System");
                        } else if (ipTimeoutRepository != null) {
                            ipTimeoutRepository.entferneAbgelaufene(jetzt);
                        }
                    } catch (Exception e) {
                        LOG.warn("IP-Timeout-Cleanup beim Reload fehlgeschlagen.", e);
                    }
                    geladen.set(true);
                } catch (Exception e) {
                    LOG.error("Beim Neuladen ist ein Fehler aufgetreten.", e);
                    try {
                        taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.RELOAD_FEHLER));
                    } catch (IllegalStateException ignored) {
                        LOG.debug("Reload-Fehlermeldung nach Shutdown verworfen.");
                    }
                    return;
                } finally {
                    if (!geladen.get()) {
                        laeuft.set(false);
                    }
                }
                try {
                    taskScheduler.aufgabe(() -> {
                        try {
                            protectionService.setFailClosed(konfiguration.schutzFailClosed());
                        } catch (Exception e) {
                            teilfehler.add("Schutz");
                            LOG.warn("Schutz-Config konnte nach Reload nicht übernommen werden.", e);
                        }
                        try {
                            playerCache.setAllowNeverSeen(konfiguration.offlineAllowNeverSeen());
                        } catch (Exception e) {
                            teilfehler.add("Offline-Auflösung");
                            LOG.warn("Offline-Resolution-Config konnte nicht übernommen werden.", e);
                        }
                        try {
                            playerCache.setIpRetentionTage(konfiguration.ipAufbewahrungTage());
                            playerCache.sweepAbgelaufeneIps();
                            historyRepository.anonymisiereAbgelaufeneIps(System.currentTimeMillis(),
                                    konfiguration.ipAufbewahrungTage() * 86_400_000L);
                        } catch (Exception e) {
                            teilfehler.add("IP-Retention");
                            LOG.warn("Aufbewahrungsfrist für IPs konnte nicht übernommen werden.", e);
                        }
                        try {
                            BrueckenKanal kanal = brueckenKanal;
                            if (kanal != null) {
                                kanal.setBridgeSecrets(konfiguration.brueckenSecrets());
                            }
                        } catch (Exception e) {
                            teilfehler.add("Tunnel-Signatur");
                            LOG.warn("Tunnel-Secret konnte nach Reload nicht übernommen werden.", e);
                        }
                        try {
                            ZeitFormat.setAnzeigeZone(konfiguration.anzeigeZone());
                        } catch (Exception e) {
                            teilfehler.add("Anzeige-Zone");
                            LOG.warn("Anzeige-Zone konnte nicht übernommen werden.", e);
                        }
                        try {
                            autoBannService.rescheduleAutoBanns();
                        } catch (Exception e) {
                            teilfehler.add("Auto-Bann-Planung");
                            LOG.warn("Auto-Bann-Planung konnte nach Reload nicht erneuert werden.", e);
                        }
                        try {
                            BrueckenKanal kanal = brueckenKanal;
                            if (kanal != null) {
                                kanal.mutePushAnAlle();
                            }
                        } catch (Exception e) {
                            teilfehler.add("Mute-Sync");
                            LOG.warn("Mute-Sync nach Reload fehlgeschlagen (heilt per Join nach).", e);
                        }
                        try {
                            BrueckenKanal kanal = brueckenKanal;
                            if (kanal != null) {
                                kanal.reloadAnAlle();
                            }
                        } catch (Exception e) {
                            teilfehler.add("Bridge-Push");
                            LOG.warn("Reload-Push an Bridges fehlgeschlagen.", e);
                        }
                        try {
                            BrueckenKanal kanal = brueckenKanal;
                            if (kanal != null) {
                                kanal.textNeuLadenAnAlle();
                            }
                        } catch (Exception e) {
                            teilfehler.add("Text-Sync");
                            LOG.warn("Text-Sync nach Reload fehlgeschlagen (heilt per Join nach).", e);
                        }
                        try {
                            if (teilfehler.isEmpty()) {
                                messageService.senden(sender, MessageKeys.RELOAD_ERFOLGREICH);
                            } else {
                                messageService.senden(sender, MessageKeys.RELOAD_TEILWEISE,
                                        java.util.Map.of("detail", String.join(", ", teilfehler)));
                            }
                        } finally {
                            laeuft.set(false);
                        }
                    });
                } catch (IllegalStateException ignored) {
                    laeuft.set(false);
                    LOG.debug("Reload-Erfolgsmeldung nach Shutdown verworfen.");
                }
            });
        } catch (IllegalStateException e) {
            laeuft.set(false);
            LOG.warn("Reload abgewiesen: Scheduler geschlossen.", e);
            messageService.senden(sender, MessageKeys.RELOAD_FEHLER);
        }
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        return List.of();
    }

    static void ladeRepo(java.util.List<String> teilfehler, String name, Runnable laden) {
        try {
            laden.run();
        } catch (Exception e) {
            teilfehler.add(name);
            LOG.warn("Reload von {} fehlgeschlagen.", name, e);
        }
    }
}
