package de.lightrixx.punish.command;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class UnmuteCommand extends PunishCommand {

    private final MuteService muteService;

    public UnmuteCommand(MessageService messageService,
                         Konfiguration configManager,
                         ProtectionService protectionService,
                         PlayerCache playerCache,
                         MuteService muteService,
                         SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.muteService = Objects.requireNonNull(muteService, "Der MuteService darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_UNMUTE.node();
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

        if (muteService.finden(spieler.uuid(), zielScope)
                .filter(m -> m.istAktiv(System.currentTimeMillis())).isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEMUTET, Map.of("spieler", spieler.name()));
            try {
                var abdeckend = muteService.findeAbdeckend(spieler.uuid(), zielScope);
                if (abdeckend.isPresent()) {
                    Map<String, String> hinweis = new java.util.HashMap<>();
                    hinweis.put("spieler", spieler.name());
                    hinweis.put("scope", abdeckend.get().scope().anzeige());
                    messageService.senden(sender, MessageKeys.SCOPE_ABDECKEND_HINWEIS, hinweis);
                }
            } catch (Exception e) {
                LOG.debug("Abdeckend-Check nach Unmute-Leerfund fehlgeschlagen.", e);
            }
            return;
        }

        boolean erfolg = muteService.entmuten(
                spieler.uuid(),
                spieler.name(),
                ausfuehrerName(sender),
                zielScope,
                herkunftVon(sender), silent);

        if (!erfolg) {
            messageService.senden(sender, MessageKeys.NICHT_GEMUTET, Map.of("spieler", spieler.name()));
            return;
        }
        messageService.senden(sender, MessageKeys.UNMUTE_ERFOLG, Map.of("spieler", spieler.name()));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Aktive Stummschaltung";
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

            List<String> gemutet = muteService.findeAktiveMutesFuerTab().stream()
                    .map(de.lightrixx.punish.model.MuteRecord::spielerName)
                    .toList();
            return filtere(aktiveStrafNamen(50, gemutet), args[0]);
        }
        return List.of();
    }
}
