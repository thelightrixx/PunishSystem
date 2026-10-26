package de.lightrixx.punish.bridge;

import de.lightrixx.punish.core.RangBuendel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Leitet /report und /reports an den Proxy weiter.
 * Source of Truth ist das Velocity-Plugin: Die Ausführung erfolgt dort
 * (per BEFEHL_AUSFUEHREN als derselbe Spieler), die Antwort sieht der
 * Spieler direkt über die Proxy-Verbindung.
 */
public final class ReportWeiterleitung implements CommandExecutor, TabCompleter, TunnelVorschlag {

    private static final List<String> REPORTS_UNTERBEFEHLE =
            List.of("list", "offen", "alle", "info", "accept", "deny", "close", "delete");

    private final KanalClient kanal;
    private final String zielBefehl;
    private final String permission;

    public ReportWeiterleitung(KanalClient kanal, String zielBefehl) {
        this(kanal, zielBefehl, "");
    }

    public ReportWeiterleitung(KanalClient kanal, String zielBefehl, String permission) {
        this.kanal = Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        this.zielBefehl = Objects.requireNonNull(zielBefehl, "Der Zielbefehl darf nicht null sein.");
        this.permission = permission == null ? "" : permission;
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
        return hat(sender, permission);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        fuehreAus(sender, args);
        return true;
    }

    @Override
    public void fuehreAus(CommandSender sender, String[] args) {
        if (!darfSehen(sender)) {
            org.slf4j.LoggerFactory.getLogger(ReportWeiterleitung.class).warn(
                    "Lokale Ablehnung /{} von {} ohne Recht {} (Proxy-Re-Check bleibt).",
                    zielBefehl, nameFuerLog(sender), permission);
            sender.sendMessage(Component.text().append(Component.text("[PunishSystem] ", NamedTextColor.RED))
                    .append(Component.text("Dazu hast du keine Berechtigung.", NamedTextColor.RED)).build());
            return;
        }
        StringBuilder zeile = new StringBuilder(zielBefehl);
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) {
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
            sender.sendMessage(Component.text().append(Component.text("[PunishSystem] ", NamedTextColor.RED))
                    .append(Component.text("Der Report konnte nicht an den Proxy gesendet werden "
                            + "(kein Online-Spieler als Nachrichten-Träger?).", NamedTextColor.RED)).build());
        }
    }

    private static boolean hat(CommandSender sender, String permission) {
        if (sender == null || permission == null || permission.isBlank()) {
            return false;
        }
        try {
            return RangBuendel.gewaehrt(sender::hasPermission, permission);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ReportWeiterleitung.class).debug("Permission-Check fehlgeschlagen.", e);
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

    @Override
    public String hinweisFuer(String vorschlag, String[] args) {
        if ("reports".equalsIgnoreCase(zielBefehl)) {
            if (vorschlag != null && vorschlag.trim().matches("\\d+")) {
                return "Report-ID";
            }
            String k = de.lightrixx.punish.util.TabHinweis.kategorieTooltip(vorschlag);
            if (k != null && !k.isBlank()) {
                return k;
            }
            int position = args == null ? 0 : args.length;
            return position <= 1 ? "Spieler" : "Grund";
        }
        String kategorie = de.lightrixx.punish.util.TabHinweis.kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        int position = args == null ? 0 : args.length;
        return position <= 1 ? "Spieler" : "Grund";
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
        if (args == null || args.length <= 1) {
            String prefix = args == null || args.length == 0 ? "" : args[0];
            if ("reports".equalsIgnoreCase(zielBefehl)) {
                return filtere(REPORTS_UNTERBEFEHLE, prefix);
            }
            return filtereOnlineSpieler(sender, prefix);
        }
        if ("reports".equalsIgnoreCase(zielBefehl) && args.length == 2
                && "list".equalsIgnoreCase(args[0])) {
            return filtere(List.of("offen", "alle"), args[1]);
        }
        return List.of();
    }

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

    static boolean istGeschuetzt(Player ziel) {
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

    private List<String> filtereOnlineSpieler(CommandSender sender, String prefix) {
        String vergleich = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        String eigenerName = sender instanceof Player spieler ? spieler.getName() : null;
        List<String> namen = new ArrayList<>();
        try {
            for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
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
                if (namen.size() >= 25) {
                    break;
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ReportWeiterleitung.class).debug("Online-Spieler konnten nicht gelesen werden.", e);
        }
        namen.sort(String.CASE_INSENSITIVE_ORDER);
        return namen;
    }

    private static List<String> filtere(List<String> vorschlaege, String prefix) {
        if (vorschlaege == null || vorschlaege.isEmpty()) {
            return List.of();
        }
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(vorschlaege);
        }
        String vergleich = prefix.toLowerCase(Locale.ROOT);
        List<String> ergebnis = new ArrayList<>();
        for (String vorschlag : vorschlaege) {
            if (vorschlag != null && vorschlag.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                ergebnis.add(vorschlag);
            }
        }
        return ergebnis;
    }
}
