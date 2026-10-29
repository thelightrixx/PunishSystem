package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.PlayerCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class UnbanIpCommand extends PunishCommand {

    private final IpBanService ipBanService;

    public UnbanIpCommand(MessageService messageService,
                          Konfiguration configManager,
                          ProtectionService protectionService,
                          PlayerCache playerCache,
                          IpBanService ipBanService,
                          IpBanRepository ipBanRepository,
                          AuftragsPlaner taskScheduler,
                          SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler, verzeichnis);
        this.ipBanService = Objects.requireNonNull(ipBanService, "Der IpBanService darf nicht null sein.");
        Objects.requireNonNull(ipBanRepository, "Das IpBanRepository darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_UNBANIP.node();
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

        Optional<de.lightrixx.punish.model.IpBanRecord> entfernt =
                ipBanService.ipEntbannen(kanonisch, ausfuehrerName(sender),
                        scope.scope(), herkunftVon(sender), silent);
        String anzeigeIp = PunishStatusCommand.maskeIp(sender, kanonisch);
        if (entfernt.isEmpty()) {
            messageService.senden(sender, MessageKeys.NICHT_GEBANNT_IP, Map.of("ip", anzeigeIp));
            try {
                var abdeckend = ipBanService.ipFindeAbdeckend(kanonisch, scope.scope());
                if (abdeckend.isPresent()) {
                    Map<String, String> hinweis = new java.util.HashMap<>();
                    hinweis.put("spieler", anzeigeIp);
                    hinweis.put("scope", abdeckend.get().scope().anzeige());
                    messageService.senden(sender, MessageKeys.SCOPE_ABDECKEND_HINWEIS, hinweis);
                }
            } catch (Exception e) {
                LOG.debug("Abdeckend-Check nach Unban-IP-Leerfund fehlgeschlagen.", e);
            }
            return;
        }
        messageService.senden(sender, MessageKeys.UNBAN_IP_ERFOLG, Map.of("ip", anzeigeIp));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Aktive IP-Sperre";
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
            List<IpBanRecord> aktiveIpBanns = sender != null
                    && sender.hatRecht(Permissions.PUNISH_ALTS.node())
                    ? ipBanService.findeAktiveIpBannsFuerTab()
                    : List.of();
            List<String> ips = new ArrayList<>();
            for (IpBanRecord bann : aktiveIpBanns) {
                if (ips.size() >= 25) {
                    break;
                }
                if (bann != null && bann.ip() != null) {
                    ips.add(bann.ip());
                }
            }

            List<String> namen = new ArrayList<>();
            for (IpBanRecord bann : aktiveIpBanns) {
                if (namen.size() >= 25) {
                    break;
                }
                if (bann == null || bann.ip() == null) {
                    continue;
                }
                Optional<CachedPlayer> ziel = zielAufloesenFuerTab(bann.ip());
                if (ziel.isPresent()) {
                    namen.add(ziel.get().name());
                }
            }
            ips.addAll(aktiveStrafNamen(25, namen));
            return filtere(ips.stream().distinct().toList(), args[0]);
        }
        return List.of();
    }
}
