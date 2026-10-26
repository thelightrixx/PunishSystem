package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MuteAllCommand extends PunishCommand {

    private static final Logger LOG = LoggerFactory.getLogger(MuteAllCommand.class);
    private static final String BESTAETIGUNGS_ARGUMENT = "confirm";

    private final MuteService muteService;
    private final TimeParser timeParser;

    public MuteAllCommand(MessageService messageService,
                          Konfiguration configManager,
                          ProtectionService protectionService,
                          PlayerCache playerCache,
                          MuteService muteService,
                          TimeParser timeParser,
                          AuftragsPlaner taskScheduler,
                          SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler, verzeichnis);
        this.muteService = Objects.requireNonNull(muteService, "Der MuteService darf nicht null sein.");
        this.timeParser = Objects.requireNonNull(timeParser, "Der TimeParser darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_MUTEALL.node();
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
        if (bereinigt.length < 1) {
            messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
            return;
        }

        ParseResult parseResult = timeParser.parsen(bereinigt[0]);
        long dauerMillis;
        if (parseResult instanceof ParseResult.Geltzeit(long millis)) {
            dauerMillis = millis;
            if (!istAblaufMoeglich(dauerMillis)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long maximalMillis = configManager.muteMaxMinuten() * 60000L;
            if (dauerMillis > maximalMillis) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.muteMaxMinuten())));
                return;
            }
        } else {
            messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
            return;
        }

        int grundStartIndex;
        if (configManager.bestaetigungMuteAll()) {
            if (bereinigt.length < 2 || !bereinigt[1].equalsIgnoreCase(BESTAETIGUNGS_ARGUMENT)) {

                sendeBestaetigung(sender, args);
                return;
            }
            grundStartIndex = 2;
        } else {
            grundStartIndex = 1;
        }

        String grund;
        if (bereinigt.length <= grundStartIndex) {
            grund = configManager.standardGrund(MessageKeys.DEFAULT_MUTE_GRUND);
        } else {
            grund = grundAusArgumentenOhneStandard(bereinigt, grundStartIndex);
            if (grund.isBlank()) {
                grund = configManager.standardGrund(MessageKeys.DEFAULT_MUTE_GRUND);
            }
        }
        UUID ausfuehrerUuid = eigeneUuid(sender).orElse(null);

        de.lightrixx.punish.service.MassenErgebnis ergebnis = muteService.muteAlle(
                dauerMillis,
                grund,
                ausfuehrerName(sender),
                ausfuehrerUuid,
                scope.scope(), herkunftVon(sender), silent);

        int anzahl = ergebnis.anzahl();
        messageService.senden(sender, MessageKeys.BESTAETIGUNG_ERFOLGREICH,
                Map.of("anzahl", String.valueOf(anzahl)));
        if (ergebnis.gekuerzt() > 0) {
            messageService.senden(sender, MessageKeys.MASSEN_LIMIT_GEKUERZT, Map.of(
                    "kandidaten", String.valueOf(anzahl + ergebnis.gekuerzt()),
                    "anzahl", String.valueOf(anzahl),
                    "max", String.valueOf(de.lightrixx.punish.service.Limits.MUTE_ALL_MAX)));
        }
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        String vorlage = tooltipFuerVorlage("mute", vorschlag);
        return vorlage != null && !vorlage.isBlank() ? vorlage : "Grund";
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
        java.util.List<String> gemischt = new java.util.ArrayList<>(grundVorschlaege("mute"));
        gemischt.addAll(scopeVorschlaege("--"));
        gemischt.addAll(silentVorschlag(sender, ""));
        boolean bestaetigungAktiv = configManager.bestaetigungMuteAll();
        if (args.length == 1) {
            return filtere(dauerVorschlaegeFuer("mute"), args[0]);
        }
        if (bestaetigungAktiv) {
            if (args.length == 2) {
                return filtere(List.of(BESTAETIGUNGS_ARGUMENT), args[1]);
            }
            if (args.length == 3) {
                return filtere(gemischt, args[args.length - 1]);
            }
            if (args.length > 3) {
                return nurEnde(sender, args[args.length - 1]);
            }
        } else if (args.length == 2) {
            return filtere(gemischt, args[args.length - 1]);
        } else if (args.length > 2) {
            return nurEnde(sender, args[args.length - 1]);
        }
        return List.of();
    }

    private void sendeBestaetigung(BefehlsSender sender, String[] args) {
        String befehl = baueBestaetigungsBefehl(args);
        int kandidaten = 0;
        try {
            kandidaten = muteService.zaehleMuteAlleKandidaten(eigeneUuid(sender).orElse(null));
        } catch (Exception e) {
            LOG.warn("MuteAll-Kandidatenzählung fehlgeschlagen (zeige 0).", e);
        }
        Component hinweis = messageService.komponente(MessageKeys.BESTAETIGUNG_NOETIG, Map.of());
        Component umfang = Component.text(
                "Betroffen sind " + kandidaten + " Online-Spieler (ohne dich/geschützte).",
                NamedTextColor.YELLOW);
        Component klickbar = Component.text(befehl, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand(befehl));
        messageService.sendeKomponente(sender, hinweis.append(Component.newline()).append(umfang)
                .append(Component.newline()).append(klickbar));
    }

    private String baueBestaetigungsBefehl(String[] args) {
        StringBuilder befehl = new StringBuilder("/mute-all");
        StringBuilder grund = new StringBuilder();
        StringBuilder flags = new StringBuilder();
        boolean dauerGesetzt = false;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (!dauerGesetzt && !arg.equalsIgnoreCase(BESTAETIGUNGS_ARGUMENT)
                    && !ScopeParser.istScopeFlag(arg) && !ScopeParser.istSilentFlag(arg)) {
                befehl.append(" ").append(arg);
                dauerGesetzt = true;
                continue;
            }
            if (arg.equalsIgnoreCase(BESTAETIGUNGS_ARGUMENT)) {
                continue;
            }
            if (ScopeParser.istScopeFlag(arg) || ScopeParser.istSilentFlag(arg)) {
                flags.append(" ").append(arg);
                continue;
            }
            if (grund.length() > 0) {
                grund.append(' ');
            }
            grund.append(arg);
        }
        befehl.append(" ").append(BESTAETIGUNGS_ARGUMENT);
        if (grund.length() > 0) {
            befehl.append(' ').append(grund);
        }
        befehl.append(flags);
        return befehl.toString();
    }
}
