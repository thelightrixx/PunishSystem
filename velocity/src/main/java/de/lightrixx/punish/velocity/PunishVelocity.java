package de.lightrixx.punish.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.AutoBannService;
import de.lightrixx.punish.service.BanKickHelper;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.IpMuteService;
import de.lightrixx.punish.service.IpTimeoutService;
import de.lightrixx.punish.service.KickService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.FreezeService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.NoteService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerpassteBroadcastsRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import de.lightrixx.punish.storage.mysql.SpeicherTyp;
import de.lightrixx.punish.storage.mysql.YamlNachMysqlMigrator;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.StartBanner;
import de.lightrixx.punish.util.TimeParser;
import de.lightrixx.punish.util.ZeitFormat;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(id = "punishsystem", name = "PunishSystemVelocity", version = "4.0-beta",
        description = "Zentrales Bestrafungssystem mit Server-Scopes (by lightrixx).",
        authors = {"lightrixx"})
public final class PunishVelocity {

    private static final long CLEANUP_MINUTEN = 5L;
    private static final long SHUTDOWN_WARTEZEIT_SEKUNDEN = 15L;
    private static final long VERPASST_RETENTION_MILLIS = 90L * 24L * 60L * 60L * 1000L;
    public static final String DATENORDNER_NAME = "PunishSystemVelocity";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path datenOrdner;

    // Dirty-Flag für den entprellten Sync-Push: Massenaktionen stoßen pro Spieler
    // einen Observer an, der Push läuft aber nur einmal je Intervall.
    private final java.util.concurrent.atomic.AtomicBoolean muteSyncPushFaellig =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private IoExecutor ioExecutor;
    private volatile boolean persistenzWarnungAktiv;
    private VelocityAuftragsPlaner planer;
    private LogWriter logWriter;
    private AutoBannService autoBannService;
    private BrueckenKanal kanal;
    private MysqlVerbindung mysqlVerbindung;
    private ModellVerteilung modellVerteilung;
    private TextVerteilung textVerteilung;
    private UpdateVerteilung updateVerteilung;
    private LuckPermsKontextRechner luckPermsKontext;

