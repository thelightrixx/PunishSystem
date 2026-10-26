package de.lightrixx.punish.service;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class KickService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KickService.class);

    private final Konfiguration configManager;
    private final HistoryService historyService;
    private final BroadcastService broadcastService;
    private final LogWriter logWriter;
    private final ProtectionService protectionService;

    private final de.lightrixx.punish.core.AuftragsPlaner taskScheduler;
    private final SpielerVerzeichnis verzeichnis;
    private final MiniMessage miniMessage = MiniMessageUtil.miniMessage();

    public KickService(Konfiguration configManager,
                       HistoryService historyService,
                       BroadcastService broadcastService,
                       LogWriter logWriter,
                       ProtectionService protectionService,
                       SpielerVerzeichnis verzeichnis) {
        this(configManager, historyService, broadcastService, logWriter, protectionService, null, verzeichnis);
    }

    public KickService(Konfiguration configManager,
                       HistoryService historyService,
                       BroadcastService broadcastService,
                       LogWriter logWriter,
                       ProtectionService protectionService,
                       de.lightrixx.punish.core.AuftragsPlaner taskScheduler,
                       SpielerVerzeichnis verzeichnis) {
        this.configManager = Objects.requireNonNull(configManager, "Der ConfigManager darf nicht null sein.");
        this.historyService = Objects.requireNonNull(historyService, "Der HistoryService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
        this.logWriter = Objects.requireNonNull(logWriter, "Der LogWriter darf nicht null sein.");
        this.protectionService = Objects.requireNonNull(protectionService, "Der ProtectionService darf nicht null sein.");
        this.taskScheduler = taskScheduler;
        this.verzeichnis = Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    public boolean kickenSpieler(UUID zielUuid, String zielName, String grund, String ausfuehrer) {
        return kickenSpieler(zielUuid, zielName, grund, ausfuehrer, false);
    }

    public boolean kickenSpieler(UUID zielUuid, String zielName, String grund, String ausfuehrer,
                                 boolean silent) {
        if (!verzeichnis.darfDirektAusfuehren()) {
            if (taskScheduler != null && !taskScheduler.istGeschlossen()) {
                try {
                    taskScheduler.aufgabe(() -> kickenSpieler(zielUuid, zielName, grund, ausfuehrer, silent));
                } catch (IllegalStateException e) {
                    LOG.warn("Kick für {} konnte nach Shutdown nicht geplant werden.", zielUuid);
                }
                return false;
            }
            throw new IllegalStateException("kickenSpieler muss auf dem Primary-Thread aufgerufen werden.");
        }
        OnlineSpieler online = verzeichnis.onlineSpieler(zielUuid).orElse(null);
        if (online == null) {
            return false;
        }

        try {
            if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.KICK)) {
                LOG.warn("kickenSpieler für {} abgewiesen: Ziel ist geschützt.", zielUuid);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Schutzcheck für Kick {} fehlgeschlagen, breche sicherheitshalber ab.", zielUuid, e);
            return false;
        }
        String effektiverName = zielName != null ? zielName : online.name();
        String effektiveGrund = grund != null && !grund.isBlank()
                ? grund : configManager.standardGrund(MessageKeys.DEFAULT_KICK_GRUND);
        String effektiverAusfuehrer = ausfuehrer != null && !ausfuehrer.isBlank() ? ausfuehrer : "Konsole";

        Map<String, String> platzhalter = platzhalter(effektiverName, effektiverAusfuehrer, effektiveGrund);
        String herkunft = serverStempel();
        de.lightrixx.punish.model.ServerScope scope;
        try {
            scope = configManager.standardScope();
        } catch (Exception e) {
            scope = de.lightrixx.punish.model.ServerScope.unbekannt();
        }
        platzhalter.put("scope", scope.anzeige());
        platzhalter.put("herkunft", herkunft);

        verzeichnis.trennen(online.uuid(), baueKickScreen(platzhalter));

        try {
            historyService.hinzufuegen(HistoryType.KICK, effektiverAusfuehrer,
                    zielUuid, effektiverName, effektiveGrund, null, scope, herkunft);
        } catch (Exception e) {
            LOG.warn(
                    "Kick-History für {} konnte nicht geschrieben werden (Kick bleibt vollzogen).", zielUuid, e);
        }

        broadcastService.senden(MessageKeys.BROADCAST_KICK, platzhalter, silent);
        logWriter.schreiben(HistoryType.KICK, effektiverAusfuehrer, effektiverName,
                "Grund: " + effektiveGrund, scope, herkunft);
        return true;
    }

    private String serverStempel() {
        try {
            return configManager.serverName();
        } catch (Exception e) {
            return de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT;
        }
    }

    public List<OnlineSpieler> kickenIp(String ip, String grund, String ausfuehrer, UUID ausfuehrerUuid) {
        return kickenIp(ip, grund, ausfuehrer, ausfuehrerUuid, false);
    }

    public List<OnlineSpieler> kickenIp(String ip, String grund, String ausfuehrer, UUID ausfuehrerUuid,
                                        boolean silent) {
        if (!verzeichnis.darfDirektAusfuehren()) {
            if (taskScheduler != null && !taskScheduler.istGeschlossen()) {
                try {
                    taskScheduler.aufgabe(() -> kickenIp(ip, grund, ausfuehrer, ausfuehrerUuid, silent));
                } catch (IllegalStateException e) {
                    LOG.warn("IP-Kick für {} konnte nach Shutdown nicht geplant werden.", ip);
                }
                return List.of();
            }
            throw new IllegalStateException("kickenIp muss auf dem Primary-Thread aufgerufen werden.");
        }
        String kanonischeIp;
        try {
            kanonischeIp = de.lightrixx.punish.model.IpBanRecord.kanonisieren(ip);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        String effektiveGrund = grund != null && !grund.isBlank()
                ? grund : configManager.standardGrund(MessageKeys.DEFAULT_KICK_GRUND);
        String effektiverAusfuehrer = ausfuehrer != null && !ausfuehrer.isBlank() ? ausfuehrer : "Konsole";
        String herkunft = serverStempel();
        de.lightrixx.punish.model.ServerScope scope;
        try {
            scope = configManager.standardScope();
        } catch (Exception e) {
            scope = de.lightrixx.punish.model.ServerScope.unbekannt();
        }

        List<OnlineSpieler> zuKicken = new ArrayList<>();
        for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
            try {
                if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                    continue;
                }
                if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.KICK)) {
                    continue;
                }
            } catch (Exception e) {
                LOG.warn("Schutzcheck für IP-Kick-Ziel {} fehlgeschlagen, überspringe.", online.name(), e);
                continue;
            }
            String spielerIp = online.ip();
            if (spielerIp == null || spielerIp.isBlank() || "unbekannt".equalsIgnoreCase(spielerIp.trim())) {
                continue;
            }
            try {
                if (de.lightrixx.punish.model.IpBanRecord.kanonisieren(spielerIp.trim()).equals(kanonischeIp)) {
                    zuKicken.add(online);
                }
            } catch (IllegalArgumentException e) {
                LOG.trace("Ungültige IP übersprungen.", e);
            }
        }
        if (zuKicken.isEmpty()) {
            return List.of();
        }

        List<OnlineSpieler> erfolgreich = new ArrayList<>();
        for (OnlineSpieler online : zuKicken) {
            Map<String, String> platzhalter = new HashMap<>();
            platzhalter.put("spieler", online.name());
            platzhalter.put("ausfuehrer", effektiverAusfuehrer);
            platzhalter.put("grund", effektiveGrund);
            platzhalter.put("ip", kanonischeIp);
            platzhalter.put("scope", scope.anzeige());
            platzhalter.put("herkunft", herkunft);
            try {
                verzeichnis.trennen(online.uuid(), baueKickScreenMitFallback(platzhalter));
            } catch (Exception e) {
                LOG.warn("IP-Kick für {} fehlgeschlagen, fahre mit Rest fort.", online.name(), e);
                continue;
            }
            try {
                historyService.hinzufuegen(HistoryType.IP_KICK, effektiverAusfuehrer,
                        online.uuid(), online.name(),
                        effektiveGrund + " (IP: " + kanonischeIp + ")", null, scope, herkunft);
            } catch (Exception e) {
                LOG.warn("IP_KICK-History für {} fehlgeschlagen (Kick bleibt vollzogen).", online.uuid(), e);
            }
            erfolgreich.add(online);
        }

        if (!erfolgreich.isEmpty()) {
            Map<String, String> broadcastPlatzhalter = new HashMap<>();
            broadcastPlatzhalter.put("spieler", erfolgreich.size() == 1 ? erfolgreich.get(0).name() : kanonischeIp);
            broadcastPlatzhalter.put("ip", kanonischeIp);
            broadcastPlatzhalter.put("ausfuehrer", effektiverAusfuehrer);
            broadcastPlatzhalter.put("grund", effektiveGrund);
            broadcastPlatzhalter.put("anzahl", String.valueOf(erfolgreich.size()));
            broadcastPlatzhalter.put("scope", scope.anzeige());
            broadcastPlatzhalter.put("herkunft", herkunft);
            broadcastService.senden(MessageKeys.BROADCAST_IP_KICK, broadcastPlatzhalter, silent);
            logWriter.schreiben(HistoryType.IP_KICK, effektiverAusfuehrer, kanonischeIp,
                    "Anzahl: " + erfolgreich.size() + ", Grund: " + effektiveGrund, scope, herkunft);
        }
        return List.copyOf(erfolgreich);
    }

    public List<OnlineSpieler> kickenAlle(String grund, String ausfuehrer, UUID ausfuehrerUuid) {
        return kickenAlle(grund, ausfuehrer, ausfuehrerUuid, false);
    }

    public List<OnlineSpieler> kickenAlle(String grund, String ausfuehrer, UUID ausfuehrerUuid, boolean silent) {
        if (!verzeichnis.darfDirektAusfuehren()) {
            if (taskScheduler != null && !taskScheduler.istGeschlossen()) {
                try {
                    taskScheduler.aufgabe(() -> kickenAlle(grund, ausfuehrer, ausfuehrerUuid, silent));
                } catch (IllegalStateException e) {
                    LOG.warn("Kick-All konnte nach Shutdown nicht geplant werden.");
                }
                return List.of();
            }
            throw new IllegalStateException("kickenAlle muss auf dem Primary-Thread aufgerufen werden.");
        }
        String effektiveGrund = grund != null && !grund.isBlank()
                ? grund : configManager.standardGrund(MessageKeys.DEFAULT_KICK_GRUND);
        String effektiverAusfuehrer = ausfuehrer != null && !ausfuehrer.isBlank() ? ausfuehrer : "Konsole";

        List<OnlineSpieler> zuKicken = new ArrayList<>();
        for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
            try {
                if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                    continue;
                }
                if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.KICK)) {
                    continue;
                }
            } catch (Exception e) {
                LOG.warn("Schutzcheck für Kick-All-Ziel {} fehlgeschlagen, überspringe.", online.name(), e);
                continue;
            }
            zuKicken.add(online);
        }

        Map<String, String> basisPlatzhalter = new HashMap<>();
        basisPlatzhalter.put("ausfuehrer", effektiverAusfuehrer);
        basisPlatzhalter.put("grund", effektiveGrund);
        String herkunftAlle = serverStempel();
        de.lightrixx.punish.model.ServerScope scopeAlle;
        try {
            scopeAlle = configManager.standardScope();
        } catch (Exception e) {
            scopeAlle = de.lightrixx.punish.model.ServerScope.unbekannt();
        }
        basisPlatzhalter.put("scope", scopeAlle.anzeige());
        basisPlatzhalter.put("herkunft", herkunftAlle);

        List<OnlineSpieler> erfolgreich = new ArrayList<>();
        for (OnlineSpieler online : zuKicken) {
            Map<String, String> einzelPlatzhalter = new HashMap<>(basisPlatzhalter);
            einzelPlatzhalter.put("spieler", online.name());
            try {
                verzeichnis.trennen(online.uuid(), baueKickScreen(einzelPlatzhalter));
            } catch (Exception e) {
                LOG.warn("Kick für {} fehlgeschlagen, fahre mit Rest fort.", online.name(), e);
                continue;
            }

            try {
                historyService.hinzufuegen(HistoryType.KICK_ALL, effektiverAusfuehrer,
                        online.uuid(), online.name(), effektiveGrund, null,
                        scopeAlle, herkunftAlle);
            } catch (Exception e) {
                LOG.warn("KICK_ALL-History für {} fehlgeschlagen (Kick bleibt vollzogen).", online.uuid(), e);
            }
            erfolgreich.add(online);
        }

        basisPlatzhalter.put("anzahl", String.valueOf(erfolgreich.size()));
        if (!erfolgreich.isEmpty()) {
            broadcastService.senden(MessageKeys.BROADCAST_KICK_ALL, basisPlatzhalter, silent);
        }
        logWriter.schreiben(HistoryType.KICK_ALL, effektiverAusfuehrer, "alle",
                "Anzahl: " + erfolgreich.size() + ", Grund: " + effektiveGrund, scopeAlle, herkunftAlle);
        return List.copyOf(erfolgreich);
    }

    public int zaehleKickAlleKandidaten(UUID ausfuehrerUuid) {
        int anzahl = 0;
        try {
            for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                try {
                    if (ausfuehrerUuid != null && online.uuid().equals(ausfuehrerUuid)) {
                        continue;
                    }
                    if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.KICK)) {
                        continue;
                    }
                } catch (Exception e) {
                    LOG.debug("KickAll-Kandidat '{}' nach Schutz-Check-Fehler übersprungen.", online.name(), e);
                    continue;
                }
                anzahl++;
            }
        } catch (Exception e) {
            LOG.warn("KickAll-Kandidatenzählung fehlgeschlagen (zeige 0).", e);
        }
        return anzahl;
    }

    private Component baueKickScreen(Map<String, String> platzhalter) {
        String vorlage = configManager.screen(MessageKeys.SCREEN_KICK);
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            return miniMessage.deserialize(ersetzt);
        } catch (Exception e) {
            LOG.warn("Ungültiges MiniMessage-Format im Kick-Screen, nutze Plain-Text-Fallback.", e);
            return Component.text(ersetzt);
        }
    }

    private Component baueKickScreenMitFallback(Map<String, String> platzhalter) {
        String vorlage;
        try {
            vorlage = configManager.screen(MessageKeys.SCREEN_IP_KICK);
        } catch (Exception e) {
            vorlage = null;
        }
        if (vorlage == null || vorlage.isBlank()) {
            try {
                vorlage = configManager.screen(MessageKeys.SCREEN_KICK);
            } catch (Exception e) {
                vorlage = "<red>Du wurdest vom Server gekickt!";
            }
        }
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, platzhalter);
        try {
            return miniMessage.deserialize(ersetzt);
        } catch (Exception e) {
            LOG.warn("Ungültiges MiniMessage-Format im IP-Kick-Screen, nutze Plain-Text-Fallback.", e);
            return Component.text(ersetzt);
        }
    }

    private Map<String, String> platzhalter(String spieler, String ausfuehrer, String grund) {
        Map<String, String> platzhalter = new HashMap<>();
        platzhalter.put("spieler", spieler);
        platzhalter.put("ausfuehrer", ausfuehrer);
        platzhalter.put("grund", grund);
        return platzhalter;
    }
}
