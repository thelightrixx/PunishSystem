package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.NoteRecord;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.Limits;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.IpMuteService;
import de.lightrixx.punish.service.IpTimeoutService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.NoteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.TimeParser;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@SuppressWarnings("checkstyle:parameternumber")
public final class PunishEditCommand extends PunishCommand {

    private static final String MODUS_SHOW = "show";

    private static final String TYP_VERSTOSS = "verstoss";
    private static final String TYP_WARN = "warn";
    private static final String TYP_NOTE = "note";
    private static final String TYP_BAN = "ban";
    private static final String TYP_MUTE = "mute";
    private static final String TYP_TIMEOUT = "timeout";
    private static final String TYP_BAN_IP = "ban-ip";
    private static final String TYP_MUTE_IP = "mute-ip";
    private static final String TYP_TIMEOUT_IP = "timeout-ip";

    private static final List<String> TYPEN = List.of(
            TYP_VERSTOSS, TYP_WARN, TYP_NOTE,
            TYP_BAN, TYP_MUTE, TYP_TIMEOUT,
            TYP_BAN_IP, TYP_MUTE_IP, TYP_TIMEOUT_IP);

    private static final List<String> LEVEL_VORSCHLAEGE = List.of(
            "level=low", "level=medium", "level=high");

    private final VerstossService verstossService;
    private final WarnService warnService;
    private final NoteService noteService;
    private final BanService banService;
    private final MuteService muteService;
    private final TimeoutService timeoutService;
    private final IpBanService ipBanService;
    private final IpMuteService ipMuteService;
    private final IpTimeoutService ipTimeoutService;
    private final TimeParser timeParser;

    @SuppressWarnings("checkstyle:parameternumber")
    public PunishEditCommand(MessageService messageService,
                             Konfiguration configManager,
                             ProtectionService protectionService,
                             PlayerCache playerCache,
                             VerstossService verstossService,
                             WarnService warnService,
                             NoteService noteService,
                             BanService banService,
                             MuteService muteService,
                             TimeoutService timeoutService,
                             IpBanService ipBanService,
                             IpMuteService ipMuteService,
                             IpTimeoutService ipTimeoutService,
                             TimeParser timeParser,
                             AuftragsPlaner taskScheduler,
                             SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.verstossService = Objects.requireNonNull(verstossService, "Der VerstossService darf nicht null sein.");
        this.warnService = Objects.requireNonNull(warnService, "Der WarnService darf nicht null sein.");
        this.noteService = Objects.requireNonNull(noteService, "Der NoteService darf nicht null sein.");
        this.banService = Objects.requireNonNull(banService, "Der BanService darf nicht null sein.");
        this.muteService = Objects.requireNonNull(muteService, "Der MuteService darf nicht null sein.");
        this.timeoutService = Objects.requireNonNull(timeoutService, "Der TimeoutService darf nicht null sein.");
        this.ipBanService = Objects.requireNonNull(ipBanService, "Der IpBanService darf nicht null sein.");
        this.ipMuteService = Objects.requireNonNull(ipMuteService, "Der IpMuteService darf nicht null sein.");
        this.ipTimeoutService = Objects.requireNonNull(ipTimeoutService, "Der IpTimeoutService darf nicht null sein.");
        this.timeParser = Objects.requireNonNull(timeParser, "Der TimeParser darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_EDIT.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return true;
    }

    @Override
    public boolean hatRecht(BefehlsSender sender, String[] args) {
        if (sender == null) {
            return false;
        }
        if (sender.hatRecht(Permissions.PUNISH_EDIT.node())) {
            return true;
        }
        String[] check = args == null ? new String[0] : args;
        String typArg = "";
        if (check.length >= 1) {
            if (MODUS_SHOW.equalsIgnoreCase(check[0]) && check.length >= 2) {
                typArg = check[1];
            } else {
                typArg = check[0];
            }
        }
        String typ = normalisiereTyp(typArg);
        if (typ == null) {
            return sender.hatRecht(Permissions.PUNISH_VERSTOSS.node())
                    || sender.hatRecht(Permissions.PUNISH_WARN.node())
                    || sender.hatRecht(Permissions.PUNISH_NOTE.node())
                    || sender.hatRecht(Permissions.PUNISH_BAN.node())
                    || sender.hatRecht(Permissions.PUNISH_MUTE.node())
                    || sender.hatRecht(Permissions.PUNISH_TIMEOUT.node())
                    || sender.hatRecht(Permissions.PUNISH_BANIP.node())
                    || sender.hatRecht(Permissions.PUNISH_MUTEIP.node())
                    || sender.hatRecht(Permissions.PUNISH_TIMEOUTIP.node());
        }
        return switch (typ) {
            case TYP_VERSTOSS -> sender.hatRecht(Permissions.PUNISH_VERSTOSS.node())
                    || sender.hatRecht(Permissions.PUNISH_VERSTOSS_EDIT.node());
            case TYP_WARN -> sender.hatRecht(Permissions.PUNISH_WARN.node());
            case TYP_NOTE -> sender.hatRecht(Permissions.PUNISH_NOTE.node());
            case TYP_BAN -> sender.hatRecht(Permissions.PUNISH_BAN.node());
            case TYP_MUTE -> sender.hatRecht(Permissions.PUNISH_MUTE.node());
            case TYP_TIMEOUT -> sender.hatRecht(Permissions.PUNISH_TIMEOUT.node());
            case TYP_BAN_IP -> sender.hatRecht(Permissions.PUNISH_BANIP.node());
            case TYP_MUTE_IP -> sender.hatRecht(Permissions.PUNISH_MUTEIP.node());
            case TYP_TIMEOUT_IP -> sender.hatRecht(Permissions.PUNISH_TIMEOUTIP.node());
            default -> false;
        };
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        ScopeParser.Ergebnis scope = scopeAusArgs(args == null ? new String[0] : args);
        if (blockiereBeiUnbekanntemScope(sender, scope)) {
            return;
        }
        boolean silent = silentAuswerten(sender, scope);
        String[] bereinigt = scope.bereinigteArgs();
        ServerScope scopeSelektor = scope.scope();
        if (bereinigt.length == 0) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        if (MODUS_SHOW.equalsIgnoreCase(bereinigt[0])) {
            verarbeitenShow(sender, ohneErstes(bereinigt), scopeSelektor);
            return;
        }
        String typ = normalisiereTyp(bereinigt[0]);
        if (typ == null) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        verarbeitenEdit(sender, typ, ohneErstes(bereinigt), scopeSelektor, silent);
    }

    private void verarbeitenShow(BefehlsSender sender, String[] args, ServerScope scope) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        String typ = normalisiereTyp(args[0]);
        if (typ == null) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        switch (typ) {
            case TYP_VERSTOSS -> zeigeVerstoss(sender, args[1], args.length >= 3 ? args[2] : null);
            case TYP_WARN -> zeigeWarn(sender, args[1], args.length >= 3 ? args[2] : null);
            case TYP_NOTE -> zeigeNotiz(sender, args[1], args.length >= 3 ? args[2] : null);
            case TYP_BAN -> zeigeBan(sender, args[1], scope);
            case TYP_MUTE -> zeigeMute(sender, args[1], scope);
            case TYP_TIMEOUT -> zeigeTimeout(sender, args[1], scope);
            case TYP_BAN_IP -> zeigeBanIp(sender, args[1], scope);
            case TYP_MUTE_IP -> zeigeMuteIp(sender, args[1], scope);
            case TYP_TIMEOUT_IP -> zeigeTimeoutIp(sender, args[1], scope);
            default -> messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
        }
    }

