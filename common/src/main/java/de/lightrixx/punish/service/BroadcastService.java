package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.VerpassteNachricht;
import de.lightrixx.punish.storage.VerpassteBroadcastsRepository;
import de.lightrixx.punish.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class BroadcastService {

    private static final Logger LOG = LoggerFactory.getLogger(BroadcastService.class);

    private final Konfiguration configManager;
    private final BooleanSupplier aktiv;
    private final AuftragsPlaner taskScheduler;
    private final SpielerVerzeichnis verzeichnis;
    private final MiniMessage miniMessage = MiniMessageUtil.miniMessage();
    private volatile VerpassteBroadcastsRepository verpasstAblage;

    public BroadcastService(Konfiguration configManager,
                            BooleanSupplier aktiv,
                            AuftragsPlaner taskScheduler,
                            SpielerVerzeichnis verzeichnis) {
        this.configManager = Objects.requireNonNull(configManager, "Die Konfiguration darf nicht null sein.");
        this.aktiv = Objects.requireNonNull(aktiv, "Der Aktiv-Schalter darf nicht null sein.");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "Der AuftragsPlaner darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    public void mitVerpasstAblage(VerpassteBroadcastsRepository ablage) {
        this.verpasstAblage = ablage;
    }

    public void senden(String typ, Map<String, String> platzhalter) {
        senden(typ, platzhalter, false);
    }

    public void senden(String typ, Map<String, String> platzhalter, boolean silent) {
        Objects.requireNonNull(typ, "Der Broadcast-Typ darf nicht null sein.");

        if (!aktiv.getAsBoolean()) {
            return;
        }
        if (!configManager.broadcastAktiv(typ)) {
            return;
        }

        String vorlage = configManager.broadcastNachricht(typ);
        if (vorlage.isEmpty()) {

            LOG.warn("Kein Broadcast-Text für Typ '{}' (unbekannt oder leer) – Broadcast übersprungen.", typ);
            return;
        }

        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        final Component nachricht = mitPraefix(deserialisierenMitFallback(typ, ersetzt));

        if (silent) {
            LOG.info("[Broadcast:{} (silent)] {}", typ, sanierenFuerLog(ersetzt));
            return;
        }
        LOG.info("[Broadcast:{}] {}", typ, sanierenFuerLog(ersetzt));
        ablegenFuerOffline(typ, ersetzt);

        if (verzeichnis.darfDirektAusfuehren()) {
            sendenAnTeam(nachricht);
        } else {
            try {
                taskScheduler.aufgabe(() -> sendenAnTeam(nachricht));
            } catch (IllegalStateException e) {
                LOG.debug("Broadcast konnte nicht eingeplant werden; Plugin wird deaktiviert.");
            }
        }
    }

    private Component deserialisierenMitFallback(String typ, String ersetzt) {
        try {
            return miniMessage.deserialize(ersetzt);
        } catch (Exception e) {

            LOG.warn("Ungültiges MiniMessage-Format im Broadcast '{}', nutze Plain-Text-Fallback.", typ, e);
            return Component.text(ersetzt);
        }
    }

    private Component mitPraefix(Component inhalt) {
        Component praefix;
        try {
            praefix = miniMessage.deserialize(configManager.praefix());
        } catch (Exception e) {
            praefix = Component.text("[PunishSystem]");
        }
        return praefix.append(Component.space()).append(inhalt);
    }

    private static String sanierenFuerLog(String wert) {
        if (wert == null) {
            return "";
        }
        StringBuilder bereinigt = new StringBuilder(Math.min(wert.length(), 512));
        for (int i = 0; i < wert.length() && bereinigt.length() < 500; i++) {
            char c = wert.charAt(i);
            if (c == '\r' || c == '\n' || c == 0x1B) {
                bereinigt.append(' ');
            } else if (c < 0x20 || c == 0x7F) {
                continue;
            } else {
                bereinigt.append(c);
            }
        }
        if (wert.length() > bereinigt.length()) {
            bereinigt.append("…");
        }
        return bereinigt.toString();
    }

    private void sendenAnTeam(Component nachricht) {
        if (!aktiv.getAsBoolean()) {
            return;
        }
        Component leerzeile = Component.empty();
        for (OnlineSpieler spieler : verzeichnis.onlineSpieler()) {
            if (spieler == null) {
                continue;
            }
            if (verzeichnis.hatRecht(spieler.uuid(), Permissions.BROADCAST_RECEIVE_ALL.node())) {
                if (ChatAbstand.brauchtFuehrendeLeerzeile(spieler)) {
                    verzeichnis.mitteilen(spieler.uuid(), leerzeile);
                }
                verzeichnis.mitteilen(spieler.uuid(), nachricht);
                verzeichnis.mitteilen(spieler.uuid(), leerzeile);
                ChatAbstand.markiere(spieler);
            }
        }
    }

    private void ablegenFuerOffline(String typ, String ersetzt) {
        VerpassteBroadcastsRepository ablage = verpasstAblage;
        if (ablage == null) {
            return;
        }
        try {
            if (!configManager.verpassteBroadcastsAktiv()) {
                return;
            }
        } catch (Exception e) {
            LOG.debug("Verpasst-Aktiv-Lookup fehlgeschlagen, lege trotzdem ab.", e);
        }
        try {
            ablage.ablegen(typ, ersetzt == null ? "" : ersetzt);
        } catch (Exception e) {
            LOG.warn("Verpasster Broadcast '{}' konnte nicht abgelegt werden.", typ, e);
        }
    }

    public void liefereVerpassteNach(UUID uuid, List<VerpassteNachricht> eintraege) {
        Objects.requireNonNull(uuid, "Die Spieler-UUID darf nicht null sein.");
        Objects.requireNonNull(eintraege, "Die Einträge dürfen nicht null sein.");
        if (eintraege.isEmpty() || !aktiv.getAsBoolean()) {
            return;
        }
        boolean nachsenden;
        try {
            nachsenden = configManager.verpassteBroadcastsAktiv();
        } catch (Exception e) {
            LOG.debug("Verpasst-Aktiv-Lookup (Nachsendung) fehlgeschlagen, breche ab.", e);
            return;
        }
        if (!nachsenden) {
            return;
        }
        var ziel = verzeichnis.onlineSpieler(uuid);
        if (ziel.isEmpty()) {
            return;
        }
        if (!verzeichnis.hatRecht(uuid, Permissions.BROADCAST_RECEIVE_ALL.node())) {
            return;
        }
        Component marker;
        try {
            marker = miniMessage.deserialize(configManager.verpasstePrefix());
        } catch (Exception e) {
            LOG.debug("Ungültiges verpasst.prefix, nutze Plain-Text-Fallback.", e);
            marker = Component.text("[Verpasst] ");
        }
        String kopfErsetzt = MiniMessageUtil.ersetzen(configManager.verpassteKopf(),
                Map.of("anzahl", String.valueOf(eintraege.size())));
        Component kopf = mitPraefix(deserialisierenMitFallback("verpasst-kopf", kopfErsetzt));
        Component leerzeile = Component.empty();
        if (ChatAbstand.brauchtFuehrendeLeerzeile(ziel.get())) {
            verzeichnis.mitteilen(uuid, leerzeile);
        }
        verzeichnis.mitteilen(uuid, kopf);
        for (VerpassteNachricht eintrag : eintraege) {
            if (eintrag == null) {
                continue;
            }
            Component original =
                    deserialisierenMitFallback("verpasst:" + eintrag.typ(), eintrag.text());
            verzeichnis.mitteilen(uuid, mitPraefix(marker.append(original)));
        }
        verzeichnis.mitteilen(uuid, leerzeile);
        ChatAbstand.markiere(ziel.get());
    }
}
