package de.lightrixx.punish.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpTimeoutRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.TimeoutRecord;
import de.lightrixx.punish.model.VerpassteNachricht;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.service.AutoBannService;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.FreezeService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.IpMuteService;
import de.lightrixx.punish.service.IpTimeoutService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ScreenTexte;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.VerpassteBroadcastsRepository;
import de.lightrixx.punish.util.ZeitFormat;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class StrafListener {

    private static final Logger LOG = LoggerFactory.getLogger(StrafListener.class);

    private final ProxyServer proxy;
    private final Logger logger;
    private final PlayerCache playerCache;
    private final BanService banService;
    private final IpBanService ipBanService;
    private final TimeoutService timeoutService;
    private final MuteService muteService;
    private final IpMuteService ipMuteService;
    private final IpTimeoutService ipTimeoutService;
    private final AutoBannService autoBannService;
    private final VelocityKonfiguration config;
    private final MessageService messageService;
    private final AuftragsPlaner planer;
    private final BrueckenKanal kanal;
    private final GuiBrueckenDienst guiDienst;
    private final BanRepository banRepository;
    private volatile BroadcastService altWacheBroadcast;
    private volatile de.lightrixx.punish.service.ProtectionService altWacheProtection;
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> altWacheCooldown =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile PlatzhalterDienst platzhalterDienst;
    private volatile ChatfilterStaffelService chatfilterStaffel;
    private volatile ModellVerteilung modellVerteilung;
    private volatile TextVerteilung textVerteilung;
    private volatile UpdateVerteilung updateVerteilung;
    private volatile VerpassteBroadcastsRepository verpasstAblage;
    private volatile SpielerVerzeichnis verpasstVerzeichnis;
    private volatile BroadcastService verpasstBroadcast;
    private volatile FreezeService freezeService;
    private record LoginFenster(Player spieler, long seit, long bis) {
    }

    private final java.util.concurrent.ConcurrentHashMap<UUID, LoginFenster> loginBasis =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, TunnelRateBegrenzer> befehlTempo =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, TunnelRateBegrenzer> metaTempo =
            new java.util.concurrent.ConcurrentHashMap<>();

    static final class TunnelRateBegrenzer {
        private final double proSekunde;
        private final double kapazitaet;
        private double tokens;
        private long letzteMillis;
        private final java.util.function.LongSupplier uhr;

        TunnelRateBegrenzer(double proSekunde, double kapazitaet,
                            java.util.function.LongSupplier uhr) {
            this.proSekunde = proSekunde;
            this.kapazitaet = kapazitaet;
            this.tokens = kapazitaet;
            this.uhr = uhr;
            this.letzteMillis = uhr.getAsLong();
        }

        synchronized boolean versuche() {
            long jetzt = uhr.getAsLong();
            long vergangen = Math.max(0L, jetzt - letzteMillis);
            letzteMillis = jetzt;
            tokens = Math.min(kapazitaet, tokens + vergangen * proSekunde / 1000.0d);
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return true;
            }
            return false;
        }
    }

    @SuppressWarnings("checkstyle:parameternumber")
    public StrafListener(ProxyServer proxy, Logger logger, PlayerCache playerCache,
                         BanService banService, IpBanService ipBanService,
                         TimeoutService timeoutService, MuteService muteService,
                         AutoBannService autoBannService, VelocityKonfiguration config,
                         MessageService messageService, AuftragsPlaner planer,
                         BanRepository banRepository, BrueckenKanal kanal) {
        this(proxy, logger, playerCache, banService, ipBanService, timeoutService, muteService,
                null, null, autoBannService, config, messageService, planer, banRepository, kanal);
    }

    @SuppressWarnings("checkstyle:parameternumber")
    public StrafListener(ProxyServer proxy, Logger logger, PlayerCache playerCache,
                         BanService banService, IpBanService ipBanService,
                         TimeoutService timeoutService, MuteService muteService,
                         IpMuteService ipMuteService, IpTimeoutService ipTimeoutService,
                         AutoBannService autoBannService, VelocityKonfiguration config,
                         MessageService messageService, AuftragsPlaner planer,
                         BanRepository banRepository, BrueckenKanal kanal) {
        this(proxy, logger, playerCache, banService, ipBanService, timeoutService, muteService,
                ipMuteService, ipTimeoutService, autoBannService, config, messageService,
                planer, banRepository, kanal, null);
    }

    @SuppressWarnings("checkstyle:parameternumber")
    public StrafListener(ProxyServer proxy, Logger logger, PlayerCache playerCache,
                         BanService banService, IpBanService ipBanService,
                         TimeoutService timeoutService, MuteService muteService,
                         IpMuteService ipMuteService, IpTimeoutService ipTimeoutService,
                         AutoBannService autoBannService, VelocityKonfiguration config,
                         MessageService messageService, AuftragsPlaner planer,
                         BanRepository banRepository, BrueckenKanal kanal,
                         GuiBrueckenDienst guiDienst) {
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.playerCache = Objects.requireNonNull(playerCache, "Der PlayerCache darf nicht null sein.");
        this.banService = Objects.requireNonNull(banService, "Der BanService darf nicht null sein.");
        this.ipBanService = Objects.requireNonNull(ipBanService, "Der IpBanService darf nicht null sein.");
        this.timeoutService = Objects.requireNonNull(timeoutService, "Der TimeoutService darf nicht null sein.");
        this.muteService = Objects.requireNonNull(muteService, "Der MuteService darf nicht null sein.");
        this.ipMuteService = ipMuteService;
        this.ipTimeoutService = ipTimeoutService;
        this.autoBannService = Objects.requireNonNull(autoBannService, "Der AutoBannService darf nicht null sein.");
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.messageService = Objects.requireNonNull(messageService, "Der MessageService darf nicht null sein.");
        this.planer = Objects.requireNonNull(planer, "Der Planer darf nicht null sein.");
        this.kanal = Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        this.guiDienst = guiDienst;
        this.banRepository = banRepository;
    }

    public void setAltWache(BroadcastService broadcastService,
                            de.lightrixx.punish.service.ProtectionService protectionService) {
        this.altWacheBroadcast = broadcastService;
        this.altWacheProtection = protectionService;
    }

    public void setPlatzhalterDienst(PlatzhalterDienst platzhalterDienst) {
        this.platzhalterDienst = platzhalterDienst;
    }

    public void setChatfilterStaffel(ChatfilterStaffelService chatfilterStaffel) {
        this.chatfilterStaffel = chatfilterStaffel;
    }

    public void setFreezeService(FreezeService freezeService) {
        this.freezeService = freezeService;
    }

    public void setModellVerteilung(ModellVerteilung modellVerteilung) {
        this.modellVerteilung = modellVerteilung;
    }

    public void setTextVerteilung(TextVerteilung textVerteilung) {
        this.textVerteilung = textVerteilung;
    }

    public void setUpdateVerteilung(UpdateVerteilung updateVerteilung) {
        this.updateVerteilung = updateVerteilung;
    }

    public void setVerpasstNachsendung(VerpassteBroadcastsRepository ablage,
                                       SpielerVerzeichnis verzeichnis,
                                       BroadcastService broadcastService) {
        this.verpasstAblage = ablage;
        this.verpasstVerzeichnis = verzeichnis;
        this.verpasstBroadcast = broadcastService;
    }

    @Subscribe(priority = -32767)
    public void onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }
        Player spieler;
        UUID uuid;
        String name;
        try {
            spieler = event.getPlayer();
            uuid = spieler.getUniqueId();
            name = spieler.getUsername();
        } catch (Exception e) {
            logger.debug("Login-Event ohne Spielerdaten, überspringe Sperrprüfung.", e);
            return;
        }
        String ip;
        long jetzt = System.currentTimeMillis();
        try {
            ip = ipVon(spieler);
        } catch (Exception e) {
            ip = "unbekannt";
        }

        try {
            migriereVorab(uuid, name, jetzt);
        } catch (Exception e) {
            logger.debug("Vorab-Migration (Login) fehlgeschlagen, fahre mit Sperrprüfung fort.", e);
        }

        Component sperre;
        boolean failClosed = true;
        try {
            failClosed = config.schutzFailClosed();
        } catch (Exception e) {
            logger.debug("Fail-Closed-Lookup fehlgeschlagen, nutze fail-closed=true.", e);
        }
        try {
            sperre = loginSperre(uuid, name, ip, jetzt);
        } catch (Exception e) {
            logger.warn("Sperrprüfung beim Login für '{}' fehlgeschlagen ({}).", name,
                    failClosed ? "fail-closed, Login verweigert" : "fail-open, Login erlaubt", e);
            sperre = failClosed ? fallbackSperre() : null;
        }
        if (sperre != null) {
            try {
                event.setResult(com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(sperre));
            } catch (Exception e) {
                logger.warn("Login-Deny für '{}' fehlgeschlagen, trenne ersatzweise.", name, e);
                try {
                    spieler.disconnect(sperre);
                } catch (Exception ex) {
                    logger.debug("Ersatz-Disconnect beim Login fehlgeschlagen.", ex);
                }
            }
            return;
        }
        long basis = jetzt;
        try {
            basis = playerCache.finden(uuid).map(CachedPlayer::zuletztGesehen).orElse(jetzt);
            if (basis > jetzt) {
                basis = jetzt;
            }
        } catch (Exception e) {
            logger.debug("Login-Basis für '{}' fehlgeschlagen, nutze Login-Zeit.", name, e);
        }
        try {
            playerCache.aktualisieren(new CachedPlayer(uuid, name, ip, jetzt));
        } catch (Exception e) {
            logger.debug("PlayerCache-Update beim Login für '{}' fehlgeschlagen.", name, e);
        }
        pruefeAltWache(uuid, name, ip);
        loginBasis.put(uuid, new LoginFenster(spieler, basis, jetzt));
    }

    private void pruefeAltWache(UUID uuid, String name, String ip) {
        BanRepository banns = this.banRepository;
        BroadcastService broadcast = this.altWacheBroadcast;
        if (banns == null || broadcast == null || ip == null || ip.isBlank()
                || "unbekannt".equalsIgnoreCase(ip.trim())) {
            return;
        }
        try {
            if (!config.altWacheAktiv()) {
                return;
            }
        } catch (Exception e) {
            logger.debug("Alt-Wache-Aktiv-Lookup fehlgeschlagen, überspringe Prüfung.", e);
            return;
        }
        long jetzt = System.currentTimeMillis();
        long cooldownMillis = Math.max(1L, config.altWacheCooldownMinuten()) * 60_000L;
        Long letzter = altWacheCooldown.get(uuid);
        if (letzter != null && jetzt - letzter < cooldownMillis) {
            return;
        }
        planer.aufgabeAsync(() -> {
            try {
                List<CachedPlayer> kandidaten = playerCache.findeNachIpMitHistorie(ip);
                if (kandidaten == null || kandidaten.isEmpty()) {
                    return;
                }
                long zeit = System.currentTimeMillis();
                List<String> aktiveAlts = new ArrayList<>();
                for (CachedPlayer kandidat : kandidaten) {
                    if (kandidat == null || uuid.equals(kandidat.uuid())) {
                        continue;
                    }
                    if (istAltGeschuetzt(kandidat.uuid())) {
                        continue;
                    }
                    List<BanRecord> liste = banns.alleFuer(kandidat.uuid());
                    if (liste == null || liste.isEmpty()) {
                        continue;
                    }
                    boolean aktiv = false;
                    for (BanRecord bann : liste) {
                        if (bann != null && bann.istAktiv(zeit)) {
                            aktiv = true;
                            break;
                        }
                    }
                    if (aktiv) {
                        aktiveAlts.add(kandidat.name());
                    }
                }
                if (aktiveAlts.isEmpty()) {
                    return;
                }
                Long vorher = altWacheCooldown.get(uuid);
                if (vorher == null ? altWacheCooldown.putIfAbsent(uuid, zeit) != null
                        : zeit - vorher < cooldownMillis || !altWacheCooldown.replace(uuid, vorher, zeit)) {
                    return;
                }
                Map<String, String> platzhalter = new HashMap<>();
                platzhalter.put("spieler", name);
                platzhalter.put("alt", String.join(", ", aktiveAlts));
                platzhalter.put("anzahl", String.valueOf(aktiveAlts.size()));
                platzhalter.put("ip", maskiereIp(ip));
                broadcast.senden(de.lightrixx.punish.core.MessageKeys.BROADCAST_ALT_WACHE, platzhalter);
            } catch (Exception e) {
                logger.debug("Alt-Wache-Prüfung für '{}' fehlgeschlagen.", name, e);
            }
        });
    }

    void bereinigeAltWacheCooldowns(long jetzt) {
        long cooldownMillis = Math.max(1L, config.altWacheCooldownMinuten()) * 60_000L;
        altWacheCooldown.values().removeIf(zeit -> jetzt - zeit >= cooldownMillis);
    }

    private boolean istAltGeschuetzt(UUID zielUuid) {
        de.lightrixx.punish.service.ProtectionService protection = this.altWacheProtection;
        if (protection == null || zielUuid == null) {
            return false;
        }
        try {
            return protection.istGeschuetzt(zielUuid,
                    de.lightrixx.punish.service.ProtectionService.IgnoreTyp.ALTS);
        } catch (Exception e) {
            logger.warn("Alt-Schutzprüfung für {} fehlgeschlagen.", zielUuid, e);
            return false;
        }
    }

    private static String maskiereIp(String ip) {
        String text = ip == null ? "" : ip.trim();
        int doppelpunkt = text.indexOf(':');
        if (doppelpunkt >= 0) {
            int ende = text.lastIndexOf(':');
            if (ende > 0) {
                return text.substring(0, ende) + ":*";
            }
            return text;
        }
        int letzterPunkt = text.lastIndexOf('.');
        if (letzterPunkt <= 0) {
            return text;
        }
        return text.substring(0, letzterPunkt) + ".*";
    }

    private void migriereVorab(UUID uuid, String name, long jetzt) {
        try {
            if (banService.findeAbdeckend(uuid, ServerScope.global()).isEmpty()) {
                var vorab = banService.findeVorabBannNachName(name, uuid);
                if (vorab.isPresent() && vorab.get().istAktiv(jetzt)) {
                    banService.migriereVorabBann(vorab.get().spielerUuid(), uuid, name);
                }
            }
        } catch (Exception e) {
            logger.debug("Vorab-Bann-Migration für '{}' fehlgeschlagen.", name, e);
        }
        try {
            if (!muteService.istIrgendwoGemutet(uuid)) {
                var vorabMute = muteService.findeVorabMuteNachName(name, uuid);
                if (vorabMute.isPresent() && vorabMute.get().istAktiv(jetzt)) {
                    muteService.migriereVorabMute(vorabMute.get().spielerUuid(), uuid, name);
                }
            }
        } catch (Exception e) {
            logger.debug("Vorab-Mute-Migration für '{}' fehlgeschlagen.", name, e);
        }
        try {
            if (timeoutService.findeAbdeckend(uuid, ServerScope.global()).isEmpty()) {
                var vorab = timeoutService.findeVorabTimeoutNachName(name, uuid);
                if (vorab.isPresent() && vorab.get().istAktiv(jetzt)) {
                    timeoutService.migriereVorabTimeout(vorab.get().spielerUuid(), uuid, name);
                }
            }
        } catch (Exception e) {
            logger.debug("Vorab-Timeout-Migration für '{}' fehlgeschlagen.", name, e);
        }
    }

    private Component fallbackSperre() {
        try {
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>Login derzeit nicht möglich. Bitte erneut versuchen.");
        } catch (Exception e) {
            logger.debug("Fallback-Sperrtext konnte nicht formatiert werden.", e);
            return Component.text("Login derzeit nicht möglich. Bitte erneut versuchen.");
        }
    }

    private Component loginSperre(UUID uuid, String name, String ip, long jetzt) {
        boolean ipUnbekannt = "unbekannt".equalsIgnoreCase(ip);
        if (ipUnbekannt) {
            boolean failClosed;
            try {
                failClosed = config.schutzFailClosed();
            } catch (Exception e) {
                failClosed = true;
            }
            if (failClosed) {
                logger.warn("IP für '{}' nicht lesbar – Login wird fail-closed verweigert.", name);
                return fallbackSperre();
            }
            logger.warn("IP für '{}' nicht lesbar – IP-Prüfungen werden fail-open übersprungen.", name);
        }
        Optional<BanRecord> bann;
        try {
            bann = banService.findeAbdeckend(uuid, ServerScope.global());
        } catch (Exception e) {
            throw new IllegalStateException("Ban-Lookup (Login) fehlgeschlagen.", e);
        }
        if (bann.isPresent() && bann.get().istAktiv(jetzt)) {
            try {
                return ScreenTexte.baueBannScreen(bann.get(), jetzt, config);
            } catch (Exception e) {
                logger.warn("Bann-Screen für '{}' konnte nicht gebaut werden, nutze Fallback.", name, e);
                return fallbackSperre();
            }
        } else if (bann.isPresent()) {

            boolean auto = bann.get().autoBann();
            planer.aufgabeAsync(() -> {
                try {
                    if (auto) {
                        autoBannService.behandleAutoBannAblaufNachLogin(uuid, name);
                    } else {
                        banService.bereinigeAblauf(uuid, name, "System");
                    }
                } catch (Exception e) {
                    logger.debug("Nachgelagerter Ban-Cleanup fehlgeschlagen.", e);
                }
            });
        }
        if (!ipUnbekannt) {
            Optional<IpBanRecord> ipBann;
            try {
                ipBann = ipBanService.ipFindeAbdeckend(ip, ServerScope.global());
            } catch (Exception e) {
                throw new IllegalStateException("IP-Bann-Lookup (Login) fehlgeschlagen.", e);
            }
            if (ipBann.isPresent() && ipBann.get().istAktiv(jetzt)) {
                try {
                    return ScreenTexte.baueIpBannScreen(ipBann.get(), config);
                } catch (Exception e) {
                    logger.warn("IP-Bann-Screen für '{}' konnte nicht gebaut werden, nutze Fallback.", name, e);
                    return fallbackSperre();
                }
            } else if (ipBann.isPresent()) {
                planer.aufgabeAsync(() -> {
                    try {
                        ipBanService.bereinigeAblauf(ip, "System");
                    } catch (Exception e) {
                        logger.debug("Nachgelagerter IP-Cleanup fehlgeschlagen.", e);
                    }
                });
            }
        } else {
            logger.warn("IP für '{}' nicht lesbar – IP-Bann-Prüfung übersprungen (Fail-Open in der IP-Dimension).", name);
        }
        Optional<TimeoutRecord> timeout;
        try {
            timeout = timeoutService.findeAbdeckend(uuid, ServerScope.global());
        } catch (Exception e) {
            throw new IllegalStateException("Timeout-Lookup (Login) fehlgeschlagen.", e);
        }
        if (timeout.isPresent() && timeout.get().istAktiv(jetzt)) {
            try {
                return timeoutService.baueTimeoutScreen(timeout.get(), jetzt);
            } catch (Exception e) {
                logger.warn("Timeout-Screen für '{}' konnte nicht gebaut werden, nutze Fallback.", name, e);
                return fallbackSperre();
            }
        } else if (timeoutService.hatEintrag(uuid)) {
            planer.aufgabeAsync(() -> {
                try {
                    timeoutService.bereinigeAbgelaufeneFuerUuid(uuid, "System");
                } catch (Exception e) {
                    logger.debug("Nachgelagerter Timeout-Cleanup fehlgeschlagen.", e);
                }
            });
        }
        if (ipTimeoutService != null && !ipUnbekannt) {
            Optional<IpTimeoutRecord> ipTimeout;
            try {
                ipTimeout = ipTimeoutService.findeAbdeckend(ip, ServerScope.global());
            } catch (Exception e) {
                throw new IllegalStateException("IP-Timeout-Lookup (Login) fehlgeschlagen.", e);
            }
            if (ipTimeout.isPresent() && ipTimeout.get().istAktiv(jetzt)) {
                try {
                    return ipTimeoutService.baueIpTimeoutScreen(ipTimeout.get(), jetzt);
                } catch (Exception e) {
                    logger.warn("IP-Timeout-Screen für '{}' konnte nicht gebaut werden, nutze Fallback.", name, e);
                    return fallbackSperre();
                }
            } else if (ipTimeout.isPresent()) {
                String ipKopie = ip;
                planer.aufgabeAsync(() -> {
                    try {
                        ipTimeoutService.bereinigeAbgelaufeneFuerIp(ipKopie, "System");
                    } catch (Exception e) {
                        logger.debug("Nachgelagerter IP-Timeout-Cleanup fehlgeschlagen.", e);
                    }
                });
            }
        }
        return null;
    }

    @Subscribe(priority = -32767)
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player spieler)
                || (!event.getResult().isAllowed() && !event.getResult().isForwardToServer())) {
            return;
        }
        UUID uuid = spieler.getUniqueId();
        String server = spieler.getCurrentServer()
                .map(v -> v.getServerInfo().getName()).orElse(ServerScope.UNBEKANNT_WERT);
        long jetzt = System.currentTimeMillis();
        String schluessel;
        Map<String, String> platzhalter;
        FreezeService freezes = freezeService;
        var freeze = freezes == null ? Optional.<de.lightrixx.punish.model.FreezeRecord>empty()
                : freezes.findeAktivenFreezeFuer(uuid, jetzt, server);
        if (freeze.isPresent()) {
            var record = freeze.get();
            schluessel = de.lightrixx.punish.core.MessageKeys.FREEZE_BLOCKIERT;
            platzhalter = Map.of("grund", record.grund(), "verbleibend",
                    de.lightrixx.punish.util.ZeitFormat.formatRestzeit(record.verbleibendeMillis(jetzt)));
        } else {
            String befehl = "/" + BrueckenKanal.tunnelBasisFuer(
                    event.getResult().getCommand().orElse(event.getCommand()));
            boolean blockiert = config.muteBlockierteBefehle().stream().anyMatch(eintrag ->
                    eintrag.endsWith("*")
                            ? befehl.startsWith(eintrag.substring(0, eintrag.length() - 1))
                            : befehl.equals(eintrag));
            if (!blockiert) {
                return;
            }
            var mute = muteService.findeAktivenMuteFuer(uuid, jetzt, server);
            if (mute.isPresent()) {
                var record = mute.get();
                platzhalter = Map.of("grund", record.grund(), "verbleibend",
                        de.lightrixx.punish.util.ZeitFormat.formatRestzeit(record.verbleibendeMillis(jetzt)));
            } else {
                var ipMute = ipMuteService == null ? Optional.<de.lightrixx.punish.model.IpMuteRecord>empty()
                        : ipMuteService.findeAktivenMuteFuer(ipVon(spieler), jetzt, server);
                if (ipMute.isEmpty()) {
                    return;
                }
                var record = ipMute.get();
                platzhalter = Map.of("grund", record.grund(), "verbleibend",
                        de.lightrixx.punish.util.ZeitFormat.formatRestzeit(record.verbleibendeMillis(jetzt)));
            }
            schluessel = de.lightrixx.punish.core.MessageKeys.MUTE_BLOCKIERT;
        }
        if (event.getInvocationInfo().signedState() == CommandExecuteEvent.SignedState.SIGNED_WITH_ARGS) {
            event.setResult(CommandExecuteEvent.CommandResult.forwardToServer());
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        messageService.senden(new VelocitySender(spieler), schluessel, platzhalter);
    }

    @Subscribe(priority = -32767)
    public void onPreConnect(ServerPreConnectEvent event) {
        Player spieler;
        UUID uuid;
        try {
            spieler = event.getPlayer();
            uuid = spieler.getUniqueId();
        } catch (Exception e) {
            logger.warn("Spieler für Serverwechsel-Prüfung (PreConnect) konnte nicht ermittelt werden.", e);
            return;
        }
        RegisteredServer ziel;
        try {
            ziel = event.getResult().getServer().orElse(null);
        } catch (Exception e) {
            logger.warn("Zielserver für Serverwechsel-Prüfung (PreConnect) für {} konnte nicht ermittelt werden.",
                    uuid, e);
            return;
        }
        if (ziel == null) {
            return;
        }
        String serverName;
        try {
            serverName = ziel.getServerInfo().getName();
        } catch (Exception e) {
            logger.warn("Servername für Serverwechsel-Prüfung (PreConnect) für {} konnte nicht ermittelt werden.",
                    uuid, e);
            return;
        }
        if (serverName == null || serverName.isBlank()) {
            verweigereServerwechsel(event, spieler, fallbackSperre());
            return;
        }
        long jetzt = System.currentTimeMillis();
        Component sperre = null;
        boolean failClosed = true;
        try {
            failClosed = config.schutzFailClosed();
        } catch (Exception e) {
            logger.debug("Fail-Closed-Lookup (PreConnect) fehlgeschlagen, nutze fail-closed=true.", e);
        }
        try {
            Optional<BanRecord> bann = banService.findeWirksam(uuid, serverName);
            if (bann.isPresent() && bann.get().istAktiv(jetzt)) {
                try {
                    sperre = ScreenTexte.baueBannScreen(bann.get(), jetzt, config);
                } catch (Exception e) {
                    logger.warn("Bann-Screen (PreConnect) für '{}' fehlgeschlagen, nutze Fallback.",
                            spieler.getUsername(), e);
                    sperre = fallbackSperre();
                }
            }
        } catch (Exception e) {
            logger.warn("Ban-Lookup (PreConnect) für '{}' fehlgeschlagen ({}).", spieler.getUsername(),
                    failClosed ? "fail-closed, Zugang verweigert" : "fail-open, Zugang erlaubt", e);
            if (failClosed) {
                sperre = fallbackSperre();
            }
        }
        if (sperre == null) {
            try {
                String ip = ipVon(spieler);
                if (!"unbekannt".equals(ip)) {
                    Optional<IpBanRecord> ipBann = ipBanService.findeWirksam(ip, serverName);
                    if (ipBann.isPresent() && ipBann.get().istAktiv(jetzt)) {
                        try {
                            sperre = ScreenTexte.baueIpBannScreen(ipBann.get(), config);
                        } catch (Exception e) {
                            logger.warn("IP-Bann-Screen (PreConnect) fehlgeschlagen, nutze Fallback.", e);
                            sperre = fallbackSperre();
                        }
                    }
                } else {
                    logger.warn("IP für '{}' (PreConnect) nicht lesbar – IP-Bann-Prüfung {}.",
                            spieler.getUsername(),
                            failClosed ? "fail-closed verweigert" : "fail-open übersprungen");
                    if (failClosed) {
                        sperre = fallbackSperre();
                    }
                }
            } catch (Exception e) {
                logger.warn("IP-Lookup (PreConnect) für '{}' fehlgeschlagen ({}).", spieler.getUsername(),
                        failClosed ? "fail-closed, Zugang verweigert" : "fail-open, Zugang erlaubt", e);
                if (failClosed) {
                    sperre = fallbackSperre();
                }
            }
        }
        if (sperre == null) {
            try {
                Optional<TimeoutRecord> timeout =
                        timeoutService.findeWirksamOhneCleanup(uuid, serverName, jetzt);
                if (timeout.isPresent()) {
                    try {
                        sperre = timeoutService.baueTimeoutScreen(timeout.get(), jetzt);
                    } catch (Exception e) {
                        logger.warn("Timeout-Screen (PreConnect) fehlgeschlagen, nutze Fallback.", e);
                        sperre = fallbackSperre();
                    }
                }
            } catch (Exception e) {
                logger.warn("Timeout-Lookup (PreConnect) für '{}' fehlgeschlagen ({}).", spieler.getUsername(),
                        failClosed ? "fail-closed, Zugang verweigert" : "fail-open, Zugang erlaubt", e);
                if (failClosed) {
                    sperre = fallbackSperre();
                }
            }
        }
        if (sperre == null && ipTimeoutService != null) {
            try {
                String ip = ipVon(spieler);
                if (!"unbekannt".equals(ip)) {
                    Optional<IpTimeoutRecord> ipTimeout =
                            ipTimeoutService.findeWirksamOhneCleanup(ip, serverName, jetzt);
                    if (ipTimeout.isPresent()) {
                        try {
                            sperre = ipTimeoutService.baueIpTimeoutScreen(ipTimeout.get(), jetzt);
                        } catch (Exception e) {
                            logger.warn("IP-Timeout-Screen (PreConnect) fehlgeschlagen, nutze Fallback.", e);
                            sperre = fallbackSperre();
                        }
                    }
                } else {
                    logger.warn("IP für '{}' (PreConnect) nicht lesbar – IP-Timeout-Prüfung {}.",
                            spieler.getUsername(),
                            failClosed ? "fail-closed verweigert" : "fail-open übersprungen");
                    if (failClosed) {
                        sperre = fallbackSperre();
                    }
                }
            } catch (Exception e) {
                logger.warn("IP-Timeout-Lookup (PreConnect) für '{}' fehlgeschlagen ({}).", spieler.getUsername(),
                        failClosed ? "fail-closed, Zugang verweigert" : "fail-open, Zugang erlaubt", e);
                if (failClosed) {
                    sperre = fallbackSperre();
                }
            }
        }
        if (sperre != null) {
            verweigereServerwechsel(event, spieler, sperre);
        }
    }

    private void verweigereServerwechsel(ServerPreConnectEvent event, Player spieler, Component grund) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        try {
            if (event.getPreviousServer() == null) {
                spieler.disconnect(grund);
            } else {
                spieler.sendMessage(grund);
            }
        } catch (Exception e) {
            logger.warn("Sperrhinweis beim verweigerten Serverwechsel fehlgeschlagen.", e);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player spieler = event.getPlayer();
        UUID uuid = spieler.getUniqueId();
        var fenster = loginBasis.get(uuid);
        if (fenster != null && fenster.spieler() == spieler) {
            loginBasis.remove(uuid, fenster);
        }
        long jetzt = System.currentTimeMillis();
        try {
            playerCache.finden(uuid).ifPresent(alt -> {
                try {
                    playerCache.aktualisieren(alt.mitZuletztGesehen(jetzt));
                } catch (Exception e) {
                    logger.debug("PlayerCache-Nachupdate beim Disconnect fehlgeschlagen.", e);
                }
            });
        } catch (Exception e) {
            logger.debug("PlayerCache-Lookup beim Disconnect fehlgeschlagen.", e);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        try {
            kanal.mutePushAn(event.getServer());
        } catch (Exception e) {
            logger.debug("Mute-Heilung nach Serverwechsel fehlgeschlagen.", e);
        }
        UUID uuid;
        try {
            uuid = event.getPlayer().getUniqueId();
        } catch (Exception e) {
            logger.debug("ServerConnected ohne Spielerdaten, keine Verpasst-Nachsendung.", e);
            return;
        }
        var fenster = loginBasis.get(uuid);
        if (fenster != null && fenster.spieler() == event.getPlayer() && loginBasis.remove(uuid, fenster)) {
            sendeVerpassteNach(uuid, fenster);
        }
        try {
            Player spieler = event.getPlayer();
            String server = event.getServer().getServerInfo().getName();
            boolean erstbeitritt = event.getPreviousServer().isEmpty();
            planer.spaeter(() -> sendeMuteBeimBeitritt(spieler, server, erstbeitritt), 20L);
        } catch (Exception e) {
            logger.debug("Mute-Hinweis beim Serverbeitritt konnte nicht geplant werden.", e);
        }
    }

    private void sendeMuteBeimBeitritt(Player spieler, String server, boolean erstbeitritt) {
        UUID uuid = spieler.getUniqueId();
        try {
            if (proxy.getPlayer(uuid).orElse(null) != spieler || spieler.getCurrentServer()
                    .map(v -> v.getServerInfo().getName()).filter(server::equalsIgnoreCase).isEmpty()) {
                return;
            }
            long jetzt = System.currentTimeMillis();
            var mute = muteService.findeAktivenMuteFuer(uuid, jetzt, server);
            if (mute.isPresent()) {
                var record = mute.get();
                if (erstbeitritt || record.scope().typ() == ServerScope.Typ.SERVER) {
                    sendeMuteHinweis(spieler, MessageKeys.ZIEL_GEMUTET, record.scope(), record.ausfuehrer(),
                            record.grund(), record.ablaufAm(), record.verbleibendeMillis(jetzt), "");
                }
                return;
            }
            if (ipMuteService != null) {
                ipMuteService.findeAktivenMuteFuer(ipVon(spieler), jetzt, server).ifPresent(record -> {
                    if (erstbeitritt || record.scope().typ() == ServerScope.Typ.SERVER) {
                        sendeMuteHinweis(spieler, MessageKeys.ZIEL_GEMUTET_IP, record.scope(), record.ausfuehrer(),
                                record.grund(), record.ablaufAm(), record.verbleibendeMillis(jetzt), record.ip());
                    }
                });
            }
        } catch (Exception e) {
            logger.debug("Mute-Hinweis beim Serverbeitritt fehlgeschlagen.", e);
        }
    }

    private void sendeMuteHinweis(Player spieler, String schluessel, ServerScope scope, String ausfuehrer,
                                  String grund, long ablauf, long verbleibend, String ip) {
        String restzeit = ZeitFormat.formatRestzeit(verbleibend);
        messageService.senden(new VelocitySender(spieler), schluessel, Map.of(
                "spieler", spieler.getUsername(), "ausfuehrer", ausfuehrer, "grund", grund,
                "ip", ip, "scope", scope.anzeige(), "dauer", restzeit,
                "verbleibend", restzeit, "ablauf", ZeitFormat.formatZeitpunkt(ablauf)));
    }

    private void sendeVerpassteNach(UUID uuid, LoginFenster fenster) {
        VerpassteBroadcastsRepository ablage = verpasstAblage;
        SpielerVerzeichnis verzeichnis = verpasstVerzeichnis;
        BroadcastService broadcast = verpasstBroadcast;
        if (ablage == null || verzeichnis == null || broadcast == null) {
            return;
        }
        try {
            if (!config.verpassteBroadcastsAktiv()) {
                return;
            }
        } catch (Exception e) {
            logger.debug("Verpasst-Aktiv-Lookup fehlgeschlagen, keine Nachsendung.", e);
            return;
        }
        try {
            planer.spaeter(() -> liefereVerpasst(uuid, fenster, ablage, verzeichnis, broadcast), 20L);
        } catch (IllegalStateException e) {
            logger.debug("Verpasst-Nachsendung konnte nicht eingeplant werden.");
        }
    }

    private void liefereVerpasst(UUID uuid, LoginFenster fenster, VerpassteBroadcastsRepository ablage,
                                 SpielerVerzeichnis verzeichnis, BroadcastService broadcast) {
        try {
            if (proxy.getPlayer(uuid).orElse(null) != fenster.spieler()) {
                return;
            }
            if (!verzeichnis.hatRecht(uuid, Permissions.BROADCAST_RECEIVE_ALL.node())) {
                try {
                    ablage.cursorSetzen(uuid, fenster.bis());
                } catch (Exception e) {
                    logger.debug("Missed-Cursor-Update (ohne Recht) fehlgeschlagen.", e);
                }
                return;
            }
            long cursor = 0L;
            try {
                cursor = ablage.cursorFuer(uuid).orElse(0L);
            } catch (Exception e) {
                logger.debug("Missed-Cursor-Lookup fehlgeschlagen.", e);
            }
            java.util.List<VerpassteNachricht> offen;
            long stand = fenster.bis() - 1L;
            long ab = Math.max(fenster.seit(), cursor);
            try {
                offen = ablage.momentaufnahme().stream()
                        .filter(e -> e.zeitstempel() > ab && e.zeitstempel() <= stand).toList();
            } catch (Exception e) {
                logger.debug("Missed-Abruf fehlgeschlagen.", e);
                return;
            }
            if (!offen.isEmpty()) {
                try {
                    for (int von = 0; von < offen.size(); von += VerpassteBroadcastsRepository.MAX_ABRUF) {
                        broadcast.liefereVerpassteNach(uuid, offen.subList(von,
                                Math.min(offen.size(), von + VerpassteBroadcastsRepository.MAX_ABRUF)));
                    }
                } catch (Exception e) {
                    logger.debug("Verpasst-Nachsendung fehlgeschlagen.", e);
                    return;
                }
            }
            if (proxy.getPlayer(uuid).orElse(null) != fenster.spieler() || verzeichnis.onlineSpieler(uuid).isEmpty()
                    || !verzeichnis.hatRecht(uuid, Permissions.BROADCAST_RECEIVE_ALL.node())
                    || !config.verpassteBroadcastsAktiv()) {
                return;
            }
            try {
                ablage.cursorSetzen(uuid, stand);
            } catch (Exception e) {
                logger.debug("Missed-Cursor-Update fehlgeschlagen.", e);
            }
        } catch (Exception e) {
            logger.debug("Verpasst-Nachsendung fehlgeschlagen.", e);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(Protokoll.KANAL)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection verbindung)) {
            return;
        }
        RegisteredServer server;
        try {
            server = verbindung.getServer();
        } catch (Exception e) {
            logger.warn("Quellserver einer Tunnel-Nachricht konnte nicht ermittelt werden.", e);
            return;
        }
        byte[] daten = kanal.eingehendPruefen(server, event.getData());
        if (daten == null || daten.length == 0) {
            return;
        }
        daten = kanal.sequenzPruefen(server, daten);
        if (daten == null || daten.length == 0) {
            return;
        }
        Protokoll.Paket paket;
        try {
            paket = Protokoll.paket(daten);
        } catch (Exception e) {
            logger.debug("Ungültiges Bridge-Paket verworfen.", e);
            return;
        }
        try {
            switch (paket.typ()) {
                case Protokoll.HELLO -> {
                    Protokoll.Hello hello = Protokoll.leseHello(paket.nutzdaten());
                    kanal.beantworteHello(server, hello.serverName(), hello.protokollVersion(),
                            hello.pluginVersion(), hello.variante(), hello.sessionId(), hello.vollSyncAngefordert());
                }
                case Protokoll.BEFEHL_AUSFUEHREN -> {
                    if (!befehlTempoErlaubt(serverNameFuer(server))) {
                        break;
                    }
                    Protokoll.BefehlsAuftrag auftrag = Protokoll.leseBefehl(paket.nutzdaten());
                    kanal.fuehreAus(auftrag.alsUuid(), auftrag.zeile(), server);
                }
                case Protokoll.GUI_ANFRAGE -> {
                    if (guiDienst == null) {
                        logger.debug("GUI-Anfrage ohne Dienst verworfen.");
                        break;
                    }
                    Protokoll.GuiAnfrage anfrage = Protokoll.leseGuiAnfrage(paket.nutzdaten());
                    if (!trefferGebunden(proxy, server, anfrage.absenderUuid())
                            || !darfGuiSehen(anfrage.absenderUuid(), anfrage.typ(), anfrage.ziel())) {
                        logger.warn("GUI-Anfrage abgewiesen (Recht oder Bridge-Bindung fehlt, Typ={}).",
                                anfrage.typ());
                        try {
                            kanal.sendeGuiAntwort(server, Protokoll.guiAntwort(
                                    new Protokoll.GuiAntwort(anfrage.requestId(), false,
                                            "Keine Berechtigung.", 1, 1, 0, java.util.List.of())));
                        } catch (Exception e) {
                            logger.debug("GUI-Ablehnung konnte nicht gesendet werden.", e);
                        }
                        break;
                    }
                    try {
                        planer.aufgabeAsync(() -> {
                            Protokoll.GuiAntwort antwort;
                            try {
                                antwort = guiDienst.beantworte(anfrage);
                            } catch (Exception e) {
                                logger.debug("GUI-Anfrage fehlgeschlagen.", e);
                                antwort = new Protokoll.GuiAntwort(anfrage.requestId(), false,
                                        "Fehler auf dem Proxy.", 1, 1, 0, java.util.List.of());
                            }
                            try {
                                kanal.sendeGuiAntwort(server, Protokoll.guiAntwort(antwort));
                            } catch (Exception e) {
                                logger.debug("GUI-Antwort konnte nicht gesendet werden.", e);
                            }
                        });
                    } catch (Exception e) {
                        logger.debug("GUI-Anfrage konnte nicht geplant werden.", e);
                    }
                }
                case Protokoll.PLATZHALTER_ANFRAGE -> {
                    PlatzhalterDienst dienst = platzhalterDienst;
                    if (dienst == null) {
                        logger.debug("Platzhalter-Anfrage ohne Dienst verworfen.");
                        break;
                    }
                    Protokoll.PlatzhalterAnfrage anfrage =
                            Protokoll.lesePlatzhalterAnfrage(paket.nutzdaten());
                    if (!trefferGebunden(proxy, server, anfrage.absenderUuid())
                            || !darfPlatzhalterSehen(anfrage.absenderUuid(), anfrage.ziel())) {
                        logger.warn("Platzhalter-Anfrage abgewiesen (Recht oder Bridge-Bindung fehlt).");
                        try {
                            kanal.sendePlatzhalterAntwort(server, Protokoll.platzhalterAntwort(
                                    Protokoll.PlatzhalterAntwort.fehler(anfrage.requestId())));
                        } catch (Exception e) {
                            logger.debug("Platzhalter-Ablehnung konnte nicht gesendet werden.", e);
                        }
                        break;
                    }
                    try {
                        planer.aufgabeAsync(() -> {
                            Protokoll.PlatzhalterAntwort antwort;
                            try {
                                antwort = dienst.beantworte(anfrage);
                            } catch (Exception e) {
                                logger.debug("Platzhalter-Anfrage fehlgeschlagen.", e);
                                antwort = Protokoll.PlatzhalterAntwort.fehler(anfrage.requestId());
                            }
                            try {
                                kanal.sendePlatzhalterAntwort(server, Protokoll.platzhalterAntwort(antwort));
                            } catch (Exception e) {
                                logger.debug("Platzhalter-Antwort konnte nicht gesendet werden.", e);
                            }
                        });
                    } catch (Exception e) {
                        logger.debug("Platzhalter-Anfrage konnte nicht geplant werden.", e);
                    }
                }
                case Protokoll.CHATFILTER_TREFFER -> {
                    ChatfilterStaffelService staffel = chatfilterStaffel;
                    if (staffel == null) {
                        logger.debug("Chatfilter-Treffer ohne Dienst verworfen.");
                        break;
                    }
                    Protokoll.ChatfilterTreffer treffer =
                            Protokoll.leseChatfilterTreffer(paket.nutzdaten());
                    if (!trefferGebunden(proxy, server, treffer.uuid())) {
                        logger.debug("Chatfilter-Treffer verworfen (Ziel nicht auf meldendem Server).");
                        break;
                    }
                    try {
                        planer.aufgabeAsync(() -> {
                            try {
                                staffel.melde(treffer.uuid(), treffer.name(), treffer);
                            } catch (Exception e) {
                                logger.debug("Chatfilter-Staffel fehlgeschlagen.", e);
                            }
                        });
                    } catch (Exception e) {
                        logger.debug("Chatfilter-Meldung konnte nicht geplant werden.", e);
                    }
                }
                case Protokoll.MODELL_STAND -> {
                    if (!metaTempoErlaubt(serverNameFuer(server), paket.typ())) {
                        break;
                    }
                    ModellVerteilung verteilung = modellVerteilung;
                    if (verteilung == null) {
                        logger.debug("Modell-Stand ohne Dienst verworfen.");
                        break;
                    }
                    Protokoll.ModellStand stand =
                            Protokoll.leseModellStand(paket.nutzdaten());
                    try {
                        planer.aufgabeAsync(() -> {
                            try {
                                verteilung.standEmpfangen(server, stand.modellHash(), stand.vocabHash());
                            } catch (Exception e) {
                                logger.debug("Modell-Stand fehlgeschlagen.", e);
                            }
                        });
                    } catch (Exception e) {
                        logger.debug("Modell-Stand konnte nicht geplant werden.", e);
                    }
                }
                case Protokoll.MODELL_QUITTUNG -> {
                    ModellVerteilung verteilung = modellVerteilung;
                    if (verteilung == null) {
                        logger.debug("Modell-Quittung ohne Dienst verworfen.");
                        break;
                    }
                    Protokoll.ModellQuittung quittung =
                            Protokoll.leseModellQuittung(paket.nutzdaten());
                    try {
                        verteilung.quittungEmpfangen(server, quittung);
                    } catch (Exception e) {
                        logger.debug("Modell-Quittung fehlgeschlagen.", e);
                    }
                }
                case Protokoll.TEXT_STAND -> {
                    if (!metaTempoErlaubt(serverNameFuer(server), paket.typ())) {
                        break;
                    }
                    TextVerteilung verteilung = textVerteilung;
                    if (verteilung == null) {
                        logger.debug("Text-Stand ohne Dienst verworfen.");
                        break;
                    }
                    Protokoll.TextStand stand =
                            Protokoll.leseTextStand(paket.nutzdaten());
                    try {
                        planer.aufgabeAsync(() -> {
                            try {
                                verteilung.standEmpfangen(server, stand.paketHash());
                            } catch (Exception e) {
                                logger.debug("Text-Stand fehlgeschlagen.", e);
                            }
                        });
                    } catch (Exception e) {
                        logger.debug("Text-Stand konnte nicht geplant werden.", e);
                    }
                }
                case Protokoll.TEXT_QUITTUNG -> {
                    TextVerteilung verteilung = textVerteilung;
                    if (verteilung == null) {
                        logger.debug("Text-Quittung ohne Dienst verworfen.");
                        break;
                    }
                    Protokoll.TextQuittung quittung =
                            Protokoll.leseTextQuittung(paket.nutzdaten());
                    try {
                        verteilung.quittungEmpfangen(server, quittung);
                    } catch (Exception e) {
                        logger.debug("Text-Quittung fehlgeschlagen.", e);
                    }
                }
                case Protokoll.UPDATE_STAND -> {
                    if (!metaTempoErlaubt(serverNameFuer(server), paket.typ())) {
                        break;
                    }
                    try {
                        Protokoll.UpdateStand stand = Protokoll.leseUpdateStand(paket.nutzdaten());
                        String kanonisch = server.getServerInfo().getName();
                        kanal.merkeBridgeVariante(kanonisch, stand.variante());
                        UpdateVerteilung verteilung = updateVerteilung;
                        if (verteilung != null && verteilung.bereit()) {
                            UpdateVerteilung ziel = verteilung;
                            try {
                                planer.aufgabeAsync(() -> {
                                    try {
                                        ziel.standEmpfangen(server, kanonisch, stand.variante());
                                    } catch (Exception e) {
                                        logger.debug("Update-Stand fehlgeschlagen.", e);
                                    }
                                });
                            } catch (Exception e) {
                                logger.debug("Update-Stand konnte nicht geplant werden.", e);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Update-Stand fehlgeschlagen.", e);
                    }
                }
                case Protokoll.UPDATE_QUITTUNG -> {
                    UpdateVerteilung verteilung = updateVerteilung;
                    if (verteilung == null) {
                        logger.debug("Update-Quittung ohne Dienst verworfen.");
                        break;
                    }
                    try {
                        verteilung.quittungEmpfangen(server, Protokoll.leseUpdateQuittung(paket.nutzdaten()));
                    } catch (Exception e) {
                        logger.debug("Update-Quittung fehlgeschlagen.", e);
                    }
                }
                default -> logger.debug("Unbekannter Bridge-Typ {} ignoriert.", paket.typ());
            }
        } catch (Exception e) {
            logger.debug("Bridge-Anfrage fehlgeschlagen.", e);
        }
    }

    private boolean befehlTempoErlaubt(String serverName) {
        var begrenzer = befehlTempo.computeIfAbsent(serverName,
                k -> new TunnelRateBegrenzer(20.0d, 20.0d, System::currentTimeMillis));
        if (begrenzer.versuche()) {
            return true;
        }
        logger.debug("BEFEHL_AUSFUEHREN von '{}' verworfen (Rate-Limit 20/s).", serverName);
        return false;
    }

    private boolean metaTempoErlaubt(String serverName, int typ) {
        String schluessel = serverName + "|" + typ;
        var begrenzer = metaTempo.computeIfAbsent(schluessel,
                k -> new TunnelRateBegrenzer(1.0d, 1.0d, System::currentTimeMillis));
        if (begrenzer.versuche()) {
            return true;
        }
        logger.debug("Transfer-Stand von '{}' (Typ {}) verworfen (Rate-Limit 1/s).", serverName, typ);
        return false;
    }

    private static String serverNameFuer(RegisteredServer server) {
        try {
            String name = server == null ? null : server.getServerInfo().getName();
            return name == null || name.isBlank() ? "?" : name;
        } catch (Exception e) {
            LOG.debug("Servername einer Bridge konnte nicht ermittelt werden.", e);
            return "?";
        }
    }

    static boolean trefferGebunden(ProxyServer proxy, RegisteredServer melder, UUID ziel) {
        if (proxy == null || melder == null || ziel == null) {
            return false;
        }
        try {
            var spieler = proxy.getPlayer(ziel).orElse(null);
            if (spieler == null) {
                return false;
            }
            var verbindung = spieler.getCurrentServer().orElse(null);
            if (verbindung == null || verbindung.getServer() == null) {
                return false;
            }
            String erwartet;
            String ist;
            try {
                erwartet = melder.getServerInfo().getName();
                ist = verbindung.getServer().getServerInfo().getName();
            } catch (Exception e) {
                LOG.debug("Serverbindung eines Chatfilter-Treffers für {} konnte nicht geprüft werden.", ziel, e);
                return false;
            }
            return erwartet != null && erwartet.equalsIgnoreCase(ist);
        } catch (Exception e) {
            LOG.debug("Spielerverbindung eines Chatfilter-Treffers für {} konnte nicht ermittelt werden.", ziel, e);
            return false;
        }
    }

    private boolean hatRecht(UUID uuid, String knoten) {
        if (uuid == null || knoten == null || knoten.isBlank()) {
            return false;
        }
        try {
            return proxy.getPlayer(uuid).map(spieler -> {
                try {
                    return VelocitySender.hatRecht(spieler, knoten);
                } catch (Exception e) {
                    logger.debug("Recht {} für {} konnte nicht geprüft werden.", knoten, uuid, e);
                    return false;
                }
            }).orElse(false);
        } catch (Exception e) {
            logger.debug("Spieler für Rechteprüfung {} für {} konnte nicht ermittelt werden.", knoten, uuid, e);
            return false;
        }
    }

    private boolean istSelbst(UUID absender, String ziel) {
        if (absender == null || ziel == null || ziel.isBlank()) {
            return false;
        }
        try {
            Optional<Player> spieler = proxy.getPlayer(absender);
            if (spieler.isPresent()) {
                if (absender.equals(spieler.get().getUniqueId())) {
                    String name = spieler.get().getUsername();
                    if (name != null && name.equalsIgnoreCase(ziel.trim())) {
                        return true;
                    }
                }
            }
            Optional<CachedPlayer> cached = playerCache.findenNachName(ziel.trim());
            return cached.isPresent() && absender.equals(cached.get().uuid());
        } catch (Exception e) {
            logger.debug("Selbstprüfung für {} und Ziel '{}' fehlgeschlagen.", absender, ziel, e);
            return false;
        }
    }

    private boolean darfGuiSehen(UUID absender, String typ, String ziel) {
        if (typ == null) {
            return false;
        }
        String norm = typ.trim().toUpperCase(java.util.Locale.ROOT);
        if (Protokoll.GUI_KATALOG.equals(norm) || Protokoll.GUI_RECHTE.equals(norm)) {
            return absender != null && hatRecht(absender, "punish.gui");
        }
        if (absender == null) {
            return false;
        }
        boolean selbst = istSelbst(absender, ziel);
        return switch (norm) {
            case Protokoll.GUI_STATUS -> selbst
                    ? (hatRecht(absender, "punish.own") || hatRecht(absender, "punish.status"))
                    : hatRecht(absender, "punish.status");
            case Protokoll.GUI_HISTORY -> selbst
                    ? (hatRecht(absender, "punish.own") || hatRecht(absender, "punish.history"))
                    : hatRecht(absender, "punish.history");
            case Protokoll.GUI_REPORTS, Protokoll.GUI_REPORT_INFO -> hatRecht(absender, "punish.reports");
            case Protokoll.GUI_DOSSIER -> hatRecht(absender, "punish.gui")
                    && hatRecht(absender, "punish.status")
                    && hatRecht(absender, "punish.history")
                    && hatRecht(absender, "punish.reports");
            default -> false;
        };
    }

    private boolean darfPlatzhalterSehen(UUID absender, String ziel) {
        if (absender == null) {
            return false;
        }
        if (ziel == null || ziel.isBlank()) {
            return true;
        }
        if (istSelbst(absender, ziel)) {
            return true;
        }
        return hatRecht(absender, "punish.status");
    }

    private static String ipVon(Player spieler) {
        try {
            if (spieler.getRemoteAddress() != null
                    && spieler.getRemoteAddress().getAddress() != null) {
                String roh = spieler.getRemoteAddress().getAddress().getHostAddress();
                if (roh == null || roh.isBlank()) {
                    return "unbekannt";
                }
                try {
                    return IpBanRecord.kanonisieren(roh);
                } catch (IllegalArgumentException e) {
                    return "unbekannt";
                }
            }
        } catch (Exception e) {
            LOG.debug("IP-Lookup fehlgeschlagen.", e);
        }
        return "unbekannt";
    }
}
