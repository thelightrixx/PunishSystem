package de.lightrixx.punish.model;

import java.util.Objects;
import java.util.UUID;

public record CachedPlayer(
        UUID uuid,
        String name,
        String letzteIp,
        long zuletztGesehen
) {

    public CachedPlayer {
        Objects.requireNonNull(uuid, "Die Spieler-UUID darf nicht null sein.");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Der Spielername darf nicht leer sein.");
        }
        if (letzteIp == null || letzteIp.isBlank()) {
            throw new IllegalArgumentException("Die letzte bekannte IP darf nicht leer sein.");
        }
        if (zuletztGesehen <= 0) {
            throw new IllegalArgumentException("Der Zeitstempel 'zuletzt gesehen' muss positiv sein.");
        }
    }

    public CachedPlayer mitName(String neuerName) {
        return new CachedPlayer(uuid, neuerName, letzteIp, zuletztGesehen);
    }

    public CachedPlayer mitZuletztGesehen(long neuerZeitstempel) {
        return new CachedPlayer(uuid, name, letzteIp, neuerZeitstempel);
    }
}
