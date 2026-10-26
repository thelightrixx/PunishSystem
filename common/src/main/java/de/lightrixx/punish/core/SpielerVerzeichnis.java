package de.lightrixx.punish.core;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpielerVerzeichnis {

    List<OnlineSpieler> onlineSpieler();

    Optional<OnlineSpieler> onlineSpieler(UUID uuid);

    Optional<OnlineSpieler> onlineSpielerNachName(String name);

    void trennen(UUID uuid, Component grund);

    void mitteilen(UUID uuid, Component nachricht);

    boolean hatRecht(UUID uuid, String knoten);

    boolean darfDirektAusfuehren();

    boolean istBereit();
}
