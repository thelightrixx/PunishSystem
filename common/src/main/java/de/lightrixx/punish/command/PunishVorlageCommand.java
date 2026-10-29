package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PunishVorlageCommand extends PunishCommand {

    private final Map<String, PunishCommand> zielBefehle;

    public PunishVorlageCommand(MessageService messageService,
                                Konfiguration configManager,
                                ProtectionService protectionService,
                                PlayerCache playerCache,
                                AuftragsPlaner taskScheduler,
                                SpielerVerzeichnis verzeichnis,
                                Map<String, PunishCommand> zielBefehle) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler, verzeichnis);
        this.zielBefehle = Map.copyOf(Objects.requireNonNull(zielBefehle, "Die Ziel-Befehle dürfen nicht null sein."));
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_TEMPLATES.node();
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        String[] sauber = ohneScopeFlags(args);
        if (sauber.length < 2) {
            sendeVorlagenListe(sender);
            return;
        }
        String name = sauber[1].trim();
        Konfiguration.Vorlage vorlage = vorlageNachName(name);
        if (vorlage == null) {
            Map<String, String> platzhalter = new LinkedHashMap<>();
            platzhalter.put("vorlage", name);
            messageService.senden(sender, MessageKeys.PUNISH_VORLAGE_UNBEKANNT, platzhalter);
            sendeVorlagenListe(sender);
            return;
        }
        String ziel = normiereBefehl(vorlage.befehl());
        PunishCommand zielBefehl = zielBefehle.get(ziel);
        if (zielBefehl == null) {
            Map<String, String> platzhalter = new LinkedHashMap<>();
            platzhalter.put("vorlage", vorlage.grund());
            platzhalter.put("befehl", ziel);
            messageService.senden(sender, MessageKeys.PUNISH_VORLAGE_BEFEHL_UNTERSTUETZT, platzhalter);
            return;
        }
        boolean mitDauer = brauchtDauer(ziel);
        if (mitDauer && (vorlage.dauer() == null || vorlage.dauer().isBlank())) {
            messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
            return;
        }
        String[] zielArgs = baueZielArgs(args, vorlage, mitDauer);
        if (!zielBefehl.hatRecht(sender, zielArgs)) {
            messageService.senden(sender, MessageKeys.KEIN_RECHT);
            return;
        }
        zielBefehl.ausfuehren(sender, zielArgs);
    }

    private String[] baueZielArgs(String[] args, Konfiguration.Vorlage vorlage, boolean mitDauer) {
        List<String> ziel = new ArrayList<>();
        ziel.add(args[0]);
        if (mitDauer) {
            ziel.add(vorlage.dauer().trim());
        }
        ziel.add(vorlage.grund());
        for (int i = 2; i < args.length; i++) {
            if (args[i] != null && !args[i].isBlank()) {
                ziel.add(args[i].trim());
            }
        }
        return ziel.toArray(new String[0]);
    }

    private void sendeVorlagenListe(BefehlsSender sender) {
        List<Component> zeilen = new ArrayList<>();
        zeilen.add(messageService.komponente(MessageKeys.PUNISH_VORLAGE_KOPF, Map.of()));
        int sichtbar = 0;
        for (Konfiguration.Vorlage vorlage : configManager.vorlagen()) {
            if (!darfVorlageSehen(sender, vorlage)) {
                continue;
            }
            Map<String, String> platzhalter = new LinkedHashMap<>();
            platzhalter.put("grund", vorlage.grund());
            platzhalter.put("befehl", vorlage.befehl());
            platzhalter.put("dauer", brauchtDauer(normiereBefehl(vorlage.befehl()))
                    ? String.valueOf(vorlage.dauer()) : "");
            zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_VORLAGE_EINTRAG, platzhalter));
            sichtbar++;
        }
        if (sichtbar == 0) {
            messageService.senden(sender, MessageKeys.KEIN_RECHT);
            return;
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private Konfiguration.Vorlage vorlageNachName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String gesucht = name.trim().toLowerCase(Locale.ROOT);
        for (Konfiguration.Vorlage vorlage : configManager.vorlagen()) {
            if (vorlage.grund() != null && vorlage.grund().trim().toLowerCase(Locale.ROOT).equals(gesucht)) {
                return vorlage;
            }
        }
        return null;
    }

    private boolean darfVorlageSehen(BefehlsSender sender, Konfiguration.Vorlage vorlage) {
        String ziel = normiereBefehl(vorlage.befehl());
        return zielBefehle.containsKey(ziel)
                && (!brauchtDauer(ziel)
                        || (vorlage.dauer() != null && !vorlage.dauer().isBlank()))
                && sender != null && sender.hatRecht(zielRecht(ziel));
    }

    private static String normiereBefehl(String befehl) {
        return befehl == null ? "" : befehl.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean brauchtDauer(String befehl) {
        return switch (befehl) {
            case "ban", "ban-ip", "mute", "mute-ip", "timeout", "timeout-ip", "freeze" -> true;
            default -> false;
        };
    }

    private static String zielRecht(String befehl) {
        return switch (befehl) {
            case "ban" -> Permissions.PUNISH_BAN.node();
            case "ban-ip" -> Permissions.PUNISH_BANIP.node();
            case "permabann" -> Permissions.PUNISH_PERMABANN.node();
            case "mute" -> Permissions.PUNISH_MUTE.node();
            case "mute-ip" -> Permissions.PUNISH_MUTEIP.node();
            case "timeout" -> Permissions.PUNISH_TIMEOUT.node();
            case "timeout-ip" -> Permissions.PUNISH_TIMEOUTIP.node();
            case "freeze" -> Permissions.PUNISH_FREEZE.node();
            case "kick" -> Permissions.PUNISH_KICK.node();
            case "kick-ip" -> Permissions.PUNISH_KICKIP.node();
            case "warn" -> Permissions.PUNISH_WARN.node();
            default -> Permissions.PUNISH_TEMPLATES.node();
        };
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args[args.length - 1];
        if (letztes != null && letztes.startsWith("--")) {
            return scopeVorschlaege(letztes);
        }
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        if (args.length == 1) {
            return spielerVorschlaegeSmart(sender, args[0]);
        }
        if (args.length == 2) {
            return filtere(vorlagenNamenFuer(sender), args[1]);
        }
        return nurEnde(sender, letztes);
    }

    private List<String> vorlagenNamenFuer(BefehlsSender sender) {
        List<String> namen = new ArrayList<>();
        for (Konfiguration.Vorlage vorlage : configManager.vorlagen()) {
            if (vorlage.grund() != null && !vorlage.grund().isBlank()
                    && !vorlage.grund().contains(" ")
                    && darfVorlageSehen(sender, vorlage)) {
                namen.add(vorlage.grund());
            }
        }
        return namen;
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        int position = args == null ? 0 : args.length;
        if (position == 2 && vorschlag != null) {
            Konfiguration.Vorlage vorlage = vorlageNachName(vorschlag);
            if (vorlage != null) {
                String ziel = normiereBefehl(vorlage.befehl());
                return brauchtDauer(ziel)
                        ? ziel + " " + String.valueOf(vorlage.dauer())
                        : ziel;
            }
        }
        return null;
    }
}
