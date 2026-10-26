package de.lightrixx.punish.core;

import java.util.Objects;
import java.util.UUID;

public record OnlineSpieler(
        UUID uuid,
        String name,
        String ip,
        boolean op
) {
    public OnlineSpieler {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Der Name darf nicht leer sein.");
        }
        if (ip == null || ip.isBlank()) {
            ip = "unbekannt";
        }
    }
}