    @Inject
    public PunishVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path datenOrdner) {
        this.proxy = proxy;
        this.logger = logger;
        this.datenOrdner = datenOrdner;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        for (String zeile : StartBanner.zeilen(liesPluginVersion(), "Velocity")) {
            logger.info(zeile);
        }
        File ordner = ordnerVorbereiten();
        File datenUnterOrdner = new File(ordner, "data");

        VelocityKonfiguration config = konfigurationLaden(ordner);

        this.ioExecutor = new IoExecutor();
        this.planer = new VelocityAuftragsPlaner(proxy, this);
        SnakeYamlSpeicher speicher = new SnakeYamlSpeicher();
        VelocityVerzeichnis verzeichnis = new VelocityVerzeichnis(proxy);

        this.logWriter = new LogWriter(ordner, ioExecutor);

        SpeicherTyp speicherTyp = config.speicherTyp();
        MysqlVerbindung mysql = oeffneMysqlFallsGewuenscht(config, speicherTyp,
                datenUnterOrdner, ordner, speicher, verzeichnis);
        this.mysqlVerbindung = mysql;
        boolean nutztMysql = mysql != null;
        logger.info("Speicher-Backend: {}.", nutztMysql ? "MySQL" : "YAML");

        BanRepository banRepository = nutztMysql ? new BanRepository(mysql, ioExecutor)
                : new BanRepository(datenUnterOrdner, speicher, ioExecutor);
        RepositoryBuendel repos = repositoriesLaden(config, nutztMysql, mysql, datenUnterOrdner, ordner,
                speicher, verzeichnis, banRepository);
        IpBanRepository ipBanRepository = repos.ipBan();
        IpMuteRepository ipMuteRepository = repos.ipMute();
        IpTimeoutRepository ipTimeoutRepository = repos.ipTimeout();
        MuteRepository muteRepository = repos.mute();
        TimeoutRepository timeoutRepository = repos.timeout();
        FreezeRepository freezeRepository = repos.freeze();
        VerstossRepository verstossRepository = repos.verstoss();
        NoteRepository noteRepository = repos.note();
        WarnRepository warnRepository = repos.warn();
        ReportRepository reportRepository = repos.report();
        HistoryRepository historyRepository = repos.history();
        PlayerCache playerCache = repos.spieler();
        try {
            verzeichnis.mitPlayerCache(playerCache);
        } catch (Exception e) {
            logger.debug("PlayerCache konnte nicht am Verzeichnis registriert werden.", e);
        }
        VerpassteBroadcastsRepository verpasstRepo = nutztMysql
                ? new VerpassteBroadcastsRepository(mysql, ioExecutor)
                : new VerpassteBroadcastsRepository(datenUnterOrdner, speicher, ioExecutor);
        verpasstRepo.laden();

        MessageService messageService = new MessageService(config);
        ProtectionService protectionService = new ProtectionService(
                verzeichnis, verzeichnis, luckPermsZugang());
        try {
            protectionService.setFailClosed(config.schutzFailClosed());
            playerCache.setAllowNeverSeen(config.offlineAllowNeverSeen());
            ZeitFormat.setAnzeigeZone(config.anzeigeZone());
            if (!protectionService.istFailClosed()) {
                // B-020: Diese Warnung bleibt bewusst bestehen (Fail-Open ist ein
                // Fehlerrisiko, kein Bug) – keine Verhaltensänderung.
                logger.warn("schutz.fail-closed ist false (Legacy Fail-Open): "
                        + "Für Team-Server fail-closed: true empfohlen.");
            }
        } catch (Exception e) {
            logger.warn("Schutz-/Zonen-Config konnte nicht übernommen werden, nutze Defaults.", e);
        }
        TimeParser timeParser = new TimeParser();
        HistoryService historyService = new HistoryService(historyRepository);
        BroadcastService broadcastService = new BroadcastService(config,
                () -> true, planer, verzeichnis);
        broadcastService.mitVerpasstAblage(verpasstRepo);
        BanKickHelper kickHelper = new BanKickHelper(planer, config, protectionService, verzeichnis);

        BanService banService = new BanService(banRepository, historyService, broadcastService,
                logWriter, protectionService, kickHelper, verzeichnis);
        IpBanService ipBanService = new IpBanService(ipBanRepository, historyService, broadcastService,
                logWriter, kickHelper, protectionService, verzeichnis);
        AutoBannService autoBann = new AutoBannService(planer, banRepository, config, timeParser,
                historyService, broadcastService, logWriter, protectionService, verstossRepository,
                messageService, kickHelper, verzeichnis);
        this.autoBannService = autoBann;
        MuteService muteService = new MuteService(muteRepository, historyService, broadcastService,
                logWriter, messageService, planer, protectionService, verzeichnis);
        IpMuteService ipMuteService = new IpMuteService(ipMuteRepository, historyService, broadcastService,
                logWriter, messageService, planer, protectionService, verzeichnis);
        KickService kickService = new KickService(config, historyService, broadcastService,
                logWriter, protectionService, planer, verzeichnis);
        VerstossService verstossService = new VerstossService(verstossRepository, banService, autoBann,
                config, historyService, broadcastService, logWriter, messageService, planer,
                protectionService, verzeichnis);
        TimeoutService timeoutService = new TimeoutService(timeoutRepository, config, historyService,
                broadcastService, logWriter, planer, protectionService, verzeichnis);
        FreezeService freezeService = new FreezeService(freezeRepository, historyService, broadcastService,
                logWriter, messageService, planer, protectionService, verzeichnis);
        IpTimeoutService ipTimeoutService = new IpTimeoutService(ipTimeoutRepository, config, historyService,
                broadcastService, logWriter, planer, protectionService, kickHelper, verzeichnis);
        NoteService noteService = new NoteService(noteRepository, broadcastService);
        WarnService warnService = new WarnService(warnRepository, historyService, broadcastService,
                logWriter, messageService, planer, verzeichnis);
        warnService.mitEskalation(config, verstossService);
        warnService.mitSchutz(protectionService);
        ReportService reportService = new ReportService(reportRepository, config,
                historyService, broadcastService, logWriter, messageService, planer, verzeichnis);

        this.kanal = new BrueckenKanal(this, proxy, logger, muteRepository, ipMuteRepository);
        kanal.setBanRepository(banRepository);
        kanal.setTimeoutRepository(timeoutRepository);
        kanal.setFreezeRepository(freezeRepository);
        kanal.setMessageService(messageService);
        kanal.setBroadcastService(broadcastService);
        try {
            kanal.setPluginVersion(liesPluginVersion());
        } catch (Exception e) {
            logger.debug("Proxy-Version konnte nicht an den Brückenkanal gegeben werden.", e);
        }
        try {
            kanal.setBridgeSecrets(config.brueckenSecrets());
        } catch (Exception e) {
            logger.debug("Tunnel-Secret konnte nicht an den Brückenkanal gegeben werden.", e);
        }
        muteService.beobachterHinzufuegen(uuid -> muteSyncAnstossen());
        ipMuteService.beobachterHinzufuegen(() -> muteSyncAnstossen());
        banService.beobachterHinzufuegen(() -> muteSyncAnstossen());
        timeoutService.beobachterHinzufuegen(() -> muteSyncAnstossen());
        freezeService.beobachterHinzufuegen(uuid -> muteSyncAnstossen());
        kanal.registrieren();
        starteMuteSyncPush();

        UpdateVerteilung updateVerteilung = new UpdateVerteilung(proxy, logger,
                kanal::sendeAn);
        this.updateVerteilung = updateVerteilung;

        VelocityBefehle.register(this, proxy, logger, messageService, config, protectionService,
                playerCache, banService, ipBanService, autoBann, muteService, ipMuteService,
                kickService, timeoutService, ipTimeoutService, freezeService,
                verstossService, noteService, warnService, reportService, historyService, broadcastService,
                timeParser, planer, verzeichnis, banRepository, ipBanRepository, muteRepository,
                timeoutRepository, freezeRepository, verstossRepository, noteRepository, warnRepository, reportRepository, historyRepository,
                 ipMuteRepository, ipTimeoutRepository, kanal, ioExecutor, updateVerteilung, ordner);
        starteUpdateAutoCheck(config);

        GuiBrueckenDienst guiDienst = new GuiBrueckenDienst(proxy, logger, config, playerCache,
                banRepository, muteRepository, timeoutRepository, ipBanRepository,
                ipMuteRepository, ipTimeoutRepository, verstossRepository, warnRepository,
                historyService, reportService, protectionService);
        try {
            guiDienst.setFreezeRepository(freezeRepository);
        } catch (Exception e) {
            logger.debug("Freeze-Repository konnte nicht an den GUI-Dienst gegeben werden.", e);
        }

        PlatzhalterDienst platzhalterDienst = new PlatzhalterDienst(logger, config, playerCache,
                banRepository, muteRepository, timeoutRepository, verstossRepository,
                warnRepository, reportService);
        try {
            platzhalterDienst.setFreezeRepository(freezeRepository);
        } catch (Exception e) {
            logger.debug("Freeze-Repository konnte nicht an den Platzhalterdienst gegeben werden.", e);
        }
        try {
            platzhalterDienst.setNoteRepository(noteRepository);
        } catch (Exception e) {
            logger.debug("Notiz-Repository konnte nicht an den Platzhalterdienst gegeben werden.", e);
        }
        try {
            platzhalterDienst.setHistoryService(historyService);
        } catch (Exception e) {
            logger.debug("History-Service konnte nicht an den Platzhalterdienst gegeben werden.", e);
        }

        LuckPermsKontextRechner lpKontext =
                new LuckPermsKontextRechner(logger, banRepository, muteRepository, timeoutRepository);
        try {
            lpKontext.setFreezeRepository(freezeRepository);
        } catch (Exception e) {
            logger.debug("Freeze-Repository konnte nicht an die LuckPerms-Kontexte gegeben werden.", e);
        }
        try {
            lpKontext.register();
        } catch (Exception e) {
            logger.debug("LuckPerms-Kontexte konnten nicht registriert werden.", e);
        }
        this.luckPermsKontext = lpKontext;
        muteService.beobachterHinzufuegen(uuid -> lpKontext.signalUpdate());
        banService.beobachterHinzufuegen(() -> lpKontext.signalUpdate());
        timeoutService.beobachterHinzufuegen(() -> lpKontext.signalUpdate());
        freezeService.beobachterHinzufuegen(uuid -> lpKontext.signalUpdate());

        StrafListener strafListener = new StrafListener(proxy, logger, playerCache, banService,
                ipBanService, timeoutService, muteService, ipMuteService, ipTimeoutService,
                autoBann, config, messageService, planer,
                banRepository, kanal, guiDienst);
        strafListener.setPlatzhalterDienst(platzhalterDienst);
        try {
            strafListener.setChatfilterStaffel(
                    new ChatfilterStaffelService(config, warnService, muteService, broadcastService));
        } catch (Exception e) {
            logger.warn("Chatfilter-Staffel konnte nicht gestartet werden (Treffer werden ignoriert).", e);
        }
        ModellVerteilung modellVerteilung = new ModellVerteilung(proxy, logger, config, ordner,
                kanal::sendeAn);
        try {
            modellVerteilung.starten();
        } catch (Exception e) {
            logger.warn("Modell-Verteilung konnte nicht gestartet werden (Sync inaktiv).", e);
        }
        this.modellVerteilung = modellVerteilung;
        kanal.setModellVerteilung(modellVerteilung);
        strafListener.setModellVerteilung(modellVerteilung);
        TextVerteilung textVerteilung = new TextVerteilung(proxy, logger, ordner,
                kanal::sendeAn);
        try {
            textVerteilung.laden();
        } catch (Exception e) {
            logger.warn("Text-Verteilung konnte nicht geladen werden (Sync inaktiv).", e);
        }
        this.textVerteilung = textVerteilung;
        kanal.setTextVerteilung(textVerteilung);
        strafListener.setTextVerteilung(textVerteilung);
        kanal.setUpdateVerteilung(updateVerteilung);
        strafListener.setUpdateVerteilung(updateVerteilung);
        strafListener.setVerpasstNachsendung(verpasstRepo, verzeichnis, broadcastService);
        strafListener.setAltWache(broadcastService, protectionService);
        try {
            loggeZeroConfigStatus(config, nutztMysql, modellVerteilung);
        } catch (Exception e) {
            logger.debug("Zero-Config-Status konnte nicht geloggt werden.", e);
        }
        proxy.getEventManager().register(this, strafListener);

        autoBann.planeAlleAktivenAutoBanns();
        try {
            verstossService.bereinigeAlleVerfallenen();
        } catch (Exception e) {
            logger.warn("Verfall-Cleanup beim Start fehlgeschlagen.", e);
        }
        try {
            planer.intervallAsync(() -> {
                try {
                    long jetzt = System.currentTimeMillis();
                    autoBann.bereinigeAbgelaufeneMitZeremonie(jetzt);
                    banService.bereinigeAbgelaufeneMitHistory("System");
                    muteService.bereinigeAbgelaufeneMitHistory("System");
                    timeoutService.bereinigeAbgelaufeneMitHistory("System");
                    freezeService.bereinigeAbgelaufeneMitHistory("System");
                    ipBanService.bereinigeAbgelaufeneMitHistory("System");
                    ipMuteService.bereinigeAbgelaufeneMitHistory("System");
                    ipTimeoutService.bereinigeAbgelaufeneMitHistory("System");
                    historyRepository.anonymisiereAbgelaufeneIps(jetzt,
                            config.ipAufbewahrungTage() * 86_400_000L);
                    playerCache.sweepAbgelaufeneIps();
                    verstossService.bereinigeAlleVerfallenen();
                    verpasstRepo.beschneidenAelterAls(jetzt - VERPASST_RETENTION_MILLIS);
                } catch (Exception e) {
                    logger.warn("Fehler beim periodischen Expiry-Cleanup.", e);
                }
            }, CLEANUP_MINUTEN * 60L * 20L, CLEANUP_MINUTEN * 60L * 20L);
        } catch (IllegalStateException e) {
            logger.warn("Expiry-Cleanup konnte nicht geplant werden.", e);
        }
        startePersistenzWaechter(verzeichnis);
        logger.info("PunishSystemVelocity by lightrixx aktiviert. Scope-Default: "
                + (config.serverName().equals(ServerScope.UNBEKANNT_WERT)
                        ? "aktueller Server (Auto)"
                        : config.serverName()));
    }

    private void startePersistenzWaechter(VelocityVerzeichnis verzeichnis) {
        try {
            planer.intervallAsync(() -> {
                try {
                    IoExecutor io = this.ioExecutor;
                    if (io == null || io.istGesund()) {
                        persistenzWarnungAktiv = false;
                        return;
                    }
                    int fehler = io.anzahlFehler();
                    logger.error("Persistenz ungesund ({} Fehler) – Strafen werden ggf. nicht dauerhaft "
                            + "gespeichert! /punish-diagnostics prüfen.", fehler);
                    if (!persistenzWarnungAktiv) {
                        persistenzWarnungAktiv = true;
                        benachrichtigeTeam(verzeichnis, fehler);
                    }
                } catch (Exception e) {
                    logger.debug("Persistenz-Wächter fehlgeschlagen.", e);
                }
            }, CLEANUP_MINUTEN * 60L * 20L, CLEANUP_MINUTEN * 60L * 20L);
        } catch (IllegalStateException e) {
            logger.warn("Persistenz-Wächter konnte nicht geplant werden.", e);
        }
    }

    private void benachrichtigeTeam(VelocityVerzeichnis verzeichnis, int fehler) {
        Component warnung;
        try {
            warnung = MiniMessageUtil.miniMessage().deserialize(
                    "<red>[PunishSystem] Persistenzfehler (" + fehler
                            + "): Strafen werden ggf. nicht dauerhaft gespeichert – /punish-diagnostics prüfen.");
        } catch (Exception e) {
            logger.debug("Persistenz-Warnung konnte nicht gebaut werden.", e);
            return;
        }
        Runnable senden = () -> {
            try {
                for (OnlineSpieler spieler : verzeichnis.onlineSpieler()) {
                    try {
                        if (verzeichnis.hatRecht(spieler.uuid(),
                                Permissions.PUNISH_DIAGNOSTICS.node())) {
                            verzeichnis.mitteilen(spieler.uuid(), warnung);
                        }
                    } catch (Exception e) {
                        logger.debug("Persistenz-Warnung an {} fehlgeschlagen.", spieler.uuid(), e);
                    }
                }
            } catch (Exception e) {
                logger.debug("Persistenz-Warnung konnte nicht zugestellt werden.", e);
            }
        };
        try {
            if (verzeichnis.darfDirektAusfuehren()) {
                senden.run();
            } else {
                planer.aufgabe(senden);
            }
        } catch (IllegalStateException e) {
            logger.debug("Persistenz-Warnung konnte nicht eingeplant werden.", e);
        }
    }

    private void loggeZeroConfigStatus(VelocityKonfiguration config, boolean nutztMysql,
                                       ModellVerteilung modellVerteilung) {
        int serverAnzahl = 0;
        try {
            serverAnzahl = proxy.getAllServers().size();
        } catch (Exception e) {
            logger.debug("Zero-Config: Serveranzahl konnte nicht ermittelt werden.", e);
        }
        boolean modellBereit = false;
        try {
            modellBereit = modellVerteilung != null && modellVerteilung.aktiv();
        } catch (Exception e) {
            logger.debug("Zero-Config: Modell-Status konnte nicht ermittelt werden.", e);
        }
        logger.info("Zero-Config: Speicher={}, Modell-Sync={}, Bridges={} (Namen/Permissions/Speicher auto, LuckPerms nur auf Proxy nötig).",
                nutztMysql ? "MySQL" : "YAML(deprecated)",
                modellBereit ? "bereit" : "inaktiv",
                serverAnzahl);
        if (!nutztMysql) {
            logger.warn("YAML-Modus ist deprecated: für Netzwerke speicher.typ=mysql in config/general.yml setzen + Proxy neustarten.");
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("PunishSystemVelocity wird deaktiviert.");
        if (luckPermsKontext != null) {
            try {
                luckPermsKontext.unregister();
            } catch (Exception e) {
                logger.warn("Fehler beim Abmelden der LuckPerms-Kontexte.", e);
            }
            luckPermsKontext = null;
        }
        if (autoBannService != null) {
            try {
                autoBannService.shutdown();
            } catch (Exception e) {
                logger.warn("Fehler beim Herunterfahren des AutoBannService.", e);
            }
        }
        if (planer != null) {
            planer.alleAbbrechen();
        }
        if (logWriter != null) {
            logWriter.schliessen();
        }
        if (ioExecutor != null) {
            boolean ok = ioExecutor.herunterfahren(SHUTDOWN_WARTEZEIT_SEKUNDEN, TimeUnit.SECONDS);
            if (!ok) {
                logger.warn("I/O-Queue wurde nicht leer – Datenverlust möglich!");
            }
        }
        if (mysqlVerbindung != null) {
            try {
                mysqlVerbindung.schliessen();
            } catch (Exception e) {
                logger.warn("MySQL-Pool konnte nicht geschlossen werden.", e);
            }
            mysqlVerbindung = null;
        }
        if (modellVerteilung != null) {
            try {
                modellVerteilung.schliessen();
            } catch (Exception e) {
                logger.warn("Modell-Sync-Pool konnte nicht geschlossen werden.", e);
            }
            modellVerteilung = null;
        }
        if (updateVerteilung != null) {
            try {
                updateVerteilung.schliessen();
            } catch (Exception e) {
                logger.warn("Update-Push-Pool konnte nicht geschlossen werden.", e);
            }
            updateVerteilung = null;
        }
        if (textVerteilung != null) {
            try {
                textVerteilung.schliessen();
            } catch (Exception e) {
                logger.warn("Text-Sync-Pool konnte nicht geschlossen werden.", e);
            }
            textVerteilung = null;
        }
    }

    private void muteSyncAnstossen() {
        muteSyncPushFaellig.set(true);
    }

    private void starteMuteSyncPush() {
        planer.intervallAsync(() -> {
            try {
                if (muteSyncPushFaellig.compareAndSet(true, false)) {
                    kanal.mutePushAnAlle();
                }
            } catch (Exception e) {
                logger.debug("Entprellter Sync-Push an Bridges fehlgeschlagen.", e);
            }
        }, 20L, 20L);
    }

    private void starteUpdateAutoCheck(VelocityKonfiguration config) {
        try {
            if (config == null || !config.updateAutoCheckBeimStart()) {
                return;
            }
            String autor = config.updateHangarAutor();
            String slug = config.updateHangarSlug();
            if (autor.isBlank() || slug.isBlank()) {
                logger.debug("Update-Auto-Check übersprungen (update.hangar-author/slug leer).");
                return;
            }
            VelocityAuftragsPlaner lokal = planer;
            if (lokal == null) {
                return;
            }
            String channel = config.updateChannel();
            lokal.aufgabeAsync(() -> {
                try {
                    var dienst = new de.lightrixx.punish.update.HangarUpdateService(autor, slug, channel,
                            de.lightrixx.punish.update.HangarUpdateService.standardAbruf());
                    var info = dienst.pruefe();
                    String aktuell = liesPluginVersion();
                    if (de.lightrixx.punish.update.UpdateVergleich.neuerVerfuegbar(aktuell, info.version())) {
                        logger.warn("PunishSystem-Update verfügbar: {} (installiert: {}, Channel: {}). /punish-update check",
                                info.version(), aktuell, info.channel());
                    } else {
                        logger.info("PunishSystem aktuell ({}).", aktuell);
                    }
                } catch (Exception e) {
                    logger.debug("Update-Auto-Check fehlgeschlagen.", e);
                }
            });
            try {
                de.lightrixx.punish.update.HangarUpdateService.planeHinweisIntervall(
                        lokal, config::updateIntervallMinuten,
                        () -> {
                            try {
                                var dienst = new de.lightrixx.punish.update.HangarUpdateService(
                                        config.updateHangarAutor(), config.updateHangarSlug(),
                                        config.updateChannel(),
                                        de.lightrixx.punish.update.HangarUpdateService.standardAbruf());
                                var info = dienst.pruefe();
                                String aktuell = liesPluginVersion();
                                if (de.lightrixx.punish.update.UpdateVergleich.neuerVerfuegbar(aktuell, info.version())) {
                                    logger.warn("PunishSystem-Update verfügbar (Intervall-Check): {} (installiert: {}). Nur Hinweis, kein Download.",
                                            info.version(), aktuell);
                                }
                            } catch (Exception e) {
                                logger.debug("Periodischer Update-Hinweis fehlgeschlagen (nur Hinweis).", e);
                            }
                        });
            } catch (Exception e) {
                logger.debug("Update-Intervall-Planung übersprungen.", e);
            }
        } catch (Exception e) {
            logger.debug("Update-Auto-Check konnte nicht gestartet werden.", e);
        }
    }

    private File ordnerVorbereiten() {
        Path effektivPfad = datenOrdner;
        try {
            Path eltern = datenOrdner.getParent();
            if (eltern != null) {
                effektivPfad = eltern.resolve(DATENORDNER_NAME);
            }
        } catch (Exception e) {
            logger.warn("Konnte Wunsch-Datenordner nicht auflösen, nutze injizierten Ordner.", e);
            effektivPfad = datenOrdner;
        }
        migriereLegacyOrdner(datenOrdner, effektivPfad);
        File ordner = effektivPfad.toFile();
        if (!ordner.exists() && !ordner.mkdirs()) {
            logger.warn("Der Datenordner konnte nicht angelegt werden.");
        }
        try {
            File datenUnterOrdner = new File(ordner, "data");
            int snapshots = de.lightrixx.punish.storage.DateiSicherung.snapshotOrdnerFuerBoot(datenUnterOrdner);
            snapshots += de.lightrixx.punish.storage.DateiSicherung.snapshotOrdnerFuerBoot(ordner);
            if (snapshots > 0) {
                logger.info("Boot-Backup: {} Datendatei(en) gesichert.", snapshots);
            }
        } catch (Exception e) {
            logger.warn("Boot-Backup fehlgeschlagen (Start läuft weiter).", e);
        }
        return ordner;
    }

    private VelocityKonfiguration konfigurationLaden(File ordner) {
        VelocityKonfiguration config = new VelocityKonfiguration(proxy, ordner);
        config.laden();
        String ladefehler = config.letzterLadefehler();
        if (ladefehler != null && !ladefehler.isBlank()) {
            throw new IllegalStateException("Konfiguration konnte beim Start nicht geladen werden: "
                    + ladefehler);
        }
        return config;
    }

    private record RepositoryBuendel(
            IpBanRepository ipBan,
            IpMuteRepository ipMute,
            IpTimeoutRepository ipTimeout,
            MuteRepository mute,
            TimeoutRepository timeout,
            FreezeRepository freeze,
            VerstossRepository verstoss,
            NoteRepository note,
            WarnRepository warn,
            ReportRepository report,
            HistoryRepository history,
            PlayerCache spieler) {
    }

    private RepositoryBuendel repositoriesLaden(VelocityKonfiguration config, boolean nutztMysql,
                                                MysqlVerbindung mysql,
                                                File datenUnterOrdner, File ordner,
                                                SnakeYamlSpeicher speicher,
                                                VelocityVerzeichnis verzeichnis,
                                                BanRepository banRepository) {
        IpBanRepository ipBanRepository = nutztMysql ? new IpBanRepository(mysql, ioExecutor)
                : new IpBanRepository(datenUnterOrdner, speicher, ioExecutor);
        IpMuteRepository ipMuteRepository = nutztMysql ? new IpMuteRepository(mysql, ioExecutor)
                : new IpMuteRepository(datenUnterOrdner, speicher, ioExecutor);
        IpTimeoutRepository ipTimeoutRepository = nutztMysql ? new IpTimeoutRepository(mysql, ioExecutor)
                : new IpTimeoutRepository(datenUnterOrdner, speicher, ioExecutor);
        MuteRepository muteRepository = nutztMysql ? new MuteRepository(mysql, ioExecutor)
                : new MuteRepository(datenUnterOrdner, speicher, ioExecutor);
        TimeoutRepository timeoutRepository = nutztMysql ? new TimeoutRepository(mysql, ioExecutor)
                : new TimeoutRepository(datenUnterOrdner, speicher, ioExecutor);
        FreezeRepository freezeRepository = nutztMysql ? new FreezeRepository(mysql, ioExecutor)
                : new FreezeRepository(datenUnterOrdner, speicher, ioExecutor);
        VerstossRepository verstossRepository = nutztMysql ? new VerstossRepository(mysql, ioExecutor)
                : new VerstossRepository(datenUnterOrdner, speicher, ioExecutor);
        NoteRepository noteRepository = nutztMysql ? new NoteRepository(mysql, ioExecutor)
                : new NoteRepository(datenUnterOrdner, speicher, ioExecutor);
        WarnRepository warnRepository = nutztMysql ? new WarnRepository(mysql, ioExecutor)
                : new WarnRepository(datenUnterOrdner, speicher, ioExecutor);
        ReportRepository reportRepository = nutztMysql ? new ReportRepository(mysql, ioExecutor)
                : new ReportRepository(datenUnterOrdner, speicher, ioExecutor);
        HistoryRepository historyRepository = nutztMysql ? new HistoryRepository(mysql, ioExecutor)
                : new HistoryRepository(datenUnterOrdner, speicher, ioExecutor);
        PlayerCache playerCache = nutztMysql ? new PlayerCache(mysql, ioExecutor, verzeichnis)
                : new PlayerCache(ordner, speicher, ioExecutor, verzeichnis);
        playerCache.setIpRetentionTage(config.ipAufbewahrungTage());
        long start = System.nanoTime();
        banRepository.laden();
        ipBanRepository.laden();
        ipMuteRepository.laden();
        ipTimeoutRepository.laden();
        muteRepository.laden();
        timeoutRepository.laden();
        freezeRepository.laden();
        verstossRepository.laden();
        noteRepository.laden();
        warnRepository.laden();
        reportRepository.laden();
        historyRepository.laden();
        playerCache.laden();
        logger.info("Repositories geladen in {} ms.", (System.nanoTime() - start) / 1_000_000L);
        return new RepositoryBuendel(ipBanRepository, ipMuteRepository, ipTimeoutRepository,
                muteRepository, timeoutRepository, freezeRepository, verstossRepository, noteRepository,
                warnRepository, reportRepository, historyRepository, playerCache);
    }

    private MysqlVerbindung oeffneMysqlFallsGewuenscht(VelocityKonfiguration config, SpeicherTyp typ,
                                                      File datenUnterOrdner, File ordner,
                                                      SnakeYamlSpeicher speicher,
                                                      VelocityVerzeichnis verzeichnis) {
        if (typ != SpeicherTyp.MYSQL) {
            return null;
        }
        MysqlVerbindung mysql;
        try {
            mysql = MysqlVerbindung.oeffnen(config.mysqlKonfiguration());
        } catch (Exception e) {
            logger.error("MySQL ist als Speicher konfiguriert, aber nicht erreichbar – "
                    + "PunishSystem wird deaktiviert (Fail-Closed, keine leeren Straf-Listen); "
                    + "der Proxy läuft ohne Strafverwaltung.", e);
            throw new IllegalStateException("MySQL-Backend nicht verfügbar.", e);
        }
        try {
            MysqlSchema.initialisieren(mysql);
        } catch (Exception e) {
            try {
                mysql.schliessen();
            } catch (Exception closeFehler) {
                logger.debug("MySQL-Cleanup nach Schema-Fehler fehlgeschlagen.", closeFehler);
            }
            logger.error("MySQL-Schema konnte nicht angelegt werden – PunishSystem wird deaktiviert; "
                    + "der Proxy läuft ohne Strafverwaltung.", e);
            throw new IllegalStateException("MySQL-Schema fehlerhaft.", e);
        }
        boolean migrationLaeuftNoch = MysqlSchema.migrationOffen(mysql);
        if (config.speicherMigration() && (MysqlSchema.istLeer(mysql) || migrationLaeuftNoch)) {
            try {
                MysqlSchema.markiereMigration(mysql, MysqlSchema.MIGRATION_LAEUFT);
                if (migrationLaeuftNoch) {
                    logger.info("Unterbrochene YAML→MySQL-Migration wird fortgesetzt "
                            + "(Migrationsstatus 'running' aus vorherigem Lauf).");
                } else {
                    logger.info("MySQL ist leer – importiere einmalig YAML nach MySQL ...");
                }
                int anzahl = YamlNachMysqlMigrator.migrieren(datenUnterOrdner, ordner, speicher,
                        ioExecutor, verzeichnis, mysql, config.ipAufbewahrungTage());
                logger.info("Migration: {} Einträge nach MySQL übernommen und verifiziert.", anzahl);
                MysqlSchema.markiereMigration(mysql, MysqlSchema.MIGRATION_FERTIG);
            } catch (Exception e) {
                try {
                    mysql.schliessen();
                } catch (Exception closeFehler) {
                    logger.debug("MySQL-Cleanup nach Migrations-Fehler fehlgeschlagen.", closeFehler);
                }
                logger.error("YAML→MySQL-Migration fehlgeschlagen – PunishSystem wird deaktiviert "
                        + "(Migrationsstatus bleibt 'running', nächster Start versucht es erneut); "
                        + "der Proxy läuft ohne Strafverwaltung.", e);
                throw new IllegalStateException("MySQL-Migration fehlgeschlagen.", e);
            }
        }
        return mysql;
    }

    private void migriereLegacyOrdner(Path injiziert, Path effektiv) {
        try {
            if (injiziert == null || effektiv == null || injiziert.equals(effektiv)) {
                return;
            }
            File neu = effektiv.toFile();
            if (neu.exists() && neu.isDirectory() && neu.list() != null && neu.list().length > 0) {
                return;
            }
            File alt = injiziert.toFile();
            if (!alt.isDirectory()) {
                return;
            }
            try {
                java.nio.file.Files.move(alt.toPath(), effektiv,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                logger.info("Datenordner '{}' nach '{}' migriert.", alt.getName(), neu.getName());
            } catch (Exception moveFehler) {
                logger.debug("Atomarer Move fehlgeschlagen, versuche Kopie.", moveFehler);
                try {
                    kopiereOrdnerRekursiv(alt.toPath(), effektiv);
                    logger.info("Datenordner '{}' nach '{}' kopiert (alt bleibt als Backup).",
                            alt.getName(), neu.getName());
                } catch (Exception kopierFehler) {
                    logger.warn("Legacy-Datenordner '{}' konnte nicht migriert werden.", alt.getName(), kopierFehler);
                }
            }
        } catch (Exception e) {
            logger.debug("Legacy-Migration übersprungen.", e);
        }
    }

    private static void kopiereOrdnerRekursiv(java.nio.file.Path quelle, java.nio.file.Path ziel) throws java.io.IOException {
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(quelle)) {
            for (java.nio.file.Path pfad : (Iterable<java.nio.file.Path>) stream::iterator) {
                java.nio.file.Path relativ = quelle.relativize(pfad);
                java.nio.file.Path zielPfad = ziel.resolve(relativ);
                if (java.nio.file.Files.isDirectory(pfad)) {
                    java.nio.file.Files.createDirectories(zielPfad);
                } else {
                    if (zielPfad.getParent() != null) {
                        java.nio.file.Files.createDirectories(zielPfad.getParent());
                    }
                    java.nio.file.Files.copy(pfad, zielPfad, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @SuppressWarnings("null")
    private String liesPluginVersion() {
        try {
            return proxy.getPluginManager().getPlugin("punishsystem")
                    .flatMap(container -> container.getDescription().getVersion())
                    .filter(vers -> !vers.isBlank())
                    .map(String::trim)
                    .orElse("unbekannt");
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    private de.lightrixx.punish.hook.LuckPermsZugang luckPermsZugang() {
        return new de.lightrixx.punish.hook.LuckPermsZugang(
                () -> {
                    try {
                        return proxy.getPluginManager().getPlugin("luckperms").isPresent();
                    } catch (Exception e) {
                        return false;
                    }
                },
                () -> false);
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }
}
