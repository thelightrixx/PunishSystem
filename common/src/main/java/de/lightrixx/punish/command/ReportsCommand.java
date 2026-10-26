package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ReportsCommand extends PunishCommand {

    private static final List<String> UNTERBEFEHLE =
            List.of("list", "info", "accept", "deny", "close", "delete");
    private static final int MAX_ZEILEN = 25;

    private final ReportService reportService;

    public ReportsCommand(MessageService messageService,
                          Konfiguration configManager,
                          ProtectionService protectionService,
                          PlayerCache playerCache,
                          ReportService reportService,
                          SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.reportService = Objects.requireNonNull(reportService,
                "Der ReportService darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_REPORTS.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        ScopeParser.SilentErgebnis silentErgebnis = silentAusArgsOhneScope(sender, args);
        args = silentErgebnis.bereinigteArgs();
        boolean silent = silentErgebnis.silent();
        if (args.length == 0) {
            verarbeitenList(sender, true);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                boolean nurOffen = true;
                if (args.length >= 2) {
                    String filter = args[1].toLowerCase(Locale.ROOT);
                    if (filter.equals("alle") || filter.equals("all")) {
                        nurOffen = false;
                    } else if (!filter.equals("offen") && !filter.equals("open")) {
                        messageService.senden(sender, MessageKeys.SYNTAX_REPORTS);
                        return;
                    }
                }
                verarbeitenList(sender, nurOffen);
            }
            case "info" -> {
                if (args.length < 2) {
                    messageService.senden(sender, MessageKeys.SYNTAX_REPORTS);
                    return;
                }
                verarbeitenInfo(sender, args[1]);
            }
            case "accept" -> {
                if (args.length < 2) {
                    messageService.senden(sender, MessageKeys.SYNTAX_REPORTS);
                    return;
                }
                verarbeitenStatus(sender, args[1], ReportStatus.ANGENOMMEN,
                        grundAusArgumentenOhneStandard(args, 2), silent);
            }
            case "deny" -> {
                if (args.length < 2) {
                    messageService.senden(sender, MessageKeys.SYNTAX_REPORTS);
                    return;
                }
                verarbeitenStatus(sender, args[1], ReportStatus.ABGELEHNT,
                        grundAusArgumentenOhneStandard(args, 2), silent);
            }
            case "close" -> {
                if (args.length < 2) {
                    messageService.senden(sender, MessageKeys.SYNTAX_REPORTS);
                    return;
                }
                verarbeitenStatus(sender, args[1], ReportStatus.ERLEDIGT,
                        grundAusArgumentenOhneStandard(args, 2), silent);
            }
            case "delete" -> {
                if (args.length < 2) {
                    messageService.senden(sender, MessageKeys.SYNTAX_REPORTS);
                    return;
                }
                verarbeitenDelete(sender, args[1]);
            }
            default -> {
                Optional<Long> id = parseId(args[0]);
                if (id.isPresent()) {
                    verarbeitenInfo(sender, args[0]);
                    return;
                }
                messageService.senden(sender, MessageKeys.SYNTAX_REPORTS);
            }
        }
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        if (vorschlag != null && vorschlag.trim().matches("\\d+")) {
            return "Report-ID";
        }
        return kategorieTooltip(vorschlag);
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args.length == 0 ? "" : args[args.length - 1];
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        if (args.length <= 1) {
            List<String> vorschlaege = new ArrayList<>(UNTERBEFEHLE);
            vorschlaege.addAll(offeneIds(args.length == 0 ? "" : args[0]));
            vorschlaege.addAll(silentVorschlag(sender, ""));
            return filtere(vorschlaege.stream().distinct().toList(),
                    args.length == 0 ? "" : args[0]);
        }
        String unterbefehl = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (unterbefehl) {
                case "list" -> filtere(List.of("offen", "alle"), args[1]);
                case "info", "accept", "deny", "close", "delete" ->
                        filtere(offeneIds(args[1]), args[1]);
                default -> List.of();
            };
        }
        return silentVorschlag(sender, args[args.length - 1]);
    }

    private void verarbeitenList(BefehlsSender sender, boolean nurOffen) {
        List<ReportRecord> liste;
        try {
            liste = nurOffen ? reportService.offene() : reportService.alle();
        } catch (Exception e) {
            LOG.warn("Report-Liste konnte nicht geladen werden.", e);
            return;
        }
        if (liste == null || liste.isEmpty()) {
            messageService.senden(sender, MessageKeys.REPORTS_LEER);
            return;
        }
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.REPORTS_KOPF, Map.of(
                "anzahl", String.valueOf(liste.size()))));
        liste.stream()
                .filter(Objects::nonNull)
                .limit(MAX_ZEILEN)
                .forEach(report -> {
                    Map<String, String> ph = platzhalter(report);
                    net.kyori.adventure.text.Component zeile =
                            messageService.komponenteOhnePraefix(MessageKeys.REPORTS_EINTRAG, ph);
                    String vorschlag = "/reports info " + report.id();
                    zeilen.add(zeile
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    mini.deserialize("<gray>Klicken für Details")))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(vorschlag)));
                });
        if (liste.size() > MAX_ZEILEN) {
            zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.REPORTS_MEHR, Map.of(
                    "anzahl", String.valueOf(liste.size() - MAX_ZEILEN))));
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private void verarbeitenInfo(BefehlsSender sender, String rohId) {
        Optional<Long> id = parseId(rohId);
        if (id.isEmpty()) {
            messageService.senden(sender, MessageKeys.REPORT_NICHT_GEFUNDEN, Map.of("id", rohId));
            return;
        }
        Optional<ReportRecord> gefunden;
        try {
            gefunden = reportService.findenNachId(id.get());
        } catch (Exception e) {
            LOG.warn("Report-Info für {} konnte nicht geladen werden.", id.get(), e);
            return;
        }
        if (gefunden.isEmpty()) {
            messageService.senden(sender, MessageKeys.REPORT_NICHT_GEFUNDEN,
                    Map.of("id", String.valueOf(id.get())));
            return;
        }
        messageService.senden(sender, MessageKeys.REPORT_INFO, platzhalter(gefunden.get()));
    }

    private void verarbeitenStatus(BefehlsSender sender, String rohId, ReportStatus status, String grund,
                                   boolean silent) {
        Optional<Long> id = parseId(rohId);
        if (id.isEmpty()) {
            messageService.senden(sender, MessageKeys.REPORT_NICHT_GEFUNDEN, Map.of("id", rohId));
            return;
        }
        Optional<ReportRecord> geschlossen;
        try {
            geschlossen = reportService.schliessen(id.get(), status, ausfuehrerName(sender), grund, silent);
        } catch (de.lightrixx.punish.service.ReportService.ClaimKonflikt konflikt) {
            messageService.senden(sender, MessageKeys.REPORT_BEREITS_UEBERNOMMEN, Map.of(
                    "id", String.valueOf(id.get()),
                    "bearbeiter", konflikt.aktuellerBearbeiter()));
            return;
        } catch (de.lightrixx.punish.service.ReportService.BereitsGeschlossen bereits) {
            messageService.senden(sender, MessageKeys.REPORT_BEREITS_GESCHLOSSEN, Map.of(
                    "id", String.valueOf(id.get()),
                    "status", bereits.status().name()));
            return;
        } catch (Exception e) {
            LOG.warn("Report-Status für {} konnte nicht gesetzt werden.", id.get(), e);
            return;
        }
        if (geschlossen.isEmpty()) {
            messageService.senden(sender, MessageKeys.REPORT_NICHT_GEFUNDEN,
                    Map.of("id", String.valueOf(id.get())));
            return;
        }
        messageService.senden(sender, MessageKeys.REPORT_CLOSE_ERFOLG, Map.of(
                "id", String.valueOf(id.get()),
                "spieler", geschlossen.get().zielName(),
                "status", geschlossen.get().status().name()));
    }

    private void verarbeitenDelete(BefehlsSender sender, String rohId) {
        Optional<Long> id = parseId(rohId);
        if (id.isEmpty()) {
            messageService.senden(sender, MessageKeys.REPORT_NICHT_GEFUNDEN, Map.of("id", rohId));
            return;
        }
        Optional<ReportRecord> entfernt;
        try {
            entfernt = reportService.entfernen(id.get(), ausfuehrerName(sender));
        } catch (Exception e) {
            LOG.warn("Report-Delete für {} fehlgeschlagen.", id.get(), e);
            return;
        }
        if (entfernt.isEmpty()) {
            messageService.senden(sender, MessageKeys.REPORT_NICHT_GEFUNDEN,
                    Map.of("id", String.valueOf(id.get())));
            return;
        }
        messageService.senden(sender, MessageKeys.REPORT_DELETE_ERFOLG, Map.of(
                "id", String.valueOf(id.get()),
                "spieler", entfernt.get().zielName()));
    }

    private List<String> offeneIds(String aktuell) {
        List<ReportRecord> offene;
        try {
            offene = reportService.offene();
        } catch (Exception e) {
            return List.of();
        }
        if (offene == null || offene.isEmpty()) {
            return List.of();
        }
        List<String> ids = offene.stream()
                .filter(Objects::nonNull)
                .limit(25)
                .map(r -> String.valueOf(r.id()))
                .toList();
        return filtere(ids, aktuell);
    }

    private static Optional<Long> parseId(String roh) {
        if (roh == null || roh.isBlank()) {
            return Optional.empty();
        }
        String teil = roh.trim();
        int leer = teil.indexOf(' ');
        if (leer >= 0) {
            teil = teil.substring(0, leer).trim();
        }
        if (teil.startsWith("#")) {
            teil = teil.substring(1);
        }
        try {
            long id = Long.parseLong(teil);
            return id >= 1 ? Optional.of(id) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Map<String, String> platzhalter(ReportRecord report) {
        String grund = ScopeParser.saeubereGrund(report.grund());
        String datum;
        try {
            datum = ZeitFormat.formatZeitpunkt(report.erstelltAm());
        } catch (Exception e) {
            datum = String.valueOf(report.erstelltAm());
        }
        return Map.of(
                "id", String.valueOf(report.id()),
                "spieler", report.zielName(),
                "reporter", report.reporterName(),
                "grund", grund,
                "text", grund,
                "server", report.server(),
                "status", report.status().name(),
                "datum", datum,
                "ausfuehrer", report.bearbeiter().isBlank() ? report.reporterName() : report.bearbeiter(),
                "bearbeiter", report.bearbeiter().isBlank() ? "-" : report.bearbeiter());
    }
}
