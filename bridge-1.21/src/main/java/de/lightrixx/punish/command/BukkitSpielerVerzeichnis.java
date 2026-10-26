package de.lightrixx.punish.command;

import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BukkitSpielerVerzeichnis implements SpielerVerzeichnis {

    private final Plugin plugin;

    public BukkitSpielerVerzeichnis(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
    }

    @Override
    public List<OnlineSpieler> onlineSpieler() {
        List<OnlineSpieler> ergebnis = new ArrayList<>();
        try {
            for (Player spieler : Bukkit.getOnlinePlayers()) {
                OnlineSpieler eintrag = von(spieler);
                if (eintrag != null) {
                    ergebnis.add(eintrag);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Online-Spieler konnten nicht gelesen werden.", e);
        }
        return List.copyOf(ergebnis);
    }

    @Override
    public Optional<OnlineSpieler> onlineSpieler(UUID uuid) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        try {
            Player spieler = Bukkit.getPlayer(uuid);
            return Optional.ofNullable(von(spieler));
        } catch (Exception e) {
            throw new IllegalStateException("Online-Spieler-Lookup für " + uuid + " fehlgeschlagen.", e);
        }
    }

    @Override
    public Optional<OnlineSpieler> onlineSpielerNachName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String gesucht = name.trim();
        try {
            Player exakt = Bukkit.getPlayerExact(gesucht);
            if (exakt != null) {
                return Optional.ofNullable(von(exakt));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Online-Spieler-Lookup für '" + gesucht + "' fehlgeschlagen.", e);
        }
        return Optional.empty();
    }

    @Override
    public void trennen(UUID uuid, Component grund) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        Player spieler;
        try {
            spieler = Bukkit.getPlayer(uuid);
        } catch (Exception e) {
            return;
        }
        if (spieler == null) {
            return;
        }
        spieler.kick(grund);
    }

    @Override
    public void mitteilen(UUID uuid, Component nachricht) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        Objects.requireNonNull(nachricht, "Die Nachricht darf nicht null sein.");
        Player spieler;
        try {
            spieler = Bukkit.getPlayer(uuid);
        } catch (Exception e) {
            return;
        }
        if (spieler == null) {
            return;
        }
        try {
            spieler.sendMessage(nachricht);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BukkitSpielerVerzeichnis.class).debug("Nachricht konnte nicht gesendet werden.", e);
        }
    }

    @Override
    public boolean hatRecht(UUID uuid, String knoten) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        if (knoten == null || knoten.isBlank()) {
            return false;
        }
        try {
            Player spieler = Bukkit.getPlayer(uuid);
            return spieler != null
                    && RangBuendel.gewaehrt(spieler::hasPermission, knoten);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean darfDirektAusfuehren() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public boolean istBereit() {
        try {
            return plugin.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private static OnlineSpieler von(Player spieler) {
        if (spieler == null) {
            return null;
        }
        try {
            UUID uuid = spieler.getUniqueId();
            String name = spieler.getName();
            if (uuid == null || name == null || name.isBlank()) {
                return null;
            }
            String ip = "unbekannt";
            try {
                java.net.InetSocketAddress adresse = spieler.getAddress();
                if (adresse != null && adresse.getAddress() != null) {
                    String roh = adresse.getAddress().getHostAddress();
                    if (roh != null && !roh.isBlank()) {
                        ip = roh;
                    }
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(BukkitSpielerVerzeichnis.class).debug("Spieler-IP konnte nicht gelesen werden.", e);
            }
            boolean op;
            try {
                op = spieler.isOp();
            } catch (Exception e) {
                op = false;
            }
            return new OnlineSpieler(uuid, name, ip, op);
        } catch (Exception e) {
            throw new IllegalStateException("Spielerprofil konnte nicht gelesen werden.", e);
        }
    }

}
