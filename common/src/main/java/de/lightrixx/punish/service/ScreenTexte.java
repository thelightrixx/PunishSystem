package de.lightrixx.punish.service;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.util.MiniMessageUtil;
import de.lightrixx.punish.util.ZeitFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class ScreenTexte {

    private static final Logger LOG = LoggerFactory.getLogger(ScreenTexte.class);
    private static final MiniMessage MINI_MESSAGE = MiniMessageUtil.miniMessage();

    private ScreenTexte() {
    }

    private static java.util.List<String> bekannteServer(Konfiguration config) {
        try {
            java.util.List<String> server = config.bekannteServer();
            return server == null ? java.util.List.of() : server;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    public static Component baueBannScreen(BanRecord bann, long jetztMillis, Konfiguration config) {
        Objects.requireNonNull(bann, "Der Bann darf nicht null sein.");
        Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        String screenTyp;
        if (bann.autoBann()) {
            screenTyp = MessageKeys.SCREEN_BANN_AUTO;
        } else if (bann.ablaufAm() == null) {
            screenTyp = MessageKeys.SCREEN_BANN_PERMANENT;
        } else {
            screenTyp = MessageKeys.SCREEN_BANN;
        }

        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", bann.spielerName());
        platzhalter.put("grund", de.lightrixx.punish.command.ScopeParser.saeubereGrund(bann.grund(), bekannteServer(config)));
        platzhalter.put("ausfuehrer", bann.ausfuehrer());
        platzhalter.put("ablauf", bann.ablaufAm() != null
                ? ZeitFormat.formatZeitpunkt(bann.ablaufAm())
                : "Permanent");
        String verbleibendText = bann.ablaufAm() != null
                ? ZeitFormat.formatRestzeit(Math.max(0L, bann.ablaufAm() - jetztMillis))
                : "Permanent";
        platzhalter.put("verbleibend", verbleibendText);
        String dauerText = bann.ablaufAm() != null
                ? ZeitFormat.formatRestzeit(Math.max(0L, bann.ablaufAm() - bann.erstelltAm()))
                : "Permanent";
        platzhalter.put("dauer", dauerText);

        int maxPunkte = config.verstoesseMaxPunkte();
        platzhalter.put("anzahl", String.valueOf(maxPunkte));
        platzhalter.put("punkte", String.valueOf(maxPunkte));
        platzhalter.put("max", String.valueOf(maxPunkte));
        platzhalter.put("scope", bann.scope().anzeige());

        return baueScreen(config, screenTyp, platzhalter);
    }

    public static Component baueIpBannScreen(IpBanRecord ipBann, Konfiguration config) {
        Objects.requireNonNull(ipBann, "Der IP-Bann darf nicht null sein.");
        Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        long jetzt = System.currentTimeMillis();
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("ip", ipBann.ip());
        platzhalter.put("grund", de.lightrixx.punish.command.ScopeParser.saeubereGrund(ipBann.grund(), bekannteServer(config)));
        platzhalter.put("ausfuehrer", ipBann.ausfuehrer());
        if (ipBann.ablaufAm() != null) {
            long rest = Math.max(0L, ipBann.ablaufAm() - jetzt);
            long gesamt = Math.max(0L, ipBann.ablaufAm() - ipBann.erstelltAm());
            platzhalter.put("dauer", ZeitFormat.formatRestzeit(gesamt));
            platzhalter.put("verbleibend", ZeitFormat.formatRestzeit(rest));
            platzhalter.put("ablauf", ZeitFormat.formatZeitpunkt(ipBann.ablaufAm()));
        } else {
            platzhalter.put("dauer", "Permanent");
            platzhalter.put("verbleibend", "Permanent");
            platzhalter.put("ablauf", "Permanent");
        }
        platzhalter.put("scope", ipBann.scope().anzeige());
        return baueScreen(config, MessageKeys.SCREEN_IP_BANN, platzhalter);
    }

    private static Component baueScreen(Konfiguration config, String screenTyp,
                                        Map<String, String> platzhalter) {
        String vorlage = config.screen(screenTyp);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            return MINI_MESSAGE.deserialize(ersetzt);
        } catch (Exception e) {

            LOG.warn("Ungültiges MiniMessage-Format im Screen '{}', nutze Plain-Text-Fallback.", screenTyp, e);
            return Component.text(ersetzt);
        }
    }
}
