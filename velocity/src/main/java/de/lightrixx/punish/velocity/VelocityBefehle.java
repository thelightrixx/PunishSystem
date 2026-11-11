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
import de.lightrixx.punish.command.PunishVorlageCommand;
import de.lightrixx.punish.command.PunishUndoCommand;
import de.lightrixx.punish.command.PunishStatsCommand;
import de.lightrixx.punish.command.PunishExportCommand;
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
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
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
import de.lightrixx.punish.util.ZeitFormat;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

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
                kanal, ioExecutor, null, null);
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
                                 BrueckenKanal kanal, IoExecutor ioExecutor, UpdateVerteilung updateVerteilung,
                                 java.io.File datenOrdner) {
        CommandManager manager = proxy.getCommandManager();
        java.util.Map<String, PunishCommand> vorlagenZiele = new java.util.LinkedHashMap<>();
        BanCommand ban = new BanCommand(messageService, config, protectionService, playerCache,
                banService, timeParser, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "ban", List.of("eban", "tempban", "etempban"), ban);
        vorlagenZiele.put("ban", ban);
        PermabannCommand permabann = new PermabannCommand(messageService, config, protectionService, playerCache,
                banService, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "permabann", List.of(), permabann);
        vorlagenZiele.put("permabann", permabann);
        BanIpCommand banIp = new BanIpCommand(messageService, config, protectionService, playerCache,
                ipBanService, timeParser, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "ban-ip",
                List.of("banip", "ebanip", "tempbanip", "etempbanip"), banIp);
        vorlagenZiele.put("ban-ip", banIp);
        UnbanIpCommand unbanIp = new UnbanIpCommand(messageService, config, protectionService,
                playerCache, ipBanService, ipBanRepository, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "unban-ip",
                List.of("unbanip", "eunbanip", "pardon-ip"), unbanIp);
        BanAllCommand banAll = new BanAllCommand(messageService, config, protectionService, playerCache,
                banService, timeParser, planer, verzeichnis);
        banAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "ban-all", List.of(), banAll);
        MuteCommand mute = new MuteCommand(messageService, config, protectionService, playerCache,
                muteService, timeParser, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "mute", List.of("emute"), mute);
        vorlagenZiele.put("mute", mute);
        UnmuteCommand unmute = new UnmuteCommand(messageService, config, protectionService,
                playerCache, muteService, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "unmute", List.of(), unmute);
        MuteIpCommand muteIp = new MuteIpCommand(messageService, config, protectionService, playerCache,
                ipMuteService, timeParser, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "mute-ip", List.of("muteip"), muteIp);
        vorlagenZiele.put("mute-ip", muteIp);
        MuteAllCommand muteAll = new MuteAllCommand(messageService, config, protectionService, playerCache,
                muteService, timeParser, planer, verzeichnis);
        muteAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "mute-all", List.of(), muteAll);
        UnmuteAllCommand unmuteAll = new UnmuteAllCommand(messageService, config, protectionService, playerCache,
                muteService, planer, verzeichnis);
        unmuteAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "unmute-all", List.of(), unmuteAll);
        UnmuteIpCommand unmuteIp = new UnmuteIpCommand(messageService, config, protectionService,
                playerCache, ipMuteService, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "unmute-ip", List.of("unmuteip"),
                unmuteIp);
        KickCommand kick = new KickCommand(messageService, config, protectionService, playerCache,
                kickService, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "kick", List.of("ekick"), kick);
        vorlagenZiele.put("kick", kick);
        KickIpCommand kickIp = new KickIpCommand(messageService, config, protectionService, playerCache,
                kickService, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "kick-ip", List.of("kickip"), kickIp);
        vorlagenZiele.put("kick-ip", kickIp);
        KickAllCommand kickAll = new KickAllCommand(messageService, config, protectionService, playerCache,
                kickService, verzeichnis);
        kickAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "kick-all", List.of("kickall", "ekickall"), kickAll);
        PardonCommand pardon = new PardonCommand(messageService, config, protectionService, playerCache,
                banService, planer, verzeichnis);
        pardon.setVerstossService(verstossService);
        registrar(manager, plugin, proxy, messageService, "pardon", List.of("unban", "eunban"), pardon);
        PardonAllCommand pardonAll = new PardonAllCommand(messageService, config, protectionService, playerCache,
                banService, planer, verzeichnis);
        pardonAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "pardon-all", List.of("unban-all"), pardonAll);
        TimeoutCommand timeout = new TimeoutCommand(messageService, config, protectionService, playerCache,
                timeoutService, timeParser, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "timeout", List.of(), timeout);
        vorlagenZiele.put("timeout", timeout);
        UntimeoutCommand untimeout = new UntimeoutCommand(messageService, config,
                protectionService, playerCache, timeoutService, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "untimeout", List.of(), untimeout);
        TimeoutIpCommand timeoutIp = new TimeoutIpCommand(messageService, config, protectionService, playerCache,
                ipTimeoutService, timeParser, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "timeout-ip", List.of("timeoutip"), timeoutIp);
        vorlagenZiele.put("timeout-ip", timeoutIp);
        TimeoutAllCommand timeoutAll = new TimeoutAllCommand(messageService, config, protectionService, playerCache,
                timeoutService, timeParser, planer, verzeichnis);
        timeoutAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "timeout-all", List.of(), timeoutAll);
        UntimeoutAllCommand untimeoutAll = new UntimeoutAllCommand(messageService, config, protectionService,
                playerCache, timeoutService, planer, verzeichnis);
        untimeoutAll.setIoExecutor(ioExecutor);
        registrar(manager, plugin, proxy, messageService, "untimeout-all", List.of(), untimeoutAll);
        UntimeoutIpCommand untimeoutIp = new UntimeoutIpCommand(messageService, config,
                protectionService, playerCache, ipTimeoutService, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "untimeout-ip", List.of("untimeoutip"),
                untimeoutIp);
        FreezeCommand freeze = new FreezeCommand(messageService, config, protectionService, playerCache,
                freezeService, timeParser, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "freeze", List.of(), freeze);
        vorlagenZiele.put("freeze", freeze);
        UnfreezeCommand unfreeze = new UnfreezeCommand(messageService, config, protectionService,
                playerCache, freezeService, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "unfreeze", List.of(), unfreeze);
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
        WarnCommand warn = new WarnCommand(messageService, config, protectionService, playerCache,
                warnService, planer, verzeichnis);
        registrar(manager, plugin, proxy, messageService, "warn", List.of("warnung", "verwarnung"), warn);
        vorlagenZiele.put("warn", warn);
        java.util.Map<String, PunishCommand> undoZiele = new java.util.LinkedHashMap<>();
        undoZiele.put("pardon", pardon);
        undoZiele.put("unban-ip", unbanIp);
        undoZiele.put("unmute", unmute);
        undoZiele.put("unmute-ip", unmuteIp);
        undoZiele.put("untimeout", untimeout);
        undoZiele.put("untimeout-ip", untimeoutIp);
        undoZiele.put("unfreeze", unfreeze);
        undoZiele.put("warn", warn);
        registrar(manager, plugin, proxy, messageService, "punish-undo", List.of("psundo"),
                new PunishUndoCommand(messageService, config, protectionService, playerCache,
                        verzeichnis, historyService, warnService, undoZiele));
        registrar(manager, plugin, proxy, messageService, "punish-stats", List.of("psstats"),
                new PunishStatsCommand(messageService, config, protectionService, playerCache,
                        verzeichnis, historyService, reportService, timeParser));
        registrar(manager, plugin, proxy, messageService, "punish-search", List.of(),
                new Suche(messageService, config, protectionService, playerCache,
                        verzeichnis, historyRepository, noteRepository, reportRepository));
        if (datenOrdner != null) {
            registrar(manager, plugin, proxy, messageService, "punish-export", List.of("psexport"),
                    new PunishExportCommand(messageService, config, protectionService, playerCache,
                            verzeichnis, planer, historyRepository, warnRepository, noteRepository,
                            reportRepository, datenOrdner));
        }
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
        reload.setFreezeRepository(freezeRepository);
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
        registrar(manager, plugin, proxy, messageService, "punish-setup", List.of("pssetup"),
                new VelocitySetupBefehl(messageService, config, protectionService, playerCache,
                        planer, proxy, kanal, datenOrdner, () -> proxyVersion(proxy)));
        ChatfilterBefehl chatfilter = new ChatfilterBefehl(messageService, config,
                protectionService, playerCache, planer, verzeichnis, proxy);
        try {
            chatfilter.setBrueckenKanal(kanal);
        } catch (Exception e) {
            logger.warn("Chatfilter-Kanal konnte nicht verdrahtet werden.", e);
        }
        registrar(manager, plugin, proxy, messageService, "chatfilter", List.of(), chatfilter);
        registrar(manager, plugin, proxy, messageService, "punish", List.of(),
                new PunishVorlageCommand(messageService, config, protectionService, playerCache,
                        planer, verzeichnis, vorlagenZiele));
        logger.info("Proxy-Befehle registriert (Scopes via --global / --server).");
    }

    static final class Suche extends PunishCommand {

        private static final int LIMIT = 200;
        private static final Comparator<Treffer> NACH_ZEIT = Comparator.comparingLong(Treffer::zeit)
                .thenComparing(Treffer::typ).thenComparingLong(Treffer::id)
                .thenComparing(Treffer::ziel).thenComparing(Treffer::text);

        private record Treffer(long zeit, String typ, long id, String ziel, String text) {
            boolean passt(String suche) {
                return (typ + " " + ziel + " " + text).toLowerCase(Locale.ROOT).contains(suche);
            }
        }

        private final HistoryRepository history;
        private final NoteRepository notizen;
        private final ReportRepository reports;

        Suche(MessageService messageService, Konfiguration config, ProtectionService schutz,
              PlayerCache playerCache, SpielerVerzeichnis verzeichnis, HistoryRepository history,
              NoteRepository notizen, ReportRepository reports) {
            super(messageService, config, schutz, playerCache, null, verzeichnis);
            this.history = history;
            this.notizen = notizen;
            this.reports = reports;
        }

        @Override
        protected String recht() {
            return Permissions.PUNISH_SEARCH.node();
        }

        @Override
        public boolean unterstuetztScope() {
            return false;
        }

        @Override
        public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
            return List.of();
        }

        @Override
        public void ausfuehren(BefehlsSender sender, String[] args) {
            int textEnde = args.length;
            int seite = 1;
            if (textEnde > 0 && args[textEnde - 1].regionMatches(true, 0, "page:", 0, 5)) {
                try {
                    seite = Integer.parseInt(args[--textEnde].substring(5));
                } catch (NumberFormatException e) {
                    messageService.senden(sender, MessageKeys.PUNISH_SEARCH_SYNTAX);
                    return;
                }
            }
            String text = String.join(" ", Arrays.copyOf(args, textEnde)).strip();
            if (seite < 1 || text.isEmpty()) {
                messageService.senden(sender, MessageKeys.PUNISH_SEARCH_SYNTAX);
                return;
            }
            List<Treffer> treffer = suche(text.toLowerCase(Locale.ROOT));
            if (treffer.isEmpty()) {
                messageService.senden(sender, MessageKeys.PUNISH_SEARCH_LEER);
                return;
            }
            int groesse = Math.clamp(configManager.historyEintraegeProSeite(), 1, 100);
            int anzahl = Math.min(treffer.size(), LIMIT);
            int seiten = (anzahl + groesse - 1) / groesse;
            seite = Math.min(seite, seiten);
            List<Component> zeilen = new ArrayList<>();
            zeilen.add(messageService.komponente(MessageKeys.PUNISH_SEARCH_KOPF, Map.of(
                    "anzahl", String.valueOf(anzahl), "mehr", treffer.size() > LIMIT ? "+" : "",
                    "seite", String.valueOf(seite), "seiten", String.valueOf(seiten))));
            int start = (seite - 1) * groesse;
            for (Treffer eintrag : treffer.subList(start, Math.min(start + groesse, anzahl))) {
                zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_SEARCH_ZEILE, Map.of(
                        "typ", eintrag.typ(), "id", String.valueOf(eintrag.id()),
                        "ziel", eintrag.ziel(), "text", eintrag.text(),
                        "datum", ZeitFormat.formatZeitpunkt(eintrag.zeit()))));
            }
            messageService.sendeBlock(sender, zeilen);
        }

        private List<Treffer> suche(String suchtext) {
            PriorityQueue<Treffer> treffer = new PriorityQueue<>(LIMIT + 1, NACH_ZEIT);
            history.alleSchluessel().values().forEach(eintraege -> eintraege.forEach(eintrag ->
                    aufnehmen(treffer, suchtext, new Treffer(eintrag.zeitstempel(),
                            eintrag.typ().anzeigename(), eintrag.id(), eintrag.ziel(),
                            eintrag.ausfuehrer() + " · " + eintrag.grund()))));
            notizen.alle().values().forEach(eintraege -> eintraege.forEach(eintrag ->
                    aufnehmen(treffer, suchtext, new Treffer(eintrag.erstelltAm(), "Notiz",
                            eintrag.nummer(), eintrag.spielerName(), eintrag.ausfuehrer() + " · " + eintrag.text()))));
            for (var report : reports.alle()) {
                String text = report.reporterName() + " · " + report.grund();
                if (!report.closeGrund().isBlank()) {
                    text += " · " + report.closeGrund();
                }
                aufnehmen(treffer, suchtext, new Treffer(report.erstelltAm(), "Report",
                        report.id(), report.zielName(), text));
            }
            return treffer.stream().sorted(NACH_ZEIT.reversed()).toList();
        }

        private static void aufnehmen(PriorityQueue<Treffer> treffer, String suchtext, Treffer eintrag) {
            if (eintrag.passt(suchtext)) {
                treffer.add(eintrag);
                if (treffer.size() > LIMIT + 1) {
                    treffer.poll();
                }
            }
        }
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
        boolean bridgeOk = false;
        String bridgeDetail = "kein Brückenkanal";
        if (kanal != null) {
            var gemeldet = kanal.bridgeVersionen();
            var secrets = config.brueckenSecrets();
            var server = proxy.getAllServers();
            long helloAnzahl = server.stream().filter(s -> gemeldet.containsKey(
                    s.getServerInfo().getName().toLowerCase(java.util.Locale.ROOT))).count();
            boolean secretsVollstaendig = server.stream().allMatch(s -> {
                String secret = secrets.get(s.getServerInfo().getName().toLowerCase(java.util.Locale.ROOT));
                return de.lightrixx.punish.net.TunnelSignatur.istKonfiguriert(secret)
                        && !de.lightrixx.punish.net.TunnelSignatur.istSchwach(secret);
            });
            bridgeOk = serverAnzahl > 0 && helloAnzahl == serverAnzahl && secretsVollstaendig;
            bridgeDetail = helloAnzahl + "/" + serverAnzahl + " Bridges per HELLO gemeldet; "
                    + (secretsVollstaendig ? "Secrets vollständig" : "Bridge-Secrets fehlen oder sind zu kurz");
        }
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
        boolean ueberschreiben = befehl.uebernimmtFremdeBefehle();
        List<String> fremdeAliase = ueberschreibAliase(name);
        if (!ueberschreiben && aliase != null) {
            aliase = aliase.stream().filter(alias -> !fremdeAliase.contains(alias)).toList();
        }
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
                            String rest = builder.getRemaining();
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
                            var vorschlagsBuilder = builder.createOffset(
                                    builder.getStart() + TabHinweis.praefixFuer(rest).length());
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
                                    vorschlagsBuilder.suggest(vorschlag);
                                } else {
                                    final String text = hinweis;
                                    vorschlagsBuilder.suggest(vorschlag, () -> text);
                                }
                            }
                            return vorschlagsBuilder.buildFuture();
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
                if (alias != null && !alias.isBlank() && !fremdeAliase.contains(alias.trim())) {
                    alleAliase.add("punish:" + alias.trim());
                }
            }
        }
        if (ueberschreiben && istStrafBefehl(name)) {
            List<String> namen = new ArrayList<>();
            namen.add(name);
            if (aliase != null) {
                namen.addAll(aliase);
            }
            for (String befehlsName : namen) {
                for (String praefix : List.of("minecraft:", "bukkit:", "essentials:")) {
                    alleAliase.add(praefix + befehlsName);
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

    private static boolean istStrafBefehl(String name) {
        return switch (name) {
            case "ban", "permabann", "ban-ip", "unban-ip", "ban-all",
                    "kick", "kick-ip", "kick-all", "pardon", "pardon-all",
                    "mute", "unmute", "mute-ip", "unmute-ip", "mute-all", "unmute-all",
                    "timeout", "untimeout", "timeout-ip", "untimeout-ip", "timeout-all", "untimeout-all",
                    "freeze", "unfreeze", "warn", "verstoss" -> true;
            default -> false;
        };
    }

    private static List<String> ueberschreibAliase(String name) {
        return switch (name) {
            case "ban" -> List.of("eban", "tempban", "etempban");
            case "ban-ip" -> List.of("banip", "ebanip", "tempbanip", "etempbanip");
            case "unban-ip" -> List.of("unbanip", "eunbanip", "pardon-ip");
            case "mute" -> List.of("emute");
            case "kick" -> List.of("ekick");
            case "kick-all" -> List.of("kickall", "ekickall");
            case "pardon" -> List.of("eunban");
            default -> List.of();
        };
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
        boolean mitScope = befehl == null
                || (befehl.unterstuetztScope() && !befehl.erzwingtExplizitenScope());
        if (!mitScope) {
            return teile.toArray(new String[0]);
        }
        int letztes = teile.size() - 1;
        while (letztes >= 0 && de.lightrixx.punish.command.ScopeParser.istSilentFlag(teile.get(letztes))) {
            letztes--;
        }
        if (letztes < 0 || !teile.get(letztes).startsWith("--")) {
            teile.add(standardFlag(sender));
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
                return ServerScope.server(herkunft).alsFlag();
            }
        }
        return "--global";
    }
}
