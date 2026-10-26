package de.lightrixx.punish.command;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class TimeoutCommand extends PunishCommand {

    private final TimeoutService timeoutService;
    private final TimeParser timeParser;

    public TimeoutCommand(MessageService messageService,
                          Konfiguration configManager,
                          ProtectionService protectionService,
                          PlayerCache playerCache,
                          TimeoutService timeoutService,
                          TimeParser timeParser,
                          AuftragsPlaner taskScheduler,
                          SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.timeoutService = Objects.requireNonNull(timeoutService, "Der TimeoutService darf nicht null sein.");
        this.timeParser = Objects.requireNonNull(timeParser, "Der TimeParser darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_TIMEOUT.node();
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {

        ScopeParser.Ergebnis scope = scopeAusArgs(args);
        if (blockiereBeiUnbekanntemScope(sender, scope)) {
            return;
        }
        boolean silent = silentAuswerten(sender, scope);
        String[] bereinigt = scope.bereinigteArgs();
        verarbeitenTimeout(sender, bereinigt, scope.scope(), silent);
    }

    private void verarbeitenTimeout(BefehlsSender sender, String[] args,
                                     de.lightrixx.punish.model.ServerScope scope, boolean silent) {
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", ""));
            return;
        }
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
            return;
        }

        Optional<CachedPlayer> ziel = zielAufloesen(args[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", args[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();

        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.TIMEOUT, () -> {

            ParseResult vorab = timeParser.parsen(args[1]);
            if (vorab instanceof ParseResult.Permanent) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (!(vorab instanceof ParseResult.Geltzeit(long vorabMillis))) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long maximalMillis = configManager.timeoutMaxMinuten() * 60000L;
            if (vorabMillis > maximalMillis) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.timeoutMaxMinuten())));
                return;
            }
            if (!istAblaufMoeglich(vorabMillis)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }

            if (timeoutService.istAbgedeckt(spieler.uuid(), scope)) {
                messageService.senden(sender, MessageKeys.BEREITS_TIMEOUT, Map.of("spieler", spieler.name()));
                return;
            }

            long dauerMillis = vorabMillis;

            String grund = grundAusArgumenten(args, 2, MessageKeys.DEFAULT_TIMEOUT_GRUND);
            boolean erfolg = timeoutService.timeoutVerhaengen(
                    spieler.uuid(),
                    spieler.name(),
                    dauerMillis,
                    grund,
                    ausfuehrerName(sender),
                    scope, herkunftVon(sender), silent);
            if (!erfolg) {
                try {
                    if (schutzFehlerSenden(sender, spieler, protectionService.pruefeSchutz(spieler.uuid(), ProtectionService.IgnoreTyp.TIMEOUT))) {
                        return;
                    }
                } catch (Exception e) {
                    LOG.warn("Schutz-Check für '{}' fehlgeschlagen, werte als UNSICHER (fail-closed).",
                            spieler.name(), e);
                    if (schutzFehlerSenden(sender, spieler,
                            ProtectionService.SchutzErgebnis.UNSICHER)) {
                        return;
                    }
                }
                if (timeoutService.istAbgedeckt(spieler.uuid(), scope)) {
                    messageService.senden(sender, MessageKeys.BEREITS_TIMEOUT, Map.of("spieler", spieler.name()));
                } else {
                    messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
                }
            }
        });
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        int position = args == null ? 0 : args.length;
        if (position <= 1) {
            return "Spieler";
        }
        if (position == 2) {
            return "Dauer";
        }
        String vorlage = tooltipFuerVorlage("timeout", vorschlag);
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
        if (args.length == 1) {
            return spielerVorschlaegeSmart(sender, args[0]);
        }
        if (args.length == 2) {
            return filtere(dauerVorschlaegeOhnePermFuer("timeout"), args[1]);
        }
        return grundOderEnde(sender, args, 2, "timeout");
    }
}
