package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.NoteRecord;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.NoteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PunishNoteCommand extends PunishCommand {

    private static final String UNTERBEFEHL_ADD = "add";
    private static final String UNTERBEFEHL_DELETE = "delete";

    private static final String UNTERBEFEHL_REMOVE_ALIAS = "remove";
    private static final String UNTERBEFEHL_LIST = "list";
    private static final List<String> UNTERBEFEHLE =
            List.of(UNTERBEFEHL_ADD, UNTERBEFEHL_DELETE, UNTERBEFEHL_LIST);

    private static final int VORSCHAU_WOERTER = 5;

    private static final int VORSCHAU_MAX_ZEICHEN = 40;

    private final NoteService noteService;

    public PunishNoteCommand(MessageService messageService,
                             Konfiguration configManager,
                             ProtectionService protectionService,
                             PlayerCache playerCache,
                             NoteService noteService,
                             SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.noteService = Objects.requireNonNull(noteService,
                "Der NoteService darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_NOTE.node();
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
            messageService.senden(sender, MessageKeys.SYNTAX_NOTE);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case UNTERBEFEHL_ADD -> verarbeitenAdd(sender, args, silent);
            case UNTERBEFEHL_DELETE, UNTERBEFEHL_REMOVE_ALIAS -> verarbeitenDelete(sender, args, silent);
            case UNTERBEFEHL_LIST -> verarbeitenList(sender, args);
            default -> messageService.senden(sender, MessageKeys.SYNTAX_NOTE);
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
            return "Unterbefehl";
        }
        if (position == 2) {
            return "Spieler";
        }
        return "Text";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args.length == 0 ? "" : args[args.length - 1];
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        if (args.length == 1) {
            return filtere(UNTERBEFEHLE, args[0]);
        }
        String unterbefehl = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (unterbefehl) {
                case UNTERBEFEHL_ADD, UNTERBEFEHL_DELETE, UNTERBEFEHL_REMOVE_ALIAS,
                     UNTERBEFEHL_LIST ->
                        filtere(spielerKandidaten(args[1]), args[1]);
                default -> List.of();
            };
        }

        return switch (unterbefehl) {
            case UNTERBEFEHL_ADD -> silentVorschlag(sender, args[args.length - 1]);
            case UNTERBEFEHL_DELETE, UNTERBEFEHL_REMOVE_ALIAS -> {
                if (args.length == 3) {
                    yield nummernVorschlaege(args[1], args[2]);
                }
                yield silentVorschlag(sender, args[args.length - 1]);
            }
            default -> silentVorschlag(sender, args[args.length - 1]);
        };
    }

    private void verarbeitenAdd(BefehlsSender sender, String[] args, boolean silent) {
        if (args.length < 3) {
            messageService.senden(sender, MessageKeys.SYNTAX_NOTE);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[1]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[1]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.NOTE, () -> {
            String text = grundAusArgumentenOhneStandard(args, 2);
            if (text.isBlank()) {
                messageService.senden(sender, MessageKeys.KEIN_GRUND);
                return;
            }
            NoteRecord record;
            try {
                record = noteService.hinzufuegen(
                        spieler.uuid(), spieler.name(), text, ausfuehrerName(sender), silent);
            } catch (IllegalArgumentException e) {
                LOG.warn("Notiz-Add für {} abgewiesen: {}", spieler.uuid(), e.getMessage());
                messageService.senden(sender, MessageKeys.KEIN_GRUND);
                return;
            }
            messageService.senden(sender, MessageKeys.NOTE_ADD_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "id", String.valueOf(record.nummer())));
        });
    }

    private void verarbeitenDelete(BefehlsSender sender, String[] args, boolean silent) {
        if (args.length < 3) {
            messageService.senden(sender, MessageKeys.SYNTAX_NOTE);
            sendeNotizListe(sender, args);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[1]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[1]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.NOTE, () -> {
            Optional<Integer> nummer = parseNummer(args[2]);
            if (nummer.isEmpty()) {
                messageService.senden(sender, MessageKeys.NUMMER_UNGUELTIG, Map.of("nummer", args[2]));
                sendeNotizListe(sender, spieler);
                return;
            }
            Optional<NoteRecord> entfernt = noteService.entfernen(
                    spieler.uuid(), spieler.name(), nummer.get(), ausfuehrerName(sender), silent);
            if (entfernt.isEmpty()) {
                messageService.senden(sender, MessageKeys.NOTE_NICHT_GEFUNDEN, Map.of(
                        "spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            messageService.senden(sender, MessageKeys.NOTE_DELETE_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "id", String.valueOf(nummer.get())));
        });
    }

    private void verarbeitenList(BefehlsSender sender, String[] args) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.SYNTAX_NOTE);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[1]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[1]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        try {
            if (protectionService.istGeschuetzt(spieler.uuid(), ProtectionService.IgnoreTyp.NOTE)) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return;
            }
        } catch (Exception e) {
            if (protectionService.istFailClosed()) {
                messageService.senden(sender, MessageKeys.SCHUTZ_OFFLINE_UNBEKANNT,
                        Map.of("spieler", spieler.name()));
                return;
            }
        }
        List<NoteRecord> notizen;
        try {
            notizen = noteService.findeNotizen(spieler.uuid());
        } catch (Exception e) {
            LOG.warn("Notiz-Liste für {} konnte nicht geladen werden.", spieler.uuid(), e);
            return;
        }
        if (notizen == null || notizen.isEmpty()) {
            messageService.senden(sender, MessageKeys.NOTIZEN_LEER, Map.of("spieler", spieler.name()));
            return;
        }
        sendeNotizBlock(sender, spieler, notizen);
    }

    public static String vorschau(NoteRecord notiz) {
        if (notiz == null || notiz.text() == null) {
            return "–";
        }
        String normalisiert = notiz.text().trim().replaceAll("\\s+", " ");
        if (normalisiert.isEmpty()) {
            return "–";
        }
        String[] woerter = normalisiert.split(" ");
        int anzahl = Math.min(VORSCHAU_WOERTER, woerter.length);
        String kurz = String.join(" ", Arrays.copyOfRange(woerter, 0, anzahl));
        boolean gekuerzt = woerter.length > anzahl;
        if (kurz.length() > VORSCHAU_MAX_ZEICHEN) {
            kurz = kurz.substring(0, VORSCHAU_MAX_ZEICHEN);
            gekuerzt = true;
        }
        return gekuerzt ? kurz + "…" : kurz;
    }

    private List<String> nummernVorschlaege(String spielerName, String aktuell) {
        Optional<CachedPlayer> ziel = zielAufloesen(spielerName);
        if (ziel.isEmpty()) {
            return List.of();
        }
        try {
            if (protectionService.istGeschuetzt(ziel.get().uuid(), ProtectionService.IgnoreTyp.NOTE)) {
                return List.of();
            }
        } catch (Exception e) {
            if (protectionService.istFailClosed()) {
                return List.of();
            }
        }
        List<NoteRecord> notizen;
        try {
            notizen = noteService.findeNotizen(ziel.get().uuid());
        } catch (Exception e) {
            return List.of();
        }
        if (notizen == null || notizen.isEmpty()) {
            return List.of();
        }
        var mini = MiniMessageUtil.miniMessage();
        List<String> vorschlaege = notizen.stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(NoteRecord::nummer).reversed())
                .limit(25)
                .map(notiz -> notiz.nummer() + " " + mini.escapeTags(vorschau(notiz)))
                .toList();
        return filtere(vorschlaege, aktuell);
    }

    private Optional<Integer> parseNummer(String roh) {
        if (roh == null || roh.isBlank()) {
            return Optional.empty();
        }
        int trenner = roh.indexOf(' ');
        String nummernTeil = trenner >= 0 ? roh.substring(0, trenner).trim() : roh.trim();
        try {
            int nummer = Integer.parseInt(nummernTeil);
            return nummer >= 1 ? Optional.of(nummer) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
                    if (vergleich.isEmpty() || online.name().toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                        kombiniert.add(online.name());
                    }
                    if (kombiniert.size() >= 25) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Online-Scan für Notiz-Tab-Complete übersprungen.", e);
        }
        try {
            kombiniert.addAll(spielerNamenMitPrefix(prefix, 25));
        } catch (Exception e) {
            LOG.debug("Cache-Scan für Notiz-Tab-Complete übersprungen.", e);
        }
        List<String> ergebnis = new ArrayList<>(kombiniert);
        ergebnis.sort(String.CASE_INSENSITIVE_ORDER);
        return ergebnis.stream().distinct().limit(50).toList();
    }

    private void sendeNotizListe(BefehlsSender sender, String[] args) {
        if (args.length >= 2 && args[1] != null && !args[1].isBlank()) {
            Optional<CachedPlayer> ziel = zielAufloesen(args[1]);
            ziel.ifPresent(spieler -> sendeNotizListe(sender, spieler));
        }
    }

    private void sendeNotizListe(BefehlsSender sender, CachedPlayer spieler) {
        List<NoteRecord> notizen;
        try {
            notizen = noteService.findeNotizen(spieler.uuid());
        } catch (Exception e) {
            return;
        }
        if (notizen == null || notizen.isEmpty()) {
            return;
        }
        sendeNotizBlock(sender, spieler, notizen);
    }

    private void sendeNotizBlock(BefehlsSender sender, CachedPlayer spieler, List<NoteRecord> notizen) {
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.NOTE_LIST_KOPF, Map.of(
                "spieler", spieler.name(),
                "anzahl", String.valueOf(notizen.size()))));
        notizen.stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparingInt(NoteRecord::nummer).reversed())
                .limit(50)
                .forEach(notiz -> {
                    Map<String, String> ph = Map.of(
                            "id", String.valueOf(notiz.nummer()),
                            "spieler", spieler.name(),
                            "text", ScopeParser.saeubereGrund(notiz.text(), bekannteServer()),
                            "ausfuehrer", notiz.ausfuehrer(),
                            "datum", ZeitFormat.formatZeitpunkt(notiz.erstelltAm()));
                    net.kyori.adventure.text.Component zeile =
                            messageService.komponenteOhnePraefix(MessageKeys.NOTE_LIST_EINTRAG, ph);
                    String vorschlag = "/punish-edit show note " + spieler.name() + " " + notiz.nummer();
                    zeilen.add(zeile
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    mini.deserialize("<gray>Klicken zum Bearbeiten")))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(vorschlag)));
                });
        messageService.sendeBlock(sender, zeilen);
    }
}
