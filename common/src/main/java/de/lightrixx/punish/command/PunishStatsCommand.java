package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PunishStatsCommand extends PunishCommand {

    private static final String ZEITRAUM_ALLE = "all";
    private static final String ZEITRAUM_STANDARD = "7d";

    private final HistoryService historyService;
    private final ReportService reportService;
    private final TimeParser timeParser;

    public PunishStatsCommand(MessageService messageService,
                              Konfiguration configManager,
                              ProtectionService protectionService,
                              PlayerCache playerCache,
                              SpielerVerzeichnis verzeichnis,
                              HistoryService historyService,
                              ReportService reportService,
                              TimeParser timeParser) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.historyService = Objects.requireNonNull(historyService,
                "Der HistoryService darf nicht null sein.");
        this.reportService = reportService;
        this.timeParser = timeParser;
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_STATS.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (args != null && args.length > 2) {
            messageService.senden(sender, MessageKeys.PUNISH_STATS_SYNTAX);
            return;
        }
        String zeitraum = ZEITRAUM_STANDARD;
        String staffFilter = null;
        if (args != null && args.length >= 1 && args[0] != null && !args[0].isBlank()) {
            String erster = args[0].trim();
            if (istZeitraum(erster)) {
                zeitraum = erster.toLowerCase(Locale.ROOT);
                if (args.length == 2 && args[1] != null && !args[1].isBlank()) {
                    staffFilter = args[1].trim();
                }
            } else {
                if (args.length == 2) {
                    messageService.senden(sender, MessageKeys.PUNISH_STATS_ZEITRAUM_UNGUELTIG,
                            Map.of("zeitraum", erster));
                    return;
                }
                staffFilter = erster;
            }
        }
        Long seit = seitMillis(zeitraum);
        if (seit == null) {
            messageService.senden(sender, MessageKeys.PUNISH_STATS_ZEITRAUM_UNGUELTIG,
                    Map.of("zeitraum", zeitraum));
            return;
        }
        long jetzt = System.currentTimeMillis();
        long grenze = ZEITRAUM_ALLE.equalsIgnoreCase(zeitraum) ? 0L : jetzt - seit;

        Map<String, Zaehler> zaehler = new LinkedHashMap<>();
        try {
            List<HistoryEntry> verlauf = historyService.findeAlle();
            for (HistoryEntry eintrag : verlauf) {
                if (eintrag == null || eintrag.zeitstempel() < grenze) {
                    continue;
                }
                String staff = eintrag.ausfuehrer();
                if (staff == null || staff.isBlank()) {
                    continue;
                }
                Zaehler ziel = zaehler.computeIfAbsent(staff.toLowerCase(Locale.ROOT),
                        schluessel -> new Zaehler(staff));
                zaehleHistory(ziel, eintrag.typ());
            }
        } catch (Exception e) {
            LOG.warn("Team-Statistik konnte den Verlauf nicht laden.", e);
        }
        try {
            if (reportService != null) {
                for (ReportRecord report : reportService.alle()) {
                    if (report == null || report.status() == null || report.status().istOffen()
                            || report.bearbeiter() == null || report.bearbeiter().isBlank()
                            || report.bearbeitetAm() < grenze) {
                        continue;
                    }
                    Zaehler ziel = zaehler.computeIfAbsent(
                            report.bearbeiter().toLowerCase(Locale.ROOT),
                            schluessel -> new Zaehler(report.bearbeiter()));
                    ziel.reports++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Team-Statistik konnte die Reports nicht laden.", e);
        }

        List<Zaehler> ausgabe = new ArrayList<>();
        for (Zaehler eintrag : zaehler.values()) {
            if (staffFilter != null && !eintrag.name.equalsIgnoreCase(staffFilter)) {
                continue;
            }
            if (eintrag.gesamt() > 0) {
                ausgabe.add(eintrag);
            }
        }
        ausgabe.sort(Comparator.comparingInt(Zaehler::gesamt).reversed()
                .thenComparing(zaehlerEintrag -> zaehlerEintrag.name, String.CASE_INSENSITIVE_ORDER));

        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATS_KOPF,
                Map.of("zeitraum", zeitraum)));
        if (ausgabe.isEmpty()) {
            zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATS_LEER,
                    Map.of("zeitraum", zeitraum)));
        } else {
            for (Zaehler eintrag : ausgabe) {
                zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATS_ZEILE, Map.of(
                        "staff", eintrag.name,
                        "banns", String.valueOf(eintrag.banns),
                        "mutes", String.valueOf(eintrag.mutes),
                        "timeouts", String.valueOf(eintrag.timeouts),
                        "warns", String.valueOf(eintrag.warns),
                        "verstoesse", String.valueOf(eintrag.verstoesse),
                        "reports", String.valueOf(eintrag.reports))));
            }
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private boolean istZeitraum(String wert) {
        if (wert == null || wert.isBlank()) {
            return false;
        }
        String klein = wert.trim().toLowerCase(Locale.ROOT);
        if (ZEITRAUM_ALLE.equals(klein)) {
            return true;
        }
        if (timeParser == null) {
            return false;
        }
        try {
            return timeParser.parsen(klein) instanceof ParseResult.Geltzeit;
        } catch (Exception e) {
            return false;
        }
    }

    private Long seitMillis(String zeitraum) {
        if (zeitraum == null || ZEITRAUM_ALLE.equalsIgnoreCase(zeitraum)) {
            return 0L;
        }
        if (timeParser == null) {
            return null;
        }
        try {
            return timeParser.parsen(zeitraum) instanceof ParseResult.Geltzeit geltzeit
                    ? geltzeit.millis() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void zaehleHistory(Zaehler zaehler, HistoryType typ) {
        if (typ == null) {
            return;
        }
        switch (typ) {
            case BAN, PERMABANN, AUTO_BANN, BAN_ALL -> zaehler.banns++;
            case MUTE, MUTE_ALL, IP_MUTE -> zaehler.mutes++;
            case TIMEOUT, TIMEOUT_ALL, IP_TIMEOUT -> zaehler.timeouts++;
            case WARN_ADD -> zaehler.warns++;
            case VERSTOSS_ADD -> zaehler.verstoesse++;
            default -> {
            }
        }
    }

    private static final class Zaehler {
        private final String name;
        private int banns;
        private int mutes;
        private int timeouts;
        private int warns;
        private int verstoesse;
        private int reports;

        private Zaehler(String name) {
            this.name = name;
        }

        private int gesamt() {
            return banns + mutes + timeouts + warns + verstoesse + reports;
        }
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> werte = new ArrayList<>(List.of("7d", "30d", "90d", "all"));
            werte.addAll(spielerVorschlaegeSmart(sender, args[0] == null ? "" : args[0]));
            return filtere(werte, args[0] == null ? "" : args[0]);
        }
        if (args.length == 2) {
            return spielerVorschlaegeSmart(sender, args[1] == null ? "" : args[1]);
        }
        return List.of();
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        if (vorschlag != null && (vorschlag.endsWith("d") || vorschlag.equalsIgnoreCase("all"))) {
            return "Zeitraum";
        }
        return "Staff";
    }
}
