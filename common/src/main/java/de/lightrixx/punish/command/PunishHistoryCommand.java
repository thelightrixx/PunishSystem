package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.ZeitFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PunishHistoryCommand extends PunishCommand {

    private static final int HISTORY_FETCH_LIMIT = 1000;

    private static final Comparator<HistoryEntry> NEUESTE_ZUERST =
            Comparator.comparingLong((HistoryEntry entry) -> entry.zeitstempel()).reversed()
                .thenComparing(Comparator.comparingLong((HistoryEntry entry) -> entry.id()).reversed());

    private final HistoryService historyService;

    public PunishHistoryCommand(MessageService messageService,
                                Konfiguration configManager,
                                ProtectionService protectionService,
                                PlayerCache playerCache,
                                HistoryService historyService,
                                SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_HISTORY.node();
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
        if (istSelbstAnfrage(sender, args)) {
            return sender.hatRecht(Permissions.PUNISH_OWN.node())
                    || sender.hatRecht(Permissions.PUNISH_HISTORY.node());
        }
        if (istServerAnfrage(args)) {
            return sender.hatRecht(Permissions.PUNISH_HISTORY_SERVER.node());
        }
        return sender.hatRecht(Permissions.PUNISH_HISTORY.node());
    }

    @Override
    public boolean sichtbarFuer(BefehlsSender sender) {
        return hatRecht(sender, new String[0])
                || (sender != null && sender.hatRecht(Permissions.PUNISH_OWN.node()));
    }

    private boolean istSelbstAnfrage(BefehlsSender sender, String[] args) {
        if (sender == null || !sender.istSpieler()) {
            return false;
        }
        return PunishmentsQueryParser.parsen(args).query().zielTyp() == PunishmentsQuery.ZielTyp.SELF;
    }

    private boolean istServerAnfrage(String[] args) {
        try {
            return PunishmentsQueryParser.parsen(args).query().zielTyp() == PunishmentsQuery.ZielTyp.SERVER;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean istGeschuetztNameAusgeblendet(String zielName) {
        if (zielName == null || zielName.isBlank()) {
            return false;
        }
        try {
            Optional<CachedPlayer> gefunden = playerCache.findenNachName(zielName.trim());
            if (gefunden.isEmpty()) {
                return false;
            }
            return protectionService.istGeschuetzt(gefunden.get().uuid());
        } catch (Exception e) {
            return protectionService.istFailClosed();
        }
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        String scopeFilter = null;
        String[] queryArgs = args;
        if (args != null && args.length > 0) {
            String letztes = args[args.length - 1];
            if (letztes != null && letztes.startsWith("--") && letztes.length() >= 3) {
                String rest = letztes.substring(2).trim().toLowerCase(java.util.Locale.ROOT);
                if (rest.equals("global") || rest.equals("unbekannt")) {
                    scopeFilter = rest;
                    queryArgs = java.util.Arrays.copyOf(args, args.length - 1);
                } else if (rest.startsWith("server=")) {
                    String name = de.lightrixx.punish.model.ServerScope.normalisiereServerName(
                            rest.substring("server=".length()));
                    if (name != null) {
                        scopeFilter = name;
                        queryArgs = java.util.Arrays.copyOf(args, args.length - 1);
                    }
                } else {
                    String name = de.lightrixx.punish.model.ServerScope.normalisiereServerName(rest);
                    if (name != null) {
                        scopeFilter = name;
                        queryArgs = java.util.Arrays.copyOf(args, args.length - 1);
                    }
                }
            }
        }
        var parsed = PunishmentsQueryParser.parsen(queryArgs);
        if (parsed.unbekannterFilter() != null) {
            messageService.senden(sender, MessageKeys.PUNISH_HISTORY_FILTER_UNGUELTIG,
                    Map.of("filter", parsed.unbekannterFilter()));
            messageService.senden(sender, MessageKeys.PUNISH_HISTORY_SYNTAX);
            return;
        }
        if (parsed.unbekannterSchluessel() != null) {
            messageService.senden(sender, MessageKeys.PUNISH_HISTORY_SYNTAX);
            return;
        }
        PunishmentsQuery query = parsed.query();

        if (query.zielTyp() == PunishmentsQuery.ZielTyp.SERVER) {
            serverUebersicht(sender, query, scopeFilter);
            return;
        }

        UUID zielUuid;
        String zielName;
        if (query.zielTyp() == PunishmentsQuery.ZielTyp.SELF) {
            if (!sender.istSpieler() || sender.spielerUuid() == null) {
                messageService.senden(sender, MessageKeys.NUR_SPIELER);
                return;
            }
            zielUuid = sender.spielerUuid();
            zielName = sender.name();
        } else {
            Optional<CachedPlayer> ziel = zielAufloesen(query.spielerName());
            if (ziel.isEmpty()) {
                messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN,
                        Map.of("spieler", query.spielerName()));
                return;
            }
            zielUuid = ziel.get().uuid();
            zielName = ziel.get().name();
        }
        spielerAnsicht(sender, query, zielUuid, zielName, scopeFilter);
    }

    private static boolean passtScopeFilter(String eintragScope, String filter) {
        if (filter == null) {
            return true;
        }
        if (eintragScope == null || eintragScope.isBlank()) {
            eintragScope = de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT;
        }
        return eintragScope.trim().equalsIgnoreCase(filter);
    }

    private void spielerAnsicht(BefehlsSender sender, PunishmentsQuery query, UUID zielUuid, String zielName,
                                String scopeFilter) {
        if (query.zielTyp() != PunishmentsQuery.ZielTyp.SELF) {
            try {
                if (protectionService.istGeschuetzt(zielUuid)) {
                    messageService.senden(sender, MessageKeys.GESCHUETZT);
                    return;
                }
            } catch (Exception e) {
                if (protectionService.istFailClosed()) {
                    messageService.senden(sender, MessageKeys.SCHUTZ_OFFLINE_UNBEKANNT,
                            Map.of("spieler", zielName));
                    return;
                }
            }
        }
        int seitenGroesse = Math.max(1, configManager.historyEintraegeProSeite());
        PunishmentsFilter filter = query.filter();

        List<HistoryEntry> verlauf = historyService.findeSpielerHistory(zielUuid, HISTORY_FETCH_LIMIT).stream()
                .filter(e -> e != null && filter.passt(e.typ()))
                .filter(e -> passtScopeFilter(e.scope(), scopeFilter))
                .sorted(NEUESTE_ZUERST)
                .toList();

        sendeErgebnis(sender, query, zielName, verlauf, seitenGroesse, "player:" + zielName);
    }

    private void serverUebersicht(BefehlsSender sender, PunishmentsQuery query, String scopeFilter) {
        int seitenGroesse = Math.max(1, configManager.historyEintraegeProSeite());
        PunishmentsFilter filter = query.filter();

        List<HistoryEntry> verlauf = historyService.findeServerHistory(HISTORY_FETCH_LIMIT).stream()
                .filter(e -> e != null && filter.passt(e.typ()))
                .filter(e -> passtScopeFilter(e.scope(), scopeFilter))
                .filter(e -> !istGeschuetztNameAusgeblendet(e.ziel()))
                .sorted(NEUESTE_ZUERST)
                .toList();

        sendeErgebnis(sender, query, "Server", verlauf, seitenGroesse, "player:server");
    }

    private void sendeErgebnis(BefehlsSender sender, PunishmentsQuery query, String zielName,
                               List<HistoryEntry> verlauf, int seitenGroesse, String spielerToken) {
        int sichereGroesse = Math.max(1, seitenGroesse);
        int seiten = Math.max(1, (verlauf.size() + sichereGroesse - 1) / sichereGroesse);
        int seite = Math.min(Math.max(1, query.seite()), seiten);
        int von = (seite - 1) * sichereGroesse;
        int bis = Math.min(von + sichereGroesse, verlauf.size());
        List<HistoryEntry> seitenEintraege = von < bis ? verlauf.subList(von, bis) : List.of();

        List<Component> zeilen = new ArrayList<>();
        zeilen.add(messageService.komponente(MessageKeys.PUNISH_HISTORY_KOPF, Map.of(
                "ziel", zielName,
                "filter", query.filter().anzeigename(),
                "seite", String.valueOf(seite),
                "seiten", String.valueOf(seiten))));

        zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_HISTORY_VERLAUF_KOPF, Map.of(
                "anzahl", String.valueOf(verlauf.size()),
                "seite", String.valueOf(seite),
                "seiten", String.valueOf(seiten))));
        if (seitenEintraege.isEmpty()) {
            zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_HISTORY_VERLAUF_LEER,
                    Map.of("ziel", zielName)));
        } else {
            long jetzt = System.currentTimeMillis();
            for (HistoryEntry eintrag : seitenEintraege) {
                zeilen.add(baueVerlaufZeile(sender, eintrag, jetzt));
            }
        }
        if (seiten > 1) {
            zeilen.add(baueNavigation(query, spielerToken, seite, seiten));
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private Component baueNavigation(PunishmentsQuery query, String spielerToken, int seite, int seiten) {
        String basis = "/punish-history filter:" + query.filter().name().toLowerCase(Locale.ROOT)
                + " " + spielerToken + " page:";
        Component nav = Component.text("‹ Zurück", seite > 1 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY);
        if (seite > 1) {
            String befehl = basis + (seite - 1);
            nav = nav.clickEvent(ClickEvent.runCommand(befehl))
                    .hoverEvent(HoverEvent.showText(Component.text(befehl, NamedTextColor.GRAY)));
        }
        nav = nav.append(Component.text("  |  Seite " + seite + "/" + seiten + "  |  ", NamedTextColor.GRAY));
        Component weiter = Component.text("Weiter ›", seite < seiten ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY);
        if (seite < seiten) {
            String befehl = basis + (seite + 1);
            weiter = weiter.clickEvent(ClickEvent.runCommand(befehl))
                    .hoverEvent(HoverEvent.showText(Component.text(befehl, NamedTextColor.GRAY)));
        }
        return nav.append(weiter);
    }

    private Component baueVerlaufZeile(BefehlsSender sender, HistoryEntry eintrag, long jetzt) {
        Map<String, String> ph = new HashMap<>();
        ph.put("id", String.valueOf(eintrag.id()));
        ph.put("typ", eintrag.typ().anzeigename() + " [" + eintrag.scope() + "]");
        ph.put("ziel", eintrag.ziel());
        ph.put("ausfuehrer", eintrag.ausfuehrer());
        ph.put("grund", ScopeParser.saeubereGrund(eintrag.grund(), bekannteServer()));
        ph.put("datum", ZeitFormat.formatZeitpunkt(eintrag.zeitstempel()));
        ph.put("ablauf", eintrag.ablauf() != null ? ZeitFormat.formatZeitpunkt(eintrag.ablauf()) : "");
        ph.put("scope", eintrag.scope());
        ph.put("herkunft", eintrag.herkunft());
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.HISTORY_EINTRAG, ph);
        if (eintrag.istEffektivAktiv(jetzt)) {
            Component hover = messageService.komponenteOhnePraefix(MessageKeys.HISTORY_HOVER, ph);
            zeile = zeile.hoverEvent(HoverEvent.showText(hover));
            String klick = klickBefehlFuer(sender, eintrag);
            if (klick != null) {
                zeile = zeile.clickEvent(ClickEvent.suggestCommand(klick));
            }
        }
        return zeile;
    }

    private String klickBefehlFuer(BefehlsSender sender, HistoryEntry eintrag) {
        String ziel = eintrag.ziel();
        if (ziel == null || !ziel.matches("[A-Za-z0-9_]{3,16}")) {
            if ((eintrag.typ() == HistoryType.BAN_IP || eintrag.typ() == HistoryType.IP_BAN_EDIT)
                    && sender.hatRecht(Permissions.PUNISH_UNBANIP.node())
                    && ziel != null && !ziel.isBlank()) {
                return "/unban-ip " + ziel;
            }
            if ((eintrag.typ() == HistoryType.IP_MUTE || eintrag.typ() == HistoryType.IP_MUTE_EDIT)
                    && sender.hatRecht(Permissions.PUNISH_UNMUTEIP.node())
                    && ziel != null && !ziel.isBlank()) {
                return "/unmute-ip " + ziel;
            }
            if ((eintrag.typ() == HistoryType.IP_TIMEOUT || eintrag.typ() == HistoryType.IP_TIMEOUT_EDIT)
                    && sender.hatRecht(Permissions.PUNISH_UNTIMEOUTIP.node())
                    && ziel != null && !ziel.isBlank()) {
                return "/untimeout-ip " + ziel;
            }
            return null;
        }
        return switch (eintrag.typ()) {
            case BAN, BAN_EDIT, PERMABANN, AUTO_BANN ->
                    sender.hatRecht(Permissions.PUNISH_PARDON.node()) ? "/pardon " + ziel : null;
            case BAN_ALL, PARDON_ALL, KICK_ALL, IP_KICK, MUTE_ALL, UNMUTE_ALL, TIMEOUT_ALL,
                    UNTIMEOUT_ALL ->
                    null;
            case MUTE, MUTE_EDIT ->
                    sender.hatRecht(Permissions.PUNISH_UNMUTE.node()) ? "/unmute " + ziel : null;
            case TIMEOUT, TIMEOUT_EDIT ->
                    sender.hatRecht(Permissions.PUNISH_UNTIMEOUT.node()) ? "/untimeout " + ziel : null;
            case FREEZE ->
                    sender.hatRecht(Permissions.PUNISH_UNFREEZE.node()) ? "/unfreeze " + ziel : null;
            case WARN_ADD, WARN_EDIT, WARN_REMOVE ->
                    sender.hatRecht(Permissions.PUNISH_WARN.node()) ? "/warn list " + ziel : null;
            default -> null;
        };
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        if (vorschlag != null) {
            if (vorschlag.equalsIgnoreCase("self")) {
                return "Eigene Daten";
            }
            if (vorschlag.equalsIgnoreCase("server")) {
                return "Server-Daten";
            }
        }
        return "Spieler";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        String aktuell = args[args.length - 1] == null ? "" : args[args.length - 1];

        if (aktuell.startsWith("--")) {
            java.util.List<String> scope = new java.util.ArrayList<>(
                    ScopeParser.vorschlaege(aktuell, bekannteServer()));
            if ("--unbekannt".startsWith(aktuell.toLowerCase(java.util.Locale.ROOT))
                    && !scope.contains("--unbekannt")) {
                scope.add("--unbekannt");
            }
            return scope;
        }
        int trenner = Math.max(aktuell.indexOf(':'), aktuell.indexOf('='));
        if (trenner >= 0) {
            String schluessel = aktuell.substring(0, trenner).toLowerCase(Locale.ROOT);
            String praefix = aktuell.substring(0, trenner + 1);
            String rest = aktuell.substring(trenner + 1);
            return switch (schluessel) {
                case "filter", "typ", "type" -> filtere(
                        List.of("all", "bans", "mutes", "timeouts", "freezes", "verstoesse", "warns", "kicks", "ipbans",
                                        "ipmutes", "iptimeouts")
                                .stream().map(w -> praefix + w).toList(), aktuell);
                case "player", "spieler", "target", "ziel", "p" ->
                        filtere(spielerKandidatenMitPraefix(sender, praefix, rest), aktuell);
                case "page", "seite" -> List.of();
                default -> List.of();
            };
        }

        boolean darfFremd = sender == null || !sender.istSpieler()
                || sender.hatRecht(Permissions.PUNISH_HISTORY.node());
        boolean hatZiel = enthaeltZiel(args, args.length - 1);
        boolean hatFilter = enthaeltFilter(args, args.length - 1);

        if (!darfFremd) {
            if (!hatZiel) {
                return filtere(List.of("self"), aktuell);
            }
            if (!hatFilter) {
                return filtere(FILTER_VORSCHLAEGE, aktuell);
            }
            return List.of();
        }

        if (!hatZiel) {
            return filtere(bareZielKandidaten(sender, aktuell), aktuell);
        }
        if (!hatFilter) {
            java.util.List<String> gemischt = new java.util.ArrayList<>(FILTER_VORSCHLAEGE);
            gemischt.addAll(ScopeParser.vorschlaege("--", bekannteServer()));
            gemischt.add("--unbekannt");
            return filtere(gemischt, aktuell);
        }

        java.util.List<String> scope = new java.util.ArrayList<>(
                ScopeParser.vorschlaege("--", bekannteServer()));
        scope.add("--unbekannt");
        return filtere(scope, aktuell);
    }

    private static final List<String> FILTER_VORSCHLAEGE = List.of(
            "filter:all", "filter:bans", "filter:mutes", "filter:timeouts", "filter:freezes",
            "filter:verstoesse", "filter:warns", "filter:kicks", "filter:ipbans",
            "filter:ipmutes", "filter:iptimeouts");

    private static boolean enthaeltFilter(String[] args, int bisExklusiv) {
        for (int i = 0; i < bisExklusiv && i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            int trenner = Math.max(token.indexOf(':'), token.indexOf('='));
            if (trenner < 0) {
                continue;
            }
            String schluessel = token.substring(0, trenner).trim().toLowerCase(Locale.ROOT);
            if (schluessel.equals("filter") || schluessel.equals("typ") || schluessel.equals("type")) {
                return true;
            }
        }
        return false;
    }

    private static boolean enthaeltZiel(String[] args, int bisExklusiv) {
        for (int i = 0; i < bisExklusiv && i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            String getrimmt = token.trim();
            int trenner = Math.max(getrimmt.indexOf(':'), getrimmt.indexOf('='));
            if (trenner >= 0) {
                String schluessel = getrimmt.substring(0, trenner).trim().toLowerCase(Locale.ROOT);
                if (schluessel.equals("player") || schluessel.equals("spieler")
                        || schluessel.equals("target") || schluessel.equals("ziel")
                        || schluessel.equals("p")) {
                    return true;
                }
                continue;
            }
            if (getrimmt.matches("\\d+")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private List<String> bareZielKandidaten(BefehlsSender sender, String prefix) {
        Set<String> kombiniert = new LinkedHashSet<>();
        kombiniert.add("self");
        if (sender == null || sender.hatRecht(Permissions.PUNISH_HISTORY_SERVER.node())) {
            kombiniert.add("server");
        }
        kombiniert.addAll(onlineTreffer(prefix, 30));
        if (prefix != null && prefix.trim().length() >= 3) {
            kombiniert.addAll(offlineSpielerVorschlaege(prefix));
        }
        return new ArrayList<>(kombiniert);
    }

    private List<String> spielerKandidatenMitPraefix(BefehlsSender sender, String praefix, String rest) {
        boolean darfFremd = sender == null || !sender.istSpieler()
                || sender.hatRecht(Permissions.PUNISH_HISTORY.node());
        Set<String> kombiniert = new LinkedHashSet<>();
        if (!darfFremd) {
            kombiniert.add(praefix + "self");
            return new ArrayList<>(kombiniert);
        }
        kombiniert.add(praefix + "self");
        if (sender == null || sender.hatRecht(Permissions.PUNISH_HISTORY_SERVER.node())) {
            kombiniert.add(praefix + "server");
        }
        for (String name : onlineTreffer(rest, 30)) {
            kombiniert.add(praefix + name);
        }
        if (rest != null && rest.trim().length() >= 3) {
            for (String name : offlineSpielerVorschlaege(rest)) {
                kombiniert.add(praefix + name);
            }
        }
        return new ArrayList<>(kombiniert);
    }

    private List<String> onlineTreffer(String prefix, int limit) {
        String vergleich = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        List<String> treffer = new ArrayList<>();
        if (verzeichnis == null) {
            return treffer;
        }
        try {
            for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                if (online == null) {
                    continue;
                }
                String name = online.name();
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (!vergleich.isEmpty() && !name.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                    continue;
                }
                if (!treffer.contains(name)) {
                    treffer.add(name);
                }
                if (treffer.size() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("Online-Scan für History-Tab-Complete übersprungen.", e);
        }
        treffer.sort(String.CASE_INSENSITIVE_ORDER);
        return treffer;
    }
}
