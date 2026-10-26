package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class BanKickHelper {

    private static final Logger LOG = LoggerFactory.getLogger(BanKickHelper.class);

    private final AuftragsPlaner taskScheduler;
    private final Konfiguration configManager;
    private final ProtectionService protectionService;
    private final SpielerVerzeichnis verzeichnis;
    private final MiniMessage miniMessage = MiniMessageUtil.miniMessage();

    public BanKickHelper(AuftragsPlaner taskScheduler,
                         Konfiguration configManager,
                         ProtectionService protectionService,
                         SpielerVerzeichnis verzeichnis) {
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "Der AuftragsPlaner darf nicht null sein.");
        this.configManager = Objects.requireNonNull(configManager, "Die Konfiguration darf nicht null sein.");
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    public void spielerKicken(UUID zielUuid, String screenTyp, Map<String, String> platzhalter) {
        if (!verzeichnis.darfDirektAusfuehren()) {
            UUID copy = zielUuid;
            Map<String, String> kopie = platzhalter == null ? Map.of() : new HashMap<>(platzhalter);
            try {
                taskScheduler.aufgabe(() -> spielerKicken(copy, screenTyp, kopie));
            } catch (IllegalStateException e) {
                LOG.debug("Kick nach Shutdown verworfen für {}", copy);
            }
            return;
        }
        if (verzeichnis.onlineSpieler(zielUuid).isEmpty()) {
            return;
        }
        try {
            Component screen = baueScreen(screenTyp, platzhalter);
            verzeichnis.trennen(zielUuid, screen);
        } catch (Exception e) {
            LOG.warn("Kick für {} fehlgeschlagen (Strafe bleibt aktiv).", zielUuid, e);
        }
    }

    public void onlineSpielerMitIpKicken(String ip, String grund, String ausfuehrer) {
        onlineSpielerMitIpKicken(ip, grund, ausfuehrer, null, null);
    }

    public void onlineSpielerMitIpKicken(String ip, String grund, String ausfuehrer,
                                         Long dauerMillis, Long ablaufAm) {
        onlineSpielerMitIpKicken(ip, grund, ausfuehrer, dauerMillis, ablaufAm, null);
    }

    public void onlineSpielerMitIpKicken(String ip, String grund, String ausfuehrer,
                                         ProtectionService.IgnoreTyp typ) {
        onlineSpielerMitIpKicken(ip, grund, ausfuehrer, null, null, typ);
    }

    public void onlineSpielerMitIpKicken(String ip, String grund, String ausfuehrer,
                                         Long dauerMillis, Long ablaufAm,
                                         ProtectionService.IgnoreTyp typ) {
        onlineSpielerMitIpKickenMitScreen(ip, grund, ausfuehrer, dauerMillis, ablaufAm,
                de.lightrixx.punish.core.MessageKeys.SCREEN_IP_BANN, typ);
    }

    public void onlineSpielerMitIpKickenMitScreen(String ip, String grund, String ausfuehrer,
                                                  Long dauerMillis, Long ablaufAm, String screenTyp) {
        onlineSpielerMitIpKickenMitScreen(ip, grund, ausfuehrer, dauerMillis, ablaufAm, screenTyp, null);
    }

    public void onlineSpielerMitIpKickenMitScreen(String ip, String grund, String ausfuehrer,
                                                  Long dauerMillis, Long ablaufAm, String screenTyp,
                                                  ProtectionService.IgnoreTyp typ) {
        if (!verzeichnis.darfDirektAusfuehren()) {
            Long dauerKopie = dauerMillis;
            Long ablaufKopie = ablaufAm;
            try {
                taskScheduler.aufgabe(() -> onlineSpielerMitIpKickenMitScreen(
                        ip, grund, ausfuehrer, dauerKopie, ablaufKopie, screenTyp, typ));
            } catch (IllegalStateException e) {
                LOG.debug("IP-Kick nach Shutdown verworfen für {}", ip);
            }
            return;
        }
        String kanonischeIp;
        try {
            kanonischeIp = IpBanRecord.kanonisieren(ip);
        } catch (IllegalArgumentException e) {
            return;
        }

        String dauerText = dauerMillis != null
                ? de.lightrixx.punish.util.ZeitFormat.formatRestzeit(dauerMillis)
                : "Permanent";
        String ablaufText = ablaufAm != null
                ? de.lightrixx.punish.util.ZeitFormat.formatZeitpunkt(ablaufAm)
                : "Permanent";
        Map<String, String> platzhalter = platzhalter(
                "", ausfuehrer, grund, dauerText, dauerText, ablaufText, kanonischeIp);
        Component screen = baueScreen(screenTyp, platzhalter);

        for (OnlineSpieler spieler : verzeichnis.onlineSpieler()) {
            if (spieler == null) {
                continue;
            }
            try {
                boolean geschuetzt = typ == null
                        ? protectionService.istOnlineGeschuetzt(spieler)
                        : protectionService.istOnlineGeschuetzt(spieler, typ);
                if (geschuetzt) {
                    continue;
                }
            } catch (Exception e) {
                LOG.warn("Schutzcheck für IP-Kick-Ziel {} fehlgeschlagen, überspringe.", spieler.name(), e);
                continue;
            }
            String spielerIp = spieler.ip();
            if (spielerIp == null || spielerIp.isBlank() || "unbekannt".equalsIgnoreCase(spielerIp.trim())) {
                continue;
            }
            String kanonisiert;
            try {
                kanonisiert = IpBanRecord.kanonisieren(spielerIp.trim());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (kanonisiert.equals(kanonischeIp)) {
                try {
                    verzeichnis.trennen(spieler.uuid(), screen);
                } catch (Exception e) {
                    LOG.warn("IP-Kick für {} fehlgeschlagen, fahre mit Rest fort.", spieler.name(), e);
                }
            }
        }
    }

    public Component baueScreen(String screenTyp, Map<String, String> platzhalter) {
        String vorlage = configManager.screen(screenTyp);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            return miniMessage.deserialize(ersetzt);
        } catch (Exception e) {
            LOG.warn("Ungültiges MiniMessage-Format im Screen '{}', nutze Plain-Text-Fallback.", screenTyp, e);
            return Component.text(ersetzt);
        }
    }

    public static Map<String, String> platzhalter(String spieler, String ausfuehrer, String grund,
                                                  String dauer, String verbleibend, String ablauf, String ip) {
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", spieler);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        platzhalter.put("dauer", dauer);
        platzhalter.put("verbleibend", verbleibend);
        platzhalter.put("ablauf", ablauf);
        if (ip != null) {
            platzhalter.put("ip", ip);
        }
        return platzhalter;
    }
}
