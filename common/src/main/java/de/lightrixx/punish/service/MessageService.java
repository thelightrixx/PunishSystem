package de.lightrixx.punish.service;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MessageService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(MessageService.class);

    private final Konfiguration configManager;
    private final MiniMessage miniMessage;

    public MessageService(Konfiguration configManager) {
        this.configManager = Objects.requireNonNull(configManager, "Der ConfigManager darf nicht null sein.");
        this.miniMessage = MiniMessageUtil.miniMessage();
    }

    public void senden(BefehlsSender empfaenger, String schluessel) {
        senden(empfaenger, schluessel, Map.of());
    }

    public void senden(BefehlsSender empfaenger, String schluessel, Map<String, String> platzhalter) {
        senden(empfaenger, schluessel, platzhalter, Set.of());
    }

    public void senden(BefehlsSender empfaenger, String schluessel, Map<String, String> platzhalter,
                       Set<String> rohSchluessel) {
        Objects.requireNonNull(empfaenger, "Der Empfänger darf nicht null sein.");
        sendeKomponente(empfaenger, komponente(schluessel, platzhalter, rohSchluessel));
    }

    public void sendenOhnePraefix(BefehlsSender empfaenger, String schluessel, Map<String, String> platzhalter) {
        Objects.requireNonNull(empfaenger, "Der Empfänger darf nicht null sein.");
        sendeKomponente(empfaenger, komponenteOhnePraefix(schluessel, platzhalter));
    }

    public void sendeKomponente(BefehlsSender empfaenger, Component nachricht) {
        Objects.requireNonNull(empfaenger, "Der Empfänger darf nicht null sein.");
        Objects.requireNonNull(nachricht, "Die Nachricht darf nicht null sein.");
        if (ChatAbstand.brauchtFuehrendeLeerzeile(empfaenger)) {
            empfaenger.nachricht(Component.empty());
        }
        empfaenger.nachricht(nachricht);
        empfaenger.nachricht(Component.empty());
        ChatAbstand.markiere(empfaenger);
    }

    public void sendeBlock(BefehlsSender empfaenger, List<Component> zeilen) {
        Objects.requireNonNull(empfaenger, "Der Empfänger darf nicht null sein.");
        Objects.requireNonNull(zeilen, "Die Zeilen dürfen nicht null sein.");
        java.util.ArrayList<Component> gefiltert = new java.util.ArrayList<>(zeilen.size());
        for (Component zeile : zeilen) {
            if (zeile != null) {
                gefiltert.add(zeile);
            }
        }
        if (gefiltert.isEmpty()) {
            return;
        }
        if (ChatAbstand.brauchtFuehrendeLeerzeile(empfaenger)) {
            empfaenger.nachricht(Component.empty());
        }
        for (Component zeile : gefiltert) {
            empfaenger.nachricht(zeile);
        }
        empfaenger.nachricht(Component.empty());
        ChatAbstand.markiere(empfaenger);
    }

    public void anSpieler(UUID zielUuid, String schluessel, Map<String, String> platzhalter,
                          SpielerVerzeichnis verzeichnis) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
        if (verzeichnis.onlineSpieler(zielUuid).isEmpty()) {
            return;
        }
        Component nachricht = komponente(schluessel, platzhalter);
        String abstand = "player:" + zielUuid;
        if (ChatAbstand.brauchtFuehrendeLeerzeile(abstand)) {
            verzeichnis.mitteilen(zielUuid, Component.empty());
        }
        verzeichnis.mitteilen(zielUuid, nachricht);
        verzeichnis.mitteilen(zielUuid, Component.empty());
        ChatAbstand.markiere(abstand);
    }

    public Component komponente(String schluessel, Map<String, String> platzhalter) {
        return komponente(schluessel, platzhalter, Set.of());
    }

    public Component komponente(String schluessel, Map<String, String> platzhalter,
                                Set<String> rohSchluessel) {
        Component praefix;
        try {
            praefix = miniMessage.deserialize(configManager.praefix());
        } catch (Exception e) {
            LOG.warn("Prefix konnte nicht als MiniMessage geparst werden (nutze Plain-Text).", e);
            praefix = Component.text("[Punish]");
        }
        Component inhalt = komponenteOhnePraefix(schluessel, platzhalter, rohSchluessel);
        return praefix.append(Component.space()).append(inhalt);
    }

    public Component komponenteOhnePraefix(String schluessel, Map<String, String> platzhalter) {
        return komponenteOhnePraefix(schluessel, platzhalter, Set.of());
    }

    public Component komponenteOhnePraefix(String schluessel, Map<String, String> platzhalter,
                                           Set<String> rohSchluessel) {
        String vorlage = configManager.nachricht(schluessel);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter, rohSchluessel);
        try {
            return miniMessage.deserialize(ersetzt);
        } catch (Exception e) {
            LOG.warn("Nachricht '{}' konnte nicht als MiniMessage geparst werden (nutze Plain-Text).",
                    schluessel, e);
            return Component.text(ersetzt);
        }
    }
}
