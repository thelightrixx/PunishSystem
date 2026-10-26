package de.lightrixx.punish.core;

import net.kyori.adventure.text.Component;

import java.util.UUID;

public interface BefehlsSender {

    void nachricht(Component nachricht);

    boolean hatRecht(String knoten);

    boolean istSpieler();

    UUID spielerUuid();

    String name();

    default String abstandsSchluessel() {
        if (istSpieler() && spielerUuid() != null) {
            return "player:" + spielerUuid();
        }
        String n = name();
        return "sender:" + (n == null ? "unbekannt" : n);
    }

    default String herkunft() {
        return null;
    }
}
