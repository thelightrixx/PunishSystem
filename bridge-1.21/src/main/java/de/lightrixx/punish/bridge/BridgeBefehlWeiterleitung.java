package de.lightrixx.punish.bridge;

import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.util.TabHinweis;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BridgeBefehlWeiterleitung implements CommandExecutor, TabCompleter, TunnelVorschlag {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(BridgeBefehlWeiterleitung.class);
    private static final List<String> FALLBACK_DAUERN =
            List.of("10m", "1h", "6h", "1d", "7d", "30d", "perm");
    private static final List<String> FALLBACK_SERVER = List.of("lobby", "survival");
    private static final int MAX_VORSCHLAEGE = 25;

    private final KanalClient kanal;
    private final String zielBefehl;
    private final String permission;
    private final boolean mitScopeVorschlag;
    private final BridgeKonfiguration config;
    private volatile BanCache banCache;
    private volatile MuteCache muteCache;
    private volatile TimeoutCache timeoutCache;
    private volatile IpMuteCache ipMuteCache;
    private volatile PlayerCache spielerCache;

    public BridgeBefehlWeiterleitung(KanalClient kanal, String zielBefehl,
                                     String permission, boolean mitScopeVorschlag) {
        this(kanal, zielBefehl, permission, mitScopeVorschlag, null);
    }

    public BridgeBefehlWeiterleitung(KanalClient kanal, String zielBefehl,
                                     String permission, boolean mitScopeVorschlag,
                                     BridgeKonfiguration config) {
        this.kanal = Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        this.zielBefehl = Objects.requireNonNull(zielBefehl, "Der Zielbefehl darf nicht null sein.");
        this.permission = permission == null ? "" : permission;
        this.mitScopeVorschlag = mitScopeVorschlag;
        this.config = config;
    }

    public void mitStrafCaches(BanCache banCache, MuteCache muteCache,
                               TimeoutCache timeoutCache, IpMuteCache ipMuteCache) {
        this.banCache = banCache;
        this.muteCache = muteCache;
        this.timeoutCache = timeoutCache;
        this.ipMuteCache = ipMuteCache;
    }

    public void mitSpielerCache(PlayerCache spielerCache) {
        this.spielerCache = spielerCache;
    }

    @Override
    public String zielBefehl() {
        return zielBefehl;
    }

    @Override
    public String recht() {
        return permission;
    }

    @Override
    public boolean darfSehen(CommandSender sender) {
        if (sender == null) {
            return false;
        }
        if (permission == null || permission.isBlank()) {
            return true;
        }
        if (hat(sender, permission)) {
            return true;
        }
        for (String alternative : alternativRechte()) {
            if (hat(sender, alternative)) {
                return true;
            }
        }
        return false;
    }

    private List<String> alternativRechte() {
        if (zielBefehl == null) {
            return List.of();
        }
        return switch (zielBefehl.trim().toLowerCase(Locale.ROOT)) {
            case "verstoss" -> List.of("punish.verstoss.add",
                    "punish.verstoss.remove", "punish.verstoss.clear");
            case "punish-edit", "psedit" -> List.of("punish.verstoss",
                    "punish.verstoss.edit", "punish.warn", "punish.note", "punish.ban",
                    "punish.mute", "punish.timeout", "punish.banip", "punish.muteip",
                    "punish.timeoutip");
            case "punish-status", "psstatus" -> List.of("punish.own", "punish.status.server");
            case "pscheck" -> List.of("punish.own", "punish.status.server");
            case "punish-history", "pshistory" -> List.of("punish.own", "punish.history.server");
            default -> List.of();
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        fuehreAus(sender, args);
        return true;
    }

    @Override
    public void fuehreAus(CommandSender sender, String[] args) {
        if (!darfSehen(sender)) {
            LOG.warn("Lokale Ablehnung /{} von {} ohne Recht {} (Proxy-Re-Check bleibt).",
                    zielBefehl, nameFuerLog(sender), permission);
            try {
                sender.sendMessage(prefixHinweis("Dazu hast du keine Berechtigung.", NamedTextColor.RED));
            } catch (Exception e) {
                LOG.debug("Kein-Recht-Hinweis konnte nicht gesendet werden.", e);
            }
            return;
        }
        StringBuilder zeile = new StringBuilder(zielBefehl);
        if (args != null) {
            for (String arg : args) {
                if (arg != null && ! arg.isBlank()) {
                    zeile.append(' ').append(arg.trim());
                }
            }
        }
        UUID alsUuid = null;
        if (sender instanceof Player spieler) {
            alsUuid = spieler.getUniqueId();
        }
        try {
            kanal.sendeBefehl(alsUuid, zeile.toString());
        } catch (Exception e) {
            sender.sendMessage(prefixHinweis("Der Proxy ist nicht erreichbar "
                    + "(mind. 1 Spieler muss online sein, damit die Bridge tunneln kann).", NamedTextColor.RED));
        }
    }

    private static Component prefixHinweis(String text, NamedTextColor farbe) {
        return Component.text().append(Component.text("[PunishSystem] ", NamedTextColor.RED))
                .append(Component.text(text, farbe)).build();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return vorschlaege(sender, args);
    }

    @Override
    public List<String> vorschlaege(CommandSender sender, String[] args) {
        if (!darfSehen(sender)) {
            return List.of();
        }
        if (args == null || args.length == 0) {
            return spielerVorschlag(sender, "");
        }
        String befehl = zielBefehl.trim().toLowerCase(Locale.ROOT);
        String aktuell = args[args.length - 1] == null ? "" : args[args.length - 1];
        if (mitScopeVorschlag && aktuell.startsWith("--")) {
            return TabHinweis.filtere(scopeVorschlaege(), aktuell);
        }
        if (aktuell.startsWith("-") && ! aktuell.startsWith("--")) {
            return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
        }
        return switch (befehl) {
            case "ban", "mute", "timeout" -> strafeMitDauer(sender, args, aktuell, befehl);
            case "ban-ip", "mute-ip", "timeout-ip" -> strafeMitDauer(sender, args, aktuell, befehl);
            case "permabann" -> grundAb(sender, args, aktuell, "ban", 2);
            case "kick" -> kickNormal(sender, args, aktuell);
            case "kick-ip" -> kickIp(sender, args, aktuell);
            case "ban-all" -> banAll(sender, args, aktuell);
            case "mute-all" -> muteTimeoutAll(sender, args, aktuell, "mute");
            case "timeout-all" -> muteTimeoutAll(sender, args, aktuell, "timeout");
            case "kick-all", "pardon-all", "unmute-all", "untimeout-all" ->
                    bestaetigungUndGrund(sender, args, aktuell, 1);
            case "pardon" -> entstrafe(sender, aktuell, "pardon");
            case "unmute" -> entstrafe(sender, aktuell, "unmute");
            case "untimeout" -> entstrafe(sender, aktuell, "untimeout");
            case "unban-ip" -> entstrafeIp(sender, aktuell, "unban-ip");
            case "unmute-ip" -> entstrafeIp(sender, aktuell, "unmute-ip");
            case "untimeout-ip" -> entstrafeIp(sender, aktuell, "untimeout-ip");
            case "verstoss" -> verstoss(sender, args, aktuell);
            case "warn" -> warn(sender, args, aktuell);
            case "punish-note", "psnote" -> notiz(sender, args, aktuell);
            case "punish-edit", "psedit" -> edit(sender, args, aktuell);
            case "punish-status", "psstatus", "punish-history", "pshistory" ->
                    statusHistory(befehl, sender, args, aktuell);
            case "pscheck" -> args.length == 1 ? spielerVorschlag(sender, aktuell) : List.of();
            case "alts" -> alts(args, aktuell, sender);
            case "punish-delete-ip", "psdelete-ip", "psdeleteip" -> deleteIp(sender, aktuell);
            case "punish-version", "psversion" -> List.of();
            case "punish-help", "pshelp" -> List.of();
            case "chatfilter" -> chatfilter(args, aktuell);
            default -> args.length <= 2 ? spielerVorschlag(sender, aktuell) : endFlags(sender, aktuell);
        };
    }

    @Override
    public String hinweisFuer(String vorschlag, String[] args) {
        String kategorie = TabHinweis.kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        String befehl = zielBefehl.trim().toLowerCase(Locale.ROOT);
        if ((befehl.equals("punish-status") || befehl.equals("psstatus")
                || befehl.equals("punish-history") || befehl.equals("pshistory"))
                && vorschlag != null) {
            if (vorschlag.equalsIgnoreCase("self")) {
                return "Eigene Daten";
            }
            if (vorschlag.equalsIgnoreCase("server")) {
                return "Server-Daten";
            }
        }
        String grundSchluessel = grundSchluesselFuerHinweis();
        if (grundSchluessel != null && vorschlag != null && config != null) {
            try {
                String dauer = config.empfohleneDauer(grundSchluessel, vorschlag.trim());
                if (dauer != null && ! dauer.isBlank()) {
                    return "Empf. Dauer: " + dauer.trim();
                }
            } catch (Exception e) {
                LOG.trace("Empfohlene Dauer konnte nicht gelesen werden.", e);
            }
        }
        return positionsHinweis(args);
    }

    private String grundSchluesselFuerHinweis() {
        return switch (zielBefehl.trim().toLowerCase(Locale.ROOT)) {
            case "ban", "ban-ip", "ban-all", "permabann" -> "ban";
            case "mute", "mute-ip", "mute-all" -> "mute";
            case "timeout", "timeout-ip", "timeout-all" -> "timeout";
            default -> null;
        };
    }

    private String positionsHinweis(String[] args) {
        int position = args == null ? 0 : args.length;
        return switch (zielBefehl.trim().toLowerCase(Locale.ROOT)) {
            case "ban", "mute", "timeout" -> position <= 1 ? "Spieler" : position == 2 ? "Dauer" : "Grund";
            case "ban-ip", "mute-ip", "timeout-ip" -> {
                if (position <= 1) {
                    yield "Spieler oder IP";
                }
                String letztes = (args == null || args.length == 0) ? "" : args[args.length - 1];
                yield TabHinweis.dauerFoermig(letztes) ? "Dauer" : "Grund";
            }
            case "permabann", "kick" -> position <= 1 ? "Spieler" : "Grund";
            case "kick-ip" -> position <= 1 ? "Spieler oder IP" : "Grund";
            case "ban-all", "mute-all", "timeout-all" -> position <= 1 ? "Dauer" : "Grund";
            case "kick-all", "pardon-all", "unmute-all", "untimeout-all" -> "Grund";
            case "pardon" -> "Aktive Sperre";
            case "unmute" -> "Aktive Stummschaltung";
            case "untimeout" -> "Aktiver Timeout";
            case "unban-ip" -> "Aktive IP-Sperre";
            case "unmute-ip" -> "Aktive IP-Stummschaltung";
            case "untimeout-ip" -> "Aktiver IP-Timeout";
            case "verstoss" -> position <= 1 ? "Unterbefehl" : position == 2 ? "Spieler" : "Grund";
            case "warn" -> {
                if (position <= 1) {
                    yield "Spieler oder Unterbefehl";
                }
                if (position == 2 && args != null && args.length > 0 && args[0] != null) {
                    String sub = args[0].toLowerCase(Locale.ROOT);
                    if (sub.equals("add") || sub.equals("delete") || sub.equals("list")) {
                        yield "Spieler";
                    }
                    yield "Grund";
                }
                yield "Grund";
            }
            case "punish-note", "psnote" ->
                    position <= 1 ? "Unterbefehl" : position == 2 ? "Spieler" : "Text";
            case "punish-edit", "psedit" -> {
                if (position <= 1) {
                    yield "Modus oder Typ";
                }
                if (position == 2) {
                    yield "Typ oder Spieler";
                }
                if (position == 3) {
                    yield "Spieler";
                }
                yield "Feld";
            }
            case "punish-status", "psstatus", "punish-history", "pshistory" -> "Spieler";
            case "pscheck" -> "Spieler";
            case "chatfilter" -> "Unterbefehl";
            case "alts" -> "Spieler";
            case "punish-delete-ip", "psdelete-ip", "psdeleteip" -> "Spieler oder IP";
            default -> null;
        };
    }

    private List<String> strafeMitDauer(CommandSender sender, String[] args, String aktuell,
                                       String befehl) {
        if (args.length == 1) {
            return spielerVorschlag(sender, aktuell);
        }
        if (args.length == 2) {
            List<String> dauern = new ArrayList<>(dauernFuer(befehl));
            if ("ban-ip".equals(befehl) && ! dauern.stream().anyMatch(d -> d.equalsIgnoreCase("perm"))
                    && hat(sender, "punish.permabann")) {
                dauern.add("perm");
            }
            if ("ban-ip".equals(befehl)) {
                dauern.addAll(grundFuer("ban"));
            }
            return TabHinweis.filtere(dauern, aktuell);
        }
        return grundOderEnde(sender, args, 2, befehlOhneIp(befehl));
    }

    private List<String> grundAb(CommandSender sender, String[] args, String aktuell,
                                 String grundSchluessel, int abPosition) {
        if (args.length == 1) {
            return spielerVorschlag(sender, aktuell);
        }
        return grundOderEnde(sender, args, abPosition - 1, grundSchluessel);
    }

    private List<String> grundOderEnde(CommandSender sender, String[] args, int grundStartIndex,
                                       String grundSchluessel) {
        if (TabHinweis.hatGrundBegonnen(args, grundStartIndex)) {
            return endFlags(sender, args[args.length - 1]);
        }
        List<String> gemischt = new ArrayList<>(grundFuer(grundSchluessel));
        gemischt.addAll(endFlags(sender, ""));
        return TabHinweis.filtere(gemischt, args[args.length - 1]);
    }

    private List<String> grundUndEnde(CommandSender sender, String aktuell, String grundSchluessel) {
        List<String> gemischt = new ArrayList<>(grundFuer(grundSchluessel));
        gemischt.addAll(endFlags(sender, ""));
        return TabHinweis.filtere(gemischt, aktuell);
    }

    private List<String> endFlags(CommandSender sender, String aktuell) {
        List<String> flags = new ArrayList<>();
        if (mitScopeVorschlag) {
            flags.addAll(scopeVorschlaege());
        }
        if (darfSilent(sender)) {
            flags.add("-s");
        }
        return TabHinweis.filtere(flags, aktuell);
    }

    private List<String> kickNormal(CommandSender sender, String[] args, String aktuell) {
        if (args.length == 1) {
            return spielerVorschlag(sender, aktuell);
        }
        if (args.length == 2) {
            return grundUndEnde(sender, aktuell, "kick");
        }
        return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
    }

    private List<String> kickIp(CommandSender sender, String[] args, String aktuell) {
        if (args.length == 1) {
            Set<String> vorschlaege = new LinkedHashSet<>();
            if (hat(sender, "punish.kickip") || hat(sender, "punish.alts")) {
                vorschlaege.addAll(onlineIps(sender));
            }
            vorschlaege.addAll(spielerVorschlag(sender, aktuell));
            return TabHinweis.filtere(new ArrayList<>(vorschlaege), aktuell);
        }
        if (args.length == 2) {
            return grundUndEnde(sender, aktuell, "kick");
        }
        return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
    }

    private List<String> banAll(CommandSender sender, String[] args, String aktuell) {
        if (args.length == 1) {
            List<String> dauern = new ArrayList<>(dauernFuer("ban"));
            if (hat(sender, "punish.permabann") && dauern.stream().noneMatch(d -> d.equalsIgnoreCase("perm"))) {
                dauern.add("perm");
            }
            return TabHinweis.filtere(dauern, aktuell);
        }
        if (args.length == 2) {
            return TabHinweis.filtere(List.of("confirm"), aktuell);
        }
        return grundOderEnde(sender, args, 2, "ban");
    }

    private List<String> muteTimeoutAll(CommandSender sender, String[] args, String aktuell,
                                         String schluessel) {
        if (args.length == 1) {
            return TabHinweis.filtere(new ArrayList<>(dauernFuer(schluessel)), aktuell);
        }
        if (args.length == 2) {
            return TabHinweis.filtere(List.of("confirm"), aktuell);
        }
        return grundOderEnde(sender, args, 2, schluessel);
    }

    private List<String> bestaetigungUndGrund(CommandSender sender, String[] args, String aktuell, int confirmPos) {
        if (args.length == confirmPos) {
            List<String> vorschlaege = new ArrayList<>(List.of("confirm"));
            vorschlaege.addAll(grundFuer("ban"));
            vorschlaege.addAll(endFlags(sender, ""));
            return TabHinweis.filtere(vorschlaege, aktuell);
        }
        if (args.length == confirmPos + 1) {
            return grundUndEnde(sender, aktuell, "ban");
        }
        return endFlags(sender, aktuell);
    }

    private List<String> entstrafe(CommandSender sender, String aktuell, String befehl) {
        if (aktuell.startsWith("--") || (aktuell.startsWith("-") && ! aktuell.startsWith("--"))) {
            return entstrafeFlags(sender, aktuell);
        }
        List<String> aktive = aktiveNamen(befehl, aktuell);
        if (! aktive.isEmpty() || ! aktuell.isBlank()) {
            return aktive;
        }
        return aktiveNamen(befehl, "");
    }

    private List<String> entstrafeIp(CommandSender sender, String aktuell, String befehl) {
        if (aktuell.startsWith("--") || (aktuell.startsWith("-") && ! aktuell.startsWith("--"))) {
            return entstrafeFlags(sender, aktuell);
        }
        List<String> aktive = aktiveIps(befehl, aktuell);
        if (! aktive.isEmpty() || ! aktuell.isBlank()) {
            return aktive;
        }
        return aktiveIps(befehl, "");
    }

    private List<String> entstrafeFlags(CommandSender sender, String aktuell) {
        if (aktuell.startsWith("--")) {
            List<String> flags = new ArrayList<>();
            flags.add("--global");
            for (String server : serverNamen()) {
                flags.add("--" + server);
            }
            flags.add("--unbekannt");
            return TabHinweis.filtere(flags, aktuell);
        }
        return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
    }

    private List<String> aktiveNamen(String befehl, String prefix) {
        long jetzt = System.currentTimeMillis();
        Set<String> namen = new LinkedHashSet<>();
        try {
            if ("pardon".equals(befehl) && banCache != null) {
                for (var eintrag : banCache.alle()) {
                    if (eintrag != null && eintrag.istAktiv(jetzt) && eintrag.name() != null
                            && ! eintrag.name().isBlank()) {
                        namen.add(eintrag.name().trim());
                    }
                    if (namen.size() >= 50) {
                        break;
                    }
                }
            } else if ("unmute".equals(befehl) && muteCache != null) {
                for (var eintrag : muteCache.alle()) {
                    if (eintrag != null && eintrag.istAktiv(jetzt) && eintrag.name() != null
                            && ! eintrag.name().isBlank()) {
                        namen.add(eintrag.name().trim());
                    }
                    if (namen.size() >= 50) {
                        break;
                    }
                }
            } else if ("untimeout".equals(befehl) && timeoutCache != null) {
                for (var eintrag : timeoutCache.alle()) {
                    if (eintrag != null && eintrag.istAktiv(jetzt) && eintrag.name() != null
                            && ! eintrag.name().isBlank()) {
                        namen.add(eintrag.name().trim());
                    }
                    if (namen.size() >= 50) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOG.trace("Aktive Strafnamen konnten nicht gelesen werden.", e);
        }
        List<String> gefiltert = TabHinweis.filtere(new ArrayList<>(namen), prefix);
        if (! gefiltert.isEmpty()) {
            gefiltert.sort(String.CASE_INSENSITIVE_ORDER);
            return TabHinweis.begrenzeDistinct(gefiltert, MAX_VORSCHLAEGE);
        }
        if (! namen.isEmpty()) {
            return List.of();
        }
        return spielerVorschlagFallback(prefix);
    }

    private List<String> aktiveIps(String befehl, String prefix) {
        long jetzt = System.currentTimeMillis();
        Set<String> ips = new LinkedHashSet<>();
        try {
            if ("unmute-ip".equals(befehl) && ipMuteCache != null) {
                for (var eintrag : ipMuteCache.alle()) {
                    if (eintrag != null && eintrag.istAktiv(jetzt) && eintrag.ip() != null
                            && ! eintrag.ip().isBlank()) {
                        ips.add(eintrag.ip().trim());
                    }
                    if (ips.size() >= MAX_VORSCHLAEGE) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOG.trace("Aktive IP-Strafen konnten nicht gelesen werden.", e);
        }
        List<String> gefiltert = TabHinweis.filtere(new ArrayList<>(ips), prefix);
        if (! gefiltert.isEmpty()) {
            gefiltert.sort(String.CASE_INSENSITIVE_ORDER);
            return TabHinweis.begrenzeDistinct(gefiltert, MAX_VORSCHLAEGE);
        }
        return List.of();
    }

    private List<String> spielerVorschlagFallback(String prefix) {
        try {
            Set<String> vorschlaege = new LinkedHashSet<>(filtereBestrafbareOnline(null, ""));
            List<String> gefiltert = TabHinweis.filtere(new ArrayList<>(vorschlaege), prefix);
            gefiltert.sort(String.CASE_INSENSITIVE_ORDER);
            return TabHinweis.begrenzeDistinct(gefiltert, MAX_VORSCHLAEGE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> verstoss(CommandSender sender, String[] args, String aktuell) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (hat(sender, "punish.verstoss") || hat(sender, "punish.verstoss.add")) {
                subs.add("add");
            }
            if (hat(sender, "punish.verstoss") || hat(sender, "punish.verstoss.remove")) {
                subs.add("remove");
            }
            if (hat(sender, "punish.verstoss") || hat(sender, "punish.verstoss.clear")) {
                subs.add("clear");
            }
            return TabHinweis.filtere(subs, aktuell);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return spielerVorschlag(sender, aktuell);
        }
        if ("add".equals(sub)) {
            if (aktuell.toLowerCase(Locale.ROOT).startsWith("level=")) {
                return TabHinweis.filtere(List.of("level=low", "level=medium", "level=high"), aktuell);
            }
            if (args.length > 3) {
                return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
            }
            List<String> gemischt = new ArrayList<>(grundFuer("verstoss"));
            gemischt.add("level=low");
            gemischt.add("level=medium");
            gemischt.add("level=high");
            if (darfSilent(sender)) {
                gemischt.add("-s");
            }
            return TabHinweis.filtere(gemischt, aktuell);
        }
        return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
    }

    private List<String> warn(CommandSender sender, String[] args, String aktuell) {
        if (args.length == 1) {
            List<String> vorschlaege = new ArrayList<>(List.of("add", "delete", "list"));
            vorschlaege.addAll(spielerVorschlag(sender, aktuell));
            return TabHinweis.filtere(vorschlaege.stream().distinct().toList(), aktuell);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "add" -> spielerVorschlag(sender, aktuell);
                case "delete", "list" -> spielerVorschlag(sender, aktuell);
                default -> grundUndEndeOhneScope(sender, aktuell, "warn");
            };
        }
        if ("add".equals(sub)) {
            if (args.length > 3) {
                return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
            }
            return grundUndEndeOhneScope(sender, aktuell, "warn");
        }
        return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
    }

    private List<String> grundUndEndeOhneScope(CommandSender sender, String aktuell, String grundSchluessel) {
        List<String> gemischt = new ArrayList<>(grundFuer(grundSchluessel));
        if (darfSilent(sender)) {
            gemischt.add("-s");
        }
        return TabHinweis.filtere(gemischt, aktuell);
    }

    private List<String> notiz(CommandSender sender, String[] args, String aktuell) {
        if (args.length == 1) {
            return TabHinweis.filtere(List.of("add", "delete", "list"), aktuell);
        }
        if (args.length == 2) {
            return spielerVorschlag(sender, aktuell);
        }
        return darfSilent(sender) ? TabHinweis.filtere(List.of("-s"), aktuell) : List.of();
    }

    private List<String> erlaubteEditTypen(CommandSender sender) {
        if (hat(sender, "punish.edit")) {
            return List.of("ban", "mute", "timeout", "ban-ip", "mute-ip", "timeout-ip",
                    "verstoss", "warn", "note");
        }
        List<String> erlaubte = new ArrayList<>();
        if (hat(sender, "punish.ban")) {
            erlaubte.add("ban");
        }
        if (hat(sender, "punish.mute")) {
            erlaubte.add("mute");
        }
        if (hat(sender, "punish.timeout")) {
            erlaubte.add("timeout");
        }
        if (hat(sender, "punish.banip")) {
            erlaubte.add("ban-ip");
        }
        if (hat(sender, "punish.muteip")) {
            erlaubte.add("mute-ip");
        }
        if (hat(sender, "punish.timeoutip")) {
            erlaubte.add("timeout-ip");
        }
        if (hat(sender, "punish.verstoss") || hat(sender, "punish.verstoss.edit")) {
            erlaubte.add("verstoss");
        }
        if (hat(sender, "punish.warn")) {
            erlaubte.add("warn");
        }
        if (hat(sender, "punish.note")) {
            erlaubte.add("note");
        }
        return erlaubte;
    }

    private boolean darfEditTyp(CommandSender sender, String typ) {
        if (sender == null || typ == null || typ.isBlank()) {
            return false;
        }
        if (hat(sender, "punish.edit")) {
            return true;
        }
        return switch (typ.trim().toLowerCase(Locale.ROOT)) {
            case "ban" -> hat(sender, "punish.ban");
            case "mute" -> hat(sender, "punish.mute");
            case "timeout" -> hat(sender, "punish.timeout");
            case "ban-ip", "banip" -> hat(sender, "punish.banip");
            case "mute-ip", "muteip" -> hat(sender, "punish.muteip");
            case "timeout-ip", "timeoutip" -> hat(sender, "punish.timeoutip");
            case "verstoss", "verstoß" -> hat(sender, "punish.verstoss")
                    || hat(sender, "punish.verstoss.edit");
            case "warn" -> hat(sender, "punish.warn");
            case "note", "punish-note" -> hat(sender, "punish.note");
            default -> false;
        };
    }

    private List<String> dauernOhnePermFuerEdit(String befehl, CommandSender sender) {
        List<String> alle = new ArrayList<>(dauernFuer(befehl));
        if (hat(sender, "punish.permabann")) {
            return alle;
        }
        alle.removeIf(d -> d != null && (d.trim().equalsIgnoreCase("perm")
                || d.trim().equalsIgnoreCase("permanent") || d.trim().equalsIgnoreCase("perma")));
        return alle;
    }

    private List<String> edit(CommandSender sender, String[] args, String aktuell) {
        List<String> typen = erlaubteEditTypen(sender);
        if (args.length == 1) {
            List<String> vorschlaege = new ArrayList<>(List.of("show"));
            vorschlaege.addAll(typen);
            return TabHinweis.filtere(vorschlaege, aktuell);
        }
        if ("show".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return TabHinweis.filtere(typen, aktuell);
            }
            String showTyp = args[1] == null ? "" : args[1].trim();
            if (!darfEditTyp(sender, showTyp)) {
                return List.of();
            }
            if (args.length == 3) {
                return spielerVorschlag(sender, aktuell);
            }
            return endFlags(sender, aktuell);
        }
        String typ = args[0] == null ? "" : args[0].trim();
        if (!darfEditTyp(sender, typ)) {
            return List.of();
        }
        if (args.length == 2) {
            return spielerVorschlag(sender, aktuell);
        }
        if (aktuell.contains("=")) {
            String key = aktuell.substring(0, aktuell.indexOf('=')).toLowerCase(Locale.ROOT);
            if (key.equals("level") || key.equals("stufe")) {
                return TabHinweis.filtere(List.of("level=low", "level=medium", "level=high"), aktuell);
            }
            if (key.equals("dauer") || key.equals("duration") || key.equals("zeit")) {
                List<String> vorschlaege = new ArrayList<>();
                for (String d : dauernOhnePermFuerEdit("ban", sender)) {
                    vorschlaege.add("dauer=" + d);
                }
                return TabHinweis.filtere(vorschlaege, aktuell);
            }
            return List.of();
        }
        if (args.length > 3) {
            return endFlags(sender, aktuell);
        }
        List<String> gemischt = new ArrayList<>(
                List.of("dauer=", "grund=", "text=", "level=low", "level=medium", "level=high"));
        gemischt.addAll(grundFuer("ban"));
        gemischt.addAll(endFlags(sender, ""));
        return TabHinweis.filtere(gemischt, aktuell);
    }

    private List<String> statusHistory(String befehl, CommandSender sender, String[] args, String aktuell) {
        List<String> filterNamen = filterNamen(befehl);
        int trenner = Math.max(aktuell.indexOf(':'), aktuell.indexOf('='));
        if (trenner >= 0) {
            String schluessel = aktuell.substring(0, trenner).toLowerCase(Locale.ROOT);
            String praefix = aktuell.substring(0, trenner + 1);
            if (schluessel.equals("filter") || schluessel.equals("typ") || schluessel.equals("type")) {
                List<String> werte = new ArrayList<>();
                for (String w : filterNamen) {
                    werte.add(praefix + w);
                }
                return TabHinweis.filtere(werte, aktuell);
            }
            if (schluessel.equals("player") || schluessel.equals("spieler")
                    || schluessel.equals("target") || schluessel.equals("ziel")
                    || schluessel.equals("p")) {
                List<String> namen = new ArrayList<>();
                for (String n : spielerVorschlag(sender, aktuell.substring(trenner + 1))) {
                    namen.add(praefix + n);
                }
                return TabHinweis.filtere(namen, aktuell);
            }
            return List.of();
        }
        boolean hatZiel = enthaeltZiel(args, args.length - 1);
        boolean hatFilter = enthaeltFilter(args, args.length - 1);
        if (! hatZiel) {
            List<String> vorschlaege = new ArrayList<>(List.of("self", "server"));
            vorschlaege.addAll(spielerVorschlag(sender, aktuell));
            vorschlaege.add("filter:");
            vorschlaege.add("player:");
            vorschlaege.add("page:");
            return TabHinweis.filtere(vorschlaege.stream().distinct().toList(), aktuell);
        }
        if (! hatFilter) {
            List<String> gemischt = new ArrayList<>();
            for (String name : filterNamen) {
                gemischt.add("filter:" + name);
            }
            gemischt.addAll(scopeVorschlaege());
            gemischt.add("--unbekannt");
            return TabHinweis.filtere(gemischt, aktuell);
        }
        List<String> vorschlaege = new ArrayList<>(scopeVorschlaege());
        vorschlaege.add("--unbekannt");
        return TabHinweis.filtere(vorschlaege, aktuell);
    }

    private static List<String> filterNamen(String befehl) {
        boolean history = "punish-history".equals(befehl) || "pshistory".equals(befehl);
        return history
                ? List.of("all", "bans", "mutes", "timeouts", "verstoesse", "warns", "kicks",
                        "ipbans", "ipmutes", "iptimeouts")
                : List.of("all", "bans", "mutes", "timeouts", "verstoesse", "warns",
                        "ipbans", "ipmutes", "iptimeouts");
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
                        || schluessel.equals("p") || schluessel.equals("filter")
                        || schluessel.equals("typ") || schluessel.equals("type")
                        || schluessel.equals("page") || schluessel.equals("seite")) {
                    if (schluessel.equals("player") || schluessel.equals("spieler")
                            || schluessel.equals("target") || schluessel.equals("ziel")
                            || schluessel.equals("p")) {
                        return true;
                    }
                    continue;
                }
            }
            if (getrimmt.matches("\\d+")) {
                continue;
            }
            if (getrimmt.startsWith("--") || getrimmt.startsWith("-")) {
                continue;
            }
            return true;
        }
        return false;
    }

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

    private static List<String> chatfilter(String[] args, String aktuell) {
        if (args.length <= 1) {
            return TabHinweis.filtere(
                    new ArrayList<>(List.of("on", "off", "ai_on", "ai_off", "add", "sync")), aktuell);
        }
        return List.of();
    }

    private List<String> alts(String[] args, String aktuell, CommandSender sender) {        if (args.length == 1) {
            return spielerVorschlag(sender, aktuell);
        }
        List<String> flags = new ArrayList<>(scopeVorschlaege());
        flags.add("--unbekannt");
        return TabHinweis.filtere(flags, aktuell);
    }

    private List<String> deleteIp(CommandSender sender, String aktuell) {
        Set<String> vorschlaege = new LinkedHashSet<>(spielerVorschlag(sender, aktuell));
        if (hat(sender, "punish.deleteip") || hat(sender, "punish.alts")) {
            vorschlaege.addAll(onlineIps(sender));
        }
        return TabHinweis.filtere(new ArrayList<>(vorschlaege), aktuell);
    }

    private List<String> scopeVorschlaege() {
        List<String> flags = new ArrayList<>(List.of("--global"));
        for (String server : serverNamen()) {
            flags.add("--" + server);
        }
        return flags;
    }

    private List<String> serverNamen() {
        if (config != null) {
            try {
                List<String> server = config.bekannteServer();
                if (server != null && ! server.isEmpty()) {
                    return server;
                }
            } catch (Exception e) {
                LOG.trace("Bekannte Server konnten nicht gelesen werden.", e);
            }
        }
        return FALLBACK_SERVER;
    }

    private List<String> dauernFuer(String befehl) {
        if (config != null) {
            try {
                List<String> dauern = config.dauerListe(befehl);
                if (dauern != null && ! dauern.isEmpty()) {
                    return dauern;
                }
            } catch (Exception e) {
                LOG.trace("Dauerliste konnte nicht gelesen werden.", e);
            }
            try {
                List<String> dauern = config.tabDauerListe();
                if (dauern != null && ! dauern.isEmpty()) {
                    return dauern;
                }
            } catch (Exception e) {
                LOG.trace("Tab-Dauerliste konnte nicht gelesen werden.", e);
            }
        }
        return FALLBACK_DAUERN;
    }

    private List<String> grundFuer(String befehl) {
        if (config != null) {
            try {
                List<String> gruende = config.grundListe(befehl);
                if (gruende != null && ! gruende.isEmpty()) {
                    return gruende;
                }
            } catch (Exception e) {
                LOG.trace("Grundliste konnte nicht gelesen werden.", e);
            }
            try {
                List<String> gruende = config.tabGrundListe();
                if (gruende != null && ! gruende.isEmpty()) {
                    return gruende;
                }
            } catch (Exception e) {
                LOG.trace("Tab-Grundliste konnte nicht gelesen werden.", e);
            }
        }
        return List.of();
    }

    private static String befehlOhneIp(String befehl) {
        if ("ban-ip".equals(befehl)) {
            return "ban";
        }
        if ("mute-ip".equals(befehl)) {
            return "mute";
        }
        if ("timeout-ip".equals(befehl)) {
            return "timeout";
        }
        return befehl;
    }

    private static boolean hat(CommandSender sender, String permission) {
        if (sender == null || permission == null || permission.isBlank()) {
            return false;
        }
        try {
            return RangBuendel.gewaehrt(sender::hasPermission, permission);
        } catch (Exception e) {
            LOG.debug("Permission-Check fehlgeschlagen.", e);
            return false;
        }
    }

    private static String nameFuerLog(CommandSender sender) {
        try {
            if (sender == null) {
                return "?";
            }
            String name = sender.getName();
            return name == null || name.isBlank() ? "?" : name;
        } catch (Exception e) {
            return "?";
        }
    }

    private static boolean darfSilent(CommandSender sender) {
        return hat(sender, "punish.allow-silent-syntax");
    }

    // B-020: Fail-closed (null/op/Fehler -> geschützt) bleibt bewusst bestehen,
    // keine Verhaltensänderung.
    private static final List<String> IGNORE_KNOTEN = List.of(
            "punish.ignore.*",
            "punish.ignore.ban",
            "punish.ignore.mute",
            "punish.ignore.timeout",
            "punish.ignore.freeze",
            "punish.ignore.kick",
            "punish.ignore.warn",
            "punish.ignore.verstoss",
            "punish.ignore.note",
            "punish.ignore.alts",
            "punish.ignore.report");

    private static boolean istGeschuetzt(Player ziel) {
        if (ziel == null) {
            return true;
        }
        try {
            if (ziel.isOp()) {
                return true;
            }
        } catch (Exception e) {
            return true;
        }
        try {
            for (String knoten : IGNORE_KNOTEN) {
                try {
                    if (ziel.hasPermission(knoten)) {
                        return true;
                    }
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> spielerVorschlag(CommandSender sender, String prefix) {
        String vergleich = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        Set<String> kombiniert = new LinkedHashSet<>(filtereBestrafbareOnline(sender, vergleich));
        if (vergleich.length() >= 3) {
            for (String offline : offlineVorschlaege(sender, vergleich, kombiniert)) {
                if (kombiniert.size() >= MAX_VORSCHLAEGE) {
                    break;
                }
                kombiniert.add(offline);
            }
        }
        List<String> ergebnis = TabHinweis.filtere(new ArrayList<>(kombiniert), prefix);
        ergebnis.sort(String.CASE_INSENSITIVE_ORDER);
        return TabHinweis.begrenzeDistinct(ergebnis, MAX_VORSCHLAEGE);
    }

    private static List<String> filtereBestrafbareOnline(CommandSender sender, String vergleichRaw) {
        String vergleich = vergleichRaw == null ? "" : vergleichRaw.trim().toLowerCase(Locale.ROOT);
        String eigenerName = sender instanceof Player spieler ? spieler.getName() : null;
        List<String> namen = new ArrayList<>();
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online == null || online.getName() == null) {
                    continue;
                }
                if (eigenerName != null && online.getName().equalsIgnoreCase(eigenerName)) {
                    continue;
                }
                if (istGeschuetzt(online)) {
                    continue;
                }
                if (vergleich.isEmpty() || online.getName().toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                    namen.add(online.getName());
                }
                if (namen.size() >= MAX_VORSCHLAEGE) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.trace("Online-Spieler konnten nicht gelesen werden.", e);
        }
        namen.sort(String.CASE_INSENSITIVE_ORDER);
        return namen;
    }

    // getOfflinePlayers() parst usercache.json synchron; der Cache hält das aus dem
    // TAB-Complete-Pfad (Server-Thread) und begrenzt es auf einen Refresh je 30 s.
    private volatile List<OfflinePlayer> offlineSpielerCache = List.of();
    private volatile long offlineSpielerStand = 0L;

    private List<OfflinePlayer> offlineSpielerMitCache() {
        long jetzt = System.currentTimeMillis();
        List<OfflinePlayer> cache = offlineSpielerCache;
        if (jetzt - offlineSpielerStand < 30_000L) {
            return cache;
        }
        try {
            List<OfflinePlayer> frisch = java.util.Arrays.asList(Bukkit.getOfflinePlayers());
            offlineSpielerCache = frisch;
            offlineSpielerStand = jetzt;
            return frisch;
        } catch (Exception e) {
            LOG.trace("Offline-Spieler konnten nicht gelesen werden.", e);
            return cache;
        }
    }

    private List<String> offlineVorschlaege(CommandSender sender, String vergleich, Set<String> bereits) {
        Set<String> klein = new LinkedHashSet<>();
        if (bereits != null) {
            for (String name : bereits) {
                if (name != null) {
                    klein.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        String eigenerName = sender instanceof Player spieler ? spieler.getName() : null;
        List<String> treffer = new ArrayList<>();
        try {
            for (OfflinePlayer offline : offlineSpielerMitCache()) {
                if (offline == null) {
                    continue;
                }
                String rohName = offline.getName();
                if (rohName == null || rohName.isBlank()) {
                    continue;
                }
                String name = rohName.trim();
                if (! name.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                    continue;
                }
                if (eigenerName != null && name.equalsIgnoreCase(eigenerName)) {
                    continue;
                }
                if (klein.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    if (offline.isOp()) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                treffer.add(name);
                klein.add(name.toLowerCase(Locale.ROOT));
                if (treffer.size() >= MAX_VORSCHLAEGE) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.trace("Offline-Spieler konnten nicht gelesen werden.", e);
        }
        PlayerCache cache = spielerCache;
        if (treffer.size() < MAX_VORSCHLAEGE && cache != null) {
            try {
                for (String name : cache.alleNamen()) {
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    String getrimmt = name.trim();
                    if (! getrimmt.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                        continue;
                    }
                    if (klein.contains(getrimmt.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    treffer.add(getrimmt);
                    klein.add(getrimmt.toLowerCase(Locale.ROOT));
                    if (treffer.size() >= MAX_VORSCHLAEGE) {
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.trace("Spieler-Cache konnte nicht gelesen werden.", e);
            }
        }
        treffer.sort(String.CASE_INSENSITIVE_ORDER);
        return treffer;
    }

    private List<String> onlineIps(CommandSender sender) {
        Set<String> ips = new LinkedHashSet<>();
        boolean vollansicht = hat(sender, "punish.alts") || hat(sender, "punish.deleteip");
        if (!vollansicht) {
            return new ArrayList<>(ips);
        }
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online == null) {
                    continue;
                }
                String roh = null;
                try {
                    var adresse = online.getAddress();
                    if (adresse != null && adresse.getAddress() != null) {
                        roh = adresse.getAddress().getHostAddress();
                    }
                } catch (Exception e) {
                    LOG.trace("Online-IP konnte nicht gelesen werden.", e);
                }
                if (roh == null || roh.isBlank() || roh.equalsIgnoreCase("unbekannt")) {
                    continue;
                }
                try {
                    String kanonisch = de.lightrixx.punish.model.IpBanRecord.kanonisieren(roh.trim());
                    ips.add(kanonisch);
                } catch (Exception e) {
                    LOG.trace("Online-IP konnte nicht kanonisiert werden.", e);
                }
                if (ips.size() >= MAX_VORSCHLAEGE) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.trace("Online-IPs konnten nicht gelesen werden.", e);
        }
        return new ArrayList<>(ips);
    }
}
