package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BanCommand extends PunishCommand {

    private final BanService banService;
    private final TimeParser timeParser;

    public BanCommand(MessageService messageService,
                      Konfiguration configManager,
                      ProtectionService protectionService,
                      PlayerCache playerCache,
                      BanService banService,
                      TimeParser timeParser,
                      AuftragsPlaner taskScheduler,
                      SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler, verzeichnis);
        this.banService = Objects.requireNonNull(banService, "Der BanService darf nicht null sein.");
        this.timeParser = Objects.requireNonNull(timeParser, "Der TimeParser darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_BAN.node();
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {

        ScopeParser.Ergebnis scope = scopeAusArgs(args);
        if (blockiereBeiUnbekanntemScope(sender, scope)) {
            return;
        }
        boolean silent = silentAuswerten(sender, scope);
        String[] bereinigt = scope.bereinigteArgs();
        if (bereinigt.length < 1) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", ""));
            return;
        }
        verarbeitenBann(sender, bereinigt, scope.scope(), silent);
    }

    private void verarbeitenBann(BefehlsSender sender, String[] args,
                                 de.lightrixx.punish.model.ServerScope scope, boolean silent) {
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
        String herkunft = herkunftVon(sender);

        mitSchutz(sender, args, spieler, ProtectionService.IgnoreTyp.BAN, () -> {

            ParseResult vorab = timeParser.parsen(args[1]);
            if (vorab instanceof ParseResult.Permanent) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
                return;
            }
            if (!(vorab instanceof ParseResult.Geltzeit(long vorabMillis))
                    || !istAblaufMoeglich(vorabMillis)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long maximalMillis = configManager.banMaxMinuten() * 60000L;
            if (vorabMillis > maximalMillis) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.banMaxMinuten())));
                return;
            }
            long minimalMillis = configManager.banMinMinuten() * 60000L;
            if (vorabMillis < minimalMillis) {
                messageService.senden(sender, MessageKeys.LIMIT_UNTERSCHRITTEN,
                        Map.of("min", String.valueOf(configManager.banMinMinuten())));
                return;
            }

            if (banService.istAbgedeckt(spieler.uuid(), scope)) {
                messageService.senden(sender, MessageKeys.BEREITS_GEBANNT, Map.of("spieler", spieler.name()));
                return;
            }

            ParseResult parseResult = vorab;
            if (parseResult instanceof ParseResult.Geltzeit(long dauerMillis)) {

                String grund = grundAusArgumenten(args, 2, MessageKeys.DEFAULT_BAN_GRUND);
                try {
                    String roh = args.length <= 2 ? "" : String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                    sendeKuerzungsHinweisWennNoetig(sender, roh);
                } catch (Exception e) {
                    LOG.debug("Kürzungs-Hinweis übersprungen.", e);
                }
                boolean erfolg = banService.bannen(
                        spieler.uuid(),
                        spieler.name(),
                        dauerMillis,
                        grund,
                        ausfuehrerName(sender),
                        scope, herkunft, silent);
                if (!erfolg) {

                    try {
                        if (schutzFehlerSenden(sender, spieler, protectionService.pruefeSchutz(spieler.uuid(), ProtectionService.IgnoreTyp.BAN))) {
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
                    if (banService.istAbgedeckt(spieler.uuid(), scope)) {
                        messageService.senden(sender, MessageKeys.BEREITS_GEBANNT, Map.of("spieler", spieler.name()));
                    } else {
                        messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
                    }
                }
            } else if (parseResult instanceof ParseResult.Permanent) {
                messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
            } else {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
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
        String vorlage = tooltipFuerVorlage("ban", vorschlag);
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
            return filtere(dauerVorschlaegeOhnePermFuer("ban"), args[1]);
        }
        return grundOderEnde(sender, args, 2, "ban");
    }
}
