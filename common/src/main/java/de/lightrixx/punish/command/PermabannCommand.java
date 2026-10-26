package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PermabannCommand extends PunishCommand {
    private final BanService banService;

    public PermabannCommand(MessageService messageService,
                            Konfiguration configManager,
                            ProtectionService protectionService,
                            PlayerCache playerCache,
                            BanService banService,
                            AuftragsPlaner taskScheduler,
                            SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler, verzeichnis);
        this.banService = Objects.requireNonNull(banService, "Der BanService darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_PERMABANN.node();
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
        Optional<CachedPlayer> ziel = zielAufloesen(bereinigt[0]);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", bereinigt[0]));
            return;
        }
        CachedPlayer spieler = ziel.get();
        de.lightrixx.punish.model.ServerScope wirksam = scope.scope();
        mitSchutz(sender, bereinigt, spieler, ProtectionService.IgnoreTyp.BAN, () -> {

            if (banService.istAbgedeckt(spieler.uuid(), wirksam)) {
                messageService.senden(sender, MessageKeys.BEREITS_GEBANNT, Map.of("spieler", spieler.name()));
                return;
            }

            String grund = grundAusArgumenten(bereinigt, 1, MessageKeys.DEFAULT_PERMABANN_GRUND);
            boolean erfolg = banService.permabann(
                    spieler.uuid(),
                    spieler.name(),
                    grund,
                    ausfuehrerName(sender),
                    wirksam, herkunftVon(sender), silent);
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
                if (banService.istAbgedeckt(spieler.uuid(), wirksam)) {
                    messageService.senden(sender, MessageKeys.BEREITS_GEBANNT, Map.of("spieler", spieler.name()));
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
        return "Grund";
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
            java.util.List<String> gemischt = new java.util.ArrayList<>(grundVorschlaege("ban"));
            gemischt.addAll(scopeVorschlaege("--"));
            gemischt.addAll(silentVorschlag(sender, ""));
            return filtere(gemischt, args[args.length - 1]);
        }
        return nurEnde(sender, args[args.length - 1]);
    }
}
