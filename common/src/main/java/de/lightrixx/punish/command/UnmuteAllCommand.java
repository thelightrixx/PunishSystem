package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class UnmuteAllCommand extends PunishCommand {
    private static final String BESTAETIGUNGS_ARGUMENT = "confirm";
    private final MuteService muteService;

    public UnmuteAllCommand(MessageService messageService,
                            Konfiguration configManager,
                            ProtectionService protectionService,
                            PlayerCache playerCache,
                            MuteService muteService,
                            AuftragsPlaner taskScheduler,
                            SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler, verzeichnis);
        this.muteService = Objects.requireNonNull(muteService, "Der MuteService darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_UNMUTEALL.node();
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {

        ScopeParser.Ergebnis scope = scopeAusArgs(args);
        if (blockiereBeiUnbekanntemScope(sender, scope)) {
            return;
        }
        if (blockiereBeiIoRueckstau(sender)) {
            return;
        }
        boolean silent = silentAuswerten(sender, scope);
        String[] bereinigt = scope.bereinigteArgs();
        int grundStartIndex;
        if (configManager.bestaetigungUnmuteAll()) {
            if (bereinigt.length < 1 || !BESTAETIGUNGS_ARGUMENT.equalsIgnoreCase(bereinigt[0])) {

                sendeBestaetigung(sender, args);
                return;
            }
            grundStartIndex = 1;
        } else {
            grundStartIndex = 0;
        }

        String grund = grundAusArgumentenOhneStandard(bereinigt, grundStartIndex);
        if (grund.isBlank()) {
            grund = configManager.standardGrund(MessageKeys.DEFAULT_MUTE_GRUND);
        }
        de.lightrixx.punish.model.ServerScope filter = scope.hatteFlag() ? scope.scope() : null;
        int anzahl = muteService.entmutenAlle(grund, ausfuehrerName(sender), herkunftVon(sender), filter,
                silent);
        messageService.senden(sender, MessageKeys.BESTAETIGUNG_ERFOLGREICH, Map.of("anzahl", String.valueOf(anzahl)));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Grund";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        String letztes = args[args.length - 1];
        if (letztes != null && letztes.startsWith("--")) {
            return scopeVorschlaege(letztes);
        }
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        java.util.List<String> gemischt = new java.util.ArrayList<>(grundVorschlaege());
        gemischt.addAll(scopeVorschlaege("--"));
        gemischt.addAll(silentVorschlag(sender, ""));
        boolean bestaetigungAktiv = configManager.bestaetigungUnmuteAll();
        if (bestaetigungAktiv) {
            if (args.length == 1) {
                return filtere(List.of(BESTAETIGUNGS_ARGUMENT), args[0]);
            }
            if (args.length == 2) {
                return filtere(gemischt, args[args.length - 1]);
            }
            if (args.length > 2) {
                return nurEnde(sender, args[args.length - 1]);
            }
        } else if (args.length == 1) {
            return filtere(gemischt, args[args.length - 1]);
        } else if (args.length > 1) {
            return nurEnde(sender, args[args.length - 1]);
        }
        return List.of();
    }

    private void sendeBestaetigung(BefehlsSender sender, String[] args) {
        String befehl = baueBestaetigungsBefehl(args);
        Component hinweis = messageService.komponente(MessageKeys.BESTAETIGUNG_NOETIG, Map.of());
        Component klickbar = Component.text(befehl, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand(befehl));
        messageService.sendeKomponente(sender, hinweis.append(Component.newline()).append(klickbar));
    }

    private String baueBestaetigungsBefehl(String[] args) {
        StringBuilder befehl = new StringBuilder("/unmute-all ").append(BESTAETIGUNGS_ARGUMENT);

        int start = (args.length > 0 && BESTAETIGUNGS_ARGUMENT.equalsIgnoreCase(args[0])) ? 1 : 0;
        for (int i = start; i < args.length; i++) {
            befehl.append(" ").append(args[i]);
        }
        return befehl.toString();
    }
}
