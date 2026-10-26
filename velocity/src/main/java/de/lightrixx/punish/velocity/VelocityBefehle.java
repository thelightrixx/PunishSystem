package de.lightrixx.punish.velocity;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.command.BanAllCommand;
import de.lightrixx.punish.command.BanCommand;
import de.lightrixx.punish.util.TabHinweis;
import de.lightrixx.punish.command.BanIpCommand;
import de.lightrixx.punish.command.FreezeCommand;
import de.lightrixx.punish.command.KickAllCommand;
import de.lightrixx.punish.command.KickCommand;
import de.lightrixx.punish.command.KickIpCommand;
import de.lightrixx.punish.command.MuteAllCommand;
import de.lightrixx.punish.command.MuteCommand;
import de.lightrixx.punish.command.MuteIpCommand;
import de.lightrixx.punish.command.PardonAllCommand;
import de.lightrixx.punish.command.PardonCommand;
import de.lightrixx.punish.command.PermabannCommand;
import de.lightrixx.punish.command.PunishHistoryCommand;
import de.lightrixx.punish.command.PunishNoteCommand;
import de.lightrixx.punish.command.PunishStatusCommand;
import de.lightrixx.punish.command.PsCheckCommand;
import de.lightrixx.punish.command.PunishHelpCommand;
import de.lightrixx.punish.command.PunishVersionCommand;
import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.command.PunishEditCommand;
import de.lightrixx.punish.command.ReportCommand;
import de.lightrixx.punish.command.ReportsCommand;
import de.lightrixx.punish.command.TimeoutAllCommand;
import de.lightrixx.punish.command.TimeoutCommand;
import de.lightrixx.punish.command.TimeoutIpCommand;
import de.lightrixx.punish.command.UnfreezeCommand;
import de.lightrixx.punish.command.WarnCommand;
import de.lightrixx.punish.command.UnbanIpCommand;
import de.lightrixx.punish.command.UnmuteAllCommand;
import de.lightrixx.punish.command.UnmuteCommand;
import de.lightrixx.punish.command.UnmuteIpCommand;
import de.lightrixx.punish.command.UntimeoutAllCommand;
import de.lightrixx.punish.command.UntimeoutCommand;
import de.lightrixx.punish.command.UntimeoutIpCommand;
import de.lightrixx.punish.command.VerstossCommand;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.AutoBannService;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.FreezeService;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.IpMuteService;
import de.lightrixx.punish.service.IpTimeoutService;
import de.lightrixx.punish.service.KickService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.NoteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.IoExecutor;
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
import de.lightrixx.punish.util.TimeParser;
import de.lightrixx.punish.util.PluginVersion;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public final class VelocityBefehle {

    private VelocityBefehle() {
    }

    @SuppressWarnings("checkstyle:parameternumber")
    public static void register(PunishVelocity plugin, ProxyServer proxy, Logger logger,
                                MessageService messageService, VelocityKonfiguration config,
                                ProtectionService protectionService, PlayerCache playerCache,
                                BanService banService, IpBanService ipBanService,
                                AutoBannService autoBannService, MuteService muteService,
                                IpMuteService ipMuteService,
                                 KickService kickService, TimeoutService timeoutService,
                                 IpTimeoutService ipTimeoutService, FreezeService freezeService,
                                 VerstossService verstossService, NoteService noteService,
                                 WarnService warnService,
                                 ReportService reportService,
                                 HistoryService historyService, BroadcastService broadcastService,
                                 TimeParser timeParser, VelocityAuftragsPlaner planer,
                                 VelocityVerzeichnis verzeichnis, BanRepository banRepository,
                                 IpBanRepository ipBanRepository, MuteRepository muteRepository,
                                  TimeoutRepository timeoutRepository, FreezeRepository freezeRepository, VerstossRepository verstossRepository,
                                 NoteRepository noteRepository, WarnRepository warnRepository,
                                 ReportRepository reportRepository,
                                 HistoryRepository historyRepository,
                                 IpMuteRepository ipMuteRepository,
                                 IpTimeoutRepository ipTimeoutRepository,
                                 BrueckenKanal kanal, IoExecutor ioExecutor) {
        register(plugin, proxy, logger, messageService, config, protectionService, playerCache,
                banService, ipBanService, autoBannService, muteService, ipMuteService,
                kickService, timeoutService, ipTimeoutService, freezeService,
                verstossService, noteService, warnService, reportService, historyService, broadcastService,
                timeParser, planer, verzeichnis, banRepository, ipBanRepository, muteRepository,
                timeoutRepository, freezeRepository, verstossRepository, noteRepository, warnRepository,
                reportRepository, historyRepository, ipMuteRepository, ipTimeoutRepository,
                kanal, ioExecutor, null);
    }

    @SuppressWarnings("checkstyle:parameternumber")
    public static void register(PunishVelocity plugin, ProxyServer proxy, Logger logger,
                                MessageService messageService, VelocityKonfiguration config,
                                ProtectionService protectionService, PlayerCache playerCache,
                                BanService banService, IpBanService ipBanService,
                                AutoBannService autoBannService, MuteService muteService,
                                IpMuteService ipMuteService,
                                 KickService kickService, TimeoutService timeoutService,
                                 IpTimeoutService ipTimeoutService, FreezeService freezeService,
                                 VerstossService verstossService, NoteService noteService,
                                 WarnService warnService,
                                 ReportService reportService,
                                 HistoryService historyService, BroadcastService broadcastService,
                                 TimeParser timeParser, VelocityAuftragsPlaner planer,
                                 VelocityVerzeichnis verzeichnis, BanRepository banRepository,
                                 IpBanRepository ipBanRepository, MuteRepository muteRepository,
                                  TimeoutRepository timeoutRepository, FreezeRepository freezeRepository, VerstossRepository verstossRepository,
                                 NoteRepository noteRepository, WarnRepository warnRepository,
                                 ReportRepository reportRepository,
                                 HistoryRepository historyRepository,
                                 IpMuteRepository ipMuteRepository,
                                 IpTimeoutRepository ipTimeoutRepository,
                                 BrueckenKanal kanal, IoExecutor ioExecutor, UpdateVerteilung updateVerteilung) {
        CommandManager manager = proxy.getCommandManager();
        registrar(manager, plugin, proxy, messageService, "ban", List.of(),
                new BanCommand(messageService, config, protectionService, playerCache,
                        banService, timeParser, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "permabann", List.of(),
                new PermabannCommand(messageService, config, protectionService, playerCache,
                        banService, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "ban-ip", List.of(),
                new BanIpCommand(messageService, config, protectionService, playerCache,
                        ipBanService, timeParser, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "unban-ip", List.of(),
                new UnbanIpCommand(messageService, config, protectionService, playerCache,
                        ipBanService, ipBanRepository, planer, verzeichnis));
        BanAllCommand banAll = new BanAllCommand(messageService, config, protectionService, playerCache,
                banService, timeParser, planer, verzeichnis);
        banAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "ban-all", List.of(), banAll);
        registrar(manager, plugin, proxy, messageService, "mute", List.of(),
                new MuteCommand(messageService, config, protectionService, playerCache,
                        muteService, timeParser, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "unmute", List.of(),
                new UnmuteCommand(messageService, config, protectionService, playerCache,
                        muteService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "mute-ip", List.of("muteip"),
                new MuteIpCommand(messageService, config, protectionService, playerCache,
                        ipMuteService, timeParser, planer, verzeichnis));
        MuteAllCommand muteAll = new MuteAllCommand(messageService, config, protectionService, playerCache,
                muteService, timeParser, planer, verzeichnis);
        muteAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "mute-all", List.of(), muteAll);
        UnmuteAllCommand unmuteAll = new UnmuteAllCommand(messageService, config, protectionService, playerCache,
                muteService, planer, verzeichnis);
        unmuteAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "unmute-all", List.of(), unmuteAll);
        registrar(manager, plugin, proxy, messageService, "unmute-ip", List.of("unmuteip"),
                new UnmuteIpCommand(messageService, config, protectionService, playerCache,
                        ipMuteService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "kick", List.of(),
                new KickCommand(messageService, config, protectionService, playerCache,
                        kickService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "kick-ip", List.of("kickip"),
                new KickIpCommand(messageService, config, protectionService, playerCache,
                        kickService, verzeichnis));
        KickAllCommand kickAll = new KickAllCommand(messageService, config, protectionService, playerCache,
                kickService, verzeichnis);
        kickAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "kick-all", List.of(), kickAll);
        PardonCommand pardon = new PardonCommand(messageService, config, protectionService, playerCache,
                banService, planer, verzeichnis);
        pardon.setVerstossService(verstossService);
        registrar(manager, plugin, proxy, messageService, "pardon", List.of("unban"), pardon);
        PardonAllCommand pardonAll = new PardonAllCommand(messageService, config, protectionService, playerCache,
                banService, planer, verzeichnis);
        pardonAll.setVerstossService(verstossService);
        pardonAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "pardon-all", List.of("unban-all"), pardonAll);
        registrar(manager, plugin, proxy, messageService, "timeout", List.of(),
                new TimeoutCommand(messageService, config, protectionService, playerCache,
                        timeoutService, timeParser, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "untimeout", List.of(),
                new UntimeoutCommand(messageService, config, protectionService, playerCache,
                        timeoutService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "timeout-ip", List.of("timeoutip"),
                new TimeoutIpCommand(messageService, config, protectionService, playerCache,
                        ipTimeoutService, timeParser, planer, verzeichnis));
        TimeoutAllCommand timeoutAll = new TimeoutAllCommand(messageService, config, protectionService, playerCache,
                timeoutService, timeParser, planer, verzeichnis);
        timeoutAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "timeout-all", List.of(), timeoutAll);
        UntimeoutAllCommand untimeoutAll = new UntimeoutAllCommand(messageService, config, protectionService,
                playerCache, timeoutService, planer, verzeichnis);
        untimeoutAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "untimeout-all", List.of(), untimeoutAll);
        registrar(manager, plugin, proxy, messageService, "untimeout-ip", List.of("untimeoutip"),
                new UntimeoutIpCommand(messageService, config, protectionService, playerCache,
                        ipTimeoutService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "freeze", List.of(),
                new FreezeCommand(messageService, config, protectionService, playerCache,
                        freezeService, timeParser, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "unfreeze", List.of(),
                new UnfreezeCommand(messageService, config, protectionService, playerCache,
                        freezeService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "verstoss", List.of("verstoß"),
                new VerstossCommand(messageService, config, protectionService, playerCache,
                        verstossService, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "punish-note", List.of("psnote"),
                new PunishNoteCommand(messageService, config, protectionService, playerCache,
                        noteService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "punish-edit", List.of("psedit"),
                new PunishEditCommand(messageService, config, protectionService, playerCache,
                        verstossService, warnService, noteService, banService, muteService,
                        timeoutService, ipBanService, ipMuteService, ipTimeoutService,
                        timeParser, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "warn", List.of("warnung", "verwarnung"),
                new WarnCommand(messageService, config, protectionService, playerCache,
                        warnService, planer, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "report", List.of("melden"),
                new ReportCommand(messageService, config, protectionService, playerCache,
                        reportService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "reports", List.of("reportlist"),
                new ReportsCommand(messageService, config, protectionService, playerCache,
                        reportService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "punish-status",
                List.of("psstatus"),
                new PunishStatusCommand(messageService, config, protectionService, playerCache,
                        verstossService, warnService, banRepository, muteRepository,
                        timeoutRepository, freezeRepository, ipBanRepository, ipMuteRepository, ipTimeoutRepository,
                        verstossRepository, warnRepository, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "pscheck",
                List.of(),
                new PsCheckCommand(messageService, config, protectionService, playerCache,
                        verstossService, warnService, banRepository, muteRepository,
                        timeoutRepository, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "punish-history",
                List.of("pshistory"),
                new PunishHistoryCommand(messageService, config, protectionService, playerCache,
                        historyService, verzeichnis));
        registrar(manager, plugin, proxy, messageService, "alts",
                List.of(),
                new de.lightrixx.punish.command.AltsCommand(messageService, config, protectionService,
                        playerCache, banRepository, ipBanRepository, verzeichnis));
        de.lightrixx.punish.command.DeleteIpCommand deleteIp =
                new de.lightrixx.punish.command.DeleteIpCommand(messageService, config, protectionService,
                        playerCache, ipBanRepository, ipMuteRepository, ipTimeoutRepository, verzeichnis);
        deleteIp.setNachLoeschung(kanal::mutePushAnAlle);
        registrar(manager, plugin, proxy, messageService, "punish-delete-ip",
                List.of("psdelete-ip", "psdeleteip"), deleteIp);
        VelocityReloadBefehl reload = new VelocityReloadBefehl(messageService, config,
                protectionService, playerCache, planer, banRepository, ipBanRepository, muteRepository,
                timeoutRepository, verstossRepository, noteRepository, warnRepository, reportRepository, historyRepository, autoBannService,
                muteService, timeoutService, ipBanService, ipMuteRepository, ipTimeoutRepository,
                ipMuteService, ipTimeoutService);
        try {
            reload.setBrueckenKanal(kanal);
        } catch (Exception e) {
            logger.warn("Reload-Kanal konnte nicht verdrahtet werden.", e);
        }
        try {
            reload.setIoExecutor(ioExecutor);
        } catch (Exception e) {
            logger.warn("Reload-IO konnte nicht verdrahtet werden.", e);
        }
        registrar(manager, plugin, proxy, messageService, "punish-reload", List.of("psreload"), reload);
        VelocityUpdateBefehl update = new VelocityUpdateBefehl(messageService, config,
                protectionService, playerCache, planer, verzeichnis, () -> proxyVersion(proxy));
        try {
            update.setBrueckenKanal(kanal);
        } catch (Exception e) {
            logger.warn("Update-Kanal konnte nicht verdrahtet werden.", e);
        }
        try {
            update.setUpdateVerteilung(updateVerteilung);
        } catch (Exception e) {
            logger.warn("Update-Verteilung konnte nicht verdrahtet werden.", e);
        }
        registrar(manager, plugin, proxy, messageService, "punish-update", List.of("psupdate"), update);
        registrar(manager, plugin, proxy, messageService, "punish-version", List.of("psversion"),
                new PunishVersionCommand(messageService, config, protectionService, playerCache,
                        verzeichnis, () -> proxyVersion(proxy)));
        registrar(manager, plugin, proxy, messageService, "punish-help", List.of("pshelp"),
                new PunishHelpCommand(messageService, config, protectionService, playerCache,
                        verzeichnis));
        registrar(manager, plugin, proxy, messageService, "punish-diagnostics", List.of("psdiagnostics"),
                new de.lightrixx.punish.command.PunishDiagnosticsCommand(messageService, config,
                        protectionService, playerCache, verzeichnis,
                        () -> diagnoseKontext(proxy, config, kanal, ioExecutor)));
        ChatfilterBefehl chatfilter = new ChatfilterBefehl(messageService, config,
                protectionService, playerCache, planer, verzeichnis, proxy);
        try {
            chatfilter.setBrueckenKanal(kanal);
        } catch (Exception e) {
            logger.warn("Chatfilter-Kanal konnte nicht verdrahtet werden.", e);
        }
        registrar(manager, plugin, proxy, messageService, "chatfilter", List.of(), chatfilter);
        logger.info("Proxy-Befehle registriert (Scopes via --global / --server).");
    }

    private static de.lightrixx.punish.command.PunishDiagnosticsCommand.Kontext diagnoseKontext(
            ProxyServer proxy, VelocityKonfiguration config, BrueckenKanal kanal, IoExecutor ioExecutor) {
        String version = proxyVersion(proxy);
        String speicherTyp = "unbekannt";
        try {
            speicherTyp = String.valueOf(config.speicherTyp()).toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Speicher-Typ-Lookup fehlgeschlagen.", e);
        }
        boolean yaml = "yaml".equalsIgnoreCase(speicherTyp);
        String speicherDetail = yaml ? "YAML aktiv (deprecated)" : "MySQL aktiv";
        boolean speicherOk = true;
        if (ioExecutor != null && !ioExecutor.istGesund()) {
            speicherOk = false;
            speicherDetail += " – WARNUNG: " + ioExecutor.anzahlFehler()
                    + " Persistenzfehler, Strafen ggf. nicht dauerhaft gespeichert";
        }
        int serverAnzahl = 0;
        try {
            serverAnzahl = proxy.getAllServers().size();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Serverlisten-Lookup fehlgeschlagen.", e);
        }
        boolean bridgeOk = kanal != null;
        String bridgeDetail = bridgeOk ? serverAnzahl + " Server verbunden, Kanal registriert" : "kein Brückenkanal";
        boolean luckPerms = false;
        try {
            luckPerms = proxy.getPluginManager().getPlugin("luckperms").isPresent();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("LuckPerms-Lookup fehlgeschlagen.", e);
        }
        boolean filterAktiv = false;
        String filterDetail = "";
        try {
            filterAktiv = config.chatfilterAktiv();
            filterDetail = filterAktiv
                    ? "Staffel aktiv (Modell-Sync: " + (config.chatfilterModellSync() ? "an" : "aus") + ")"
                    : "deaktiviert";
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Chatfilter-Lookup fehlgeschlagen.", e);
        }
        return new de.lightrixx.punish.command.PunishDiagnosticsCommand.Kontext("Velocity", version,
                speicherTyp, speicherOk, speicherDetail, serverAnzahl, bridgeOk, bridgeDetail,
                luckPerms, filterAktiv, filterDetail, versionenOk(kanal, version),
                versionenDetail(kanal, version));
    }

    private static boolean versionenOk(BrueckenKanal kanal, String version) {
        try {
            java.util.Map<String, String> bekannte = kanal == null ? java.util.Map.of() : kanal.bridgeVersionen();
            if (bekannte == null || bekannte.isEmpty()) {
                return true;
            }
            if (version == null || version.isBlank() || "unbekannt".equalsIgnoreCase(version.trim())) {
                return true;
            }
            for (String bridge : bekannte.values()) {
                if (bridge != null && !bridge.isBlank() && !"unbekannt".equalsIgnoreCase(bridge.trim())
                        && !version.trim().equals(bridge.trim())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Versionsvergleich fehlgeschlagen.", e);
            return true;
        }
    }

    private static String versionenDetail(BrueckenKanal kanal, String version) {
        try {
            java.util.Map<String, String> bekannte = kanal == null ? java.util.Map.of() : kanal.bridgeVersionen();
            if (bekannte == null || bekannte.isEmpty()) {
                return "noch keine Bridge gemeldet";
            }
            java.util.List<String> teile = new java.util.ArrayList<>();
            for (var eintrag : bekannte.entrySet()) {
                teile.add(eintrag.getKey() + "=" + eintrag.getValue());
            }
            teile.sort(String.CASE_INSENSITIVE_ORDER);
            String detail = "Proxy=" + (version == null ? "unbekannt" : version) + ", " + String.join(", ", teile);
            return versionenOk(kanal, version) ? "einheitlich (" + detail + ")" : "ABWEICHUNG (" + detail + ")";
        } catch (Exception e) {
            return "Versionsstand nicht lesbar";
        }
    }

    @SuppressWarnings("null")
    private static String proxyVersion(ProxyServer proxy) {
        try {
            var version = proxy.getPluginManager().getPlugin("punishsystem")
                    .flatMap(container -> container.getDescription().getVersion())
                    .map(String::trim)
                    .filter(s -> !s.isEmpty());
            if (version.isPresent()) {
                return version.get();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Proxy-Version Lookup fehlgeschlagen.", e);
        }
        return PluginVersion.leseAktuell();
    }

    private static void registrar(CommandManager manager, PunishVelocity plugin, ProxyServer proxy,
                                   MessageService messageService, String name,
                                   List<String> aliase, PunishCommand befehl) {
        LiteralArgumentBuilder<CommandSource> knoten = BrigadierCommand
                .literalArgumentBuilder(name)
                .requires(quelle -> {
                    try {
                        return befehl.sichtbarFuer(new VelocitySender(quelle));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .then(RequiredArgumentBuilder.<CommandSource, String>argument(
                                "rest", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            BefehlsSender sender = new VelocitySender(ctx.getSource());
                            if (!befehl.sichtbarFuer(sender)) {
                                return builder.buildFuture();
                            }
                            String rest;
                            try {
                                rest = ctx.getArgument("rest", String.class);
                            } catch (Exception e) {
                                rest = "";
                            }
                            if (rest == null) {
                                rest = "";
                            }
                            String[] teile = TabHinweis.teile(rest);
                            List<String> vorschlaege;
                            try {
                                vorschlaege = befehl.vervollstaendigen(sender, teile);
                            } catch (Exception e) {
                                return builder.buildFuture();
                            }
                            List<String> alle = vorschlaege == null ? new ArrayList<>()
                                    : new ArrayList<>(vorschlaege);
                            try {
                                String letztes = teile.length == 0 ? "" : teile[teile.length - 1];
                                if (letztes != null && letztes.startsWith("--") && proxy != null) {
                                    for (var server : proxy.getAllServers()) {
                                        String serverName = null;
                                        try {
                                            serverName = server.getServerInfo().getName();
                                        } catch (Exception e) {
                                            org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Tab-Complete Servernamen-Lookup fehlgeschlagen.", e);
                                        }
                                        if (serverName == null || serverName.isBlank()) {
                                            continue;
                                        }
                                        String kandidat = "--" + serverName.trim().toLowerCase(
                                                java.util.Locale.ROOT);
                                        boolean schon = false;
                                        for (String vorhanden : alle) {
                                            if (vorhanden != null && vorhanden.equalsIgnoreCase(kandidat)) {
                                                schon = true;
                                                break;
                                            }
                                        }
                                        if (!schon && kandidat.toLowerCase(java.util.Locale.ROOT)
                                                .startsWith(letztes.toLowerCase(java.util.Locale.ROOT))) {
                                            alle.add(kandidat);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Tab-Complete Serverliste fehlgeschlagen.", e);
                            }
                            if (alle.isEmpty()) {
                                return builder.buildFuture();
                            }
                            String praefix = TabHinweis.praefixFuer(rest);
                            for (String vorschlag : alle) {
                                if (vorschlag == null) {
                                    continue;
                                }
                                String hinweis = null;
                                try {
                                    hinweis = befehl.tooltipFuer(vorschlag, teile);
                                } catch (Exception e) {
                                    org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Tab-Complete Tooltip fehlgeschlagen.", e);
                                }
                                if (hinweis == null || hinweis.isBlank()) {
                                    builder.suggest(praefix + vorschlag);
                                } else {
                                    final String text = hinweis;
                                    builder.suggest(praefix + vorschlag, () -> text);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            BefehlsSender sender = new VelocitySender(ctx.getSource());
                            String rest;
                            try {
                                rest = ctx.getArgument("rest", String.class);
                            } catch (Exception e) {
                                rest = "";
                            }
                            String[] args = zuArgs(sender, rest == null ? "" : rest, befehl);
                            if (!befehl.hatRecht(sender, args)) {
                                messageService.senden(sender, MessageKeys.KEIN_RECHT);
                                return 0;
                            }
                            try {
                                befehl.ausfuehren(sender, args);
                            } catch (Exception e) {
                                org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class)
                                        .warn("Fehler bei /{} von {}.", name, sender.name(), e);
                            }
                            return 1;
                        }));

        knoten.executes(ctx -> {
            BefehlsSender sender = new VelocitySender(ctx.getSource());
            if (!befehl.sichtbarFuer(sender)) {
                messageService.senden(sender, MessageKeys.KEIN_RECHT);
                return 0;
            }
            try {
                befehl.ausfuehren(sender, new String[0]);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class)
                        .warn("Fehler bei /{} von {}.", name, sender.name(), e);
            }
            return 1;
        });
        CommandMeta.Builder meta = manager.metaBuilder(name).plugin(plugin);
        List<String> alleAliase = new ArrayList<>();
        if (aliase != null && !aliase.isEmpty()) {
            alleAliase.addAll(aliase);
        }
        // Namespaced-Aliase "punish:<befehl>", damit ingame bei "punish:" alle
        // Plugin-Befehle als Vorhersagen erscheinen (sonst gibt es dort keine Treffer,
        // weil kein Befehl mit "punish:" beginnt). Gleiche Rechteprüfung wie Hauptname.
        alleAliase.add("punish:" + name);
        if (aliase != null) {
            for (String alias : aliase) {
                if (alias != null && !alias.isBlank()) {
                    alleAliase.add("punish:" + alias.trim());
                }
            }
        }
        meta.aliases(alleAliase.toArray(new String[0]));
        try {
            manager.register(meta.build(), new BrigadierCommand(knoten));
        } catch (IllegalArgumentException e) {
            org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class)
                    .warn("Registrierung von /{} mit allen Aliasen fehlgeschlagen ({}), "
                            + "versuche es ohne punish:-Aliase.", name, e.getMessage());
            try {
                CommandMeta.Builder fallback = manager.metaBuilder(name).plugin(plugin);
                if (aliase != null && !aliase.isEmpty()) {
                    fallback.aliases(aliase.toArray(new String[0]));
                }
                manager.register(fallback.build(), new BrigadierCommand(knoten));
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class)
                        .error("Registrierung von /{} endgültig fehlgeschlagen – der Befehl fehlt im Proxy.", name, ex);
            }
        }
    }

    static String[] zuArgs(BefehlsSender sender, String rest) {
        return zuArgs(sender, rest, null);
    }

    static String[] zuArgs(BefehlsSender sender, String rest, PunishCommand befehl) {
        String getrimmt = rest == null ? "" : rest.trim();
        List<String> teile = new ArrayList<>();
        if (!getrimmt.isEmpty()) {
            for (String token : getrimmt.split("\\s+")) {
                if (!token.isEmpty()) {
                    teile.add(token);
                }
            }
        }
        // Auto-Scope nur für Befehle mit Scope-Unterstützung. Ohne Scope (Kick, Warn,
        // Note, Verstoss, Punishments, Reload) würde das Flag sonst im Grund/Text landen.
        boolean mitScope = befehl == null || befehl.unterstuetztScope();
        if (!mitScope) {
            return teile.toArray(new String[0]);
        }
        if (teile.isEmpty() || !teile.get(teile.size() - 1).startsWith("--")) {
            String standard = standardFlag(sender);
            if (standard != null) {
                teile.add(standard);
            }
        }
        return teile.toArray(new String[0]);
    }

    private static String standardFlag(BefehlsSender sender) {
        if (sender != null && sender.istSpieler()) {
            String herkunft = null;
            try {
                herkunft = sender.herkunft();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(VelocityBefehle.class).debug("Herkunfts-Lookup für Standard-Scope fehlgeschlagen.", e);
            }
            if (herkunft != null && !herkunft.isBlank()
                    && !herkunft.equalsIgnoreCase(ServerScope.UNBEKANNT_WERT)
                    && !herkunft.equalsIgnoreCase("konsole")
                    && ServerScope.istGueltigerServerName(herkunft)) {
                return "--" + herkunft.trim().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "--global";
    }
}
