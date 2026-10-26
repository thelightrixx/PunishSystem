package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.MiniMessageUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class VerstossCommand extends PunishCommand {

    private static final String UNTERBEFEHL_ADD = "add";
    private static final String UNTERBEFEHL_REMOVE = "remove";
    private static final String UNTERBEFEHL_CLEAR = "clear";

    private static final String LEVEL_PRAEFIX = "level=";
    private static final List<String> LEVEL_VORSCHLAEGE = List.of(
            "level=low", "level=medium", "level=high");

    private final VerstossService verstossService;

    public VerstossCommand(MessageService messageService,
                           Konfiguration configManager,
                           ProtectionService protectionService,
                           PlayerCache playerCache,
                           VerstossService verstossService,
                           AuftragsPlaner taskScheduler,
                           SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.verstossService = Objects.requireNonNull(verstossService,
                "Der VerstossService darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_VERSTOSS.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public boolean hatRecht(BefehlsSender sender, String[] args) {
        if (sender == null) {
            return false;
        }
        String[] check = args == null ? new String[0] : args;
        if (check.length == 0 || (check.length == 1 && check[0].isEmpty())) {
            return sender.hatRecht(Permissions.PUNISH_VERSTOSS.node())
                    || sender.hatRecht(Permissions.PUNISH_VERSTOSS_ADD.node())
                    || sender.hatRecht(Permissions.PUNISH_VERSTOSS_REMOVE.node())
                    || sender.hatRecht(Permissions.PUNISH_VERSTOSS_CLEAR.node());
        }
        return switch (check[0].toLowerCase(Locale.ROOT)) {
            case UNTERBEFEHL_ADD -> sender.hatRecht(Permissions.PUNISH_VERSTOSS_ADD.node())
                    || sender.hatRecht(Permissions.PUNISH_VERSTOSS.node());
            case UNTERBEFEHL_REMOVE -> sender.hatRecht(Permissions.PUNISH_VERSTOSS_REMOVE.node())
                    || sender.hatRecht(Permissions.PUNISH_VERSTOSS.node());
            case UNTERBEFEHL_CLEAR -> sender.hatRecht(Permissions.PUNISH_VERSTOSS_CLEAR.node())
                    || sender.hatRecht(Permissions.PUNISH_VERSTOSS.node());
            default -> sender.hatRecht(Permissions.PUNISH_VERSTOSS.node());
        };
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        ScopeParser.SilentErgebnis silentErgebnis = silentAusArgsOhneScope(sender, args);
        args = silentErgebnis.bereinigteArgs();
        boolean silent = silentErgebnis.silent();
        if (args.length == 0) {
            messageService.senden(sender, MessageKeys.SYNTAX_VERSTOSS);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case UNTERBEFEHL_ADD -> verarbeitenAdd(sender, args, silent);
            case UNTERBEFEHL_REMOVE -> verarbeitenRemove(sender, args, silent);
            case UNTERBEFEHL_CLEAR -> verarbeitenClear(sender, args, silent);
            default -> messageService.senden(sender, MessageKeys.SYNTAX_VERSTOSS);
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
        return "Grund";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args.length == 0 ? "" : args[args.length - 1];
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();

            boolean parent = sender.hatRecht(Permissions.PUNISH_VERSTOSS.node());
            if (parent || sender.hatRecht(Permissions.PUNISH_VERSTOSS_ADD.node())) subs.add(UNTERBEFEHL_ADD);
            if (parent || sender.hatRecht(Permissions.PUNISH_VERSTOSS_REMOVE.node())) subs.add(UNTERBEFEHL_REMOVE);
            if (parent || sender.hatRecht(Permissions.PUNISH_VERSTOSS_CLEAR.node())) subs.add(UNTERBEFEHL_CLEAR);
            return filtere(subs, args[0]);
        }
        String unterbefehl = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (unterbefehl) {
                case UNTERBEFEHL_ADD -> filtere(spielerVorschlaegeSmart(sender, args[1]), args[1]);
                case UNTERBEFEHL_REMOVE, UNTERBEFEHL_CLEAR ->
                        filtere(bestrafbareSpielerNamen(), args[1]);
                default -> List.of();
            };
        }
        return switch (unterbefehl) {
            case UNTERBEFEHL_ADD -> {
                String aktuell = args[args.length - 1];
                if (beginntMitLevelPraefix(aktuell)) {
                    yield filtere(LEVEL_VORSCHLAEGE, aktuell);
                }
                if (args.length > 3) {
                    yield silentVorschlag(sender, aktuell);
                }
                List<String> gemischt = new ArrayList<>(grundVorschlaege("verstoss"));
                gemischt.addAll(LEVEL_VORSCHLAEGE);
                gemischt.addAll(silentVorschlag(sender, ""));
                yield filtere(gemischt, aktuell);
            }
            case UNTERBEFEHL_REMOVE -> {
                if (args.length == 3) {
                    yield kennungenVorschlaege(args[1], args[2]);
                }
                if (args.length == 4) {
                    yield kennungOhneNummerVorschlag(args[1], args[2], args[3]);
                }
                yield silentVorschlag(sender, args[args.length - 1]);
            }
            case UNTERBEFEHL_CLEAR -> silentVorschlag(sender, args[args.length - 1]);
            default -> List.of();
        };
    }

    private void verarbeitenAdd(BefehlsSender sender, String[] args, boolean silent) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.SYNTAX_VERSTOSS);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[1]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[1]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.VERSTOSS, () -> {
            if (verstossService.istAutoBannAktiv(spieler.uuid())) {
                messageService.senden(sender, MessageKeys.AUTO_BANN_AKTIV, Map.of("spieler", spieler.name()));
                return;
            }
            StufenAngabe stufenAngabe = leseStufenAngabe(args, 2);
            if (stufenAngabe.ungueltig) {
                messageService.senden(sender, MessageKeys.LEVEL_UNGUELTIG,
                        Map.of("level", stufenAngabe.rohWert));
                return;
            }
            VerstossStufe effektiveStufe = stufenAngabe.stufe;
            int grundBeginn = 2;
            if (!stufenAngabe.angegeben && args.length > 2) {
                var blanke = VerstossStufe.parse(args[2]);
                if (blanke.isPresent()) {
                    effektiveStufe = blanke.get();
                    grundBeginn = 3;
                }
            }
            String grund = args.length > grundBeginn
                    ? grundAusBereich(args, grundBeginn, stufenAngabe.ausgeschlossene())
                    : configManager.standardGrund(MessageKeys.DEFAULT_VERSTOSS_GRUND);
            if (grund.isBlank()) {
                grund = configManager.standardGrund(MessageKeys.DEFAULT_VERSTOSS_GRUND);
            }
            VerstossRecord record;
            try {
                record = verstossService.hinzufuegen(
                        spieler.uuid(), spieler.name(), grund, ausfuehrerName(sender),
                        effektiveStufe, silent);
            } catch (VerstossService.ZielGeschuetztException e) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return;
            } catch (IllegalStateException e) {
                messageService.senden(sender, MessageKeys.AUTO_BANN_AKTIV, Map.of("spieler", spieler.name()));
                return;
            }
            messageService.senden(sender, MessageKeys.VERSTOSS_ADD_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "id", String.valueOf(record.nummer()),
                    "grund", grund,
                    "stufe", record.stufe().schluessel(),
                    "anzahl", String.valueOf(verstossService.anzahl(spieler.uuid())),
                    "punkte", String.valueOf(verstossService.punkte(spieler.uuid())),
                    "max", String.valueOf(configManager.verstoesseMaxPunkte())));
        });
    }

    private void verarbeitenRemove(BefehlsSender sender, String[] args, boolean silent) {
        if (args.length < 3) {
            messageService.senden(sender, MessageKeys.SYNTAX_VERSTOSS);
            sendeVerstossListe(sender, args);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[1]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[1]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.VERSTOSS, () -> {
            Optional<Integer> nummer = parseNummer(args[2]);
            if (nummer.isEmpty()) {
                messageService.senden(sender, MessageKeys.NUMMER_UNGUELTIG, Map.of("nummer", args[2]));
                sendeVerstossListe(sender, spieler);
                return;
            }
            boolean entfernt = verstossService.entfernen(
                    spieler.uuid(), spieler.name(), nummer.get(), ausfuehrerName(sender), silent);
            if (!entfernt) {
                messageService.senden(sender, MessageKeys.VERSTOSS_NICHT_GEFUNDEN, Map.of(
                        "spieler", spieler.name(), "id", String.valueOf(nummer.get())));
                return;
            }
            messageService.senden(sender, MessageKeys.VERSTOSS_REMOVE_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "id", String.valueOf(nummer.get()),
                    "anzahl", String.valueOf(verstossService.anzahl(spieler.uuid())),
                    "punkte", String.valueOf(verstossService.punkte(spieler.uuid())),
                    "max", String.valueOf(configManager.verstoesseMaxPunkte())));
        });
    }

    private void verarbeitenClear(BefehlsSender sender, String[] args, boolean silent) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.SYNTAX_VERSTOSS);
            return;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(args[1]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[1]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.VERSTOSS, () -> {
            int geloesecht = verstossService.leeren(
                    spieler.uuid(), spieler.name(), ausfuehrerName(sender), silent);
            if (geloesecht <= 0) {
                messageService.senden(sender, MessageKeys.VERSTOESSE_LEER, Map.of("spieler", spieler.name()));
                return;
            }
            messageService.senden(sender, MessageKeys.VERSTOSS_CLEAR_ERFOLG, Map.of(
                    "spieler", spieler.name(),
                    "anzahl", String.valueOf(geloesecht),
                    "max", String.valueOf(configManager.verstoesseMaxPunkte())));
        });
    }

    private static boolean beginntMitLevelPraefix(String token) {
        return token != null && token.regionMatches(true, 0, LEVEL_PRAEFIX, 0, LEVEL_PRAEFIX.length());
    }

    record StufenAngabe(VerstossStufe stufe, boolean angegeben, boolean ungueltig,
                        String rohWert, Set<Integer> ausgeschlossene) {
    }

    static StufenAngabe leseStufenAngabe(String[] args, int grundStart) {
        VerstossStufe stufe = null;
        boolean angegeben = false;
        boolean ungueltig = false;
        String rohWert = "";
        Set<Integer> ausgeschlossene = new LinkedHashSet<>();
        for (int i = grundStart; i < args.length; i++) {
            String token = args[i];
            if (!beginntMitLevelPraefix(token)) {
                continue;
            }
            String wert = token.substring(LEVEL_PRAEFIX.length()).trim();
            var parsed = VerstossStufe.parse(wert);
            if (parsed.isPresent()) {
                stufe = parsed.get();
            } else {
                ungueltig = true;
                rohWert = wert;
            }
            angegeben = true;
            ausgeschlossene.add(i);
        }
        return new StufenAngabe(stufe, angegeben, ungueltig, rohWert, ausgeschlossene);
    }

    static String grundAusBereich(String[] args, int start, Set<Integer> ausgeschlossene) {
        Set<Integer> skip = ausgeschlossene == null ? Set.of() : ausgeschlossene;
        StringBuilder grund = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (skip.contains(i)) {
                continue;
            }
            if (!grund.isEmpty()) {
                grund.append(" ");
            }
            grund.append(args[i]);
        }
        return grund.isEmpty() ? "" : PunishCommand.normalisiereGrund(grund.toString());
    }

    private List<String> kennungenVorschlaege(String spielerName, String aktuell) {
        Optional<CachedPlayer> ziel = zielAufloesen(spielerName);
        if (ziel.isEmpty()) {
            return List.of();
        }
        List<VerstossRecord> verstoesse;
        try {
            verstoesse = verstossService.findeVerstoesse(ziel.get().uuid());
        } catch (Exception e) {
            return List.of();
        }
        if (verstoesse == null || verstoesse.isEmpty()) {
            return List.of();
        }
        var mini = MiniMessageUtil.miniMessage();

        List<String> vorschlaege = verstoesse.stream()
                .sorted(java.util.Comparator.comparingInt(VerstossRecord::nummer).reversed())
                .limit(10)
                .map(verstoss -> {
                    String kennung = mini.escapeTags(
                            de.lightrixx.punish.model.VerstossKennung.kennung(verstoss));
                    return verstoss.nummer() + " " + kennung;
                })
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

    private List<String> kennungOhneNummerVorschlag(String spielerName, String nummerRoh, String aktuell) {
        Optional<Integer> nummer = parseNummer(nummerRoh);
        if (nummer.isEmpty()) {
            return List.of();
        }
        Optional<CachedPlayer> ziel = zielAufloesen(spielerName);
        if (ziel.isEmpty()) {
            return List.of();
        }
        List<VerstossRecord> verstoesse;
        try {
            verstoesse = verstossService.findeVerstoesse(ziel.get().uuid());
        } catch (Exception e) {
            return List.of();
        }
        if (verstoesse == null) {
            return List.of();
        }
        var mini = MiniMessageUtil.miniMessage();
        for (VerstossRecord verstoß : verstoesse) {
            if (verstoß.nummer() == nummer.get()) {
                String kennung = mini.escapeTags(
                        de.lightrixx.punish.model.VerstossKennung.kennung(verstoß));
                return filtere(List.of(kennung), aktuell);
            }
        }
        return List.of();
    }

    private void sendeVerstossListe(BefehlsSender sender, String[] args) {
        if (args.length >= 2 && args[1] != null && !args[1].isBlank()) {
            Optional<CachedPlayer> ziel = zielAufloesen(args[1]);
            if (ziel.isEmpty()) {
                return;
            }
            try {
                if (protectionService.istGeschuetzt(ziel.get().uuid(), ProtectionService.IgnoreTyp.VERSTOSS)) {
                    return;
                }
            } catch (Exception e) {
                return;
            }
            sendeVerstossListe(sender, ziel.get());
        }
    }

    private void sendeVerstossListe(BefehlsSender sender, CachedPlayer spieler) {
        List<VerstossRecord> verstoesse;
        try {
            verstoesse = verstossService.findeVerstoesse(spieler.uuid());
        } catch (Exception e) {
            return;
        }
        if (verstoesse == null || verstoesse.isEmpty()) {
            return;
        }
        var mini = MiniMessageUtil.miniMessage();
        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>();
        zeilen.add(mini.deserialize("<gray>Verstöße von <white>"
                + mini.escapeTags(spieler.name()) + "<gray>:"));
        verstoesse.stream()
                .sorted(java.util.Comparator.comparingInt(VerstossRecord::nummer).reversed())
                .limit(10)
                .forEach(v -> {
                    String kennung = de.lightrixx.punish.model.VerstossKennung.kennung(v);
                    String vorschlag = "/verstoss remove " + spieler.name() + " " + v.nummer();
                    zeilen.add(mini.deserialize("<gray>#<white>" + v.nummer() + "<gray> – <white>"
                                    + mini.escapeTags(kennung))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    mini.deserialize("<gray>Klicken zum Übernehmen")))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(vorschlag)));
                });
        messageService.sendeBlock(sender, zeilen);
    }
}
