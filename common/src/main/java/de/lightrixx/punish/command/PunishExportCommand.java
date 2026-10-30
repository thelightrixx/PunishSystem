package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.NoteRecord;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.WarnRepository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PunishExportCommand extends PunishCommand {

    private static final int HISTORY_LIMIT = 100_000;
    private static final DateTimeFormatter DATEINAME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final HistoryRepository historyRepository;
    private final WarnRepository warnRepository;
    private final NoteRepository noteRepository;
    private final ReportRepository reportRepository;
    private final File datenOrdner;

    public PunishExportCommand(MessageService messageService,
                               Konfiguration configManager,
                               ProtectionService protectionService,
                               PlayerCache playerCache,
                               SpielerVerzeichnis verzeichnis,
                               AuftragsPlaner taskScheduler,
                               HistoryRepository historyRepository,
                               WarnRepository warnRepository,
                               NoteRepository noteRepository,
                               ReportRepository reportRepository,
                               File datenOrdner) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.historyRepository = Objects.requireNonNull(historyRepository,
                "Das HistoryRepository darf nicht null sein.");
        this.warnRepository = Objects.requireNonNull(warnRepository,
                "Das WarnRepository darf nicht null sein.");
        this.noteRepository = Objects.requireNonNull(noteRepository,
                "Das NoteRepository darf nicht null sein.");
        this.reportRepository = Objects.requireNonNull(reportRepository,
                "Das ReportRepository darf nicht null sein.");
        this.datenOrdner = Objects.requireNonNull(datenOrdner,
                "Der Datenordner darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_EXPORT.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (args != null && args.length > 0) {
            messageService.senden(sender, MessageKeys.PUNISH_EXPORT_SYNTAX);
            return;
        }
        try {
            List<HistoryEntry> history = historyRepository.findeAlle(HISTORY_LIMIT);
            Map<UUID, List<WarnRecord>> warns = warnRepository.alle();
            Map<UUID, List<NoteRecord>> notes = noteRepository.alle();
            List<ReportRecord> reports = reportRepository.alle();

            String inhalt = json(history, warns, notes, reports);
            File zielOrdner = new File(datenOrdner, "exports");
            if (!zielOrdner.exists() && !zielOrdner.mkdirs() && !zielOrdner.exists()) {
                throw new IllegalStateException("Export-Ordner konnte nicht angelegt werden.");
            }
            LocalDateTime jetzt = LocalDateTime.now(configManager.anzeigeZone());
            File ziel = new File(zielOrdner, "punish-export-" + DATEINAME.format(jetzt) + ".json");
            Files.writeString(ziel.toPath(), inhalt, StandardCharsets.UTF_8);

            int anzahl = (history == null ? 0 : history.size())
                    + anzahlWerte(warns) + anzahlWerte(notes)
                    + (reports == null ? 0 : reports.size());
            messageService.senden(sender, MessageKeys.PUNISH_EXPORT_ERFOLG, Map.of(
                    "pfad", ziel.getAbsolutePath(),
                    "eintraege", String.valueOf(anzahl)));
        } catch (Exception e) {
            LOG.warn("Punish-Export fehlgeschlagen.", e);
            messageService.senden(sender, MessageKeys.PUNISH_EXPORT_FEHLER, Map.of(
                    "fehler", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        return List.of();
    }

    private static int anzahlWerte(Map<UUID, ?> karte) {
        if (karte == null || karte.isEmpty()) {
            return 0;
        }
        int anzahl = 0;
        for (Object wert : karte.values()) {
            if (wert instanceof java.util.Collection<?> liste) {
                anzahl += liste.size();
            }
        }
        return anzahl;
    }

    private static String json(List<HistoryEntry> history,
                               Map<UUID, List<WarnRecord>> warns,
                               Map<UUID, List<NoteRecord>> notes,
                               List<ReportRecord> reports) {
        StringBuilder out = new StringBuilder(65_536);
        out.append("{\"format\":1,\"erstelltAm\":").append(System.currentTimeMillis());
        out.append(",\"history\":[");
        boolean erster = true;
        if (history != null) {
            for (HistoryEntry eintrag : history) {
                if (eintrag == null) {
                    continue;
                }
                if (!erster) {
                    out.append(',');
                }
                erster = false;
                out.append("{\"id\":").append(eintrag.id())
                        .append(",\"typ\":").append(text(eintrag.typ().name()))
                        .append(",\"ausfuehrer\":").append(text(eintrag.ausfuehrer()))
                        .append(",\"ziel\":").append(text(eintrag.ziel()))
                        .append(",\"grund\":").append(text(eintrag.grund()))
                        .append(",\"zeitstempel\":").append(eintrag.zeitstempel())
                        .append(",\"ablauf\":").append(eintrag.ablauf() == null ? "null" : eintrag.ablauf())
                        .append(",\"aktiv\":").append(eintrag.aktiv())
                        .append(",\"scope\":").append(text(eintrag.scope()))
                        .append(",\"herkunft\":").append(text(eintrag.herkunft()))
                        .append('}');
            }
        }
        out.append("],\"warns\":{");
        warnKarte(out, warns);
        out.append("},\"notes\":{");
        notizKarte(out, notes);
        out.append("},\"reports\":[");
        erster = true;
        if (reports != null) {
            for (ReportRecord report : reports) {
                if (report == null) {
                    continue;
                }
                if (!erster) {
                    out.append(',');
                }
                erster = false;
                out.append("{\"id\":").append(report.id())
                        .append(",\"ziel\":").append(text(report.zielName()))
                        .append(",\"reporter\":").append(text(report.reporterName()))
                        .append(",\"grund\":").append(text(report.grund()))
                        .append(",\"server\":").append(text(report.server()))
                        .append(",\"erstelltAm\":").append(report.erstelltAm())
                        .append(",\"status\":").append(text(report.status().name()))
                        .append(",\"bearbeiter\":").append(text(report.bearbeiter()))
                        .append(",\"bearbeitetAm\":").append(report.bearbeitetAm())
                        .append(",\"closeGrund\":").append(text(report.closeGrund()))
                        .append('}');
            }
        }
        out.append("]}");
        return out.toString();
    }

    private static void warnKarte(StringBuilder out, Map<UUID, List<WarnRecord>> warns) {
        if (warns == null || warns.isEmpty()) {
            return;
        }
        boolean ersterEintrag = true;
        for (Map.Entry<UUID, List<WarnRecord>> eintrag : warns.entrySet()) {
            if (eintrag.getKey() == null || eintrag.getValue() == null) {
                continue;
            }
            if (!ersterEintrag) {
                out.append(',');
            }
            ersterEintrag = false;
            out.append(text(eintrag.getKey().toString())).append(":[");
            boolean ersterWarn = true;
            for (WarnRecord warn : eintrag.getValue()) {
                if (warn == null) {
                    continue;
                }
                if (!ersterWarn) {
                    out.append(',');
                }
                ersterWarn = false;
                out.append("{\"nummer\":").append(warn.nummer())
                        .append(",\"spieler\":").append(text(warn.spielerName()))
                        .append(",\"grund\":").append(text(warn.grund()))
                        .append(",\"ausfuehrer\":").append(text(warn.ausfuehrer()))
                        .append(",\"erstelltAm\":").append(warn.erstelltAm())
                        .append('}');
            }
            out.append(']');
        }
    }

    private static void notizKarte(StringBuilder out, Map<UUID, List<NoteRecord>> notes) {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        boolean ersterEintrag = true;
        for (Map.Entry<UUID, List<NoteRecord>> eintrag : notes.entrySet()) {
            if (eintrag.getKey() == null || eintrag.getValue() == null) {
                continue;
            }
            if (!ersterEintrag) {
                out.append(',');
            }
            ersterEintrag = false;
            out.append(text(eintrag.getKey().toString())).append(":[");
            boolean ersterNote = true;
            for (NoteRecord note : eintrag.getValue()) {
                if (note == null) {
                    continue;
                }
                if (!ersterNote) {
                    out.append(',');
                }
                ersterNote = false;
                out.append("{\"nummer\":").append(note.nummer())
                        .append(",\"spieler\":").append(text(note.spielerName()))
                        .append(",\"text\":").append(text(note.text()))
                        .append(",\"ausfuehrer\":").append(text(note.ausfuehrer()))
                        .append(",\"erstelltAm\":").append(note.erstelltAm())
                        .append('}');
            }
            out.append(']');
        }
    }

    private static String text(String wert) {
        if (wert == null) {
            return "null";
        }
        StringBuilder aus = new StringBuilder(wert.length() + 2);
        aus.append('"');
        for (int i = 0; i < wert.length(); i++) {
            char zeichen = wert.charAt(i);
            switch (zeichen) {
                case '"' -> aus.append("\\\"");
                case '\\' -> aus.append("\\\\");
                case '\n' -> aus.append("\\n");
                case '\r' -> aus.append("\\r");
                case '\t' -> aus.append("\\t");
                default -> {
                    if (zeichen < 0x20) {
                        aus.append(String.format("\\u%04x", (int) zeichen));
                    } else {
                        aus.append(zeichen);
                    }
                }
            }
        }
        return aus.append('"').toString();
    }
}
