package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.service.IpMuteService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class UnmuteIpCommand extends PunishCommand {

    private final IpMuteService ipMuteService;

    public UnmuteIpCommand(MessageService messageService,
                           Konfiguration configManager,
                           ProtectionService protectionService,
                           PlayerCache playerCache,
                           IpMuteService ipMuteService,
                           SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.ipMuteService = Objects.requireNonNull(ipMuteService, "Der IpMuteService darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_UNMUTEIP.node();
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
        String eingabe = bereinigt[0];
        if (eingabe != null && eingabe.contains("/")) {
            messageService.senden(sender, MessageKeys.IP_UNGUELTIG, Map.of("ip", eingabe));
            return;
        }
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(eingabe);
        } catch (IllegalArgumentException direktFehler) {
            Optional<de.lightrixx.punish.model.CachedPlayer> ziel = zielAufloesen(eingabe);
            if (ziel.isEmpty()) {
                messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", eingabe));
                return;
            }
            String letzteIp = ziel.get().letzteIp();
            if (letzteIp == null || letzteIp.isBlank() || letzteIp.equalsIgnoreCase("unbekannt")) {
                messageService.senden(sender, MessageKeys.IP_NICHT_GEFUNDEN,
                        Map.of("spieler", ziel.get().name()));
                return;
            }
            try {
                kanonisch = IpBanRecord.kanonisieren(letzteIp);
            } catch (IllegalArgumentException e) {
                messageService.senden(sender, MessageKeys.IP_NICHT_GEFUNDEN,
                        Map.of("spieler", ziel.get().name()));
                return;
            }
        }

        boolean erfolg = ipMuteService.entmuten(kanonisch, ausfuehrerName(sender),
                scope.scope(), herkunftVon(sender), silent);
        String anzeigeIp = PunishStatusCommand.maskeIp(sender, kanonisch);
        if (!erfolg) {
            messageService.senden(sender, MessageKeys.NICHT_GEMUTET_IP, Map.of("ip", anzeigeIp));
            try {
                var abdeckend = ipMuteService.findeAbdeckend(kanonisch, scope.scope());
                if (abdeckend.isPresent()) {
                    Map<String, String> hinweis = new java.util.HashMap<>();
                    hinweis.put("spieler", anzeigeIp);
                    hinweis.put("scope", abdeckend.get().scope().anzeige());
                    messageService.senden(sender, MessageKeys.SCOPE_ABDECKEND_HINWEIS, hinweis);
                }
            } catch (Exception e) {
                LOG.debug("Abdeckend-Check nach Unmute-IP-Leerfund fehlgeschlagen.", e);
            }
            return;
        }
        messageService.senden(sender, MessageKeys.IP_UNMUTE_ERFOLG, Map.of("ip", anzeigeIp));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Aktive IP-Stummschaltung";
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
            List<String> ips = new ArrayList<>();
            if (sender != null && sender.hatRecht(Permissions.PUNISH_ALTS.node())) {
                for (var mute : ipMuteService.findeAktiveMutesFuerTab()) {
                    if (ips.size() >= 25) {
                        break;
                    }
                    if (mute != null && mute.ip() != null) {
                        ips.add(mute.ip());
                    }
                }
            }
            return filtere(ips.stream().distinct().toList(), args[0]);
        }
        return List.of();
    }
}
