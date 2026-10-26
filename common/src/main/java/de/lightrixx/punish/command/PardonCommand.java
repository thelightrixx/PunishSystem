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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PardonCommand extends PunishCommand {

    private final BanService banService;
    private volatile de.lightrixx.punish.service.VerstossService verstossService;

    public void setVerstossService(de.lightrixx.punish.service.VerstossService verstossService) {
        this.verstossService = verstossService;
    }

    public PardonCommand(MessageService messageService,
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
        return Permissions.PUNISH_PARDON.node();
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

        var bestehender = banService.finden(spieler.uuid(), zielScope)
                .filter(bann -> bann.istAktiv(System.currentTimeMillis()));
        if (bestehender.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEBANNT, Map.of("spieler", spieler.name()));
            try {
                var abdeckend = banService.findeAbdeckend(spieler.uuid(), zielScope);
                if (abdeckend.isPresent()) {
                    Map<String, String> hinweis = new java.util.HashMap<>();
                    hinweis.put("spieler", spieler.name());
                    hinweis.put("scope", abdeckend.get().scope().anzeige());
                    messageService.senden(sender, MessageKeys.SCOPE_ABDECKEND_HINWEIS, hinweis);
                }
            } catch (Exception e) {
                LOG.debug("Abdeckend-Check nach Pardon-Leerfund fehlgeschlagen.", e);
            }
            return;
        }

        boolean warAutoBann = bestehender.get().autoBann();

        boolean erfolg = banService.entbannen(
                spieler.uuid(),
                spieler.name(),
                ausfuehrerName(sender),
                zielScope,
                herkunftVon(sender), silent);

        if (!erfolg) {
            messageService.senden(sender, MessageKeys.NICHT_GEBANNT, Map.of("spieler", spieler.name()));
            return;
        }

        messageService.senden(sender, MessageKeys.PARDON_ERFOLG, Map.of("spieler", spieler.name()));

        if (warAutoBann) {

            messageService.senden(sender, MessageKeys.PARDON_AUTO_HINWEIS, Map.of("spieler", spieler.name()));
        }
        try {
            de.lightrixx.punish.service.VerstossService verstoesse = verstossService;
            if (verstoesse != null) {
                verstoesse.autoBannErneutPruefen(spieler.uuid(), spieler.name(), ausfuehrerName(sender));
            }
        } catch (Exception e) {
            LOG.debug("Auto-Bann-Re-Evaluierung nach Pardon fehlgeschlagen.", e);
        }
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Aktive Sperre";
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

            List<String> gebannt = new ArrayList<>();
            for (de.lightrixx.punish.model.BanRecord bann : banService.alleBannsFuerTab()) {
                if (bann != null && bann.istAktiv(System.currentTimeMillis())) {
                    gebannt.add(bann.spielerName());
                }
                if (gebannt.size() >= 50) {
                    break;
                }
            }
            return filtere(aktiveStrafNamen(50, gebannt), args[0]);
        }
        return List.of();
    }
}
