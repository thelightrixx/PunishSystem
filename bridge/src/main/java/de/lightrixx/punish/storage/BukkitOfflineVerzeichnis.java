package de.lightrixx.punish.storage;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BukkitOfflineVerzeichnis implements OfflineVerzeichnis {

    @Override
    public Optional<ExternSpieler> findeNachName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String gesucht = name.trim();
        OfflinePlayer offline;
        try {
            offline = Bukkit.getOfflinePlayer(gesucht);
        } catch (Exception e) {
            throw new IllegalStateException("Offline-Spieler-Lookup für '" + gesucht + "' fehlgeschlagen.", e);
        }
        return von(offline, gesucht);
    }

    @Override
    public Optional<ExternSpieler> findeNachUuid(UUID uuid) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        OfflinePlayer offline;
        try {
            offline = Bukkit.getOfflinePlayer(uuid);
        } catch (Exception e) {
            throw new IllegalStateException("Offline-Spieler-Lookup für " + uuid + " fehlgeschlagen.", e);
        }
        return von(offline, null);
    }

    @Override
    public boolean istMainThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Exception e) {
            return true;
        }
    }

    @SuppressWarnings("null")
    private static Optional<ExternSpieler> von(OfflinePlayer offline, String fallbackName) {
        if (offline == null) {
            return Optional.empty();
        }
        try {
            UUID uuid = offline.getUniqueId();
            String name = offline.getName();
            if (name == null || name.isBlank()) {
                name = fallbackName;
            }
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            boolean hatGespielt;
            boolean online;
            long zuletztGesehen;
            try {
                hatGespielt = offline.hasPlayedBefore();
            } catch (Exception e) {
                throw new IllegalStateException("Offline-Profil konnte nicht bewertet werden.", e);
            }
            try {
                online = offline.isOnline();
            } catch (Exception e) {
                online = false;
            }
            try {
                long lastSeen = offline.getLastSeen();
                zuletztGesehen = lastSeen > 0 ? lastSeen : System.currentTimeMillis();
            } catch (Exception e) {
                zuletztGesehen = System.currentTimeMillis();
            }
            boolean onlineModus;
            try {
                onlineModus = Bukkit.getOnlineMode();
            } catch (Exception e) {
                onlineModus = false;
            }
            boolean op;
            try {
                op = offline.isOp();
            } catch (Exception e) {
                op = false;
            }
            return Optional.of(new ExternSpieler(uuid, name.trim(), hatGespielt, online,
                    zuletztGesehen, onlineModus, op));
        } catch (Exception e) {
            throw new IllegalStateException("Offline-Spielerprofil konnte nicht gelesen werden.", e);
        }
    }
}
