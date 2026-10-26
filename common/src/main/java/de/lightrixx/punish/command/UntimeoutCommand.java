package de.lightrixx.punish.command;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.storage.PlayerCache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class UntimeoutCommand extends PunishCommand {

    private final TimeoutService timeoutService;

    public UntimeoutCommand(MessageService messageService,
                            Konfiguration configManager,
                            ProtectionService protectionService,
                            PlayerCache playerCache,
                            TimeoutService timeoutService,
                            SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.timeoutService = Objects.requireNonNull(timeoutService, "Der TimeoutService darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_UNTIMEOUT.node();
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {

        ScopeParser.Ergebnis scope = scopeAusArgsMitUnbekannt(args);
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
        de.lightrixx.punish.model.ServerScope zielScope = scope.scope();
        if (timeoutService.finden(spieler.uuid(), zielScope)
                .filter(t -> t.istAktiv(System.currentTimeMillis())).isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT, Map.of("spieler", spieler.name()));
            try {
                var abdeckend = timeoutService.findeAbdeckend(spieler.uuid(), zielScope);
                if (abdeckend.isPresent()) {
                    Map<String, String> hinweis = new java.util.HashMap<>();
                    hinweis.put("spieler", spieler.name());
                    hinweis.put("scope", abdeckend.get().scope().anzeige());
                    messageService.senden(sender, MessageKeys.SCOPE_ABDECKEND_HINWEIS, hinweis);
                }
            } catch (Exception e) {
                LOG.debug("Abdeckend-Check nach Untimeout-Leerfund fehlgeschlagen.", e);
            }
            return;
        }
        boolean erfolg = timeoutService.timeoutAufheben(
                spieler.uuid(), spieler.name(), ausfuehrerName(sender), zielScope, herkunftVon(sender),
                silent);
        if (!erfolg) {
            messageService.senden(sender, MessageKeys.NICHT_IM_TIMEOUT, Map.of("spieler", spieler.name()));
            return;
        }
        messageService.senden(sender, MessageKeys.UNTIMEOUT_ERFOLG, Map.of("spieler", spieler.name()));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Aktiver Timeout";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args[args.length - 1];
        if (letztes != null && letztes.startsWith("--")) {
            return scopeVorschlaegeMitUnbekannt(letztes);
        }
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        if (args.length == 1) {

            List<String> imTimeout = timeoutService.findeAktiveTimeoutsFuerTab().stream()
                    .map(de.lightrixx.punish.model.TimeoutRecord::spielerName)
                    .toList();
            return filtere(aktiveStrafNamen(50, imTimeout), args[0]);
        }
        return List.of();
    }
}
