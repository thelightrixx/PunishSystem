package de.lightrixx.punish.bridge;

import de.lightrixx.punish.core.BukkitAuftragsPlaner;
import de.lightrixx.punish.storage.BukkitOfflineVerzeichnis;
import de.lightrixx.punish.storage.BukkitYamlSpeicher;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.YamlUtil;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.StartBanner;
import de.lightrixx.punish.util.ZeitFormat;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.TimeUnit;

public final class PunishBridge extends JavaPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(PunishBridge.class);

    private IoExecutor ioExecutor;
    private BukkitAuftragsPlaner planer;
    private KanalClient kanal;
    private BridgeEnforcementListener enforcementListener;
    private BridgeKonfiguration bridgeKonfiguration;
    private de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration chatfilterKonfiguration;
    private de.lightrixx.punish.bridge.chatfilter.ChatfilterService chatfilterService;
    private volatile String chatfilterDateiStand = "";
    private final java.util.concurrent.atomic.AtomicBoolean chatfilterReloadLaeuft =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Object chatfilterSchalterSperre = new Object();
    private volatile Boolean ausstehenderFilterSchalter;
    private volatile Boolean ausstehenderKiSchalter;
    private volatile de.lightrixx.punish.bridge.chatfilter.ModellEmpfaenger modellEmpfaengerAktuell;
    private volatile de.lightrixx.punish.bridge.TextEmpfaenger textEmpfaengerAktuell;
    private volatile de.lightrixx.punish.bridge.JarEmpfaenger jarEmpfaengerAktuell;
    private volatile boolean kiAusfallBereitsGemeldet;
    private Object papiExpansion;
    private BanCache banCache;
    private MuteCache muteCache;
    private IpMuteCache ipMuteCache;
    private TimeoutCache timeoutCache;
    @SuppressWarnings("unused")
    private FreezeCache freezeCache;
    private PlayerCache spielerCache;

    @Override
    public void onEnable() {
        try {
            enableIntern();
        } catch (Throwable t) {
            LOG.error("Bridge-Enable abgebrochen.", t);
            try {
                getServer().getPluginManager().disablePlugin(this);
            } catch (Exception e) {
                LOG.warn("Bridge konnte nach Enable-Fehler nicht disabled werden.", e);
            }
        }
    }

    private void enableIntern() {
        for (String zeile : StartBanner.zeilen(liesPluginVersion(), liesPlattform())) {
            LOG.info(zeile);
        }
        warneBeiArchOhneOnnxNatives();
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            LOG.error("Folia wird von der PunishSystemBridge NICHT unterstützt (Bukkit-Scheduler ist "
                    + "region-thread-unsafe). Plugin wird deaktiviert – nutze Paper/Purpur.");
            try {
                getServer().getPluginManager().disablePlugin(this);
            } catch (Exception e) {
                LOG.debug("Bridge konnte nach Folia-Erkennung nicht disabled werden.", e);
            }
            return;
        } catch (ClassNotFoundException e) {
            LOG.debug("Kein Folia – Paper/Purpur.");
        } catch (Exception e) {
            LOG.debug("Folia-Check übersprungen.", e);
        }
        File datenOrdner = getDataFolder();
        migriereLegacyOrdner(datenOrdner);
        if (!datenOrdner.exists() && !datenOrdner.mkdirs()) {
            LOG.warn("Der Datenordner konnte nicht angelegt werden.");
        }
        try {
            int snapshots = de.lightrixx.punish.storage.DateiSicherung.snapshotOrdnerFuerBoot(datenOrdner);
            if (snapshots > 0) {
                LOG.info("Boot-Backup: {} Datendatei(en) gesichert.", snapshots);
            }
        } catch (Exception e) {
            LOG.warn("Boot-Backup fehlgeschlagen (Start läuft weiter).", e);
        }

        BridgeKonfiguration config = new BridgeKonfiguration(this);
        config.laden();
        this.bridgeKonfiguration = config;
        if (de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT.equals(config.serverName())) {
            LOG.warn("netzwerk.server-name ist leer (Zero-Config): Bridge startet im Lern-Modus und "
                    + "übernimmt den Namen automatisch vom Proxy (HELLO_ACK). Bis dahin fail-closed.");
        }
        try {
            ZeitFormat.setAnzeigeZone(config.anzeigeZone());
        } catch (Exception e) {
            LOG.warn("Anzeige-Zone konnte nicht übernommen werden.", e);
        }

        BukkitAuftragsPlaner planerLokal = new BukkitAuftragsPlaner(this, getServer().getScheduler());
        this.planer = planerLokal;
        BukkitOfflineVerzeichnis offlineVerzeichnis = new BukkitOfflineVerzeichnis();

        this.ioExecutor = new IoExecutor();
        LOG.info("Speicher-Backend: Bridge-lokal (Spieler-Cache, YAML). Strafen kommen per Proxy-Sync.");
        BukkitYamlSpeicher speicher = new BukkitYamlSpeicher(new YamlUtil());
        PlayerCache playerCache = new PlayerCache(datenOrdner, speicher, ioExecutor, offlineVerzeichnis);
        playerCache.laden();
        try {
            playerCache.setAllowNeverSeen(config.offlineAllowNeverSeen());
        } catch (Exception e) {
            LOG.warn("Offline-Resolution-Config konnte nicht übernommen werden.", e);
        }

        try {
            if (!getServer().getOnlineMode()) {
                LOG.warn("Backend läuft im Offline-Mode: UUIDs sind fälschbar, UUID-Banns umgehbar. "
                        + "Online-Mode + Velocity-Forwarding empfohlen; IP-Sperren bleiben wirksam.");
            }
        } catch (Exception e) {
            LOG.debug("Online-Mode-Check übersprungen.", e);
        }
        LOG.warn("Backend-Ports per Firewall nur für den Proxy freigeben (Proxy-Only). "
                + "Direktjoin ohne Proxy umgeht Proxy-Enforcement; die Bridge hält nur einen "
                + "Fallback-Deny-Cache (Mute/Ban/Timeout) ohne eigene Straflogik.");
        try {
            String bindIp = getServer().getIp();
            if (bindIp != null && !bindIp.isBlank()
                    && !bindIp.trim().equalsIgnoreCase("127.0.0.1")
                    && !bindIp.trim().equalsIgnoreCase("localhost")) {
                LOG.error("Backend bindet auf '{}' (nicht Loopback): Direktjoins umgehen das Proxy-Enforcement. "
                        + "Bitte server-ip=127.0.0.1 setzen + Firewall-Regeln prüfen (siehe Doku).", bindIp);
            }
        } catch (Exception e) {
            LOG.debug("Bind-IP-Check übersprungen.", e);
        }

        MuteCache muteCache = new MuteCache();
        IpMuteCache ipMuteCache = new IpMuteCache();
        BanCache banCache = new BanCache();
        TimeoutCache timeoutCache = new TimeoutCache();
        FreezeCache freezeCache = new FreezeCache();
        this.muteCache = muteCache;
        this.ipMuteCache = ipMuteCache;
        this.banCache = banCache;
        this.timeoutCache = timeoutCache;
        this.freezeCache = freezeCache;
        this.spielerCache = playerCache;
        KanalClient kanalClient = new KanalClient(this, muteCache, ipMuteCache, banCache, timeoutCache, freezeCache);
        this.kanal = kanalClient;
        kanalClient.registrieren();
        try {
            kanalClient.mitPluginVersion(liesPluginVersion());
        } catch (Exception e) {
            LOG.debug("Bridge-Version konnte nicht an den Kanal gegeben werden.", e);
        }
        kanalClient.mitHelloAckHandler(config::lerneServerName);
        kanalClient.mitReloadHandler(this::planeChatfilterReload);
        kanalClient.mitSteuerungsHandler(this::wendeChatfilterSteuerungAn);
        kanalClient.mitWortHandler(this::fuegeChatfilterWortHinzu);
        kanalClient.mitVariante("bridge-1.21");
        kanalClient.setUpdateStand(liesPluginVersion());
        try {
            kanalClient.setTunnelSecret(config.tunnelSecret());
        } catch (Exception e) {
            LOG.debug("Tunnel-Secret konnte nicht an den Kanal gegeben werden.", e);
        }
        richteUpdateEmpfaengerEin();

        this.enforcementListener = new BridgeEnforcementListener(muteCache, ipMuteCache, banCache,
                timeoutCache, freezeCache, config, kanalClient,
                MiniMessageUtil.miniMessage(), playerCache);
        getServer().getPluginManager().registerEvents(enforcementListener, this);
        this.chatfilterService = starteChatfilterFallsAktiv(config, kanalClient);
        richteModellSyncEin();

        java.util.List<BridgeBrigadierBefehle.Eintrag> brigadierEintraege = new java.util.ArrayList<>();
        ReportWeiterleitung reportTunnel = new ReportWeiterleitung(kanalClient, "report", "punish.report");
        registriereWeiterleitung("report", reportTunnel);
        brigadierEintraege.add(eintragFuer("report", reportTunnel, true));
        ReportWeiterleitung reportsTunnel = new ReportWeiterleitung(kanalClient, "reports", "punish.reports");
        registriereWeiterleitung("reports", reportsTunnel);
        brigadierEintraege.add(eintragFuer("reports", reportsTunnel, true));

        brigadierEintraege.add(tunnel("mute", new BridgeBefehlWeiterleitung(kanalClient, "mute", "punish.mute", true, config)));        brigadierEintraege.add(tunnel("unmute", new BridgeBefehlWeiterleitung(kanalClient, "unmute", "punish.unmute", false, config)));
        brigadierEintraege.add(tunnel("mute-ip", new BridgeBefehlWeiterleitung(kanalClient, "mute-ip", "punish.muteip", true, config)));
        brigadierEintraege.add(tunnel("unmute-ip", new BridgeBefehlWeiterleitung(kanalClient, "unmute-ip", "punish.unmuteip", false, config)));
        brigadierEintraege.add(tunnel("mute-all", new BridgeBefehlWeiterleitung(kanalClient, "mute-all", "punish.muteall", true, config)));
        brigadierEintraege.add(tunnel("unmute-all", new BridgeBefehlWeiterleitung(kanalClient, "unmute-all", "punish.unmuteall", false, config)));
        brigadierEintraege.add(tunnel("punish-status", new BridgeBefehlWeiterleitung(kanalClient, "punish-status", "punish.status", true, config)));
        brigadierEintraege.add(tunnel("pscheck", new BridgeBefehlWeiterleitung(kanalClient, "pscheck", "punish.status", false, config)));
        brigadierEintraege.add(tunnel("punish-history", new BridgeBefehlWeiterleitung(kanalClient, "punish-history", "punish.history", true, config)));
        brigadierEintraege.add(tunnel("alts", new BridgeBefehlWeiterleitung(kanalClient, "alts", "punish.alts", true, config)));
        brigadierEintraege.add(tunnel("ban", new BridgeBefehlWeiterleitung(kanalClient, "ban", "punish.ban", true, config)));
        brigadierEintraege.add(tunnel("permabann", new BridgeBefehlWeiterleitung(kanalClient, "permabann", "punish.permabann", true, config)));
        brigadierEintraege.add(tunnel("ban-ip", new BridgeBefehlWeiterleitung(kanalClient, "ban-ip", "punish.banip", true, config)));
        brigadierEintraege.add(tunnel("unban-ip", new BridgeBefehlWeiterleitung(kanalClient, "unban-ip", "punish.unbanip", false, config)));
        brigadierEintraege.add(tunnel("ban-all", new BridgeBefehlWeiterleitung(kanalClient, "ban-all", "punish.banall", true, config)));
        brigadierEintraege.add(tunnel("kick", new BridgeBefehlWeiterleitung(kanalClient, "kick", "punish.kick", false, config)));
        brigadierEintraege.add(tunnel("kick-ip", new BridgeBefehlWeiterleitung(kanalClient, "kick-ip", "punish.kickip", false, config)));
        brigadierEintraege.add(tunnel("kick-all", new BridgeBefehlWeiterleitung(kanalClient, "kick-all", "punish.kickall", false, config)));
        brigadierEintraege.add(tunnel("pardon", new BridgeBefehlWeiterleitung(kanalClient, "pardon", "punish.pardon", false, config)));
        brigadierEintraege.add(tunnel("pardon-all", new BridgeBefehlWeiterleitung(kanalClient, "pardon-all", "punish.pardonall", true, config)));
        brigadierEintraege.add(tunnel("timeout", new BridgeBefehlWeiterleitung(kanalClient, "timeout", "punish.timeout", true, config)));
        brigadierEintraege.add(tunnel("untimeout", new BridgeBefehlWeiterleitung(kanalClient, "untimeout", "punish.untimeout", false, config)));
        brigadierEintraege.add(tunnel("timeout-ip", new BridgeBefehlWeiterleitung(kanalClient, "timeout-ip", "punish.timeoutip", true, config)));
        brigadierEintraege.add(tunnel("untimeout-ip", new BridgeBefehlWeiterleitung(kanalClient, "untimeout-ip", "punish.untimeoutip", false, config)));
        brigadierEintraege.add(tunnel("timeout-all", new BridgeBefehlWeiterleitung(kanalClient, "timeout-all", "punish.timeoutall", true, config)));
        brigadierEintraege.add(tunnel("untimeout-all", new BridgeBefehlWeiterleitung(kanalClient, "untimeout-all", "punish.untimeoutall", false, config)));
        brigadierEintraege.add(tunnel("freeze", new BridgeBefehlWeiterleitung(kanalClient, "freeze", "punish.freeze", true, config)));
        brigadierEintraege.add(tunnel("unfreeze", new BridgeBefehlWeiterleitung(kanalClient, "unfreeze", "punish.unfreeze", false, config)));
        brigadierEintraege.add(tunnel("verstoss", new BridgeBefehlWeiterleitung(kanalClient, "verstoss", "punish.verstoss", false, config)));
        brigadierEintraege.add(tunnel("punish-note", new BridgeBefehlWeiterleitung(kanalClient, "punish-note", "punish.note", false, config)));
        brigadierEintraege.add(tunnel("punish-edit", new BridgeBefehlWeiterleitung(kanalClient, "punish-edit", "punish.edit", true, config)));
        brigadierEintraege.add(tunnel("warn", new BridgeBefehlWeiterleitung(kanalClient, "warn", "punish.warn", false, config)));
        brigadierEintraege.add(tunnel("punish-delete-ip", new BridgeBefehlWeiterleitung(kanalClient, "punish-delete-ip", "punish.deleteip", false, config)));
        brigadierEintraege.add(tunnel("punish-version", new BridgeBefehlWeiterleitung(kanalClient, "punish-version", "punish.version", false, config)));
        brigadierEintraege.add(tunnel("punish-help", new BridgeBefehlWeiterleitung(kanalClient, "punish-help", "punish.help", false, config)));
        brigadierEintraege.add(tunnel("chatfilter", new BridgeBefehlWeiterleitung(kanalClient, "chatfilter", "punish.chatfilter", false, config)));
        registriereDiagnose(config, kanalClient, muteCache);
        try {
            var datenDienst = new de.lightrixx.punish.bridge.gui.GuiDatenDienst(kanalClient, config);
            var guiManager = new de.lightrixx.punish.bridge.gui.GuiManager(this, kanalClient, config, datenDienst);
            var guiBefehl = new de.lightrixx.punish.bridge.gui.PunishGuiCommand(guiManager);
            PluginCommand punish = getCommand("punish-gui");
            if (punish != null) {
                punish.setExecutor(guiBefehl);
                punish.setTabCompleter(guiBefehl);
            } else {
                LOG.warn("Der Befehl 'punish-gui' ist nicht in der plugin.yml definiert.");
            }
            brigadierEintraege.add(eintragFuer("punish-gui", guiBefehl, false));
            getServer().getPluginManager().registerEvents(
                    new de.lightrixx.punish.bridge.gui.GuiListener(guiManager), this);
        } catch (Exception e) {
            LOG.warn("PunishGUI konnte nicht registriert werden (Bridge läuft ohne GUI weiter).", e);
        }

        if (brigadierVerfuegbar()) {
            try {
                registriereBrigadier(brigadierEintraege);
            } catch (Throwable t) {
                LOG.debug("Brigadier-TAB übersprungen (Legacy-TAB bleibt aktiv).", t);
            }
        }

        try {
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                var papiCache = new de.lightrixx.punish.bridge.papi.PlatzhalterCache(this, kanalClient);
                var expansion = new de.lightrixx.punish.bridge.papi.PunishExpansion(this, papiCache);
                if (expansion.register()) {
                    LOG.info("PlaceholderAPI-Expansion 'punish' registriert.");
                    papiExpansion = expansion;
                } else {
                    LOG.warn("PlaceholderAPI-Expansion 'punish' konnte nicht registriert werden.");
                }
            } else {
                LOG.info("PlaceholderAPI nicht gefunden – PAPI-Platzhalter bleiben deaktiviert.");
            }
        } catch (NoClassDefFoundError | Exception e) {
            LOG.debug("PlaceholderAPI-Hook übersprungen.", e);
        }

        try {
            kanalClient.sendeHello(config.serverName());
        } catch (Exception e) {
            LOG.debug("Initiales HELLO ohne Online-Spieler verworfen (Join-Heilung aktiv).", e);
        }
        try {
            String helloServer = config.serverName();
            long[] delays = {100L, 300L, 600L};
            for (long delay : delays) {
                planerLokal.spaeterAsync(() -> {
                    try {
                        if (muteCache.istJeSynchronisiert()) {
                            return;
                        }
                        kanalClient.sendeHello(helloServer);
                        LOG.debug("HELLO-Retry nach {} Ticks (noch kein MUTE_SYNC).", delay);
                    } catch (Exception e) {
                        LOG.debug("HELLO-Retry verworfen (vermutlich noch kein Träger).", e);
                    }
                }, delay);
            }
        } catch (Exception e) {
            LOG.debug("HELLO-Retry konnte nicht geplant werden.", e);
        }
        try {
            String refreshServer = config.serverName();
            planerLokal.intervallAsync(() -> {
                try {
                    kanalClient.sendeHello(refreshServer);
                } catch (Exception e) {
                    LOG.debug("Periodischer HELLO-Refresh verworfen.", e);
                }
                try {
                    if (muteCache.istJeSynchronisiert()) {
                        long alter = System.currentTimeMillis() - muteCache.letzteSyncMillis();
                        if (muteCache.letzteSyncMillis() > 0 && alter > 600000L) {
                            LOG.warn("Bridge-Cache seit {} Min ohne Proxy-Sync (Proxy-Neustart?). "
                                    + "Erzwinge Neu-Sync beim nächsten Spieler-Kontakt.",
                                    alter / 60000L);
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("Sync-Alter-Check fehlgeschlagen.", e);
                }
            }, 2400L, 2400L);
        } catch (Exception e) {
            LOG.debug("Periodischer HELLO-Refresh konnte nicht geplant werden.", e);
        }
        LOG.info("PunishSystemBridge by lightrixx aktiviert (Server '{}', Proxy-Kanal bereit).", config.serverName());
    }

    @Override
    public void onDisable() {
        if (chatfilterService != null) {
            try {
                chatfilterService.close();
            } catch (Exception e) {
                LOG.debug("Chatfilter konnte nicht beendet werden.", e);
            }
            chatfilterService = null;
        }
        if (papiExpansion != null) {
            try {
                papiExpansion.getClass().getMethod("unregister").invoke(papiExpansion);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.debug("PAPI-Expansion konnte nicht entladen werden.", e);
            }
            papiExpansion = null;
        }
        if (textEmpfaengerAktuell != null) {
            try {
                textEmpfaengerAktuell.abbrechen();
            } catch (Exception e) {
                LOG.debug("Text-Empfänger konnte beim Deaktivieren nicht abgebrochen werden.", e);
            }
            textEmpfaengerAktuell = null;
        }
        if (modellEmpfaengerAktuell != null) {
            try {
                modellEmpfaengerAktuell.abbrechen();
            } catch (Exception e) {
                LOG.debug("Modell-Empfänger konnte beim Deaktivieren nicht abgebrochen werden.", e);
            }
            modellEmpfaengerAktuell = null;
        }
        if (jarEmpfaengerAktuell != null) {
            try {
                jarEmpfaengerAktuell.abbrechen();
            } catch (Exception e) {
                LOG.debug("Update-Empfänger konnte beim Deaktivieren nicht abgebrochen werden.", e);
            }
            jarEmpfaengerAktuell = null;
        }
        if (kanal != null) {
            kanal.abmelden();
        }
        if (planer != null) {
            planer.alleAbbrechen();
        }
        try {
            getServer().getScheduler().cancelTasks(this);
        } catch (Exception e) {
            LOG.warn("Bukkit-Tasks konnten nicht abgebrochen werden.", e);
        }
        if (ioExecutor != null) {
            boolean ok = ioExecutor.herunterfahren(15L, TimeUnit.SECONDS);
            if (!ok) {
                LOG.warn("I/O-Queue wurde nicht leer – Datenverlust im lokalen Namens-Cache möglich.");
            }
        }
        LOG.info("PunishSystemBridge deaktiviert.");
    }

    private de.lightrixx.punish.bridge.chatfilter.ChatfilterService starteChatfilterFallsAktiv(
            BridgeKonfiguration config, KanalClient kanalClient) {
        var cfConfig = new de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration(this);
        try {
            cfConfig.laden();
        } catch (Exception e) {
            LOG.warn("Chatfilter-Konfiguration fehlgeschlagen, Filter bleibt aus.", e);
            return null;
        }
        if (cfConfig.hatLadeFehler()) {
            LOG.warn("Chatfilter-Konfiguration unlesbar, Filter bleibt aus (Fail-Closed).");
            return null;
        }
        stelleLaufzeitSchalterWiederHer(cfConfig);
        this.chatfilterKonfiguration = cfConfig;
        kiAusfallBereitsGemeldet = false;
        var dienst = baueChatfilterService(config, cfConfig, kanalClient);
        if (dienst == null && !cfConfig.aktiv()) {
            LOG.info("Chatfilter deaktiviert (chatfilter.aktiv=false).");
        }
        chatfilterDateiStand = berechneChatfilterDateiStand(cfConfig);
        return dienst;
    }

    static boolean archOhneOnnxNatives(String arch) {
        if (arch == null || arch.isBlank()) {
            return false;
        }
        String normiert = arch.trim().toLowerCase(java.util.Locale.ROOT);
        if (normiert.contains("riscv") || normiert.contains("mips") || normiert.contains("sparc")
                || normiert.contains("ppc") || normiert.contains("powerpc") || normiert.contains("s390")) {
            return true;
        }
        if (normiert.contains("arm") || normiert.contains("aarch")) {
            return true;
        }
        return false;
    }

    private void warneBeiArchOhneOnnxNatives() {
        try {
            String arch = System.getProperty("os.arch", "");
            if (archOhneOnnxNatives(arch)) {
                LOG.warn("Architektur '{}' ohne ONNX-Natives: KI-Modell kann nicht laden, nur Wortliste aktiv.", arch);
            }
        } catch (Exception e) {
            LOG.debug("Arch-Check übersprungen.", e);
        }
    }

    private void sendeKiAusfallEinmalig(String detail) {
        if (kiAusfallBereitsGemeldet) {
            return;
        }
        kiAusfallBereitsGemeldet = true;
        String text = detail == null || detail.isBlank()
                ? "Chatfilter-KI ausgefallen, nur Wortliste aktiv. Modelldatei und Logs prüfen."
                : detail;
        LOG.warn("KI-Ausfall: {} (nur Wortliste aktiv).", text);
        try {
            var mini = de.lightrixx.punish.util.MiniMessageUtil.miniMessage();
            net.kyori.adventure.text.Component nachricht;
            try {
                nachricht = mini.deserialize("<red>[PunishSystem] " + mini.escapeTags(text));
            } catch (Exception e) {
                nachricht = net.kyori.adventure.text.Component.text("[PunishSystem] " + text);
            }
            for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
                try {
                    if (online != null && online.hasPermission("punish.chatfilter")) {
                        online.sendMessage(nachricht);
                    }
                } catch (Exception e) {
                    LOG.trace("KI-Ausfall-Team-Hinweis konnte nicht gesendet werden.", e);
                }
            }
        } catch (Exception e) {
            LOG.debug("KI-Ausfall-Team-Broadcast fehlgeschlagen.", e);
        }
    }

    private void planeChatfilterReload() {
        if (!chatfilterReloadLaeuft.compareAndSet(false, true)) {
            LOG.debug("Chatfilter-Reload abgewiesen: läuft bereits.");
            return;
        }
        BukkitAuftragsPlaner planerLokal = planer;
        if (planerLokal == null) {
            chatfilterReloadLaeuft.set(false);
            return;
        }
        try {
            planerLokal.spaeterAsync(this::ladeChatfilterNeu, 0L);
        } catch (Exception e) {
            chatfilterReloadLaeuft.set(false);
            LOG.debug("Chatfilter-Reload konnte nicht geplant werden.", e);
        }
    }

    static boolean rebuildNoetig(String alterStand, String neuerStand, boolean dienstVorhanden) {
        if (!dienstVorhanden) {
            return true;
        }
        if (alterStand == null || neuerStand == null) {
            return true;
        }
        return !alterStand.equals(neuerStand);
    }

    private String berechneChatfilterDateiStand(
            de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration cfConfig) {
        try {
            java.util.Map<String, byte[]> stand = new java.util.LinkedHashMap<>();
            java.io.File configOrdner = new java.io.File(getDataFolder(), "config");
            leseInStand(stand, new java.io.File(configOrdner, "chatfilter.yml"), "chatfilter.yml");
            leseInStand(stand, new java.io.File(new java.io.File(getDataFolder(), TextEmpfaenger.SYNC_ORDNER),
                    "general.yml"), "config-sync-general.yml");
            if (cfConfig != null) {
                leseInStand(stand, cfConfig.dateiAufloesen(cfConfig.wortlisteDateiname()), "wortliste.txt");
                leseInStand(stand, cfConfig.dateiAufloesen(cfConfig.modellDateiname()), "modell_int8.onnx");
                leseInStand(stand, cfConfig.dateiAufloesen(cfConfig.vocabDateiname()), "vocab.txt");
            }
            if (stand.isEmpty()) {
                return "";
            }
            return de.lightrixx.punish.net.Protokoll.textPaketHash(stand);
        } catch (Exception e) {
            LOG.debug("Chatfilter-Datei-Stand konnte nicht berechnet werden.", e);
            return null;
        }
    }

    private static final long STAND_STREAMING_AB_BYTES = 8L * 1024L * 1024L;

    private static void leseInStand(java.util.Map<String, byte[]> stand, java.io.File datei,
                                    String schluessel) {
        try {
            if (datei != null && datei.isFile() && datei.length() > 0) {
                if (datei.length() > STAND_STREAMING_AB_BYTES) {
                    String hash = de.lightrixx.punish.chatfilter.TextHash.sha256DateiHex(
                            datei.toPath());
                    stand.put(schluessel, hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    stand.put(schluessel, java.nio.file.Files.readAllBytes(datei.toPath()));
                }
            }
        } catch (Exception e) {
            LOG.debug("Chatfilter-Datei {} konnte nicht gelesen werden.", schluessel, e);
        }
    }

    private void ladeChatfilterNeu() {
        try {
            BridgeKonfiguration config = bridgeKonfiguration;
            KanalClient kanalClient = kanal;
            if (config == null || kanalClient == null || enforcementListener == null) {
                LOG.warn("Chatfilter-Reload abgebrochen: Bridge nicht vollständig gestartet.");
                return;
            }
            if (config != null) {
                try {
                    config.laden();
                } catch (Exception e) {
                    LOG.warn("Bridge-Konfiguration konnte nicht neu geladen werden.", e);
                }
                try {
                    KanalClient aktuell = kanal;
                    if (aktuell != null) {
                        aktuell.setTunnelSecret(config.tunnelSecret());
                    }
                } catch (Exception e) {
                    LOG.debug("Tunnel-Secret konnte nach Reload nicht übernommen werden.", e);
                }
                try {
                    enforcementListener.invalidiereBlockliste();
                } catch (Exception e) {
                    LOG.debug("Blocklisten-Cache konnte nach Reload nicht invalidiert werden.", e);
                }
            }
            var cfConfig = new de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration(this);
            try {
                cfConfig.laden();
            } catch (Exception e) {
                LOG.warn("Chatfilter-Reload fehlgeschlagen, alte Konfiguration bleibt aktiv.", e);
                return;
            }
            if (cfConfig.hatLadeFehler()) {
                LOG.warn("Chatfilter-Reload fehlgeschlagen (chatfilter.yml nicht lesbar), "
                        + "alte Konfiguration bleibt aktiv.");
                return;
            }
            this.chatfilterKonfiguration = cfConfig;
            kiAusfallBereitsGemeldet = false;
            richteModellEmpfaengerEin(cfConfig);
            richteTextEmpfaengerEin();
            String dateiStand = berechneChatfilterDateiStand(cfConfig);
            if (!rebuildNoetig(chatfilterDateiStand, dateiStand, chatfilterService != null)) {
                LOG.debug("Chatfilter-Dateien unverändert, kein Neubau (Modell bleibt geladen).");
                aktualisiereModellStand();
                aktualisiereTextStand();
                return;
            }
            var neu = baueChatfilterService(config, cfConfig, kanalClient);
            var alt = chatfilterService;
            if (neu != null) {
                if (alt != null) {
                    neu.uebernimmSchalterVon(alt);
                }
                wendeAusstehendeSchalterAn(neu);
                enforcementListener.setChatfilter(neu);
                chatfilterService = neu;
                chatfilterDateiStand = dateiStand;
                LOG.info("Chatfilter neu geladen (Schwelle {}, Timeout {}ms).",
                        cfConfig.schwelle(), cfConfig.kiTimeoutMillis());
            } else if (!sollChatfilterGebautWerden(cfConfig)) {
                enforcementListener.setChatfilter(null);
                chatfilterService = null;
                chatfilterDateiStand = "";
                LOG.info("Chatfilter per Reload deaktiviert.");
            } else {
                LOG.warn("Chatfilter-Reload fehlgeschlagen, alte Konfiguration bleibt aktiv.");
                return;
            }
            if (alt != null) {
                try {
                    alt.close();
                } catch (Exception e) {
                    LOG.debug("Alter Chatfilter konnte nicht beendet werden.", e);
                }
            }
            aktualisiereModellStand();
            aktualisiereTextStand();
        } finally {
            chatfilterReloadLaeuft.set(false);
        }
    }

    private void richteModellSyncEin() {
        var cfConfig = chatfilterKonfiguration;
        if (cfConfig == null) {
            return;
        }
        richteModellEmpfaengerEin(cfConfig);
        aktualisiereModellStand();
        richteTextEmpfaengerEin();
        aktualisiereTextStand();
    }

    private void richteUpdateEmpfaengerEin() {
        KanalClient kanalClient = kanal;
        if (kanalClient == null) {
            return;
        }
        java.io.File ordner = new java.io.File(getDataFolder(), "update-pending");
        try {
            var empfaenger = new JarEmpfaenger(ordner, new JarEmpfaenger.Rueckmeldung() {
                @Override
                public void quittung(String variante, String version, boolean erfolg) {
                    try {
                        KanalClient aktuell = kanal;
                        if (aktuell != null) {
                            aktuell.sendeUpdateQuittung(variante, version, erfolg);
                        }
                    } catch (Exception e) {
                        LOG.debug("Update-Quittung fehlgeschlagen.", e);
                    }
                }

                @Override
                public void standMelden() {
                    try {
                        KanalClient aktuell = kanal;
                        if (aktuell != null) {
                            aktuell.sendeUpdateStand();
                        }
                    } catch (Exception e) {
                        LOG.debug("Update-Stand-Meldung fehlgeschlagen.", e);
                    }
                }
            });
            jarEmpfaengerAktuell = empfaenger;
            kanalClient.mitUpdateHandler(empfaenger);
        } catch (Exception e) {
            LOG.warn("Update-Empfänger konnte nicht eingerichtet werden.", e);
        }
    }

    private void richteTextEmpfaengerEin() {
        KanalClient kanalClient = kanal;
        var cfConfig = chatfilterKonfiguration;
        if (kanalClient == null || cfConfig == null) {
            return;
        }
        try {
            var alt = textEmpfaengerAktuell;
            if (alt != null) {
                alt.abbrechen();
            }
        } catch (Exception e) {
            LOG.debug("Alter Text-Empfänger konnte nicht abgebrochen werden.", e);
        }
        var empfaenger = new de.lightrixx.punish.bridge.TextEmpfaenger(this, cfConfig,
                new de.lightrixx.punish.bridge.TextEmpfaenger.Rueckmeldung() {
                    @Override
                    public void quittung(String paketHash, boolean erfolg) {
                        try {
                            KanalClient aktuell = kanal;
                            if (aktuell != null) {
                                aktuell.sendeTextQuittung(paketHash, erfolg);
                            }
                        } catch (Exception e) {
                            LOG.debug("Text-Quittung fehlgeschlagen.", e);
                        }
                        try {
                            aktualisiereTextStand();
                        } catch (Exception e) {
                            LOG.debug("Text-Stand konnte nicht aktualisiert werden.", e);
                        }
                    }

                    @Override
                    public void neuLaden() {
                        planeChatfilterReload();
                    }
                });
        textEmpfaengerAktuell = empfaenger;
        kanalClient.mitTextHandler(empfaenger);
    }

    private void aktualisiereTextStand() {
        KanalClient kanalClient = kanal;
        var cfConfig = chatfilterKonfiguration;
        if (kanalClient == null || cfConfig == null) {
            return;
        }
        try {
            java.util.Map<String, byte[]> stand = new java.util.LinkedHashMap<>();
            java.io.File syncOrdner = new java.io.File(getDataFolder(),
                    de.lightrixx.punish.bridge.TextEmpfaenger.SYNC_ORDNER);
            for (String name : new String[]{"general.yml", "messages.yml", "screens.yml", "broadcasts.yml",
                    "gui.yml"}) {
                java.io.File datei = new java.io.File(syncOrdner, name);
                if (datei.isFile() && datei.length() > 0) {
                    stand.put(name, java.nio.file.Files.readAllBytes(datei.toPath()));
                }
            }
            java.io.File wortliste = cfConfig.dateiAufloesen(cfConfig.wortlisteDateiname());
            if (wortliste.isFile() && wortliste.length() > 0) {
                stand.put("wortliste.txt", java.nio.file.Files.readAllBytes(wortliste.toPath()));
            }
            if (stand.isEmpty()) {
                kanalClient.setTextStand("");
                return;
            }
            kanalClient.setTextStand(de.lightrixx.punish.net.Protokoll.textPaketHash(stand));
        } catch (Exception e) {
            LOG.debug("Text-Stand konnte nicht berechnet werden.", e);
        }
    }

    private void richteModellEmpfaengerEin(
            de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration cfConfig) {
        KanalClient kanalClient = kanal;
        if (kanalClient == null || cfConfig == null) {
            return;
        }
        try {
            var alt = modellEmpfaengerAktuell;
            if (alt != null) {
                alt.abbrechen();
            }
        } catch (Exception e) {
            LOG.debug("Alter Modell-Empfänger konnte nicht abgebrochen werden.", e);
        }
        var empfaenger = new de.lightrixx.punish.bridge.chatfilter.ModellEmpfaenger(cfConfig,
                new de.lightrixx.punish.bridge.chatfilter.ModellEmpfaenger.Rueckmeldung() {
                    @Override
                    public void quittung(String dateiname, boolean erfolg) {
                        try {
                            KanalClient aktuell = kanal;
                            if (aktuell != null) {
                                aktuell.sendeModellQuittung(dateiname, erfolg);
                            }
                        } catch (Exception e) {
                            LOG.debug("Modell-Quittung fehlgeschlagen.", e);
                        }
                        try {
                            aktualisiereModellStand();
                        } catch (Exception e) {
                            LOG.debug("Modell-Stand konnte nicht aktualisiert werden.", e);
                        }
                    }

                    @Override
                    public void neuLaden() {
                        planeChatfilterReload();
                    }
                });
        modellEmpfaengerAktuell = empfaenger;
        kanalClient.mitModellHandler(empfaenger);
    }

    private void aktualisiereModellStand() {
        KanalClient kanalClient = kanal;
        var cfConfig = chatfilterKonfiguration;
        if (kanalClient == null || cfConfig == null) {
            return;
        }
        kanalClient.setModellStand(hashDateiStill(cfConfig, cfConfig.modellDateiname()),
                hashDateiStill(cfConfig, cfConfig.vocabDateiname()));
    }

    private static String hashDateiStill(
            de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration cfConfig, String name) {
        try {
            java.io.File datei = cfConfig.dateiAufloesen(name);
            if (datei == null || !datei.isFile()) {
                return "";
            }
            return de.lightrixx.punish.chatfilter.TextHash.sha256DateiHex(datei.toPath());
        } catch (Exception e) {
            LOG.debug("Modell-Hash konnte nicht berechnet werden.", e);
            return "";
        }
    }

    private de.lightrixx.punish.bridge.chatfilter.ChatfilterService baueChatfilterService(
            BridgeKonfiguration config,
            de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration cfConfig,
            KanalClient kanalClient) {
        if (!sollChatfilterGebautWerden(cfConfig)) {
            return null;
        }
        de.lightrixx.punish.chatfilter.WortlistenFilter wortliste = null;
        try {
            var zeilen = cfConfig.leseZeilen(cfConfig.dateiAufloesen(cfConfig.wortlisteDateiname()));
            wortliste = new de.lightrixx.punish.chatfilter.WortlistenFilter(zeilen);
            LOG.info("Chatfilter-Wortliste geladen ({} Einträge).", wortliste.eintraege().size());
        } catch (Exception e) {
            LOG.warn("Wortliste konnte nicht geladen werden, Stufe 1 bleibt aus.", e);
        }
        de.lightrixx.punish.chatfilter.ToxizitaetsModell modell = null;
        boolean brauchtKi = java.util.Arrays.stream(
                        de.lightrixx.punish.chatfilter.FilterKanal.values())
                .anyMatch(kanal -> cfConfig.modusFuer(kanal)
                        == de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration.Modus.KI);
        if (brauchtKi) {
            try {
                var vocabZeilen = cfConfig.leseZeilen(cfConfig.dateiAufloesen(cfConfig.vocabDateiname()));
                var tokenizer = de.lightrixx.punish.chatfilter.BertWordPieceTokenizer
                        .ausZeilen(vocabZeilen, 128);
                modell = de.lightrixx.punish.bridge.chatfilter.OnnxToxicityService.laden(
                        cfConfig.dateiAufloesen(cfConfig.modellDateiname()), tokenizer,
                        cfConfig.maxZeichen());
            } catch (de.lightrixx.punish.chatfilter.ChatfilterAusfall e) {
                LOG.warn("KI-Modell konnte nicht geladen werden, nur Wortliste aktiv.", e);
            } catch (Exception e) {
                LOG.warn("KI-Modell konnte nicht geladen werden, nur Wortliste aktiv.", e);
            }
        }
        if (brauchtKi && modell == null) {
            sendeKiAusfallEinmalig("Chatfilter-KI fehlt/defekt, nur Wortliste aktiv. Modelldatei und Logs prüfen.");
        }
        if (wortliste == null && modell == null) {
            LOG.warn("Chatfilter aktiv, aber weder Wortliste noch KI verfügbar – Filter bleibt aus.");
            return null;
        }
        String serverName = config.serverName();
        var dienst = new de.lightrixx.punish.bridge.chatfilter.ChatfilterService(
                cfConfig, wortliste, modell,
                (uuid, name, text, treffer) -> {
                    try {
                        boolean klartext = true;
                        try {
                            klartext = cfConfig.klartextUbertragen();
                        } catch (Exception e) {
                            klartext = true;
                        }
                        String gekuerzt = "";
                        if (klartext) {
                            gekuerzt = text == null ? "" : text;
                            if (gekuerzt.length() > 400) {
                                gekuerzt = gekuerzt.substring(0, 400);
                            }
                        }
                        kanalClient.sendeChatfilterTreffer(new de.lightrixx.punish.net.Protokoll.ChatfilterTreffer(
                                uuid, name, treffer.kanal().name(), treffer.quelle().name(),
                                treffer.bewertung(), treffer.textHash(), gekuerzt,
                                System.currentTimeMillis()));
                    } catch (Exception e) {
                        LOG.debug("Chatfilter-Treffer konnte nicht gemeldet werden.", e);
                    }
                });
        enforcementListener.setChatfilter(dienst);
        LOG.info("Chatfilter aktiviert (Server '{}', Schwelle {}, Timeout {}ms).",
                serverName, cfConfig.schwelle(), cfConfig.kiTimeoutMillis());
        return dienst;
    }

    private boolean sollChatfilterGebautWerden(
            de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration cfConfig) {
        boolean dateiAktiv;
        try {
            dateiAktiv = cfConfig.aktiv();
        } catch (Exception e) {
            dateiAktiv = false;
        }
        Boolean ausstehend;
        synchronized (chatfilterSchalterSperre) {
            ausstehend = ausstehenderFilterSchalter;
        }
        if (ausstehend == null) {
            try {
                ausstehend = cfConfig.leseLaufzeitFilter();
            } catch (Exception e) {
                ausstehend = null;
            }
        }
        var alt = chatfilterService;
        Boolean altOverride = alt == null ? null : alt.filterAktivOverride();
        return sollChatfilterGebautWerden(dateiAktiv, ausstehend, altOverride);
    }

    static boolean sollChatfilterGebautWerden(boolean dateiAktiv, Boolean ausstehendFilter,
                                                     Boolean altOverride) {
        if (Boolean.TRUE.equals(ausstehendFilter)) {
            return true;
        }
        if (Boolean.FALSE.equals(ausstehendFilter)) {
            return dateiAktiv;
        }
        if (Boolean.TRUE.equals(altOverride)) {
            return true;
        }
        return dateiAktiv;
    }

    private void wendeChatfilterSteuerungAn(
            de.lightrixx.punish.net.Protokoll.ChatfilterSteuerung steuerung) {
        if (steuerung == null) {
            return;
        }
        var dienst = chatfilterService;
        if (dienst == null) {
            synchronized (chatfilterSchalterSperre) {
                if (steuerung.filterAn() != null) {
                    ausstehenderFilterSchalter = steuerung.filterAn();
                }
                if (steuerung.kiAn() != null) {
                    ausstehenderKiSchalter = steuerung.kiAn();
                }
            }
            persistiereLaufzeitSchalter(steuerung.filterAn(), steuerung.kiAn());
            LOG.info("Chatfilter-Steuerung empfangen (Filter={}, KI={}), baue Filter auf.",
                    steuerung.filterAn(), steuerung.kiAn());
            planeChatfilterReload();
            return;
        }
        if (steuerung.filterAn() != null) {
            dienst.setFilterAktiv(steuerung.filterAn());
        }
        if (steuerung.kiAn() != null) {
            dienst.setKiAktiv(steuerung.kiAn());
        }
        persistiereLaufzeitSchalter(dienst.filterAktivOverride(), dienst.kiAktivOverride());
        LOG.info("Chatfilter-Steuerung angewendet (Filter={}, KI={}).",
                steuerung.filterAn(), steuerung.kiAn());
    }

    private void stelleLaufzeitSchalterWiederHer(
            de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration cfConfig) {
        if (cfConfig == null) {
            return;
        }
        try {
            synchronized (chatfilterSchalterSperre) {
                if (ausstehenderFilterSchalter == null) {
                    ausstehenderFilterSchalter = cfConfig.leseLaufzeitFilter();
                }
                if (ausstehenderKiSchalter == null) {
                    ausstehenderKiSchalter = cfConfig.leseLaufzeitKi();
                }
            }
        } catch (Exception e) {
            LOG.debug("Laufzeit-Schalter konnten nicht wiederhergestellt werden.", e);
        }
    }

    private void persistiereLaufzeitSchalter(Boolean filter, Boolean ki) {
        try {
            var cfConfig = chatfilterKonfiguration;
            Boolean zielFilter = filter;
            Boolean zielKi = ki;
            if (cfConfig != null) {
                if (zielFilter == null) {
                    zielFilter = cfConfig.leseLaufzeitFilter();
                }
                if (zielKi == null) {
                    zielKi = cfConfig.leseLaufzeitKi();
                }
                synchronized (chatfilterSchalterSperre) {
                    if (ausstehenderFilterSchalter != null && zielFilter == null) {
                        zielFilter = ausstehenderFilterSchalter;
                    }
                    if (ausstehenderKiSchalter != null && zielKi == null) {
                        zielKi = ausstehenderKiSchalter;
                    }
                }
                if (!cfConfig.speichereLaufzeitSchalter(zielFilter, zielKi)) {
                    LOG.warn("Laufzeit-Schalter gelten nur bis zum nächsten Neustart "
                            + "(chatfilter.yml nicht schreibbar).");
                }
            }
        } catch (Exception e) {
            LOG.debug("Laufzeit-Schalter-Persistenz übersprungen.", e);
        }
    }

    private void wendeAusstehendeSchalterAn(
            de.lightrixx.punish.bridge.chatfilter.ChatfilterService dienst) {
        synchronized (chatfilterSchalterSperre) {
            if (ausstehenderFilterSchalter != null) {
                dienst.setFilterAktiv(ausstehenderFilterSchalter);
                ausstehenderFilterSchalter = null;
            }
            if (ausstehenderKiSchalter != null) {
                dienst.setKiAktiv(ausstehenderKiSchalter);
                ausstehenderKiSchalter = null;
            }
        }
    }

    private static boolean fehltZeilenumbruch(java.nio.file.Path pfad) throws java.io.IOException {
        try (java.nio.channels.FileChannel kanal = java.nio.channels.FileChannel.open(pfad,
                java.nio.file.StandardOpenOption.READ)) {
            long groesse = kanal.size();
            if (groesse <= 0) {
                return false;
            }
            java.nio.ByteBuffer puffer = java.nio.ByteBuffer.allocate(1);
            kanal.position(groesse - 1);
            kanal.read(puffer);
            byte letztes = puffer.array()[0];
            return letztes != '\n' && letztes != '\r';
        }
    }

    private void fuegeChatfilterWortHinzu(String wort) {
        var gueltig = de.lightrixx.punish.chatfilter.WortlistenFilter.gueltigerEintrag(wort);
        if (gueltig.isEmpty()) {
            LOG.warn("Chatfilter-Wort abgewiesen (ungültig).");
            return;
        }
        var cfConfig = chatfilterKonfiguration;
        if (cfConfig == null) {
            cfConfig = new de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration(this);
            try {
                cfConfig.laden();
            } catch (Exception e) {
                LOG.warn("Chatfilter-Wort abgewiesen (Konfiguration fehlt).", e);
                return;
            }
            chatfilterKonfiguration = cfConfig;
        }
        java.io.File datei;
        try {
            datei = cfConfig.dateiAufloesen(cfConfig.wortlisteDateiname());
            var eltern = datei.getParentFile();
            if (eltern != null) {
                eltern.mkdirs();
            }
            java.nio.file.Path pfad = datei.toPath();
            if (java.nio.file.Files.exists(pfad) && fehltZeilenumbruch(pfad)) {
                java.nio.file.Files.writeString(pfad, System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            }
            java.nio.file.Files.writeString(pfad, gueltig.get()
                    + System.lineSeparator(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("Chatfilter-Wort konnte nicht gespeichert werden.", e);
            return;
        }
        var dienst = chatfilterService;
        if (dienst == null) {
            LOG.info("Chatfilter-Wort '{}' gespeichert (aktiv ab Start/Reload).", gueltig.get());
            return;
        }
        try {
            var zeilen = cfConfig.leseZeilen(datei);
            dienst.aktualisiereWortliste(
                    new de.lightrixx.punish.chatfilter.WortlistenFilter(zeilen));
            LOG.info("Chatfilter-Wort '{}' übernommen (sofort aktiv).", gueltig.get());
        } catch (Exception e) {
            LOG.warn("Chatfilter-Wort gespeichert, Live-Update fehlgeschlagen (Reload nötig).", e);
        }
    }

    private void migriereLegacyOrdner(File neu) {
        try {
            File eltern = neu.getParentFile();
            if (eltern == null) {
                return;
            }
            if (neu.exists() && neu.isDirectory() && neu.list() != null && neu.list().length > 0) {
                return;
            }
            String[] legacyNamen = {"PunishBridge", "PunishSystem-Bridge"};
            for (String legacyName : legacyNamen) {
                File alt = new File(eltern, legacyName);
                if (alt.equals(neu) || !alt.isDirectory()) {
                    continue;
                }
                try {
                    java.nio.file.Files.move(alt.toPath(), neu.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    LOG.info("Datenordner '{}' nach '{}' migriert.", alt.getName(), neu.getName());
                    return;
                } catch (Exception moveFehler) {
                    LOG.debug("Atomarer Move fehlgeschlagen, versuche Kopie.", moveFehler);
                    try {
                        kopiereOrdnerRekursiv(alt.toPath(), neu.toPath());
                        LOG.info("Datenordner '{}' nach '{}' kopiert (alt bleibt als Backup).",
                                alt.getName(), neu.getName());
                        return;
                    } catch (Exception kopierFehler) {
                        LOG.warn("Legacy-Datenordner '{}' konnte nicht migriert werden.", alt.getName(), kopierFehler);
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Legacy-Migration übersprungen.", e);
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
                    java.nio.file.Files.createDirectories(zielPfad.getParent());
                    java.nio.file.Files.copy(pfad, zielPfad, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String liesPluginVersion() {
        try {
            String version = getPluginMeta().getVersion();
            return version == null || version.isBlank() ? "unbekannt" : version.trim();
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    private String liesPlattform() {
        try {
            String name = getServer().getName();
            return name == null || name.isBlank() ? "unbekannt" : name.trim();
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    private void registriereDiagnose(BridgeKonfiguration config, KanalClient kanalClient, MuteCache muteCache) {
        try {
            BridgeDiagnosticsBefehl diagnose = new BridgeDiagnosticsBefehl(config, kanalClient, muteCache,
                    this::liesPluginVersion, kanalClient::proxyPluginVersion, this::chatfilterInfoMoment);
            PluginCommand befehl = getCommand("punish-diagnostics");
            if (befehl == null) {
                LOG.warn("Der Befehl 'punish-diagnostics' ist nicht in der plugin.yml definiert.");
                return;
            }
            befehl.setExecutor(diagnose);
            befehl.setTabCompleter(diagnose);
        } catch (Exception e) {
            LOG.warn("Diagnose-Befehl konnte nicht registriert werden (Bridge läuft ohne Diagnose weiter).", e);
        }
    }

    private BridgeDiagnosticsBefehl.ChatfilterInfo chatfilterInfoMoment() {
        var cfConfig = chatfilterKonfiguration;
        var dienst = chatfilterService;
        if (cfConfig == null) {
            return new BridgeDiagnosticsBefehl.ChatfilterInfo(false, "deaktiviert");
        }
        boolean dateiAktiv;
        try {
            dateiAktiv = cfConfig.aktiv();
        } catch (Exception e) {
            dateiAktiv = false;
        }
        if (dienst == null) {
            return dateiAktiv
                    ? new BridgeDiagnosticsBefehl.ChatfilterInfo(true, "aktiv, aber Dienst fehlt (Wortliste/Modell fehlt?)")
                    : new BridgeDiagnosticsBefehl.ChatfilterInfo(false, "deaktiviert");
        }
        try {
            if (dienst.kiDefekt()) {
                return new BridgeDiagnosticsBefehl.ChatfilterInfo(true,
                        "aktiv, aber KI defekt (nur Wortliste, " + dienst.kiFehlerFolge()
                                + " Fehler in Folge) – Modell prüfen");
            }
        } catch (Exception e) {
            LOG.debug("KI-Defekt-Check fehlgeschlagen.", e);
        }
        return new BridgeDiagnosticsBefehl.ChatfilterInfo(true, "aktiv");
    }

    private void registriereWeiterleitung(String name, ReportWeiterleitung weiterleitung) {
        if (name == null || name.isBlank()) {
            LOG.warn("Der Befehl konnte nicht registriert werden, da kein Name angegeben ist.");
            return;
        }
        PluginCommand befehl = getCommand(name);
        if (befehl == null) {
            LOG.warn("Der Befehl '{}' ist nicht in der plugin.yml definiert.", name);
            return;
        }
        befehl.setExecutor(weiterleitung);
        befehl.setTabCompleter(weiterleitung);
    }

    private BridgeBrigadierBefehle.Eintrag tunnel(String name, BridgeBefehlWeiterleitung weiterleitung) {
        registriereTunnel(name, weiterleitung);
        return eintragFuer(name, weiterleitung, true);
    }

    @SuppressWarnings("null")
    private BridgeBrigadierBefehle.Eintrag eintragFuer(String name, TunnelVorschlag tunnel,
                                                      boolean mitNamensraum) {
        java.util.List<String> aliase = new java.util.ArrayList<>();
        String beschreibung = "";
        try {
            PluginCommand befehl = getCommand(name);
            if (befehl != null) {
                if (befehl.getAliases() != null) {
                    for (String alias : befehl.getAliases()) {
                        if (alias != null && ! alias.isBlank()) {
                            aliase.add(alias.trim());
                        }
                    }
                }
                if (befehl.getDescription() != null) {
                    beschreibung = befehl.getDescription();
                }
            }
        } catch (Exception e) {
            LOG.debug("Befehls-Metadaten für '{}' konnten nicht gelesen werden.", name, e);
        }
        if (mitNamensraum) {
            java.util.List<String> erweitert = new java.util.ArrayList<>(aliase);
            erweitert.add("punish:" + name);
            for (String alias : aliase) {
                erweitert.add("punish:" + alias);
            }
            aliase = erweitert;
        }
        return new BridgeBrigadierBefehle.Eintrag(name, aliase, beschreibung, tunnel);
    }

    private static boolean brigadierVerfuegbar() {
        try {
            Class.forName("io.papermc.paper.command.brigadier.Commands");
            Class.forName("io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void registriereBrigadier(java.util.List<BridgeBrigadierBefehle.Eintrag> eintraege) {
        getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> BridgeBrigadierBefehle.registrieren(event.registrar(), eintraege));
        LOG.info("Brigadier-TAB für {} Befehle registriert.", eintraege.size());
    }

    private void registriereTunnel(String name, BridgeBefehlWeiterleitung tunnel) {
        if (name == null || name.isBlank()) {
            LOG.warn("Der Tunnel-Befehl konnte nicht registriert werden, da kein Name angegeben ist.");
            return;
        }
        if (tunnel != null) {
            try {
                tunnel.mitStrafCaches(banCache, muteCache, timeoutCache, ipMuteCache);
            } catch (Exception e) {
                LOG.debug("Straf-Caches konnten nicht verdrahtet werden.", e);
            }
            try {
                tunnel.mitSpielerCache(spielerCache);
            } catch (Exception e) {
                LOG.debug("Spieler-Cache konnte nicht verdrahtet werden.", e);
            }
        }
        PluginCommand befehl = getCommand(name);
        if (befehl == null) {
            LOG.warn("Der Tunnel-Befehl '{}' ist nicht in der plugin.yml definiert.", name);
            return;
        }
        befehl.setExecutor(tunnel);
        befehl.setTabCompleter(tunnel);
    }
}
