package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ReportCommand extends PunishCommand {

    private final ReportService reportService;

    public ReportCommand(MessageService messageService,
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
        return Permissions.PUNISH_REPORT.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        final String[] bereinigt = ohneScopeFlags(args);
        if (bereinigt.length < 2) {
            messageService.senden(sender, MessageKeys.SYNTAX_REPORT);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(bereinigt[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", bereinigt[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        mitSchutz(sender, bereinigt, spieler, ProtectionService.IgnoreTyp.REPORT, () -> {
            String grund = grundAusArgumentenOhneStandard(bereinigt, 1);
            if (grund.isBlank()) {
                messageService.senden(sender, MessageKeys.KEIN_GRUND);
                return;
            }
            Optional<UUID> reporterUuid = eigeneUuid(sender);
            String reporterName = ausfuehrerName(sender);
            String server = herkunftVon(sender);
            ReportService.Ergebnis ergebnis;
            try {
                ergebnis = reportService.melden(spieler.uuid(), spieler.name(),
                        reporterUuid.orElse(null), reporterName, grund, server);
            } catch (IllegalArgumentException e) {
                LOG.warn("Report von {} für {} abgewiesen: {}", reporterName, spieler.uuid(), e.getMessage());
                messageService.senden(sender, MessageKeys.KEIN_GRUND);
                return;
            }
            if (!ergebnis.erfolg()) {
                String fehler = ergebnis.fehler() == null ? MessageKeys.KEIN_GRUND : ergebnis.fehler();
                Map<String, String> platzhalter = switch (fehler) {
                    case MessageKeys.REPORT_COOLDOWN -> Map.of(
                            "verbleibend", formatiereDauer(ergebnis.cooldownRestSekunden()),
                            "spieler", spieler.name());
                    case MessageKeys.REPORT_DUPLIKAT -> Map.of(
                            "spieler", spieler.name(),
                            "id", ergebnis.report() == null
                                    ? "?" : String.valueOf(ergebnis.report().id()));
                    default -> Map.of("spieler", spieler.name());
                };
                messageService.senden(sender, fehler, platzhalter);
                return;
            }
            ReportRecord record = ergebnis.report();
            messageService.senden(sender, MessageKeys.REPORT_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "id", String.valueOf(record.id()),
                    "grund", record.grund()));
        });
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        int position = args == null ? 0 : args.length;
        if (position <= 1) {
            return "Spieler";
        }
        return "Grund";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args.length <= 1) {
            return filtere(spielerKandidaten(args.length == 0 ? "" : args[0]),
                    args.length == 0 ? "" : args[0]);
        }
        return filtere(grundVorschlaege("report"), args[args.length - 1]);
    }

    private List<String> spielerKandidaten(String prefix) {
        Set<String> kombiniert = new LinkedHashSet<>();
        String vergleich = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        try {
            if (verzeichnis != null) {
                for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                    if (online == null || online.name() == null) {
                        continue;
                    }
                    try {
                        if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.REPORT)) {
                            continue;
                        }
                    } catch (Exception e) {
                        LOG.debug("Online-Schutzcheck für Report-Tab-Complete übersprungen.", e);
                        continue;
                    }
                    if (vergleich.isEmpty() || online.name().toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                        kombiniert.add(online.name());
                    }
                    if (kombiniert.size() >= 25) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Online-Scan für Report-Tab-Complete übersprungen.", e);
        }
        try {
            kombiniert.addAll(spielerNamenMitPrefix(prefix, 25));
        } catch (Exception e) {
            LOG.debug("Cache-Scan für Report-Tab-Complete übersprungen.", e);
        }
        List<String> ergebnis = new ArrayList<>(kombiniert);
        ergebnis.sort(String.CASE_INSENSITIVE_ORDER);
        return ergebnis.stream().distinct().limit(50).toList();
    }

    static String formatiereDauer(long sekunden) {
        if (sekunden <= 0) {
            return "einen Moment";
        }
        if (sekunden < 60) {
            return sekunden + "s";
        }
        long minuten = sekunden / 60L;
        long rest = sekunden % 60L;
        if (minuten < 60) {
            return rest == 0 ? minuten + "m" : minuten + "m " + rest + "s";
        }
        return minuten + "m";
    }

    static String formatiereZeitpunkt(long zeitpunkt) {
        try {
            return ZeitFormat.formatZeitpunkt(zeitpunkt);
        } catch (Exception e) {
            return String.valueOf(zeitpunkt);
        }
    }
}
