package de.lightrixx.punish.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.RangBuendel;
import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.UUID;

public final class VelocitySender implements BefehlsSender {

    private final CommandSource quelle;

    public VelocitySender(CommandSource quelle) {
        this.quelle = Objects.requireNonNull(quelle, "Die Quelle darf nicht null sein.");
    }

    public CommandSource quelle() {
        return quelle;
    }

    @Override
    public void nachricht(Component nachricht) {
        Objects.requireNonNull(nachricht, "Die Nachricht darf nicht null sein.");
        try {
            quelle.sendMessage(nachricht);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocitySender.class).debug("Nachricht senden fehlgeschlagen.", e);
        }
    }

    @Override
    public boolean hatRecht(String knoten) {
        if (knoten == null || knoten.isBlank()) {
            return false;
        }
        try {
            return RangBuendel.gewaehrt(quelle::hasPermission, knoten);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean istSpieler() {
        return quelle instanceof Player;
    }

    @Override
    public UUID spielerUuid() {
        if (quelle instanceof Player spieler) {
            try {
                return spieler.getUniqueId();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String name() {
        if (quelle instanceof Player spieler) {
            try {
                String name = spieler.getUsername();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(VelocitySender.class).debug("Spielername-Lookup fehlgeschlagen.", e);
            }
            return "Unbekannt (" + spieler.getUniqueId() + ")";
        }
        return "Konsole";
    }

    @Override
    public String herkunft() {

        if (quelle instanceof Player spieler) {
            try {
                return spieler.getCurrentServer()
                        .map(v -> v.getServerInfo().getName())
                        .filter(n -> n != null && !n.isBlank())
                        .map(n -> Objects.requireNonNull(n, "Servername").trim())
                        .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                        .orElse(null);
            } catch (Exception e) {
                return null;
            }
        }
        return "konsole";
    }
}