    private boolean schutzBlockiertAnzeige(BefehlsSender sender, CachedPlayer spieler) {
        try {
            if (protectionService.istGeschuetzt(spieler.uuid())) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return true;
            }
        } catch (Exception e) {
            if (protectionService.istFailClosed()) {
                messageService.senden(sender, MessageKeys.SCHUTZ_OFFLINE_UNBEKANNT,
                        Map.of("spieler", spieler.name()));
                return true;
            }
        }
        return false;
    }

    private void zeigeVerstoss(BefehlsSender sender, String zielName, String nummerRoh) {
        Optional<CachedPlayer> ziel = zielAufloesen(zielName);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", zielName));
            return;
        }
        CachedPlayer spieler = ziel.get();
        if (schutzBlockiertAnzeige(sender, spieler)) {
            return;
        }
        List<VerstossRecord> alle;
        try {
            alle = verstossService.findeVerstoesse(spieler.uuid());
        } catch (Exception e) {
            LOG.warn("Punish-Edit-Show Verstöße für {} fehlgeschlagen.", spieler.uuid(), e);
            return;
        }
        if (alle == null || alle.isEmpty()) {
            messageService.senden(sender, MessageKeys.VERSTOESSE_LEER, Map.of("spieler", spieler.name()));
            return;
        }
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        if (nummerRoh != null && !nummerRoh.isBlank()) {
            Optional<Integer> nummer = parseNummer(nummerRoh);
            if (nummer.isEmpty()) {
                messageService.senden(sender, MessageKeys.NUMMER_UNGUELTIG, Map.of("nummer", nummerRoh));
                return;
            }
            VerstossRecord treffer = null;
            for (VerstossRecord v : alle) {
                if (v != null && v.nummer() == nummer.get()) {
                    treffer = v;
                    break;
                }
            }
            if (treffer == null) {
                messageService.senden(sender, MessageKeys.VERSTOSS_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            zeilen.add(mini.deserialize("<yellow>Verstoß <white>#" + treffer.nummer()
                    + "<yellow> von <white>" + mini.escapeTags(spieler.name())));
            zeilen.add(mini.deserialize("<gray>Stufe: <white>" + mini.escapeTags(treffer.stufe().schluessel())
                    + " <gray>Grund: <white>" + mini.escapeTags(treffer.grund())));
            zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(treffer.ausfuehrer())
                    + " <gray>am <white>" + mini.escapeTags(ZeitFormat.formatZeitpunkt(treffer.erstelltAm()))
                    + " <gray>(Datum/Ausführer sind gesperrt)"));
            String basis = "/punish-edit verstoss " + spieler.name() + " " + treffer.nummer() + " ";
            zeilen.add(mini.deserialize("<gray>Klick: ")
                    .append(mini.deserialize("<green>[Grund ändern]")
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis + "grund="))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    mini.deserialize("<gray>Grund ändern"))))
                    .append(mini.deserialize(" <yellow>[Level ändern]")
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis + "level="))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    mini.deserialize("<gray>Level ändern")))));
            messageService.sendeBlock(sender, zeilen);
            return;
        }
        zeilen.add(mini.deserialize("<gray>Verstöße von <white>" + mini.escapeTags(spieler.name()) + "<gray>:"));
        alle.stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(VerstossRecord::nummer).reversed())
                .limit(10)
                .forEach(v -> {
                    String kennung = de.lightrixx.punish.model.VerstossKennung.kennung(v);
                    String vorschlag = "/punish-edit show verstoss " + spieler.name() + " " + v.nummer();
                    zeilen.add(mini.deserialize("<gray>#<white>" + v.nummer() + "<gray> ["
                                    + mini.escapeTags(v.stufe().schluessel()) + "] <white>"
                                    + mini.escapeTags(kennung))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    mini.deserialize("<gray>Klicken für Details")))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(vorschlag)));
                });
        messageService.sendeBlock(sender, zeilen);
    }

    private void zeigeWarn(BefehlsSender sender, String zielName, String nummerRoh) {
        Optional<CachedPlayer> ziel = zielAufloesen(zielName);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", zielName));
            return;
        }
        CachedPlayer spieler = ziel.get();
        if (schutzBlockiertAnzeige(sender, spieler)) {
            return;
        }
        List<WarnRecord> alle;
        try {
            alle = warnService.findeWarns(spieler.uuid());
        } catch (Exception e) {
            LOG.warn("Punish-Edit-Show Warns für {} fehlgeschlagen.", spieler.uuid(), e);
            return;
        }
        if (alle == null || alle.isEmpty()) {
            messageService.senden(sender, MessageKeys.WARNS_LEER, Map.of("spieler", spieler.name()));
            return;
        }
        var mini = MiniMessageUtil.miniMessage();
        if (nummerRoh != null && !nummerRoh.isBlank()) {
            Optional<Integer> nummer = parseNummer(nummerRoh);
            if (nummer.isEmpty()) {
                messageService.senden(sender, MessageKeys.NUMMER_UNGUELTIG, Map.of("nummer", nummerRoh));
                return;
            }
            WarnRecord treffer = null;
            for (WarnRecord w : alle) {
                if (w != null && w.nummer() == nummer.get()) {
                    treffer = w;
                    break;
                }
            }
            if (treffer == null) {
                messageService.senden(sender, MessageKeys.WARN_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
            zeilen.add(mini.deserialize("<yellow>Warnung <white>#" + treffer.nummer()
                    + "<yellow> für <white>" + mini.escapeTags(spieler.name())));
            zeilen.add(mini.deserialize("<gray>Grund: <white>" + mini.escapeTags(treffer.grund())));
            zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(treffer.ausfuehrer())
                    + " <gray>am <white>" + mini.escapeTags(ZeitFormat.formatZeitpunkt(treffer.erstelltAm()))));
            String basis = "/punish-edit warn " + spieler.name() + " " + treffer.nummer() + " grund=";
            zeilen.add(mini.deserialize("<green>[Grund ändern]")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            mini.deserialize("<gray>Klicken zum Übernehmen"))));
            messageService.sendeBlock(sender, zeilen);
            return;
        }
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<gray>Warnungen von <white>" + mini.escapeTags(spieler.name()) + "<gray>:"));
        alle.stream().filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(WarnRecord::nummer).reversed()).limit(10)
                .forEach(w -> zeilen.add(mini.deserialize("<gray>#<white>" + w.nummer() + "<gray> – <white>"
                                + mini.escapeTags(WarnCommand.vorschau(w)))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                mini.deserialize("<gray>Klicken für Details")))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(
                                "/punish-edit show warn " + spieler.name() + " " + w.nummer()))));
        messageService.sendeBlock(sender, zeilen);
    }

    private void zeigeNotiz(BefehlsSender sender, String zielName, String nummerRoh) {
        Optional<CachedPlayer> ziel = zielAufloesen(zielName);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", zielName));
            return;
        }
        CachedPlayer spieler = ziel.get();
        if (schutzBlockiertAnzeige(sender, spieler)) {
            return;
        }
        List<NoteRecord> alle;
        try {
            alle = noteService.findeNotizen(spieler.uuid());
        } catch (Exception e) {
            LOG.warn("Punish-Edit-Show Notizen für {} fehlgeschlagen.", spieler.uuid(), e);
            return;
        }
        if (alle == null || alle.isEmpty()) {
            messageService.senden(sender, MessageKeys.NOTIZEN_LEER, Map.of("spieler", spieler.name()));
            return;
        }
        var mini = MiniMessageUtil.miniMessage();
        if (nummerRoh != null && !nummerRoh.isBlank()) {
            Optional<Integer> nummer = parseNummer(nummerRoh);
            if (nummer.isEmpty()) {
                messageService.senden(sender, MessageKeys.NUMMER_UNGUELTIG, Map.of("nummer", nummerRoh));
                return;
            }
            NoteRecord treffer = null;
            for (NoteRecord n : alle) {
                if (n != null && n.nummer() == nummer.get()) {
                    treffer = n;
                    break;
                }
            }
            if (treffer == null) {
                messageService.senden(sender, MessageKeys.NOTE_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
            zeilen.add(mini.deserialize("<yellow>Notiz <white>#" + treffer.nummer()
                    + "<yellow> für <white>" + mini.escapeTags(spieler.name())));
            zeilen.add(mini.deserialize("<gray>Text: <white>" + mini.escapeTags(treffer.text())));
            zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(treffer.ausfuehrer())
                    + " <gray>am <white>" + mini.escapeTags(ZeitFormat.formatZeitpunkt(treffer.erstelltAm()))));
            String basis = "/punish-edit note " + spieler.name() + " " + treffer.nummer() + " text=";
            zeilen.add(mini.deserialize("<green>[Text ändern]")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            mini.deserialize("<gray>Klicken zum Übernehmen"))));
            messageService.sendeBlock(sender, zeilen);
            return;
        }
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<gray>Notizen von <white>" + mini.escapeTags(spieler.name()) + "<gray>:"));
        alle.stream().filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(NoteRecord::nummer).reversed()).limit(10)
                .forEach(n -> zeilen.add(mini.deserialize("<gray>#<white>" + n.nummer() + "<gray> – <white>"
                                + mini.escapeTags(PunishNoteCommand.vorschau(n)))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                mini.deserialize("<gray>Klicken für Details")))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(
                                "/punish-edit show note " + spieler.name() + " " + n.nummer()))));
        messageService.sendeBlock(sender, zeilen);
    }

    private void zeigeBan(BefehlsSender sender, String zielName, ServerScope scope) {
        Optional<CachedPlayer> ziel = zielAufloesen(zielName);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", zielName));
            return;
        }
        CachedPlayer spieler = ziel.get();
        if (schutzBlockiertAnzeige(sender, spieler)) {
            return;
        }
        long jetzt = System.currentTimeMillis();
        var aktiv = banService.findeAbdeckend(spieler.uuid(), scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEBANNT, Map.of("spieler", spieler.name()));
            return;
        }
        var b = aktiv.get();
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<yellow>Bann für <white>" + mini.escapeTags(spieler.name())));
        zeilen.add(mini.deserialize("<gray>Grund: <white>" + mini.escapeTags(b.grund())));
        String ablauf = b.istPermanent() ? "Permanent" : ZeitFormat.formatZeitpunkt(b.ablaufAm());
        String rest = b.istPermanent() ? "–" : ZeitFormat.formatRestzeit(b.verbleibendeMillis(jetzt));
        zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(b.ausfuehrer())
                + " <gray>bis <white>" + mini.escapeTags(ablauf) + " <gray>(noch " + mini.escapeTags(rest) + ")"));
        zeilen.add(mini.deserialize("<gray>Scope: <white>" + mini.escapeTags(b.scope().anzeige())));
        String basis = "/punish-edit ban " + spieler.name() + " dauer= grund=";
        zeilen.add(mini.deserialize("<green>[Bearbeiten]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        mini.deserialize("<gray>Klicken zum Übernehmen"))));
        messageService.sendeBlock(sender, zeilen);
    }

    private void zeigeMute(BefehlsSender sender, String zielName, ServerScope scope) {
        Optional<CachedPlayer> ziel = zielAufloesen(zielName);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", zielName));
            return;
        }
        CachedPlayer spieler = ziel.get();
        if (schutzBlockiertAnzeige(sender, spieler)) {
            return;
        }
        long jetzt = System.currentTimeMillis();
        var aktiv = muteService.findeAbdeckend(spieler.uuid(), scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEMUTET, Map.of("spieler", spieler.name()));
            return;
        }
        var m = aktiv.get();
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<yellow>Mute für <white>" + mini.escapeTags(spieler.name())));
        zeilen.add(mini.deserialize("<gray>Grund: <white>" + mini.escapeTags(m.grund())));
        zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(m.ausfuehrer())
                + " <gray>bis <white>" + mini.escapeTags(ZeitFormat.formatZeitpunkt(m.ablaufAm()))
                + " <gray>(noch " + mini.escapeTags(ZeitFormat.formatRestzeit(m.verbleibendeMillis(jetzt))) + ")"));
        zeilen.add(mini.deserialize("<gray>Scope: <white>" + mini.escapeTags(m.scope().anzeige())));
        String basis = "/punish-edit mute " + spieler.name() + " dauer= grund=";
        zeilen.add(mini.deserialize("<green>[Bearbeiten]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        mini.deserialize("<gray>Klicken zum Übernehmen"))));
        messageService.sendeBlock(sender, zeilen);
    }

    private void zeigeTimeout(BefehlsSender sender, String zielName, ServerScope scope) {
        Optional<CachedPlayer> ziel = zielAufloesen(zielName);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", zielName));
            return;
        }
        CachedPlayer spieler = ziel.get();
        if (schutzBlockiertAnzeige(sender, spieler)) {
            return;
        }
        long jetzt = System.currentTimeMillis();
        var aktiv = timeoutService.findeAbdeckend(spieler.uuid(), scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT, Map.of("spieler", spieler.name()));
            return;
        }
        var t = aktiv.get();
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<yellow>Timeout für <white>" + mini.escapeTags(spieler.name())));
        zeilen.add(mini.deserialize("<gray>Grund: <white>" + mini.escapeTags(t.grund())));
        zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(t.ausfuehrer())
                + " <gray>bis <white>" + mini.escapeTags(ZeitFormat.formatZeitpunkt(t.ablaufAm()))
                + " <gray>(noch " + mini.escapeTags(ZeitFormat.formatRestzeit(t.verbleibendeMillis(jetzt))) + ")"));
        zeilen.add(mini.deserialize("<gray>Scope: <white>" + mini.escapeTags(t.scope().anzeige())));
        String basis = "/punish-edit timeout " + spieler.name() + " dauer= grund=";
        zeilen.add(mini.deserialize("<green>[Bearbeiten]")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        mini.deserialize("<gray>Klicken zum Übernehmen"))));
        messageService.sendeBlock(sender, zeilen);
    }

    private void zeigeBanIp(BefehlsSender sender, String eingabe, ServerScope scope) {
        Optional<IpZiel> ziel = ipAufloesen(eingabe);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", eingabe));
            return;
        }
        String ip = ziel.get().ip();
        long jetzt = System.currentTimeMillis();
        var aktiv = ipBanService.ipFindeAbdeckend(ip, scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEBANNT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
            return;
        }
        var b = aktiv.get();
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<yellow>IP-Bann für <white>"
                + mini.escapeTags(PunishStatusCommand.maskeIp(sender, ip))));
        zeilen.add(mini.deserialize("<gray>Grund: <white>" + mini.escapeTags(b.grund())));
        String ablauf = b.istPermanent() ? "Permanent" : ZeitFormat.formatZeitpunkt(b.ablaufAm());
        zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(b.ausfuehrer())
                + " <gray>bis <white>" + mini.escapeTags(ablauf)));
        zeilen.add(mini.deserialize("<gray>Scope: <white>" + mini.escapeTags(b.scope().anzeige())));
        if (sender.hatRecht(Permissions.PUNISH_ALTS.node())) {
            String basis = "/punish-edit ban-ip " + ip + " dauer= grund=";
            zeilen.add(mini.deserialize("<green>[Bearbeiten]")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            mini.deserialize("<gray>Klicken zum Übernehmen"))));
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private void zeigeMuteIp(BefehlsSender sender, String eingabe, ServerScope scope) {
        Optional<IpZiel> ziel = ipAufloesen(eingabe);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", eingabe));
            return;
        }
        String ip = ziel.get().ip();
        long jetzt = System.currentTimeMillis();
        var aktiv = ipMuteService.findeAbdeckend(ip, scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEMUTET_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
            return;
        }
        var m = aktiv.get();
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<yellow>IP-Mute für <white>"
                + mini.escapeTags(PunishStatusCommand.maskeIp(sender, ip))));
        zeilen.add(mini.deserialize("<gray>Grund: <white>" + mini.escapeTags(m.grund())));
        zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(m.ausfuehrer())
                + " <gray>bis <white>" + mini.escapeTags(ZeitFormat.formatZeitpunkt(m.ablaufAm()))
                + " <gray>(noch " + mini.escapeTags(ZeitFormat.formatRestzeit(m.verbleibendeMillis(jetzt))) + ")"));
        if (sender.hatRecht(Permissions.PUNISH_ALTS.node())) {
            String basis = "/punish-edit mute-ip " + ip + " dauer= grund=";
            zeilen.add(mini.deserialize("<green>[Bearbeiten]")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            mini.deserialize("<gray>Klicken zum Übernehmen"))));
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private void zeigeTimeoutIp(BefehlsSender sender, String eingabe, ServerScope scope) {
        Optional<IpZiel> ziel = ipAufloesen(eingabe);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", eingabe));
            return;
        }
        String ip = ziel.get().ip();
        long jetzt = System.currentTimeMillis();
        var aktiv = ipTimeoutService.findeAbdeckend(ip, scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
            return;
        }
        var t = aktiv.get();
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<yellow>IP-Timeout für <white>"
                + mini.escapeTags(PunishStatusCommand.maskeIp(sender, ip))));
        zeilen.add(mini.deserialize("<gray>Grund: <white>" + mini.escapeTags(t.grund())));
        zeilen.add(mini.deserialize("<gray>Von <white>" + mini.escapeTags(t.ausfuehrer())
                + " <gray>bis <white>" + mini.escapeTags(ZeitFormat.formatZeitpunkt(t.ablaufAm()))
                + " <gray>(noch " + mini.escapeTags(ZeitFormat.formatRestzeit(t.verbleibendeMillis(jetzt))) + ")"));
        if (sender.hatRecht(Permissions.PUNISH_ALTS.node())) {
            String basis = "/punish-edit timeout-ip " + ip + " dauer= grund=";
            zeilen.add(mini.deserialize("<green>[Bearbeiten]")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(basis))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            mini.deserialize("<gray>Klicken zum Übernehmen"))));
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private void verarbeitenEdit(BefehlsSender sender, String typ, String[] args, ServerScope scope,
                                 boolean silent) {
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        switch (typ) {
            case TYP_VERSTOSS -> editVerstoss(sender, args, silent);
            case TYP_WARN -> editWarn(sender, args, silent);
            case TYP_NOTE -> editNote(sender, args, silent);
            case TYP_BAN -> editBan(sender, args, scope, silent);
            case TYP_MUTE -> editMute(sender, args, scope, silent);
            case TYP_TIMEOUT -> editTimeout(sender, args, scope, silent);
            case TYP_BAN_IP -> editBanIp(sender, args, scope, silent);
            case TYP_MUTE_IP -> editMuteIp(sender, args, scope, silent);
            case TYP_TIMEOUT_IP -> editTimeoutIp(sender, args, scope, silent);
            default -> messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
        }
    }

    private void editVerstoss(BefehlsSender sender, String[] args, boolean silent) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        Optional<Integer> nummer = parseNummer(args[1]);
        if (nummer.isEmpty()) {
            messageService.senden(sender, MessageKeys.NUMMER_UNGUELTIG, Map.of("nummer", args[1]));
            return;
        }
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.VERSTOSS, TYP_VERSTOSS, () -> {
            if (verstossService.istAutoBannAktiv(spieler.uuid())) {
                messageService.senden(sender, MessageKeys.AUTO_BANN_AKTIV, Map.of("spieler", spieler.name()));
                return;
            }
            List<VerstossRecord> alle;
            try {
                alle = verstossService.findeVerstoesse(spieler.uuid());
            } catch (Exception e) {
                LOG.warn("Verstoß-Edit: Laden für {} fehlgeschlagen.", spieler.uuid(), e);
                messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
                return;
            }
            VerstossRecord alt = null;
            if (alle != null) {
                for (VerstossRecord v : alle) {
                    if (v != null && v.nummer() == nummer.get()) {
                        alt = v;
                        break;
                    }
                }
            }
            if (alt == null) {
                messageService.senden(sender, MessageKeys.VERSTOSS_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            int start = 2 + skipVorschauTokens(args, 1, nummer.get(), alt);
            Felder felder = parseFelder(args, start);
            if (felder.stufeUngueltig) {
                messageService.senden(sender, MessageKeys.LEVEL_UNGUELTIG, Map.of("level", felder.stufeRoh));
                return;
            }
            String neuerGrund = felder.grund;
            if (neuerGrund == null || neuerGrund.isBlank()) {
                if (felder.stufe != null) {
                    neuerGrund = alt.grund();
                } else {
                    messageService.senden(sender, MessageKeys.KEIN_GRUND);
                    return;
                }
            }
            boolean ok = verstossService.bearbeiten(spieler.uuid(), spieler.name(), nummer.get(),
                    neuerGrund, felder.stufe, ausfuehrerName(sender), silent);
            if (!ok) {
                messageService.senden(sender, MessageKeys.VERSTOSS_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            VerstossStufe effektiveStufe = felder.stufe != null ? felder.stufe : alt.stufe();
            messageService.senden(sender, MessageKeys.VERSTOSS_EDIT_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "id", String.valueOf(nummer.get()),
                    "stufe", effektiveStufe.schluessel(),
                    "grund", neuerGrund));
        });
    }

    private void editWarn(BefehlsSender sender, String[] args, boolean silent) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        Optional<Integer> nummer = parseNummer(args[1]);
        if (nummer.isEmpty()) {
            messageService.senden(sender, MessageKeys.NUMMER_UNGUELTIG, Map.of("nummer", args[1]));
            return;
        }
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.WARN, TYP_WARN, () -> {
            List<WarnRecord> alle;
            try {
                alle = warnService.findeWarns(spieler.uuid());
            } catch (Exception e) {
                LOG.warn("Warn-Edit: Laden für {} fehlgeschlagen.", spieler.uuid(), e);
                messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
                return;
            }
            WarnRecord alt = null;
            if (alle != null) {
                for (WarnRecord w : alle) {
                    if (w != null && w.nummer() == nummer.get()) {
                        alt = w;
                        break;
                    }
                }
            }
            if (alt == null) {
                messageService.senden(sender, MessageKeys.WARN_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            int start = 2 + skipVorschauTokens(args, 1, nummer.get(), WarnCommand.vorschau(alt));
            Felder felder = parseFelder(args, start);
            String neuerGrund = felder.grund;
            if (neuerGrund == null || neuerGrund.isBlank()) {
                messageService.senden(sender, MessageKeys.KEIN_GRUND);
                return;
            }
            var ok = warnService.bearbeiten(spieler.uuid(), spieler.name(), nummer.get(),
                    neuerGrund, ausfuehrerName(sender), silent);
            if (ok.isEmpty()) {
                messageService.senden(sender, MessageKeys.WARN_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            messageService.senden(sender, MessageKeys.WARN_EDIT_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "id", String.valueOf(nummer.get()),
                    "grund", neuerGrund));
        });
    }

    private void editNote(BefehlsSender sender, String[] args, boolean silent) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        Optional<Integer> nummer = parseNummer(args[1]);
        if (nummer.isEmpty()) {
            messageService.senden(sender, MessageKeys.NUMMER_UNGUELTIG, Map.of("nummer", args[1]));
            return;
        }
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.NOTE, TYP_NOTE, () -> {
            List<NoteRecord> alle;
            try {
                alle = noteService.findeNotizen(spieler.uuid());
            } catch (Exception e) {
                LOG.warn("Note-Edit: Laden für {} fehlgeschlagen.", spieler.uuid(), e);
                messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
                return;
            }
            NoteRecord alt = null;
            if (alle != null) {
                for (NoteRecord n : alle) {
                    if (n != null && n.nummer() == nummer.get()) {
                        alt = n;
                        break;
                    }
                }
            }
            if (alt == null) {
                messageService.senden(sender, MessageKeys.NOTE_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            int start = 2 + skipVorschauTokens(args, 1, nummer.get(), PunishNoteCommand.vorschau(alt));
            Felder felder = parseFelder(args, start);
            String neuerText = felder.grund;
            if (neuerText == null || neuerText.isBlank()) {
                messageService.senden(sender, MessageKeys.KEIN_GRUND);
                return;
            }
            var ok = noteService.bearbeiten(spieler.uuid(), spieler.name(), nummer.get(),
                    neuerText, ausfuehrerName(sender), silent);
            if (ok.isEmpty()) {
                messageService.senden(sender, MessageKeys.NOTE_NICHT_GEFUNDEN,
                        Map.of("spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            messageService.senden(sender, MessageKeys.NOTE_EDIT_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "id", String.valueOf(nummer.get())));
        });
    }

    private void editBan(BefehlsSender sender, String[] args, ServerScope scope, boolean silent) {
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        long jetzt = System.currentTimeMillis();
        var aktiv = banService.findeAbdeckend(spieler.uuid(), scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEBANNT, Map.of("spieler", spieler.name()));
            return;
        }
        if (aktiv.get().autoBann()) {
            messageService.senden(sender, MessageKeys.AUTO_BANN_AKTIV, Map.of("spieler", spieler.name()));
            return;
        }
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.BAN, TYP_BAN, () -> {
            var erneut = banService.findeAbdeckend(spieler.uuid(), scope).filter(b -> b.istAktiv(System.currentTimeMillis()));
            if (erneut.isEmpty() || erneut.get().autoBann()) {
                messageService.senden(sender, erneut.isEmpty()
                        ? MessageKeys.NICHT_GEBANNT : MessageKeys.AUTO_BANN_AKTIV,
                        Map.of("spieler", spieler.name()));
                return;
            }
            Felder felder = parseFelderMitLegacyDauer(args, 1);
            if (felder.dauerUngueltig) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            if (felder.perm && (sender == null || !sender.hatRecht(Permissions.PUNISH_PERMABANN.node()))) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (erneut.get().istPermanent() && felder.dauerMillis != null && (sender == null
                    || !sender.hatRecht(Permissions.PUNISH_PERMABANN.node()))) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (felder.perm) {
                String permGrund = felder.grund;
                if (permGrund != null && permGrund.isBlank()) {
                    permGrund = null;
                }
                var permOk = banService.bearbeitenPermanent(spieler.uuid(), spieler.name(), permGrund,
                        ausfuehrerName(sender), scope, herkunftVon(sender), silent);
                if (permOk.isEmpty()) {
                    messageService.senden(sender, MessageKeys.NICHT_GEBANNT, Map.of("spieler", spieler.name()));
                    return;
                }
                messageService.senden(sender, MessageKeys.BAN_EDIT_ERFOLG, Map.of(
                        "spieler", spieler.name(),
                        "grund", permOk.get().grund(),
                        "dauer", Limits.PERMANENT_ANZEIGE));
                return;
            }
            Long dauer = felder.dauerMillis;
            String neuerGrund = felder.grund;
            if (dauer == null && (neuerGrund == null || neuerGrund.isBlank())) {
                messageService.senden(sender, MessageKeys.PUNISH_EDIT_KEIN_FELD);
                return;
            }
            if (neuerGrund != null && neuerGrund.isBlank()) {
                neuerGrund = null;
            }
            if (dauer == null && erneut.get().istPermanent()) {
                var permOk = banService.bearbeitenPermanent(spieler.uuid(), spieler.name(), neuerGrund,
                        ausfuehrerName(sender), scope, herkunftVon(sender), silent);
                if (permOk.isEmpty()) {
                    messageService.senden(sender, MessageKeys.NICHT_GEBANNT, Map.of("spieler", spieler.name()));
                    return;
                }
                messageService.senden(sender, MessageKeys.BAN_EDIT_ERFOLG, Map.of(
                        "spieler", spieler.name(),
                        "dauer", Limits.PERMANENT_ANZEIGE,
                        "grund", permOk.get().grund()));
                return;
            }
            boolean nurGrund = dauer == null && neuerGrund != null && !neuerGrund.isBlank();
            if (dauer == null) {
                dauer = erneut.get().verbleibendeMillis(System.currentTimeMillis());
                if (dauer <= 0) {
                    messageService.senden(sender, MessageKeys.NICHT_GEBANNT,
                            Map.of("spieler", spieler.name()));
                    return;
                }
            }
            if (!istAblaufMoeglich(dauer)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long max = configManager.banMaxMinuten() * 60000L;
            if (!nurGrund && dauer > max) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.banMaxMinuten())));
                return;
            }
            long min = configManager.banMinMinuten() * 60000L;
            if (!nurGrund && dauer < min) {
                messageService.senden(sender, MessageKeys.LIMIT_UNTERSCHRITTEN,
                        Map.of("min", String.valueOf(configManager.banMinMinuten())));
                return;
            }
            var ok = banService.bearbeiten(spieler.uuid(), spieler.name(), dauer, neuerGrund,
                    ausfuehrerName(sender), scope, herkunftVon(sender), silent);
            if (ok.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_GEBANNT, Map.of("spieler", spieler.name()));
                return;
            }
            messageService.senden(sender, MessageKeys.BAN_EDIT_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "dauer", ZeitFormat.formatRestzeit(dauer),
                    "grund", ok.get().grund()));
        });
    }

    private void editMute(BefehlsSender sender, String[] args, ServerScope scope, boolean silent) {
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        long jetzt = System.currentTimeMillis();
        var aktiv = muteService.findeAbdeckend(spieler.uuid(), scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEMUTET, Map.of("spieler", spieler.name()));
            return;
        }
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.MUTE, TYP_MUTE, () -> {
            var erneut = muteService.findeAbdeckend(spieler.uuid(), scope)
                    .filter(b -> b.istAktiv(System.currentTimeMillis()));
            if (erneut.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_GEMUTET, Map.of("spieler", spieler.name()));
                return;
            }
            Felder felder = parseFelderMitLegacyDauer(args, 1);
            if (felder.perm) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (felder.dauerUngueltig) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            Long dauer = felder.dauerMillis;
            String neuerGrund = felder.grund;
            if (dauer == null && (neuerGrund == null || neuerGrund.isBlank())) {
                messageService.senden(sender, MessageKeys.PUNISH_EDIT_KEIN_FELD);
                return;
            }
            boolean nurGrund = dauer == null;
            if (dauer == null) {
                dauer = Math.max(1L, erneut.get().verbleibendeMillis(System.currentTimeMillis()));
            }
            if (!istAblaufMoeglich(dauer)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long max = configManager.muteMaxMinuten() * 60000L;
            if (!nurGrund && dauer > max) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.muteMaxMinuten())));
                return;
            }
            if (neuerGrund != null && neuerGrund.isBlank()) {
                neuerGrund = null;
            }
            var ok = muteService.bearbeiten(spieler.uuid(), spieler.name(), dauer, neuerGrund,
                    ausfuehrerName(sender), scope, herkunftVon(sender), silent);
            if (ok.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_GEMUTET, Map.of("spieler", spieler.name()));
                return;
            }
            messageService.senden(sender, MessageKeys.MUTE_EDIT_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "dauer", ZeitFormat.formatRestzeit(dauer),
                    "grund", ok.get().grund()));
        });
    }

    private void editTimeout(BefehlsSender sender, String[] args, ServerScope scope, boolean silent) {
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        long jetzt = System.currentTimeMillis();
        var aktiv = timeoutService.findeAbdeckend(spieler.uuid(), scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT, Map.of("spieler", spieler.name()));
            return;
        }
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.TIMEOUT, TYP_TIMEOUT, () -> {
            var erneut = timeoutService.findeAbdeckend(spieler.uuid(), scope)
                    .filter(b -> b.istAktiv(System.currentTimeMillis()));
            if (erneut.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT, Map.of("spieler", spieler.name()));
                return;
            }
            Felder felder = parseFelderMitLegacyDauer(args, 1);
            if (felder.perm) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (felder.dauerUngueltig) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            Long dauer = felder.dauerMillis;
            String neuerGrund = felder.grund;
            if (dauer == null && (neuerGrund == null || neuerGrund.isBlank())) {
                messageService.senden(sender, MessageKeys.PUNISH_EDIT_KEIN_FELD);
                return;
            }
            boolean nurGrund = dauer == null;
            if (dauer == null) {
                dauer = Math.max(1L, erneut.get().verbleibendeMillis(System.currentTimeMillis()));
            }
            if (!istAblaufMoeglich(dauer)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long max = configManager.timeoutMaxMinuten() * 60000L;
            if (!nurGrund && dauer > max) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.timeoutMaxMinuten())));
                return;
            }
            if (neuerGrund != null && neuerGrund.isBlank()) {
                neuerGrund = null;
            }
            var ok = timeoutService.bearbeiten(spieler.uuid(), spieler.name(), dauer, neuerGrund,
                    ausfuehrerName(sender), scope, herkunftVon(sender), silent);
            if (ok.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT, Map.of("spieler", spieler.name()));
                return;
            }
            messageService.senden(sender, MessageKeys.TIMEOUT_EDIT_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "dauer", ZeitFormat.formatRestzeit(dauer),
                    "grund", ok.get().grund()));
        });
    }

    private void editBanIp(BefehlsSender sender, String[] args, ServerScope scope, boolean silent) {
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<IpZiel> roh = ipAufloesen(args[0]);
        if (roh.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        String ip = roh.get().ip();
        long jetzt = System.currentTimeMillis();
        var aktiv = ipBanService.ipFindeAbdeckend(ip, scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEBANNT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
            return;
        }
        Runnable fortsetzung = () -> {
            var erneut = ipBanService.ipFindeAbdeckend(ip, scope)
                    .filter(b -> b.istAktiv(System.currentTimeMillis()));
            if (erneut.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_GEBANNT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
                return;
            }
            Felder felder = parseFelderMitLegacyDauer(args, 1);
            if (felder.dauerUngueltig) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            if (felder.perm && (sender == null || !sender.hatRecht(Permissions.PUNISH_PERMABANN.node()))) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (erneut.get().istPermanent() && felder.dauerMillis != null && (sender == null
                    || !sender.hatRecht(Permissions.PUNISH_PERMABANN.node()))) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            Long dauer = felder.perm ? null : felder.dauerMillis;
            String neuerGrund = felder.grund;
            if (dauer == null && !felder.perm && (neuerGrund == null || neuerGrund.isBlank())) {
                if (erneut.get().istPermanent()) {
                    dauer = null;
                } else {
                    messageService.senden(sender, MessageKeys.PUNISH_EDIT_KEIN_FELD);
                    return;
                }
            }
            boolean nurGrund = dauer == null && !felder.perm
                    && neuerGrund != null && !neuerGrund.isBlank()
                    && !erneut.get().istPermanent();
            if (nurGrund) {
                dauer = erneut.get().verbleibendeMillis(System.currentTimeMillis());
                if (dauer <= 0) {
                    messageService.senden(sender, MessageKeys.NICHT_GEBANNT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
                    return;
                }
            }
            if (dauer != null) {
                if (!istAblaufMoeglich(dauer)) {
                    messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                    return;
                }
                long max = configManager.banMaxMinuten() * 60000L;
                if (!nurGrund && dauer > max) {
                    messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                            Map.of("max", String.valueOf(configManager.banMaxMinuten())));
                    return;
                }
                long min = configManager.banMinMinuten() * 60000L;
                if (!nurGrund && dauer < min) {
                    messageService.senden(sender, MessageKeys.LIMIT_UNTERSCHRITTEN,
                            Map.of("min", String.valueOf(configManager.banMinMinuten())));
                    return;
                }
            }
            if (neuerGrund != null && neuerGrund.isBlank()) {
                neuerGrund = null;
            }
            var ok = ipBanService.ipBannBearbeiten(ip, dauer, neuerGrund,
                    ausfuehrerName(sender), scope, herkunftVon(sender), silent);
            if (ok.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_GEBANNT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
                return;
            }
            String dauerText = dauer != null ? ZeitFormat.formatRestzeit(dauer) : "Permanent";
            messageService.senden(sender, MessageKeys.IP_BAN_EDIT_ERFOLG, Map.of(
                    "ip", PunishStatusCommand.maskeIp(sender, ok.get().ip()),
                    "dauer", dauerText,
                    "grund", ok.get().grund()));
        };
        if (roh.get().spieler() != null) {
            mitSchutz(sender, args, roh.get().spieler(), ProtectionService.IgnoreTyp.BAN, TYP_BAN_IP, fortsetzung);
        } else {
            fortsetzung.run();
        }
    }

    private void editMuteIp(BefehlsSender sender, String[] args, ServerScope scope, boolean silent) {
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<IpZiel> roh = ipAufloesen(args[0]);
        if (roh.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        String ip = roh.get().ip();
        long jetzt = System.currentTimeMillis();
        var aktiv = ipMuteService.findeAbdeckend(ip, scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEMUTET_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
            return;
        }
        Runnable fortsetzung = () -> {
            var erneut = ipMuteService.findeAbdeckend(ip, scope)
                    .filter(b -> b.istAktiv(System.currentTimeMillis()));
            if (erneut.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_GEMUTET_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
                return;
            }
            Felder felder = parseFelderMitLegacyDauer(args, 1);
            if (felder.perm) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (felder.dauerUngueltig) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            Long dauer = felder.dauerMillis;
            String neuerGrund = felder.grund;
            if (dauer == null && (neuerGrund == null || neuerGrund.isBlank())) {
                messageService.senden(sender, MessageKeys.PUNISH_EDIT_KEIN_FELD);
                return;
            }
            boolean nurGrund = dauer == null;
            if (dauer == null) {
                dauer = Math.max(1L, erneut.get().verbleibendeMillis(System.currentTimeMillis()));
            }
            if (!istAblaufMoeglich(dauer)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long max = configManager.muteMaxMinuten() * 60000L;
            if (!nurGrund && dauer > max) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.muteMaxMinuten())));
                return;
            }
            if (neuerGrund != null && neuerGrund.isBlank()) {
                neuerGrund = null;
            }
            var ok = ipMuteService.bearbeiten(ip, dauer, neuerGrund,
                    ausfuehrerName(sender), scope, herkunftVon(sender), silent);
            if (ok.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_GEMUTET_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
                return;
            }
            messageService.senden(sender, MessageKeys.IP_MUTE_EDIT_ERFOLG, Map.of(
                    "ip", PunishStatusCommand.maskeIp(sender, ok.get().ip()),
                    "dauer", ZeitFormat.formatRestzeit(dauer),
                    "grund", ok.get().grund()));
        };
        if (roh.get().spieler() != null) {
            mitSchutz(sender, args, roh.get().spieler(), ProtectionService.IgnoreTyp.MUTE, TYP_MUTE_IP, fortsetzung);
        } else {
            fortsetzung.run();
        }
    }

    private void editTimeoutIp(BefehlsSender sender, String[] args, ServerScope scope, boolean silent) {
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SYNTAX_PUNISH_EDIT);
            return;
        }
        Optional<IpZiel> roh = ipAufloesen(args[0]);
        if (roh.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        String ip = roh.get().ip();
        long jetzt = System.currentTimeMillis();
        var aktiv = ipTimeoutService.findeAbdeckend(ip, scope).filter(b -> b.istAktiv(jetzt));
        if (aktiv.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
            return;
        }
        Runnable fortsetzung = () -> {
            var erneut = ipTimeoutService.findeAbdeckend(ip, scope)
                    .filter(b -> b.istAktiv(System.currentTimeMillis()));
            if (erneut.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
                return;
            }
            Felder felder = parseFelderMitLegacyDauer(args, 1);
            if (felder.perm) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (felder.dauerUngueltig) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            Long dauer = felder.dauerMillis;
            String neuerGrund = felder.grund;
            if (dauer == null && (neuerGrund == null || neuerGrund.isBlank())) {
                messageService.senden(sender, MessageKeys.PUNISH_EDIT_KEIN_FELD);
                return;
            }
            boolean nurGrund = dauer == null;
            if (dauer == null) {
                dauer = Math.max(1L, erneut.get().verbleibendeMillis(System.currentTimeMillis()));
            }
            if (!istAblaufMoeglich(dauer)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long max = configManager.timeoutMaxMinuten() * 60000L;
            if (!nurGrund && dauer > max) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.timeoutMaxMinuten())));
                return;
            }
            if (neuerGrund != null && neuerGrund.isBlank()) {
                neuerGrund = null;
            }
            var ok = ipTimeoutService.bearbeiten(ip, dauer, neuerGrund,
                    ausfuehrerName(sender), scope, herkunftVon(sender), silent);
            if (ok.isEmpty()) {
                messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT_IP, Map.of("ip", PunishStatusCommand.maskeIp(sender, ip)));
                return;
            }
            messageService.senden(sender, MessageKeys.IP_TIMEOUT_EDIT_ERFOLG, Map.of(
                    "ip", PunishStatusCommand.maskeIp(sender, ok.get().ip()),
                    "dauer", ZeitFormat.formatRestzeit(dauer),
                    "grund", ok.get().grund()));
        };
        if (roh.get().spieler() != null) {
            mitSchutz(sender, args, roh.get().spieler(), ProtectionService.IgnoreTyp.TIMEOUT, TYP_TIMEOUT_IP, fortsetzung);
        } else {
            fortsetzung.run();
        }
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        int position = args == null ? 0 : args.length;
        if (position <= 1) {
            return "Modus oder Typ";
        }
        if (position == 2) {
            return "Typ oder Spieler";
        }
        if (position == 3) {
            return "Spieler";
        }
        return "Feld";
    }

    private boolean darfTypSehen(BefehlsSender sender, String typ) {
        if (sender == null || typ == null) {
            return false;
        }
        if (sender.hatRecht(Permissions.PUNISH_EDIT.node())) {
            return true;
        }
        return switch (typ) {
            case TYP_VERSTOSS -> sender.hatRecht(Permissions.PUNISH_VERSTOSS.node())
                    || sender.hatRecht(Permissions.PUNISH_VERSTOSS_EDIT.node());
            case TYP_WARN -> sender.hatRecht(Permissions.PUNISH_WARN.node());
            case TYP_NOTE -> sender.hatRecht(Permissions.PUNISH_NOTE.node());
            case TYP_BAN -> sender.hatRecht(Permissions.PUNISH_BAN.node());
            case TYP_MUTE -> sender.hatRecht(Permissions.PUNISH_MUTE.node());
            case TYP_TIMEOUT -> sender.hatRecht(Permissions.PUNISH_TIMEOUT.node());
            case TYP_BAN_IP -> sender.hatRecht(Permissions.PUNISH_BANIP.node());
            case TYP_MUTE_IP -> sender.hatRecht(Permissions.PUNISH_MUTEIP.node());
            case TYP_TIMEOUT_IP -> sender.hatRecht(Permissions.PUNISH_TIMEOUTIP.node());
            default -> false;
        };
    }

    private List<String> erlaubteTypen(BefehlsSender sender) {
        if (sender != null && sender.hatRecht(Permissions.PUNISH_EDIT.node())) {
            return new ArrayList<>(TYPEN);
        }
        List<String> erlaubte = new ArrayList<>();
        for (String typ : TYPEN) {
            if (darfTypSehen(sender, typ)) {
                erlaubte.add(typ);
            }
        }
        return erlaubte;
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        String letztes = args[args.length - 1];
        if (letztes != null && letztes.startsWith("--")) {
            return scopeVorschlaege(letztes);
        }
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        if (args.length == 1) {
            List<String> alle = new ArrayList<>();
            alle.add(MODUS_SHOW);
            alle.addAll(erlaubteTypen(sender));
            return filtere(alle, args[0]);
        }
        boolean show = MODUS_SHOW.equalsIgnoreCase(args[0]);
        if (show) {
            if (args.length == 2) {
                return filtere(erlaubteTypen(sender), args[1]);
            }
            String typ = normalisiereTyp(args[1]);
            if (typ == null) {
                return List.of();
            }
            if (!darfTypSehen(sender, typ)) {
                return List.of();
            }
            if (args.length == 3) {
                return filtere(zielKandidaten(sender, typ, args[2]), args[2]);
            }
            if (args.length == 4 && istListenTyp(typ)) {
                return filtere(nummernVorschlaege(typ, args[2], args[3]), args[3]);
            }
            return List.of();
        }
        String typ = normalisiereTyp(args[0]);
        if (typ == null) {
            return List.of();
        }
        if (!darfTypSehen(sender, typ)) {
            return List.of();
        }
        if (args.length == 2) {
            return filtere(zielKandidaten(sender, typ, args[1]), args[1]);
        }
        if (args.length == 3 && istListenTyp(typ)) {
            return filtere(nummernVorschlaege(typ, args[1], args[2]), args[2]);
        }
        String aktuell = args[args.length - 1];
        if (aktuell != null && aktuell.contains("=")) {
            String key = aktuell.substring(0, aktuell.indexOf('=')).toLowerCase(Locale.ROOT);
            if (key.equals("level") || key.equals("stufe")) {
                return filtere(LEVEL_VORSCHLAEGE, aktuell);
            }
            if (key.equals("dauer") || key.equals("duration") || key.equals("zeit")) {
                List<String> vorschlaege = new ArrayList<>();
                for (String d : dauerVorschlaegeOhnePermFuer(typ)) {
                    vorschlaege.add("dauer=" + d);
                }
                if (TYP_BAN_IP.equals(typ)
                        && sender != null && sender.hatRecht(Permissions.PUNISH_PERMABANN.node())) {
                    vorschlaege.add("dauer=perm");
                }
                return filtere(vorschlaege, aktuell);
            }
        }
        List<String> gemischt = new ArrayList<>();
        if (TYP_VERSTOSS.equals(typ)) {
            gemischt.addAll(LEVEL_VORSCHLAEGE);
        }
        if (istAktivTyp(typ) || istIpTyp(typ)) {
            for (String d : dauerVorschlaegeOhnePermFuer(typ)) {
                gemischt.add("dauer=" + d);
            }
            gemischt.add("grund=");
        } else if (TYP_NOTE.equals(typ)) {
            gemischt.add("text=");
        } else {
            gemischt.add("grund=");
        }
        gemischt.addAll(grundVorschlaege(typ));
        gemischt.addAll(scopeVorschlaege("--"));
        gemischt.addAll(silentVorschlag(sender, ""));
        return filtere(gemischt, aktuell);
    }

    private List<String> zielKandidaten(BefehlsSender sender, String typ, String prefix) {
        if (istIpTyp(typ)) {
            List<String> alle = new ArrayList<>(bestrafbareSpielerNamen());
            alle.addAll(spielerNamenMitPrefix(prefix, 25));
            if (sender != null && sender.hatRecht(Permissions.PUNISH_ALTS.node())) {
                try {
                    alle.addAll(ipVorschlaege(prefix));
                } catch (Exception e) {
                    LOG.debug("IP-Tab-Complete übersprungen.", e);
                }
            }
            return alle.stream().distinct().limit(50).toList();
        }
        List<String> alle = new ArrayList<>(bestrafbareSpielerNamen());
        alle.addAll(spielerNamenMitPrefix(prefix, 25));
        return alle.stream().distinct().limit(50).toList();
    }

    private List<String> ipVorschlaege(String prefix) {
        String vergleich = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        List<String> treffer = new ArrayList<>();
        try {
            for (var b : ipBanService.findeAktiveIpBannsFuerTab()) {
                if (b == null || b.ip() == null) {
                    continue;
                }
                if (vergleich.isEmpty() || b.ip().toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                    treffer.add(b.ip());
                }
                if (treffer.size() >= 15) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("IP-Bann-Tab-Scan übersprungen.", e);
        }
        try {
            for (var m : ipMuteService.findeAktiveMutesFuerTab()) {
                if (m == null || m.ip() == null) {
                    continue;
                }
                if (vergleich.isEmpty() || m.ip().toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                    if (!treffer.contains(m.ip())) {
                        treffer.add(m.ip());
                    }
                }
                if (treffer.size() >= 25) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("IP-Mute-Tab-Scan übersprungen.", e);
        }
        try {
            for (var t : ipTimeoutService.findeAktiveTimeoutsFuerTab()) {
                if (t == null || t.ip() == null) {
                    continue;
                }
                if (vergleich.isEmpty() || t.ip().toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                    if (!treffer.contains(t.ip())) {
                        treffer.add(t.ip());
                    }
                }
                if (treffer.size() >= 35) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("IP-Timeout-Tab-Scan übersprungen.", e);
        }
        return treffer;
    }

    private List<String> nummernVorschlaege(String typ, String spielerName, String aktuell) {
        Optional<CachedPlayer> ziel = zielAufloesen(spielerName);
        if (ziel.isEmpty()) {
            return List.of();
        }
        try {
            if (TYP_VERSTOSS.equals(typ)) {
                List<VerstossRecord> alle = verstossService.findeVerstoesse(ziel.get().uuid());
                if (alle == null || alle.isEmpty()) {
                    return List.of();
                }
                return filtere(alle.stream().filter(Objects::nonNull)
                        .sorted(java.util.Comparator.comparingInt(VerstossRecord::nummer).reversed())
                        .limit(10).map(v -> String.valueOf(v.nummer())).toList(), aktuell);
            }
            if (TYP_WARN.equals(typ)) {
                List<WarnRecord> alle = warnService.findeWarns(ziel.get().uuid());
                if (alle == null || alle.isEmpty()) {
                    return List.of();
                }
                return filtere(alle.stream().filter(Objects::nonNull)
                        .sorted(java.util.Comparator.comparingInt(WarnRecord::nummer).reversed())
                        .limit(10).map(w -> String.valueOf(w.nummer())).toList(), aktuell);
            }
            if (TYP_NOTE.equals(typ)) {
                List<NoteRecord> alle = noteService.findeNotizen(ziel.get().uuid());
                if (alle == null || alle.isEmpty()) {
                    return List.of();
                }
                return filtere(alle.stream().filter(Objects::nonNull)
                        .sorted(java.util.Comparator.comparingInt(NoteRecord::nummer).reversed())
                        .limit(10).map(n -> String.valueOf(n.nummer())).toList(), aktuell);
            }
        } catch (Exception e) {
            LOG.debug("Edit-Nummern-Vorschau für '{}' fehlgeschlagen.", typ, e);
            return List.of();
        }
        return List.of();
    }

    static String normalisiereTyp(String roh) {
        if (roh == null || roh.isBlank()) {
            return null;
        }
        return switch (roh.trim().toLowerCase(Locale.ROOT)) {
            case "verstoss", "verstoß", "verstosse", "verstöße", "violation" -> TYP_VERSTOSS;
            case "warn", "warnung", "verwarnung" -> TYP_WARN;
            case "note", "notes", "notiz", "notizen", "punish-note" -> TYP_NOTE;
            case "ban", "bann" -> TYP_BAN;
            case "mute", "mut" -> TYP_MUTE;
            case "timeout" -> TYP_TIMEOUT;
            case "ban-ip", "banip", "ip-ban", "ipban" -> TYP_BAN_IP;
            case "mute-ip", "muteip", "ip-mute", "ipmute" -> TYP_MUTE_IP;
            case "timeout-ip", "timeoutip", "ip-timeout", "iptimeout" -> TYP_TIMEOUT_IP;
            default -> null;
        };
    }

    private static boolean istListenTyp(String typ) {
        return TYP_VERSTOSS.equals(typ) || TYP_WARN.equals(typ) || TYP_NOTE.equals(typ);
    }

    private static boolean istAktivTyp(String typ) {
        return TYP_BAN.equals(typ) || TYP_MUTE.equals(typ) || TYP_TIMEOUT.equals(typ);
    }

    private static boolean istIpTyp(String typ) {
        return TYP_BAN_IP.equals(typ) || TYP_MUTE_IP.equals(typ) || TYP_TIMEOUT_IP.equals(typ);
    }

    private record Felder(String grund, VerstossStufe stufe, boolean stufeUngueltig, String stufeRoh,
                          Long dauerMillis, boolean dauerUngueltig, String dauerRoh, boolean perm,
                          boolean grundExplizit) {
    }

    private Felder parseFelder(String[] args, int start) {
        StringBuilder grundSammler = new StringBuilder();
        StringBuilder legacySammler = new StringBuilder();
        boolean grundModus = false;
        boolean grundExplizit = false;
        VerstossStufe stufe = null;
        boolean stufeUngueltig = false;
        String stufeRoh = "";
        Long dauerMillis = null;
        boolean dauerUngueltig = false;
        String dauerRoh = "";
        boolean perm = false;
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            int gleich = token.indexOf('=');
            if (gleich > 0) {
                String key = token.substring(0, gleich).trim().toLowerCase(Locale.ROOT);
                String wert = token.substring(gleich + 1);
                switch (key) {
                    case "grund", "reason", "text" -> {
                        grundModus = true;
                        grundExplizit = true;
                        anhaengen(grundSammler, wert);
                    }
                    case "level", "stufe" -> {
                        String w = wert.trim();
                        var parsed = VerstossStufe.parse(w);
                        if (parsed.isPresent()) {
                            stufe = parsed.get();
                        } else {
                            stufeUngueltig = true;
                            stufeRoh = w;
                        }
                        grundModus = false;
                    }
                    case "dauer", "duration", "zeit", "time" -> {
                        String w = wert.trim();
                        if (w.equalsIgnoreCase("perm") || w.equalsIgnoreCase("permanent")
                                || w.equalsIgnoreCase("perma")) {
                            perm = true;
                        } else {
                            ParseResult parsed = timeParser.parsen(w);
                            if (parsed instanceof ParseResult.Geltzeit(long millis)) {
                                dauerMillis = millis;
                            } else if (parsed instanceof ParseResult.Permanent) {
                                perm = true;
                            } else {
                                dauerUngueltig = true;
                                dauerRoh = w;
                            }
                        }
                        grundModus = false;
                    }
                    default -> {
                        if (grundModus) {
                            anhaengen(grundSammler, token);
                        } else {
                            anhaengen(legacySammler, token);
                        }
                    }
                }
            } else if (grundModus) {
                anhaengen(grundSammler, token);
            } else {
                anhaengen(legacySammler, token);
            }
        }
        String grund = grundSammler.isEmpty() ? legacySammler.toString() : grundSammler.toString();
        return new Felder(normalisiereGrund(grund), stufe, stufeUngueltig, stufeRoh,
                dauerMillis, dauerUngueltig, dauerRoh, perm, grundExplizit);
    }

    private Felder parseFelderMitLegacyDauer(String[] args, int start) {
        Felder basis = parseFelder(args, start);
        if (basis.dauerMillis != null || basis.perm || basis.dauerUngueltig || basis.grundExplizit) {
            return basis;
        }
        if (basis.grund == null || basis.grund.isBlank()) {
            return basis;
        }
        String[] teile = basis.grund.split(" ", 2);
        String kandidat = teile[0];
        ParseResult parsed = timeParser.parsen(kandidat);
        if (parsed instanceof ParseResult.Geltzeit(long millis)) {
            String rest = teile.length > 1 ? teile[1] : "";
            return new Felder(normalisiereGrund(rest), basis.stufe, basis.stufeUngueltig, basis.stufeRoh,
                    millis, false, "", false, false);
        }
        if (parsed instanceof ParseResult.Permanent) {
            String rest = teile.length > 1 ? teile[1] : "";
            return new Felder(normalisiereGrund(rest), basis.stufe, basis.stufeUngueltig, basis.stufeRoh,
                    null, false, "", true, false);
        }
        boolean siehtNachDauerAus = kandidat.matches("(?i)^[0-9]+[a-z]*$|^(perm|permanent|perma)$");
        if (siehtNachDauerAus) {
            return new Felder(basis.grund, basis.stufe, basis.stufeUngueltig, basis.stufeRoh,
                    null, true, kandidat, false, false);
        }
        return basis;
    }

    private static void anhaengen(StringBuilder sammler, String teil) {
        if (teil == null) {
            return;
        }
        String getrimmt = teil.trim();
        if (getrimmt.isEmpty() && sammler.isEmpty()) {
            return;
        }
        if (!sammler.isEmpty()) {
            sammler.append(" ");
        }
        sammler.append(teil);
    }

    private Optional<Integer> parseNummer(String roh) {
        if (roh == null || roh.isBlank()) {
            return Optional.empty();
        }
        int trenner = roh.indexOf(' ');
        String teil = (trenner >= 0 ? roh.substring(0, trenner) : roh).trim();
        try {
            int nummer = Integer.parseInt(teil);
            return nummer >= 1 ? Optional.of(nummer) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String[] ohneErstes(String[] args) {
        if (args == null || args.length <= 1) {
            return new String[0];
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private int skipVorschauTokens(String[] args, int nummerIndex, int nummer, VerstossRecord record) {
        if (record == null || args.length <= nummerIndex + 1) {
            return 0;
        }
        String kennung;
        try {
            kennung = MiniMessageUtil.miniMessage().escapeTags(
                    de.lightrixx.punish.model.VerstossKennung.kennung(record));
        } catch (Exception e) {
            return 0;
        }
        return skipTokenSequenz(args, nummerIndex, nummer, kennung.split(" "));
    }

    private int skipVorschauTokens(String[] args, int nummerIndex, int nummer, String vorschau) {
        if (vorschau == null || vorschau.isBlank() || args.length <= nummerIndex + 1) {
            return 0;
        }
        String sauber;
        try {
            sauber = MiniMessageUtil.miniMessage().escapeTags(vorschau);
        } catch (Exception e) {
            sauber = vorschau;
        }
        return skipTokenSequenz(args, nummerIndex, nummer, sauber.split(" "));
    }

    private int skipTokenSequenz(String[] args, int nummerIndex, int nummer, String[] tokens) {
        if (tokens == null || tokens.length == 0) {
            return 0;
        }
        int start = nummerIndex + 1;
        if (args.length >= start + tokens.length) {
            boolean treffer = true;
            for (int i = 0; i < tokens.length; i++) {
                if (!tokens[i].equals(args[start + i])) {
                    treffer = false;
                    break;
                }
            }
            if (treffer) {
                return tokens.length;
            }
        }

        if (tokens.length > 1 && args.length > nummerIndex && args[nummerIndex] != null
                && args[nummerIndex].trim().equals(nummer + " " + tokens[0])) {
            String[] rest = Arrays.copyOfRange(tokens, 1, tokens.length);
            if (args.length >= start + rest.length) {
                boolean treffer = true;
                for (int i = 0; i < rest.length; i++) {
                    if (!rest[i].equals(args[start + i])) {
                        treffer = false;
                        break;
                    }
                }
                if (treffer) {
                    return rest.length;
                }
            }
        }
        return 0;
    }

    private record IpZiel(String ip, CachedPlayer spieler) {
    }

    private Optional<IpZiel> ipAufloesen(String eingabe) {
        if (eingabe == null || eingabe.isBlank() || eingabe.contains("/")) {
            return Optional.empty();
        }
        try {
            String direkt = IpBanRecord.kanonisieren(eingabe.trim());
            return Optional.of(new IpZiel(direkt, null));
        } catch (Exception e) {
            LOG.debug("IP-Direktauflösung fehlgeschlagen.", e);
        }
        Optional<CachedPlayer> ziel = zielAufloesen(eingabe.trim());
        if (ziel.isEmpty()) {
            return Optional.empty();
        }
        String ip = ziel.get().letzteIp();
        if (ip == null || ip.isBlank() || ip.equalsIgnoreCase("unbekannt")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new IpZiel(IpBanRecord.kanonisieren(ip), ziel.get()));
        } catch (Exception e) {
            LOG.debug("IP-Kanonisierung für '{}' fehlgeschlagen.", ip, e);
            return Optional.empty();
        }
    }
}
