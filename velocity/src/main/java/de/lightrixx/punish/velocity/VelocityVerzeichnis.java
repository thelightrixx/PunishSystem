package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class VelocityVerzeichnis implements SpielerVerzeichnis, OfflineVerzeichnis {

    private final ProxyServer proxy;
    private volatile PlayerCache playerCache;

    public VelocityVerzeichnis(ProxyServer proxy) {
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
    }

    public void mitPlayerCache(PlayerCache playerCache) {
        this.playerCache = playerCache;
    }

    @Override
    public List<OnlineSpieler> onlineSpieler() {
        List<OnlineSpieler> ergebnis = new ArrayList<>();
        try {
            for (Player spieler : proxy.getAllPlayers()) {
                OnlineSpieler eintrag = von(spieler);
                if (eintrag != null) {
                    ergebnis.add(eintrag);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("OnlineSpieler-Lookup fehlgeschlagen.", e);
        }
        return List.copyOf(ergebnis);
    }

    @Override
    public Optional<OnlineSpieler> onlineSpieler(UUID uuid) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        try {
            return proxy.getPlayer(uuid).map(VelocityVerzeichnis::von).filter(Objects::nonNull);
        } catch (Exception e) {
            throw new IllegalStateException("OnlineSpieler-Lookup für " + uuid + " fehlgeschlagen.", e);
        }
    }

    @Override
    public Optional<OnlineSpieler> onlineSpielerNachName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return proxy.getPlayer(name.trim()).map(VelocityVerzeichnis::von).filter(Objects::nonNull);
        } catch (Exception e) {
            throw new IllegalStateException("OnlineSpieler-Lookup für '" + name + "' fehlgeschlagen.", e);
        }
    }

    @Override
    public void trennen(UUID uuid, Component grund) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        try {
            proxy.getPlayer(uuid).ifPresent(spieler -> {
                try {
                    spieler.disconnect(grund);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(VelocityVerzeichnis.class).debug("Disconnect senden fehlgeschlagen.", e);
                }
            });
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocityVerzeichnis.class).debug("Trennen-Lookup fehlgeschlagen.", e);
        }
    }

    @Override
    public void mitteilen(UUID uuid, Component nachricht) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        Objects.requireNonNull(nachricht, "Die Nachricht darf nicht null sein.");
        try {
            proxy.getPlayer(uuid).ifPresent(spieler -> {
                try {
                    spieler.sendMessage(nachricht);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(VelocityVerzeichnis.class).debug("Mitteilen senden fehlgeschlagen.", e);
                }
            });
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VelocityVerzeichnis.class).debug("Mitteilen-Lookup fehlgeschlagen.", e);
        }
    }

    @Override
    public boolean hatRecht(UUID uuid, String knoten) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        if (knoten == null || knoten.isBlank()) {
            return false;
        }
        try {
            return proxy.getPlayer(uuid).map(spieler -> {
                try {
                    return RangBuendel.gewaehrt(spieler::hasPermission, knoten);
                } catch (Exception e) {
                    return false;
                }
            }).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean darfDirektAusfuehren() {
        return true;
    }

    @Override
    public boolean istBereit() {
        return true;
    }

    @Override
    public Optional<ExternSpieler> findeNachName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String gesucht = name.trim();
        try {
            Optional<Player> online = proxy.getPlayer(gesucht);
            if (online.isPresent()) {
                ExternSpieler extern = extern(online.get(), true);
                if (extern != null) {
                    return Optional.of(extern);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Online-Lookup für '" + gesucht + "' fehlgeschlagen.", e);
        }
        try {
            PlayerCache cache = this.playerCache;
            if (cache != null) {
                Optional<CachedPlayer> gecacht = cache.findenNachName(gesucht);
                if (gecacht.isPresent()) {
                    return Optional.of(ausCache(gecacht.get()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cache-Lookup für '" + gesucht + "' fehlgeschlagen.", e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ExternSpieler> findeNachUuid(UUID uuid) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        try {
            Optional<Player> online = proxy.getPlayer(uuid);
            if (online.isPresent()) {
                ExternSpieler extern = extern(online.get(), true);
                if (extern != null) {
                    return Optional.of(extern);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Online-Lookup für " + uuid + " fehlgeschlagen.", e);
        }
        try {
            PlayerCache cache = this.playerCache;
            if (cache != null) {
                Optional<CachedPlayer> gecacht = cache.finden(uuid);
                if (gecacht.isPresent()) {
                    return Optional.of(ausCache(gecacht.get()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cache-Lookup für " + uuid + " fehlgeschlagen.", e);
        }
        return Optional.empty();
    }

    @Override
    public boolean istMainThread() {

        return false;
    }

    private static OnlineSpieler von(Player spieler) {
        if (spieler == null) {
            return null;
        }
        try {
            UUID uuid = spieler.getUniqueId();
            String name = spieler.getUsername();
            if (uuid == null || name == null || name.isBlank()) {
                return null;
            }
            String ip = "unbekannt";
            try {
                if (spieler.getRemoteAddress() != null
                        && spieler.getRemoteAddress().getAddress() != null) {
                    String roh = spieler.getRemoteAddress().getAddress().getHostAddress();
                    if (roh != null && !roh.isBlank()) {
                        ip = roh;
                    }
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(VelocityVerzeichnis.class).debug("IP-Lookup fehlgeschlagen.", e);
            }
            boolean op;
            try {
                op = spieler.hasPermission("*");
            } catch (Exception e) {
                op = false;
            }
            return new OnlineSpieler(uuid, name, ip, op);
        } catch (Exception e) {
            return null;
        }
    }

    private ExternSpieler ausCache(CachedPlayer gecacht) {
        Objects.requireNonNull(gecacht, "Der Cache-Eintrag darf nicht null sein.");
        boolean onlineModus;
        try {
            onlineModus = proxy.getConfiguration().isOnlineMode();
        } catch (Exception e) {
            onlineModus = true;
        }
        return new ExternSpieler(gecacht.uuid(), gecacht.name(), true, false,
                gecacht.zuletztGesehen(), onlineModus, false);
    }

    private ExternSpieler extern(Player spieler, boolean hatGespielt) {
        OnlineSpieler o = von(spieler);
        if (o == null) {
            return null;
        }
        boolean onlineModus;
        try {
            onlineModus = proxy.getConfiguration().isOnlineMode();
        } catch (Exception e) {
            onlineModus = true;
        }
        return new ExternSpieler(o.uuid(), o.name(), hatGespielt, true,
                System.currentTimeMillis(), onlineModus, o.op());
    }
}
