package de.lightrixx.punish.bridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.List;

public interface TunnelVorschlag {

    String zielBefehl();

    String recht();

    List<String> vorschlaege(CommandSender sender, String[] args);

    void fuehreAus(CommandSender sender, String[] args);

    String hinweisFuer(String vorschlag, String[] args);

    default void sendeAbweisung(CommandSender sender) {
        if (sender == null) {
            return;
        }
        try {
            sender.sendMessage(Component.text().append(Component.text("[PunishSystem] ", NamedTextColor.RED))
                    .append(Component.text("Dazu hast du keine Berechtigung.", NamedTextColor.RED)).build());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TunnelVorschlag.class).debug("Abweisung konnte nicht gesendet werden.", e);
        }
    }

    default boolean darfSehen(CommandSender sender) {
        return sender != null;
    }
}
