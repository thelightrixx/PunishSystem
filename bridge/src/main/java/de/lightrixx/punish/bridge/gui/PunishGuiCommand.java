package de.lightrixx.punish.bridge.gui;

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

public final class PunishGuiCommand implements CommandExecutor, TabCompleter,
        de.lightrixx.punish.bridge.TunnelVorschlag {

    private final GuiManager manager;
    private final boolean dossierModus;

    public PunishGuiCommand(GuiManager manager) {
        this(manager, false);
    }

    public PunishGuiCommand(GuiManager manager, boolean dossierModus) {
        this.manager = Objects.requireNonNull(manager, "Der Manager darf nicht null sein.");
        this.dossierModus = dossierModus;
    }

    @Override
    public String zielBefehl() {
        return dossierModus ? "punish-dossier" : "punish-gui";
    }

    @Override
    public String recht() {
        return "punish.gui";
    }

    @Override
    public boolean darfSehen(CommandSender sender) {
        if (!(sender instanceof Player spieler)) {
            return false;
        }
        try {
            return manager.darfOeffnen(spieler);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String hinweisFuer(String vorschlag, String[] args) {
        return "Spieler";
    }

    @Override
    public void sendeAbweisung(CommandSender sender) {
        if (sender == null) {
            return;
        }
        try {
            if (!(sender instanceof Player)) {
                sender.sendMessage(prefixHinweis("Dieser Befehl kann nur von einem Spieler ausgeführt werden.", NamedTextColor.RED));
                return;
            }
            sender.sendMessage(prefixHinweis("Dazu hast du keine Berechtigung.", NamedTextColor.RED));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PunishGuiCommand.class).debug("Abweisung konnte nicht gesendet werden.", e);
        }
    }

    private static Component prefixHinweis(String text, NamedTextColor farbe) {
        return Component.text().append(Component.text("[PunishSystem] ", NamedTextColor.RED))
                .append(Component.text(text, farbe)).build();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player spieler)) {
            sender.sendMessage(prefixHinweis("Dieser Befehl kann nur von einem Spieler ausgeführt werden.", NamedTextColor.RED));
            return true;
        }
        if (!manager.darfOeffnen(spieler)) {
            spieler.sendMessage(prefixHinweis("Dazu hast du keine Berechtigung.", NamedTextColor.RED));
            return true;
        }
        fuehreAus(sender, args);
        return true;
    }

    @Override
    public void fuehreAus(CommandSender sender, String[] args) {
        if (!(sender instanceof Player spieler)) {
            return;
        }
        String startZiel = args != null && args.length >= 1 && args[0] != null && !args[0].isBlank()
                ? args[0].trim() : null;
        if (dossierModus) {
            if (startZiel == null) {
                spieler.sendMessage(prefixHinweis("Verwendung: /punish-dossier <Spieler>",
                        NamedTextColor.RED));
                return;
            }
            manager.oeffneDossier(spieler, startZiel, 1);
            return;
        }
        manager.starteNeu(spieler, startZiel);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player spieler) || !manager.darfOeffnen(spieler)) {
            return List.of();
        }
        return vorschlaege(sender, args);
    }

    @Override
    public List<String> vorschlaege(CommandSender sender, String[] args) {
        if (!(sender instanceof Player spieler)) {
            return List.of();
        }
        if (args == null || args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0];
        String vergleich = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> namen = new ArrayList<>();
        try {
            for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (online == null || online.getName() == null) {
                    continue;
                }
                if (online.getName().equalsIgnoreCase(spieler.getName())) {
                    continue;
                }
                boolean geschuetzt = false;
                try {
                    geschuetzt = online.isOp() || hatIgnoreSchutz(online);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(PunishGuiCommand.class).debug("Spieler-Permission konnte nicht gelesen werden.", e);
                }
                if (geschuetzt) {
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
            org.slf4j.LoggerFactory.getLogger(PunishGuiCommand.class).debug("Online-Spieler konnten nicht gelesen werden.", e);
        }
        namen.sort(String.CASE_INSENSITIVE_ORDER);
        return namen;
    }

    private static boolean hatIgnoreSchutz(Player spieler) {
        for (String knoten : List.of(
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
                "punish.ignore.report")) {
            try {
                if (spieler.hasPermission(knoten)) {
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
